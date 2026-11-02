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
 * 관문 켬 — 전년 대비 성장률은 최신 두 회계연도가 이웃한 해일 때만 낸다. 사이가 빈 종목은 몇 년 치 변화를 1년 치로 냈다(DSGX FY2026 대 FY2022).
 */
class GrowthConsecutiveYearsTest {

    private static final LocalDate START = LocalDate.of(2026, 3, 2);

    private static IndicatorContext context(int latestYear, int previousYear) {
        List<FinancialFigures> annual = List.of(
                FinancialFiguresFixture.annual(LocalDate.of(latestYear, 12, 31)).revenue(BigDecimal.valueOf(1_200))
                        .operatingIncome(BigDecimal.valueOf(240)).build(),
                FinancialFiguresFixture.annual(LocalDate.of(previousYear, 12, 31)).revenue(BigDecimal.valueOf(1_000))
                        .operatingIncome(BigDecimal.valueOf(200)).build());
        List<PriceBar> bars = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            BigDecimal price = BigDecimal.valueOf(100 + i);
            bars.add(new PriceBar(START.plusDays(i), price, price, price, price, price, 1_000L, null, null));
        }
        return new IndicatorContext(1L, bars, annual, List.of(), Map.of());
    }

    @Test
    @DisplayName("최신 두 FY 사이가 비면 매출 · 영업이익 성장률을 안 낸다 — 이웃한 해면 낸다")
    void growthNeedsAdjacentYears() {
        assertThat(new RevenueGrowthCalculator().calculate(context(2025, 2022))).isEmpty();
        assertThat(new OperatingIncomeGrowthCalculator().calculate(context(2025, 2022))).isEmpty();
        assertThat(new RevenueGrowthCalculator().calculate(context(2025, 2024)).getLast().value())
                .isEqualByComparingTo("20");
    }
}
