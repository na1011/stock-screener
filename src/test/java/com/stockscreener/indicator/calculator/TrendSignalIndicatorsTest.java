package com.stockscreener.indicator.calculator;

import com.stockscreener.indicator.IndicatorContext;
import com.stockscreener.indicator.IndicatorPoint;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 이동평균 여럿의 관계에서 나오는 지표들.
 *
 * <p>둘의 공통 함정은 <b>없는 선을 있는 것처럼 다루는 것</b>이다. 200일선이 아직 없는 종목에
 * 0을 넣으면 정배열은 늘 완전 정배열이 되고 골든크로스는 상장 첫날 일어난 것이 된다.
 * 값이 그럴듯해 화면으로는 드러나지 않는다.
 */
class TrendSignalIndicatorsTest {

    private static final LocalDate START = LocalDate.of(2024, 1, 2);

    @Nested
    @DisplayName("이평선 정배열")
    class MaAlign {

        private final MaAlignScoreCalculator calculator = new MaAlignScoreCalculator();

        @Test
        @DisplayName("짧은 것이 전부 위면 3, 전부 아래면 0이다")
        void countsAdjacentPairs() {
            // 20 > 60 > 120 > 200
            IndicatorContext aligned = ladder(140, 130, 120, 110);
            assertThat(value(calculator.calculate(aligned))).isEqualByComparingTo("3");

            // 완전 역배열
            IndicatorContext inverted = ladder(110, 120, 130, 140);
            assertThat(value(calculator.calculate(inverted))).isEqualByComparingTo("0");
        }

        @Test
        @DisplayName("중간이 어긋난 상태가 2로 구분된다 — 이진으로 접으면 사라지는 정보다")
        void partialAlignmentIsDistinct() {
            // 20 > 60 성립(140>130), 60 > 120 불성립(130<135), 120 > 200 성립(135>120) → 2
            assertThat(value(calculator.calculate(ladder(140, 130, 135, 120))))
                    .isEqualByComparingTo("2");
        }

        @Test
        @DisplayName("이웃 쌍만 센다 — 건너뛴 쌍까지 세면 같은 사실을 두 번 센다")
        void countsOnlyAdjacentPairs() {
            // 20 > 60 불성립(100<130), 60 > 120 성립(130>120), 120 > 200 성립(120>110) → 2.
            // 20 > 120·20 > 200·60 > 200까지 세면 상한이 3을 넘어 구간 라벨이 무너진다.
            assertThat(value(calculator.calculate(ladder(100, 130, 120, 110))))
                    .isEqualByComparingTo("2");
        }

        @Test
        @DisplayName("같으면 성립하지 않는다 — 부등호는 strict다")
        void equalMeansNotAligned() {
            assertThat(value(calculator.calculate(ladder(100, 100, 100, 100))))
                    .isEqualByComparingTo("0");
        }

        @Test
        @DisplayName("200일선이 없는 날짜는 값을 내지 않는다 — 0으로 메우면 늘 정배열이 된다")
        void skipsDatesWithoutTheLongestMa() {
            Map<String, NavigableMap<LocalDate, BigDecimal>> deps = Map.of(
                    Sma20Calculator.CODE, series(140),
                    Sma60Calculator.CODE, series(130),
                    Sma120Calculator.CODE, series(120),
                    Sma200Calculator.CODE, new TreeMap<>());

            assertThat(calculator.calculate(context(deps))).isEmpty();
        }
    }

    @Nested
    @DisplayName("골든크로스 경과일")
    class GoldenCross {

        private final GoldenCrossAgeCalculator calculator = new GoldenCrossAgeCalculator();

        @Test
        @DisplayName("통과한 날이 0이고 하루마다 하나씩 는다")
        void countsTradingDaysSinceTheCross() {
            // 60일선이 사흘째에 200일선을 넘어선다.
            NavigableMap<LocalDate, BigDecimal> shortMa = new TreeMap<>();
            NavigableMap<LocalDate, BigDecimal> longMa = new TreeMap<>();
            double[] shortValues = {90, 95, 105, 110, 115};
            for (int i = 0; i < shortValues.length; i++) {
                shortMa.put(START.plusDays(i), bd(shortValues[i]));
                longMa.put(START.plusDays(i), bd(100));
            }

            List<IndicatorPoint> points = calculator.calculate(withMas(shortMa, longMa));

            // 인덱스 0은 이전 봉이 없어 판정 대상이 아니고, 1은 아직 교차 전이라 값이 없다.
            assertThat(points).hasSize(3);
            assertThat(points.get(0).value()).isEqualByComparingTo("0");   // 교차 당일
            assertThat(points.get(0).tradeDate()).isEqualTo(START.plusDays(2));
            assertThat(points.get(1).value()).isEqualByComparingTo("1");
            assertThat(points.get(2).value()).isEqualByComparingTo("2");
        }

        @Test
        @DisplayName("아래로 지난 것은 교차가 아니다 — 데드크로스 뒤에는 값이 없다")
        void deadCrossIsNotACross() {
            NavigableMap<LocalDate, BigDecimal> shortMa = new TreeMap<>();
            NavigableMap<LocalDate, BigDecimal> longMa = new TreeMap<>();
            double[] shortValues = {110, 105, 95, 90};
            for (int i = 0; i < shortValues.length; i++) {
                shortMa.put(START.plusDays(i), bd(shortValues[i]));
                longMa.put(START.plusDays(i), bd(100));
            }

            assertThat(calculator.calculate(withMas(shortMa, longMa))).isEmpty();
        }

        @Test
        @DisplayName("창 안에 교차가 없으면 행을 쓰지 않는다 — 센티널을 만들지 않는다")
        void writesNothingWhenNoCrossInWindow() {
            // 내내 60일선이 위에 있다. "이미 위에 있음"은 교차가 아니다.
            NavigableMap<LocalDate, BigDecimal> shortMa = new TreeMap<>();
            NavigableMap<LocalDate, BigDecimal> longMa = new TreeMap<>();
            for (int i = 0; i < 200; i++) {
                shortMa.put(START.plusDays(i), bd(150));
                longMa.put(START.plusDays(i), bd(100));
            }

            // −1 같은 센티널을 내면 "가장 최근에 교차했다"는 뜻의 극단에 앉아
            // LOWER_IS_BETTER 조건이 정반대 집합을 고른다(ADR-0046 §결과).
            assertThat(calculator.calculate(withMas(shortMa, longMa))).isEmpty();
        }

        @Test
        @DisplayName("교차가 탐색 창보다 오래되면 값이 사라진다 — 선언한 폭을 넘겨 훑지 않는다")
        void stopsAtTheDeclaredSearchWindow() {
            // 첫날 교차한 뒤 창(120)보다 오래 지난다.
            NavigableMap<LocalDate, BigDecimal> shortMa = new TreeMap<>();
            NavigableMap<LocalDate, BigDecimal> longMa = new TreeMap<>();
            int days = GoldenCrossAgeCalculator.SEARCH_DAYS + 30;
            for (int i = 0; i < days; i++) {
                shortMa.put(START.plusDays(i), bd(i == 0 ? 90 : 150));
                longMa.put(START.plusDays(i), bd(100));
            }

            List<IndicatorPoint> points = calculator.calculate(withMas(shortMa, longMa));

            assertThat(points).isNotEmpty();
            assertThat(points.get(points.size() - 1).value().intValue())
                    .as("마지막 값이 탐색 창을 넘지 않는다")
                    .isLessThanOrEqualTo(GoldenCrossAgeCalculator.SEARCH_DAYS);
        }

        @Test
        @DisplayName("60일선이 없는 날짜는 건너뛴다")
        void skipsDatesWithoutTheShortMa() {
            NavigableMap<LocalDate, BigDecimal> longMa = new TreeMap<>();
            for (int i = 0; i < 5; i++) {
                longMa.put(START.plusDays(i), bd(100));
            }

            assertThat(calculator.calculate(withMas(new TreeMap<>(), longMa))).isEmpty();
        }
    }

    // ── 픽스처 ────────────────────────────────────────────────────────────

    /** 네 이동평균이 하루치만 있는 컨텍스트. */
    private static IndicatorContext ladder(double sma20, double sma60, double sma120, double sma200) {
        return context(Map.of(
                Sma20Calculator.CODE, series(sma20),
                Sma60Calculator.CODE, series(sma60),
                Sma120Calculator.CODE, series(sma120),
                Sma200Calculator.CODE, series(sma200)));
    }

    private static NavigableMap<LocalDate, BigDecimal> series(double value) {
        NavigableMap<LocalDate, BigDecimal> map = new TreeMap<>();
        map.put(START, bd(value));
        return map;
    }

    private static IndicatorContext withMas(NavigableMap<LocalDate, BigDecimal> shortMa,
                                            NavigableMap<LocalDate, BigDecimal> longMa) {
        return context(Map.of(Sma60Calculator.CODE, shortMa, Sma200Calculator.CODE, longMa));
    }

    private static IndicatorContext context(Map<String, NavigableMap<LocalDate, BigDecimal>> deps) {
        return new IndicatorContext(1L, List.of(), List.of(), List.of(), deps);
    }

    private static BigDecimal value(List<IndicatorPoint> points) {
        assertThat(points).hasSize(1);
        return points.get(0).value();
    }

    private static BigDecimal bd(double value) {
        return BigDecimal.valueOf(value);
    }
}
