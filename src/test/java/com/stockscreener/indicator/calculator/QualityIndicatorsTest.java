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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 프리셋 개편이 더한 지표 넷의 계약. 재는 것은 <b>부호와 기저</b>다.
 *
 * <p>넷 다 값의 크기보다 어느 쪽이 좋은 쪽인지가 조건의 뜻을 정한다. 부호가 뒤집히면
 * 정반대 종목이 상위로 오는데 점수도 결과도 그럴듯하다.
 */
class QualityIndicatorsTest {

    /**
     * 재무를 쓰는 픽스처의 봉 시작일. <b>그 재무가 공시된 뒤</b>여야 한다(ADR-0128).
     *
     * <p>지표가 봉마다 「그 시점에 공시돼 있던」 재무로 계산하므로 봉이 공시일보다 앞이면
     * 값을 내지 않는 것이 옳다. {@code FinancialFiguresFixture}가 공시일을
     * {@code periodEnd + 2개월}로 둔다. 예전에는 스냅샷 하나를 창 전체에 펴서
     * <b>봉이 몇 해 앞이어도 값이 나왔다.</b>
     */
    private static final LocalDate START = LocalDate.of(2026, 3, 2);

    @Nested
    @DisplayName("ROA")
    class Roa {

        private final RoaCalculator calculator = new RoaCalculator();

        @Test
        @DisplayName("순이익 TTM을 총자산으로 나눈다 — 총자산은 잔액이라 합산하지 않는다")
        void dividesTrailingIncomeByTheLatestAssetBalance() {
            // 분기마다 25, 총자산 1,000 → TTM 100 / 1,000 = 10%
            List<FinancialFigures> quarters = quarters(q -> q
                    .netIncome(BigDecimal.valueOf(25))
                    .totalAssets(BigDecimal.valueOf(1_000)));

            List<IndicatorPoint> points = calculator.calculate(context(quarters));

            assertThat(points).isNotEmpty();
            // 총자산을 4분기 합산하면 2.5%가 나온다. 그것이 이 테스트가 막는 실패다.
            assertThat(points.getLast().value()).isEqualByComparingTo("10.00000000");
        }

        @Test
        @DisplayName("적자면 음수다 — 부호를 거르지 않는다")
        void keepsTheSignForLosses() {
            List<FinancialFigures> quarters = quarters(q -> q
                    .netIncome(BigDecimal.valueOf(-25))
                    .totalAssets(BigDecimal.valueOf(1_000)));

            assertThat(calculator.calculate(context(quarters)).getLast().value())
                    .isEqualByComparingTo("-10.00000000");
        }

        @Test
        @DisplayName("총자산이 0 이하면 값을 내지 않는다")
        void emitsNothingWithoutPositiveAssets() {
            List<FinancialFigures> quarters = quarters(q -> q
                    .netIncome(BigDecimal.valueOf(25))
                    .totalAssets(BigDecimal.ZERO));

            assertThat(calculator.calculate(context(quarters))).isEmpty();
        }
    }

    @Nested
    @DisplayName("발생액 비율")
    class AccrualRatio {

        private final AccrualRatioCalculator calculator = new AccrualRatioCalculator();

        @Test
        @DisplayName("현금이 이익보다 많으면 음수다 — 낮은 쪽이 이익의 질이 좋은 쪽이다")
        void isNegativeWhenCashExceedsEarnings() {
            // 순이익 100, 영업현금흐름 150, 총자산 1,000 → (100-150)/1,000 = -5%
            List<IndicatorPoint> points = calculator.calculate(annualContext(100, 150, 1_000));

            assertThat(points).isNotEmpty();
            assertThat(points.getLast().value()).isEqualByComparingTo("-5.00000000");
        }

        @Test
        @DisplayName("이익만 있고 현금이 안 따라오면 양수다")
        void isPositiveWhenEarningsOutrunCash() {
            assertThat(calculator.calculate(annualContext(100, 20, 1_000)).getLast().value())
                    .isEqualByComparingTo("8.00000000");
        }

        @Test
        @DisplayName("영업현금흐름이 없으면 값을 내지 않는다 — 순이익만으로 짐작하지 않는다")
        void emitsNothingWithoutOperatingCashFlow() {
            List<FinancialFigures> annual = List.of(
                    FinancialFiguresFixture.annual(LocalDate.of(2025, 12, 31))
                            .netIncome(BigDecimal.valueOf(100))
                            .totalAssets(BigDecimal.valueOf(1_000))
                            .build());

            assertThat(calculator.calculate(
                    new IndicatorContext(1L, bars(10), annual, List.of(), Map.of())))
                    .isEmpty();
        }
    }

    @Nested
    @DisplayName("RSI(2)")
    class Rsi2 {

        private final Rsi2Calculator calculator = new Rsi2Calculator();

        @Test
        @DisplayName("이틀 연속 오르면 100이다 — 짧은 기간에서는 극단이 정상이다")
        void reachesHundredOnConsecutiveGains() {
            assertThat(calculator.calculate(risingContext(20)).getLast().value())
                    .isEqualByComparingTo("100.00000000");
        }

        @Test
        @DisplayName("RSI(14)와 같은 공식을 쓴다 — 같은 기간이면 값이 같다")
        void sharesTheFormulaWithTheFourteenDayVersion() {
            // 공식이 갈라지지 않았음을 재는 유일한 방법은 같은 입력에 같은 기간을 주는 것이다.
            List<PriceBar> bars = zigzagBars(60);

            List<IndicatorPoint> shortWindow =
                    RelativeStrengthIndex.rsi(bars, 14);
            List<IndicatorPoint> fromCalculator =
                    new Rsi14Calculator().calculate(
                            new IndicatorContext(1L, bars, List.of(), List.of(), Map.of()));

            assertThat(fromCalculator).isEqualTo(shortWindow);
        }

        @Test
        @DisplayName("봉이 셋에 모자라면 값을 내지 않는다")
        void needsThreeBars() {
            assertThat(calculator.calculate(risingContext(2))).isEmpty();
        }
    }

    @Nested
    @DisplayName("150일 이동평균")
    class Sma150 {

        private final Sma150Calculator calculator = new Sma150Calculator();

        @Test
        @DisplayName("150봉이 차야 첫 값이 나온다")
        void needsAFullWindow() {
            assertThat(calculator.calculate(risingContext(149))).isEmpty();
            assertThat(calculator.calculate(risingContext(150))).hasSize(1);
        }

        @Test
        @DisplayName("값이 창 안 종가의 평균이다")
        void averagesTheWindow() {
            // 1..150의 평균은 75.5다.
            List<PriceBar> bars = new ArrayList<>(150);
            for (int i = 1; i <= 150; i++) {
                BigDecimal price = BigDecimal.valueOf(i);
                bars.add(new PriceBar(START.plusDays(i), price, price, price, price, price,
                        1_000L, null, null));
            }

            assertThat(calculator.calculate(
                    new IndicatorContext(1L, bars, List.of(), List.of(), Map.of()))
                    .getLast().value())
                    .isEqualByComparingTo("75.5");
        }
    }

    // ── 픽스처 ────────────────────────────────────────────────────────────

    private static List<FinancialFigures> quarters(
            java.util.function.UnaryOperator<FinancialFiguresFixture> customize) {
        List<FinancialFigures> quarters = new ArrayList<>(4);
        LocalDate end = LocalDate.of(2025, 12, 31);
        for (int i = 0; i < 4; i++) {
            quarters.add(customize
                    .apply(FinancialFiguresFixture.quarter(end.minusMonths(3L * i)))
                    .build());
        }
        return quarters;
    }

    private static IndicatorContext context(List<FinancialFigures> quarters) {
        return new IndicatorContext(1L, bars(10), List.of(), quarters, Map.of());
    }

    private static IndicatorContext annualContext(double netIncome, double operatingCashFlow,
                                                  double assets) {
        List<FinancialFigures> annual = List.of(
                FinancialFiguresFixture.annual(LocalDate.of(2025, 12, 31))
                        .netIncome(BigDecimal.valueOf(netIncome))
                        .operatingCashFlow(BigDecimal.valueOf(operatingCashFlow))
                        .totalAssets(BigDecimal.valueOf(assets))
                        .build());
        return new IndicatorContext(1L, bars(10), annual, List.of(), Map.of());
    }

    private static IndicatorContext risingContext(int count) {
        return new IndicatorContext(1L, bars(count), List.of(), List.of(), Map.of());
    }

    /** 매일 1씩 오르는 종가. */
    private static List<PriceBar> bars(int count) {
        List<PriceBar> bars = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            BigDecimal price = BigDecimal.valueOf(100 + i);
            bars.add(new PriceBar(START.plusDays(i), price, price, price, price, price,
                    1_000L, null, null));
        }
        return bars;
    }

    /** 오르내림이 번갈아 나오는 종가. 평활이 한쪽으로 쏠리지 않게 한다. */
    private static List<PriceBar> zigzagBars(int count) {
        List<PriceBar> bars = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            BigDecimal price = BigDecimal.valueOf(100 + (i % 7) * (i % 2 == 0 ? 1 : -1));
            bars.add(new PriceBar(START.plusDays(i), price, price, price, price, price,
                    1_000L, null, null));
        }
        return bars;
    }
}
