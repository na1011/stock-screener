package com.stockscreener.pipeline.datasource.us;

import com.stockscreener.pipeline.datasource.DataSourceException;
import com.stockscreener.pipeline.datasource.SecurityDescriptor;
import com.stockscreener.pipeline.datasource.SecurityNameCache;
import com.stockscreener.pipeline.datasource.UniverseSnapshot;
import com.stockscreener.pipeline.datasource.http.DataSourceProperties;
import com.stockscreener.pipeline.datasource.http.ExternalApiClientFactory;
import com.stockscreener.pipeline.datasource.http.TokenBucketRateLimiter;

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 녹화한 응답으로 파싱과 에러 매핑을 검증한다. API 키가 필요 없다.
 *
 * <p>MockWebServer를 쓰는 이유는 실제 소켓을 열어 {@code RestClient}를 통과시키기 위해서다.
 * {@code RestClient}를 목킹하면 <b>봉투 판별과 상태코드 처리가 전혀 검증되지 않는다.</b>
 *
 * <p>하한(부분 목록 거부)은 운영값이 4,252라 픽스처에 그만큼 넣어야 확인할 수 있으므로,
 * <b>기본 어댑터로는 그 규칙이 늘 발화한다.</b> 그래서 픽스처 3종으로 도는 시험은 하한을
 * 낮춘 생성자를 쓰고, 하한 자체는 별도 시험이 잰다 — 규칙은 하한 값과 무관하다.
 */
class AlpacaUniverseAdapterTest {

    private static final LocalDate AS_OF = LocalDate.of(2026, 8, 28);

    private MockWebServer server;
    private SecurityNameCache nameCache;

    @BeforeEach
    void startServer() throws IOException {
        server = new MockWebServer();
        server.start();
        nameCache = new SecurityNameCache();
    }

    @AfterEach
    void stopServer() throws IOException {
        server.shutdown();
    }

    @Test
    @DisplayName("활성 종목을 티커 목록으로 내고 이름·거래소를 캐시에 심는다")
    void parsesAssetsAndSeedsNameCache() throws Exception {
        enqueue(200, fixture("assets-ok.json"));

        UniverseSnapshot snapshot = adapter(1).fetchConstituents(
                AlpacaUniverseAdapter.UNIVERSE_CODE, AS_OF);

        assertThat(snapshot.tickers()).containsExactly("AAPL", "KIM.PRL", "DATAONLY");
        assertThat(snapshot.sourceName()).isEqualTo("alpaca");

        Optional<SecurityDescriptor> apple = nameCache.find("US", "AAPL");
        assertThat(apple).isPresent();
        assertThat(apple.get().name()).isEqualTo("Apple Inc. Common Stock");
        assertThat(apple.get().listingExchange()).isEqualTo("NASDAQ");
    }

    @Test
    @DisplayName("IMPORTANT — tradable=false로 거르지 않는다. 데이터 조회 전용 자산이 그 값으로 온다")
    void keepsNonTradableAssets() throws Exception {
        enqueue(200, fixture("assets-ok.json"));

        UniverseSnapshot snapshot = adapter(1).fetchConstituents(
                AlpacaUniverseAdapter.UNIVERSE_CODE, AS_OF);

        assertThat(snapshot.tickers()).contains("DATAONLY");
    }

    @Test
    @DisplayName("229자짜리 증권명이 잘리지 않는다 — 자르면 서로 다른 우선주가 같은 이름이 된다")
    void keepsLongSecurityNamesIntact() throws Exception {
        enqueue(200, fixture("assets-ok.json"));

        adapter(1).fetchConstituents(AlpacaUniverseAdapter.UNIVERSE_CODE, AS_OF);

        String name = nameCache.find("US", "KIM.PRL").orElseThrow().name();
        assertThat(name).hasSizeGreaterThan(200).endsWith("per share");
    }

    @Test
    @DisplayName("status=active와 두 거래소를 명시해 부른다 — 기본값은 상장폐지까지 포함한다")
    void requestsActiveAssetsOfBothExchanges() throws Exception {
        enqueue(200, fixture("assets-ok.json"));

        adapter(1).fetchConstituents(AlpacaUniverseAdapter.UNIVERSE_CODE, AS_OF);

        String path = URLDecoder.decode(server.takeRequest().getPath(), StandardCharsets.UTF_8);
        assertThat(path).contains("/v2/assets")
                .contains("status=active")
                .contains("asset_class=us_equity")
                .contains("exchange=NYSE,NASDAQ");
    }

    @Test
    @DisplayName("인증 헤더 둘을 붙인다 — 하나를 빠뜨리면 401이고 그것은 키가 틀린 것처럼 보인다")
    void sendsBothCredentialHeaders() throws Exception {
        enqueue(200, fixture("assets-ok.json"));

        adapter(1).fetchConstituents(AlpacaUniverseAdapter.UNIVERSE_CODE, AS_OF);

        var request = server.takeRequest();
        assertThat(request.getHeader("APCA-API-KEY-ID")).isEqualTo("test-key-id");
        assertThat(request.getHeader("APCA-API-SECRET-KEY")).isEqualTo("test-secret");
    }

    @Test
    @DisplayName("빈 배열은 실패다 — 전 종목 제외로 읽히므로 반영하지 않는다")
    void rejectsEmptyArray() {
        enqueue(200, fixture("assets-empty.json"));

        assertThatThrownBy(() -> adapter(1).fetchConstituents(
                AlpacaUniverseAdapter.UNIVERSE_CODE, AS_OF))
                .isInstanceOf(DataSourceException.class)
                .hasMessageContaining("빈 배열")
                .extracting(e -> ((DataSourceException) e).kind())
                .isEqualTo(DataSourceException.Kind.MALFORMED);
    }

    @Test
    @DisplayName("IMPORTANT — 하한 미만이면 부분 목록으로 보고 거부한다(ADR-0008)")
    void rejectsPartialList() {
        enqueue(200, fixture("assets-ok.json"));

        // 하한을 4로 두면 3종목짜리 픽스처가 부분 목록이다.
        assertThatThrownBy(() -> adapter(4).fetchConstituents(
                AlpacaUniverseAdapter.UNIVERSE_CODE, AS_OF))
                .isInstanceOf(DataSourceException.class)
                .hasMessageContaining("부분 목록을 반영하지 않는다");
    }

    @Test
    @DisplayName("중복 심볼은 실패다 — EXCLUDE 제약에서 뒤늦게 터지는 것보다 낫다")
    void rejectsDuplicateSymbols() {
        enqueue(200, """
                [{"symbol":"AAPL","name":"Apple Inc.","exchange":"NASDAQ","status":"active","tradable":true},
                 {"symbol":"AAPL","name":"Apple Inc. (dup)","exchange":"NYSE","status":"active","tradable":true}]""");

        assertThatThrownBy(() -> adapter(1).fetchConstituents(
                AlpacaUniverseAdapter.UNIVERSE_CODE, AS_OF))
                .isInstanceOf(DataSourceException.class)
                .hasMessageContaining("중복 심볼");
    }

    @Test
    @DisplayName("이름이 빈 자산은 건너뛰지 않고 실패한다 — 벤더 스펙 변경의 신호다")
    void rejectsAssetsMissingRequiredFields() {
        enqueue(200, """
                [{"symbol":"AAPL","name":"","exchange":"NASDAQ","status":"active","tradable":true}]""");

        assertThatThrownBy(() -> adapter(1).fetchConstituents(
                AlpacaUniverseAdapter.UNIVERSE_CODE, AS_OF))
                .isInstanceOf(DataSourceException.class)
                .hasMessageContaining("필수 필드가 빈 자산");
    }

    @Test
    @DisplayName("401은 AUTH다 — 실측한 인증 실패 모양이다")
    void mapsUnauthorizedToAuth() {
        enqueue(401, fixture("error-401.json"));

        assertThatThrownBy(() -> adapter(1).fetchConstituents(
                AlpacaUniverseAdapter.UNIVERSE_CODE, AS_OF))
                .isInstanceOf(DataSourceException.class)
                .extracting(e -> ((DataSourceException) e).kind())
                .isEqualTo(DataSourceException.Kind.AUTH);
    }

    @Test
    @DisplayName("429는 RATE_LIMITED, 500은 TRANSIENT — 재시도 가능 여부가 갈린다")
    void mapsRetryableStatuses() {
        enqueue(429, "{\"message\":\"too many requests\"}");
        assertThatThrownBy(() -> adapter(1).fetchConstituents(
                AlpacaUniverseAdapter.UNIVERSE_CODE, AS_OF))
                .extracting(e -> ((DataSourceException) e).kind())
                .isEqualTo(DataSourceException.Kind.RATE_LIMITED);

        enqueue(500, "{\"message\":\"boom\"}");
        assertThatThrownBy(() -> adapter(1).fetchConstituents(
                AlpacaUniverseAdapter.UNIVERSE_CODE, AS_OF))
                .extracting(e -> ((DataSourceException) e).kind())
                .isEqualTo(DataSourceException.Kind.TRANSIENT);
    }

    @Test
    @DisplayName("시크릿이 비면 부르기 전에 AUTH다 — 조건은 api-key 하나만 보므로 어댑터는 등록된다")
    void failsFastWhenSecretMissing() {
        DataSourceProperties properties = properties("test-key-id", "");

        assertThatThrownBy(() -> new AlpacaUniverseAdapter(
                new ExternalApiClientFactory(), properties,
                new TokenBucketRateLimiter("alpaca", 0, 0), nameCache,
                server.url("/").toString(), 1)
                .fetchConstituents(AlpacaUniverseAdapter.UNIVERSE_CODE, AS_OF))
                .isInstanceOf(DataSourceException.class)
                .hasMessageContaining("APCA_API_SECRET_KEY")
                .extracting(e -> ((DataSourceException) e).kind())
                .isEqualTo(DataSourceException.Kind.AUTH);
        assertThat(server.getRequestCount()).as("호출 전에 막는다").isZero();
    }

    @Test
    @DisplayName("담당하지 않는 유니버스는 NOT_FOUND다")
    void rejectsUnknownUniverse() {
        assertThatThrownBy(() -> adapter(1).fetchConstituents("KOSPI200", AS_OF))
                .isInstanceOf(DataSourceException.class)
                .extracting(e -> ((DataSourceException) e).kind())
                .isEqualTo(DataSourceException.Kind.NOT_FOUND);
    }

    @Test
    @DisplayName("단건은 404면 비어 있고, 2xx 아닌 나머지는 던진다 — 빼고 넘어가면 숨은 상장이 조용히 빠진다(ADR-0171)")
    void fetchAssetContract() throws Exception {
        enqueue(200, """
                {"symbol":"ET","name":"Energy Transfer LP Common Units","exchange":"NYSE","status":"inactive","tradable":false}""");
        enqueue(404, "{\"message\":\"not found\"}");
        enqueue(503, "{}");
        AlpacaUniverseAdapter adapter = adapter(1);

        Optional<AlpacaAsset> et = adapter.fetchAsset("ET");
        assertThat(et).get().extracting(AlpacaAsset::exchange, AlpacaAsset::status)
                .containsExactly("NYSE", "inactive");
        assertThat(adapter.fetchAsset("NOPE")).isEmpty();
        assertThatThrownBy(() -> adapter.fetchAsset("DOWN"))
                .isInstanceOf(DataSourceException.class)
                .hasMessageContaining("HTTP 503");
    }

    @Test
    @DisplayName("단건은 6시간 메모한다 — 복원 격자가 같은 심볼을 다시 물어도 0콜 · 지나면 다시 부른다")
    void fetchAssetIsMemoizedForSixHours() throws Exception {
        SecTickerCikCacheTest.MutableClock clock =
                new SecTickerCikCacheTest.MutableClock(java.time.Instant.parse("2026-10-10T11:00:00Z"));
        String body = """
                {"symbol":"ET","name":"Energy Transfer LP Common Units","exchange":"NYSE","status":"inactive","tradable":false}""";
        enqueue(200, body);
        enqueue(200, body);
        AlpacaUniverseAdapter adapter = new AlpacaUniverseAdapter(
                new ExternalApiClientFactory(), properties("test-key-id", "test-secret"),
                new TokenBucketRateLimiter("alpaca", 0, 0), nameCache,
                server.url("/").toString(), 1, clock);

        adapter.fetchAsset("ET");
        clock.advance(java.time.Duration.ofHours(5));
        adapter.fetchAsset("ET");
        assertThat(server.getRequestCount()).as("6시간 안").isEqualTo(1);
        clock.advance(java.time.Duration.ofHours(2));
        adapter.fetchAsset("ET");
        assertThat(server.getRequestCount()).as("6시간이 지나면 다시").isEqualTo(2);
    }

    private AlpacaUniverseAdapter adapter(int minAssets) {
        return new AlpacaUniverseAdapter(
                new ExternalApiClientFactory(), properties("test-key-id", "test-secret"),
                new TokenBucketRateLimiter("alpaca", 0, 0), nameCache,
                server.url("/").toString(), minAssets);
    }

    private DataSourceProperties properties(String keyId, String secret) {
        DataSourceProperties properties = new DataSourceProperties();
        DataSourceProperties.Source source = properties.getAlpaca();
        source.setBaseUrl(server.url("/").toString());
        source.setApiKey(keyId);
        source.setSecretKey(secret);
        return properties;
    }

    private void enqueue(int status, String body) {
        server.enqueue(new MockResponse()
                .setResponseCode(status)
                .setHeader("Content-Type", "application/json;charset=UTF-8")
                .setBody(body));
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
