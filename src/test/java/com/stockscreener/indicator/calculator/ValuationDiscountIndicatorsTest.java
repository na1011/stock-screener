package com.stockscreener.indicator.calculator;

import com.stockscreener.indicator.FinancialFigures;
import com.stockscreener.indicator.IndicatorContext;
import com.stockscreener.indicator.IndicatorPoint;
import com.stockscreener.indicator.PriceBar;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 자기 5년 이력 대비 할인율과 그 재료가 되는 5년 중앙값들.
 *
 * <p>이 파일에서 가장 중요한 검사 둘 — <b>PBR은 저량이라 4분기를 더하면 네 배가 된다</b>는 것과,
 * 할인율이 <b>가장 최근 봉</b>에서 계산된다는 것이다. 둘 다 틀려도 값이 그럴듯하게 나온다.
 */
class ValuationDiscountIndicatorsTest {

    private static final LocalDate START = LocalDate.of(2020, 1, 2);
    private static final int BARS = ValuationHistory.LOOKBACK + 2;

    @Nested
    @DisplayName("할인율 — (중앙값 − 현재) ÷ 중앙값")
    class Discount {

        private final PerDiscount5yCalculator calculator = new PerDiscount5yCalculator();

        @Test
        @DisplayName("중앙값 20, 현재 15면 25% 싸다")
        void computesDiscount() {
            List<IndicatorPoint> points = calculator.calculate(
                    context(3, Map.of(2, "15"), Map.of(2, "20")));

            assertThat(points).isNotEmpty();
            assertThat(points.get(0).value()).isEqualByComparingTo("25");
        }

        @Test
        @DisplayName("현재가 중앙값보다 높으면 음수 — 자기 과거보다 비싸다")
        void negativeWhenExpensive() {
            assertThat(calculator.calculate(context(3, Map.of(2, "25"), Map.of(2, "20")))
                    .get(0).value()).isEqualByComparingTo("-25");
        }

        @Test
        @DisplayName("IMPORTANT — 봉마다 자기 배수로 낸다. 마지막 봉의 값을 펴면 과거가 미래를 본다")
        void usesLatestBarNotFirst() {
            // 0번 봉은 40% 할인, 2번 봉은 25% 할인. **둘 다 나와야 한다** — 봉마다 자기 값이다.
            List<IndicatorPoint> points = calculator.calculate(
                    context(3, Map.of(0, "12", 2, "15"), Map.of(0, "20", 2, "20")));

            assertThat(points).hasSize(2);
            assertThat(points.get(0).value())
                    .as("0번 봉은 자기 배수로 40이다 — 마지막 봉의 값을 펴면 25가 나온다")
                    .isEqualByComparingTo("40");
            assertThat(points.get(1).value())
                    .as("2번 봉은 25다")
                    .isEqualByComparingTo("25");
        }

        @Test
        @DisplayName("최신 봉에 값이 없으면 그 앞의 봉으로 거슬러 올라간다")
        void fallsBackToEarlierBar() {
            assertThat(calculator.calculate(context(3, Map.of(1, "15"), Map.of(1, "20")))
                    .get(0).value()).isEqualByComparingTo("25");
        }

        @Test
        @DisplayName("부모가 있는 봉에만 낸다 — 없는 봉을 채우면 그 값은 그 뒤의 것이다")
        void emitsOnlyWhereBothParentsExist() {
            List<IndicatorPoint> points = calculator.calculate(
                    context(3, Map.of(2, "15"), Map.of(2, "20")));

            // 부모(배수·중앙값)가 2번 봉에만 있다. 예전에는 그 값 하나를 세 봉에 폈는데,
            // 그러면 0·1번 봉이 **그 뒤의 값**을 갖는다(ADR-0128).
            assertThat(points).hasSize(1);
            assertThat(points.get(0).value()).isEqualByComparingTo("25");
        }

        @Test
        @DisplayName("중앙값이 0 이하면 계산하지 않는다 — 나눗셈이 뜻을 잃는다")
        void producesNothingWhenMedianNotPositive() {
            assertThat(calculator.calculate(context(3, Map.of(2, "15"), Map.of(2, "0"))))
                    .isEmpty();
        }

        @Test
        @DisplayName("한쪽만 있으면 계산하지 않는다")
        void producesNothingWithoutBothInputs() {
            assertThat(calculator.calculate(context(3, Map.of(2, "15"), Map.of()))).isEmpty();
        }

        private IndicatorContext context(int barCount,
                                         Map<Integer, String> base,
                                         Map<Integer, String> median) {
            List<PriceBar> bars = new ArrayList<>();
            NavigableMap<LocalDate, BigDecimal> baseSeries = new TreeMap<>();
            NavigableMap<LocalDate, BigDecimal> medianSeries = new TreeMap<>();
            for (int i = 0; i < barCount; i++) {
                LocalDate date = START.plusDays(i);
                bars.add(bar(date, BigDecimal.TEN));
                if (base.containsKey(i)) {
                    baseSeries.put(date, new BigDecimal(base.get(i)));
                }
                if (median.containsKey(i)) {
                    medianSeries.put(date, new BigDecimal(median.get(i)));
                }
            }
            return new IndicatorContext(1L, bars, List.of(), List.of(),
                    Map.of(PerCalculator.CODE, baseSeries,
                            PerMedian5yCalculator.CODE, medianSeries));
        }
    }

    @Nested
    @DisplayName("5년 중앙값 — 유량과 저량을 가른다")
    class Median {

        @Test
        @DisplayName("IMPORTANT — PBR은 저량이라 그 시점 잔액 하나를 쓴다. 4분기를 더하면 네 배다")
        void pbrUsesBalanceNotSum() {
            // 자기자본 잔액 4000, 주식수 100 → BPS 40. 주가 20 → PBR 0.5
            // 4분기를 더했다면 BPS 160이 되어 PBR 0.125가 나온다.
            List<IndicatorPoint> points = new PbrMedian5yCalculator().calculate(
                    context(BigDecimal.valueOf(20), q -> q
                            .totalEquity(BigDecimal.valueOf(4000))
                            .sharesOutstanding(BigDecimal.valueOf(100))));

            assertThat(points).isNotEmpty();
            assertThat(points.get(0).value()).isEqualByComparingTo("0.5");
        }

        @Test
        @DisplayName("PSR은 유량이라 4분기를 더한다")
        void psrSumsFourQuarters() {
            // 분기 매출 250 × 4 = 1000, 주식수 100 → 주당매출 10. 주가 20 → PSR 2
            List<IndicatorPoint> points = new PsrMedian5yCalculator().calculate(
                    context(BigDecimal.valueOf(20), q -> q
                            .revenue(BigDecimal.valueOf(250))
                            .sharesOutstanding(BigDecimal.valueOf(100))));

            assertThat(points).isNotEmpty();
            assertThat(points.get(0).value()).isEqualByComparingTo("2");
        }

        @Test
        @DisplayName("자본잠식 구간은 표본에서 뺀다 — 음수 PBR이 중앙값을 끌어내려 「싸다」로 읽힌다")
        void pbrSkipsNegativeEquity() {
            assertThat(new PbrMedian5yCalculator().calculate(
                    context(BigDecimal.valueOf(20), q -> q
                            .totalEquity(BigDecimal.valueOf(-4000))
                            .sharesOutstanding(BigDecimal.valueOf(100)))))
                    .isEmpty();
        }

        @Test
        @DisplayName("봉이 5년치에 못 미치면 계산하지 않는다")
        void producesNothingWithoutFullWindow() {
            List<PriceBar> bars = new ArrayList<>();
            for (int i = 0; i < 10; i++) {
                bars.add(bar(START.plusDays(i), BigDecimal.valueOf(20)));
            }
            assertThat(new PsrMedian5yCalculator().calculate(new IndicatorContext(
                    1L, bars, List.of(), quarters(q -> q
                            .revenue(BigDecimal.valueOf(250))
                            .sharesOutstanding(BigDecimal.valueOf(100))), null)))
                    .isEmpty();
        }

        private IndicatorContext context(BigDecimal close,
                                         java.util.function.UnaryOperator<FinancialFiguresFixture> shape) {
            List<PriceBar> bars = new ArrayList<>(BARS);
            for (int i = 0; i < BARS; i++) {
                bars.add(bar(START.plusDays(i), close));
            }
            return new IndicatorContext(1L, bars, List.of(), quarters(shape), null);
        }
    }

    private static List<FinancialFigures> quarters(
            java.util.function.UnaryOperator<FinancialFiguresFixture> shape) {
        // 창 전체를 덮도록 분기를 넉넉히 만든다.
        //
        // 기말을 반년 뒤로 미는 것이 요점이다 — 픽스처가 공시일을 기말 + 2개월로 잡으므로,
        // 기말이 START에 가까우면 공시일이 창 시작 뒤가 되어 앞쪽 봉이 전부 표본에서 빠진다.
        List<FinancialFigures> list = new ArrayList<>();
        LocalDate end = START.minusMonths(6);
        for (int i = 0; i < 8; i++) {
            list.add(shape.apply(FinancialFiguresFixture.quarter(end.minusMonths(3L * i))).build());
        }
        return list;
    }

    private static PriceBar bar(LocalDate date, BigDecimal close) {
        return new PriceBar(date, close, close, close, close, close, 1000L, null, null);
    }
}
