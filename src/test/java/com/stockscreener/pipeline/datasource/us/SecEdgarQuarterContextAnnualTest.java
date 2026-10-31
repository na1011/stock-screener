package com.stockscreener.pipeline.datasource.us;

import com.stockscreener.pipeline.datasource.RawFinancialReport;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 관문 켬 K8 · R2 — 10-K가 3개월 문맥에 그 해 12개월 값을 실은 칸을 컬럼 단위로 바로잡는다. FY를 낸 컬럼은 그 3개월 값을 안 읽고(FY − 분기 셋으로
 * 유도), FY를 안 낸 컬럼은 같은 공시가 다른 컬럼에서 같은 오태깅을 보였고 그 값이 9개월 누계 이상일 때 FY 자리로 옮긴다.
 *
 * <p>무너지는 방식이 조용하다 — 4분기 매출이 한 해 매출이라 TTM이 그만큼 부푼다(LHX FY2025 +71% · ORCL 2020 Q4 39,068 대 분기 10,440).
 */
class SecEdgarQuarterContextAnnualTest {

    private static final String Q1 = "0000000001-17-000001";
    private static final String Q2 = "0000000001-17-000002";
    private static final String Q3 = "0000000001-17-000003";
    private static final String TEN_K = "0000000001-18-000001";

    private static String fact(String start, String end, long val, String accn, String form, String filed) {
        return """
                {"start":"%s","end":"%s","val":%d,"accn":"%s","fy":2017,"fp":"%s","form":"%s","filed":"%s"}"""
                .formatted(start, end, val, accn, form.equals("10-K") ? "FY" : "Q3", form, filed);
    }

    /** 한 해의 분기 셋(각 9개월의 3분의 1) · 9개월 누계 · 10-K의 FY(없으면 null)와 4분기 문맥. */
    private static String year(Long fy, long q4Context, long nineMonths) {
        long q = nineMonths / 3;
        List<String> facts = new ArrayList<>(List.of(
                fact("2017-01-01", "2017-03-31", q, Q1, "10-Q", "2017-05-01"),
                fact("2017-04-01", "2017-06-30", q, Q2, "10-Q", "2017-08-01"),
                fact("2017-01-01", "2017-06-30", 2 * q, Q2, "10-Q", "2017-08-01"),
                fact("2017-07-01", "2017-09-30", q, Q3, "10-Q", "2017-11-01"),
                fact("2017-01-01", "2017-09-30", nineMonths, Q3, "10-Q", "2017-11-01"),
                fact("2017-10-01", "2017-12-31", q4Context, TEN_K, "10-K", "2018-02-15")));
        if (fy != null) {
            facts.add(fact("2017-01-01", "2017-12-31", fy, TEN_K, "10-K", "2018-02-15"));
        }
        return String.join(",", facts);
    }

    private static List<RawFinancialReport> build(String revenue, String operating, boolean usGate) throws Exception {
        String json = """
                {"facts":{"us-gaap":{"Revenues":{"units":{"USD":[%s]}},"OperatingIncomeLoss":{"units":{"USD":[%s]}}}}}"""
                .formatted(revenue, operating);
        JsonNode facts = new ObjectMapper().readTree(json).path("facts");
        return SecEdgarFacts.build(facts, 2015, usGate).reports();
    }

    private static RawFinancialReport latest(List<RawFinancialReport> reports, String period) {
        return reports.stream().filter(r -> r.fiscalYear() == 2017 && period.equals(r.fiscalPeriod()))
                .max(Comparator.comparing(RawFinancialReport::publishedAt)).orElseThrow();
    }

    @Test
    @DisplayName("관문 켬: FY를 낸 컬럼의 4분기 문맥 값이 FY와 같고 9개월 실적이 있으면 그 칸만 FY − 분기 셋으로 — 같은 문맥의 맞는 3개월 값은 그대로")
    void quarterContextCarryingTheYearIsDroppedPerColumn() throws Exception {
        // 매출 4분기 문맥 = FY 12,000(오태깅) · 영업이익 4분기 문맥 300 = FY 1,200 − 9개월 900(맞는 3개월 값)
        List<RawFinancialReport> on = build(year(12_000L, 12_000, 9_000), year(1_200L, 300, 900), true);

        assertThat(latest(on, "Q4").revenue()).as("FY − 분기 셋").isEqualByComparingTo("3000");
        assertThat(latest(on, "Q4").operatingIncome()).as("맞는 칸은 그대로").isEqualByComparingTo("300");
        assertThat(latest(build(year(12_000L, 12_000, 9_000), year(1_200L, 300, 900), false), "Q4").revenue())
                .as("관문 꺼짐은 옛 출력 그대로").isEqualByComparingTo("12000");
    }

    @Test
    @DisplayName("R2: 그 공시가 다른 컬럼에서 같은 오태깅을 보였고 FY를 안 낸 컬럼의 4분기 문맥 값이 9개월 누계 이상이면 FY로 옮긴다(LHX FY2025 꼴)")
    void orphanQuarterContextValueOfACarrierFilingBecomesTheYear() throws Exception {
        // 영업이익: 4분기 문맥 = FY 1,200(공시 단위 증거) · 매출: 그 10-K가 FY 없이 4분기 문맥에만 12,000(9개월 9,000 이상)
        List<RawFinancialReport> on = build(year(null, 12_000, 9_000), year(1_200L, 1_200, 900), true);

        assertThat(latest(on, "FY").revenue()).isEqualByComparingTo("12000");
        assertThat(latest(on, "Q4").revenue()).isEqualByComparingTo("3000");
        assertThat(latest(on, "Q4").operatingIncome()).isEqualByComparingTo("300");
    }

    @Test
    @DisplayName("R2는 공시 단위 증거 없이 안 옮긴다 — 일회성 항목이 큰 4분기는 한 분기가 9개월 합을 넘을 수 있다(2017 4분기 세제 개편)")
    void orphanWithoutCarrierEvidenceIsKept() throws Exception {
        List<RawFinancialReport> on = build(year(null, 12_000, 9_000), year(1_200L, 300, 900), true);

        assertThat(latest(on, "Q4").revenue()).isEqualByComparingTo("12000");
    }

    @Test
    @DisplayName("그 해 9개월 실적이 0에 가까우면 4분기 = FY가 정당할 수 있어 둔다(첫해 · 한 분기에 몰린 실적)")
    void quarterEqualToYearWithoutPriorQuartersIsKept() throws Exception {
        RawFinancialReport q4 = latest(build(year(12_000L, 12_000, 60), year(1_200L, 1_200, 6), true), "Q4");

        assertThat(q4.revenue()).isEqualByComparingTo("12000");
        assertThat(q4.derived()).isFalse();
    }
}
