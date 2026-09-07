package com.stockscreener.indicator.calculator;

import com.stockscreener.indicator.FinancialFigures;
import com.stockscreener.indicator.IndicatorContext;
import com.stockscreener.indicator.PriceBar;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 매출총이익 계열 둘. 재는 것은 <b>분모가 서로 다르다는 것</b>과, <b>매출원가가 없는 것을
 * 0으로 읽지 않는다</b>는 것이다.
 *
 * <p>후자가 이 지표들의 고유한 위험이다 — 매출원가를 0으로 취급하면 매출총이익률이 100%가
 * 되고, 금융업·서비스업이 <b>총이익성 상위</b>에 통째로 올라온다. 예외도 빈 결과도 아니라서
 * 화면에서는 「금융주가 원가 효율이 좋다」로 읽힌다.
 */
class GrossProfitIndicatorsTest {

    private static final LocalDate START = LocalDate.of(2024, 1, 2);

    private final GrossMarginCalculator margin = new GrossMarginCalculator();
    private final GrossProfitToAssetsCalculator toAssets = new GrossProfitToAssetsCalculator();

    @Test
    @DisplayName("매출총이익률은 매출로, 총이익성은 총자산으로 나눈다")
    void theTwoUseDifferentDenominators() {
        // 매출 1,000 · 매출원가 600 → 총이익 400. 총자산 2,000.
        IndicatorContext context = context(1_000, 600, 2_000);

        assertThat(margin.calculate(context).getLast().value())
                .as("400 / 1,000")
                .isEqualByComparingTo("40.00000000");
        assertThat(toAssets.calculate(context).getLast().value())
                .as("400 / 2,000")
                .isEqualByComparingTo("20.00000000");
    }

    @Test
    @DisplayName("회사가 신고한 매출총이익이 유도값을 이긴다")
    void reportedGrossProfitWins() {
        // 적재는 되고 있는데(US_TOP1000의 417종목이 갖는다) 여기서 한 번도 안 읽었다.
        // ADR-0104 §규칙의 「소스가 낸 값이 언제나 이긴다」와 방향이 반대였던 자리다.
        List<FinancialFigures> annual = List.of(
                FinancialFiguresFixture.annual(LocalDate.of(2025, 12, 31))
                        .revenue(BigDecimal.valueOf(1_000))
                        .costOfSales(BigDecimal.valueOf(600))
                        .grossProfit(BigDecimal.valueOf(250))
                        .totalAssets(BigDecimal.valueOf(2_000))
                        .build());
        IndicatorContext context =
                new IndicatorContext(1L, bars(), annual, List.of(), Map.of());

        assertThat(margin.calculate(context).getLast().value())
                .as("유도값 400이 아니라 신고값 250이어야 한다")
                .isEqualByComparingTo("25.00000000");
    }

    @Test
    @DisplayName("매출원가가 없어도 신고 매출총이익이 있으면 값이 나온다")
    void usesReportedGrossProfitWithoutCostOfSales() {
        // 실측으로 최신 FY에 이 모양인 종목이 19개다. 유도만 보던 때는 전부 빈칸이었다.
        List<FinancialFigures> annual = List.of(
                FinancialFiguresFixture.annual(LocalDate.of(2025, 12, 31))
                        .revenue(BigDecimal.valueOf(1_000))
                        .grossProfit(BigDecimal.valueOf(250))
                        .totalAssets(BigDecimal.valueOf(2_000))
                        .build());
        IndicatorContext context =
                new IndicatorContext(1L, bars(), annual, List.of(), Map.of());

        assertThat(margin.calculate(context).getLast().value()).isEqualByComparingTo("25.00000000");
        assertThat(toAssets.calculate(context).getLast().value())
                .isEqualByComparingTo("12.50000000");
    }

    @Test
    @DisplayName("매출원가가 없으면 값을 내지 않는다 — 0으로 읽으면 마진이 100%가 된다")
    void emitsNothingWhenCostOfSalesIsAbsent() {
        List<FinancialFigures> annual = List.of(
                FinancialFiguresFixture.annual(LocalDate.of(2025, 12, 31))
                        .revenue(BigDecimal.valueOf(1_000))
                        .totalAssets(BigDecimal.valueOf(2_000))
                        .build());
        IndicatorContext context =
                new IndicatorContext(1L, bars(), annual, List.of(), Map.of());

        assertThat(margin.calculate(context))
                .as("금융업·서비스업은 매출원가 개념이 없다. 결손이 아니라 원래 없는 것이다")
                .isEmpty();
        assertThat(toAssets.calculate(context)).isEmpty();
    }

    @Test
    @DisplayName("매출이 0 이하면 값을 내지 않는다")
    void emitsNothingWithoutPositiveRevenue() {
        assertThat(margin.calculate(context(0, 600, 2_000))).isEmpty();
        assertThat(toAssets.calculate(context(0, 600, 2_000))).isEmpty();
    }

    @Test
    @DisplayName("원가가 매출을 넘으면 음수다 — 부호를 거르지 않는다")
    void keepsTheSignWhenCostExceedsRevenue() {
        assertThat(margin.calculate(context(1_000, 1_200, 2_000)).getLast().value())
                .isEqualByComparingTo("-20.00000000");
    }

    @Test
    @DisplayName("총자산이 0 이하면 총이익성만 값을 내지 않는다")
    void onlyTheAssetRatioNeedsAssets() {
        assertThat(margin.calculate(context(1_000, 600, 0))).isNotEmpty();
        assertThat(toAssets.calculate(context(1_000, 600, 0))).isEmpty();
    }

    // ── 픽스처 ────────────────────────────────────────────────────────────

    private static IndicatorContext context(double revenue, double costOfSales, double assets) {
        List<FinancialFigures> annual = List.of(
                FinancialFiguresFixture.annual(LocalDate.of(2025, 12, 31))
                        .revenue(BigDecimal.valueOf(revenue))
                        .costOfSales(BigDecimal.valueOf(costOfSales))
                        .totalAssets(BigDecimal.valueOf(assets))
                        .build());
        return new IndicatorContext(1L, bars(), annual, List.of(), Map.of());
    }

    private static List<PriceBar> bars() {
        List<PriceBar> bars = new ArrayList<>(10);
        for (int i = 0; i < 10; i++) {
            BigDecimal price = BigDecimal.valueOf(100 + i);
            bars.add(new PriceBar(START.plusDays(i), price, price, price, price, price,
                    1_000L, null, null));
        }
        return bars;
    }
}
