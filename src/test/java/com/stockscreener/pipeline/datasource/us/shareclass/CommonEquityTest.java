package com.stockscreener.pipeline.datasource.us.shareclass;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 보통주 자본 판정(탐색 {@code PERSHARE-DEN-PLAN-1026.md} v1.1) — 값은 2026-10-26에 받은 실제 공시 인스턴스의 꼴을 옮겼다. 무너지는 방식이
 * 조용하다: 우선주를 못 빼면 PBR이 그럴듯하게 낮고(VST 8.62 대 약 1.8배), 액면을 장부가로 읽으면 0을 빼서 지금과 같은 값이 나간다.
 */
class CommonEquityTest {

    private static final LocalDate FY = LocalDate.of(2025, 12, 31);
    private static final LocalDate Q2 = LocalDate.of(2026, 6, 30);
    private static final String COMPONENT = "StatementEquityComponentsAxis=";
    private static final String SERIES = "StatementClassOfStockAxis=";

    /** 인스턴스 하나의 재료를 짓는다. */
    private static final class Filing {
        private final Map<String, LinkedHashMap<ClassFacts.Slot, ClassFacts.Measured>> equity = new LinkedHashMap<>();
        private final Map<String, LinkedHashMap<ClassFacts.MemberPeriod, ClassFacts.Measured>> durations = new LinkedHashMap<>();

        Filing at(String concept, String key, LocalDate date, double value) {
            equity.computeIfAbsent(concept, k -> new LinkedHashMap<>())
                    .put(new ClassFacts.Slot(key, null, date), new ClassFacts.Measured(value, 0));
            return this;
        }

        Filing at(String concept, LocalDate date, double value) {
            return at(concept, ClassFacts.AXISLESS, date, value);
        }

        Filing during(String concept, LocalDate start, LocalDate end, double value) {
            durations.computeIfAbsent(concept, k -> new LinkedHashMap<>()).put(
                    new ClassFacts.MemberPeriod(ClassFacts.AXISLESS, new ClassFacts.Period(start, end)), new ClassFacts.Measured(value, 0));
            return this;
        }

        Filing duringEquity(String concept, String key, LocalDate start, LocalDate end, double value) {
            equity.computeIfAbsent(concept, k -> new LinkedHashMap<>())
                    .put(new ClassFacts.Slot(key, start, end), new ClassFacts.Measured(value, 0));
            return this;
        }

        ClassFacts facts() {
            return new ClassFacts(durations, new LinkedHashMap<>(), "10-K", equity);
        }
    }

    private static CommonEquity.Value judged(Map<String, ClassFacts> facts, String accession, LocalDate date) {
        return CommonEquity.judge(facts).get(new CommonEquity.Key(accession, date));
    }

    @Test
    @DisplayName("자본변동표 우선주 성분이 장부가다 — companyfacts 축 없는 태그가 없어도(MS 9,750M)")
    void theStatementComponentIsTheCarryingAmount() {
        ClassFacts f = new Filing().at("StockholdersEquity", FY, 111_632e6)
                .at("StockholdersEquityIncludingPortionAttributableToNoncontrollingInterest", COMPONENT + "us-gaap:PreferredStockMember", FY, 9_750e6)
                .during("DividendsPreferredStock", FY.minusYears(1).plusDays(1), FY, 612e6).facts();

        CommonEquity.Value v = judged(Map.of("a", f), "a", FY);

        assertThat(v.branch()).isEqualTo("stmt");
        assertThat(v.nonCommon()).isEqualTo(9_750e6);
    }

    @Test
    @DisplayName("성분이 액면이면(배당이 성분의 25%를 넘음) 시리즈 주당 청산가 × 주식수로 간다 — AUB 17,250주 × 10,000")
    void aParComponentFallsToLiquidationPerShareTimesShares() {
        ClassFacts f = new Filing().at("StockholdersEquity", FY, 5_006e6)
                .at("StockholdersEquity", COMPONENT + "us-gaap:PreferredStockMember", FY, 173_000)
                .at("PreferredStockValue", FY, 173_000)
                .at("PreferredStockSharesOutstanding", SERIES + "us-gaap:SeriesAPreferredStockMember", FY, 17_250)
                .at("PreferredStockLiquidationPreference", SERIES + "us-gaap:SeriesAPreferredStockMember", FY, 10_000)
                .during("PreferredStockDividendsIncomeStatementImpact", FY.minusYears(1).plusDays(1), FY, 11.9e6).facts();

        CommonEquity.Value v = judged(Map.of("a", f), "a", FY);

        assertThat(v.branch()).isEqualTo("liq");
        assertThat(v.nonCommon()).isEqualTo(172.5e6);
    }

    @Test
    @DisplayName("10-Q가 잔액을 안 실어도 우선주 주식수가 10-K와 같으면 장부가를 잇는다 — AXP 1,600주 · 1,584M")
    void theSameShareCountCarriesTheCarryingAmount() {
        ClassFacts annual = new Filing().at("StockholdersEquity", FY, 33_474e6)
                .at("StockholdersEquity", COMPONENT + "us-gaap:PreferredStockMember", FY, 0)
                .at("PreferredStockValue", FY, 0).at("PreferredStockSharesOutstanding", FY, 1_600)
                .at("PreferredStockValueOutstanding", SERIES + "us-gaap:SeriesDPreferredStockMember", FY, 1_584e6)
                .at("PreferredStockLiquidationPreferenceValue", SERIES + "us-gaap:SeriesDPreferredStockMember", FY, 1_600e6).facts();
        ClassFacts quarter = new Filing().at("StockholdersEquity", Q2, 34_280e6)
                .at("StockholdersEquity", COMPONENT + "us-gaap:PreferredStockMember", Q2, 0)
                .at("PreferredStockValue", Q2, 0).at("PreferredStockSharesOutstanding", Q2, 1_600)
                .during("DividendsPreferredStock", Q2.minusMonths(6).plusDays(1), Q2, 29e6).facts();

        Map<CommonEquity.Key, CommonEquity.Value> judged = CommonEquity.judge(Map.of("k", annual, "q", quarter));

        assertThat(judged.get(new CommonEquity.Key("k", FY)).nonCommon()).isEqualTo(1_584e6);
        assertThat(judged.get(new CommonEquity.Key("q", Q2)).branch()).isEqualTo("carry");
        assertThat(judged.get(new CommonEquity.Key("q", Q2)).nonCommon()).isEqualTo(1_584e6);
    }

    @Test
    @DisplayName("주식수가 바뀌면 잇지 않는다 — 새 발행 · 상환이 장부가를 움직였다")
    void aDifferentShareCountIsNotCarried() {
        ClassFacts annual = new Filing().at("StockholdersEquity", FY, 33_474e6).at("PreferredStockSharesOutstanding", FY, 1_600)
                .at("PreferredStockValueOutstanding", SERIES + "us-gaap:SeriesDPreferredStockMember", FY, 1_584e6).facts();
        ClassFacts quarter = new Filing().at("StockholdersEquity", Q2, 34_280e6).at("PreferredStockSharesOutstanding", Q2, 2_000)
                .during("DividendsPreferredStock", Q2.minusMonths(6).plusDays(1), Q2, 29e6).facts();

        assertThat(judged(Map.of("k", annual, "q", quarter), "q", Q2).decided()).isFalse();
    }

    @Test
    @DisplayName("청산가가 메자닌 간극 안이면 자본 밖이라 0이다 — CELH 부채 2,178 + 자본 1,181 대 합계 5,120")
    void aMezzanineLiquidationIsNotSubtracted() {
        ClassFacts f = new Filing().at("StockholdersEquity", FY, 1_181e6).at("Liabilities", FY, 2_178e6)
                .at("LiabilitiesAndStockholdersEquity", FY, 5_120e6)
                .at("PreferredStockLiquidationPreferenceValue", FY, 1_135e6).facts();

        CommonEquity.Value v = judged(Map.of("a", f), "a", FY);

        assertThat(v.branch()).isEqualTo("mezz");
        assertThat(v.nonCommon()).isZero();
    }

    @Test
    @DisplayName("LP: GP 자본(음수)을 뺀다 — CQP 414M − (−2,742M) = 보통 단위 3,156M")
    void theGeneralPartnerCapitalIsNotCommon() {
        ClassFacts f = new Filing().at("PartnersCapital", FY, 414e6).at("GeneralPartnersCapitalAccount", FY, -2_742e6).facts();

        CommonEquity.Value v = judged(Map.of("a", f), "a", FY);

        assertThat(v.branch()).isEqualTo("lp");
        assertThat(v.nonCommon()).isEqualTo(-2_742e6);
        assertThat(v.ncavNonCommon()).as("GP 자본은 보통주보다 앞선 청구권이 아니다").isZero();
    }

    @Test
    @DisplayName("LP 클래스 축에만 실린 우선 단위 — 가중평균이 실린 구성원이 보통 단위다(PAA 우선 A 1,248 + B 787)")
    void lpClassesWithoutWeightedUnitsAreNotCommon() {
        String lp = "LimitedPartnersCapitalAccountByClassAxis=";
        ClassFacts f = new Filing().at("PartnersCapital", FY, 9_836e6)
                .at("LimitedPartnersCapitalAccount", lp + "paa:CommonUnitsMember", FY, 7_801e6)
                .at("LimitedPartnersCapitalAccount", lp + "paa:SeriesAPreferredUnitsMember", FY, 1_248e6)
                .at("LimitedPartnersCapitalAccount", lp + "paa:SeriesBPreferredUnitsMember", FY, 787e6)
                .duringEquity("WeightedAverageLimitedPartnershipUnitsOutstanding", lp + "paa:CommonUnitsMember",
                        FY.minusYears(1).plusDays(1), FY, 704e6).facts();

        CommonEquity.Value v = judged(Map.of("a", f), "a", FY);

        assertThat(v.branch()).isEqualTo("lp");
        assertThat(v.nonCommon()).isEqualTo(2_035e6);
    }

    @Test
    @DisplayName("성분 명시 0이면 날짜만 남은 옛 시리즈 청산가를 안 뺀다 — NI 2025(성분 0 · 시리즈 청산가 1,763M)")
    void anExplicitZeroComponentOverrulesStaleSeriesLiquidation() {
        ClassFacts f = new Filing().at("StockholdersEquity", FY, 9_450e6)
                .at("StockholdersEquityIncludingPortionAttributableToNoncontrollingInterest", COMPONENT + "us-gaap:PreferredStockMember", FY, 0)
                .at("PreferredStockLiquidationPreferenceValue", SERIES + "us-gaap:SeriesAPreferredStockMember", FY, 400e6)
                .at("PreferredStockLiquidationPreferenceValue", SERIES + "us-gaap:SeriesBPreferredStockMember", FY, 500e6)
                .at("PreferredStockLiquidationPreferenceValue", SERIES + "us-gaap:SeriesCPreferredStockMember", FY, 863e6).facts();

        CommonEquity.Value v = judged(Map.of("a", f), "a", FY);

        assertThat(v.branch()).isEqualTo("zero");
        assertThat(v.nonCommon()).isZero();
    }

    @Test
    @DisplayName("전환 · 참가형(전환 조건 · 선순위 조건 없음 · 배당 없음)은 못 정함이다 — 태그 유무와 무관(MDGL · COGT)")
    void aCommonEquivalentConvertibleIsUndecided() {
        ClassFacts withTag = new Filing().at("StockholdersEquity", FY, 532e6)
                .at("PreferredStockValue", SERIES + "us-gaap:SeriesAPreferredStockMember", FY, 64.75e6)
                .at("PreferredStockSharesOutstanding", SERIES + "us-gaap:SeriesAPreferredStockMember", FY, 39_414)
                .at("PreferredStockConvertibleConversionRatio", SERIES + "us-gaap:SeriesAPreferredStockMember", FY, 250).facts();
        ClassFacts withoutTag = new Filing().at("StockholdersEquity", FY, 603e6).at("PreferredStockValue", FY, 0)
                .at("PreferredStockSharesOutstanding", FY, 2_369_797)
                .at("PreferredStockConvertibleConversionRatio", FY, 1).facts();

        assertThat(judged(Map.of("a", withTag), "a", FY).branch()).isEqualTo("conv");
        assertThat(judged(Map.of("a", withoutTag), "a", FY).branch()).isEqualTo("conv");
        assertThat(judged(Map.of("a", withTag), "a", FY).decided()).isFalse();
    }

    @Test
    @DisplayName("우선 배당이 비지배 손익과 같으면 자회사 우선주다 — 모회사 자본에서 뺄 것 0(AEE)")
    void subsidiaryPreferredDividendsMeanNothingToSubtract() {
        ClassFacts f = new Filing().at("StockholdersEquity", Q2, 13_687e6)
                .during("PreferredStockDividendsIncomeStatementImpact", Q2.minusMonths(6).plusDays(1), Q2, 3e6)
                .during("NetIncomeLossAttributableToNoncontrollingInterest", Q2.minusMonths(6).plusDays(1), Q2, 3e6).facts();

        CommonEquity.Value v = judged(Map.of("a", f), "a", Q2);

        assertThat(v.branch()).isEqualTo("subsid");
        assertThat(v.nonCommon()).isZero();
    }

    @Test
    @DisplayName("액면 × 주식수와 같은 성분은 장부가가 아니다 — 배당이 없어도(RKLB 45,951,250주 × 0.0001)")
    void aComponentEqualToParTimesSharesIsPar() {
        ClassFacts f = new Filing().at("StockholdersEquity", FY, 1_722e6)
                .at("StockholdersEquity", COMPONENT + "us-gaap:PreferredStockMember", FY, 4_595)
                .at("PreferredStockParOrStatedValuePerShare", FY, 0.0001).at("PreferredStockSharesOutstanding", FY, 45_951_250)
                .at("PreferredStockDividendRatePercentage", FY, 0.05).facts();

        assertThat(judged(Map.of("a", f), "a", FY).branch()).isNotEqualTo("stmt");
    }

    @Test
    @DisplayName("증거 없는 성분은 받지 않는다 — ZETA는 우선주 없이 APIC 열을 PreferredStockIncludingAdditionalPaidInCapitalMember로 실었다")
    void anUncorroboratedComponentIsNotPreferred() {
        ClassFacts f = new Filing().at("StockholdersEquity", Q2, 926e6)
                .at("StockholdersEquity", COMPONENT + "us-gaap:PreferredStockIncludingAdditionalPaidInCapitalMember", Q2, 1_997e6).facts();

        CommonEquity.Value v = judged(Map.of("a", f), "a", Q2);

        assertThat(v.branch()).isNotEqualTo("stmt");
        assertThat(v.nonCommon()).isZero();
    }

    @Test
    @DisplayName("연 배당이 청산가의 25%를 넘으면 그 값은 총액이 아니다 — 주식수가 같은 다른 날짜 값을 잇는다(FLR 96M 대 600M)")
    void aLiquidationTheDividendsContradictIsDropped() {
        ClassFacts issued = new Filing().at("StockholdersEquity", LocalDate.of(2021, 6, 30), 1_500e6)
                .at("PreferredStockSharesOutstanding", LocalDate.of(2021, 6, 30), 600_000)
                .at("PreferredStockLiquidationPreference", LocalDate.of(2021, 6, 30), 1_000).facts();
        ClassFacts later = new Filing().at("StockholdersEquity", Q2, 1_600e6).at("PreferredStockSharesOutstanding", Q2, 600_000)
                .at("PreferredStockLiquidationPreferenceValue", SERIES + "us-gaap:ConvertiblePreferredStockMember", Q2, 96e6)
                .during("DividendsPreferredStock", Q2.minusMonths(6).plusDays(1), Q2, 20e6).facts();

        Map<CommonEquity.Key, CommonEquity.Value> judged = CommonEquity.judge(Map.of("i", issued, "q", later));

        assertThat(judged.get(new CommonEquity.Key("i", LocalDate.of(2021, 6, 30))).nonCommon()).isEqualTo(600e6);
        assertThat(judged.get(new CommonEquity.Key("q", Q2)).branch()).isEqualTo("carry");
        assertThat(judged.get(new CommonEquity.Key("q", Q2)).nonCommon()).isEqualTo(600e6);
    }

    @Test
    @DisplayName("주당 청산가 × 주식수는 시리즈 장부가를 거부하지 못한다 — 상환된 시리즈의 주식수까지 곱한다(STT 3,500M 대 2,468M)")
    void aPerShareProductDoesNotVetoSeriesCarrying() {
        String d = SERIES + "us-gaap:SeriesDPreferredStockMember";
        String g = SERIES + "us-gaap:SeriesGPreferredStockMember";
        ClassFacts f = new Filing().at("StockholdersEquity", Q2, 28_268e6)
                .at("PreferredStockSharesOutstanding", d, Q2, 7_500).at("PreferredStockValue", d, Q2, 0)
                .at("PreferredStockSharesOutstanding", g, Q2, 24_680).at("PreferredStockValue", g, Q2, 2_468e6)
                .at("PreferredStockLiquidationPreference", d, Q2, 100_000).at("PreferredStockLiquidationPreference", g, Q2, 100_000).facts();

        CommonEquity.Value v = judged(Map.of("a", f), "a", Q2);

        assertThat(v.branch()).isEqualTo("sertag");
        assertThat(v.nonCommon()).isEqualTo(2_468e6);
    }

    @Test
    @DisplayName("청구권은 부채 · 자본 합계를 못 넘는다 — 단위가 엉킨 곱(MS 9.8 × 10^15)은 청산가가 아니다")
    void aLiquidationAboveTheBalanceSheetIsDropped() {
        String a = SERIES + "us-gaap:SeriesAPreferredStockMember";
        ClassFacts f = new Filing().at("StockholdersEquity", Q2, 116_329e6).at("LiabilitiesAndStockholdersEquity", Q2, 1_675_057e6)
                .at("PreferredStockSharesOutstanding", a, Q2, 1_267_382).at("PreferredStockLiquidationPreference", a, Q2, 7.758e9)
                .during("DividendsPreferredStock", Q2.minusMonths(6).plusDays(1), Q2, 301e6).facts();

        assertThat(judged(Map.of("a", f), "a", Q2).decided()).isFalse();
    }

    @Test
    @DisplayName("같은 날짜에 주식수를 실은 공시가 있으면 비교열의 「명시 0」을 안 받고 그 값을 옮긴다(FLR — 우선주를 APIC에 싣는다)")
    void aComparativeZeroDefersToTheFilingThatHeldShares() {
        ClassFacts own = new Filing().at("StockholdersEquity", FY, 1_500e6)
                .at("StockholdersEquity", COMPONENT + "us-gaap:PreferredStockMember", FY, 0)
                .at("PreferredStockSharesOutstanding", FY, 600_000).at("PreferredStockLiquidationPreference", FY, 1_000).facts();
        ClassFacts later = new Filing().at("StockholdersEquity", FY, 1_500e6)
                .at("StockholdersEquity", COMPONENT + "us-gaap:PreferredStockMember", FY, 0).facts();

        Map<CommonEquity.Key, CommonEquity.Value> judged = CommonEquity.judge(Map.of("own", own, "later", later));

        assertThat(judged.get(new CommonEquity.Key("later", FY)).nonCommon()).isEqualTo(600e6);
    }

    @Test
    @DisplayName("행: 신호 없는 발행사의 companyfacts 공시는 none, companyfacts가 안 덮는 보충 공시 · 신호 발행사는 못 정함")
    void rowsWithoutAValueAreNoneOnlyWhereCompanyFactsCovers() {
        CommonIncomeRows.Row covered = new CommonIncomeRows.Row(2025, "FY", 0, FY.minusYears(1).plusDays(1), FY, FY.plusMonths(2), "cf", null);
        CommonIncomeRows.Row supplement = new CommonIncomeRows.Row(2026, "Q2", 0, Q2.minusMonths(3).plusDays(1), Q2, Q2.plusMonths(1), "supp",
                null);
        Map<String, IssuerFacts.Filing> filings = Map.of("cf", new IssuerFacts.Filing("10-K", FY.plusMonths(2)),
                "supp", new IssuerFacts.Filing("10-Q", Q2.plusMonths(1)));

        List<CommonEquityRows.Resolved> quiet = CommonEquityRows.resolve(List.of(covered, supplement), Map.of(), filings, Set.of("cf"), false);
        List<CommonEquityRows.Resolved> signal = CommonEquityRows.resolve(List.of(covered), Map.of(), filings, Set.of("cf"), true);

        assertThat(quiet.get(0).branch()).isEqualTo("none");
        assertThat(quiet.get(0).nonCommon()).isZero();
        assertThat(quiet.get(1).branch()).isEqualTo("nokey");
        assertThat(quiet.get(1).nonCommon()).isNull();
        assertThat(signal.get(0).nonCommon()).isNull();
    }

    @Test
    @DisplayName("행: 행 공시가 그 날짜를 안 실었으면 제출일 ≤ 행 공시일인 최신 공시의 같은 날짜 값")
    void aRowTakesTheLatestEarlierFilingOfTheSameDate() {
        CommonIncomeRows.Row row = new CommonIncomeRows.Row(2025, "FY", 1, FY.minusYears(1).plusDays(1), FY, LocalDate.of(2026, 5, 1),
                "later", null);
        Map<String, IssuerFacts.Filing> filings = Map.of("k", new IssuerFacts.Filing("10-K", LocalDate.of(2026, 2, 20)),
                "late", new IssuerFacts.Filing("10-K/A", LocalDate.of(2026, 9, 1)),
                "later", new IssuerFacts.Filing("10-Q", LocalDate.of(2026, 5, 1)));
        Map<CommonEquity.Key, CommonEquity.Value> judged = Map.of(
                new CommonEquity.Key("k", FY), new CommonEquity.Value(100e6, 100e6, "stmt"),
                new CommonEquity.Key("late", FY), new CommonEquity.Value(999e6, 999e6, "stmt"));

        CommonEquityRows.Resolved r = CommonEquityRows.resolve(List.of(row), judged, filings, Set.of("k", "later"), true).get(0);

        assertThat(r.nonCommon()).isEqualTo(100e6);
    }
}
