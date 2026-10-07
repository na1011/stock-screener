package com.stockscreener.pipeline.datasource.us;

import com.stockscreener.pipeline.datasource.DataSourceException;
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
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** {@link SecTickerCikCache}의 계약. 이 클래스의 javadoc이 그 엔드포인트의 정본이다. */
class SecTickerCikCacheTest {

    /** 다시 받기 대기를 실제로 자지 않는다 — 대기 자체는 {@link #waitsBridgeAShortOutage}가 본다. */
    private static final com.stockscreener.pipeline.datasource.http.RefetchWaits NO_WAIT =
            new com.stockscreener.pipeline.datasource.http.RefetchWaits(new long[] {2_000, 10_000}, millis -> { });

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
    @DisplayName("점을 하이픈으로 바꿔 주식 클래스를 붙인다")
    void normalizesShareClassTickers() throws Exception {
        enqueue(200, fixture());
        SecTickerCikCache cache = cache(1);

        assertThat(cache.findCik("FIX")).contains(1L);
        assertThat(cache.findCik("BRK.A")).as("소스는 BRK-A로 싣는다").contains(1067983L);
        assertThat(cache.findCik("BRK.B")).as("주식 클래스 둘이 같은 CIK다").contains(1067983L);
    }

    @Test
    @DisplayName("우선주는 붙이지 않는다 — 붙이면 발행사 재무가 우선주 행에 실린다")
    void doesNotMapPreferredTickers() throws Exception {
        enqueue(200, fixture());
        assertThat(cache(1).findCik("KIM.PRL"))
                .as("`.PR<X>` → `-P<X>`로 열면 365종목이 더 붙지만 그 CIK가 보통주와 같다")
                .isEmpty();
    }

    @Test
    @DisplayName("모르는 티커는 예외가 아니라 빈 값이다 — 8,504 중 1,815가 정상적으로 여기 든다")
    void unknownTickerIsEmptyNotAnException() throws Exception {
        enqueue(200, fixture());
        assertThat(cache(1).findCik("NOPE")).isEmpty();
    }

    @Test
    @DisplayName("CIK를 10자리로 채운다 — 안 채우면 404이고 그것이 404의 최다 원인이다")
    void padsCikToTenDigits() {
        assertThat(SecTickerCikCache.padded(320193L)).isEqualTo("CIK0000320193");
        assertThat(SecTickerCikCache.padded(1_067_983L)).isEqualTo("CIK0001067983");
    }

    @Test
    @DisplayName("한 번만 받는다 — 종목마다 3.5MB를 다시 받지 않는다")
    void loadsOnce() throws Exception {
        enqueue(200, fixture());
        SecTickerCikCache cache = cache(1);
        cache.findCik("FIX");
        cache.findCik("BRK.A");
        assertThat(server.getRequestCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("신원 표시를 헤더로 보낸다 — 없으면 SEC가 미분류 봇으로 차단한다")
    void sendsUserAgent() throws Exception {
        enqueue(200, fixture());
        cache(1).findCik("FIX");
        RecordedRequest request = server.takeRequest();
        assertThat(request.getHeader("User-Agent")).isEqualTo("Test Runner test@example.com");
        assertThat(request.getPath()).isEqualTo("/files/company_tickers_exchange.json");
    }

    @Test
    @DisplayName("행이 하한에 못 미치면 부분 응답 — 받기 실패(TRANSIENT)다 · 매핑을 반쪽으로 만들지 않는다(ADR-0174)")
    void rejectsPartialResponse() throws Exception {
        // 옛 단언은 MALFORMED였다 — 덜 온 파일은 소스의 답이 아니라 받기 실패다(종목 층에서 「답 못 받음」).
        enqueue(200, fixture());
        enqueue(200, fixture());
        enqueue(200, fixture());
        assertThatThrownBy(() -> cache(999).freshSnapshot())
                .isInstanceOf(DataSourceException.class)
                .hasFieldOrPropertyWithValue("kind", DataSourceException.Kind.TRANSIENT)
                .hasMessageContaining("부분 응답");
        assertThat(server.getRequestCount()).as("대기를 두고 두 번 다시 받는다").isEqualTo(3);
    }

    @Test
    @DisplayName("fields가 없으면 MALFORMED다(명부) — 종목 조회는 판을 못 얻어 TRANSIENT로 감싼다 · 열 순서를 위치로 박지 않는다")
    void rejectsUnknownShape() {
        String body = "{\"data\": [[1, \"이름\", \"FIX\", \"Nasdaq\"]]}";
        enqueue(200, body);
        enqueue(200, body);
        enqueue(200, body);
        SecTickerCikCache cache = cache(1);
        assertThatThrownBy(cache::freshSnapshot)
                .isInstanceOf(DataSourceException.class)
                .hasFieldOrPropertyWithValue("kind", DataSourceException.Kind.MALFORMED);
        assertThatThrownBy(() -> cache.findCik("FIX"))
                .as("종목 소비 경로는 AUTH·RATE_LIMITED만 원래 종류 — 판이 없으면 「모른다」가 아니라 「못 받았다」다")
                .isInstanceOf(DataSourceException.class)
                .hasFieldOrPropertyWithValue("kind", DataSourceException.Kind.TRANSIENT);
    }

    @Test
    @DisplayName("200인데 본문이 없으면 스펙 변경이 아니라 받기 실패(TRANSIENT)다 — 구조 이상과 가른다")
    void emptyBodyIsTransient() {
        enqueue(200, "");
        enqueue(200, "");
        enqueue(200, "");
        assertThatThrownBy(() -> cache(1).freshSnapshot())
                .isInstanceOf(DataSourceException.class)
                .hasFieldOrPropertyWithValue("kind", DataSourceException.Kind.TRANSIENT);
    }

    @Test
    @DisplayName("신원 표시가 비면 호출 전에 AUTH로 멈춘다")
    void requiresUserAgent() {
        DataSourceProperties properties = new DataSourceProperties();
        ExternalApiClientFactory factory = new ExternalApiClientFactory();
        SecTickerCikCache cache = new SecTickerCikCache(factory, properties,
                new TokenBucketRateLimiter("sec-edgar", 0, 0), server.url("/").toString(), 1);

        assertThatThrownBy(() -> cache.findCik("FIX"))
                .isInstanceOf(DataSourceException.class)
                .hasFieldOrPropertyWithValue("kind", DataSourceException.Kind.AUTH);
        assertThat(server.getRequestCount()).isZero();
    }

    @Test
    @DisplayName("SEC가 적은 거래소를 함께 든다 — 정규화도 같다(ADR-0171)")
    void carriesExchange() throws Exception {
        enqueue(200, fixture());
        SecTickerCikCache.Snapshot snapshot = cache(1).snapshot();
        assertThat(snapshot.exchangeOf("FIX")).contains("Nasdaq");
        assertThat(snapshot.exchangeOf("BRK.B")).as("BRK-B로 싣는다").contains("NYSE");
        assertThat(snapshot.exchangeOf("NOPE")).isEmpty();
    }

    @Test
    @DisplayName("거래소 칸이 없으면 MALFORMED다 — 빠지면 숨은 상장이 전부 「거래소 모름」으로 조용히 빠진다")
    void rejectsMissingExchangeField() {
        for (int i = 0; i < 3; i++) {
            enqueue(200, "{\"fields\":[\"cik\",\"name\",\"ticker\"],\"data\":[[1,\"이름\",\"FIX\"]]}");
        }
        assertThatThrownBy(() -> cache(1).freshSnapshot())
                .isInstanceOf(DataSourceException.class)
                .hasFieldOrPropertyWithValue("kind", DataSourceException.Kind.MALFORMED);
    }

    @Test
    @DisplayName("받은 지 하루가 지나면 다시 받는다 — 하루 안이면 안 부른다")
    void refreshesAfterADay() throws Exception {
        MutableClock clock = new MutableClock(Instant.parse("2026-10-10T11:00:00Z"));
        enqueue(200, fixture());
        enqueue(200, "{\"fields\":[\"cik\",\"name\",\"ticker\",\"exchange\"],\"data\":[[99,\"새 판\",\"FIX\",\"NYSE\"]]}");
        SecTickerCikCache cache = cache(1, clock);

        assertThat(cache.findCik("FIX")).contains(1L);
        clock.advance(Duration.ofHours(23));
        assertThat(cache.findCik("FIX")).as("하루 안").contains(1L);
        assertThat(server.getRequestCount()).isEqualTo(1);
        clock.advance(Duration.ofHours(2));
        assertThat(cache.findCik("FIX")).as("하루가 지나면 새 판").contains(99L);
        assertThat(server.getRequestCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("한 번 잡은 판은 갱신이 끼어도 그대로다 — 한 실행이 두 판을 섞지 않는다")
    void snapshotIsPinned() throws Exception {
        MutableClock clock = new MutableClock(Instant.parse("2026-10-10T11:00:00Z"));
        enqueue(200, fixture());
        enqueue(200, "{\"fields\":[\"cik\",\"name\",\"ticker\",\"exchange\"],\"data\":[[99,\"새 판\",\"FIX\",\"NYSE\"]]}");
        SecTickerCikCache cache = cache(1, clock);
        SecTickerCikCache.Snapshot held = cache.snapshot();

        clock.advance(Duration.ofHours(25));
        assertThat(cache.findCik("FIX")).contains(99L);
        assertThat(held.findCik("FIX")).as("잡아 둔 판은 옛 값 그대로").contains(1L);
        assertThat(held.exchangeOf("FIX")).contains("Nasdaq");
    }

    @Test
    @DisplayName("다시 받기가 실패하면(즉시 1회 재시도 뒤) 명부 판은 던지고 종목 판은 낡은 판을 낸다 — 10분은 다시 안 부른다")
    void refreshFailureContractDependsOnTheConsumer() throws Exception {
        MutableClock clock = new MutableClock(Instant.parse("2026-10-10T11:00:00Z"));
        enqueue(200, fixture());
        enqueue(503, "{}");
        enqueue(503, "{}");
        enqueue(503, "{}");
        enqueue(200, "{\"fields\":[\"cik\",\"name\",\"ticker\",\"exchange\"],\"data\":[[99,\"새 판\",\"FIX\",\"NYSE\"]]}");
        SecTickerCikCache cache = cache(1, clock);
        assertThat(cache.findCik("FIX")).contains(1L);
        assertThat(cache.snapshot().fresh()).isTrue();

        clock.advance(Duration.ofHours(25));
        assertThatThrownBy(cache::freshSnapshot)
                .as("명부는 낡은 라벨로 짓지 않는다(ADR-0008)")
                .isInstanceOf(DataSourceException.class)
                .hasMessageContaining("HTTP 503");
        assertThat(cache.findCik("FIX")).as("종목 단위는 옛 판에 있는 티커를 계속 찾는다").contains(1L);
        assertThat(cache.snapshot().fresh()).as("그 판은 낡은 판이다").isFalse();
        assertThatThrownBy(() -> cache.findCik("NOPE"))
                .as("낡은 판의 부재는 소스의 답이 아니다 — 「모름」 대신 TRANSIENT(ADR-0174)")
                .isInstanceOf(DataSourceException.class)
                .hasFieldOrPropertyWithValue("kind", DataSourceException.Kind.TRANSIENT)
                .hasMessageContaining("미매핑");
        assertThatThrownBy(cache::freshSnapshot).hasMessageContaining("HTTP 503");
        assertThat(server.getRequestCount()).as("실패 뒤 10분 안은 다시 안 부른다 — 종목 루프가 종목마다 받지 않게")
                .isEqualTo(4);

        clock.advance(Duration.ofMinutes(11));
        assertThat(cache.freshSnapshot().findCik("FIX")).as("10분이 지나면 다시 묻는다").contains(99L);
        assertThat(server.getRequestCount()).isEqualTo(5);
    }

    @Test
    @DisplayName("첫 적재 실패도 즉시 1회 재시도 뒤 10분 기억한다 — 그동안 종목 조회는 콜 없이 TRANSIENT(hang이면 종목마다 60초)")
    void firstLoadFailureIsRememberedAfterOneRetry() throws Exception {
        MutableClock clock = new MutableClock(Instant.parse("2026-10-10T11:00:00Z"));
        enqueue(503, "{}");
        enqueue(503, "{}");
        enqueue(503, "{}");
        enqueue(200, fixture());
        SecTickerCikCache cache = cache(1, clock);
        assertThatThrownBy(() -> cache.findCik("FIX"))
                .isInstanceOf(DataSourceException.class)
                .hasFieldOrPropertyWithValue("kind", DataSourceException.Kind.TRANSIENT)
                .hasMessageContaining("HTTP 503");
        assertThatThrownBy(cache::freshSnapshot).hasMessageContaining("HTTP 503");
        assertThatThrownBy(() -> cache.findCik("BRK.A")).hasMessageContaining("HTTP 503");
        assertThat(server.getRequestCount()).as("세 번(대기 둔 재시도 둘 포함) 뒤로는 기억 — 콜 0").isEqualTo(3);

        clock.advance(Duration.ofMinutes(11));
        assertThat(cache.findCik("FIX")).contains(1L);
        assertThat(server.getRequestCount()).isEqualTo(4);
    }

    @Test
    @DisplayName("순간 실패 하나는 즉시 재시도가 흡수한다 — 기억으로 넘어가지 않는다")
    void singleBlipIsAbsorbedByTheImmediateRetry() throws Exception {
        enqueue(503, "{}");
        enqueue(200, fixture());
        SecTickerCikCache cache = cache(1, new MutableClock(Instant.parse("2026-10-10T11:00:00Z")));
        assertThat(cache.findCik("FIX")).contains(1L);
        assertThat(cache.snapshot().fresh()).isTrue();
        assertThat(server.getRequestCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("IMPORTANT — 1초짜리 끊김(503·503 → 1초 뒤 200)은 대기 둔 재시도가 넘는다 — 첫 조회가 3초 안에 성공하고 10분 기억으로 안 간다")
    void waitsBridgeAShortOutage() throws Exception {
        assertThat(bridges(new long[] {2_000, 10_000})).isTrue();
    }

    /**
     * 끊김이 1초 이어지는 서버에 첫 조회를 하고, 그 조회가 성공했는지(대기 합 3초 안에). 대기는 가짜 시계를 민다 — 실제로 자지
     * 않는다. 대조군은 대기 없는 재시도다({@code {0, 0}} — 시도가 다 0초에 몰려 끊김 안에서 끝난다).
     */
    private boolean bridges(long[] waitMillis) throws Exception {
        java.util.concurrent.atomic.AtomicLong elapsed = new java.util.concurrent.atomic.AtomicLong();
        String body = fixture();
        server.setDispatcher(new okhttp3.mockwebserver.Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest request) {
                return elapsed.get() < 1_000
                        ? new MockResponse().setResponseCode(503).setBody("<html>down</html>")
                        : new MockResponse().setResponseCode(200)
                                .setHeader("Content-Type", "application/json").setBody(body);
            }
        });
        DataSourceProperties properties = new DataSourceProperties();
        properties.getSecEdgar().setApiKey("Test Runner test@example.com");
        SecTickerCikCache cache = new SecTickerCikCache(new ExternalApiClientFactory(), properties,
                new TokenBucketRateLimiter("sec-edgar", 0, 0), server.url("/").toString(), 1,
                new MutableClock(Instant.parse("2026-10-10T11:00:00Z")),
                new com.stockscreener.pipeline.datasource.http.RefetchWaits(waitMillis, elapsed::addAndGet));
        try {
            return cache.findCik("FIX").isPresent() && elapsed.get() <= 3_000;
        } catch (DataSourceException e) {
            return false;
        }
    }

    private SecTickerCikCache cache(int minRows, Clock clock) {
        DataSourceProperties properties = new DataSourceProperties();
        properties.getSecEdgar().setApiKey("Test Runner test@example.com");
        return new SecTickerCikCache(new ExternalApiClientFactory(), properties,
                new TokenBucketRateLimiter("sec-edgar", 0, 0), server.url("/").toString(), minRows, clock, NO_WAIT);
    }

    /** 시험이 손으로 미는 시계. */
    static final class MutableClock extends Clock {
        private Instant now;

        MutableClock(Instant start) {
            this.now = start;
        }

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    private SecTickerCikCache cache(int minRows) {
        DataSourceProperties properties = new DataSourceProperties();
        properties.getSecEdgar().setApiKey("Test Runner test@example.com");
        ExternalApiClientFactory factory = new ExternalApiClientFactory();
        return new SecTickerCikCache(factory, properties,
                new TokenBucketRateLimiter("sec-edgar", 0, 0), server.url("/").toString(), minRows,
                java.time.Clock.systemUTC(), NO_WAIT);
    }

    private void enqueue(int status, String body) {
        server.enqueue(new MockResponse()
                .setResponseCode(status)
                .setHeader("Content-Type", "application/json;charset=UTF-8")
                .setBody(body));
    }

    private static String fixture() {
        try {
            return new String(new ClassPathResource("fixtures/sec-edgar/tickers.json")
                    .getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("fixture를 읽을 수 없다", e);
        }
    }
}
