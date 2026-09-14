package com.stockscreener.pipeline.datasource.us;

import com.stockscreener.pipeline.datasource.DataSourceException;
import com.stockscreener.pipeline.datasource.RawFinancialReport;
import com.stockscreener.pipeline.datasource.SecurityRef;
import com.stockscreener.pipeline.datasource.http.DataSourceProperties;
import com.stockscreener.pipeline.datasource.http.ExternalApiClientFactory;
import com.stockscreener.pipeline.datasource.http.TokenBucketRateLimiter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link SecEdgarFinancialAdapter} 관통.
 *
 * <p>이 테스트가 지키는 것은 <b>"우리 파싱이 맞는가"가 아니라 "벤더가 아직 그대로인가"</b>다.
 * 파싱은 녹화된 fixture로 {@code SecEdgarFinancialAdapterTest}가 검증하고, 소스의 모양은
 * {@link SecEdgarProbeLiveTest}가 잰다. 여기서는 <b>목으로는 증명할 수 없는 것</b>만 본다.
 */
@Tag("live")
@EnabledIfEnvironmentVariable(named = "SEC_EDGAR_USER_AGENT", matches = ".+",
        disabledReason = "SEC_EDGAR_USER_AGENT가 없어 건너뛴다")
class SecEdgarLiveTest {

    private static final int FROM_FISCAL_YEAR = 2022;

    @Test
    @DisplayName("[live] 실제 companyfacts에서 기간이 겹치지 않는 보고서가 나온다")
    void reconstructsPeriods() throws DataSourceException {
        List<RawFinancialReport> reports = adapter()
                .fetchReports(new SecurityRef(1L, UsMarket.CODE, "AAPL"), FROM_FISCAL_YEAR).reports();

        assertThat(reports).as("0건이면 「없다」가 아니라 재구성이 깨진 것이다").isNotEmpty();

        Map<String, Integer> byPeriod = new TreeMap<>();
        for (RawFinancialReport r : reports) {
            byPeriod.merge(r.fiscalYear() + "/" + r.fiscalPeriod(), 1, Integer::sum);
        }
        System.out.printf("%nAAPL %d년 이후 보고서 %d건 — 라벨별 판 수 %s%n",
                FROM_FISCAL_YEAR, reports.size(), byPeriod);
        reports.stream().limit(8).forEach(r -> System.out.printf(
                "  %d/%-2s %s~%s 공시=%s %s 통화=%s 매출=%s 순이익=%s 자산=%s 주식수=%s%n",
                r.fiscalYear(), r.fiscalPeriod(), r.periodStart(), r.periodEnd(), r.publishedAt(),
                r.reportCode(), r.currencyCode(), r.revenue(), r.netIncome(), r.totalAssets(),
                r.sharesOutstanding()));

        reports.stream().filter(r -> r.fiscalYear() == 2024 && "FY".equals(r.fiscalPeriod()))
                .forEach(r -> System.out.printf(
                        "%n손대조용 AAPL FY2024: 매출=%s 영업이익=%s 순이익=%s 자산=%s 자본=%s%n"
                                + "  부채=%s 유동자산=%s 유동부채=%s 이익잉여금=%s OCF=%s%n"
                                + "  주식수=%s EPS=%s 매출원가=%s 현금=%s capex=%s 재고=%s%n",
                        r.revenue(), r.operatingIncome(), r.netIncome(), r.totalAssets(),
                        r.totalEquity(), r.totalLiabilities(), r.currentAssets(),
                        r.currentLiabilities(), r.retainedEarnings(), r.operatingCashFlow(),
                        r.sharesOutstanding(), r.eps(), r.costOfSales(), r.cashAndEquivalents(),
                        r.capex(), r.inventories()));

        System.out.println("판이 둘 이상인 라벨의 비어 있지 않은 컬럼:");
        reports.stream()
                .filter(r -> byPeriod.get(r.fiscalYear() + "/" + r.fiscalPeriod()) > 1)
                .limit(6)
                .forEach(r -> System.out.printf("  %d/%-2s 공시=%s → %s%n",
                        r.fiscalYear(), r.fiscalPeriod(), r.publishedAt(), nonNullOf(r)));

        // 저장 키에 기간이 없다. 라벨이 겹치면 제약이 걸리는 것이 아니라 앞 기간의 최신
        // 표시가 조용히 내려간다 — 그래서 어댑터가 스스로 겹치지 않게 내야 한다.
        for (Map.Entry<String, Integer> e : byPeriod.entrySet()) {
            assertThat(e.getValue())
                    .as("%s의 판이 %d개다 — 값이 같은 재보고를 접지 못하면 가짜 정정이 된다",
                            e.getKey(), e.getValue())
                    .isLessThanOrEqualTo(3);
        }

        // 한 라벨 안에서 기간이 하나여야 한다. 둘이면 12개월과 3개월이 같은 라벨을 받은 것이다.
        Map<String, String> windowOf = new TreeMap<>();
        for (RawFinancialReport r : reports) {
            String key = r.fiscalYear() + "/" + r.fiscalPeriod();
            String window = r.periodStart() + "~" + r.periodEnd();
            assertThat(windowOf.putIfAbsent(key, window))
                    .as("%s에 기간이 둘이다: %s vs %s", key, windowOf.get(key), window)
                    .satisfiesAnyOf(v -> assertThat(v).isNull(), v -> assertThat(v).isEqualTo(window));
        }

        assertThat(reports).allSatisfy(r -> {
            assertThat(r.currencyCode()).as("통화는 3자 ISO여야 한다 — character(3) 컬럼이다")
                    .hasSize(3);
            assertThat(r.fiscalPeriod()).as("ck_fs_period가 허용하는 값이어야 한다")
                    .isIn("FY", "Q1", "Q2", "Q3", "Q4");
            assertThat(r.periodEnd()).as("ck_fs_range").isAfter(r.periodStart());
        });
    }

    @Test
    @DisplayName("[live] 분기 넷이 회계연도를 이룬다 — Q4를 유도하지 않으면 TTM이 15개월이 된다")
    void quartersCoverTheYear() throws DataSourceException {
        List<RawFinancialReport> reports = adapter()
                .fetchReports(new SecurityRef(1L, UsMarket.CODE, "MSFT"), FROM_FISCAL_YEAR).reports();
        Map<Integer, java.util.Set<String>> quarters = new TreeMap<>();
        for (RawFinancialReport r : reports) {
            if (!"FY".equals(r.fiscalPeriod())) {
                quarters.computeIfAbsent(r.fiscalYear(), y -> new java.util.TreeSet<>())
                        .add(r.fiscalPeriod());
            }
        }
        System.out.printf("MSFT 회계연도별 분기: %s%n", quarters);
        assertThat(quarters).as("분기가 하나도 없으면 아래 단정은 「잴 것이 없었다」다").isNotEmpty();
        assertThat(quarters.values())
                .as("네 분기가 다 있어야 TTM 네 행이 열두 달을 덮는다")
                .anySatisfy(q -> assertThat(q).containsExactly("Q1", "Q2", "Q3", "Q4"));
    }

    @Test
    @DisplayName("[live] 매핑 파일에 없는 티커는 NOT_FOUND다 — 예외이지 조용한 0건이 아니다")
    void unknownTickerIsNotFound() {
        assertThatThrownBy(() -> adapter()
                .fetchReports(new SecurityRef(2L, UsMarket.CODE, "KIM.PRL"), FROM_FISCAL_YEAR))
                .isInstanceOf(DataSourceException.class)
                .hasFieldOrPropertyWithValue("kind", DataSourceException.Kind.NOT_FOUND);
    }

    @Test
    @DisplayName("[live] 같은 회계연도를 두 번 받아도 같은 판이 나온다 — 멱등")
    void sameInputGivesSameReports() throws DataSourceException {
        SecEdgarFinancialAdapter adapter = adapter();
        SecurityRef nvda = new SecurityRef(3L, UsMarket.CODE, "NVDA");
        List<RawFinancialReport> first = adapter.fetchReports(nvda, FROM_FISCAL_YEAR).reports();
        List<RawFinancialReport> second = adapter.fetchReports(nvda, FROM_FISCAL_YEAR).reports();
        assertThat(second).as("두 번째가 다르면 재구성이 상태에 의존한다").isEqualTo(first);
    }

    private static List<String> nonNullOf(RawFinancialReport r) {
        List<String> on = new java.util.ArrayList<>();
        if (r.revenue() != null) { on.add("revenue"); }
        if (r.operatingIncome() != null) { on.add("opInc"); }
        if (r.netIncome() != null) { on.add("netInc"); }
        if (r.totalAssets() != null) { on.add("assets"); }
        if (r.totalEquity() != null) { on.add("equity"); }
        if (r.totalLiabilities() != null) { on.add("liab"); }
        if (r.currentAssets() != null) { on.add("curAssets"); }
        if (r.currentLiabilities() != null) { on.add("curLiab"); }
        if (r.retainedEarnings() != null) { on.add("retained"); }
        if (r.operatingCashFlow() != null) { on.add("ocf"); }
        if (r.sharesOutstanding() != null) { on.add("shares"); }
        if (r.eps() != null) { on.add("eps"); }
        if (r.costOfSales() != null) { on.add("cogs"); }
        if (r.cashAndEquivalents() != null) { on.add("cash"); }
        if (r.capex() != null) { on.add("capex"); }
        if (r.inventories() != null) { on.add("inv"); }
        return on;
    }

    private static SecEdgarFinancialAdapter adapter() {
        DataSourceProperties properties = new DataSourceProperties();
        properties.getSecEdgar().setApiKey(System.getenv("SEC_EDGAR_USER_AGENT"));
        ExternalApiClientFactory factory = new ExternalApiClientFactory();
        // 탐침과 같은 간격(초당 5회)으로 둔다. 0으로 두면 SEC의 초당 한도를 넘길 수 있다.
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter("sec-edgar", 300, 0);
        return new SecEdgarFinancialAdapter(factory, properties, limiter,
                new SecTickerCikCache(factory, properties, limiter),
                properties.getSecEdgar().getBaseUrl());
    }
}
