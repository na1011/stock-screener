package com.stockscreener.indicator.calculator;

import com.stockscreener.indicator.IndicatorContext;
import com.stockscreener.indicator.IndicatorPoint;
import com.stockscreener.indicator.PriceBar;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * MACD-V 계열 — 주봉판과 교차 파생 넷.
 *
 * <p>공통 함정은 <b>어긋나도 값이 정상 범위 안이라 안 드러난다</b>는 것이다. 평활계수를 바꿔
 * 써도, 주 하나를 두 번 세도, 부모의 구멍을 이어 붙여도 숫자는 그럴듯하게 나온다.
 * 그래서 여기서는 <b>독립 참조 구현과 값을 대조</b>한다 — 「값이 있다」로는 아무것도 못 잡는다.
 */
class MacdVFamilyTest {

    private static final LocalDate START = LocalDate.of(2020, 1, 6); // 월요일

    @Nested
    @DisplayName("평활계수 — 둘을 바꿔 쓰면 조용히 틀린다")
    class Smoothing {

        @Test
        @DisplayName("Wilder RMA(α=1/P)와 EMA(α=2/(P+1))는 다른 값을 낸다")
        void wilderIsNotEma() {
            BigDecimal previous = bd(10);
            BigDecimal input = bd(20);

            BigDecimal rma = Atr14Calculator.rmaStep(previous, input, 14);
            BigDecimal ema = MovingAverages.emaStep(previous, input, 14);

            // (10×13 + 20) ÷ 14 = 10.71428571
            assertThat(rma).isEqualByComparingTo("10.71428571");
            assertThat(ema)
                    .as("바꿔 써도 값이 정상 범위 안이라 드러나지 않는다 — 그래서 여기서 못박는다")
                    .isNotEqualByComparingTo(rma);
        }

        @Test
        @DisplayName("emaStep을 이어 붙인 것이 ema와 같다 — 추출이 값을 안 바꿨다")
        void emaStepMatchesEma() {
            List<BigDecimal> values = new ArrayList<>();
            for (int i = 0; i < 30; i++) {
                values.add(bd(100 + (i % 7) * 3));
            }
            List<BigDecimal> whole = MovingAverages.ema(values, 9);

            BigDecimal seed = BigDecimal.ZERO;
            for (int i = 0; i < 9; i++) {
                seed = seed.add(values.get(i));
            }
            BigDecimal stepped = seed.divide(bd(9), MovingAverages.SCALE, RoundingMode.HALF_UP);
            for (int i = 9; i < values.size(); i++) {
                stepped = MovingAverages.emaStep(stepped, values.get(i), 9);
            }
            assertThat(stepped).isEqualByComparingTo(whole.get(whole.size() - 1));
        }
    }

    @Nested
    @DisplayName("실체범위 — 첫 봉에는 직전 종가가 없다")
    class TrueRange {

        @Test
        @DisplayName("직전 종가가 없으면 고가−저가다 — 0을 넣으면 시드가 통째로 부푼다")
        void seedsWithRangeWhenNoPreviousClose() {
            assertThat(Atr14Calculator.trueRange(bd(110), bd(90), null))
                    .isEqualByComparingTo("20");
            assertThat(Atr14Calculator.trueRange(bd(110), bd(90), BigDecimal.ZERO))
                    .as("0을 직전 종가로 넣으면 갭이 주가만큼 벌어진 것으로 계산된다")
                    .isEqualByComparingTo("110");
        }

        @Test
        @DisplayName("갭이 크면 고저 폭이 아니라 갭이 실체범위다")
        void takesTheGapWhenLarger() {
            assertThat(Atr14Calculator.trueRange(bd(110), bd(100), bd(80)))
                    .isEqualByComparingTo("30");
        }
    }

    @Nested
    @DisplayName("주봉 MACD-V")
    class Weekly {

        private final WeeklyMacdVSeries series = new WeeklyMacdVSeries();

        @Test
        @DisplayName("주 마지막 거래일 값이 독립 참조와 같다 — 오프셋·ATR 시드·주 이중 반영을 함께 잡는다")
        void matchesIndependentReferenceAtWeekEnd() {
            List<PriceBar> bars = weekdayBars(210);
            NavigableMap<LocalDate, WeeklyMacdVSeries.Point> points = series.of(contextOf(bars));
            assertThat(points).isNotEmpty();

            List<WeeklyBar> weeks = WeeklyResampler.resample(bars);
            int lastWeek = weeks.size() - 1;
            LocalDate weekEnd = weeks.get(lastWeek).endDate();

            assertThat(points).containsKey(weekEnd);
            assertThat(points.get(weekEnd).value())
                    .as("어긋나면 12·26·9의 오프셋 중 하나가 밀렸거나 그 주가 두 번 들어간 것이다")
                    .isEqualByComparingTo(referenceMacdV(weeks, lastWeek));
        }

        @Test
        @DisplayName("진행 중인 주를 포함한다 — 같은 주의 화요일과 수요일 값이 다르다")
        void includesTheRunningWeek() {
            List<PriceBar> bars = weekdayBars(210);
            // 마지막 주를 수요일까지로 자른다.
            List<PriceBar> trimmed = new ArrayList<>();
            for (PriceBar bar : bars) {
                trimmed.add(bar);
                if (bar.tradeDate().getDayOfWeek() == DayOfWeek.WEDNESDAY
                        && bar.tradeDate().equals(bars.get(bars.size() - 3).tradeDate())) {
                    break;
                }
            }
            NavigableMap<LocalDate, WeeklyMacdVSeries.Point> points = series.of(contextOf(trimmed));

            LocalDate wednesday = trimmed.get(trimmed.size() - 1).tradeDate();
            LocalDate tuesday = trimmed.get(trimmed.size() - 2).tradeDate();
            assertThat(WeeklyResampler.weekKey(tuesday))
                    .as("두 날짜가 같은 주여야 이 테스트가 뜻을 갖는다")
                    .isEqualTo(WeeklyResampler.weekKey(wednesday));

            assertThat(points.get(wednesday).value())
                    .as("완결 주만 쓰면 같은 주 안에서 값이 안 바뀐다")
                    .isNotEqualByComparingTo(points.get(tuesday).value());
        }

        @Test
        @DisplayName("완결 주 200주를 못 채우면 한 행도 없다 — 시드에 끌린 값을 내지 않는다")
        void staysSilentBeforeTheGate() {
            assertThat(series.of(contextOf(weekdayBars(WeeklyMacdVSeries.MIN_COMPLETED_WEEKS))))
                    .isEmpty();
        }

        @Test
        @DisplayName("히스토그램이 본선에서 시그널선을 뺀 값이다")
        void histIsValueMinusSignal() {
            List<PriceBar> bars = weekdayBars(210);
            NavigableMap<LocalDate, WeeklyMacdVSeries.Point> points = series.of(contextOf(bars));
            WeeklyMacdVSeries.Point last = points.lastEntry().getValue();

            List<WeeklyBar> weeks = WeeklyResampler.resample(bars);
            BigDecimal signal = referenceSignal(weeks, weeks.size() - 1);
            assertThat(last.hist()).isEqualByComparingTo(last.value().subtract(signal));
        }
    }

    @Nested
    @DisplayName("일봉 히스토그램")
    class DailyHist {

        private final MacdVHistCalculator calculator = new MacdVHistCalculator();

        @Test
        @DisplayName("부모가 9점보다 적으면 한 행도 없다")
        void staysSilentDuringWarmup() {
            assertThat(calculator.calculate(dailyContext(ramp(8)))).isEmpty();
            assertThat(calculator.calculate(dailyContext(ramp(9)))).hasSize(1);
        }

        @Test
        @DisplayName("본선이 시그널선을 위로 지나면 부호가 뒤집힌다")
        void signFlipsOnTheCross() {
            // 내리다가 오른다 — 히스토그램이 음수에서 양수로 넘어가야 한다.
            List<BigDecimal> values = new ArrayList<>();
            for (int i = 0; i < 20; i++) {
                values.add(bd(100 - i * 5));
            }
            for (int i = 0; i < 20; i++) {
                values.add(bd(5 + i * 8));
            }
            List<IndicatorPoint> points = calculator.calculate(dailyContext(values));

            assertThat(points.get(5).value().signum()).isNegative();
            assertThat(points.get(points.size() - 1).value().signum()).isPositive();
        }

        @Test
        @DisplayName("부모에 구멍이 나면 그 자리에서 다시 시드한다 — 이어 붙이면 구멍이 뒤에 남는다")
        void reseedsAfterAGapInTheParent() {
            List<PriceBar> bars = weekdayBars(20);
            NavigableMap<LocalDate, BigDecimal> parent = new TreeMap<>();
            for (int i = 0; i < bars.size(); i++) {
                // 20~29번 거래일을 억제된 것으로 본다(부모에 그 날짜가 없다).
                if (i >= 20 && i < 30) {
                    continue;
                }
                parent.put(bars.get(i).tradeDate(), bd(100 + i));
            }
            IndicatorContext context = new IndicatorContext(1L, bars, List.of(), List.of(),
                    Map.of(MacdVCalculator.CODE, parent));

            List<IndicatorPoint> points = calculator.calculate(context);
            LocalDate gapEnd = bars.get(30).tradeDate();

            long afterGap = points.stream().filter(p -> !p.tradeDate().isBefore(gapEnd)).count();
            long expected = bars.size() - 30 - (MacdVHistCalculator.SIGNAL_PERIOD - 1);
            assertThat(afterGap)
                    .as("구멍 뒤에도 9점이 다시 차야 값이 난다 — 이어 붙이면 %d행이 더 나온다",
                            MacdVHistCalculator.SIGNAL_PERIOD - 1)
                    .isEqualTo(expected);
        }
    }

    @Nested
    @DisplayName("일봉 히스토그램 변화량")
    class DailyChange {

        private final MacdVHistChange5Calculator calculator = new MacdVHistChange5Calculator();

        @Test
        @DisplayName("5칸 전이 아니라 5거래일 전과 견준다 — 사이가 비면 행을 쓰지 않는다")
        void looksBackByTradingDayNotByIndex() {
            List<PriceBar> bars = weekdayBars(10);
            NavigableMap<LocalDate, BigDecimal> parent = new TreeMap<>();
            for (int i = 0; i < bars.size(); i++) {
                if (i >= 10 && i < 14) {
                    continue; // 억제 구멍
                }
                parent.put(bars.get(i).tradeDate(), bd(i));
            }
            IndicatorContext context = new IndicatorContext(1L, bars, List.of(), List.of(),
                    Map.of(MacdVHistCalculator.CODE, parent));

            List<IndicatorPoint> points = calculator.calculate(context);

            // 구멍이 10~13이므로 5거래일 전이 그 안에 드는 것은 15~18번 거래일이다.
            // 14번은 5거래일 전이 9번이라 살아 있다 — 그 하나가 「5칸 전」과 「5거래일 전」을 가른다.
            for (int i = 15; i < 19; i++) {
                LocalDate date = bars.get(i).tradeDate();
                assertThat(points).noneMatch(p -> p.tradeDate().equals(date));
            }
            // 값이 나는 자리는 언제나 정확히 5거래일 차이다.
            assertThat(points).allSatisfy(point ->
                    assertThat(point.value()).isEqualByComparingTo("5"));
        }
    }

    // --- 참조 구현: 계산기와 독립적으로 완결 주 전체를 한 번에 접는다 ---

    private static BigDecimal referenceMacdV(List<WeeklyBar> weeks, int week) {
        BigDecimal fast = referenceEma(weeks, WeeklyMacdVSeries.FAST, week);
        BigDecimal slow = referenceEma(weeks, WeeklyMacdVSeries.SLOW, week);
        return fast.subtract(slow)
                .divide(referenceAtr(weeks, week), MovingAverages.MC)
                .multiply(bd(100))
                .setScale(MovingAverages.SCALE, RoundingMode.HALF_UP);
    }

    private static BigDecimal referenceSignal(List<WeeklyBar> weeks, int week) {
        List<BigDecimal> values = new ArrayList<>();
        for (int w = WeeklyMacdVSeries.SLOW - 1; w <= week; w++) {
            values.add(referenceMacdV(weeks, w));
        }
        List<BigDecimal> ema = MovingAverages.ema(values, WeeklyMacdVSeries.SIGNAL);
        return ema.get(ema.size() - 1);
    }

    private static BigDecimal referenceEma(List<WeeklyBar> weeks, int period, int week) {
        List<BigDecimal> closes = new ArrayList<>();
        for (int w = 0; w <= week; w++) {
            closes.add(BigDecimal.valueOf(weeks.get(w).close()));
        }
        List<BigDecimal> ema = MovingAverages.ema(closes, period);
        return ema.get(ema.size() - 1);
    }

    private static BigDecimal referenceAtr(List<WeeklyBar> weeks, int week) {
        int period = WeeklyMacdVSeries.ATR_PERIOD;
        BigDecimal sum = BigDecimal.ZERO;
        for (int w = 0; w < period; w++) {
            BigDecimal previousClose =
                    (w == 0) ? null : BigDecimal.valueOf(weeks.get(w - 1).close());
            sum = sum.add(Atr14Calculator.trueRange(BigDecimal.valueOf(weeks.get(w).high()),
                    BigDecimal.valueOf(weeks.get(w).low()), previousClose));
        }
        BigDecimal atr = sum.divide(bd(period), MovingAverages.SCALE, RoundingMode.HALF_UP);
        for (int w = period; w <= week; w++) {
            BigDecimal trueRange = Atr14Calculator.trueRange(
                    BigDecimal.valueOf(weeks.get(w).high()),
                    BigDecimal.valueOf(weeks.get(w).low()),
                    BigDecimal.valueOf(weeks.get(w - 1).close()));
            atr = Atr14Calculator.rmaStep(atr, trueRange, period);
        }
        return atr;
    }

    // --- 픽스처 ---

    /** 평일만 담은 봉. 주말을 건너뛰어야 ISO 주 경계가 실제 시세와 같은 모양이 된다. */
    private static List<PriceBar> weekdayBars(int weeks) {
        List<PriceBar> bars = new ArrayList<>(weeks * 5);
        LocalDate date = START;
        int i = 0;
        while (bars.size() < weeks * 5) {
            if (date.getDayOfWeek() != DayOfWeek.SATURDAY
                    && date.getDayOfWeek() != DayOfWeek.SUNDAY) {
                // 값이 단조증가하면 ATR이 0에 수렴해 나눗셈이 무의미해진다. 흔들어 준다.
                double close = 1000 + Math.sin(i / 6.0) * 120 + i * 0.4;
                bars.add(new PriceBar(date, bd(close), bd(close + 8), bd(close - 8),
                        bd(close), bd(close), 1000L, null, null));
                i++;
            }
            date = date.plusDays(1);
        }
        return bars;
    }

    private static List<BigDecimal> ramp(int size) {
        List<BigDecimal> values = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            values.add(bd(100 + i));
        }
        return values;
    }

    private static IndicatorContext dailyContext(List<BigDecimal> parentValues) {
        List<PriceBar> bars = weekdayBars(parentValues.size() / 5 + 2);
        NavigableMap<LocalDate, BigDecimal> parent = new TreeMap<>();
        for (int i = 0; i < parentValues.size(); i++) {
            parent.put(bars.get(i).tradeDate(), parentValues.get(i));
        }
        return new IndicatorContext(1L, bars, List.of(), List.of(),
                Map.of(MacdVCalculator.CODE, parent));
    }

    private static IndicatorContext contextOf(List<PriceBar> bars) {
        return new IndicatorContext(1L, bars, List.of(), List.of(), Map.of());
    }

    private static BigDecimal bd(double value) {
        return BigDecimal.valueOf(value);
    }
}
