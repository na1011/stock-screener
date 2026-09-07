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
import static org.assertj.core.api.Assertions.within;

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


    @Nested
    @DisplayName("5년 주당순이익 성장률")
    class EpsCagr {

        private final EpsCagr5yCalculator calculator = new EpsCagr5yCalculator();

        @Test
        @DisplayName("5년에 주당순이익이 두 배면 연평균 14.87%다")
        void doublingOverFiveYearsIsFourteenPercent() {
            List<IndicatorPoint> points = calculator.calculate(
                    financials(quarters(25, 12.5), bars(1)));

            assertThat(points).hasSize(1);
            // 2^(1/5) − 1 = 0.148698…
            assertThat(points.get(0).value().doubleValue()).isCloseTo(14.87, within(0.01));
        }

        @Test
        @DisplayName("IMPORTANT — 주식수가 늘면 순이익 CAGR보다 낮다. 양 끝 주식수가 약분되지 않는다는 증거다")
        void dilutionMakesItLowerThanNetIncomeCagr() {
            // 순이익은 5년에 두 배(연 14.87%)인데 주식수도 두 배가 됐다 — 주당으로는 제자리다.
            List<PriceBar> bars = new ArrayList<>();
            for (int i = 0; i <= EpsCagr5yCalculator.LOOKBACK; i++) {
                boolean recent = i == EpsCagr5yCalculator.LOOKBACK;
                bars.add(barWithShares(START.plusDays(i), "100", recent ? "2" : "1"));
            }

            List<IndicatorPoint> points =
                    calculator.calculate(financials(quarters(25, 12.5), bars));

            assertThat(points).hasSize(1);
            assertThat(points.get(0).value().doubleValue())
                    .as("약분되면 14.87이 나온다 — 그때 이름은 EPS인데 재는 것은 순이익이다")
                    .isCloseTo(0.0, within(0.01));
        }

        @Test
        @DisplayName("5년 전이 적자면 값을 내지 않는다")
        void emitsNothingWhenTheBaseIsALoss() {
            assertThat(calculator.calculate(financials(quarters(25, -10), bars(1)))).isEmpty();
        }

        @Test
        @DisplayName("IMPORTANT — 분기가 빠져 실제 기간이 5년이 아니면 값을 내지 않는다")
        void emitsNothingWhenTheTwoWindowsAreNotFiveYearsApart() {
            // 인덱스 20은 늘 「20분기 전」이지만 공시가 빠지면 「5년 전」이 아니다.
            // 여기서는 분기 간격을 6개월로 벌려 인덱스 20이 10년 전이 되게 만든다.
            List<FinancialFigures> sparse = new ArrayList<>();
            for (int j = 0; j < 24; j++) {
                sparse.add(FinancialFiguresFixture
                        .quarter(START.plusDays(EpsCagr5yCalculator.LOOKBACK).minusMonths(6L * j))
                        .netIncome(new BigDecimal("25"))
                        .publishedAt(START.minusDays(1))
                        .build());
            }

            assertThat(calculator.calculate(financials(sparse, bars(1))))
                    .as("이름이 5년이라 말하면서 10년을 재면 안 된다")
                    .isEmpty();
        }

        @Test
        @DisplayName("봉이 1,260개 이하면 값을 내지 않는다")
        void emitsNothingWithoutFiveYearsOfBars() {
            List<PriceBar> tooShort = new ArrayList<>();
            for (int i = 0; i < EpsCagr5yCalculator.LOOKBACK; i++) {
                tooShort.add(barWithShares(START.plusDays(i), "100", "1"));
            }
            assertThat(calculator.calculate(financials(quarters(25, 12.5), tooShort))).isEmpty();
        }

        /** 최근 넷이 {@code recent}, 5년 전 넷이 {@code prior}인 분기 스물넷. */
        private List<FinancialFigures> quarters(double recent, double prior) {
            List<FinancialFigures> quarters = new ArrayList<>();
            for (int j = 0; j < 24; j++) {
                double amount = j < 4 ? recent : prior;
                quarters.add(FinancialFiguresFixture
                        .quarter(START.plusDays(EpsCagr5yCalculator.LOOKBACK).minusMonths(3L * j))
                        .netIncome(BigDecimal.valueOf(amount))
                        // 이 시험은 **계산식**을 잰다. 봉마다 재무를 고르는 축은
                        // IndicatorRunnerTest의 대조군과 FinancialVisibilityTest가 갖는다 —
                        // 여기서 공시 시차를 두면 두 축이 한 시험에 섞인다(ADR-0128).
                        .publishedAt(START.minusDays(1))
                        .build());
            }
            return quarters;
        }

        private List<PriceBar> bars(int shares) {
            List<PriceBar> bars = new ArrayList<>();
            for (int i = 0; i <= EpsCagr5yCalculator.LOOKBACK; i++) {
                bars.add(barWithShares(START.plusDays(i), "100", String.valueOf(shares)));
            }
            return bars;
        }
    }

    @Nested
    @DisplayName("5년 PER 중앙값")
    class PerMedian5y {

        private final PerMedian5yCalculator calculator = new PerMedian5yCalculator();

        @Test
        @DisplayName("IMPORTANT — 봉마다 그때 공시돼 있던 이익을 쓴다. 오늘 이익을 과거에 소급하지 않는다")
        void rebuildsEarningsAsTheyWereKnownAtEachBar() {
            // 주가는 내내 100이고 주식수는 1이다. 과거 이익은 분기당 5(TTM 20 → PER 5),
            // 최근 이익은 분기당 25(TTM 100 → PER 1)다.
            //
            // PER 지표의 시계열을 그대로 썼다면 모든 봉이 오늘의 TTM 100을 쓰므로 중앙값이 1이다.
            // 시점별로 다시 세우면 과거 구간이 5로 잡혀 중앙값이 1보다 커진다.
            List<IndicatorPoint> points = calculator.calculate(
                    financials(steppedQuarters(), flatBars()));

            assertThat(points).isNotEmpty();
            assertThat(points.get(points.size() - 1).value().doubleValue())
                    .as("1이 나오면 오늘 이익을 과거에 소급한 것이다 — 그것은 주가 이력이지 밸류에이션 이력이 아니다")
                    .isGreaterThan(1.0);
        }

        @Test
        @DisplayName("표본이 절반에 못 미치면 값을 내지 않는다")
        void emitsNothingWithoutEnoughSamples() {
            // 공시가 최근 것뿐이면 대부분의 봉에서 TTM을 세울 수 없다.
            List<FinancialFigures> onlyRecent = new ArrayList<>();
            for (int j = 0; j < 4; j++) {
                onlyRecent.add(FinancialFiguresFixture
                        .quarter(START.plusDays(PerMedian5yCalculator.LOOKBACK).minusMonths(3L * j))
                        .netIncome(new BigDecimal("25"))
                        .build());
            }

            assertThat(calculator.calculate(financials(onlyRecent, flatBars()))).isEmpty();
        }

        @Test
        @DisplayName("재무가 없으면 값을 내지 않는다")
        void emitsNothingWithoutFinancials() {
            assertThat(calculator.calculate(financials(List.of(), flatBars()))).isEmpty();
        }

        /** 옛 분기는 5, 최근 넷은 25. 시점별 복원이 되는지를 가르는 계단이다. */
        private List<FinancialFigures> steppedQuarters() {
            List<FinancialFigures> quarters = new ArrayList<>();
            for (int j = 0; j < 40; j++) {
                double amount = j < 4 ? 25 : 5;
                quarters.add(FinancialFiguresFixture
                        .quarter(START.plusDays(PerMedian5yCalculator.LOOKBACK).minusMonths(3L * j))
                        .netIncome(BigDecimal.valueOf(amount))
                        .build());
            }
            return quarters;
        }

        private List<PriceBar> flatBars() {
            List<PriceBar> bars = new ArrayList<>();
            for (int i = 0; i <= PerMedian5yCalculator.LOOKBACK; i++) {
                bars.add(barWithShares(START.plusDays(i), "100", "1"));
            }
            return bars;
        }
    }

    private static IndicatorContext financials(List<FinancialFigures> quarters,
                                               List<PriceBar> bars) {
        return new IndicatorContext(1L, bars, List.of(), quarters, null);
    }

    private static PriceBar barWithShares(LocalDate date, String close, String shares) {
        BigDecimal raw = new BigDecimal(close);
        return new PriceBar(date, raw, raw, raw, raw, raw, 1_000L, null, new BigDecimal(shares));
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
