package com.stockscreener.pipeline.datasource.us;

import com.stockscreener.pipeline.datasource.RawFinancialReport;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.Comparator;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 관문 켬 K11 — 한 공시 안의 두 분기 창이 공통 태그 둘 이상에서 전부 같으면 문맥 복사다. 앞 공시와 달라진 쪽을 그 공시에서 안 읽는다.
 *
 * <p>무너지는 방식이 조용하다 — 복사된 분기가 최신 판이 되어 그 분기 매출 · 원가가 다른 분기 값이다(APTV 2017 Q2 · Q3 원가 둘 다 2,498 꼴).
 */
class SecEdgarCopiedQuarterContextTest {

    private static String fact(String start, String end, long val, String accn, String form, String filed) {
        return """
                {"start":"%s","end":"%s","val":%d,"accn":"%s","fy":2017,"fp":"Q3","form":"%s","filed":"%s"}"""
                .formatted(start, end, val, accn, form, filed);
    }

    private static List<RawFinancialReport> build(boolean usGate) throws Exception {
        String q1 = "0000000001-17-000001";
        String q2 = "0000000001-17-000002";
        String q3 = "0000000001-17-000003";
        String fy16 = "0000000001-17-000000";
        // 2분기 10-Q가 2분기 창에 매출 2,000 · 원가 1,200을 냈는데, 3분기 10-Q가 비교 2분기 창에 3분기 값(2,500 · 1,500)을 그대로 복사했다.
        String revenue = String.join(",",
                fact("2016-01-01", "2016-12-31", 8_000, fy16, "10-K", "2017-02-15"),
                fact("2017-01-01", "2017-03-31", 1_800, q1, "10-Q", "2017-05-01"),
                fact("2017-04-01", "2017-06-30", 2_000, q2, "10-Q", "2017-08-01"),
                fact("2017-07-01", "2017-09-30", 2_500, q3, "10-Q", "2017-11-01"),
                fact("2017-04-01", "2017-06-30", 2_500, q3, "10-Q", "2017-11-01"));
        String cost = String.join(",",
                fact("2016-01-01", "2016-12-31", 4_800, fy16, "10-K", "2017-02-15"),
                fact("2017-01-01", "2017-03-31", 1_100, q1, "10-Q", "2017-05-01"),
                fact("2017-04-01", "2017-06-30", 1_200, q2, "10-Q", "2017-08-01"),
                fact("2017-07-01", "2017-09-30", 1_500, q3, "10-Q", "2017-11-01"),
                fact("2017-04-01", "2017-06-30", 1_500, q3, "10-Q", "2017-11-01"));
        String json = """
                {"facts":{"us-gaap":{"Revenues":{"units":{"USD":[%s]}},"CostOfRevenue":{"units":{"USD":[%s]}}}}}"""
                .formatted(revenue, cost);
        JsonNode facts = new ObjectMapper().readTree(json).path("facts");
        return SecEdgarFacts.build(facts, 2015, usGate).reports();
    }

    private static RawFinancialReport latestQ2(List<RawFinancialReport> reports) {
        return reports.stream().filter(r -> r.fiscalYear() == 2017 && "Q2".equals(r.fiscalPeriod()))
                .max(Comparator.comparing(RawFinancialReport::publishedAt)).orElseThrow();
    }

    @Test
    @DisplayName("관문 켬: 3분기 10-Q가 비교 2분기 창에 3분기 문맥을 통째로 복사하면 그 창을 안 읽는다 — 2분기는 앞 10-Q 값")
    void copiedQuarterContextIsIgnored() throws Exception {
        assertThat(latestQ2(build(true)).revenue()).isEqualByComparingTo("2000");
        assertThat(latestQ2(build(true)).costOfSales()).isEqualByComparingTo("1200");
        assertThat(latestQ2(build(false)).revenue()).as("관문 꺼짐은 옛 출력 그대로 — 복사본이 최신 판").isEqualByComparingTo("2500");
    }
}
