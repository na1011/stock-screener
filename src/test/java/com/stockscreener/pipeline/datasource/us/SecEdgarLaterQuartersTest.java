package com.stockscreener.pipeline.datasource.us;

import com.stockscreener.pipeline.datasource.RawFinancialReport;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 관문 켬 · 재고 A6 — 같은 해 앞 분기가 다른 기저로 다시 실린 날부터 뒤 분기는 새 기저가 확인될 때까지 모른다(두 시장 한 규칙 · 국내
 * {@code DartFlowBasis}). PNR 2017 2분기: 원 10-Q 매출 1,265 · 분사 뒤 2018 10-Q 비교열 754.
 */
class SecEdgarLaterQuartersTest {

    private static final long M = 1_000_000L;

    private static String fact(String start, String end, long val, String accn, String form, String fp, int fy, String filed) {
        return """
                {"start":"%s","end":"%s","val":%d,"accn":"%s","fy":%d,"fp":"%s","form":"%s","filed":"%s"}"""
                .formatted(start, end, val, accn, fy, fp, form, filed);
    }

    private static List<RawFinancialReport> build() throws Exception {
        String revenues = String.join(",",
                fact("2017-01-01", "2017-03-31", 1_200 * M, "0000000001-17-000001", "10-Q", "Q1", 2017, "2017-04-25"),
                fact("2017-04-01", "2017-06-30", 1_265 * M, "0000000001-17-000002", "10-Q", "Q2", 2017, "2017-07-25"),
                fact("2017-07-01", "2017-09-30", 1_250 * M, "0000000001-17-000003", "10-Q", "Q3", 2017, "2017-10-24"),
                // 회계연도 창을 세우는 연차 사실(10-K).
                fact("2017-01-01", "2017-12-31", 4_965 * M, "0000000001-18-000000", "10-K", "FY", 2017, "2018-02-15"),
                // 다음 해 1분기 10-Q의 비교열 — 분사로 전년 1분기가 다른 기저로 다시 실렸다.
                fact("2018-01-01", "2018-03-31", 720 * M, "0000000001-18-000001", "10-Q", "Q1", 2018, "2018-04-24"),
                fact("2017-01-01", "2017-03-31", 700 * M, "0000000001-18-000001", "10-Q", "Q1", 2018, "2018-04-24"));
        JsonNode facts = new ObjectMapper().readTree("{\"facts\":{\"us-gaap\":{\"Revenues\":{\"units\":{\"USD\":[" + revenues + "]}}}}}")
                .path("facts");
        return SecEdgarFacts.build(facts, 2015).reports();
    }

    @Test
    @DisplayName("앞 분기가 1% 넘게 다시 실린 날 — 같은 해 뒤 분기의 그 열은 그날부터 모른다(부재 판정)")
    void laterQuarterIsUnknownFromTheRestatementDay() throws Exception {
        RawFinancialReport q2 = build().stream()
                .filter(r -> r.fiscalYear() == 2017 && "Q2".equals(r.fiscalPeriod()) && r.publishedAt().equals(LocalDate.of(2018, 4, 24)))
                .findFirst().orElseThrow();
        assertThat(q2.revenue()).isNull();
        assertThat(q2.assertedAbsent()).contains("revenue");
    }

    @Test
    @DisplayName("한 해에 사건이 둘이면 쌓인다 — 날마다 한 판 · 앞 사건이 모르게 한 열을 뒤 사건의 판이 옛 값으로 되돌리지 않는다")
    void eventsOfOneYearAccumulateIntoOneVintagePerDay() throws Exception {
        String q1 = "0000000001-17-000001";
        String q2 = "0000000001-17-000002";
        String q3 = "0000000001-17-000003";
        String k = "0000000001-18-000000";
        String n1 = "0000000001-18-000001";
        String n2 = "0000000001-18-000002";
        String n3 = "0000000001-18-000003";
        // 2018 1분기 10-Q가 전년 1분기 매출을 · 2분기 10-Q가 전년 2분기 순이익을 다시 쓰고, 3분기 10-Q가 전년 3분기를 같은 값으로 다시 싣는다.
        String revenues = String.join(",",
                fact("2017-01-01", "2017-03-31", 1_000 * M, q1, "10-Q", "Q1", 2017, "2017-04-25"),
                fact("2017-04-01", "2017-06-30", 1_100 * M, q2, "10-Q", "Q2", 2017, "2017-07-25"),
                fact("2017-07-01", "2017-09-30", 1_200 * M, q3, "10-Q", "Q3", 2017, "2017-10-24"),
                fact("2017-01-01", "2017-12-31", 4_600 * M, k, "10-K", "FY", 2017, "2018-02-15"),
                fact("2018-01-01", "2018-03-31", 1_300 * M, n1, "10-Q", "Q1", 2018, "2018-04-24"),
                fact("2017-01-01", "2017-03-31", 900 * M, n1, "10-Q", "Q1", 2018, "2018-04-24"),
                fact("2018-04-01", "2018-06-30", 1_400 * M, n2, "10-Q", "Q2", 2018, "2018-07-24"),
                fact("2017-04-01", "2017-06-30", 1_100 * M, n2, "10-Q", "Q2", 2018, "2018-07-24"),
                fact("2018-07-01", "2018-09-30", 1_500 * M, n3, "10-Q", "Q3", 2018, "2018-10-23"),
                fact("2017-07-01", "2017-09-30", 1_200 * M, n3, "10-Q", "Q3", 2018, "2018-10-23"));
        String netIncome = String.join(",",
                fact("2017-01-01", "2017-03-31", 100 * M, q1, "10-Q", "Q1", 2017, "2017-04-25"),
                fact("2017-04-01", "2017-06-30", 110 * M, q2, "10-Q", "Q2", 2017, "2017-07-25"),
                fact("2017-07-01", "2017-09-30", 120 * M, q3, "10-Q", "Q3", 2017, "2017-10-24"),
                fact("2017-01-01", "2017-12-31", 460 * M, k, "10-K", "FY", 2017, "2018-02-15"),
                fact("2018-01-01", "2018-03-31", 130 * M, n1, "10-Q", "Q1", 2018, "2018-04-24"),
                fact("2017-01-01", "2017-03-31", 100 * M, n1, "10-Q", "Q1", 2018, "2018-04-24"),
                fact("2018-04-01", "2018-06-30", 140 * M, n2, "10-Q", "Q2", 2018, "2018-07-24"),
                fact("2017-04-01", "2017-06-30", 90 * M, n2, "10-Q", "Q2", 2018, "2018-07-24"),
                fact("2018-07-01", "2018-09-30", 150 * M, n3, "10-Q", "Q3", 2018, "2018-10-23"),
                fact("2017-07-01", "2017-09-30", 120 * M, n3, "10-Q", "Q3", 2018, "2018-10-23"));
        JsonNode facts = new ObjectMapper().readTree("{\"facts\":{\"us-gaap\":{"
                        + "\"Revenues\":{\"units\":{\"USD\":[" + revenues + "]}},"
                        + "\"NetIncomeLoss\":{\"units\":{\"USD\":[" + netIncome + "]}}}}}")
                .path("facts");
        List<RawFinancialReport> q3s = SecEdgarFacts.build(facts, 2015).reports().stream()
                .filter(r -> r.fiscalYear() == 2017 && "Q3".equals(r.fiscalPeriod()))
                .toList();

        assertThat(q3s).extracting(RawFinancialReport::publishedAt).as("날마다 한 판").doesNotHaveDuplicates();
        RawFinancialReport second = q3s.stream().filter(r -> r.publishedAt().equals(LocalDate.of(2018, 7, 24))).findFirst().orElseThrow();
        assertThat(second.revenue()).as("앞 사건(1분기 매출)이 모르게 한 열은 그대로 모른다").isNull();
        assertThat(second.netIncome()).isNull();
        RawFinancialReport restored = q3s.stream().filter(r -> r.publishedAt().equals(LocalDate.of(2018, 10, 23))).findFirst().orElseThrow();
        assertThat(restored.revenue()).as("그 기간이 다시 실린 날 되살린다").isEqualByComparingTo("1200000000");
        assertThat(restored.netIncome()).isEqualByComparingTo("120000000");
    }

    @Test
    @DisplayName("신호 그날 그 기간이 다시 실렸으면 그날 확인됐다 — 모르게 하지 않는다(BAX 2015 3분기 · 분사 10-K가 3분기를 같은 값으로 다시 실었다)")
    void periodCarriedOnTheSignalDayIsKnown() throws Exception {
        String revenues = String.join(",",
                fact("2017-01-01", "2017-03-31", 1_000 * M, "0000000001-17-000001", "10-Q", "Q1", 2017, "2017-04-25"),
                fact("2017-04-01", "2017-06-30", 1_100 * M, "0000000001-17-000002", "10-Q", "Q2", 2017, "2017-07-25"),
                fact("2017-07-01", "2017-09-30", 1_200 * M, "0000000001-17-000003", "10-Q", "Q3", 2017, "2017-10-24"),
                // 10-K가 분기표에서 1분기를 새 기저로 다시 쓰고 3분기는 같은 값으로 다시 싣는다.
                fact("2017-01-01", "2017-12-31", 4_500 * M, "0000000001-18-000000", "10-K", "FY", 2017, "2018-02-15"),
                fact("2017-01-01", "2017-03-31", 900 * M, "0000000001-18-000000", "10-K", "FY", 2017, "2018-02-15"),
                fact("2017-07-01", "2017-09-30", 1_200 * M, "0000000001-18-000000", "10-K", "FY", 2017, "2018-02-15"));
        JsonNode facts = new ObjectMapper().readTree("{\"facts\":{\"us-gaap\":{\"Revenues\":{\"units\":{\"USD\":[" + revenues + "]}}}}}")
                .path("facts");
        List<RawFinancialReport> q3s = SecEdgarFacts.build(facts, 2015).reports().stream()
                .filter(r -> r.fiscalYear() == 2017 && "Q3".equals(r.fiscalPeriod()))
                .toList();
        assertThat(q3s).allSatisfy(r -> assertThat(r.revenue()).isEqualByComparingTo("1200000000"));
    }
}
