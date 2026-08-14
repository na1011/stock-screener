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
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 베타가 <b>기울기</b>를 재는지 본다.
 *
 * <p>회귀는 짝이 어긋나도, 축이 뒤바뀌어도, 부분 주가 섞여도 값을 낸다. 그래서 「값이 나온다」는
 * 검사는 아무것도 재지 않는다. 여기서는 <b>답을 아는 입력</b>을 넣는다 — 지수를 그대로 따라가면
 * 1, 두 배로 움직이면 2, 반대로 움직이면 −1이다.
 */
class Beta104wCalculatorTest {

    private static final LocalDate START = LocalDate.of(2022, 1, 3);
    /** 104주를 채우고도 남는 길이. 월~금만 만든다. */
    private static final int WEEKS = 130;

    private final Beta104wCalculator calculator = new Beta104wCalculator();

    @Test
    @DisplayName("지수와 같은 비율로 움직이면 베타가 1이다")
    void movesWithTheIndex() {
        assertThat(lastBeta(1.0)).isEqualByComparingTo("1.000");
    }

    @Test
    @DisplayName("지수의 두 배로 움직이면 베타가 2다")
    void movesTwiceAsMuch() {
        assertThat(lastBeta(2.0)).isEqualByComparingTo("2.000");
    }

    @Test
    @DisplayName("지수와 반대로 움직이면 베타가 음수다")
    void movesAgainstTheIndex() {
        assertThat(lastBeta(-1.0)).isEqualByComparingTo("-1.000");
    }

    @Test
    @DisplayName("지수가 없으면 한 행도 내지 않는다")
    void emitsNothingWithoutTheIndex() {
        assertThat(calculator.calculate(
                new IndicatorContext(1L, bars(1.0), List.of(), List.of(), Map.of())))
                .isEmpty();
    }

    @Test
    @DisplayName("104주를 채우지 못하면 값을 내지 않는다")
    void emitsNothingBeforeTheWindowFills() {
        List<PriceBar> shortBars = bars(1.0).subList(0, 200);
        NavigableMap<LocalDate, BigDecimal> index = index(shortBars);

        assertThat(calculator.calculate(context(shortBars, index))).isEmpty();
    }

    // ── 픽스처 ────────────────────────────────────────────────────────────

    private static BigDecimal lastBeta(double beta) {
        List<PriceBar> bars = bars(beta);
        List<IndicatorPoint> points = calculator().calculate(context(bars, index(bars)));

        assertThat(points).isNotEmpty();
        return points.getLast().value();
    }

    private static Beta104wCalculator calculator() {
        return new Beta104wCalculator();
    }

    /**
     * 주마다 지수는 +1%씩 흔들리고 종목은 그것의 {@code beta}배로 움직인다.
     *
     * <p>주 안에서는 값을 바꾸지 않는다 — 주간 종가만 회귀에 들어가므로 주중 궤적은 결과에
     * 영향을 주지 않아야 하고, 영향을 준다면 그것이 결함이다.
     */
    private static List<PriceBar> bars(double beta) {
        List<PriceBar> bars = new ArrayList<>(WEEKS * 5);
        double price = 1_000;
        for (int w = 0; w < WEEKS; w++) {
            // 주마다 방향이 바뀌는 수익률. 분산이 0이 아니어야 기울기가 정의된다.
            double weeklyReturn = (w % 2 == 0 ? 1 : -1) * 0.01 * beta;
            if (w > 0) {
                price *= 1 + weeklyReturn;
            }
            LocalDate monday = START.plusWeeks(w);
            for (int d = 0; d < 5; d++) {
                BigDecimal close = BigDecimal.valueOf(price);
                bars.add(new PriceBar(monday.plusDays(d), close, close, close, close, close,
                        1_000L, null, null));
            }
        }
        return bars;
    }

    /** 같은 주에 +1%씩 흔들리는 지수. 종목과 같은 주 경계를 쓴다. */
    private static NavigableMap<LocalDate, BigDecimal> index(List<PriceBar> bars) {
        NavigableMap<LocalDate, BigDecimal> index = new TreeMap<>();
        double level = 2_500;
        long currentWeek = Long.MIN_VALUE;
        int w = -1;
        for (PriceBar bar : bars) {
            long key = WeeklyResampler.weekKey(bar.tradeDate());
            if (key != currentWeek) {
                currentWeek = key;
                w++;
                if (w > 0) {
                    level *= 1 + (w % 2 == 0 ? 1 : -1) * 0.01;
                }
            }
            index.put(bar.tradeDate(), BigDecimal.valueOf(level));
        }
        return index;
    }

    private static IndicatorContext context(List<PriceBar> bars,
                                            NavigableMap<LocalDate, BigDecimal> index) {
        return new IndicatorContext(1L, bars, List.of(), List.of(), Map.of())
                .withMarketIndex(index);
    }
}
