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
    /** 봉 조회 둘째 페이지 본문의 키. {@code page_token=P2}로 온 요청에 준다. */
    private static final String PAGE2 = "PAGE2";

    private final Map<String, String> bodies = new LinkedHashMap<>();
    /** 경로별 응답 코드. 없으면 200 — 단건의 5xx 갈래를 시험한다. */
    private final Map<String, Integer> statuses = new LinkedHashMap<>();
    private final List<String> paths = new ArrayList<>();

    @BeforeEach
    void startServer() throws IOException {
        server = new MockWebServer();
        nameCache = new SecurityNameCache();
        bodies.clear();
        statuses.clear();
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
                if (path.contains("page_token=P2") && bodies.containsKey(PAGE2)) {
                    return new MockResponse().setResponseCode(200)
                            .setHeader("Content-Type", "application/json;charset=UTF-8")
                            .setBody(bodies.get(PAGE2));
                }
                // 경로가 같거나 그 뒤가 질의뿐일 때만 맞춘다 — 단건 /v2/assets/ET가 목록 /v2/assets에 걸리지 않게.
                for (Map.Entry<String, String> entry : bodies.entrySet()) {
                    String key = entry.getKey();
                    if (path.equals(key) || path.startsWith(key + "?")) {
                        return new MockResponse().setResponseCode(statuses.getOrDefault(key, 200))
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
    @DisplayName("같은 CIK의 대표는 가격이 아니라 거래대금이 고른다 — 같은 주식수를 나눠 쓰면 시총 최대가 곧 가격 최대다")
    void picksTheMostTradedSecurityPerCik() throws Exception {
        // MOG.B(거래 드문 클래스)가 가격이 더 높고 주식수는 같다 — 옛 규칙은 MOG.B를 뽑았다(2026-10-05 실측 0.09 대 106백만 달러).
        bodies.put("/v2/assets", assets(
                asset("MOG.A", "Moog Inc."), asset("MOG.B", "MOOG INC CL B"), asset("MID", "Mid Corp.")));
        bodies.put("/files/company_tickers_exchange.json",
                cikMap(Map.of("MOG-A", 1L, "MOG-B", 1L, "MID", 2L)));
        bodies.put("/v2/stocks/bars", page(List.of(
                series("MOG.A", bar("26", "100", 900_000L), bar("27", "100", 1_100_000L)),
                series("MOG.B", bar("26", "120", 0L), bar("27", "120", 50L)),
                series("MID", bar("26", "50", 1000L), bar("27", "50", 1000L))), null));

        UniverseSnapshot snapshot = adapter(2, shares(
                share("MOG.A", "10"), share("MOG.B", "10"), share("MID", "10")))
                .fetchConstituents(UsMarketCapUniverseAdapter.UNIVERSE_CODE, AS_OF);

        assertThat(snapshot.tickers()).containsExactly("MOG.A", "MID").doesNotContain("MOG.B");
    }

    @Test
    @DisplayName("거래대금은 창 안의 합이다 — 평균이면 봉이 띄엄띄엄인 클래스가 부푼다")
    void comparesTheWindowSumNotTheAverage() throws Exception {
        // X: 두 봉 10×1,000 = 합 20,000 · 평균 10,000 / Y: 한 봉 11×1,500 = 16,500 — 평균이면 Y, 합이면 X.
        bodies.put("/v2/assets", assets(asset("X", "X Corp. Class A"), asset("Y", "X Corp. Class B"),
                asset("MID", "Mid Corp.")));
        bodies.put("/files/company_tickers_exchange.json", cikMap(Map.of("X", 1L, "Y", 1L, "MID", 2L)));
        bodies.put("/v2/stocks/bars", page(List.of(
                series("X", bar("26", "10", 1000L), bar("27", "10", 1000L)),
                series("Y", bar("27", "11", 1500L)),
                series("MID", bar("26", "50", 1000L), bar("27", "50", 1000L))), null));

        UniverseSnapshot snapshot = adapter(2, shares(share("X", "10"), share("Y", "10"), share("MID", "10")))
                .fetchConstituents(UsMarketCapUniverseAdapter.UNIVERSE_CODE, AS_OF);

        assertThat(snapshot.tickers()).contains("X").doesNotContain("Y");
    }

    @Test
    @DisplayName("거래량이 없는 봉은 MALFORMED다 — 0으로 읽으면 전부 동률이 되어 기호순으로 대표가 조용히 정해진다")
    void missingVolumeIsMalformed() {
        bodies.put("/v2/stocks/bars", page(List.of(
                series("BIG", bar("27", "100", null)), series("MID", bar("27", "50", 1000L))), null));

        assertThatThrownBy(() -> adapter(2, shares(share("BIG", "10"), share("MID", "100")))
                .fetchConstituents(UsMarketCapUniverseAdapter.UNIVERSE_CODE, AS_OF))
                .isInstanceOf(DataSourceException.class)
                .hasMessageContaining("거래량")
                .extracting(e -> ((DataSourceException) e).kind())
                .isEqualTo(DataSourceException.Kind.MALFORMED);
    }

    @Test
    @DisplayName("페이지가 갈려도 거래대금은 누적한다 — 덮어쓰면 마지막 페이지의 봉만 센다")
    void accumulatesVolumeAcrossPages() throws Exception {
        // X의 봉이 두 페이지에 걸친다: 합 20,000 대 Y 10.5×1,400 = 14,700. 마지막 페이지만 세면 X 10,000으로 진다.
        // Y의 가격이 더 높아 옛 규칙(가격 최대)도 Y를 고른다 — 동률이 기호순으로 X를 고르면 이 시험이 누적에 안 닿는다.
        bodies.put("/v2/assets", assets(asset("X", "X Corp. Class A"), asset("Y", "X Corp. Class B"),
                asset("MID", "Mid Corp.")));
        bodies.put("/files/company_tickers_exchange.json", cikMap(Map.of("X", 1L, "Y", 1L, "MID", 2L)));
        bodies.put("/v2/stocks/bars", page(List.of(
                series("MID", bar("27", "50", 1000L)),
                series("X", bar("26", "10", 1000L))), "P2"));
        bodies.put(PAGE2, page(List.of(
                series("X", bar("27", "10", 1000L)),
                series("Y", bar("27", "10.5", 1400L))), null));

        UniverseSnapshot snapshot = adapter(2, shares(share("X", "10"), share("Y", "10"), share("MID", "10")))
                .fetchConstituents(UsMarketCapUniverseAdapter.UNIVERSE_CODE, AS_OF);

        assertThat(snapshot.tickers()).contains("X").doesNotContain("Y");
    }

    @Test
    @DisplayName("거래대금이 같으면(둘 다 무거래) 시총 → 기호 순이다 — 대표가 실행마다 흔들리지 않는다")
    void breaksTiesByMarketCapThenSymbol() throws Exception {
        // P·Q는 같은 CIK · 둘 다 창 안 체결 0 — 시총은 P 10×20 = 200 대 Q 10×30 = 300이라 Q.
        // R·S는 같은 CIK · 체결 0 · 시총도 같다 — 기호가 앞선 R.
        bodies.put("/v2/assets", assets(asset("P", "P Corp. Class A"), asset("Q", "P Corp. Class B"),
                asset("R", "R Corp. Class A"), asset("S", "R Corp. Class B")));
        bodies.put("/files/company_tickers_exchange.json", cikMap(Map.of("P", 1L, "Q", 1L, "R", 2L, "S", 2L)));
        bodies.put("/v2/stocks/bars", page(List.of(
                series("P", bar("27", "20", 0L)), series("Q", bar("27", "30", 0L)),
                series("R", bar("27", "5", 0L)), series("S", bar("27", "5", 0L))), null));

        UniverseSnapshot snapshot = adapter(2, shares(share("P", "10"), share("Q", "10"), share("R", "10"), share("S", "10")))
                .fetchConstituents(UsMarketCapUniverseAdapter.UNIVERSE_CODE, AS_OF);

        assertThat(snapshot.tickers()).containsExactly("Q", "R");
    }

    @Test
    @DisplayName("혼성 단위·우선 주식·신탁증권은 이름으로 뺀다 — 발행사 주식수를 그대로 써서 시총이 부푼다")
    void dropsHybridSecuritiesByName() throws Exception {
        bodies.put("/v2/assets", assets(
                asset("PPLC", "PPL Corporation Corporate Units"),
                asset("BTSGU", "BrightSpring Health Services, Inc. Tangible Equity Unit"),
                asset("LILAP", "Liberty Latin America Ltd. 8.5% Preference Shares"),
                asset("DDT", "Dillard's Capital Trust I 7.50% Capital Securities"),
                asset("ET", "Energy Transfer LP Common Units representing limited partner interests"),
                asset("MID", "Mid Corp.")));
        bodies.put("/files/company_tickers_exchange.json", cikMap(Map.of(
                "PPLC", 1L, "BTSGU", 2L, "LILAP", 3L, "DDT", 4L, "ET", 5L, "MID", 6L)));
        bodies.put("/v2/stocks/bars", bars(Map.of("PPLC", "6", "BTSGU", "5", "LILAP", "4", "DDT", "3",
                "ET", "2", "MID", "1")));

        UniverseSnapshot snapshot = adapter(2, shares(
                share("PPLC", "1"), share("BTSGU", "1"), share("LILAP", "1"), share("DDT", "1"),
                share("ET", "1"), share("MID", "1")))
                .fetchConstituents(UsMarketCapUniverseAdapter.UNIVERSE_CODE, AS_OF);

        assertThat(snapshot.tickers()).as("MLP 보통 지분(Common Units)은 남는다")
                .containsExactly("ET", "MID");
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

    // ── 숨은 상장(ADR-0171) ─────────────────────────────────────────────────────────────

    /** BIG·MID는 목록에 있고 ET는 목록에 없다 — 2026-10-05 실측의 모양. */
    private void hiddenFixture(String etBars) {
        bodies.put("/v2/assets", assets(asset("BIG", "Big Industries Inc."), asset("MID", "Mid Corp.")));
        bodies.put("/files/company_tickers_exchange.json",
                secRows(row("BIG", 1, "Nasdaq"), row("MID", 2, "Nasdaq"), row("ET", 3, "NYSE")));
        bodies.put("/v2/stocks/bars", page(List.of(
                series("BIG", bar("26", "1", 1000L), bar("27", "100", 1000L)),
                series("MID", bar("26", "1", 1000L), bar("27", "50", 1000L)),
                etBars), null));
    }

    @Test
    @DisplayName("목록이 빠뜨린 상장도 마지막 세션에 봉이 있고 단건이 NYSE·NASDAQ면 후보다 — 목록은 상장 사실이 아니다")
    void acceptsHiddenListingAliveOnTheLastSession() throws Exception {
        hiddenFixture(series("ET", bar("26", "1", 1000L), bar("27", "20", 1000L)));
        bodies.put("/v2/assets/ET", single("ET", "Energy Transfer LP Common Units", "NYSE", "inactive"));

        // 시총 BIG 10×100=1,000 · MID 100×50=5,000 · ET 3,000×20=60,000
        UniverseSnapshot snapshot = adapter(2, shares(share("BIG", "10"), share("MID", "100"), share("ET", "3000")))
                .fetchConstituents(UsMarketCapUniverseAdapter.UNIVERSE_CODE, AS_OF);

        assertThat(snapshot.tickers()).as("옛 코드는 목록 밖 ET를 못 봐 MID·BIG을 낸다").containsExactly("ET", "MID");
        assertThat(nameCache.find("US", "ET")).get()
                .extracting(d -> d.name()).isEqualTo("Energy Transfer LP Common Units");
    }

    @Test
    @DisplayName("숨은 상장이 마지막 세션에 봉이 없으면 뺀다 — SEC 파일은 폐지를 늦게 반영한다(DBRG 모양)")
    void dropsHiddenListingWithoutTheLastSessionBar() throws Exception {
        hiddenFixture(series("ET", bar("26", "20", 1000L)));
        bodies.put("/v2/assets/ET", single("ET", "Energy Transfer LP Common Units", "NYSE", "inactive"));

        UniverseSnapshot snapshot = adapter(2, shares(share("BIG", "10"), share("MID", "100"), share("ET", "3000")))
                .fetchConstituents(UsMarketCapUniverseAdapter.UNIVERSE_CODE, AS_OF);

        assertThat(snapshot.tickers()).containsExactly("MID", "BIG");
        assertThat(paths).as("봉으로 먼저 거르면 단건을 안 부른다").noneMatch(p -> p.startsWith("/v2/assets/ET"));
    }

    @Test
    @DisplayName("마지막 세션은 후보 과반의 날짜다 — 이상봉 하나가 숨은 상장 전체를 떨어뜨리지 않는다")
    void lastSessionIsTheMajorityDate() throws Exception {
        bodies.put("/v2/assets", assets(asset("BIG", "Big Industries Inc."), asset("MID", "Mid Corp.")));
        bodies.put("/files/company_tickers_exchange.json",
                secRows(row("BIG", 1, "Nasdaq"), row("MID", 2, "Nasdaq"), row("ET", 3, "NYSE")));
        bodies.put("/v2/stocks/bars", page(List.of(
                series("BIG", bar("26", "1", 1000L), bar("27", "1", 1000L), bar("28", "100", 1000L)),
                series("MID", bar("26", "1", 1000L), bar("27", "50", 1000L)),
                series("ET", bar("26", "1", 1000L), bar("27", "20", 1000L))), null));
        bodies.put("/v2/assets/ET", single("ET", "Energy Transfer LP Common Units", "NYSE", "inactive"));

        UniverseSnapshot snapshot = adapter(2, shares(share("BIG", "10"), share("MID", "100"), share("ET", "3000")))
                .fetchConstituents(UsMarketCapUniverseAdapter.UNIVERSE_CODE, AS_OF);

        assertThat(snapshot.tickers()).as("최댓값(28)이면 ET가 빠진다").contains("ET");
    }

    @Test
    @DisplayName("단건이 404면 뺀다 · 거래소가 NYSE·NASDAQ가 아니면 뺀다")
    void dropsHiddenListingUnknownOrOffExchange() throws Exception {
        hiddenFixture(series("ET", bar("26", "1", 1000L), bar("27", "20", 1000L)));
        UniverseSnapshot unknown = adapter(2, shares(share("BIG", "10"), share("MID", "100"), share("ET", "3000")))
                .fetchConstituents(UsMarketCapUniverseAdapter.UNIVERSE_CODE, AS_OF);
        assertThat(unknown.tickers()).as("단건 404").containsExactly("MID", "BIG");

        bodies.put("/v2/assets/ET", single("ET", "Energy Transfer LP Common Units", "AMEX", "active"));
        UniverseSnapshot offExchange = adapter(2, shares(share("BIG", "10"), share("MID", "100"), share("ET", "3000")))
                .fetchConstituents(UsMarketCapUniverseAdapter.UNIVERSE_CODE, AS_OF);
        assertThat(offExchange.tickers()).as("SEC는 NYSE American도 NYSE로 적는다 — 단건이 가른다")
                .containsExactly("MID", "BIG");
    }

    @Test
    @DisplayName("단건이 5xx면 던진다 — 빼고 넘어가면 상장 종목이 명부에서 빠지는 원래 결함이 재현된다(ADR-0008)")
    void throwsWhenSingleAssetFails() {
        hiddenFixture(series("ET", bar("26", "1", 1000L), bar("27", "20", 1000L)));
        bodies.put("/v2/assets/ET", "{}");
        statuses.put("/v2/assets/ET", 503);

        assertThatThrownBy(() -> adapter(2, shares(share("BIG", "10"), share("MID", "100"), share("ET", "3000")))
                .fetchConstituents(UsMarketCapUniverseAdapter.UNIVERSE_CODE, AS_OF))
                .isInstanceOf(DataSourceException.class)
                .hasMessageContaining("HTTP 503");
    }

    @Test
    @DisplayName("이름 축은 숨은 상장에도 건다 — 저장된 이름이 먼저(단건 전) · 단건 이름이 비면 저장된 이름")
    void appliesTheNameAxisToHiddenListings() throws Exception {
        bodies.put("/v2/assets", assets(asset("BIG", "Big Industries Inc."), asset("MID", "Mid Corp.")));
        bodies.put("/files/company_tickers_exchange.json", secRows(row("BIG", 1, "Nasdaq"), row("MID", 2, "Nasdaq"),
                row("DDT", 3, "NYSE"), row("ET", 4, "NYSE")));
        bodies.put("/v2/stocks/bars", page(List.of(
                series("BIG", bar("26", "1", 1000L), bar("27", "100", 1000L)),
                series("MID", bar("26", "1", 1000L), bar("27", "50", 1000L)),
                series("DDT", bar("26", "1", 1000L), bar("27", "26", 1000L)),
                series("ET", bar("26", "1", 1000L), bar("27", "20", 1000L))), null));
        bodies.put("/v2/assets/ET", single("ET", "", "NYSE", "inactive"));

        UniverseSnapshot snapshot = adapter(2, shares(share("BIG", "10"), share("MID", "100"),
                named("DDT", "100000", "Dillard's Capital Trust I 7.50% Capital Securities"),
                named("ET", "3000", "Energy Transfer LP")))
                .fetchConstituents(UsMarketCapUniverseAdapter.UNIVERSE_CODE, AS_OF);

        assertThat(snapshot.tickers()).containsExactly("ET", "MID");
        assertThat(paths).as("DDT는 저장된 이름에서 빠져 단건을 안 부른다").noneMatch(p -> p.startsWith("/v2/assets/DDT"));
        assertThat(nameCache.find("US", "ET")).get().extracting(d -> d.name()).isEqualTo("Energy Transfer LP");
    }

    @Test
    @DisplayName("점·하이픈 정규화가 숨은 상장에도 걸린다 — 시세는 BRK.B · SEC는 BRK-B")
    void normalizesHiddenTickers() throws Exception {
        bodies.put("/v2/assets", assets(asset("BIG", "Big Industries Inc."), asset("MID", "Mid Corp.")));
        bodies.put("/files/company_tickers_exchange.json",
                secRows(row("BIG", 1, "Nasdaq"), row("MID", 2, "Nasdaq"), row("BRK-B", 3, "NYSE")));
        bodies.put("/v2/stocks/bars", page(List.of(
                series("BIG", bar("26", "1", 1000L), bar("27", "100", 1000L)),
                series("MID", bar("26", "1", 1000L), bar("27", "50", 1000L)),
                series("BRK.B", bar("26", "1", 1000L), bar("27", "500", 1000L))), null));
        bodies.put("/v2/assets/BRK.B", single("BRK.B", "Berkshire Hathaway Inc. Class B", "NYSE", "inactive"));

        UniverseSnapshot snapshot = adapter(2, shares(share("BIG", "10"), share("MID", "100"), share("BRK.B", "1000")))
                .fetchConstituents(UsMarketCapUniverseAdapter.UNIVERSE_CODE, AS_OF);

        assertThat(snapshot.tickers()).containsExactly("BRK.B", "MID");
    }

    @Test
    @DisplayName("마지막 세션 제외가 CIK 중복 제거보다 앞이다 — 낡은 숨은 티커가 대표를 차지한 뒤 빠져 산 클래스까지 지우지 않는다")
    void staleHiddenTickerDoesNotTakeTheCikSeat() throws Exception {
        bodies.put("/v2/assets", assets(asset("BIG", "Big Industries Inc."), asset("LIVE", "Live Corp. Class A")));
        bodies.put("/files/company_tickers_exchange.json",
                secRows(row("BIG", 1, "Nasdaq"), row("LIVE", 9, "NYSE"), row("OLD", 9, "NYSE")));
        bodies.put("/v2/stocks/bars", page(List.of(
                series("BIG", bar("26", "1", 1000L), bar("27", "100", 1000L)),
                series("LIVE", bar("26", "1", 10L), bar("27", "50", 10L)),
                series("OLD", bar("26", "60", 1_000_000L))), null));
        bodies.put("/v2/assets/OLD", single("OLD", "Old Corp. Class B", "NYSE", "inactive"));

        UniverseSnapshot snapshot = adapter(2, shares(share("BIG", "10"), share("LIVE", "100"), share("OLD", "100")))
                .fetchConstituents(UsMarketCapUniverseAdapter.UNIVERSE_CODE, AS_OF);

        assertThat(snapshot.tickers()).containsExactly("LIVE", "BIG");
    }

    @Test
    @DisplayName("숨은 생존자가 상한(200)을 넘으면 MALFORMED다 — 목록이 깨진 것이고 단건을 그만큼 부르지 않는다")
    void rejectsWhenHiddenSurvivorsExceedTheLimit() {
        List<String> secRowList = new ArrayList<>(List.of(row("BIG", 1, "Nasdaq"), row("MID", 2, "Nasdaq")));
        List<String> barList = new ArrayList<>(List.of(
                series("BIG", bar("26", "1", 1000L), bar("27", "100", 1000L)),
                series("MID", bar("26", "1", 1000L), bar("27", "50", 1000L))));
        List<StoredShareCounts.ShareCount> counts = new ArrayList<>(List.of(share("BIG", "10"), share("MID", "100")));
        for (int i = 0; i < 201; i++) {
            String symbol = "H" + i;
            secRowList.add(row(symbol, 100 + i, "NYSE"));
            barList.add(series(symbol, bar("26", "1", 1000L), bar("27", "5", 1000L)));
            counts.add(share(symbol, "1"));
        }
        bodies.put("/v2/assets", assets(asset("BIG", "Big Industries Inc."), asset("MID", "Mid Corp.")));
        bodies.put("/files/company_tickers_exchange.json", secRows(secRowList.toArray(String[]::new)));
        bodies.put("/v2/stocks/bars", page(barList, null));

        assertThatThrownBy(() -> adapter(2, shares(counts.toArray(StoredShareCounts.ShareCount[]::new)))
                .fetchConstituents(UsMarketCapUniverseAdapter.UNIVERSE_CODE, AS_OF))
                .isInstanceOf(DataSourceException.class)
                .hasMessageContaining("상한 200");
        assertThat(paths).noneMatch(p -> p.startsWith("/v2/assets/H"));
    }

    @Test
    @DisplayName("같은 어댑터의 두 번째 호출은 단건을 다시 안 부른다 — 복원 격자 2회째 0콜")
    void memoizesSingleAssetAcrossCalls() throws Exception {
        hiddenFixture(series("ET", bar("26", "1", 1000L), bar("27", "20", 1000L)));
        bodies.put("/v2/assets/ET", single("ET", "Energy Transfer LP Common Units", "NYSE", "inactive"));
        UsMarketCapUniverseAdapter adapter =
                adapter(2, shares(share("BIG", "10"), share("MID", "100"), share("ET", "3000")));

        adapter.fetchConstituents(UsMarketCapUniverseAdapter.UNIVERSE_CODE, AS_OF);
        adapter.fetchConstituents(UsMarketCapUniverseAdapter.UNIVERSE_CODE, AS_OF.minusDays(1));

        assertThat(paths.stream().filter(p -> p.startsWith("/v2/assets/ET")).count()).isEqualTo(1);
        assertThat(paths.stream().filter(p -> p.startsWith("/files/company_tickers_exchange.json")).count())
                .as("SEC 판은 하루 안이면 한 번").isEqualTo(1);
    }

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
        return (marketCode, oldestPeriodEnd, asOf) -> list;
    }

    private static StoredShareCounts.ShareCount share(String ticker, String shares) {
        return new StoredShareCounts.ShareCount(
                ticker, new BigDecimal(shares), "3711", AS_OF.minusMonths(3), AS_OF.minusMonths(1), false,
                ticker + " Stored Name");
    }

    private static StoredShareCounts.ShareCount mismatched(String ticker, String shares) {
        return new StoredShareCounts.ShareCount(
                ticker, new BigDecimal(shares), "3711", AS_OF.minusMonths(3), AS_OF.minusMonths(1), true,
                ticker + " Stored Name");
    }

    private static StoredShareCounts.ShareCount sic(String ticker, String shares, String sicCode) {
        return new StoredShareCounts.ShareCount(
                ticker, new BigDecimal(shares), sicCode, AS_OF.minusMonths(3), AS_OF.minusMonths(1), false,
                ticker + " Stored Name");
    }

    private static String asset(String symbol, String name) {
        return """
                {"symbol":"%s","name":"%s","exchange":"NASDAQ","status":"active","tradable":true}"""
                .formatted(symbol, name);
    }

    private static String assets(String... assets) {
        return "[" + String.join(",", assets) + "]";
    }

    /** SEC 매핑 한 줄 — 거래소를 직접 준다. */
    private static String row(String ticker, long cik, String exchange) {
        return "[%d,\"%s Inc.\",\"%s\",\"%s\"]".formatted(cik, ticker, ticker, exchange);
    }

    private static String secRows(String... rows) {
        return """
                {"fields":["cik","name","ticker","exchange"],"data":[%s]}""".formatted(String.join(",", rows));
    }

    /** 단건 {@code /v2/assets/{symbol}} 응답. */
    private static String single(String symbol, String name, String exchange, String status) {
        return """
                {"symbol":"%s","name":"%s","exchange":"%s","status":"%s","tradable":false}"""
                .formatted(symbol, name, exchange, status);
    }

    private static StoredShareCounts.ShareCount named(String ticker, String shares, String storedName) {
        return new StoredShareCounts.ShareCount(
                ticker, new BigDecimal(shares), "3711", AS_OF.minusMonths(3), AS_OF.minusMonths(1), false,
                storedName);
    }

    private static String cikMap(Map<String, Long> byTicker) {
        List<String> rows = new ArrayList<>();
        byTicker.forEach((ticker, cik) ->
                rows.add("[%d,\"%s Inc.\",\"%s\",\"Nasdaq\"]".formatted(cik, ticker, ticker)));
        return """
                {"fields":["cik","name","ticker","exchange"],"data":[%s]}"""
                .formatted(String.join(",", rows));
    }

    /** 심볼마다 봉 둘을 준다 — 마지막 봉을 쓰는지가 계약이다. 거래량은 모두 같다(대표 선택에 안 끼게). */
    private static String bars(Map<String, String> closeBySymbol) {
        List<String> entries = new ArrayList<>();
        closeBySymbol.forEach((symbol, close) -> entries.add("""
                "%s":[{"t":"2026-08-26T04:00:00Z","c":1.0,"v":1000},{"t":"2026-08-27T04:00:00Z","c":%s,"v":1000}]"""
                .formatted(symbol, close)));
        return page(entries, null);
    }

    /** 봉을 그대로 적는다 — {@code "SYM":[{...},...]} 조각들. */
    private static String page(List<String> entries, String nextPageToken) {
        return "{\"bars\":{%s},\"next_page_token\":%s}".formatted(String.join(",", entries),
                nextPageToken == null ? "null" : "\"" + nextPageToken + "\"");
    }

    /** 봉 하나. 거래량이 null이면 {@code v}를 아예 안 싣는다. */
    private static String bar(String day, String close, Long volume) {
        return volume == null
                ? "{\"t\":\"2026-08-%sT04:00:00Z\",\"c\":%s}".formatted(day, close)
                : "{\"t\":\"2026-08-%sT04:00:00Z\",\"c\":%s,\"v\":%d}".formatted(day, close, volume);
    }

    private static String series(String symbol, String... bars) {
        return "\"%s\":[%s]".formatted(symbol, String.join(",", bars));
    }
}
