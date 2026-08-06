package com.stockscreener.indicator.calculator;

import com.stockscreener.indicator.FinancialFigures;
import com.stockscreener.indicator.IndicatorContext;
import com.stockscreener.indicator.IndicatorPoint;
import com.stockscreener.indicator.PriceBar;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PBR 계산을 검증한다.
 *
 * <p>가장 중요한 것은 <b>자본잠식 처리</b>다. 자기자본이 음수인 기업의 PBR은 음수가 되는데,
 * 그것을 저장하면 "PBR이 낮은 저평가 종목"을 찾는 조건에 부실기업이 최상위로 올라온다.
 */
class PbrCalculatorTest {

    private final PbrCalculator calculator = new PbrCalculator();

    @Test
    @DisplayName("주가를 주당순자산으로 나눈다")
    void dividesCloseByBookValuePerShare() {
        // 자기자본 1,000,000 / 주식수 1,000 = BPS 1,000. 주가 2,000 → PBR 2배.
        IndicatorContext context = context(
                new BigDecimal("2000"),
                figures(new BigDecimal("1000000"), new BigDecimal("1000")));

        List<IndicatorPoint> points = calculator.calculate(context);

        assertThat(points).hasSize(1);
        assertThat(points.get(0).value()).isEqualByComparingTo("2");
    }

    @Test
    @DisplayName("자본잠식이면 값을 내지 않는다 — 음수 PBR이 저평가로 보이면 안 된다")
    void skipsWhenEquityIsNegative() {
        IndicatorContext context = context(
                new BigDecimal("2000"),
                figures(new BigDecimal("-500000"), new BigDecimal("1000")));

        assertThat(calculator.calculate(context)).isEmpty();
    }

    @Test
    @DisplayName("자기자본이 0이면 값을 내지 않는다")
    void skipsWhenEquityIsZero() {
        IndicatorContext context = context(
                new BigDecimal("2000"),
                figures(BigDecimal.ZERO, new BigDecimal("1000")));

        assertThat(calculator.calculate(context)).isEmpty();
    }

    @Test
    @DisplayName("자기자본이 비어 있으면 계산하지 않는다 — 0으로 대체하지 않는다")
    void skipsWhenEquityIsNull() {
        IndicatorContext context = context(
                new BigDecimal("2000"),
                figures(null, new BigDecimal("1000")));

        assertThat(calculator.calculate(context)).isEmpty();
    }

    @Test
    @DisplayName("재무가 없으면 값을 내지 않는다")
    void skipsWhenNoFinancials() {
        IndicatorContext context = new IndicatorContext(
                1L, List.of(priceBar(new BigDecimal("2000"))), List.of(), List.of(), Map.of());

        assertThat(calculator.calculate(context)).isEmpty();
    }

    @Test
    @DisplayName("수정주가가 아니라 원주가를 쓴다 — 시점의 발행주식수와 짝이 맞아야 한다")
    void usesUnadjustedClose() {
        // 원주가 2,000 / 수정주가 1,000. BPS 1,000이므로 원주가를 쓰면 2배, 수정주가면 1배다.
        PriceBar bar = new PriceBar(
                LocalDate.of(2026, 1, 2),
                new BigDecimal("2000"), new BigDecimal("2000"), new BigDecimal("2000"),
                new BigDecimal("2000"), new BigDecimal("1000"), 1000L, null);
        IndicatorContext context = new IndicatorContext(
                1L, List.of(bar), List.of(),
                List.of(figures(new BigDecimal("1000000"), new BigDecimal("1000"))), Map.of());

        List<IndicatorPoint> points = calculator.calculate(context);

        assertThat(points.get(0).value())
                .as("원주가 2000 ÷ BPS 1000 = 2배")
                .isEqualByComparingTo("2");
    }

    private static IndicatorContext context(BigDecimal close, FinancialFigures figures) {
        return new IndicatorContext(
                1L, List.of(priceBar(close)), List.of(), List.of(figures), Map.of());
    }

    private static PriceBar priceBar(BigDecimal close) {
        return new PriceBar(LocalDate.of(2026, 1, 2), close, close, close, close, close, 1000L, null);
    }

    private static FinancialFigures figures(BigDecimal equity, BigDecimal shares) {
        return FinancialFiguresFixture.quarter(LocalDate.of(2025, 12, 31))
                .totalEquity(equity)
                .sharesOutstanding(shares)
                .build();
    }
}
