package com.stockscreener.notification;

import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.util.Map;

/**
 * 메신저 웹훅. Slack 형식({@code {"text": …}})으로 보낸다.
 *
 * <p>IMPORTANT — Discord도 이 구현 하나로 끝난다. <b>Discord 웹훅 URL 끝에 {@code /slack}을
 * 붙이면</b> Slack 형식을 그대로 받아준다. 붙이지 않으면 Discord는 {@code content} 키를
 * 요구하므로 400이 오고, 그 실패는 채널에 아무것도 뜨지 않는 것으로만 나타난다.
 * 어느 메신저를 쓰든 페이로드 분기를 만들지 않는 것이 이 선택의 목적이다(ADR-0025).
 *
 * <p>실패하면 <b>던진다</b> — {@link AlertChannel}의 계약이다. 잡아서 무해하게 만드는 일은
 * {@link CompositeAlertPort}가 한다.
 */
public class WebhookAlertChannel implements AlertChannel {

    private final AlertProperties.Webhook properties;
    private final RestClient restClient;

    public WebhookAlertChannel(AlertProperties.Webhook properties) {
        this.properties = properties;
        this.restClient = build(properties);
    }

    @Override
    public String name() {
        return "webhook";
    }

    @Override
    public boolean isConfigured() {
        return properties.getUrl() != null && !properties.getUrl().isBlank();
    }

    /**
     * 운영자 알림만 받는다.
     *
     * <p>사용자 대상 알림(워치리스트)을 여기로 보내지 않는 이유는 <b>메신저 채널이 하나뿐</b>이기
     * 때문이다. 보내면 지인의 관심종목이 운영자 채널에 뜬다. 사용자 알림은 메일로만 간다.
     */
    @Override
    public boolean accepts(AlertEvent event) {
        return event.isForOperator() && event.severity().atLeast(properties.getMinSeverity());
    }

    /**
     * 본문 길이 상한.
     *
     * <p>IMPORTANT — <b>넘으면 그 알림은 아예 안 간다.</b> 2026-09-18 Q4V 적용에서 무효화 종목 목록이 실린 알림 둘이
     * {@code 400 {"text": ["Must be 2000 or fewer in length."]}}로 거절됐다(574종목·102종목). 로그에는 남지만
     * <b>운영자에게 가는 경로는 조용히 실패</b>했고, 그 알림이 바로 「백필이 남았다」였다.
     *
     * <p>Slack의 {@code text}도 실질 상한이 있고 채널마다 다르다. 가장 낮은 실측값(2,000자)에 맞추고,
     * <b>자른 사실을 본문에 남긴다</b> — 잘렸다는 것을 모르면 목록이 짧다고 읽는다.
     */
    private static final int MAX_TEXT_LENGTH = 2000;

    static String clamp(String text) {
        if (text.length() <= MAX_TEXT_LENGTH) {
            return text;
        }
        String suffix = "…(%d자 잘렸다 — 전체는 실행 로그와 batch_execution의 note에 있다)"
                .formatted(text.length() - MAX_TEXT_LENGTH);
        return text.substring(0, Math.max(0, MAX_TEXT_LENGTH - suffix.length())) + suffix;
    }

    @Override
    public void deliver(AlertEvent event) {
        restClient.post()
                .uri(properties.getUrl())
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("text", clamp(AlertRendering.plainText(event))))
                .retrieve()
                // 4xx·5xx면 예외가 올라간다. 그것이 이 채널의 계약이다 —
                // Composite이 잡아 로그로 남기고 다른 채널을 계속 시도한다.
                .toBodilessEntity();
    }

    /**
     * MUST: 타임아웃을 명시한다. 기본값은 무한대이고, 응답하지 않는 웹훅 하나가 배치 스레드를
     * 영원히 붙잡으면 그날 밤 배치가 통째로 사라진다 — 실패로도 기록되지 않는다.
     * {@code ExternalApiClientFactory}가 같은 이유로 같은 일을 한다.
     *
     * <p>baseUrl을 걸지 않는 이유는 URL 자체가 자격증명이라 요청 시점에만 다루기 위해서다.
     * 클라이언트에 박아 두면 디버그 로그나 오류 메시지에 통째로 실릴 여지가 생긴다.
     */
    private static RestClient build(AlertProperties.Webhook properties) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout((int) properties.getConnectTimeout().toMillis());
        factory.setReadTimeout((int) properties.getReadTimeout().toMillis());
        return RestClient.builder().requestFactory(factory).build();
    }
}
