package com.stockscreener.indicator.calculator;

import com.stockscreener.indicator.FinancialFigures;
import com.stockscreener.indicator.IndicatorContext;
import com.stockscreener.indicator.IndicatorPoint;
import com.stockscreener.indicator.PriceBar;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * H-05(5년 누적 영업현금흐름)를 검증한다.
 *
 * <p>여기서 재는 것은 합계보다 <b>값을 내지 않는 조건</b>이다. 다섯 해가 모자라거나 구멍이
 * 있을 때 값을 내면 <b>이력이 짧은 종목일수록 게이트를 잘 통과하게</b> 되고, 그것은 이 조건이
 * 하려는 일과 정확히 반대다.
 *
 * <p>구멍 검사가 특히 필요한 이유는 읽기가 {@code ORDER BY period_end DESC LIMIT 5}라
 * <b>행 수만 맞으면 통과하기</b> 때문이다 — 실제로 267종목이 FY 행을 7개 갖고 있어, 한 해가
 * 빠져도 더 오래된 해가 조용히 대신 채운다.
 */
class OperatingCashFlow5ySumCalculatorTest {

    private final OperatingCashFlow5ySumCalculator calculator = new OperatingCashFlow5ySumCalculator();

    @Test
    @DisplayName("다섯 해가 연속하면 그 합을 낸다")
    void sumsFiveConsecutiveYears() {
        IndicatorContext context = context(annual(2025, "100", "200", "300", "-50", "150"));

        List<IndicatorPoint> points = calculator.calculate(context);

        assertThat(points).hasSize(1);
        // 한 해가 음수여도 누적으로 보는 것이 이 조건의 취지다.
        assertThat(points.get(0).value()).isEqualByComparingTo("700");
    }

    @Test
    @DisplayName("네 해뿐이면 값을 내지 않는다 — 네 해 합으로 접지 않는다")
    void producesNothingWithFourYears() {
        IndicatorContext context = context(annual(2025, "100", "200", "300", "400"));

        assertThat(calculator.calculate(context)).isEmpty();
    }

    @Test
    @DisplayName("한 해라도 값이 비면 값을 내지 않는다 — 0으로 접지 않는다")
    void producesNothingWhenAnyYearIsNull() {
        IndicatorContext context = context(annual(2025, "100", "200", null, "400", "500"));

        assertThat(calculator.calculate(context)).isEmpty();
    }

    @Test
    @DisplayName("연도에 구멍이 있으면 값을 내지 않는다 — 더 오래된 해가 대신 채운 것이다")
    void producesNothingWhenAYearIsMissing() {
        // 2025·2024·2023 다음이 2021이다. 행은 다섯이지만 덮는 기간은 여섯 해다.
        List<FinancialFigures> withHole = new ArrayList<>(
                annual(2025, "100", "200", "300", "400"));
        withHole.add(FinancialFiguresFixture.annual(LocalDate.of(2020, 12, 31))
                .operatingCashFlow(new BigDecimal("500")).build());

        assertThat(calculator.calculate(context(withHole))).isEmpty();
        // 구멍이 없었다면 같은 다섯 행이 값을 냈다는 것이 이 테스트의 대조군이다.
        assertThat(calculator.calculate(context(annual(2025, "100", "200", "300", "400", "500"))))
                .hasSize(1);
    }

    @Test
    @DisplayName("결산월이 12월이 아니어도 연속이면 값을 낸다 — fiscalYear 라벨이 아니라 기간으로 잰다")
    void acceptsNonDecemberFiscalYearEnds() {
        List<FinancialFigures> marchClosing = new ArrayList<>();
        LocalDate end = LocalDate.of(2026, 3, 31);
        for (int i = 0; i < 5; i++) {
            marchClosing.add(FinancialFiguresFixture.annual(end.minusYears(i))
                    .operatingCashFlow(new BigDecimal("100")).build());
        }

        assertThat(calculator.calculate(context(marchClosing))).hasSize(1);
    }

    @Test
    @DisplayName("여섯 해가 있어도 최근 다섯 해만 더한다")
    void usesOnlyTheMostRecentFiveYears() {
        List<FinancialFigures> six = new ArrayList<>(
                annual(2025, "100", "100", "100", "100", "100"));
        six.add(FinancialFiguresFixture.annual(LocalDate.of(2020, 12, 31))
                .operatingCashFlow(new BigDecimal("9999")).build());

        List<IndicatorPoint> points = calculator.calculate(context(six));

        assertThat(points).hasSize(1);
        assertThat(points.get(0).value()).isEqualByComparingTo("500");
    }

    /** {@code period_end} 내림차순으로 연간 재무를 만든다. 읽기 순서와 같다. */
    private static List<FinancialFigures> annual(int latestYear, String... operatingCashFlows) {
        List<FinancialFigures> list = new ArrayList<>(operatingCashFlows.length);
        for (int i = 0; i < operatingCashFlows.length; i++) {
            FinancialFiguresFixture fixture =
                    FinancialFiguresFixture.annual(LocalDate.of(latestYear - i, 12, 31));
            if (operatingCashFlows[i] != null) {
                fixture.operatingCashFlow(new BigDecimal(operatingCashFlows[i]));
            }
            list.add(fixture.build());
        }
        return list;
    }

    private static IndicatorContext context(List<FinancialFigures> annualFigures) {
        return new IndicatorContext(1L, List.of(priceBar()), annualFigures, List.of(), null);
    }

    private static PriceBar priceBar() {
        BigDecimal close = new BigDecimal("1000");
        return new PriceBar(LocalDate.of(2026, 8, 12), close, close, close, close, close,
                1000L, null, null);
    }
}
