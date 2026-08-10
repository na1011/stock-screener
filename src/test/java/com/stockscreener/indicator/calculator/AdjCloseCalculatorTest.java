package com.stockscreener.indicator.calculator;

import com.stockscreener.indicator.IndicatorContext;
import com.stockscreener.indicator.IndicatorPoint;
import com.stockscreener.indicator.PriceBar;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AdjCloseCalculatorTest {

    private static final LocalDate START = LocalDate.of(2024, 1, 1);

    private final AdjCloseCalculator calculator = new AdjCloseCalculator();

    @Test
    @DisplayName("봉마다 값을 낸다 — 워밍업 구간이 없다")
    void producesOnePointPerBar() {
        List<IndicatorPoint> points = calculator.calculate(contextOf(bars(5)));

        assertThat(points).hasSize(5);
        assertThat(points.get(0).tradeDate()).isEqualTo(START);
        assertThat(points.get(4).tradeDate()).isEqualTo(START.plusDays(4));
    }

    @Test
    @DisplayName("봉이 없으면 아무 값도 내지 않는다")
    void producesNothingWithoutBars() {
        assertThat(calculator.calculate(contextOf(List.of()))).isEmpty();
    }

    @Test
    @DisplayName("IMPORTANT — 원주가가 아니라 수정종가를 낸다. 이동평균과 축척이 맞아야 한다")
    void usesAdjustedCloseNotRawClose() {
        // 50:1 분할. 원주가는 분할 전 2,500,000이지만 수정종가는 전 구간 50,000이다.
        List<PriceBar> bars = List.of(
                new PriceBar(START,
                        BigDecimal.valueOf(2_500_000), BigDecimal.valueOf(2_500_000),
                        BigDecimal.valueOf(2_500_000), BigDecimal.valueOf(2_500_000),
                        BigDecimal.valueOf(50_000), 1000L, null, null),
                new PriceBar(START.plusDays(1),
                        BigDecimal.valueOf(50_000), BigDecimal.valueOf(50_000),
                        BigDecimal.valueOf(50_000), BigDecimal.valueOf(50_000),
                        BigDecimal.valueOf(50_000), 1000L, null, null));

        List<IndicatorPoint> points = calculator.calculate(contextOf(bars));

        // 둘 다 50,000이어야 한다. 원주가를 냈다면 첫 값이 2,500,000이고,
        // 그 값을 SMA_200(수정종가 기반)과 비교하는 조건은 조용히 틀린 종목을 고른다.
        assertThat(points).hasSize(2);
        assertThat(points.get(0).value()).isEqualByComparingTo("50000");
        assertThat(points.get(1).value()).isEqualByComparingTo("50000");
    }

    @Test
    @DisplayName("수정종가가 없는 봉은 건너뛴다 — 원주가로 대신 채우지 않는다")
    void skipsBarsWithoutAdjustedClose() {
        List<PriceBar> bars = new ArrayList<>(bars(3));
        bars.set(1, new PriceBar(START.plusDays(1),
                BigDecimal.valueOf(1000), BigDecimal.valueOf(1000),
                BigDecimal.valueOf(1000), BigDecimal.valueOf(1000),
                null, 1000L, null, null));

        List<IndicatorPoint> points = calculator.calculate(contextOf(bars));

        assertThat(points).hasSize(2);
        assertThat(points).extracting(IndicatorPoint::tradeDate)
                .containsExactly(START, START.plusDays(2));
    }

    @Test
    @DisplayName("봉 하나만 요구한다 — 다른 지표에 의존하지 않는다")
    void requiresSingleBarAndNoDependency() {
        assertThat(calculator.dataRequirement().priceBarsNeeded()).isEqualTo(1);
        assertThat(calculator.dataRequirement().financialYearsNeeded()).isZero();
        assertThat(calculator.dataRequirement().dependsOn()).isEmpty();
    }

    private static IndicatorContext contextOf(List<PriceBar> bars) {
        return new IndicatorContext(1L, bars, List.of(), List.of(), null);
    }

    private static List<PriceBar> bars(int count) {
        List<PriceBar> bars = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            BigDecimal price = BigDecimal.valueOf(1000 + i);
            bars.add(new PriceBar(START.plusDays(i), price, price, price, price, price, 1000L, null, null));
        }
        return bars;
    }
}
