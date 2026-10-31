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
 * 관문 켬 P5 — 매출 개념. ① 이자비용을 뺀 순수익 총계(`RevenuesNetOfInterestExpense`)는 매출 총계를 그것으로만 내는 발행사의 매출이다
 * (RKT 매출 전 연도 빔 · 은행형 유도와 같은 기저 · 매출원가를 실은 공시는 원가 위의 매출). ② 고른 매출이 같은 공시의 원가 + 총이익보다 작고 같은 공시의 다른
 * 매출 후보가 그 줄을 닫으면 그 후보다(PLXS 천 단위 오태깅).
 */
class SecEdgarRevenueConceptTest {

    private static final long MILLION = 1_000_000L;

    private static String fact(String start, String end, long val, String accn, String form, String fp, int fy, String filed) {
        return """
                {"start":"%s","end":"%s","val":%d,"accn":"%s","fy":%d,"fp":"%s","form":"%s","filed":"%s"}"""
                .formatted(start, end, val, accn, fy, fp, form, filed);
    }

    private static List<RawFinancialReport> build(String usGaap, boolean usGate) throws Exception {
        JsonNode facts = new ObjectMapper().readTree("{\"facts\":{\"us-gaap\":{" + usGaap + "}}}").path("facts");
        return SecEdgarFacts.build(facts, 2015, usGate).reports();
    }

    private static String tag(String name, List<String> facts) {
        return "\"%s\":{\"units\":{\"USD\":[%s]}}".formatted(name, String.join(",", facts));
    }

    private static RawFinancialReport latest(List<RawFinancialReport> reports, String period) {
        return reports.stream().filter(r -> r.fiscalYear() == 2017 && period.equals(r.fiscalPeriod()))
                .max(Comparator.comparing(RawFinancialReport::publishedAt)).orElseThrow();
    }

    @Test
    @DisplayName("관문 켬: 순수익 총계만 내는 발행사는 그것이 매출이다 — 꺼짐은 옛 출력(빔) 그대로")
    void netRevenueIsTheTopLineOfANetReporter() throws Exception {
        String json = tag("RevenuesNetOfInterestExpense", List.of(
                fact("2017-01-01", "2017-12-31", 5_101 * MILLION, "0000000001-18-000001", "10-K", "FY", 2017, "2018-02-15")));
        assertThat(latest(build(json, true), "FY").revenue()).isEqualByComparingTo("5101000000");
        assertThat(build(json, false)).as("관문 꺼짐은 그 해 매출이 없다(보고서도 안 생긴다)")
                .noneMatch(r -> r.fiscalYear() == 2017 && r.revenue() != null);
    }

    @Test
    @DisplayName("관문 켬: 같은 공시가 그 창에 순수익을 냈으면 순수익이 매출이다 — FY · 분기 · 4분기가 한 기저다")
    void netRevenueWinsInEveryWindowItIsReported() throws Exception {
        String tenK = "0000000001-18-000004";
        List<String> gross = new ArrayList<>();
        List<String> net = new ArrayList<>();
        String[][] quarters = {{"2017-01-01", "2017-03-31", "Q1", "0000000001-17-000001", "2017-05-01"},
                {"2017-04-01", "2017-06-30", "Q2", "0000000001-17-000002", "2017-08-01"},
                {"2017-07-01", "2017-09-30", "Q3", "0000000001-17-000003", "2017-11-01"}};
        for (String[] q : quarters) {
            gross.add(fact(q[0], q[1], 300 * MILLION, q[3], "10-Q", q[2], 2017, q[4]));
            net.add(fact(q[0], q[1], 250 * MILLION, q[3], "10-Q", q[2], 2017, q[4]));
        }
        gross.add(fact("2017-01-01", "2017-12-31", 1_300 * MILLION, tenK, "10-K", "FY", 2017, "2018-02-15"));
        net.add(fact("2017-01-01", "2017-12-31", 1_100 * MILLION, tenK, "10-K", "FY", 2017, "2018-02-15"));
        // 10-K가 4분기 문맥을 순수익으로만 싣는다(JEF 2015 4분기 2,496).
        net.add(fact("2017-10-01", "2017-12-31", 350 * MILLION, tenK, "10-K", "FY", 2017, "2018-02-15"));
        String json = tag("Revenues", gross) + "," + tag("RevenuesNetOfInterestExpense", net);
        // 순수익 기준: FY 1,100 · 4분기 350(= 1,100 − 250 × 3 · 10-K 4분기 문맥과 같다). 최댓값 경쟁이면 FY는 총수익 1,300이고 4분기가 순수익 350으로 섞였다.
        List<RawFinancialReport> on = build(json, true);
        assertThat(latest(on, "FY").revenue()).isEqualByComparingTo("1100000000");
        assertThat(latest(on, "Q4").revenue()).isEqualByComparingTo("350000000");
        assertThat(latest(on, "Q1").revenue()).isEqualByComparingTo("250000000");
    }

    @Test
    @DisplayName("관문 켬: 고른 매출이 같은 공시의 원가 + 총이익보다 작고 다른 후보가 그 줄을 닫으면 그 후보 — 큰 쪽은 안 건드린다")
    void revenueBelowItsOwnGrossMarginLineTakesTheConfirmingSibling() throws Exception {
        String accn = "0000000001-18-000001";
        String filed = "2018-02-15";
        // 계약 매출이 천 단위로 오태깅됐다(PLXS) — 원가 875 + 총이익 100 = 975가 IncludingAssessedTax 975를 확증한다.
        String broken = String.join(",",
                tag("RevenueFromContractWithCustomerExcludingAssessedTax", List.of(
                        fact("2017-01-01", "2017-12-31", 975_000, accn, "10-K", "FY", 2017, filed))),
                tag("RevenueFromContractWithCustomerIncludingAssessedTax", List.of(
                        fact("2017-01-01", "2017-12-31", 975 * MILLION, accn, "10-K", "FY", 2017, filed))),
                tag("CostOfGoodsAndServicesSold", List.of(
                        fact("2017-01-01", "2017-12-31", 875 * MILLION, accn, "10-K", "FY", 2017, filed))),
                tag("GrossProfit", List.of(
                        fact("2017-01-01", "2017-12-31", 100 * MILLION, accn, "10-K", "FY", 2017, filed))));
        assertThat(latest(build(broken, true), "FY").revenue()).isEqualByComparingTo("975000000");
        assertThat(latest(build(broken, false), "FY").revenue()).as("관문 꺼짐은 옛 출력 그대로").isEqualByComparingTo("975000");

        // 총매출이 기타 수익을 품어 원가 + 총이익보다 크다(ABT · AMGN) — 정당한 개념 차이라 그대로다.
        String wider = String.join(",",
                tag("Revenues", List.of(fact("2017-01-01", "2017-12-31", 1_050 * MILLION, accn, "10-K", "FY", 2017, filed))),
                tag("SalesRevenueGoodsNet", List.of(fact("2017-01-01", "2017-12-31", 975 * MILLION, accn, "10-K", "FY", 2017, filed))),
                tag("CostOfGoodsAndServicesSold", List.of(
                        fact("2017-01-01", "2017-12-31", 875 * MILLION, accn, "10-K", "FY", 2017, filed))),
                tag("GrossProfit", List.of(fact("2017-01-01", "2017-12-31", 100 * MILLION, accn, "10-K", "FY", 2017, filed))));
        assertThat(latest(build(wider, true), "FY").revenue()).isEqualByComparingTo("1050000000");
    }

    @Test
    @DisplayName("관문 켬: 뒤 판의 고정 태그 매출이 떨어져도 같은 판이 다른 후보 태그로 앞 판 값을 실었으면 앞 판 값을 잇는다(태그 변경)")
    void retaggedRevenueKeepsThePreviousTotal() throws Exception {
        String first = "0000000001-18-000001";
        String later = "0000000001-19-000001";
        // 2017 FY를 처음엔 계약 매출 1,000으로, 뒤 10-K는 계약 매출 100(부분) + 총계 Revenues 1,000으로 다시 싣는다(HRI 2021 · MARA 2022).
        String json = String.join(",",
                tag("RevenueFromContractWithCustomerExcludingAssessedTax", List.of(
                        fact("2017-01-01", "2017-12-31", 1_000 * MILLION, first, "10-K", "FY", 2017, "2018-02-15"),
                        fact("2017-01-01", "2017-12-31", 100 * MILLION, later, "10-K", "FY", 2018, "2019-02-15"))),
                tag("Revenues", List.of(
                        fact("2017-01-01", "2017-12-31", 1_000 * MILLION, later, "10-K", "FY", 2018, "2019-02-15"))),
                tag("NetIncomeLoss", List.of(
                        fact("2017-01-01", "2017-12-31", 50 * MILLION, first, "10-K", "FY", 2017, "2018-02-15"),
                        fact("2017-01-01", "2017-12-31", 55 * MILLION, later, "10-K", "FY", 2018, "2019-02-15"))));
        RawFinancialReport on = latest(build(json, true), "FY");
        assertThat(on.revenue()).isEqualByComparingTo("1000000000");
        assertThat(on.netIncome()).as("같은 판의 다른 재작성은 그대로 받는다").isEqualByComparingTo("55000000");
    }
}
