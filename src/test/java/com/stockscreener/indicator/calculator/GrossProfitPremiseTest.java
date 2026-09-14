package com.stockscreener.indicator.calculator;

import com.stockscreener.indicator.FinancialFigures;
import com.stockscreener.indicator.GrossProfitPremise;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 매출총이익률의 전제 검사. <b>수는 전부 2026-09-14 실측이다</b>(활성 편입 전 시장).
 *
 * <p>이 시험의 목적은 「걸리는 것」보다 <b>「안 걸리는 것」</b>이다 — 앞선 설계 둘이 정상 종목을
 * 잘랐고, 그것을 잡은 것은 논증이 아니라 대조군이었다.
 */
class GrossProfitPremiseTest {

    @Test
    @DisplayName("CNP — 회사가 같은 태그에 담는 내용을 바꿔 원가가 무너졌고 회복되지 않았다")
    void catchesCostCollapse() {
        // FY2017 원가율 39.37% → FY2018 0.64%. 원가는 3,785M → 40M(1/95)인데 매출은
        // 9,614M → 6,277M(0.65배)로 유지됐다. 후보 태그는 CostOfGoodsAndServicesSold 하나뿐이라
        // Selection.LARGEST로는 원리적으로 못 고친다(EDGAR 탐침).
        List<FinancialFigures> annual = List.of(
                fy(2025, "9357000000", "4000000"),
                fy(2024, "8643000000", "3000000"),
                fy(2018, "6277000000", "40000000"),
                fy(2017, "9614000000", "3785000000"));
        assertThat(GrossProfitPremise.of(annual)).isEqualTo(GrossProfitPremise.Break.COST_COLLAPSED);
    }

    @Test
    @DisplayName("BBIO — 원가율은 26.29%→1.75%로 떨어졌지만 원가가 늘고 매출이 24배 컸다")
    void doesNotCatchRevenueGrowth() {
        // 여기가 이 설계의 전부다. 「원가율 <2%」로 판정하면 BBIO(1.75%)와 JEF(1.76%)를
        // 원리적으로 못 가른다 — 임계가 존재하지 않는다. 원가 절대액의 전이를 보면 갈린다:
        // BBIO는 원가배 1.5854(늘었다)이고 매출배 23.853이다.
        List<FinancialFigures> annual = List.of(
                fy(2024, "500913000", "8752500"),
                fy(2023, "21000000", "5520900"));
        assertThat(GrossProfitPremise.of(annual)).isNull();
    }

    @Test
    @DisplayName("IONS — 이력 내내 원가율이 작은 것은 그 회사의 사실이다")
    void doesNotCatchConsistentlySmallCost() {
        // 로열티·협업 매출 기업의 98% 매출총이익률은 결함이 아니라 버핏 우량주가 찾는 해자
        // 신호다. 「원가 ÷ 총비용 < 5%」로 자르면 SIC:28 제약사 여섯이 함께 걸린다.
        List<FinancialFigures> annual = List.of(
                fy(2025, "944000000", "16000000"),
                fy(2024, "705000000", "11000000"),
                fy(2018, "600000000", "2000000"));
        assertThat(GrossProfitPremise.of(annual)).isNull();
    }

    @Test
    @DisplayName("MCY — 보험 손익에서 매출원가는 급부총액의 조각이다")
    void catchesInsuranceFragment() {
        // 매출 5,992M · 매출원가 15M · 보험금손실비용 5,329M. 15/5,329 = 0.28%.
        List<FinancialFigures> annual = List.of(
                FinancialFiguresFixture.annual(LocalDate.of(2025, 12, 31))
                        .revenue(new BigDecimal("5992500000"))
                        .costOfSales(new BigDecimal("15000000"))
                        .benefitsLossesAndExpenses(new BigDecimal("5328800000"))
                        .build());
        assertThat(GrossProfitPremise.of(annual))
                .isEqualTo(GrossProfitPremise.Break.INSURANCE_FRAGMENT);
    }

    @Test
    @DisplayName("CI — 보험사라도 매출원가가 급부총액의 대부분이면 그것은 진짜 원가다")
    void doesNotCatchInsurerWithRealCostOfSales() {
        // 매출 274,900M · 매출원가 215,000M · 급부총액 265,700M → 80.9%. 약국·의료비가 실제
        // 원가이고 매출총이익률 21.79%는 그럴듯하다.
        //
        // MUST NOT: revenue − benefits_losses_and_expenses로 대체하지 않는다. 그것은
        // 세전이익이다 — CI는 9,200M으로 operating_income과, MCY는 663,649,000으로
        // profit_before_tax와 소수점까지 같다(급부총액 보유 40종목 중 33이 완전 일치).
        List<FinancialFigures> annual = List.of(
                FinancialFiguresFixture.annual(LocalDate.of(2025, 12, 31))
                        .revenue(new BigDecimal("274900000000"))
                        .costOfSales(new BigDecimal("215000000000"))
                        .benefitsLossesAndExpenses(new BigDecimal("265700000000"))
                        .build());
        assertThat(GrossProfitPremise.of(annual)).isNull();
    }

    @Test
    @DisplayName("대주전자재료 — 연간 매출원가가 음수다")
    void catchesNegativeCost() {
        List<FinancialFigures> annual = List.of(
                fy(2024, "219316249770", "-157329839845"));
        assertThat(GrossProfitPremise.of(annual)).isEqualTo(GrossProfitPremise.Break.NEGATIVE_COST);
    }

    @Test
    @DisplayName("회사가 매출총이익을 신고했으면 검사하지 않는다")
    void skipsWhenGrossProfitIsReported() {
        // 실측: 신고 경로 416종목에 GM ≥ 95%가 한 건도 없다. 병은 유도 경로에만 있다.
        List<FinancialFigures> annual = List.of(
                FinancialFiguresFixture.from(fy(2025, "9357000000", "4000000"))
                        .grossProfit(new BigDecimal("3000000000")).build(),
                fy(2017, "9614000000", "3785000000"));
        assertThat(GrossProfitPremise.of(annual)).isNull();
    }

    @Test
    @DisplayName("같은 회계연도의 판 둘을 이웃 연도로 읽지 않는다")
    void foldsRevisionsOfTheSameFiscalYear() {
        // KNOWN_ON 가시성은 판 이력을 통째로 준다(ADR-0128). 안 접으면 같은 해의 두 판이
        // 이웃 연도가 되어 전이가 없는데도 있는 것으로 읽는다.
        List<FinancialFigures> annual = List.of(
                fy(2025, "9357000000", "4000000"),
                fy(2025, "9357000000", "3800000000"));
        assertThat(GrossProfitPremise.of(annual)).isNull();
    }

    private static FinancialFigures fy(int year, String revenue, String cost) {
        return FinancialFiguresFixture.annual(LocalDate.of(year, 12, 31))
                .revenue(new BigDecimal(revenue))
                .costOfSales(new BigDecimal(cost))
                .build();
    }
}
