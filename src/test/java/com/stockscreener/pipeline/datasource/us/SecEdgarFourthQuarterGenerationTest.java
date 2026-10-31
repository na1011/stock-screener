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
 * 관문 켬 — 4분기 잔차를 컬럼마다 같은 세대의 FY와 분기로 짓는다. 뒤 공시가 FY만 다시 쓰고 그 해 분기를 안 다시 냈으면 그 컬럼의 4분기를 비우고,
 * 분기도 다시 나왔거나(재작성) 고치지 않은 값이 그 뒤 공시에 다시 실렸으면(확인) 그 FY로 뺀다.
 *
 * <p>무너지는 방식이 조용하다 — 재작성 폭 전체가 4분기 한 칸에 실려 그럴듯한 양수가 된다(JCI 2017 Q4 매출 8,136 → 799 · BF 2017 887 → 24).
 */
class SecEdgarFourthQuarterGenerationTest {

    /** 값은 백만 달러 단위로 싣는다 — 작은 정수는 끝의 0 때문에 서로의 반올림으로 읽힌다(1,000 대 1,100). */
    private static final long MILLION = 1_000_000L;

    private static String fact(String start, String end, long val, String accn, String form, String fp, int fy, String filed) {
        return """
                {"start":"%s","end":"%s","val":%d,"accn":"%s","fy":%d,"fp":"%s","form":"%s","filed":"%s"}"""
                .formatted(start, end, val * MILLION, accn, fy, fp, form, filed);
    }

    /** 2017년 분기 셋(각 200) · 2017 10-K(FY 1,000) · 2018 10-K(FY 2018 1,300 · FY 2017 재작성 1,100). */
    private static List<String> base() {
        return new ArrayList<>(List.of(
                fact("2017-01-01", "2017-03-31", 200, "0000000001-17-000001", "10-Q", "Q1", 2017, "2017-05-01"),
                fact("2017-04-01", "2017-06-30", 200, "0000000001-17-000002", "10-Q", "Q2", 2017, "2017-08-01"),
                fact("2017-07-01", "2017-09-30", 200, "0000000001-17-000003", "10-Q", "Q3", 2017, "2017-11-01"),
                fact("2017-01-01", "2017-12-31", 1_000, "0000000001-18-000001", "10-K", "FY", 2017, "2018-02-15"),
                fact("2018-01-01", "2018-12-31", 1_300, "0000000001-19-000001", "10-K", "FY", 2018, "2019-02-15"),
                fact("2017-01-01", "2017-12-31", 1_100, "0000000001-19-000001", "10-K", "FY", 2018, "2019-02-15")));
    }

    private static RawFinancialReport latestQ4(List<String> revenue, boolean usGate) throws Exception {
        return latestQ4(revenue, List.of(), usGate);
    }

    private static RawFinancialReport latestQ4(List<String> revenue, List<String> cost, boolean usGate) throws Exception {
        String json = """
                {"facts":{"us-gaap":{"Revenues":{"units":{"USD":[%s]}}%s}}}""".formatted(String.join(",", revenue),
                cost.isEmpty() ? "" : ",\"CostOfRevenue\":{\"units\":{\"USD\":[" + String.join(",", cost) + "]}}");
        JsonNode facts = new ObjectMapper().readTree(json).path("facts");
        return SecEdgarFacts.build(facts, 2015, usGate).reports().stream()
                .filter(r -> r.fiscalYear() == 2017 && "Q4".equals(r.fiscalPeriod()))
                .max(Comparator.comparing(RawFinancialReport::publishedAt)).orElseThrow();
    }

    @Test
    @DisplayName("관문 켬: 뒤 10-K가 FY만 다시 쓰고 그 해 분기를 안 다시 냈으면 그 4분기를 비운다 — 재작성 폭을 4분기 한 칸에 싣지 않는다")
    void fyOnlyRewriteLeavesTheFourthQuarterBlank() throws Exception {
        assertThat(latestQ4(base(), true).revenue()).isNull();
        assertThat(latestQ4(base(), false).revenue()).as("관문 꺼짐은 옛 출력 그대로 — 새 FY − 옛 분기").isEqualByComparingTo("500000000");
    }

    @Test
    @DisplayName("관문 켬: 다음 해 10-Q가 그 해 분기를 같은 기준으로 다시 냈으면 새 FY − 새 분기로 다시 뺀다")
    void restatedQuartersAreSubtractedFromTheRestatedYear() throws Exception {
        List<String> revenue = base();
        revenue.add(fact("2018-01-01", "2018-03-31", 300, "0000000001-18-000002", "10-Q", "Q1", 2018, "2018-05-01"));
        revenue.add(fact("2018-04-01", "2018-06-30", 300, "0000000001-18-000003", "10-Q", "Q2", 2018, "2018-08-01"));
        revenue.add(fact("2018-07-01", "2018-09-30", 300, "0000000001-18-000004", "10-Q", "Q3", 2018, "2018-11-01"));
        revenue.add(fact("2017-01-01", "2017-03-31", 220, "0000000001-18-000002", "10-Q", "Q1", 2018, "2018-05-01"));
        revenue.add(fact("2017-04-01", "2017-06-30", 220, "0000000001-18-000003", "10-Q", "Q2", 2018, "2018-08-01"));
        revenue.add(fact("2017-07-01", "2017-09-30", 220, "0000000001-18-000004", "10-Q", "Q3", 2018, "2018-11-01"));

        assertThat(latestQ4(revenue, true).revenue()).isEqualByComparingTo("440000000");
    }

    @Test
    @DisplayName("관문 켬: 한 분기만 고쳤어도 나머지 분기와 FY가 그 뒤 공시에 같은 값으로 다시 실렸으면 같은 세대다 — 그 FY로 뺀다(JPM 2019 1분기 꼴)")
    void confirmedValuesStayInTheGeneration() throws Exception {
        List<String> revenue = new ArrayList<>(base().subList(0, 4));
        revenue.add(fact("2018-01-01", "2018-03-31", 300, "0000000001-18-000002", "10-Q", "Q1", 2018, "2018-05-01"));
        revenue.add(fact("2017-01-01", "2017-03-31", 150, "0000000001-18-000002", "10-Q", "Q1", 2018, "2018-05-01"));
        revenue.add(fact("2018-04-01", "2018-06-30", 300, "0000000001-18-000003", "10-Q", "Q2", 2018, "2018-08-01"));
        revenue.add(fact("2017-04-01", "2017-06-30", 200, "0000000001-18-000003", "10-Q", "Q2", 2018, "2018-08-01"));
        revenue.add(fact("2018-07-01", "2018-09-30", 300, "0000000001-18-000004", "10-Q", "Q3", 2018, "2018-11-01"));
        revenue.add(fact("2017-07-01", "2017-09-30", 200, "0000000001-18-000004", "10-Q", "Q3", 2018, "2018-11-01"));
        revenue.add(fact("2018-01-01", "2018-12-31", 1_300, "0000000001-19-000001", "10-K", "FY", 2018, "2019-02-15"));
        revenue.add(fact("2017-01-01", "2017-12-31", 1_000, "0000000001-19-000001", "10-K", "FY", 2018, "2019-02-15"));

        // 뒤 10-K가 원가를 고쳐 그 해 FY 판이 새로 난다 — 4분기는 FY 판마다 다시 지어진다(JPM 2019도 다른 컬럼이 판을 냈다).
        List<String> cost = List.of(
                fact("2017-01-01", "2017-12-31", 600, "0000000001-18-000001", "10-K", "FY", 2017, "2018-02-15"),
                fact("2017-01-01", "2017-12-31", 610, "0000000001-19-000001", "10-K", "FY", 2018, "2019-02-15"));

        assertThat(latestQ4(revenue, cost, true).revenue()).isEqualByComparingTo("450000000");
        assertThat(latestQ4(base().subList(0, 4), true).revenue()).as("대조군: 재작성 없는 원 공시").isEqualByComparingTo("400000000");
    }
}
