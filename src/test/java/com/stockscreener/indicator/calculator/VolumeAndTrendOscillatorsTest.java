package com.stockscreener.indicator.calculator;

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
 * 거래량·추세강도 오실레이터.
 *
 * <p>둘 다 <b>바꿔도 그럴듯한</b> 실수를 품는다 — MFI는 전형가격과 거래량 중 한쪽만 보정하는
 * 것, ADX는 와일더 평활을 단순이동평균으로 바꾸는 것. 어느 쪽도 값이 0~100을 벗어나지 않아
 * 화면으로는 드러나지 않는다.
 */
class VolumeAndTrendOscillatorsTest {

    private static final LocalDate START = LocalDate.of(2024, 1, 2);

    @Nested
    @DisplayName("자금흐름지수(14)")
    class Mfi {

        private final Mfi14Calculator calculator = new Mfi14Calculator();

        @Test
        @DisplayName("내린 날이 없으면 100이다 — 행을 빼면 가장 강한 종목이 사라진다")
        void allUpDaysYieldHundred() {
            List<PriceBar> bars = new ArrayList<>();
            for (int i = 0; i < 15; i++) {
                bars.add(bar(START.plusDays(i), 100 + i, 1000L));
            }

            List<IndicatorPoint> points = calculator.calculate(contextOf(bars));

            assertThat(points).hasSize(1);
            assertThat(points.get(0).value()).isEqualByComparingTo("100");
        }

        @Test
        @DisplayName("오른 날과 내린 날의 자금이 같으면 50 부근이다")
        void balancedFlowSitsNearFifty() {
            // 하루 오르고 하루 내리기를 반복하되 거래량을 같게 둔다.
            List<PriceBar> bars = new ArrayList<>();
            for (int i = 0; i < 15; i++) {
                bars.add(bar(START.plusDays(i), i % 2 == 0 ? 100 : 102, 1000L));
            }

            double mfi = calculator.calculate(contextOf(bars)).get(0).value().doubleValue();

            assertThat(mfi).isBetween(45.0, 55.0);
        }

        @Test
        @DisplayName("거래량이 큰 쪽으로 기운다 — 가격만 보는 RSI와 갈리는 자리다")
        void volumeTiltsTheBalance() {
            // 오른 날에만 거래량이 열 배. 같은 가격 궤적인데 값이 위로 쏠려야 한다.
            List<PriceBar> heavyUp = new ArrayList<>();
            List<PriceBar> flat = new ArrayList<>();
            for (int i = 0; i < 15; i++) {
                boolean up = i % 2 == 0;
                double price = up ? 102 : 100;
                heavyUp.add(bar(START.plusDays(i), price, up ? 10_000L : 1_000L));
                flat.add(bar(START.plusDays(i), price, 1_000L));
            }

            double tilted = calculator.calculate(contextOf(heavyUp)).get(0).value().doubleValue();
            double even = calculator.calculate(contextOf(flat)).get(0).value().doubleValue();

            assertThat(tilted).isGreaterThan(even);
        }

        @Test
        @DisplayName("전형가격과 거래량을 함께 보정한다 — 한쪽만 하면 곱이 불변이던 성질이 깨진다")
        void adjustsBothPriceAndVolume() {
            // 수정 기준으로는 완전한 횡보(adjClose 100 고정)인데 원주가만 중간에 꺾인다.
            // 전형가격이 평평하므로 오른 날도 내린 날도 없어 값이 나오면 안 된다.
            List<PriceBar> bars = new ArrayList<>();
            for (int i = 0; i < 15; i++) {
                BigDecimal raw = i < 7 ? bd(500) : bd(100);
                bars.add(new PriceBar(START.plusDays(i), raw, raw, raw, raw, bd(100),
                        i < 7 ? 200L : 1000L, null, null));
            }

            assertThat(calculator.calculate(contextOf(bars)))
                    .as("값이 나오면 축척이 섞인 것이다")
                    .isEmpty();
        }

        @Test
        @DisplayName("봉이 15개 미만이면 값이 없다")
        void needsFifteenBars() {
            List<PriceBar> bars = new ArrayList<>();
            for (int i = 0; i < 14; i++) {
                bars.add(bar(START.plusDays(i), 100 + i, 1000L));
            }

            assertThat(calculator.calculate(contextOf(bars))).isEmpty();
        }
    }

    @Nested
    @DisplayName("추세 강도(ADX)")
    class Adx {

        private final Adx14Calculator calculator = new Adx14Calculator();

        @Test
        @DisplayName("첫 값이 28번째 봉에서 선다 — 평활이 두 단계다")
        void firstValueAppearsOnTheTwentyEighthBar() {
            assertThat(calculator.calculate(contextOf(trending(27)))).isEmpty();

            List<IndicatorPoint> points = calculator.calculate(contextOf(trending(28)));
            assertThat(points).hasSize(1);
            assertThat(points.get(0).tradeDate()).isEqualTo(START.plusDays(27));
        }

        @Test
        @DisplayName("한 방향으로만 가면 강도가 높고, 오르내리면 낮다")
        void trendScoresHigherThanChop() {
            double trend = last(calculator.calculate(contextOf(trending(60))));

            // 같은 폭으로 하루 오르고 하루 내린다 — 방향이 상쇄된다.
            List<PriceBar> chop = new ArrayList<>();
            for (int i = 0; i < 60; i++) {
                double base = i % 2 == 0 ? 100 : 104;
                chop.add(range(START.plusDays(i), base + 2, base - 2, base));
            }
            double sideways = last(calculator.calculate(contextOf(chop)));

            assertThat(trend).isGreaterThan(25.0);
            assertThat(sideways).isLessThan(trend);
        }

        @Test
        @DisplayName("방향을 말하지 않는다 — 하락 추세도 강도가 높다")
        void fallingTrendScoresHighToo() {
            List<PriceBar> falling = new ArrayList<>();
            for (int i = 0; i < 60; i++) {
                double base = 300 - i * 2;
                falling.add(range(START.plusDays(i), base + 1, base - 1, base));
            }

            assertThat(last(calculator.calculate(contextOf(falling)))).isGreaterThan(25.0);
        }

        @Test
        @DisplayName("와일더 평활이 식 그대로다 — SMA로 바꾸면 여기서 깨진다")
        void wilderSmoothingFollowsTheFormula() {
            // (100 × 13 + 30) / 14 = 95. 단순이동평균이면 다른 값이 나온다.
            // ADX 출력만 보면 평활을 바꿔도 값이 0~100 안이라 그럴듯하다 — 식을 직접 못박는다.
            assertThat(Adx14Calculator.wilder(bd(100), bd(30)))
                    .isEqualByComparingTo("95");
            assertThat(Adx14Calculator.wilder(bd(0), bd(14)))
                    .isEqualByComparingTo("1");
        }

        @Test
        @DisplayName("완전한 단조 추세는 100이다 — DM·TR·DI·DX가 전부 맞아야 나온다")
        void perfectTrendScoresOneHundred() {
            // 매일 같은 폭으로 오르고 고저가 종가를 대칭으로 감싸면 −DM이 한 번도 안 생긴다.
            // 그러면 −DI = 0이고 DX = 100이 계속되어 어느 평활을 써도 ADX가 100으로 수렴한다.
            // 중간 단계가 하나라도 틀리면 100이 안 나온다.
            assertThat(last(calculator.calculate(contextOf(trending(60)))))
                    .isEqualTo(100.0);
        }

        @Test
        @DisplayName("값이 언제나 0~100 안에 있다")
        void staysWithinBounds() {
            List<PriceBar> bars = new ArrayList<>(trending(40));
            for (int i = 40; i < 80; i++) {
                double base = 180 - (i - 40) * 2;
                bars.add(range(START.plusDays(i), base + 1, base - 1, base));
            }

            assertThat(calculator.calculate(contextOf(bars))).allSatisfy(point ->
                    assertThat(point.value().doubleValue()).isBetween(0.0, 100.0));
        }
    }

    // ── 픽스처 ────────────────────────────────────────────────────────────

    /** 매일 2씩 오르는 봉. 고가·저가가 종가를 감싼다. */
    private static List<PriceBar> trending(int count) {
        List<PriceBar> bars = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            double base = 100 + i * 2;
            bars.add(range(START.plusDays(i), base + 1, base - 1, base));
        }
        return bars;
    }

    private static PriceBar range(LocalDate date, double high, double low, double close) {
        return new PriceBar(date, bd(close), bd(high), bd(low), bd(close), bd(close),
                1000L, null, null);
    }

    private static PriceBar bar(LocalDate date, double price, long volume) {
        BigDecimal p = bd(price);
        return new PriceBar(date, p, p, p, p, p, volume, null, null);
    }

    private static double last(List<IndicatorPoint> points) {
        assertThat(points).isNotEmpty();
        return points.get(points.size() - 1).value().doubleValue();
    }

    private static BigDecimal bd(double value) {
        return BigDecimal.valueOf(value);
    }

    private static IndicatorContext contextOf(List<PriceBar> bars) {
        return new IndicatorContext(1L, bars, List.of(), List.of(), Map.of());
    }
}
