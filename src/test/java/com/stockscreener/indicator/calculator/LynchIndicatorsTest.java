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

/** 린치 방법론 프리셋이 쓰는 신규 지표 — PEG와 52주 절대 수익률(ADR-0066). */
class LynchIndicatorsTest {

    private static final LocalDate START = LocalDate.of(2026, 1, 2);

    @Nested
    @DisplayName("PEG")
    class Peg {

        private final PegCalculator calculator = new PegCalculator();

        @Test
        @DisplayName("PER을 성장률로 나눈다 — 성장률의 단위는 퍼센트 포인트다")
        void dividesPerByGrowthInPercentagePoints() {
            // PER 20에 이익이 20% 늘면 PEG는 1.0이다. 표준 정의이며,
            // 분모를 0.20으로 환산하면 100이 나온다 — 그럴듯한 수라 눈으로 안 걸린다.
            List<IndicatorPoint> points = calculator.calculate(
                    withParents(series(START, "20"), series(START, "20")));

            assertThat(points).hasSize(1);
            assertThat(points.get(0).value()).isEqualByComparingTo("1");
        }

        @Test
        @DisplayName("IMPORTANT — 이익이 줄고 있으면 값을 내지 않는다. 음수 PEG는 가장 싼 값처럼 통과한다")
        void emitsNothingWhenEarningsShrink() {
            List<IndicatorPoint> points = calculator.calculate(
                    withParents(series(START, "20"), series(START, "-10")));

            assertThat(points)
                    .as("음수 PEG를 저장하면 'PEG가 낮은 종목' 조건에 역성장 기업이 최상위로 올라온다")
                    .isEmpty();
        }

        @Test
        @DisplayName("성장률이 정확히 0이어도 값을 내지 않는다 — 0으로 나누면 터진다")
        void emitsNothingWhenGrowthIsZero() {
            assertThat(calculator.calculate(
                    withParents(series(START, "20"), series(START, "0")))).isEmpty();
        }

        @Test
        @DisplayName("부모의 워밍업 길이가 달라 한쪽만 있는 날은 건너뛴다")
        void skipsDatesWhereOneParentIsMissing() {
            NavigableMap<LocalDate, BigDecimal> per = new TreeMap<>();
            per.put(START, new BigDecimal("20"));
            per.put(START.plusDays(1), new BigDecimal("20"));
            NavigableMap<LocalDate, BigDecimal> growth = new TreeMap<>();
            growth.put(START.plusDays(1), new BigDecimal("10"));

            List<IndicatorPoint> points = calculator.calculate(withParents(per, growth));

            assertThat(points).hasSize(1);
            assertThat(points.get(0).tradeDate()).isEqualTo(START.plusDays(1));
            assertThat(points.get(0).value()).isEqualByComparingTo("2");
        }

        @Test
        @DisplayName("부모가 비어 있으면 아무 값도 내지 않는다")
        void emitsNothingWithoutParents() {
            assertThat(calculator.calculate(
                    withParents(new TreeMap<>(), series(START, "10")))).isEmpty();
            assertThat(calculator.calculate(
                    withParents(series(START, "10"), new TreeMap<>()))).isEmpty();
        }

        private IndicatorContext withParents(NavigableMap<LocalDate, BigDecimal> per,
                                             NavigableMap<LocalDate, BigDecimal> growth) {
            return IndicatorContext.empty(1L).withDependencies(Map.of(
                    PerCalculator.CODE, per,
                    EpsGrowthTtmYoyCalculator.CODE, growth));
        }
    }

    @Nested
    @DisplayName("52주 수익률")
    class Return52w {

        private final Return52wCalculator calculator = new Return52wCalculator();

        @Test
        @DisplayName("252거래일 전 대비 두 배면 100%다")
        void doublingOverAYearIsOneHundredPercent() {
            List<PriceBar> bars = flat(ExcessReturn.LOOKBACK_52W + 1, "100");
            bars.set(bars.size() - 1, bar(START.plusDays(bars.size() - 1), "200", "200"));

            List<IndicatorPoint> points = calculator.calculate(context(bars));

            assertThat(points).hasSize(1);
            assertThat(points.get(0).value()).isEqualByComparingTo("100");
        }

        @Test
        @DisplayName("봉이 252개 이하면 값을 내지 않는다")
        void emitsNothingWithoutAFullYear() {
            assertThat(calculator.calculate(context(flat(ExcessReturn.LOOKBACK_52W, "100"))))
                    .isEmpty();
        }

        @Test
        @DisplayName("IMPORTANT — 수정종가를 쓴다. 원주가를 쓰면 5:1 분할이 −80%가 된다")
        void usesAdjustedCloseSoSplitsDoNotShowAsCrashes() {
            // 원주가는 500 → 100으로 떨어졌지만 수정종가는 100으로 평평하다(5:1 분할).
            List<PriceBar> bars = new ArrayList<>();
            for (int i = 0; i <= ExcessReturn.LOOKBACK_52W; i++) {
                boolean afterSplit = i == ExcessReturn.LOOKBACK_52W;
                bars.add(bar(START.plusDays(i), afterSplit ? "100" : "500", "100"));
            }

            List<IndicatorPoint> points = calculator.calculate(context(bars));

            assertThat(points).hasSize(1);
            assertThat(points.get(0).value())
                    .as("분할은 수익률 사건이 아니다")
                    .isEqualByComparingTo("0");
        }

        @Test
        @DisplayName("지수가 없어도 값이 난다 — 초과 수익률과 재는 대상이 다르다")
        void doesNotNeedTheMarketIndex() {
            // EXCESS_RETURN_52W는 지수가 없는 날 행을 쓰지 않는다. 이 지표는 지수를 보지 않으므로
            // 커버리지가 더 넓고, 그것은 결함이 아니다.
            List<PriceBar> bars = flat(ExcessReturn.LOOKBACK_52W + 1, "100");

            assertThat(context(bars).marketIndex()).isEmpty();
            assertThat(calculator.calculate(context(bars))).hasSize(1);
        }

        private IndicatorContext context(List<PriceBar> bars) {
            return new IndicatorContext(1L, bars, List.of(), List.of(), null);
        }

        private List<PriceBar> flat(int count, String price) {
            List<PriceBar> bars = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                bars.add(bar(START.plusDays(i), price, price));
            }
            return bars;
        }
    }

    private static PriceBar bar(LocalDate date, String close, String adjClose) {
        BigDecimal raw = new BigDecimal(close);
        return new PriceBar(date, raw, raw, raw, raw, new BigDecimal(adjClose), 1_000L, null, null);
    }

    private static NavigableMap<LocalDate, BigDecimal> series(LocalDate date, String value) {
        NavigableMap<LocalDate, BigDecimal> series = new TreeMap<>();
        series.put(date, new BigDecimal(value));
        return series;
    }
}
