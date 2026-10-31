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
 * 관문 켬 K8 — 10-K가 3개월 문맥에 그 해 12개월 값을 실으면 그 공시의 4분기 문맥을 행 단위로 안 읽고 FY − 9개월로 유도한다.
 *
 * <p>무너지는 방식이 조용하다 — 4분기 매출이 한 해 매출이라 TTM이 그만큼 부푼다(LHX FY2025 +71% · ORCL 2020 Q4 39,068 대 분기 10,440).
 */
class SecEdgarQuarterContextAnnualTest {

    private static final String TEN_Q = "0000000001-17-000003";
    private static final String TEN_K = "0000000001-18-000001";

    private static String fact(String start, String end, long val, String accn, String form, String filed) {
        return """
                {"start":"%s","end":"%s","val":%d,"accn":"%s","fy":2017,"fp":"%s","form":"%s","filed":"%s"}"""
                .formatted(start, end, val, accn, form.equals("10-K") ? "FY" : "Q3", form, filed);
    }

    private static RawFinancialReport q4(String revenueFacts, String operatingFacts, boolean usGate) throws Exception {
        String json = """
                {"facts":{"us-gaap":{"Revenues":{"units":{"USD":[%s]}},"OperatingIncomeLoss":{"units":{"USD":[%s]}}}}}"""
                .formatted(revenueFacts, operatingFacts);
        JsonNode facts = new ObjectMapper().readTree(json).path("facts");
        List<RawFinancialReport> reports = SecEdgarFacts.build(facts, 2015, usGate).reports();
        return reports.stream().filter(r -> r.fiscalYear() == 2017 && "Q4".equals(r.fiscalPeriod()))
                .max(Comparator.comparing(RawFinancialReport::publishedAt)).orElseThrow();
    }

    private static final String Q1 = "0000000001-17-000001";
    private static final String Q2 = "0000000001-17-000002";

    /** 한 해 — 분기 셋(각 9개월 누계의 3분의 1) · 3분기 10-Q의 9개월 누계 · 10-K의 FY와 4분기 문맥. */
    private static String year(long fy, long q4Context, long nineMonths) {
        long q = nineMonths / 3;
        return String.join(",",
                fact("2017-01-01", "2017-03-31", q, Q1, "10-Q", "2017-05-01"),
                fact("2017-04-01", "2017-06-30", q, Q2, "10-Q", "2017-08-01"),
                fact("2017-01-01", "2017-06-30", 2 * q, Q2, "10-Q", "2017-08-01"),
                fact("2017-07-01", "2017-09-30", q, TEN_Q, "10-Q", "2017-11-01"),
                fact("2017-01-01", "2017-09-30", nineMonths, TEN_Q, "10-Q", "2017-11-01"),
                fact("2017-01-01", "2017-12-31", fy, TEN_K, "10-K", "2018-02-15"),
                fact("2017-10-01", "2017-12-31", q4Context, TEN_K, "10-K", "2018-02-15"));
    }

    @Test
    @DisplayName("관문 켬: 4분기 문맥 매출이 그 해 FY와 같고 9개월 실적이 있으면 그 4분기 문맥을 행 단위로 버리고 FY − 9개월로 유도한다")
    void quarterContextCarryingTheYearIsDroppedRowWide() throws Exception {
        // 매출 4분기 문맥 = FY 12,000(오태깅) · 영업이익 4분기 문맥 800(같은 문맥 — 행 단위로 함께 버린다)
        RawFinancialReport on = q4(year(12_000, 12_000, 9_000), year(1_200, 800, 900), true);

        assertThat(on.revenue()).as("FY − 9개월").isEqualByComparingTo("3000");
        assertThat(on.operatingIncome()).as("같은 문맥의 다른 컬럼도 유도로 — 한 행이 두 기간을 섞지 않는다").isEqualByComparingTo("300");
        assertThat(on.derived()).isTrue();

        RawFinancialReport off = q4(year(12_000, 12_000, 9_000), year(1_200, 800, 900), false);
        assertThat(off.revenue()).as("관문 꺼짐은 옛 출력 그대로").isEqualByComparingTo("12000");
    }

    @Test
    @DisplayName("그 해 9개월 실적이 0에 가까우면 4분기 = FY가 정당할 수 있어 둔다(첫해 · 한 분기에 몰린 실적)")
    void quarterEqualToYearWithoutPriorQuartersIsKept() throws Exception {
        RawFinancialReport on = q4(year(12_000, 12_000, 60), year(1_200, 1_200, 6), true);

        assertThat(on.revenue()).isEqualByComparingTo("12000");
        assertThat(on.derived()).isFalse();
    }
}
