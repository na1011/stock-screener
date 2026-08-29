package com.stockscreener.pipeline.datasource.us;

import com.stockscreener.pipeline.datasource.DataSourceException;
import com.stockscreener.pipeline.datasource.MarketSession;
import com.stockscreener.pipeline.datasource.http.DataSourceProperties;
import com.stockscreener.pipeline.datasource.http.ExternalApiClientFactory;
import com.stockscreener.pipeline.datasource.http.TokenBucketRateLimiter;

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link AlpacaMarketCalendarAdapter}의 봉투 판별·매핑·요청 모양을 잠근다.
 *
 * <p>MockWebServer를 쓰는 이유는 {@code RestClient}를 목으로 바꾸면 <b>봉투 해석도 상태코드
 * 처리도 검증되지 않기</b> 때문이다. 픽스처는 탐침 응답을 녹화한 것이고, 하나만
 * ({@code calendar-crosses-utc-midnight.json}) 규칙을 잠그려고 일부러 합성했다.
 */
class AlpacaMarketCalendarAdapterTest {

    private static final LocalDate FROM = LocalDate.of(2024, 7, 1);
    private static final LocalDate TO = LocalDate.of(2024, 7, 5);
    private static final ZoneId NEW_YORK = ZoneId.of("America/New_York");

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
    @DisplayName("세션을 그대로 옮긴다 — 조기폐장일은 core_end가 앞당겨진 채로 온다")
    void mapsSessions() throws Exception {
        enqueue(200, fixture("calendar-ok.json"));

        List<MarketSession> sessions = adapter().fetchSessions("NYSE", FROM, TO);

        assertThat(sessions).extracting(MarketSession::sessionDate)
                .containsExactly(LocalDate.of(2024, 7, 2), LocalDate.of(2024, 7, 3),
                        LocalDate.of(2024, 7, 5));
        assertThat(sessions).extracting(MarketSession::exchangeCode)
                .containsOnly("NYSE");

        // 조기폐장은 벽시계 시각으로만 드러난다. 절대 시각만 보면 그럴듯하다.
        assertThat(sessions.get(1).coreEnd().atZone(NEW_YORK).toLocalTime())
                .as("2024-07-03은 13:00에 닫는다")
                .isEqualTo(LocalTime.of(13, 0));
        assertThat(sessions.get(0).coreEnd().atZone(NEW_YORK).toLocalTime())
                .isEqualTo(LocalTime.of(16, 0));
        assertThat(sessions.get(0).coreStart())
                .isEqualTo(Instant.parse("2024-07-02T13:30:00Z"));
    }

    @Test
    @DisplayName("세션 날짜를 시각에서 유도하지 않는다 — UTC로 환산하면 뉴욕 오후가 다음 날로 밀린다")
    void keepsSourceDateEvenWhenHoursCrossUtcMidnight() throws Exception {
        // 합성 픽스처다. core_end가 UTC로는 다음 날(2024-07-03T01:30Z)이지만
        // 시장 로컬 날짜는 07-02다. 시각에서 날짜를 만들면 여기서만 갈린다.
        enqueue(200, fixture("calendar-crosses-utc-midnight.json"));

        List<MarketSession> sessions = adapter().fetchSessions("NYSE", FROM, TO);

        assertThat(sessions).hasSize(1);
        assertThat(sessions.get(0).coreEnd()).isEqualTo(Instant.parse("2024-07-03T01:30:00Z"));
        assertThat(sessions.get(0).sessionDate())
                .as("소스가 준 date를 그대로 써야 한다")
                .isEqualTo(LocalDate.of(2024, 7, 2));
    }

    @Test
    @DisplayName("빈 배열은 정상이다 — 휴일만 든 구간이 실재한다")
    void emptyCalendarIsNotAnError() throws Exception {
        enqueue(200, fixture("calendar-empty.json"));

        assertThat(adapter().fetchSessions("NYSE", FROM, TO)).isEmpty();
    }

    @Test
    @DisplayName("calendar 키가 없으면 MALFORMED다 — 0건과 스펙 변경은 다른 사건이다")
    void missingEnvelopeKeyIsMalformed() throws Exception {
        enqueue(200, fixture("calendar-no-key.json"));

        assertThat(kindOf(() -> adapter().fetchSessions("NYSE", FROM, TO)))
                .isEqualTo(DataSourceException.Kind.MALFORMED);
    }

    @Test
    @DisplayName("calendar가 배열이 아니면 MALFORMED다")
    void nonArrayEnvelopeIsMalformed() throws Exception {
        enqueue(200, fixture("calendar-not-array.json"));

        assertThat(kindOf(() -> adapter().fetchSessions("NYSE", FROM, TO)))
                .isEqualTo(DataSourceException.Kind.MALFORMED);
    }

    @Test
    @DisplayName("401 본문이 HTML이어도 AUTH다 — 이 엔드포인트는 JSON을 주지 않는다")
    void htmlUnauthorizedIsAuth() {
        server.enqueue(new MockResponse()
                .setResponseCode(401)
                .setHeader("Content-Type", "text/html")
                .setBody("<html><head><title>401 Authorization Required</title></head></html>"));

        assertThat(kindOf(() -> adapter().fetchSessions("NYSE", FROM, TO)))
                .as("TRANSIENT면 키가 틀린 것이 일시 오류로 기록된다")
                .isEqualTo(DataSourceException.Kind.AUTH);
    }

    @Test
    @DisplayName("소스가 400을 주면 MALFORMED다 — 재시도해도 안 되는 것을 재시도로 적지 않는다")
    void badRequestIsMalformed() {
        enqueue(400, "{\"message\":\"invalid market: nyse\"}");

        assertThat(kindOf(() -> adapter().fetchSessions("NYSE", FROM, TO)))
                .isEqualTo(DataSourceException.Kind.MALFORMED);
    }

    @Test
    @DisplayName("정규장 종료가 개장보다 이르면 종목 실패로 접는다 — 던져 올리면 잡이 죽는다")
    void invertedHoursAreMalformedNotRuntime() throws Exception {
        enqueue(200, fixture("calendar-inverted-hours.json"));

        assertThat(kindOf(() -> adapter().fetchSessions("NYSE", FROM, TO)))
                .isEqualTo(DataSourceException.Kind.MALFORMED);
    }

    @Test
    @DisplayName("date가 없으면 MALFORMED다 — 시각에서 만들어 채우지 않는다")
    void missingDateIsMalformed() throws Exception {
        enqueue(200, fixture("calendar-missing-date.json"));

        assertThat(kindOf(() -> adapter().fetchSessions("NYSE", FROM, TO)))
                .isEqualTo(DataSourceException.Kind.MALFORMED);
    }

    @Test
    @DisplayName("뒤집힌 창은 콜을 태우지 않는다")
    void invertedWindowBurnsNoCall() {
        assertThat(kindOf(() -> adapter().fetchSessions("NYSE", TO, FROM)))
                .isEqualTo(DataSourceException.Kind.MALFORMED);
        assertThat(server.getRequestCount()).isZero();
    }

    @Test
    @DisplayName("모르는 거래소는 콜을 태우지 않고 NOT_FOUND다 — 소문자도 소스가 400을 준다")
    void unknownExchangeBurnsNoCall() {
        assertThat(kindOf(() -> adapter().fetchSessions("nyse", FROM, TO)))
                .isEqualTo(DataSourceException.Kind.NOT_FOUND);
        assertThat(server.getRequestCount()).isZero();
    }

    @Test
    @DisplayName("start·end를 둘 다 실어 보낸다 — end를 빼면 절단이 아니라 1주짜리 정상 응답이 온다")
    void sendsBothWindowBounds() throws Exception {
        enqueue(200, fixture("calendar-ok.json"));

        adapter().fetchSessions("NYSE", FROM, TO);

        String query = query(server.takeRequest());
        assertThat(query).contains("/v3/calendar/NYSE");
        assertThat(query).contains("start=2024-07-01");
        assertThat(query).contains("end=2024-07-05");
    }

    // ── 도구 ─────────────────────────────────────────────────────────────

    /**
     * MUST: {@code call}을 <b>한 번만</b> 부른다. 두 번 부르면 두 번째가 큐에 남은 응답이 없어
     * 읽기 타임아웃까지 매달리고, 그 타임아웃이 {@code TRANSIENT}인 {@code DataSourceException}이라
     * <b>단언이 헛돈다.</b> 실제로 그렇게 짜서 테스트 하나가 30초씩 걸렸다.
     */
    private DataSourceException.Kind kindOf(ThrowingCall call) {
        Throwable thrown = org.assertj.core.api.Assertions.catchThrowable(call::run);
        assertThat(thrown).isInstanceOf(DataSourceException.class);
        return ((DataSourceException) thrown).kind();
    }

    @FunctionalInterface
    private interface ThrowingCall {
        void run() throws Exception;
    }

    private AlpacaMarketCalendarAdapter adapter() {
        return new AlpacaMarketCalendarAdapter(new ExternalApiClientFactory(), properties(),
                new TokenBucketRateLimiter("alpaca", 0, 0), server.url("/").toString());
    }

    private DataSourceProperties properties() {
        DataSourceProperties properties = new DataSourceProperties();
        properties.getAlpaca().setApiKey("test-key");
        properties.getAlpaca().setSecretKey("test-secret");
        return properties;
    }

    private void enqueue(int status, String body) {
        server.enqueue(new MockResponse()
                .setResponseCode(status)
                .setHeader("Content-Type", "application/json;charset=UTF-8")
                .setBody(body));
    }

    private static String query(RecordedRequest request) {
        return URLDecoder.decode(request.getPath(), StandardCharsets.UTF_8);
    }

    private static String fixture(String name) {
        try {
            return new String(new ClassPathResource("fixtures/alpaca/" + name)
                    .getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("fixture를 읽을 수 없다: " + name, e);
        }
    }
}
