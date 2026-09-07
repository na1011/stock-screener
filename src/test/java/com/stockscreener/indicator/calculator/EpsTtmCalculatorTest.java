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

class EpsTtmCalculatorTest {

    private final EpsTtmCalculator calculator = new EpsTtmCalculator();
    private final PerCalculator per = new PerCalculator();

    @Test
    @DisplayName("TTM 순이익 ÷ 발행주식수로 계산한다")
    void computesTtmNetIncomePerShare() {
        // 분기당 250, 4분기 합 1000, 주식수 100 → EPS 10
        assertThat(calculator.calculate(context(BigDecimal.valueOf(250))).get(0).value())
                .isEqualByComparingTo("10");
    }

    @Test
    @DisplayName("IMPORTANT — 적자여도 값을 낸다. PER의 부호 가지를 복사하면 그 종목들이 조용히 사라진다")
    void producesValueWhenLossMaking() {
        IndicatorContext context = context(BigDecimal.valueOf(-250));

        List<IndicatorPoint> points = calculator.calculate(context);

        assertThat(points).hasSize(1);
        assertThat(points.get(0).value()).isEqualByComparingTo("-10");
        assertThat(per.calculate(context))
                .as("PER은 같은 입력에 값을 내지 않는다 — 두 지표에서 적자의 뜻이 다르다는 것이 요점이다")
                .isEmpty();
    }

    @Test
    @DisplayName("분기가 4개 미만이면 계산하지 않는다 — TTM을 만들 수 없다")
    void producesNothingWithoutFourQuarters() {
        IndicatorContext context = new IndicatorContext(
                1L, List.of(priceBar()), List.of(),
                quarters(3, BigDecimal.valueOf(250)), null);

        assertThat(calculator.calculate(context)).isEmpty();
    }

    @Test
    @DisplayName("순이익이 비어 있으면 0으로 대체하지 않고 계산을 포기한다")
    void producesNothingWhenNetIncomeMissing() {
        List<FinancialFigures> quarters =
                new ArrayList<>(quarters(4, BigDecimal.valueOf(250)));
        quarters.set(1, FinancialFiguresFixture.from(quarters.get(1)).netIncome(null).build());

        IndicatorContext context =
                new IndicatorContext(1L, List.of(priceBar()), List.of(), quarters, null);

        assertThat(calculator.calculate(context)).isEmpty();
    }

    @Test
    @DisplayName("PER과 같은 EPS를 쓴다 — 주가 ÷ EPS가 PER과 맞아야 한다")
    void staysConsistentWithPer() {
        IndicatorContext context = context(BigDecimal.valueOf(250));

        BigDecimal eps = calculator.calculate(context).get(0).value();
        BigDecimal perValue = per.calculate(context).get(0).value();

        // 주가 150. 두 지표가 다른 EPS를 쓰기 시작하면 화면에서 이 등식만 어긋난다.
        assertThat(eps.multiply(perValue)).isEqualByComparingTo("150");
    }

    private IndicatorContext context(BigDecimal quarterlyNetIncome) {
        return new IndicatorContext(
                1L, List.of(priceBar()), List.of(), quarters(4, quarterlyNetIncome), null);
    }

    private static PriceBar priceBar() {
        BigDecimal close = BigDecimal.valueOf(150);
        return new PriceBar(LocalDate.of(2026, 3, 2), close, close, close, close, close,
                1000L, null, BigDecimal.valueOf(100));
    }

    private static List<FinancialFigures> quarters(int count, BigDecimal netIncome) {
        List<FinancialFigures> list = new ArrayList<>(count);
        LocalDate end = LocalDate.of(2025, 12, 31);
        for (int i = 0; i < count; i++) {
            list.add(FinancialFiguresFixture.quarter(end.minusMonths(3L * i))
                    .netIncome(netIncome)
                    .build());
        }
        return list;
    }
}
