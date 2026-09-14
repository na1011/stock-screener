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
 * 세후 투하자본이익률 계열 넷. <b>세율의 정의역과 부호 판정이 이 시험의 중심</b>이다 —
 * 값이 그럴듯하게 틀리는 자리가 거기이기 때문이다.
 *
 * <p>봉 날짜를 재무 공시일보다 뒤에 둔다({@code .claude/rules/test.md}) — 앞이면 지표가
 * 0개가 되어 <b>증상이 계산 로직처럼 보인다.</b>
 */
class AfterTaxRoicTest {

    /** FY2025 공시일(픽스처 규약: 기말 + 2개월)보다 뒤다. */
    private static final LocalDate START = LocalDate.of(2026, 4, 1);

    @Nested
    @DisplayName("유효세율")
    class EffectiveTaxRate {

        private final EffectiveTaxRateCalculator calculator = new EffectiveTaxRateCalculator();

        @Test
        @DisplayName("법인세비용 ÷ 세전이익을 백분율로 낸다")
        void computesRatio() {
            List<IndicatorPoint> points = calculator.calculate(
                    annualContext(List.of(taxFigures(yearEnd(2025), "250", "1000", "750"))));

            assertThat(points.get(0).value()).isEqualByComparingTo("25");
        }

        @Test
        @DisplayName("IMPORTANT — 진짜 환입은 0%로 접힌다. 크기로 읽으면 +20%가 되어 부호가 반대다")
        void clampsGenuineRefundToZero() {
            // 순이익 > 세전이익이면 그 해 세금을 돌려받은 것이다(삼성전자 2022가 그 모양).
            List<IndicatorPoint> points = calculator.calculate(
                    annualContext(List.of(taxFigures(yearEnd(2025), "-200", "1000", "1200"))));

            // 이 지표는 세후 세 지표가 실제로 쓰는 값과 같아야 하므로 [0,1] 클램프까지 같다.
            // 그래야 화면에서 세후 ≈ 세전 × (1 − 유효세율)이 성립한다.
            assertThat(points.get(0).value())
                    .as("환입은 지속되지 않으므로 0%로 본다. 바로 아래 시험과 값이 갈리는 것이 "
                            + "부호 판정이 살아 있다는 증거다 — abs로 읽으면 둘 다 같은 값이 된다")
                    .isEqualByComparingTo("0");
        }

        @Test
        @DisplayName("IMPORTANT — 차감 항목 표기는 부호를 뒤집는다. 항등식이 가른다")
        void flipsSignConventionNegative() {
            // 순이익 < 세전이익인데 법인세가 음수다 — 비용을 음수로 적은 것이다(POSCO 2021).
            List<IndicatorPoint> points = calculator.calculate(
                    annualContext(List.of(taxFigures(yearEnd(2025), "-250", "1000", "750"))));

            assertThat(points.get(0).value())
                    .as("세전 − 순이익이 +250이므로 비용이다. +25%로 읽어야 한다")
                    .isEqualByComparingTo("25");
        }

        @Test
        @DisplayName("세전이익이 0 이하면 값을 내지 않는다 — 음수 분모가 부호를 뒤집는다")
        void emitsNothingWhenPretaxNotPositive() {
            assertThat(calculator.calculate(
                    annualContext(List.of(taxFigures(yearEnd(2025), "100", "-1000", "-1100")))))
                    .isEmpty();
        }
    }

    @Nested
    @DisplayName("ROIC_AFTERTAX (TTM)")
    class AfterTaxTtm {

        private final RoicAfterTaxCalculator calculator = new RoicAfterTaxCalculator();

        @Test
        @DisplayName("세후가 세전보다 낮다 — 정상 세율에서")
        void afterTaxIsLowerThanPretax() {
            IndicatorContext context = ttmContext("25");

            BigDecimal afterTax = calculator.calculate(context).get(0).value();
            BigDecimal preTax = new RoicPretaxCalculator().calculate(context).get(0).value();

            assertThat(afterTax).isLessThan(preTax);
            // 영업이익 400(=100×4), 투하자본 2000, 세율 25% → 400×0.75/2000 = 15%
            assertThat(afterTax).isEqualByComparingTo("15");
        }

        @Test
        @DisplayName("IMPORTANT — 환입 해는 세율을 0으로 자른다. 안 자르면 NOPAT이 영업이익보다 커진다")
        void clampsNegativeRateToZero() {
            // 세전 1000 · 순이익 1200 · 법인세 −200 → 관측 세율 −20%
            BigDecimal value = calculator.calculate(ttmContextRaw("-200", "1000", "1200"))
                    .get(0).value();

            assertThat(value)
                    .as("t를 0으로 자르므로 세후 = 세전인 20%다. 안 자르면 24%가 된다")
                    .isEqualByComparingTo("20");
        }

        @Test
        @DisplayName("IMPORTANT — 세율이 1을 넘으면 1로 자른다. 안 자르면 영업흑자가 세후 적자가 된다")
        void clampsRateAboveOne() {
            // 세전 1000 · 법인세 1500 → 관측 세율 150%
            BigDecimal value = calculator.calculate(ttmContextRaw("1500", "1000", "-500"))
                    .get(0).value();

            assertThat(value)
                    .as("t를 1로 자르므로 NOPAT이 0이다. 안 자르면 −10%로 부호가 뒤집힌다")
                    .isEqualByComparingTo("0");
        }
    }

    @Nested
    @DisplayName("5년 계열")
    class FiveYear {

        private final RoicAfterTaxMin5yCalculator min = new RoicAfterTaxMin5yCalculator();
        private final RoicAfterTaxAvg5yCalculator avg = new RoicAfterTaxAvg5yCalculator();

        @Test
        @DisplayName("최저는 가장 나쁜 해를, 평균은 다섯 해를 고르게 반영한다")
        void minAndAverageDiffer() {
            List<FinancialFigures> annual = fiveYears("400", "100", "300", "300", "300");

            // 세율 25% 고정, 투하자본 2000 → 세후 ROIC = 영업이익×0.75/2000×100
            assertThat(min.calculate(annualContext(annual)).get(0).value())
                    .as("최저 영업이익 100 → 100×0.75/2000 = 3.75%")
                    .isEqualByComparingTo("3.75");
            assertThat(avg.calculate(annualContext(annual)).get(0).value())
                    .as("평균 영업이익 280 → 280×0.75/2000 = 10.5%")
                    .isEqualByComparingTo("10.50000000");
        }

        @Test
        @DisplayName("IMPORTANT — 한 해가 빠지면 값을 내지 않는다. 행 수만 세면 구멍이 안 보인다")
        void emitsNothingWhenAYearIsMissing() {
            List<FinancialFigures> annual = new ArrayList<>();
            for (int year : new int[] {2025, 2024, 2022, 2021, 2020}) {
                annual.add(roicFigures(yearEnd(year), "300", "250", "1000", "750"));
            }
            assertThat(min.calculate(annualContext(annual))).isEmpty();
        }

        @Test
        @DisplayName("IMPORTANT — 세전 적자인 해가 있어도 값을 낸다. 그 해에 없는 것은 세율이다")
        void emitsValueWhenSomeYearHasNonPositivePretax() {
            List<FinancialFigures> annual = new ArrayList<>();
            annual.add(roicFigures(yearEnd(2025), "300", "250", "1000", "750"));
            // 영업은 흑자(300)인데 영업외손실로 세전이 적자인 해. 그 해 세율은 못 구한다.
            annual.add(roicFigures(yearEnd(2024), "300", "100", "-500", "-600"));
            for (int year : new int[] {2023, 2022, 2021}) {
                annual.add(roicFigures(yearEnd(year), "300", "250", "1000", "750"));
            }

            // 구할 수 있는 네 해의 세율은 전부 250/1000 = 25%이고 중앙값도 25%다.
            // 다섯 해 모두 NOPAT = 300 × 0.75 = 225, 투하자본 = 3000 − 1000 = 2000 → 11.25%.
            // 2024년은 그 해 세율을 못 구하지만 정규화 세율을 받아 같은 값을 낸다.
            assertThat(min.calculate(annualContext(annual)))
                    .as("영업이익이 있는 해를 세율 하나 때문에 버리지 않는다")
                    .isNotEmpty()
                    .allSatisfy(point -> assertThat(point.value())
                            .isEqualByComparingTo(new BigDecimal("11.25")));
        }

        @Test
        @DisplayName("IMPORTANT — 다섯 해 모두 세율을 못 구하면 그때는 값이 없다")
        void emitsNothingWhenNoYearYieldsTaxRate() {
            List<FinancialFigures> annual = new ArrayList<>();
            for (int year : new int[] {2025, 2024, 2023, 2022, 2021}) {
                // 세전이익도 순이익도 없어 유도조차 못 한다.
                annual.add(FinancialFiguresFixture.annual(yearEnd(year))
                        .operatingIncome(new BigDecimal("300"))
                        .totalAssets(new BigDecimal("1000"))
                        .currentLiabilities(new BigDecimal("750"))
                        .build());
            }
            assertThat(min.calculate(annualContext(annual)))
                    .as("정규화 세율조차 못 구하면 값을 내지 않는다 — 거기가 진짜 결손이다")
                    .isEmpty();
        }

        @Test
        @DisplayName("IMPORTANT — 투하자본이 음수인 해가 있으면 값을 내지 않는다")
        void emitsNothingOnNegativeInvestedCapital() {
            List<FinancialFigures> annual = new ArrayList<>();
            annual.add(FinancialFiguresFixture.annual(yearEnd(2025))
                    .operatingIncome(new BigDecimal("300"))
                    .incomeTaxExpense(new BigDecimal("250"))
                    .profitBeforeTax(new BigDecimal("1000"))
                    .netIncome(new BigDecimal("750"))
                    .totalAssets(new BigDecimal("1000"))
                    .currentLiabilities(new BigDecimal("1200"))
                    .build());
            for (int year : new int[] {2024, 2023, 2022, 2021}) {
                annual.add(roicFigures(yearEnd(year), "300", "250", "1000", "750"));
            }
            assertThat(min.calculate(annualContext(annual))).isEmpty();
        }
    }

    // ---------- 픽스처 ----------

    private static LocalDate yearEnd(int year) {
        return LocalDate.of(year, 12, 31);
    }

    private static IndicatorContext annualContext(List<FinancialFigures> annual) {
        return new IndicatorContext(1L, bars(), annual, List.of(), Map.of());
    }

    /** 분기 넷과 연간 하나. TTM 계산기가 둘 다 쓴다. */
    private static IndicatorContext ttmContext(String taxRatePercent) {
        BigDecimal rate = new BigDecimal(taxRatePercent);
        BigDecimal pretax = new BigDecimal("1000");
        BigDecimal tax = pretax.multiply(rate).movePointLeft(2);
        return ttmContextRaw(tax.toPlainString(), "1000",
                pretax.subtract(tax).toPlainString());
    }

    /** 분기 넷(영업이익 100씩 · 투하자본 2000)과 연간 여럿. 정규화 세율을 재는 자리에 쓴다. */
    private static IndicatorContext ttmContextWithAnnuals(List<FinancialFigures> annual) {
        List<FinancialFigures> quarters = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            quarters.add(FinancialFiguresFixture.quarter(yearEnd(2025).minusMonths(3L * i))
                    .operatingIncome(new BigDecimal("100"))
                    .totalAssets(new BigDecimal("3000"))
                    .currentLiabilities(new BigDecimal("1000"))
                    .build());
        }
        return new IndicatorContext(1L, bars(), annual, quarters, Map.of());
    }

    private static IndicatorContext ttmContextRaw(String tax, String pretax, String netIncome) {
        List<FinancialFigures> quarters = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            quarters.add(FinancialFiguresFixture.quarter(yearEnd(2025).minusMonths(3L * i))
                    .operatingIncome(new BigDecimal("100"))
                    .totalAssets(new BigDecimal("3000"))
                    .currentLiabilities(new BigDecimal("1000"))
                    .build());
        }
        List<FinancialFigures> annual = List.of(
                taxFigures(yearEnd(2025), tax, pretax, netIncome));
        return new IndicatorContext(1L, bars(), annual, quarters, Map.of());
    }

    /** 세율 재료만 채운 연간 재무. */
    private static FinancialFigures taxFigures(LocalDate periodEnd, String tax,
                                               String pretax, String netIncome) {
        return FinancialFiguresFixture.annual(periodEnd)
                .incomeTaxExpense(new BigDecimal(tax))
                .profitBeforeTax(new BigDecimal(pretax))
                .netIncome(new BigDecimal(netIncome))
                .build();
    }

    /** 세율 재료 + 투하자본까지 채운 연간 재무. */
    /**
     * 정규화가 실제로 무엇을 고치는지를 재는 자리다. 위 시험들은 「값이 난다」까지만 답하고,
     * <b>한 해의 이상치가 값을 통째로 정하지 않는다</b>는 것은 여기서만 갈린다.
     */
    @Nested
    @DisplayName("정규화 세율")
    class NormalizedRate {

        private final RoicAfterTaxCalculator ttm = new RoicAfterTaxCalculator();

        @Test
        @DisplayName("IMPORTANT — 직전 한 해가 이상치여도 TTM이 그쪽으로 끌려가지 않는다")
        void latestYearOutlierDoesNotDragTtm() {
            // 직전 해만 세율 90%(소급 과세·일회성 추징이 그 모양이다). 나머지 넷은 25%.
            List<FinancialFigures> annual = new ArrayList<>();
            annual.add(taxFigures(yearEnd(2025), "900", "1000", "100"));
            for (int year : new int[] {2024, 2023, 2022, 2021}) {
                annual.add(taxFigures(yearEnd(year), "250", "1000", "750"));
            }

            // 중앙값 25% → NOPAT = 400 × 0.75 = 300, 투하자본 2000 → 15%.
            // 직전 해 세율 90%를 그대로 쓰면 400 × 0.10 = 40 → 2%가 되어 7.5배 어긋난다.
            assertThat(ttm.calculate(ttmContextWithAnnuals(annual)))
                    .isNotEmpty()
                    .allSatisfy(point -> assertThat(point.value())
                            .isEqualByComparingTo(new BigDecimal("15.00")));
        }

        @Test
        @DisplayName("환입 한 해가 중앙값을 0으로 끌어내리지 않는다")
        void singleRefundYearDoesNotZeroTheRate() {
            // 직전 해만 환입(순이익 > 세전). 클램프로 그 해는 0%가 되지만 중앙값은 25%다.
            List<FinancialFigures> annual = new ArrayList<>();
            annual.add(taxFigures(yearEnd(2025), "-200", "1000", "1200"));
            for (int year : new int[] {2024, 2023, 2022, 2021}) {
                annual.add(taxFigures(yearEnd(year), "250", "1000", "750"));
            }

            assertThat(ttm.calculate(ttmContextWithAnnuals(annual)))
                    .isNotEmpty()
                    .allSatisfy(point -> assertThat(point.value())
                            .as("환입 해를 0%로 접되 중앙값이 25%라 NOPAT은 300이다")
                            .isEqualByComparingTo(new BigDecimal("15.00")));
        }
    }

    private static FinancialFigures roicFigures(LocalDate periodEnd, String operatingIncome,
                                                String tax, String pretax, String netIncome) {
        return FinancialFiguresFixture.annual(periodEnd)
                .operatingIncome(new BigDecimal(operatingIncome))
                .incomeTaxExpense(new BigDecimal(tax))
                .profitBeforeTax(new BigDecimal(pretax))
                .netIncome(new BigDecimal(netIncome))
                .totalAssets(new BigDecimal("3000"))
                .currentLiabilities(new BigDecimal("1000"))
                .build();
    }

    /** 세율 25% 고정, 투하자본 2000인 다섯 해. 최신부터 내림차순. */
    private static List<FinancialFigures> fiveYears(String... operatingIncomes) {
        List<FinancialFigures> annual = new ArrayList<>(operatingIncomes.length);
        for (int i = 0; i < operatingIncomes.length; i++) {
            annual.add(roicFigures(yearEnd(2025 - i), operatingIncomes[i], "250", "1000", "750"));
        }
        return annual;
    }

    private static List<PriceBar> bars() {
        BigDecimal price = new BigDecimal("1000");
        return List.of(new PriceBar(START, price, price, price, price, price,
                1_000L, null, BigDecimal.valueOf(100)));
    }
}
