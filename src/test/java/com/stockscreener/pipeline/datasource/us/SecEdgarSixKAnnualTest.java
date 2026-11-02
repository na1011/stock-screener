package com.stockscreener.pipeline.datasource.us;

import com.stockscreener.pipeline.datasource.RawFinancialReport;
import com.stockscreener.pipeline.datasource.us.shareclass.CoverUnitJudge;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 관문 켬 — 연간 실적을 6-K로만 낸 해. 6-K가 자기 기간으로 실은 12개월 창은 끝이 선언 결산월과 맞고 겹치는 연차 창이 없을 때 회계연도다.
 *
 * <p>무너지는 방식이 조용하다 — 40-F가 연간 기간을 XBRL로 안 실으면 그 해 FY와 분기 라벨이 통째로 빈다(DSGX 2024 · 2025).
 */
class SecEdgarSixKAnnualTest {

    private static String fact(String start, String end, long val, String accn, String filed) {
        return """
                {"start":"%s","end":"%s","val":%d,"accn":"%s","fy":2024,"fp":"FY","form":"6-K","filed":"%s"}"""
                .formatted(start, end, val, accn, filed);
    }

    private static List<RawFinancialReport> build(Integer fiscalYearEndMonth) throws Exception {
        String json = """
                {"facts":{"us-gaap":{"Revenues":{"units":{"USD":[%s,%s]}}}}}""".formatted(
                fact("2023-02-01", "2024-01-31", 572_931_000L, "0000000001-24-000001", "2024-03-07"),
                fact("2024-02-01", "2025-01-31", 651_000_000L, "0000000001-25-000001", "2025-03-06"));
        JsonNode facts = new ObjectMapper().readTree(json).path("facts");
        return SecEdgarFacts.build(facts, 2018, CoverUnitJudge.Result.NONE,
                new IssuerProfile.Profile(fiscalYearEndMonth, null)).reports();
    }

    @Test
    @DisplayName("선언 결산월(1월)에 끝나는 6-K 12개월 창은 회계연도다 — FY 라벨이 선다")
    void sixKAnnualWindowEndingInTheDeclaredMonthIsAFiscalYear() throws Exception {
        assertThat(build(1)).filteredOn(r -> "FY".equals(r.fiscalPeriod()))
                .extracting(RawFinancialReport::fiscalYear).containsExactlyInAnyOrder(2024, 2025);
    }

    @Test
    @DisplayName("연차 보고서(20-F · 40-F)가 이미 그 회계연도 창을 냈으면 같은 기간의 6-K 12개월 창은 더하지 않는다 — 출력이 그대로다(TSEM 꼴)")
    void sixKWindowOverlappingAnAnnualWindowIsNotAdded() throws Exception {
        String annual = """
                {"start":"2023-02-01","end":"2024-01-31","val":572931000,"accn":"0000000001-24-000009","fy":2024,"fp":"FY","form":"40-F","filed":"2024-04-22"}""";
        String json = """
                {"facts":{"us-gaap":{"Revenues":{"units":{"USD":[%s,%s]}}}}}""".formatted(annual,
                fact("2023-02-01", "2024-01-31", 572_931_000L, "0000000001-24-000001", "2024-03-07"));
        JsonNode facts = new ObjectMapper().readTree(json).path("facts");
        List<RawFinancialReport> on = SecEdgarFacts.build(facts, 2018, CoverUnitJudge.Result.NONE,
                new IssuerProfile.Profile(1, null)).reports();

        // 관문 전 출력(6-K 창을 안 읽던 규칙)과 같은 FY 판 수 — 그 대조가 단언하던 수를 박는다(2026-11-03 관문 설정 걷기).
        assertThat(on).filteredOn(r -> "FY".equals(r.fiscalPeriod())).hasSize(1);
    }

    @Test
    @DisplayName("결산월 증인이 없거나 맞지 않으면 6-K 12개월 창을 회계연도로 안 읽는다 — 연차 창이 없는 해의 롤링 12개월 창 방어")
    void sixKAnnualWindowWithoutTheWitnessIsNotAFiscalYear() throws Exception {
        assertThat(build(null)).noneMatch(r -> "FY".equals(r.fiscalPeriod()));
        assertThat(build(6)).noneMatch(r -> "FY".equals(r.fiscalPeriod()));
    }
}
