package com.stockscreener.pipeline.datasource.us;

import com.stockscreener.pipeline.datasource.RawFinancialReport;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 관문 켬 P3a × 8-K — 8-K가 정기 공시보다 먼저 FY를 재표시하면 그 값을 받고, 4분기는 FY 판뿐 아니라 그 해 분기 판이 다시 나올 때마다 다시 짓는다.
 * 네 값이 같은 재작성 물결에 들 때만 빼고 아니면 비운다.
 *
 * <p>무너지는 방식이 조용하다 — 8-K를 버리면 재작성 손익이 다음 10-K까지 1년 가까이 늦고(EQH FY2022 LDTI 285일), 분기 판에 4분기를 다시 안
 * 지으면 ΣQ1~4 ≠ 그때 FY인 채 남는다.
 */
class SecEdgarCurrentReportQ4Test {

    /** 값은 백만 달러 단위로 싣는다 — 작은 정수는 끝의 0 때문에 서로의 반올림으로 읽힌다. */
    private static final long MILLION = 1_000_000L;

    private static String fact(String start, String end, long val, String accn, String form, String fp, int fy, String filed) {
        return """
                {"start":"%s","end":"%s","val":%d,"accn":"%s","fy":%d,"fp":"%s","form":"%s","filed":"%s"}"""
                .formatted(start, end, val * MILLION, accn, fy, fp, form, filed);
    }

    /** 2017년 분기 셋(각 200) · 2017 10-K(FY 1,000) · 2018년 10-Q 셋(자기 분기 300). */
    private static List<String> base() {
        return new ArrayList<>(List.of(
                fact("2017-01-01", "2017-03-31", 200, "0000000001-17-000001", "10-Q", "Q1", 2017, "2017-05-01"),
                fact("2017-04-01", "2017-06-30", 200, "0000000001-17-000002", "10-Q", "Q2", 2017, "2017-08-01"),
                fact("2017-07-01", "2017-09-30", 200, "0000000001-17-000003", "10-Q", "Q3", 2017, "2017-11-01"),
                fact("2017-01-01", "2017-12-31", 1_000, "0000000001-18-000001", "10-K", "FY", 2017, "2018-02-15"),
                fact("2018-01-01", "2018-03-31", 300, "0000000001-18-000002", "10-Q", "Q1", 2018, "2018-05-01"),
                fact("2018-04-01", "2018-06-30", 300, "0000000001-18-000003", "10-Q", "Q2", 2018, "2018-08-01"),
                fact("2018-07-01", "2018-09-30", 300, "0000000001-18-000004", "10-Q", "Q3", 2018, "2018-11-01")));
    }

    /** 8-K(2018-04-02)가 FY2017을 1,100으로 재표시하고, 2018년 10-Q 셋이 2017년 비교분기를 220으로 다시 싣는다. */
    private static List<String> recastThenQuarters() {
        List<String> facts = base();
        facts.add(fact("2017-01-01", "2017-12-31", 1_100, "0000000001-18-000009", "8-K", "FY", 2017, "2018-04-02"));
        facts.add(fact("2017-01-01", "2017-03-31", 220, "0000000001-18-000002", "10-Q", "Q1", 2018, "2018-05-01"));
        facts.add(fact("2017-04-01", "2017-06-30", 220, "0000000001-18-000003", "10-Q", "Q2", 2018, "2018-08-01"));
        facts.add(fact("2017-07-01", "2017-09-30", 220, "0000000001-18-000004", "10-Q", "Q3", 2018, "2018-11-01"));
        return facts;
    }

    /**
     * 원가는 증인이다 — 등록인의 재표시 8-K는 안 바뀐 줄(여기서는 FY 원가 600)도 함께 실어, 그 공시가 정기 공시와 같은 실체의 재무임을 보인다
     * ({@code SecEdgarFacts.keepRegistrantCurrentReports}).
     */
    private static List<RawFinancialReport> build(List<String> revenue, boolean usGate) throws Exception {
        String cost = String.join(",",
                fact("2017-01-01", "2017-12-31", 600, "0000000001-18-000001", "10-K", "FY", 2017, "2018-02-15"),
                fact("2017-01-01", "2017-12-31", 600, "0000000001-18-000009", "8-K", "FY", 2017, "2018-04-02"),
                fact("2017-01-01", "2017-12-31", 600, "0000000001-20-000009", "8-K", "FY", 2019, "2020-08-03"));
        String json = """
                {"facts":{"us-gaap":{"Revenues":{"units":{"USD":[%s]}},"CostOfRevenue":{"units":{"USD":[%s]}}}}}"""
                .formatted(String.join(",", revenue), cost);
        JsonNode facts = new ObjectMapper().readTree(json).path("facts");
        return SecEdgarFacts.build(facts, 2015, usGate).reports();
    }

    private static RawFinancialReport latest(List<RawFinancialReport> reports, int year, String period) {
        return reports.stream().filter(r -> r.fiscalYear() == year && period.equals(r.fiscalPeriod()))
                .max(Comparator.comparing(RawFinancialReport::publishedAt)).orElseThrow();
    }

    private static RawFinancialReport at(List<RawFinancialReport> reports, String period, String publishedAt) {
        return reports.stream().filter(r -> r.fiscalYear() == 2017 && period.equals(r.fiscalPeriod())
                        && r.publishedAt().equals(LocalDate.parse(publishedAt)))
                .findFirst().orElseThrow();
    }

    @Test
    @DisplayName("관문 켬: 8-K가 재표시한 FY를 그 날 받고, 분기 셋이 다 같은 기준으로 다시 나온 날 4분기를 새 FY − 새 분기로 다시 짓는다")
    void recastYearThenRestatedQuartersRederiveTheFourthQuarter() throws Exception {
        List<RawFinancialReport> on = build(recastThenQuarters(), true);

        assertThat(at(on, "FY", "2018-04-02").revenue()).as("8-K 판").isEqualByComparingTo("1100000000");
        assertThat(at(on, "Q4", "2018-04-02").revenue()).as("FY만 새 기준 — 분기가 아직 옛 기준이라 비운다").isNull();
        assertThat(at(on, "Q4", "2018-11-01").revenue()).as("분기 셋이 다 새 기준").isEqualByComparingTo("440000000");
        assertThat(at(on, "Q4", "2018-11-01").reportCode()).as("FY 판의 공시 코드 — 보통주 몫이 같은 코드의 FY 행으로 잇는다")
                .isEqualTo(at(on, "FY", "2018-04-02").reportCode());
        assertThat(latest(build(recastThenQuarters(), false), 2017, "Q4").revenue())
                .as("관문 꺼짐은 옛 출력 그대로 — 8-K를 안 읽고 FY 판 날에만 짓는다").isEqualByComparingTo("400000000");
    }

    @Test
    @DisplayName("관문 켬: 분기 셋은 앞 물결에서, FY는 뒤 물결(8-K)에서 다시 쓰였으면 4분기를 비운다(FMC 2016 꼴)")
    void rewritesFromDifferentWavesDoNotMix() throws Exception {
        List<String> facts = recastThenQuarters();
        facts.add(fact("2017-01-01", "2017-12-31", 1_100, "0000000001-19-000001", "10-K", "FY", 2018, "2019-02-15"));
        facts.add(fact("2018-01-01", "2018-12-31", 1_200, "0000000001-19-000001", "10-K", "FY", 2018, "2019-02-15"));
        facts.add(fact("2017-01-01", "2017-12-31", 800, "0000000001-20-000009", "8-K", "FY", 2019, "2020-08-03"));
        List<RawFinancialReport> on = build(facts, true);

        assertThat(at(on, "Q4", "2018-11-01").revenue()).isEqualByComparingTo("440000000");
        assertThat(latest(on, 2017, "Q4").revenue()).as("8-K 2020이 FY만 다시 썼다").isNull();
    }

    @Test
    @DisplayName("관문 켬: 다음 해 10-Q가 전년 1분기 비교열만 다시 쓰면 FY는 그대로라 옛 4분기를 지킨다 — 그날 새 판을 안 낸다(KO 2019 꼴)")
    void partialQuarterRestatementKeepsTheFourthQuarter() throws Exception {
        List<String> facts = base();
        facts.add(fact("2017-01-01", "2017-03-31", 150, "0000000001-18-000002", "10-Q", "Q1", 2018, "2018-05-01"));
        List<RawFinancialReport> on = build(facts, true);

        RawFinancialReport q4 = latest(on, 2017, "Q4");
        assertThat(q4.publishedAt()).isEqualTo(LocalDate.parse("2018-02-15"));
        assertThat(q4.revenue()).isEqualByComparingTo("400000000");
    }

    @Test
    @DisplayName("관문 켬: 정기 공시 값과 하나도 안 맞는 8-K는 등록인 재무가 아니라 안 받는다(WTRG 2026-03-25 · 합병 상대의 수치)")
    void currentReportWithoutWitnessIsIgnored() throws Exception {
        List<String> facts = base();
        facts.add(fact("2017-01-01", "2017-12-31", 3_000, "0000000001-18-000077", "8-K", "FY", 2017, "2018-04-02"));

        assertThat(build(facts, true)).noneMatch(r -> r.reportCode().endsWith("0000000001-18-000077"));
    }

    @Test
    @DisplayName("관문 켬: 첫 FY가 이미 새 기준이고 분기는 다음 해에 다시 쓰이면, 다음 10-K가 FY를 다시 실은 날 4분기를 새 세대로 다시 짓는다(DHR 2016 꼴)")
    void yearConfirmedAfterQuarterRestatementsRederivesTheFourthQuarter() throws Exception {
        List<String> facts = new ArrayList<>(List.of(
                fact("2017-01-01", "2017-03-31", 300, "0000000001-17-000001", "10-Q", "Q1", 2017, "2017-05-01"),
                fact("2017-04-01", "2017-06-30", 300, "0000000001-17-000002", "10-Q", "Q2", 2017, "2017-08-01"),
                fact("2017-07-01", "2017-09-30", 300, "0000000001-17-000003", "10-Q", "Q3", 2017, "2017-11-01"),
                fact("2017-01-01", "2017-12-31", 1_000, "0000000001-18-000001", "10-K", "FY", 2017, "2018-02-15")));
        for (int q = 1; q <= 3; q++) {
            String accn = "0000000001-18-00000" + (q + 1);
            String filed = List.of("2018-05-01", "2018-08-01", "2018-11-01").get(q - 1);
            facts.add(fact("2018-%02d-01".formatted(3 * q - 2), List.of("2018-03-31", "2018-06-30", "2018-09-30").get(q - 1),
                    350, accn, "10-Q", "Q" + q, 2018, filed));
            facts.add(fact("2017-%02d-01".formatted(3 * q - 2), List.of("2017-03-31", "2017-06-30", "2017-09-30").get(q - 1),
                    200, accn, "10-Q", "Q" + q, 2018, filed));
        }
        facts.add(fact("2017-01-01", "2017-12-31", 1_000, "0000000001-19-000001", "10-K", "FY", 2018, "2019-02-15"));
        facts.add(fact("2018-01-01", "2018-12-31", 1_500, "0000000001-19-000001", "10-K", "FY", 2018, "2019-02-15"));
        List<RawFinancialReport> on = build(facts, true);

        assertThat(at(on, "Q4", "2018-02-15").revenue()).as("그날 원장으로는 섞인 값").isEqualByComparingTo("100000000");
        assertThat(latest(on, 2017, "Q4").publishedAt()).isEqualTo(LocalDate.parse("2019-02-15"));
        assertThat(latest(on, 2017, "Q4").revenue()).isEqualByComparingTo("400000000");
    }

    @Test
    @DisplayName("관문 켬: 같은 날 정기 공시가 같은 칸을 다르게 말한 8-K는 안 받는다(UWMC FY2020 꼴)")
    void currentReportContradictingTheSameDayPeriodicIsIgnored() throws Exception {
        List<String> facts = base();
        facts.add(fact("2017-01-01", "2017-12-31", 3_000, "0000000001-18-000088", "8-K", "FY", 2017, "2018-02-15"));
        facts.add(fact("2017-01-01", "2017-03-31", 200, "0000000001-18-000088", "8-K", "Q1", 2017, "2018-02-15"));

        assertThat(build(facts, true)).noneMatch(r -> r.reportCode().endsWith("0000000001-18-000088"));
    }

    @Test
    @DisplayName("관문 켬: 8-K는 정기 공시가 지은 라벨에만 값을 댄다 — 정기 공시가 안 보고한 기간의 라벨을 열지 않는다")
    void currentReportOpensNoLabel() throws Exception {
        List<String> facts = base();
        facts.add(fact("2016-01-01", "2016-12-31", 900, "0000000001-18-000009", "8-K", "FY", 2017, "2018-04-02"));

        assertThat(build(facts, true)).noneMatch(r -> r.fiscalYear() == 2016);
    }
}
