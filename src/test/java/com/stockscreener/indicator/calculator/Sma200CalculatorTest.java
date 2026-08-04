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

class Sma200CalculatorTest {

    private final Sma200Calculator calculator = new Sma200Calculator();

    @Test
    @DisplayName("이력이 200봉 미만이면 아무 값도 내지 않는다")
    void producesNothingWhenHistoryInsufficient() {
        IndicatorContext context = contextOf(bars(199, BigDecimal.valueOf(1000)));

        assertThat(calculator.calculate(context)).isEmpty();
    }

    @Test
    @DisplayName("정확히 200봉이면 마지막 하루치만 계산된다")
    void producesSinglePointAtExactlyPeriod() {
        IndicatorContext context = contextOf(bars(200, BigDecimal.valueOf(1000)));

        List<IndicatorPoint> points = calculator.calculate(context);

        assertThat(points).hasSize(1);
        assertThat(points.get(0).value()).isEqualByComparingTo("1000");
    }

    @Test
    @DisplayName("워밍업 구간에는 값이 없고 그 이후만 계산된다")
    void skipsWarmupPeriod() {
        IndicatorContext context = contextOf(bars(250, BigDecimal.valueOf(1000)));

        List<IndicatorPoint> points = calculator.calculate(context);

        // 250봉이면 200봉째부터 계산되므로 51개다.
        assertThat(points).hasSize(51);
    }

    @Test
    @DisplayName("이동평균이 실제로 이동한다 — 값이 변하면 평균도 변한다")
    void averageMovesWithWindow() {
        List<PriceBar> bars = new ArrayList<>(bars(200, BigDecimal.valueOf(100)));
        // 201번째 봉만 크게 다른 값을 준다.
        bars.add(bar(LocalDate.of(2024, 1, 1).plusDays(200), BigDecimal.valueOf(20100)));

        List<IndicatorPoint> points = calculator.calculate(contextOf(bars));

        assertThat(points).hasSize(2);
        assertThat(points.get(0).value()).isEqualByComparingTo("100");
        // 첫 봉(100)이 빠지고 20100이 들어오므로 평균은 100 + (20100-100)/200 = 200
        assertThat(points.get(1).value()).isEqualByComparingTo("200");
    }

    @Test
    @DisplayName("IMPORTANT — 수정종가로 계산한다. 원주가를 쓰면 액면분할에서 값이 무너진다")
    void usesAdjustedClose() {
        // 50:1 분할을 가정한다. 원주가는 분할 전 2,500,000 / 분할 후 50,000이지만
        // 수정종가는 전 구간 50,000으로 일관된다.
        List<PriceBar> bars = new ArrayList<>();
        LocalDate date = LocalDate.of(2024, 1, 1);
        for (int i = 0; i < 199; i++) {
            bars.add(new PriceBar(date.plusDays(i),
                    BigDecimal.valueOf(2_500_000), BigDecimal.valueOf(2_500_000),
                    BigDecimal.valueOf(2_500_000), BigDecimal.valueOf(2_500_000),
                    BigDecimal.valueOf(50_000), 1000L));
        }
        bars.add(new PriceBar(date.plusDays(199),
                BigDecimal.valueOf(50_000), BigDecimal.valueOf(50_000),
                BigDecimal.valueOf(50_000), BigDecimal.valueOf(50_000),
                BigDecimal.valueOf(50_000), 1000L));

        List<IndicatorPoint> points = calculator.calculate(contextOf(bars));

        assertThat(points).hasSize(1);
        // 수정종가로 계산하면 50,000이다. 원주가로 계산했다면 약 2,487,750이 나온다.
        assertThat(points.get(0).value()).isEqualByComparingTo("50000");
    }

    private static IndicatorContext contextOf(List<PriceBar> bars) {
        return new IndicatorContext(1L, bars, List.of(), List.of(), null);
    }

    private static List<PriceBar> bars(int count, BigDecimal price) {
        List<PriceBar> bars = new ArrayList<>(count);
        LocalDate start = LocalDate.of(2024, 1, 1);
        for (int i = 0; i < count; i++) {
            bars.add(bar(start.plusDays(i), price));
        }
        return bars;
    }

    private static PriceBar bar(LocalDate date, BigDecimal price) {
        return new PriceBar(date, price, price, price, price, price, 1000L);
    }
}
