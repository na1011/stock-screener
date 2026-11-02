package com.stockscreener.pipeline.datasource.us;

import com.stockscreener.pipeline.datasource.RawFinancialReport;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 관문 켬 27행 — 1분기가 없어 분기 셋으로 4분기를 못 지으면 FY − 9개월 누계로 짓는다. 10-Q 상장 첫해는 상장 전 1분기를 자기 기간으로 낸 공시가
 * 없어 그해 4분기가 안 생기고 TTM이 다음 FY까지 FY에 멈췄다(CRCL · CAI · FIG 2025).
 */
class SecEdgarNineMonthFourthQuarterTest {

    private static final long MILLION = 1_000_000L;

    private static String fact(String start, String end, long val, String accn, String form, String fp, int fy, String filed) {
        return """
                {"start":"%s","end":"%s","val":%d,"accn":"%s","fy":%d,"fp":"%s","form":"%s","filed":"%s"}"""
                .formatted(start, end, val * MILLION, accn, fy, fp, form, filed);
    }

    /** 상장 뒤 첫 10-Q(2분기)와 3분기 10-Q · 10-K. {@code nineMonthTag}로 3분기 10-Q의 9개월 누계 태그를 바꾼다. */
    private static List<RawFinancialReport> build(String nineMonthTag) throws Exception {
        String q2 = "0000000001-25-000002";
        String q3 = "0000000001-25-000003";
        String tenK = "0000000001-26-000001";
        String revenues = String.join(",",
                fact("2025-04-01", "2025-06-30", 300, q2, "10-Q", "Q2", 2025, "2025-08-12"),
                fact("2025-07-01", "2025-09-30", 350, q3, "10-Q", "Q3", 2025, "2025-11-12"),
                fact("2025-01-01", "2025-12-31", 1_300, tenK, "10-K", "FY", 2025, "2026-03-09"));
        String nine = fact("2025-01-01", "2025-09-30", 900, q3, "10-Q", "Q3", 2025, "2025-11-12");
        String json = "Revenues".equals(nineMonthTag)
                ? "{\"facts\":{\"us-gaap\":{\"Revenues\":{\"units\":{\"USD\":[" + revenues + "," + nine + "]}}}}}"
                : "{\"facts\":{\"us-gaap\":{\"Revenues\":{\"units\":{\"USD\":[" + revenues + "]}},\"" + nineMonthTag
                        + "\":{\"units\":{\"USD\":[" + nine + "]}}}}}";
        JsonNode facts = new ObjectMapper().readTree(json).path("facts");
        return SecEdgarFacts.build(facts, 2015).reports();
    }

    private static Optional<RawFinancialReport> q4(List<RawFinancialReport> reports) {
        return reports.stream().filter(r -> r.fiscalYear() == 2025 && "Q4".equals(r.fiscalPeriod())).findFirst();
    }

    @Test
    @DisplayName("1분기가 없으면 4분기 = FY − 9개월 누계(1,300 − 900 = 400) — 꺼짐은 옛 출력(4분기 없음)")
    void fourthQuarterFromNineMonthsWhenTheFirstQuarterIsMissing() throws Exception {
        RawFinancialReport q4 = q4(build("Revenues")).orElseThrow();
        assertThat(q4.revenue()).isEqualByComparingTo("400000000");
        assertThat(q4.periodStart()).hasToString("2025-10-01");
        assertThat(q4.publishedAt()).hasToString("2026-03-09");
    }

    @Test
    @DisplayName("9개월 누계가 FY와 다른 태그면 그 칸은 비운다 — 개념이 섞이지 않게")
    void nineMonthsUnderAnotherTagIsNotSubtracted() throws Exception {
        Optional<RawFinancialReport> q4 = q4(build("RevenueFromContractWithCustomerExcludingAssessedTax"));
        assertThat(q4.map(RawFinancialReport::revenue).orElse(null)).isNull();
    }
}
