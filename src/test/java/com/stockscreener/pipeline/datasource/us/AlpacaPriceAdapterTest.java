package com.stockscreener.pipeline.datasource.us;

import com.stockscreener.pipeline.datasource.DataSourceException;
import com.stockscreener.pipeline.datasource.RawPriceBar;
import com.stockscreener.pipeline.datasource.SecurityRef;
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
import java.math.BigDecimal;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 미국 시세 어댑터의 봉투 판별·파라미터·페이징을 검증한다.
 *
 * <p>MockWebServer를 쓰는 이유는 실제 소켓을 열어 {@code RestClient}를 통과시키기 위해서다.
 * {@code RestClient}를 목킹하면 <b>봉투 판별과 상태코드 처리가 전혀 검증되지 않는다.</b>
 *
 * <p>픽스처는 {@code AlpacaBarsProbeLiveTest}가 실제로 받은 응답을 줄인 것이다. 특히
 * {@code error-403-sip.json}은 15분 규칙 위반의 <b>실측 본문</b>이고, 그 403이
 * {@code AUTH}로 접히는지가 이 클래스에서 가장 중요한 단언이다 — {@code AUTH}는
 * {@code fatal()}이라 배치 전체를 세운다.
 */
class AlpacaPriceAdapterTest {

    private static final SecurityRef AAPL = new SecurityRef(1L, "US", "AAPL");
    private static final LocalDate FROM = LocalDate.of(2024, 1, 2);
    private static final LocalDate TO = LocalDate.of(2024, 1, 4);

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
    @DisplayName("봉을 날짜 오름차순으로 옮기고, 소스가 주지 않는 값은 null로 둔다")
    void mapsBars() throws Exception {
        enqueue(200, fixture("bars-ok.json"));

        List<RawPriceBar> bars = adapter().fetchDailyBars(AAPL, FROM, TO);

        assertThat(bars).hasSize(3);
        assertThat(bars).extracting(RawPriceBar::tradeDate).isSorted();
        RawPriceBar first = bars.get(0);
        assertThat(first.tradeDate()).isEqualTo(LocalDate.of(2024, 1, 2));
        assertThat(first.close()).isEqualByComparingTo(new BigDecimal("185.64"));
        assertThat(first.low()).as("소수 자릿수를 보존한다").isEqualByComparingTo(new BigDecimal("183.885"));
        assertThat(first.volume()).isEqualTo(82_496_943L);
        assertThat(first.tradeValue()).as("소스가 안 준다 — close × volume으로 합성하지 않는다").isNull();
        assertThat(first.listedShares()).as("소스가 안 준다 — 재무 쪽으로 폴백한다").isNull();
        assertThat(first.splitAdjustedBySource())
                .as("adjustment=raw를 보내므로 false다. 바꾸면 원장 계수가 두 번 적용된다").isFalse();
        assertThat(bars).allSatisfy(bar -> assertThat(bar.isValid()).isTrue());
    }

    @Test
    @DisplayName("IMPORTANT — feed·adjustment·limit을 명시한다. 기본값에 기대면 조용히 다른 값이 온다")
    void sendsExplicitParameters() throws Exception {
        enqueue(200, fixture("bars-ok.json"));

        adapter().fetchDailyBars(AAPL, FROM, TO);

        String query = URLDecoder.decode(server.takeRequest().getPath(), StandardCharsets.UTF_8);
        assertThat(query).contains("symbols=AAPL");
        assertThat(query).contains("timeframe=1Day");
        assertThat(query).as("빠뜨리면 단일 거래소 데이터를 받고 고가·저가가 오염된다")
                .contains("feed=sip");
        assertThat(query).as("소스가 계산한 수정주가를 받지 않는다(ADR-0006)")
                .contains("adjustment=raw");
        assertThat(query).as("기본 1000은 전체 데이터 포인트 기준이라 한 종목 4년치로도 닿는다")
                .contains("limit=10000");
        assertThat(query).contains("sort=asc");
        assertThat(query).contains("start=2024-01-02");
    }

    @Test
    @DisplayName("IMPORTANT — end를 날짜로 보내지 않는다. 날짜만 주면 어제도 403이다")
    void clampsEndToAnInstant() throws Exception {
        enqueue(200, fixture("bars-ok.json"));

        adapter().fetchDailyBars(AAPL, LocalDate.now().minusDays(3), LocalDate.now());

        String query = URLDecoder.decode(server.takeRequest().getPath(), StandardCharsets.UTF_8);
        // 날짜만 보내면 뉴욕 기준 그날 끝으로 해석되어 UTC로는 미래가 되고 403이 난다.
        // RFC-3339 문자열도 날짜로 시작하므로, 단언은 "날짜 뒤에 시각이 붙는가"로 해야 한다.
        assertThat(query).as("end이 맨 날짜면 안 된다").doesNotContainPattern("end=\\d{4}-\\d{2}-\\d{2}(&|$)");
        assertThat(query).as("RFC-3339 시각이어야 한다").containsPattern("end=\\d{4}-\\d{2}-\\d{2}T\\d{2}:");
    }

    @Test
    @DisplayName("IMPORTANT — 403은 AUTH다. 15분 규칙 위반이 종목 실패로 묻히면 안 된다")
    void mapsSipForbiddenToAuth() {
        enqueue(403, fixture("error-403-sip.json"));

        assertThatThrownBy(() -> adapter().fetchDailyBars(AAPL, FROM, TO))
                .isInstanceOf(DataSourceException.class)
                .satisfies(e -> {
                    DataSourceException d = (DataSourceException) e;
                    assertThat(d.kind()).isEqualTo(DataSourceException.Kind.AUTH);
                    assertThat(d.fatal()).as("배치를 세워야 한다 — 원인이 우리 요청이다").isTrue();
                    assertThat(d.sourceName()).as("유니버스 소스 이름이 붙으면 실패 집계가 갈린다")
                            .isEqualTo("alpaca:price");
                });
    }

    @Test
    @DisplayName("next_page_token이 null이 될 때까지 순회한다 — 멈추면 뒤쪽이 조용히 빈다")
    void followsPagination() throws Exception {
        enqueue(200, fixture("bars-page1.json"));
        enqueue(200, fixture("bars-page2.json"));

        List<RawPriceBar> bars = adapter().fetchDailyBars(AAPL, FROM, TO);

        assertThat(bars).hasSize(3);
        assertThat(server.getRequestCount()).isEqualTo(2);
        server.takeRequest();
        assertThat(URLDecoder.decode(server.takeRequest().getPath(), StandardCharsets.UTF_8))
                .contains("page_token=MjAyNC0wMS0wM3xBQVBM");
    }

    @Test
    @DisplayName("모르는 심볼은 빈 목록이다 — 이 소스는 「봉 없음」과 「종목 모름」을 갈라 주지 않는다")
    void returnsEmptyForSymbolsTheSourceOmits() throws Exception {
        enqueue(200, fixture("bars-unknown-symbol.json"));

        List<RawPriceBar> bars =
                adapter().fetchDailyBars(new SecurityRef(2L, "US", "ZZZZNOTREAL"), FROM, TO);

        assertThat(bars).isEmpty();
    }

    @Test
    @DisplayName("bars 키가 없으면 MALFORMED다 — 「봉 0개」로 접으면 오류 본문이 정상이 된다")
    void rejectsResponsesWithoutBars() {
        enqueue(200, "{\"next_page_token\":null}");

        assertThatThrownBy(() -> adapter().fetchDailyBars(AAPL, FROM, TO))
                .isInstanceOf(DataSourceException.class)
                .extracting(e -> ((DataSourceException) e).kind())
                .isEqualTo(DataSourceException.Kind.MALFORMED);
    }

    @Test
    @DisplayName("429는 RATE_LIMITED, 500은 TRANSIENT — 재시도 가능 여부가 갈린다")
    void mapsRetryableStatuses() {
        enqueue(429, "{\"message\":\"too many requests\"}");
        assertThatThrownBy(() -> adapter().fetchDailyBars(AAPL, FROM, TO))
                .extracting(e -> ((DataSourceException) e).kind())
                .isEqualTo(DataSourceException.Kind.RATE_LIMITED);

        enqueue(500, "{\"message\":\"boom\"}");
        assertThatThrownBy(() -> adapter().fetchDailyBars(AAPL, FROM, TO))
                .extracting(e -> ((DataSourceException) e).kind())
                .isEqualTo(DataSourceException.Kind.TRANSIENT);
    }

    @Test
    @DisplayName("뒤집힌 구간은 호출 전에 막는다 — 빈 결과와 구별되지 않는다")
    void rejectsInvertedRangeBeforeCalling() {
        assertThatThrownBy(() -> adapter().fetchDailyBars(AAPL, TO, FROM))
                .isInstanceOf(DataSourceException.class)
                .extracting(e -> ((DataSourceException) e).kind())
                .isEqualTo(DataSourceException.Kind.MALFORMED);
        assertThat(server.getRequestCount()).as("호출 전에 막는다").isZero();
    }

    @Test
    @DisplayName("시크릿이 비면 부르기 전에 AUTH다 — @ConditionalOnApiKey는 api-key만 본다")
    void failsFastWhenSecretMissing() {
        AlpacaPriceAdapter adapter = new AlpacaPriceAdapter(
                new ExternalApiClientFactory(), properties("test-key-id", ""),
                new TokenBucketRateLimiter("alpaca", 0, 0), server.url("/").toString());

        assertThatThrownBy(() -> adapter.fetchDailyBars(AAPL, FROM, TO))
                .extracting(e -> ((DataSourceException) e).kind())
                .isEqualTo(DataSourceException.Kind.AUTH);
        assertThat(server.getRequestCount()).as("호출 전에 막는다").isZero();
    }

    @Test
    @DisplayName("인증 헤더 둘을 붙인다 — 하나를 빠뜨리면 401이고 그것은 키가 틀린 것처럼 보인다")
    void sendsBothCredentialHeaders() throws Exception {
        enqueue(200, fixture("bars-ok.json"));

        adapter().fetchDailyBars(AAPL, FROM, TO);

        RecordedRequest request = server.takeRequest();
        assertThat(request.getHeader("APCA-API-KEY-ID")).isEqualTo("test-key-id");
        assertThat(request.getHeader("APCA-API-SECRET-KEY")).isEqualTo("test-secret");
    }

    @Test
    @DisplayName("담당 시장은 US 하나다 — 거래소는 stock.listing_exchange가 가른다")
    void declaresUsMarket() {
        assertThat(adapter().supportedMarketCodes()).containsExactly("US");
        assertThat(adapter().sourceName()).isEqualTo("alpaca:price");
    }

    private AlpacaPriceAdapter adapter() {
        return new AlpacaPriceAdapter(
                new ExternalApiClientFactory(), properties("test-key-id", "test-secret"),
                new TokenBucketRateLimiter("alpaca", 0, 0), server.url("/").toString());
    }

    private static DataSourceProperties properties(String apiKey, String secretKey) {
        DataSourceProperties properties = new DataSourceProperties();
        properties.getAlpaca().setApiKey(apiKey);
        properties.getAlpaca().setSecretKey(secretKey);
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
