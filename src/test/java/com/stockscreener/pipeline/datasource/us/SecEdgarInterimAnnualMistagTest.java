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
 * 관문 켬 P1 — 10-Q가 12개월 창에 같은 공시의 짧은 창 값을 실으면(문맥 오태깅) 읽지 않는다. 단 같은 공시 · 같은 태그의 하위 창이 그 12개월 값을
 * 빈틈없이 합으로 지지하면 정당한 재작성이라 남긴다.
 *
 * <p>무너지는 방식이 조용하다 — 오태깅 FY 판이 10-K 뒤에 오면 최신 판이 되어 연 매출이 한 분기 값이다(PPG 2017 FY 3,486M · 10-K 14,748M).
 */
class SecEdgarInterimAnnualMistagTest {

    private static final String KQ = "0000000001-18-000001";
    private static final String TEN_Q = "0000000001-18-000002";

    private static String fact(String start, String end, long val, String accn, String form, String filed, String fp) {
        return """
                {"start":"%s","end":"%s","val":%d,"accn":"%s","fy":2017,"fp":"%s","form":"%s","filed":"%s"}"""
                .formatted(start, end, val, accn, fp, form, filed);
    }

    private static List<RawFinancialReport> build(String laterTenQFacts, boolean usGate) throws Exception {
        String json = """
                {"facts":{"us-gaap":{"Revenues":{"units":{"USD":[%s,%s]}}}}}"""
                .formatted(fact("2017-01-01", "2017-12-31", 14_748L, KQ, "10-K", "2018-02-15", "FY"), laterTenQFacts);
        JsonNode facts = new ObjectMapper().readTree(json).path("facts");
        return SecEdgarFacts.build(facts, 2015, usGate).reports();
    }

    private static RawFinancialReport latestFy(List<RawFinancialReport> reports) {
        return reports.stream().filter(r -> r.fiscalYear() == 2017 && "FY".equals(r.fiscalPeriod())
                        && r.periodEnd().equals(LocalDate.of(2017, 12, 31)))
                .max(java.util.Comparator.comparing(RawFinancialReport::publishedAt)).orElseThrow();
    }

    @Test
    @DisplayName("관문 켬: 10-Q가 FY 창에 같은 공시 1분기 값을 실으면 그 FY 값을 안 읽는다 — 최신 판이 10-K 값으로 남는다")
    void interimAnnualEqualToItsQuarterIsDropped() throws Exception {
        String tenQ = fact("2018-01-01", "2018-03-31", 3_486L, TEN_Q, "10-Q", "2018-05-01", "Q1") + ","
                + fact("2017-01-01", "2017-12-31", 3_486L, TEN_Q, "10-Q", "2018-05-01", "Q1");

        assertThat(latestFy(build(tenQ, true)).revenue()).isEqualByComparingTo("14748");
        assertThat(latestFy(build(tenQ, false)).revenue()).as("관문 꺼짐은 옛 출력 그대로").isEqualByComparingTo("3486");
    }

    @Test
    @DisplayName("같은 공시의 분기 넷이 그 FY 값을 합으로 지지하면 정당한 재작성이라 남긴다 — 한 분기가 우연히 FY와 같아도(M FY2023 세전 43 꼴)")
    void restatementSupportedBySubWindowsIsKept() throws Exception {
        // 재작성 FY 4,000과 같은 공시의 재작성 분기 넷(1,000 · −1,000 · 4,000 · 0 — 합 4,000). 3분기가 FY와 같아 쌍둥이 조건이 선다.
        List<String> quarters = List.of(
                fact("2017-01-01", "2017-03-31", 1_000L, TEN_Q, "10-Q", "2018-05-01", "Q1"),
                fact("2017-04-01", "2017-06-30", -1_000L, TEN_Q, "10-Q", "2018-05-01", "Q1"),
                fact("2017-07-01", "2017-09-30", 4_000L, TEN_Q, "10-Q", "2018-05-01", "Q1"),
                fact("2017-10-01", "2017-12-31", 0L, TEN_Q, "10-Q", "2018-05-01", "Q1"));
        String fy = fact("2017-01-01", "2017-12-31", 4_000L, TEN_Q, "10-Q", "2018-05-01", "Q1");
        String q1 = fact("2018-01-01", "2018-03-31", 900L, TEN_Q, "10-Q", "2018-05-01", "Q1");

        assertThat(latestFy(build(String.join(",", q1, fy, String.join(",", quarters)), true)).revenue())
                .as("하위 창 합이 지지한다").isEqualByComparingTo("4000");
        assertThat(latestFy(build(String.join(",", q1, fy, quarters.get(0), quarters.get(2)), true)).revenue())
                .as("대조군: 지지하는 산수가 없으면(1분기 · 3분기만) 쌍둥이라 버린다").isEqualByComparingTo("14748");
    }
}
