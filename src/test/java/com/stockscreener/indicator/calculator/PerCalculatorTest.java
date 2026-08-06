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

class PerCalculatorTest {

    private final PerCalculator calculator = new PerCalculator();

    @Test
    @DisplayName("주가 ÷ TTM 주당순이익으로 계산한다")
    void computesFromPriceAndTtmEps() {
        // 분기당 순이익 250, 4분기 합 1000, 주식수 100 → EPS 10
        // 주가 150 → PER 15
        IndicatorContext context = new IndicatorContext(
                1L,
                List.of(priceBar(BigDecimal.valueOf(150))),
                List.of(),
                quarters(4, BigDecimal.valueOf(250), BigDecimal.valueOf(100)),
                null);

        List<IndicatorPoint> points = calculator.calculate(context);

        assertThat(points).hasSize(1);
        assertThat(points.get(0).value()).isEqualByComparingTo("15");
    }

    @Test
    @DisplayName("분기가 4개 미만이면 계산하지 않는다 — TTM을 만들 수 없다")
    void producesNothingWithoutFourQuarters() {
        IndicatorContext context = new IndicatorContext(
                1L,
                List.of(priceBar(BigDecimal.valueOf(150))),
                List.of(),
                quarters(3, BigDecimal.valueOf(250), BigDecimal.valueOf(100)),
                null);

        assertThat(calculator.calculate(context)).isEmpty();
    }

    @Test
    @DisplayName("적자면 계산하지 않는다 — 음수 PER은 '싼 종목' 조건에 적자기업을 최상위로 올린다")
    void producesNothingWhenLossMaking() {
        IndicatorContext context = new IndicatorContext(
                1L,
                List.of(priceBar(BigDecimal.valueOf(150))),
                List.of(),
                quarters(4, BigDecimal.valueOf(-250), BigDecimal.valueOf(100)),
                null);

        assertThat(calculator.calculate(context)).isEmpty();
    }

    @Test
    @DisplayName("순이익이 비어 있으면 0으로 대체하지 않고 계산을 포기한다")
    void producesNothingWhenNetIncomeMissing() {
        List<FinancialFigures> quarters = new ArrayList<>(
                quarters(4, BigDecimal.valueOf(250), BigDecimal.valueOf(100)));
        quarters.set(1, withNetIncome(quarters.get(1), null));

        IndicatorContext context = new IndicatorContext(
                1L, List.of(priceBar(BigDecimal.valueOf(150))), List.of(), quarters, null);

        assertThat(calculator.calculate(context)).isEmpty();
    }

    @Test
    @DisplayName("IMPORTANT — 원주가(close)를 쓴다. 수정주가는 시점의 발행주식수와 짝이 맞지 않는다")
    void usesUnadjustedClose() {
        // close 150, adjClose 30 (분할 반영). PER은 close 기준이어야 15가 나온다.
        PriceBar bar = new PriceBar(LocalDate.of(2026, 1, 2),
                BigDecimal.valueOf(150), BigDecimal.valueOf(150), BigDecimal.valueOf(150),
                BigDecimal.valueOf(150), BigDecimal.valueOf(30), 1000L, null);

        IndicatorContext context = new IndicatorContext(
                1L, List.of(bar), List.of(),
                quarters(4, BigDecimal.valueOf(250), BigDecimal.valueOf(100)), null);

        List<IndicatorPoint> points = calculator.calculate(context);

        assertThat(points).hasSize(1);
        // adjClose(30)로 계산했다면 3이 나온다.
        assertThat(points.get(0).value()).isEqualByComparingTo("15");
    }

    @Test
    @DisplayName("시세의 상장주식수를 재무보다 우선한다 — 날짜별 값이라 증자·분할이 바로 반영된다")
    void prefersListedSharesFromPriceBar() {
        // 재무는 주식수 100(EPS 10 → PER 15)이라 하고, 시세는 200을 준다(EPS 5 → PER 30).
        PriceBar bar = new PriceBar(LocalDate.of(2026, 1, 2),
                BigDecimal.valueOf(150), BigDecimal.valueOf(150), BigDecimal.valueOf(150),
                BigDecimal.valueOf(150), BigDecimal.valueOf(150), 1000L, BigDecimal.valueOf(200));

        IndicatorContext context = new IndicatorContext(
                1L, List.of(bar), List.of(),
                quarters(4, BigDecimal.valueOf(250), BigDecimal.valueOf(100)), null);

        assertThat(calculator.calculate(context).get(0).value())
                .as("시세 쪽 200주로 계산해야 30이다. 재무 쪽 100주를 쓰면 15가 나온다")
                .isEqualByComparingTo("30");
    }

    @Test
    @DisplayName("시세에 주식수가 없으면 재무로 폴백한다 — 소스마다 주는 쪽이 다르다")
    void fallsBackToFinancialShares() {
        IndicatorContext context = new IndicatorContext(
                1L, List.of(priceBar(BigDecimal.valueOf(150))), List.of(),
                quarters(4, BigDecimal.valueOf(250), BigDecimal.valueOf(100)), null);

        assertThat(calculator.calculate(context).get(0).value()).isEqualByComparingTo("15");
    }

    private static PriceBar priceBar(BigDecimal close) {
        return new PriceBar(LocalDate.of(2026, 1, 2), close, close, close, close, close, 1000L, null);
    }

    private static List<FinancialFigures> quarters(int count, BigDecimal netIncome, BigDecimal shares) {
        List<FinancialFigures> list = new ArrayList<>(count);
        LocalDate end = LocalDate.of(2025, 12, 31);
        for (int i = 0; i < count; i++) {
            LocalDate periodEnd = end.minusMonths(3L * i);
            list.add(FinancialFiguresFixture.quarter(periodEnd)
                    .netIncome(netIncome)
                    .sharesOutstanding(shares)
                    .build());
        }
        return list;
    }

    private static FinancialFigures withNetIncome(FinancialFigures source, BigDecimal netIncome) {
        return FinancialFiguresFixture.from(source).netIncome(netIncome).build();
    }
}
