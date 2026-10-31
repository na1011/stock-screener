package com.stockscreener.indicator.calculator;

import com.stockscreener.indicator.FinancialFigures;
import com.stockscreener.indicator.GovernanceFigures;
import com.stockscreener.indicator.GovernanceMetric;
import com.stockscreener.indicator.GovernanceValue;
import com.stockscreener.indicator.IndicatorContext;
import com.stockscreener.indicator.IndicatorPoint;
import com.stockscreener.indicator.PriceBar;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 배당성향의 분모는 그 봉 시점에 공시돼 있던 FY 판이어야 한다(관문 켬 · ADR-0128) — 꺼짐은 실행 시점의 최신 판이라
 * 정정 전 봉에도 정정 뒤 순이익이 실렸다(관문 재고 「`annualOf` 시점 위반」).
 */
class DividendPayoutCalculatorTest {

    private static final LocalDate FY_END = LocalDate.of(2024, 12, 31);
    private static final LocalDate ORIGINAL = LocalDate.of(2025, 3, 1);
    private static final LocalDate RESTATED = LocalDate.of(2025, 9, 1);
    private static final LocalDate BEFORE_RESTATEMENT = LocalDate.of(2025, 4, 1);
    private static final LocalDate AFTER_RESTATEMENT = LocalDate.of(2025, 10, 1);

    private final DividendPayoutCalculator calculator = new DividendPayoutCalculator();

    @Test
    @DisplayName("관문 꺼짐은 지금처럼 모든 봉이 최신 판의 순이익을 쓴다")
    void gateOffPairsTheLatestVintage() {
        List<IndicatorPoint> points = calculator.calculate(context(false));

        assertThat(valueOn(points, BEFORE_RESTATEMENT)).isEqualByComparingTo("25");
        assertThat(valueOn(points, AFTER_RESTATEMENT)).isEqualByComparingTo("25");
    }

    @Test
    @DisplayName("관문 켬은 그 봉까지 공시된 판의 순이익을 쓴다 — 정정 전 봉에 정정 뒤 값이 새지 않는다")
    void gateOnPairsTheVintageKnownOnTheBar() {
        List<IndicatorPoint> points = calculator.calculate(context(true));

        assertThat(valueOn(points, BEFORE_RESTATEMENT)).isEqualByComparingTo("50");
        assertThat(valueOn(points, AFTER_RESTATEMENT)).isEqualByComparingTo("25");
    }

    private static BigDecimal valueOn(List<IndicatorPoint> points, LocalDate date) {
        return points.stream().filter(p -> p.tradeDate().equals(date)).findFirst().orElseThrow().value();
    }

    private static IndicatorContext context(boolean gateOn) {
        FinancialFigures original = FinancialFiguresFixture.annual(FY_END).publishedAt(ORIGINAL)
                .netIncome(new BigDecimal("100")).netIncomeControlling(new BigDecimal("100")).build();
        FinancialFigures restated = FinancialFiguresFixture.annual(FY_END).publishedAt(RESTATED)
                .netIncome(new BigDecimal("200")).netIncomeControlling(new BigDecimal("200")).build();
        GovernanceFigures dividend = new GovernanceFigures(2024, Map.of(GovernanceMetric.CASH_DIVIDEND_TOTAL,
                new GovernanceValue(new BigDecimal("50"), LocalDate.of(2025, 3, 15))));
        return new IndicatorContext(1L, null,
                List.of(bar(BEFORE_RESTATEMENT), bar(AFTER_RESTATEMENT)),
                // 리더 계약대로 기말 내림차순 · 같은 기말이면 공시일 내림차순(판 이력 전부)
                List.of(restated, original), List.of(),
                List.of(), List.of(), new TreeMap<>(), List.of(dividend), Map.of())
                .withGateOn(gateOn);
    }

    private static PriceBar bar(LocalDate date) {
        return new PriceBar(date, BigDecimal.TEN, BigDecimal.TEN, BigDecimal.TEN, BigDecimal.TEN, BigDecimal.TEN,
                1L, BigDecimal.TEN, BigDecimal.TEN);
    }
}
