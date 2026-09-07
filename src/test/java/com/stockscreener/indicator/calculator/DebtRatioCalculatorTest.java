package com.stockscreener.indicator.calculator;

import com.stockscreener.indicator.FinancialFigures;
import com.stockscreener.indicator.IndicatorContext;
import com.stockscreener.indicator.PriceBar;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 부채비율.
 *
 * <p><b>이 계산기에는 시험이 하나도 없었다.</b> 총부채 유도를 {@link FinancialMath}로 옮기면서
 * 그 사실이 드러났다 — 유도는 원래 여기 인라인으로만 있었고, 그래서 <b>같은 재료로도 알트만
 * 둘과 NCAV는 값을 못 냈다.</b> 이제 넷이 같은 것을 쓰므로, 그 동등성이 깨지는 것을 잡을
 * 자리가 여기에도 있어야 한다.
 */
class DebtRatioCalculatorTest {

    private static final LocalDate START = LocalDate.of(2025, 1, 2);

    private final DebtRatioCalculator calculator = new DebtRatioCalculator();

    private static IndicatorContext context(FinancialFigures latest) {
        PriceBar bar = new PriceBar(START, BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE,
                BigDecimal.ONE, BigDecimal.ONE, 10L, null, BigDecimal.TEN);
        return new IndicatorContext(1L, List.of(bar), List.of(), List.of(latest), Map.of());
    }

    private static FinancialFiguresFixture quarter() {
        return FinancialFiguresFixture.quarter(LocalDate.of(2024, 12, 31));
    }

    @Test
    @DisplayName("부채총계를 자기자본으로 나눈다")
    void dividesLiabilitiesByEquity() {
        assertThat(calculator.calculate(context(quarter()
                .totalLiabilities(BigDecimal.valueOf(400))
                .totalEquity(BigDecimal.valueOf(500))
                .build())).getFirst().value())
                .isEqualByComparingTo("80");
    }

    @Test
    @DisplayName("부채총계가 없으면 자산 − 자기자본으로 유도한다 — 신고했을 때와 같은 값이다")
    void derivesLiabilitiesFromAssetsAndEquity() {
        assertThat(calculator.calculate(context(quarter()
                .totalAssets(BigDecimal.valueOf(900))
                .totalEquity(BigDecimal.valueOf(500))
                .build())).getFirst().value())
                .as("900 − 500 = 400이므로 위 시험과 같은 80이어야 한다")
                .isEqualByComparingTo("80");
    }

    @Test
    @DisplayName("유도조차 불가능하면 값을 내지 않는다 — 0으로 두면 부채가 없는 우량기업이 된다")
    void emitsNothingWhenNeitherIsAvailable() {
        assertThat(calculator.calculate(context(quarter()
                .totalEquity(BigDecimal.valueOf(500))
                .build()))).isEmpty();
    }

    @Test
    @DisplayName("자기자본이 0 이하면 값을 내지 않는다 — 분모가 음수면 부호가 뒤집힌다")
    void emitsNothingWhenEquityIsNotPositive() {
        assertThat(calculator.calculate(context(quarter()
                .totalLiabilities(BigDecimal.valueOf(400))
                .totalEquity(BigDecimal.valueOf(-100))
                .build()))).isEmpty();
    }
}
