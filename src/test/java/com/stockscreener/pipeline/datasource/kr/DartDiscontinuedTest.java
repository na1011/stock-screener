package com.stockscreener.pipeline.datasource.kr;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 국내 중단영업 판정(F2b · ADR-0184). 행은 실제 {@code fnlttSinglAcntAll} 응답의 손익계산서 꼴을 줄인 것이다.
 *
 * <p>K-IFRS 1105는 중단영업이 있을 때만 계속 · 중단을 갈라 표시한다 — 줄이 없으면 0이고, 지배 몫은 항등식으로만 정한다.
 */
class DartDiscontinuedTest {

    private static List<JsonNode> income(String rows) throws Exception {
        JsonNode list = new ObjectMapper().readTree("[" + rows + "]");
        return DartFullAccountMapper.sections(list, DartFullAccountMapper.SJ_INCOME,
                DartFullAccountMapper.SJ_COMPREHENSIVE_INCOME);
    }

    private static String row(String id, String amount, String cumulative) {
        return """
                {"sj_div":"IS","account_id":"%s","account_nm":"x","thstrm_amount":"%s","thstrm_add_amount":"%s"}"""
                .formatted(id, amount, cumulative);
    }

    @Test
    @DisplayName("중단 줄이 없는 손익계산서는 0이다")
    void noLineIsZero() throws Exception {
        BigDecimal[] d = DartFullAccountMapper.discontinued(income(row("ifrs-full_ProfitLoss", "100", "300")), false);

        assertThat(d[0]).isEqualByComparingTo("0");
        assertThat(d[1]).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("지배주주 순이익 줄 없이 계속 · 중단 지배 몫만 주면 지배 순이익은 그 합이다(솔브레인홀딩스 FY2025 꼴)")
    void controllingIncomeFromParts() throws Exception {
        List<JsonNode> rows = income(String.join(",",
                row("ifrs-full_ProfitLoss", "495558016939", ""),
                row("ifrs-full_ProfitLossFromDiscontinuedOperations", "445245906229", ""),
                row("ifrs-full_IncomeFromContinuingOperationsAttributableToOwnersOfParent", "42100000000", ""),
                row("ifrs-full_IncomeFromDiscontinuedOperationsAttributableToOwnersOfParent", "445200000000", "")));

        assertThat(DartFullAccountMapper.controllingIncomeFromParts(rows)).isEqualByComparingTo("487300000000");
        BigDecimal[] d = DartFullAccountMapper.discontinued(rows, false);
        assertThat(d[0]).isEqualByComparingTo("445245906229");
        assertThat(d[1]).as("지배 몫 직접").isEqualByComparingTo("445200000000");
    }

    @Test
    @DisplayName("비지배가 있고 지배 몫을 안 주면 모른다 — 「비지배 몫 0」으로 채우지 않는다")
    void unresolvedControllingShareStaysUnknown() throws Exception {
        BigDecimal[] d = DartFullAccountMapper.discontinued(income(String.join(",",
                row("ifrs-full_ProfitLoss", "1000", ""),
                row("ifrs-full_ProfitLossAttributableToOwnersOfParent", "800", ""),
                row("ifrs-full_ProfitLossFromDiscontinuedOperations", "100", ""))), false);

        assertThat(d[0]).isEqualByComparingTo("100");
        assertThat(d[1]).isNull();
    }

    @Test
    @DisplayName("지배 몫 줄이 없으면 주당이익 비로 짓는다 — 원화 EPS 반올림 오차 상한이 0.5% 안일 때만(LG화학 2026 반기 꼴)")
    void epsRatioResolvesWhenRoundingIsSmall() throws Exception {
        String base = String.join(",",
                row("ifrs-full_ProfitLoss", "100000000000", ""),
                row("ifrs-full_ProfitLossAttributableToOwnersOfParent", "113453000000", ""),
                row("ifrs-full_ProfitLossFromDiscontinuedOperations", "-5000000000", ""));
        BigDecimal[] d = DartFullAccountMapper.discontinued(income(String.join(",", base,
                row("ifrs-full_BasicEarningsLossPerShare", "1445", ""),
                row("ifrs-full_BasicEarningsLossPerShareFromContinuingOperations", "1504", ""))), false);
        // 지배 계속 = 113,453 × 1504/1445 ≈ 118,085(직접 태그 118,128과 0.04%) → 지배 중단 ≈ −4,632(백만).
        assertThat(d[1]).isNotNull();
        assertThat(d[1].doubleValue()).isBetween(-4_700_000_000.0, -4_600_000_000.0);

        BigDecimal[] small = DartFullAccountMapper.discontinued(income(String.join(",", base,
                row("ifrs-full_BasicEarningsLossPerShare", "45", ""),
                row("ifrs-full_BasicEarningsLossPerShareFromContinuingOperations", "47", ""))), false);
        assertThat(small[1]).as("EPS가 작아 반올림 오차 상한이 2%를 넘는다").isNull();
    }

    @Test
    @DisplayName("같은 보고서에서 지배주주 순이익 = 연결 순이익이면(그 기간 비지배 0) 지배 몫은 연결 몫이다")
    void noNoncontrollingInTheReportGivesTheConsolidatedShare() throws Exception {
        BigDecimal[] d = DartFullAccountMapper.discontinued(income(String.join(",",
                row("ifrs-full_ProfitLoss", "1000", ""),
                row("ifrs-full_ProfitLossAttributableToOwnersOfParent", "1000", ""),
                row("ifrs-full_ProfitLossFromDiscontinuedOperations", "100", ""))), false);

        assertThat(d[1]).isEqualByComparingTo("100");
    }

    @Test
    @DisplayName("4분기 = FY − Q3 보고서 누계 — Q3에 중단 줄이 없는데 FY ≠ 0이면 모른다(솔브레인홀딩스 · 카카오 꼴)")
    void fourthQuarterIsFiscalYearMinusNineMonths() throws Exception {
        ObjectMapper json = new ObjectMapper();
        JsonNode fy = json.readTree("[" + String.join(",",
                row("ifrs-full_ProfitLossFromDiscontinuedOperations", "445245906229", ""),
                row("ifrs-full_IncomeFromDiscontinuedOperationsAttributableToOwnersOfParent", "445245906229", "")) + "]");
        JsonNode q3 = json.readTree("[" + String.join(",",
                row("ifrs-full_ProfitLossFromDiscontinuedOperations", "1", "20850000000"),
                row("ifrs-full_IncomeFromDiscontinuedOperationsAttributableToOwnersOfParent", "1", "20850000000")) + "]");
        JsonNode q3WithoutLine = json.readTree("[" + row("ifrs-full_ProfitLoss", "10", "30") + "]");
        com.stockscreener.pipeline.datasource.RawFinancialReport q4 = new com.stockscreener.pipeline.datasource.RawFinancialReport(
                2025, "Q4", java.time.LocalDate.of(2025, 10, 1), java.time.LocalDate.of(2025, 12, 31),
                java.time.LocalDate.of(2026, 3, 15), "11011", "KRW", null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, false, java.util.Set.of());

        com.stockscreener.pipeline.datasource.RawFinancialReport derived =
                DartFullStatementAdapter.withFourthQuarterDiscontinued(q4, fy, q3);
        assertThat(derived.discontinuedIncome()).as("445.2B − 20.85B").isEqualByComparingTo("424395906229");
        assertThat(derived.discontinuedIncomeControlling()).isEqualByComparingTo("424395906229");

        com.stockscreener.pipeline.datasource.RawFinancialReport unknown =
                DartFullStatementAdapter.withFourthQuarterDiscontinued(q4, fy, q3WithoutLine);
        assertThat(unknown.discontinuedRule()).isEqualByComparingTo("1");
        assertThat(unknown.discontinuedIncome()).isNull();
        assertThat(unknown.discontinuedIncomeControlling()).isNull();
    }

    @Test
    @DisplayName("누계 칸을 따로 읽는다 — Q3 보고서의 9개월이 4분기 유도의 재료다")
    void readsTheCumulativeColumn() throws Exception {
        List<JsonNode> rows = income(String.join(",",
                row("ifrs-full_ProfitLossFromDiscontinuedOperations", "5", "20850000000"),
                row("ifrs-full_IncomeFromDiscontinuedOperationsAttributableToOwnersOfParent", "5", "20850000000")));

        assertThat(DartFullAccountMapper.hasDiscontinuedLine(rows)).isTrue();
        assertThat(DartFullAccountMapper.discontinued(rows, true)[1]).isEqualByComparingTo("20850000000");
    }
}
