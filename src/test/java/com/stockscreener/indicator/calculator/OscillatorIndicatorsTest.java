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
import java.util.NavigableMap;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 오실레이터 구획의 신규 지표들.
 *
 * <p>{@code SwingIndicatorsTest}와 같은 이유로 한 파일에 모았다 — 셋이 <b>같은 종류의 실수</b>를
 * 공유한다. 원주가와 수정주가를 섞는 것, 정의되지 않은 자리를 0으로 메우는 것, 창에 구멍이
 * 있는데 평균을 내는 것. 전부 값이 그럴듯해서 화면으로는 드러나지 않는다.
 */
class OscillatorIndicatorsTest {

    private static final LocalDate START = LocalDate.of(2024, 1, 2);

    @Nested
    @DisplayName("스토캐스틱 %K")
    class StochasticK {

        private final StochasticK14Calculator calculator = new StochasticK14Calculator();

        @Test
        @DisplayName("고저 범위 안의 위치를 낸다 — 범위 꼭대기에서 마감하면 100이다")
        void topOfRangeIsHundred() {
            // 16봉 전부 고가 200 / 저가 100 / 종가 200. 어느 창에서도 종가가 고가와 같다.
            List<PriceBar> bars = new ArrayList<>();
            for (int i = 0; i < 16; i++) {
                bars.add(bar(START.plusDays(i), 200, 100, 200));
            }

            List<IndicatorPoint> points = calculator.calculate(contextOf(bars));

            // 14 + 3 − 1 = 16봉이 있어야 첫 값이 나온다.
            assertThat(points).hasSize(1);
            assertThat(points.get(0).value()).isEqualByComparingTo("100");
        }

        @Test
        @DisplayName("원주가가 아니라 수정주가로 잰다 — 분할이 범위를 통째로 어긋나게 한다")
        void usesAdjustedHighAndLow() {
            // 분할 전 구간: 원주가 고가 1000·저가 500·종가 1000, 수정계수 0.5(adjClose 500).
            // 보정하면 고가 500·저가 250·종가 500이라 여전히 범위의 꼭대기다.
            // 원주가 고가·저가에 수정 종가를 섞으면 (500−500)/(1000−500) = 0이 나온다.
            List<PriceBar> bars = new ArrayList<>();
            for (int i = 0; i < 16; i++) {
                bars.add(new PriceBar(START.plusDays(i),
                        bd(1000), bd(1000), bd(500), bd(1000), bd(500), 10L, null, null));
            }

            List<IndicatorPoint> points = calculator.calculate(contextOf(bars));

            assertThat(points).hasSize(1);
            assertThat(points.get(0).value())
                    .as("0이 나오면 원주가 고저에 수정 종가를 섞은 것이다")
                    .isEqualByComparingTo("100");
        }

        @Test
        @DisplayName("고가와 저가가 같은 창은 값을 내지 않는다 — 위치가 정의되지 않는다")
        void skipsWhenRangeIsZero() {
            // 장기 거래정지처럼 14일 내내 한 값이면 분모가 0이다. 0이나 50을 지어내면
            // 과열·침체 판정이 통째로 뒤집힌다.
            List<PriceBar> bars = new ArrayList<>();
            for (int i = 0; i < 16; i++) {
                bars.add(bar(START.plusDays(i), 100, 100, 100));
            }

            assertThat(calculator.calculate(contextOf(bars))).isEmpty();
        }

        @Test
        @DisplayName("봉이 모자라면 아무 값도 내지 않는다")
        void needsFullWarmup() {
            List<PriceBar> bars = new ArrayList<>();
            for (int i = 0; i < 15; i++) {
                bars.add(bar(START.plusDays(i), 200, 100, 150));
            }

            assertThat(calculator.calculate(contextOf(bars))).isEmpty();
        }
    }

    @Nested
    @DisplayName("스토캐스틱 %D")
    class StochasticD {

        private final StochasticD14Calculator calculator = new StochasticD14Calculator();

        @Test
        @DisplayName("%K의 3일 평균이다")
        void averagesThreeDaysOfK() {
            NavigableMap<LocalDate, BigDecimal> k = new TreeMap<>();
            k.put(START, bd(30));
            k.put(START.plusDays(1), bd(60));
            k.put(START.plusDays(2), bd(90));
            k.put(START.plusDays(3), bd(120));

            List<IndicatorPoint> points = calculator.calculate(withK(k));

            assertThat(points).hasSize(2);
            assertThat(points.get(0).value()).isEqualByComparingTo("60");   // (30+60+90)/3
            assertThat(points.get(1).value()).isEqualByComparingTo("90");   // (60+90+120)/3
        }

        @Test
        @DisplayName("%K가 셋 미만이면 값이 없다")
        void needsThreePoints() {
            NavigableMap<LocalDate, BigDecimal> k = new TreeMap<>();
            k.put(START, bd(30));
            k.put(START.plusDays(1), bd(60));

            assertThat(calculator.calculate(withK(k))).isEmpty();
        }
    }

    @Nested
    @DisplayName("OBV 추세 강도")
    class ObvT {

        private final ObvT13Calculator calculator = new ObvT13Calculator();

        @Test
        @DisplayName("계속 오르면 매집이 통계적으로 뚜렷하다")
        void risingCloseYieldsStrongPositiveT() {
            // 매일 오르므로 OBV가 거래량만큼 계속 쌓인다. 완전 직선이면 t가 0이 되므로
            // 거래량을 조금씩 흔들어 잔차를 남긴다.
            List<PriceBar> bars = new ArrayList<>();
            for (int i = 0; i < 20; i++) {
                long volume = 1000L + (i % 3) * 10L;
                bars.add(new PriceBar(START.plusDays(i), bd(100 + i), bd(100 + i), bd(100 + i),
                        bd(100 + i), bd(100 + i), volume, null, null));
            }

            List<IndicatorPoint> points = calculator.calculate(contextOf(bars));

            assertThat(points).isNotEmpty();
            assertThat(points.get(points.size() - 1).value().doubleValue()).isGreaterThan(1.96);
        }

        @Test
        @DisplayName("계속 내리면 분산이 뚜렷하다 — 부호가 뒤집힌다")
        void fallingCloseYieldsStrongNegativeT() {
            List<PriceBar> bars = new ArrayList<>();
            for (int i = 0; i < 20; i++) {
                long volume = 1000L + (i % 3) * 10L;
                bars.add(new PriceBar(START.plusDays(i), bd(200 - i), bd(200 - i), bd(200 - i),
                        bd(200 - i), bd(200 - i), volume, null, null));
            }

            List<IndicatorPoint> points = calculator.calculate(contextOf(bars));

            assertThat(points.get(points.size() - 1).value().doubleValue()).isLessThan(-1.96);
        }

        @Test
        @DisplayName("거래량을 보정해 쓴다 — 분할을 대량 매집으로 읽지 않는다")
        void usesAdjustedVolume() {
            // 종가는 수정 기준으로 완전한 횡보(adjClose 100 고정)인데 원주가만 분할로 꺾인다.
            // 보정하면 종가 변화가 0이라 OBV가 한 번도 움직이지 않고 t는 0이다.
            // 원거래량을 쓰면 분할일 전후로 거래량 규모가 달라져 값이 흔들린다.
            List<PriceBar> bars = new ArrayList<>();
            for (int i = 0; i < 20; i++) {
                boolean beforeSplit = i < 10;
                BigDecimal close = beforeSplit ? bd(500) : bd(100);
                bars.add(new PriceBar(START.plusDays(i), close, close, close, close,
                        bd(100), beforeSplit ? 200L : 1000L, null, null));
            }

            List<IndicatorPoint> points = calculator.calculate(contextOf(bars));

            assertThat(points).isNotEmpty();
            assertThat(points.get(points.size() - 1).value())
                    .as("수정 종가가 평평하면 OBV가 움직이지 않는다")
                    .isEqualByComparingTo("0");
        }

        @Test
        @DisplayName("봉이 14개 미만이면 값이 없다")
        void needsFourteenBars() {
            List<PriceBar> bars = new ArrayList<>();
            for (int i = 0; i < 13; i++) {
                bars.add(bar(START.plusDays(i), 100, 100, 100));
            }

            assertThat(calculator.calculate(contextOf(bars))).isEmpty();
        }
    }

    // ── 픽스처 ────────────────────────────────────────────────────────────

    /** 수정계수 1인 봉. 원주가와 수정주가가 같다. */
    private static PriceBar bar(LocalDate date, double high, double low, double close) {
        return new PriceBar(date, bd(close), bd(high), bd(low), bd(close), bd(close),
                1000L, null, null);
    }

    private static BigDecimal bd(double value) {
        return BigDecimal.valueOf(value);
    }

    private static IndicatorContext contextOf(List<PriceBar> bars) {
        return new IndicatorContext(1L, bars, List.of(), List.of(), Map.of());
    }

    private static IndicatorContext withK(NavigableMap<LocalDate, BigDecimal> k) {
        return new IndicatorContext(1L, List.of(), List.of(), List.of(),
                Map.of(StochasticK14Calculator.CODE, k));
    }
}
