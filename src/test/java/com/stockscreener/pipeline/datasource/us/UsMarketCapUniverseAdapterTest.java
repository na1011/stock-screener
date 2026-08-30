package com.stockscreener.pipeline.datasource.us;

import com.stockscreener.pipeline.datasource.DataSourceException;
import com.stockscreener.pipeline.datasource.SecurityNameCache;
import com.stockscreener.pipeline.datasource.StoredShareCounts;
import com.stockscreener.pipeline.datasource.UniverseSnapshot;
import com.stockscreener.pipeline.datasource.http.DataSourceProperties;
import com.stockscreener.pipeline.datasource.http.ExternalApiClientFactory;
import com.stockscreener.pipeline.datasource.http.TokenBucketRateLimiter;

import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 시총 상위 유니버스의 선정 규칙을 녹화 응답으로 잰다. API 키가 필요 없다.
 *
 * <p>MockWebServer를 <b>경로별 Dispatcher</b>로 쓴다. 이 어댑터가 호출하는 엔드포인트가
 * 셋이고({@code /v2/assets}·{@code /files/company_tickers_exchange.json}·
 * {@code /v2/stocks/bars}) 순서가 구현 세부이기 때문이다 — FIFO 큐로 두면 호출 순서를 바꾸는
 * 리팩터링이 <b>선정 규칙과 무관한 이유로</b> 테스트를 깨뜨린다.
 *
 * <p>상위 N은 <b>2로 낮춰 끼운다.</b> 운영값이 1,000이라 픽스처에 그만큼 넣지 않으면 모든
 * 시험이 부분 목록 거부로 끝난다. 규칙은 N과 무관하다.
 */
class UsMarketCapUniverseAdapterTest {

    private static final LocalDate AS_OF = LocalDate.of(2026, 8, 28);

    private MockWebServer server;
    private SecurityNameCache nameCache;
    private final Map<String, String> bodies = new LinkedHashMap<>();
    private final List<String> paths = new ArrayList<>();

    @BeforeEach
    void startServer() throws IOException {
        server = new MockWebServer();
        nameCache = new SecurityNameCache();
        bodies.clear();
        paths.clear();
        // 기본 픽스처. 시험마다 필요한 것만 덮어쓴다.
        bodies.put("/v2/assets", assets(
                asset("BIG", "Big Industries Inc. Common Stock"),
                asset("MID", "Mid Corp.")));
        bodies.put("/files/company_tickers_exchange.json", cikMap(Map.of("BIG", 1L, "MID", 2L)));
        bodies.put("/v2/stocks/bars", bars(Map.of("BIG", "100", "MID", "50")));
        server.setDispatcher(new Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest request) {
                String path = request.getPath();
                paths.add(path);
                for (Map.Entry<String, String> entry : bodies.entrySet()) {
                    if (path.startsWith(entry.getKey())) {
                        return new MockResponse().setResponseCode(200)
                                .setHeader("Content-Type", "application/json;charset=UTF-8")
                                .setBody(entry.getValue());
                    }
                }
                return new MockResponse().setResponseCode(404).setBody("{}");
            }
        });
        server.start();
    }

    @AfterEach
    void stopServer() throws IOException {
        server.shutdown();
    }

    @Test
    @DisplayName("유니버스 코드의 N과 상위 N 상수가 같다 — 갈리면 이름이 내용과 어긋난다")
    void universeCodeCarriesTheSameN() {
        assertThat(UsMarketCapUniverseAdapter.UNIVERSE_CODE)
                .as("코드에 N을 담은 것이 의도다 — N이 바뀌면 새 코드를 강제해 편입 원장이 정직해진다")
                .isEqualTo("US_TOP1000");
        // 운영 생성자가 쓰는 상수를 직접 못 보므로 동작으로 잰다: 후보 2종목이면 부분 목록이다.
        assertThatThrownBy(() -> productionSizedAdapter().fetchConstituents(
                UsMarketCapUniverseAdapter.UNIVERSE_CODE, AS_OF))
                .hasMessageContaining("상위 1000");
    }

    @Test
    @DisplayName("시총 내림차순으로 상위 N을 낸다 — 주식수는 우리 DB, 종가는 소스다")
    void ranksByComposedMarketCap() throws Exception {
        bodies.put("/v2/assets", assets(
                asset("BIG", "Big Industries Inc."),
                asset("MID", "Mid Corp."),
                asset("SML", "Small Co.")));
        bodies.put("/files/company_tickers_exchange.json",
                cikMap(Map.of("BIG", 1L, "MID", 2L, "SML", 3L)));
        // 주식수 × 종가 = BIG 10×100=1000 · MID 100×50=5000 · SML 1×1=1
        bodies.put("/v2/stocks/bars", bars(Map.of("BIG", "100", "MID", "50", "SML", "1")));

        UniverseSnapshot snapshot = adapter(2, shares(
                share("BIG", "10"), share("MID", "100"), share("SML", "1")))
                .fetchConstituents(UsMarketCapUniverseAdapter.UNIVERSE_CODE, AS_OF);

        assertThat(snapshot.tickers()).containsExactly("MID", "BIG");
        assertThat(snapshot.sourceName()).isEqualTo("alpaca:market-cap");
        assertThat(nameCache.find("US", "MID")).isPresent();
    }

    @Test
    @DisplayName("IMPORTANT — 이름축이 토큰 경계다. Netflix·Curtiss-Wright가 남는다")
    void nameAxisUsesWordBoundaries() throws Exception {
        bodies.put("/v2/assets", assets(
                asset("NFLX", "Netflix, Inc. Common Stock"),
                asset("CW", "Curtiss-Wright Corp."),
                asset("BFAM", "BRIGHT HORIZONS FAMILY SOLUTIONS INC."),
                asset("FUNC", "First United Corporation Common Stock"),
                asset("ET", "Energy Transfer LP Common Units representing limited partner interests"),
                asset("NTRS", "Northern Trust Corporation"),
                asset("JPMPD", "JPMorgan Chase & Co. Depositary Shares")));
        bodies.put("/files/company_tickers_exchange.json", cikMap(Map.of(
                "NFLX", 1L, "CW", 2L, "BFAM", 3L, "FUNC", 4L, "ET", 5L, "NTRS", 6L, "JPMPD", 7L)));
        bodies.put("/v2/stocks/bars", bars(Map.of("NFLX", "7", "CW", "6", "BFAM", "5",
                "FUNC", "4", "ET", "3", "NTRS", "2", "JPMPD", "1")));

        UniverseSnapshot snapshot = adapter(6, shares(
                share("NFLX", "1"), share("CW", "1"), share("BFAM", "1"), share("FUNC", "1"),
                share("ET", "1"), share("NTRS", "1"), share("JPMPD", "1")))
                .fetchConstituents(UsMarketCapUniverseAdapter.UNIVERSE_CODE, AS_OF);

        assertThat(snapshot.tickers())
                .as("부분문자열로 재면 여섯이 통째로 사라지고 아무 에러도 안 난다")
                .containsExactly("NFLX", "CW", "BFAM", "FUNC", "ET", "NTRS")
                .doesNotContain("JPMPD");
    }

    @Test
    @DisplayName("통화가 어긋난 종목은 후보가 아니다 — 환산 없이 넣으면 상단이 오염된다")
    void dropsCurrencyMismatched() {
        assertThatThrownBy(() -> adapter(2, shares(
                share("BIG", "10"), mismatched("MID", "100")))
                .fetchConstituents(UsMarketCapUniverseAdapter.UNIVERSE_CODE, AS_OF))
                .isInstanceOf(DataSourceException.class)
                .hasMessageContaining("통화=1");
    }

    @Test
    @DisplayName("REIT·SPAC은 이름이 아니라 SIC로 뺀다")
    void dropsReitAndSpacBySic() {
        assertThatThrownBy(() -> adapter(2, shares(
                share("BIG", "10"), sic("MID", "100", "6798")))
                .fetchConstituents(UsMarketCapUniverseAdapter.UNIVERSE_CODE, AS_OF))
                .isInstanceOf(DataSourceException.class)
                .hasMessageContaining("REIT/SPAC=1");
    }

    @Test
    @DisplayName("같은 CIK를 나눠 쓰면 시총이 큰 하나만 남는다 — 우선주가 발행사 주식수를 쓴다")
    void keepsOnlyTheLargestPerCik() throws Exception {
        bodies.put("/v2/assets", assets(
                asset("BIG", "Big Industries Inc."),
                asset("BIGO", "Big Industries Inc. 5% Series A"),
                asset("MID", "Mid Corp.")));
        // BIG과 BIGO가 같은 CIK다.
        bodies.put("/files/company_tickers_exchange.json",
                cikMap(Map.of("BIG", 1L, "BIGO", 1L, "MID", 2L)));
        bodies.put("/v2/stocks/bars", bars(Map.of("BIG", "100", "BIGO", "25", "MID", "50")));

        UniverseSnapshot snapshot = adapter(2, shares(
                share("BIG", "10"), share("BIGO", "10"), share("MID", "10")))
                .fetchConstituents(UsMarketCapUniverseAdapter.UNIVERSE_CODE, AS_OF);

        assertThat(snapshot.tickers()).containsExactly("BIG", "MID").doesNotContain("BIGO");
    }

    @Test
    @DisplayName("CIK를 모르는 티커는 남긴다 — 우리 8,504 중 1,815가 매핑 파일에 없고 정상이다")
    void keepsTickersWithoutCik() throws Exception {
        bodies.put("/files/company_tickers_exchange.json", cikMap(Map.of("BIG", 1L)));

        UniverseSnapshot snapshot = adapter(2, shares(share("BIG", "10"), share("MID", "100")))
                .fetchConstituents(UsMarketCapUniverseAdapter.UNIVERSE_CODE, AS_OF);

        assertThat(snapshot.tickers()).contains("MID");
    }

    @Test
    @DisplayName("종가 커버리지가 하한 미만이면 부분 목록이다 — 모르는 심볼은 조용히 빠진다")
    void rejectsWhenClosesAreMostlyMissing() {
        bodies.put("/v2/stocks/bars", bars(Map.of("BIG", "100")));

        assertThatThrownBy(() -> adapter(2, shares(share("BIG", "10"), share("MID", "100")))
                .fetchConstituents(UsMarketCapUniverseAdapter.UNIVERSE_CODE, AS_OF))
                .isInstanceOf(DataSourceException.class)
                .hasMessageContaining("부분 목록을 반영하지 않는다")
                .extracting(e -> ((DataSourceException) e).kind())
                .isEqualTo(DataSourceException.Kind.MALFORMED);
    }

    @Test
    @DisplayName("주식수가 없는 종목은 후보가 아니고, 그 수를 축별로 신고한다")
    void reportsExclusionsPerAxis() {
        assertThatThrownBy(() -> adapter(3, shares(share("BIG", "10")))
                .fetchConstituents(UsMarketCapUniverseAdapter.UNIVERSE_CODE, AS_OF))
                .isInstanceOf(DataSourceException.class)
                .hasMessageContaining("주식수없음=1")
                .hasMessageContaining("통화=0");
    }

    @Test
    @DisplayName("묶음 조회의 파라미터를 명시하고 end를 시각으로 보낸다 — 날짜만 주면 어제도 403이다")
    void sendsExplicitBarParameters() throws Exception {
        adapter(2, shares(share("BIG", "10"), share("MID", "100")))
                .fetchConstituents(UsMarketCapUniverseAdapter.UNIVERSE_CODE, AS_OF);

        String bars = paths.stream().filter(p -> p.startsWith("/v2/stocks/bars")).findFirst()
                .orElseThrow(() -> new AssertionError("봉 조회가 없다"));
        String decoded = URLDecoder.decode(bars, StandardCharsets.UTF_8);
        assertThat(decoded)
                .contains("feed=sip")
                .contains("adjustment=raw")
                .contains("limit=10000")
                .contains("sort=asc")
                .contains("symbols=BIG,MID");
        assertThat(decoded).as("end가 맨 날짜면 403이고 그 403은 배치 전체를 죽인다")
                .containsPattern("end=[^&]*T[^&]*Z");
    }

    @Test
    @DisplayName("담당하지 않는 유니버스는 NOT_FOUND다")
    void rejectsUnknownUniverse() {
        assertThatThrownBy(() -> adapter(2, shares(share("BIG", "10")))
                .fetchConstituents("US_LISTED", AS_OF))
                .isInstanceOf(DataSourceException.class)
                .extracting(e -> ((DataSourceException) e).kind())
                .isEqualTo(DataSourceException.Kind.NOT_FOUND);
    }

    // ── 조립 ───────────────────────────────────────────────────────────────

    private UsMarketCapUniverseAdapter adapter(int topN, StoredShareCounts counts) {
        return build(topN, counts);
    }

    private UsMarketCapUniverseAdapter productionSizedAdapter() {
        return new UsMarketCapUniverseAdapter(
                listingAdapter(), shares(share("BIG", "10"), share("MID", "100")),
                cikCache(), nameCache, new ExternalApiClientFactory(), properties(),
                new TokenBucketRateLimiter("alpaca", 0, 0));
    }

    private UsMarketCapUniverseAdapter build(int topN, StoredShareCounts counts) {
        return new UsMarketCapUniverseAdapter(
                listingAdapter(), counts, cikCache(), nameCache,
                new ExternalApiClientFactory(), properties(),
                new TokenBucketRateLimiter("alpaca", 0, 0),
                server.url("/").toString(), topN);
    }

    private AlpacaUniverseAdapter listingAdapter() {
        return new AlpacaUniverseAdapter(
                new ExternalApiClientFactory(), properties(),
                new TokenBucketRateLimiter("alpaca", 0, 0), nameCache,
                server.url("/").toString(), 1);
    }

    private SecTickerCikCache cikCache() {
        return new SecTickerCikCache(
                new ExternalApiClientFactory(), properties(),
                new TokenBucketRateLimiter("sec-edgar", 0, 0),
                server.url("/").toString(), 1);
    }

    private DataSourceProperties properties() {
        DataSourceProperties properties = new DataSourceProperties();
        DataSourceProperties.Source alpaca = properties.getAlpaca();
        alpaca.setBaseUrl(server.url("/").toString());
        alpaca.setApiKey("test-key-id");
        alpaca.setSecretKey("test-secret");
        properties.setAlpacaMarketDataBaseUrl(server.url("/").toString());
        properties.getSecEdgar().setApiKey("tester tester@example.invalid");
        properties.setSecEdgarFilesBaseUrl(server.url("/").toString());
        return properties;
    }

    private static StoredShareCounts shares(StoredShareCounts.ShareCount... counts) {
        List<StoredShareCounts.ShareCount> list = List.of(counts);
        return (marketCode, oldestPeriodEnd) -> list;
    }

    private static StoredShareCounts.ShareCount share(String ticker, String shares) {
        return new StoredShareCounts.ShareCount(
                ticker, new BigDecimal(shares), "3711", AS_OF.minusMonths(3), false);
    }

    private static StoredShareCounts.ShareCount mismatched(String ticker, String shares) {
        return new StoredShareCounts.ShareCount(
                ticker, new BigDecimal(shares), "3711", AS_OF.minusMonths(3), true);
    }

    private static StoredShareCounts.ShareCount sic(String ticker, String shares, String sicCode) {
        return new StoredShareCounts.ShareCount(
                ticker, new BigDecimal(shares), sicCode, AS_OF.minusMonths(3), false);
    }

    private static String asset(String symbol, String name) {
        return """
                {"symbol":"%s","name":"%s","exchange":"NASDAQ","status":"active","tradable":true}"""
                .formatted(symbol, name);
    }

    private static String assets(String... assets) {
        return "[" + String.join(",", assets) + "]";
    }

    private static String cikMap(Map<String, Long> byTicker) {
        List<String> rows = new ArrayList<>();
        byTicker.forEach((ticker, cik) ->
                rows.add("[%d,\"%s Inc.\",\"%s\",\"Nasdaq\"]".formatted(cik, ticker, ticker)));
        return """
                {"fields":["cik","name","ticker","exchange"],"data":[%s]}"""
                .formatted(String.join(",", rows));
    }

    /** 심볼마다 봉 둘을 준다 — 마지막 봉을 쓰는지가 계약이다. */
    private static String bars(Map<String, String> closeBySymbol) {
        List<String> entries = new ArrayList<>();
        closeBySymbol.forEach((symbol, close) -> entries.add("""
                "%s":[{"t":"2026-08-26T04:00:00Z","c":1.0},{"t":"2026-08-27T04:00:00Z","c":%s}]"""
                .formatted(symbol, close)));
        return "{\"bars\":{%s},\"next_page_token\":null}".formatted(String.join(",", entries));
    }
}
