package com.stockscreener.notification;

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 실제 소켓을 열어 웹훅 페이로드와 실패 동작을 검증한다. 자격증명이 필요 없다.
 *
 * <p>{@code RestClient}를 목킹하지 않는 이유는 {@code ExternalApiClientFactoryTest}와 같다 —
 * 목은 상태코드 처리와 직렬화 결과를 전혀 검증하지 못하는데, 이 채널에서 틀릴 수 있는 것이
 * 바로 그 둘이다(Slack이 받아주는 키 이름, 4xx일 때 예외가 나는가).
 */
class WebhookAlertChannelTest {

    private MockWebServer server;

    @BeforeEach
    void startServer() throws IOException {
        server = new MockWebServer();
        server.start();
    }

    @AfterEach
    void stopServer() throws IOException {
        server.shutdown();
    }

    @Test
    @DisplayName("Slack 형식으로 보낸다 — 본문 키가 text이고 제목·본문·컨텍스트가 함께 실린다")
    void sendsSlackShapedPayload() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(200).setBody("ok"));
        WebhookAlertChannel channel = channel(AlertSeverity.WARNING);

        channel.deliver(AlertEvent.toOperator(
                AlertSeverity.WARNING, "daily-update 부분 실패",
                "348종목 성공, 2종목 실패 (대상일 2026-08-05)", "daily-update:2026-08-05",
                Map.of("executionId", "12")));

        RecordedRequest request = server.takeRequest();
        assertThat(request.getMethod()).isEqualTo("POST");
        String body = request.getBody().readUtf8();

        // 키 이름이 이 채널의 유일한 계약이다. Discord도 URL 끝에 /slack을 붙이면 이것을 받는다.
        assertThat(body).contains("\"text\"");
        assertThat(body).contains("[WARNING] daily-update 부분 실패");
        assertThat(body).contains("348종목 성공");
        // executionId는 관리자 화면에서 그 실행을 찾는 열쇠다. 빠지면 알림만 보고는 추적할 수 없다.
        assertThat(body).contains("executionId");
    }

    @Test
    @DisplayName("긴 본문은 잘라서 보낸다 — 2026-09-18 적용에서 574종목 목록이 실린 알림이 400으로 거절됐다")
    void truncatesLongBodySoTheAlertStillArrives() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(200).setBody("ok"));
        WebhookAlertChannel channel = channel(AlertSeverity.WARNING);
        String tickers = java.util.stream.IntStream.range(0, 600)
                .mapToObj(i -> "TICK" + i).collect(java.util.stream.Collectors.joining(", "));

        channel.deliver(AlertEvent.toOperator(AlertSeverity.WARNING, "재무 무효화 — 백필이 남았다",
                "574종목의 재무를 다시 썼다: " + tickers, "financial-refill:invalidated"));

        String body = server.takeRequest().getBody().readUtf8();
        String text = body.substring(body.indexOf("\"text\"") + 8);
        assertThat(text.length()).isLessThanOrEqualTo(2100);
        assertThat(body).contains("재무 무효화");
        assertThat(body).contains("잘렸다");
    }

    @Test
    @DisplayName("4xx·5xx면 던진다 — 삼키면 Composite이 '아무도 못 받았다'를 알 수 없다")
    void throwsOnErrorStatus() {
        server.enqueue(new MockResponse().setResponseCode(400).setBody("invalid_payload"));
        WebhookAlertChannel channel = channel(AlertSeverity.WARNING);

        assertThatThrownBy(() -> channel.deliver(
                AlertEvent.toOperator(AlertSeverity.ERROR, "제목", "본문", "key")))
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    @DisplayName("URL이 비어 있으면 비활성이다")
    void inactiveWithoutUrl() {
        AlertProperties.Webhook properties = new AlertProperties.Webhook();
        assertThat(new WebhookAlertChannel(properties).isConfigured()).isFalse();

        properties.setUrl("https://hooks.example.com/x");
        assertThat(new WebhookAlertChannel(properties).isConfigured()).isTrue();
    }

    @Test
    @DisplayName("임계 미만은 받지 않는다 — INFO 성공 통지가 매일 채널을 덮으면 실패도 함께 묻힌다")
    void rejectsBelowThreshold() {
        WebhookAlertChannel channel = channel(AlertSeverity.WARNING);

        assertThat(channel.accepts(AlertEvent.toOperator(AlertSeverity.INFO, "t", "b", "k"))).isFalse();
        assertThat(channel.accepts(AlertEvent.toOperator(AlertSeverity.WARNING, "t", "b", "k"))).isTrue();
        assertThat(channel.accepts(AlertEvent.toOperator(AlertSeverity.CRITICAL, "t", "b", "k"))).isTrue();
    }

    @Test
    @DisplayName("사용자 대상 알림은 받지 않는다 — 메신저 채널이 하나뿐이라 지인의 관심종목이 운영자 채널에 뜬다")
    void rejectsUserTargetedEvents() {
        WebhookAlertChannel channel = channel(AlertSeverity.INFO);

        AlertEvent toUser = AlertEvent.toUser(7L, AlertSeverity.CRITICAL, "t", "b", "k");
        assertThat(channel.accepts(toUser)).isFalse();
    }

    private WebhookAlertChannel channel(AlertSeverity minSeverity) {
        AlertProperties.Webhook properties = new AlertProperties.Webhook();
        properties.setUrl(server.url("/hook").toString());
        properties.setMinSeverity(minSeverity);
        properties.setConnectTimeout(Duration.ofSeconds(2));
        properties.setReadTimeout(Duration.ofSeconds(2));
        return new WebhookAlertChannel(properties);
    }
}
