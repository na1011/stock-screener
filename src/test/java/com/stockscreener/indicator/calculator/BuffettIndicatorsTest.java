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

/** 버핏 방법론 프리셋이 쓰는 신규 지표 여덟(ADR-0067). */
class BuffettIndicatorsTest {

    private static final LocalDate START = LocalDate.of(2021, 1, 4);

    // ── 순이익률 ──────────────────────────────────────────────────────────

    @Nested
    @DisplayName("NET_MARGIN")
    class NetMargin {

        private final NetMarginCalculator calculator = new NetMarginCalculator();

        @Test
        @DisplayName("최근 4개 분기 순이익을 같은 기간 매출로 나눈다")
        void dividesTtmNetIncomeByTtmRevenue() {
            List<FinancialFigures> quarters = quarters(4, q -> q
                    .netIncome(new BigDecimal("25"))
                    .revenue(new BigDecimal("250")));

            List<IndicatorPoint> points = calculator.calculate(context(bars(1), quarters));

            assertThat(points).hasSize(1);
            assertThat(points.get(0).value()).isEqualByComparingTo("10");
        }

        @Test
        @DisplayName("매출이 0이면 값을 내지 않는다 — 0으로 나누면 터진다")
        void emitsNothingWhenRevenueIsZero() {
            List<FinancialFigures> quarters = quarters(4, q -> q
                    .netIncome(new BigDecimal("25"))
                    .revenue(BigDecimal.ZERO));

            assertThat(calculator.calculate(context(bars(1), quarters))).isEmpty();
        }

        @Test
        @DisplayName("순손실이면 음수를 그대로 낸다 — 적자를 감추지 않는다")
        void keepsNegativeMargin() {
            List<FinancialFigures> quarters = quarters(4, q -> q
                    .netIncome(new BigDecimal("-25"))
                    .revenue(new BigDecimal("250")));

            List<IndicatorPoint> points = calculator.calculate(context(bars(1), quarters));

            assertThat(points.get(0).value()).isEqualByComparingTo("-10");
        }
    }

    // ── 세전 투하자본이익률 ────────────────────────────────────────────────

    @Nested
    @DisplayName("ROIC_PRETAX")
    class RoicPretax {

        private final RoicPretaxCalculator calculator = new RoicPretaxCalculator();

        @Test
        @DisplayName("영업이익을 (자산총계 − 유동부채)로 나눈다")
        void dividesOperatingIncomeByInvestedCapital() {
            List<FinancialFigures> quarters = quarters(4, q -> q
                    .operatingIncome(new BigDecimal("30"))
                    .totalAssets(new BigDecimal("1000"))
                    .currentLiabilities(new BigDecimal("200")));

            List<IndicatorPoint> points = calculator.calculate(context(bars(1), quarters));

            // 영업이익 TTM 120, 투하자본 800 → 15%
            assertThat(points.get(0).value()).isEqualByComparingTo("15");
        }

        @Test
        @DisplayName("IMPORTANT — 유동부채가 없으면 값을 내지 않는다. 자산총계로 대체하면 ROA가 된다")
        void emitsNothingWhenCurrentLiabilitiesMissing() {
            List<FinancialFigures> quarters = quarters(4, q -> q
                    .operatingIncome(new BigDecimal("30"))
                    .totalAssets(new BigDecimal("1000")));

            assertThat(calculator.calculate(context(bars(1), quarters)))
                    .as("금융업은 유동/비유동을 가르지 않는다. 분모를 바꾸면 뜻이 다른 값이 같은 이름을 갖는다")
                    .isEmpty();
        }

        @Test
        @DisplayName("잔액을 네 분기 합산하지 않는다 — 합산하면 투하자본이 네 배가 된다")
        void usesLatestBalanceNotSum() {
            List<FinancialFigures> quarters = quarters(4, q -> q
                    .operatingIncome(new BigDecimal("30"))
                    .totalAssets(new BigDecimal("1000"))
                    .currentLiabilities(new BigDecimal("200")));

            List<IndicatorPoint> points = calculator.calculate(context(bars(1), quarters));

            assertThat(points.get(0).value())
                    .as("합산했다면 3.75%가 나온다 — 그럴듯한 수라 눈으로 안 걸린다")
                    .isEqualByComparingTo("15");
        }
    }

    // ── 3년 최저 ROE ──────────────────────────────────────────────────────

    @Nested
    @DisplayName("ROE_MIN_3Y")
    class RoeMin3y {

        private final RoeMin3yCalculator calculator = new RoeMin3yCalculator();

        @Test
        @DisplayName("세 해 중 가장 낮은 해를 낸다 — 평균이 아니다")
        void takesMinimumNotAverage() {
            List<FinancialFigures> annual = List.of(
                    annualRoe(LocalDate.of(2025, 12, 31), "40", "100"),
                    annualRoe(LocalDate.of(2024, 12, 31), "5", "100"),
                    annualRoe(LocalDate.of(2023, 12, 31), "30", "100"));

            List<IndicatorPoint> points = calculator.calculate(annualContext(annual));

            assertThat(points.get(0).value())
                    .as("평균이면 25가 나온다 — 일회성 이익이 난 해가 나머지를 끌어올린다")
                    .isEqualByComparingTo("5");
        }

        @Test
        @DisplayName("IMPORTANT — 한 해가 빠지면 값을 내지 않는다. 행 수만 세면 구멍이 안 보인다")
        void emitsNothingWhenAYearIsMissing() {
            List<FinancialFigures> annual = List.of(
                    annualRoe(LocalDate.of(2025, 12, 31), "20", "100"),
                    // 2024가 없다. 행 수는 셋이라 LIMIT 검사는 통과한다.
                    annualRoe(LocalDate.of(2023, 12, 31), "20", "100"),
                    annualRoe(LocalDate.of(2022, 12, 31), "20", "100"));

            assertThat(calculator.calculate(annualContext(annual))).isEmpty();
        }

        @Test
        @DisplayName("IMPORTANT — 자본이 음수인 해가 있으면 값을 내지 않는다. 순손실이 양수 ROE가 된다")
        void emitsNothingOnNegativeEquity() {
            List<FinancialFigures> annual = List.of(
                    annualRoe(LocalDate.of(2025, 12, 31), "-50", "-100"),
                    annualRoe(LocalDate.of(2024, 12, 31), "20", "100"),
                    annualRoe(LocalDate.of(2023, 12, 31), "20", "100"));

            assertThat(calculator.calculate(annualContext(annual)))
                    .as("부호가 두 번 뒤집혀 +50%가 나온다 — 자본잠식 기업이 최상위로 온다")
                    .isEmpty();
        }
    }

    @Nested
    @DisplayName("ROIC_MIN_3Y")
    class RoicMin3y {

        private final RoicMin3yCalculator calculator = new RoicMin3yCalculator();

        @Test
        @DisplayName("세 해 중 가장 낮은 해를 낸다 — 단년 TTM이 놓치는 그 해다")
        void takesMinimumNotAverage() {
            List<FinancialFigures> annual = List.of(
                    annualRoic(LocalDate.of(2025, 12, 31), "40", "300", "100"),
                    annualRoic(LocalDate.of(2024, 12, 31), "10", "300", "100"),
                    annualRoic(LocalDate.of(2023, 12, 31), "30", "300", "100"));

            List<IndicatorPoint> points = calculator.calculate(annualContext(annual));

            assertThat(points.get(0).value())
                    .as("투하자본 200에 최저 영업이익 10이면 5%다. 평균이면 13.3%가 나온다")
                    .isEqualByComparingTo("5");
        }

        @Test
        @DisplayName("IMPORTANT — 연간 영업이익을 합산하지 않는다. 합하면 세 배가 된다")
        void doesNotSumAnnualFlows() {
            List<FinancialFigures> annual = List.of(
                    annualRoic(LocalDate.of(2025, 12, 31), "20", "300", "100"),
                    annualRoic(LocalDate.of(2024, 12, 31), "20", "300", "100"),
                    annualRoic(LocalDate.of(2023, 12, 31), "20", "300", "100"));

            assertThat(calculator.calculate(annualContext(annual)).get(0).value())
                    .as("연간 행의 영업이익은 이미 그 해 전체다 — 네 분기 합산 함수를 쓰면 안 된다")
                    .isEqualByComparingTo("10");
        }

        @Test
        @DisplayName("IMPORTANT — 한 해가 빠지면 값을 내지 않는다. 행 수만 세면 구멍이 안 보인다")
        void emitsNothingWhenAYearIsMissing() {
            List<FinancialFigures> annual = List.of(
                    annualRoic(LocalDate.of(2025, 12, 31), "20", "300", "100"),
                    // 2024가 없다. 행 수는 셋이라 LIMIT 검사는 통과한다.
                    annualRoic(LocalDate.of(2023, 12, 31), "20", "300", "100"),
                    annualRoic(LocalDate.of(2022, 12, 31), "20", "300", "100"));

            assertThat(calculator.calculate(annualContext(annual))).isEmpty();
        }

        @Test
        @DisplayName("IMPORTANT — 투하자본이 음수인 해가 있으면 값을 내지 않는다. 영업적자가 양수가 된다")
        void emitsNothingOnNegativeInvestedCapital() {
            List<FinancialFigures> annual = List.of(
                    annualRoic(LocalDate.of(2025, 12, 31), "-50", "100", "300"),
                    annualRoic(LocalDate.of(2024, 12, 31), "20", "300", "100"),
                    annualRoic(LocalDate.of(2023, 12, 31), "20", "300", "100"));

            assertThat(calculator.calculate(annualContext(annual)))
                    .as("자산 100 − 유동부채 300 = −200. 부호가 두 번 뒤집혀 +25%가 나온다")
                    .isEmpty();
        }

        @Test
        @DisplayName("유동부채가 없는 해가 있으면 값을 내지 않는다 — 금융업이 여기 온다")
        void emitsNothingWhenCurrentLiabilitiesAreMissing() {
            List<FinancialFigures> annual = List.of(
                    annualRoic(LocalDate.of(2025, 12, 31), "20", "300", null),
                    annualRoic(LocalDate.of(2024, 12, 31), "20", "300", "100"),
                    annualRoic(LocalDate.of(2023, 12, 31), "20", "300", "100"));

            assertThat(calculator.calculate(annualContext(annual))).isEmpty();
        }
    }

    // ── 연속 영업흑자 연수 ─────────────────────────────────────────────────

    @Nested
    @DisplayName("PROFIT_STREAK_Y")
    class ProfitStreak {

        private final ProfitStreakYearsCalculator calculator = new ProfitStreakYearsCalculator();

        @Test
        @DisplayName("일곱 해가 모두 흑자면 7이다")
        void countsSevenWhenAllProfitable() {
            List<IndicatorPoint> points =
                    calculator.calculate(annualContext(operatingIncomes(
                            "10", "10", "10", "10", "10", "10", "10")));

            assertThat(points.get(0).value()).isEqualByComparingTo("7");
        }

        @Test
        @DisplayName("적자를 만나면 거기서 끊는다 — 그 뒤 흑자는 세지 않는다")
        void stopsAtFirstLoss() {
            List<IndicatorPoint> points =
                    calculator.calculate(annualContext(operatingIncomes(
                            "10", "10", "-1", "10", "10", "10", "10")));

            assertThat(points.get(0).value())
                    .as("연속이 아니라 총합을 세면 6이 나온다")
                    .isEqualByComparingTo("2");
        }

        @Test
        @DisplayName("IMPORTANT — 일곱 해가 없으면 값을 내지 않는다. 있는 만큼 세면 이력이 짧은 종목이 유리해진다")
        void emitsNothingWithFewerThanSevenYears() {
            assertThat(calculator.calculate(annualContext(operatingIncomes(
                    "10", "10", "10", "10", "10", "10"))))
                    .as("여섯 해뿐인 종목이 '6년 연속 흑자'를 받으면 안 된다 — 판정불가여야 한다")
                    .isEmpty();
        }

        @Test
        @DisplayName("영업이익이 비어 있으면 값을 내지 않는다 — 결손을 짧은 연수로 말하지 않는다")
        void treatsMissingAsUnevaluable() {
            List<FinancialFigures> annual = operatingIncomes(
                    "10", null, "10", "10", "10", "10", "10");

            List<IndicatorPoint> points = calculator.calculate(annualContext(annual));

            assertThat(points)
                    .as("계정이 비어 있는 것은 「1년 연속 흑자」가 아니다 — 판정불가여야 한다")
                    .isEmpty();
        }

        @Test
        @DisplayName("직전 해가 적자면 0이다 — 값이 없는 것과 다르다")
        void emitsZeroWhenLatestYearIsLoss() {
            List<IndicatorPoint> points =
                    calculator.calculate(annualContext(operatingIncomes(
                            "-1", "10", "10", "10", "10", "10", "10")));

            assertThat(points.get(0).value()).isEqualByComparingTo("0");
        }
    }

    // ── 5년 최저 영업이익률 ────────────────────────────────────────────────

    @Nested
    @DisplayName("OPM_MIN_5Y")
    class OperatingMarginMin5y {

        private final OperatingMarginMin5yCalculator calculator =
                new OperatingMarginMin5yCalculator();

        @Test
        @DisplayName("다섯 해 중 가장 낮은 이익률을 낸다")
        void takesMinimum() {
            List<FinancialFigures> annual = new ArrayList<>();
            String[] operating = {"30", "20", "2", "25", "30"};
            for (int i = 0; i < operating.length; i++) {
                annual.add(FinancialFiguresFixture.annual(yearEnd(2025 - i))
                        .operatingIncome(new BigDecimal(operating[i]))
                        .revenue(new BigDecimal("100"))
                        .build());
            }

            List<IndicatorPoint> points = calculator.calculate(annualContext(annual));

            assertThat(points.get(0).value()).isEqualByComparingTo("2");
        }

        @Test
        @DisplayName("매출이 0 이하인 해가 있으면 값을 내지 않는다 — 절댓값 분모는 적자를 흑자로 만든다")
        void emitsNothingWhenRevenueIsNotPositive() {
            List<FinancialFigures> annual = new ArrayList<>();
            for (int i = 0; i < 5; i++) {
                annual.add(FinancialFiguresFixture.annual(yearEnd(2025 - i))
                        .operatingIncome(new BigDecimal("-10"))
                        .revenue(i == 2 ? BigDecimal.ZERO : new BigDecimal("100"))
                        .build());
            }

            assertThat(calculator.calculate(annualContext(annual))).isEmpty();
        }
    }

    // ── 유보 1원당 시가 창출 ───────────────────────────────────────────────

    @Nested
    @DisplayName("RETAINED_TO_MARKET_4Y")
    class RetainedToMarket {

        private final RetainedToMarket4yCalculator calculator =
                new RetainedToMarket4yCalculator();

        @Test
        @DisplayName("주가 증가분을 주당 이익잉여금 증가분으로 나눈다")
        void dividesPriceGainByRetainedGainPerShare() {
            // 유보 400 증가, 주식수 100 → 주당 4원. 주가는 앵커 1,000 → 1,008로 8원 올랐다.
            List<IndicatorPoint> points = calculator.calculate(retainedContext(
                    "1000", "1008", 100L, "1400", "1000"));

            assertThat(points).isNotEmpty();
            assertThat(points.get(points.size() - 1).value()).isEqualByComparingTo("2");
        }

        @Test
        @DisplayName("IMPORTANT — 이익잉여금이 줄었으면 값을 내지 않는다. 음수 분모는 오른 주가를 최하위로 민다")
        void emitsNothingWhenRetainedEarningsShrank() {
            assertThat(calculator.calculate(retainedContext(
                    "1000", "1500", 100L, "900", "1000")))
                    .as("주가가 50% 올랐는데 음수가 나오면 정렬이 뒤집힌다")
                    .isEmpty();
        }

        @Test
        @DisplayName("주가가 내렸으면 음수를 낸다 — 유보를 헛되이 썼다는 뜻이다")
        void emitsNegativeWhenPriceFell() {
            List<IndicatorPoint> points = calculator.calculate(retainedContext(
                    "1000", "996", 100L, "1400", "1000"));

            assertThat(points.get(points.size() - 1).value()).isEqualByComparingTo("-1");
        }

        @Test
        @DisplayName("다섯 해가 연속이 아니면 값을 내지 않는다")
        void emitsNothingWhenYearsAreNotConsecutive() {
            List<FinancialFigures> annual = List.of(
                    retained(yearEnd(2025), "1400"),
                    retained(yearEnd(2024), "1300"),
                    // 2023이 없다.
                    retained(yearEnd(2022), "1200"),
                    retained(yearEnd(2021), "1100"),
                    retained(yearEnd(2020), "1000"));

            assertThat(calculator.calculate(new IndicatorContext(
                    1L, barsFrom(START, 1300, "1000", "1008", 100L), annual, List.of(), Map.of())))
                    .isEmpty();
        }
    }

    // ── 잉여현금흐름 ──────────────────────────────────────────────────────

    @Nested
    @DisplayName("FCF_MARGIN · FCF_YIELD")
    class FreeCashFlowIndicators {

        private final FcfMarginCalculator margin = new FcfMarginCalculator();
        private final FcfYieldCalculator yield = new FcfYieldCalculator();

        @Test
        @DisplayName("영업활동현금흐름에서 자본적지출을 뺀다 — 더하지 않는다")
        void subtractsCapex() {
            List<FinancialFigures> annual = List.of(
                    FinancialFiguresFixture.annual(yearEnd(2025))
                            .operatingCashFlow(new BigDecimal("300"))
                            .capex(new BigDecimal("100"))
                            .revenue(new BigDecimal("1000"))
                            .build());

            List<IndicatorPoint> points = margin.calculate(annualContext(annual));

            assertThat(points.get(0).value())
                    .as("더했다면 40%가 나온다 — 자본적지출이 양수 크기로 오므로 빼는 것이 맞다")
                    .isEqualByComparingTo("20");
        }

        @Test
        @DisplayName("IMPORTANT — 자본적지출이 음수로 와도 유출로 읽는다. 부호 규약이 회사마다 갈린다")
        void treatsNegativeCapexAsOutflow() {
            List<FinancialFigures> annual = List.of(
                    FinancialFiguresFixture.annual(yearEnd(2025))
                            .operatingCashFlow(new BigDecimal("300"))
                            .capex(new BigDecimal("-100"))
                            .revenue(new BigDecimal("1000"))
                            .build());

            assertThat(margin.calculate(annualContext(annual)).get(0).value())
                    .as("부호를 그대로 빼면 40%가 나온다 — 뺄셈이 덧셈이 되어 "
                            + "잉여현금흐름이 영업현금흐름보다 커지는데 값이 그럴듯해 안 드러난다")
                    .isEqualByComparingTo("20");
        }

        @Test
        @DisplayName("IMPORTANT — 자본적지출이 없으면 값을 내지 않는다. 0으로 보면 자본집약 기업이 우량해 보인다")
        void emitsNothingWhenCapexMissing() {
            List<FinancialFigures> annual = List.of(
                    FinancialFiguresFixture.annual(yearEnd(2025))
                            .operatingCashFlow(new BigDecimal("300"))
                            .revenue(new BigDecimal("1000"))
                            .build());

            assertThat(margin.calculate(annualContext(annual))).isEmpty();
        }

        @Test
        @DisplayName("자본적지출이 영업현금흐름을 넘으면 음수를 낸다")
        void emitsNegativeWhenCapexExceedsCashFlow() {
            List<FinancialFigures> annual = List.of(
                    FinancialFiguresFixture.annual(yearEnd(2025))
                            .operatingCashFlow(new BigDecimal("100"))
                            .capex(new BigDecimal("300"))
                            .revenue(new BigDecimal("1000"))
                            .build());

            assertThat(margin.calculate(annualContext(annual)).get(0).value())
                    .isEqualByComparingTo("-20");
        }

        @Test
        @DisplayName("수익률은 시가총액으로 나눈다 — 다시 계산하지 않고 MARKET_CAP에 의존한다")
        void dividesByMarketCapDependency() {
            List<FinancialFigures> annual = List.of(
                    FinancialFiguresFixture.annual(yearEnd(2025))
                            .operatingCashFlow(new BigDecimal("300"))
                            .capex(new BigDecimal("100"))
                            .revenue(new BigDecimal("1000"))
                            .build());
            NavigableMap<LocalDate, BigDecimal> marketCap = new TreeMap<>();
            marketCap.put(START, new BigDecimal("4000"));

            List<IndicatorPoint> points = yield.calculate(new IndicatorContext(
                    1L, bars(1), annual, List.of(),
                    Map.of(MarketCapCalculator.CODE, marketCap)));

            assertThat(points.get(0).value()).isEqualByComparingTo("5");
        }

        @Test
        @DisplayName("시가총액이 없는 날은 건너뛴다")
        void skipsDatesWithoutMarketCap() {
            List<FinancialFigures> annual = List.of(
                    FinancialFiguresFixture.annual(yearEnd(2025))
                            .operatingCashFlow(new BigDecimal("300"))
                            .capex(new BigDecimal("100"))
                            .revenue(new BigDecimal("1000"))
                            .build());

            assertThat(yield.calculate(new IndicatorContext(
                    1L, bars(1), annual, List.of(),
                    Map.of(MarketCapCalculator.CODE, new TreeMap<>()))))
                    .isEmpty();
        }
    }

    // ── 픽스처 ────────────────────────────────────────────────────────────

    private static LocalDate yearEnd(int year) {
        return LocalDate.of(year, 12, 31);
    }

    private static List<PriceBar> bars(int count) {
        return barsFrom(START, count, "1000", "1000", 100L);
    }

    /** 첫 봉만 {@code firstPrice}, 나머지는 {@code lastPrice}인 일봉. */
    private static List<PriceBar> barsFrom(LocalDate start, int count,
                                           String firstPrice, String lastPrice, long shares) {
        List<PriceBar> bars = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            BigDecimal price = new BigDecimal(i == 0 ? firstPrice : lastPrice);
            bars.add(new PriceBar(start.plusDays(i), price, price, price, price, price,
                    1_000L, null, BigDecimal.valueOf(shares)));
        }
        return bars;
    }

    private static IndicatorContext context(List<PriceBar> bars,
                                            List<FinancialFigures> quarters) {
        return new IndicatorContext(1L, bars, List.of(), quarters, Map.of());
    }

    private static IndicatorContext annualContext(List<FinancialFigures> annual) {
        return new IndicatorContext(1L, bars(1), annual, List.of(), Map.of());
    }

    /**
     * 앵커(FY−4 기말) 다음 날부터 시작하는 봉과, 다섯 해 연간 재무를 가진 컨텍스트.
     *
     * <p>첫 봉이 앵커가 되도록 시작일을 FY2021 기말 이전에 둔다.
     */
    private static IndicatorContext retainedContext(String anchorPrice, String nowPrice,
                                                    long shares,
                                                    String recentRetained, String pastRetained) {
        List<FinancialFigures> annual = List.of(
                retained(yearEnd(2025), recentRetained),
                retained(yearEnd(2024), "0"),
                retained(yearEnd(2023), "0"),
                retained(yearEnd(2022), "0"),
                retained(yearEnd(2021), pastRetained));
        // 앵커는 FY2021 기말(2021-12-31) 이하의 <b>마지막</b> 봉이다. 시작을 그 날로 두어야
        // 첫 봉이 앵커가 된다 — 하루 앞에서 시작하면 둘째 봉도 기말 이하라 그쪽이 앵커가 되고,
        // 그러면 앵커 가격이 nowPrice와 같아져 값이 0으로 나온다.
        List<PriceBar> bars =
                barsFrom(LocalDate.of(2021, 12, 31), 3, anchorPrice, nowPrice, shares);
        return new IndicatorContext(1L, bars, annual, List.of(), Map.of());
    }

    private static FinancialFigures retained(LocalDate periodEnd, String value) {
        return FinancialFiguresFixture.annual(periodEnd)
                .retainedEarnings(new BigDecimal(value))
                .build();
    }

    private static FinancialFigures annualRoe(LocalDate periodEnd, String netIncome,
                                              String equity) {
        return FinancialFiguresFixture.annual(periodEnd)
                .netIncome(new BigDecimal(netIncome))
                .totalEquity(new BigDecimal(equity))
                .build();
    }

    /** 연간 투하자본이익률용. {@code currentLiabilities}가 null이면 금융업 형태다. */
    private static FinancialFigures annualRoic(LocalDate periodEnd, String operatingIncome,
                                               String assets, String currentLiabilities) {
        return FinancialFiguresFixture.annual(periodEnd)
                .operatingIncome(new BigDecimal(operatingIncome))
                .totalAssets(new BigDecimal(assets))
                .currentLiabilities(
                        currentLiabilities == null ? null : new BigDecimal(currentLiabilities))
                .build();
    }

    /** 최신 연도부터 내림차순으로 영업이익을 채운 연간 재무. null은 계정 결손이다. */
    private static List<FinancialFigures> operatingIncomes(String... values) {
        List<FinancialFigures> annual = new ArrayList<>(values.length);
        for (int i = 0; i < values.length; i++) {
            FinancialFiguresFixture fixture = FinancialFiguresFixture.annual(yearEnd(2025 - i));
            if (values[i] != null) {
                fixture.operatingIncome(new BigDecimal(values[i]));
            }
            annual.add(fixture.build());
        }
        return annual;
    }

    private static List<FinancialFigures> quarters(
            int count, java.util.function.UnaryOperator<FinancialFiguresFixture> customize) {
        List<FinancialFigures> quarters = new ArrayList<>(count);
        LocalDate periodEnd = LocalDate.of(2025, 12, 31);
        for (int i = 0; i < count; i++) {
            quarters.add(customize
                    .apply(FinancialFiguresFixture.quarter(periodEnd.minusMonths(3L * i)))
                    .build());
        }
        return quarters;
    }
}
