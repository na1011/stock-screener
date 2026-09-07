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
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 이름이 {@code EPS}인데 EPS를 재지 않는 세 가지 실패를 막는다.
 *
 * <p>셋 다 <b>값이 그럴듯하게 나오는</b> 실패다. 예외도 빈 결과도 아니라서, 이 테스트가 없으면
 * 조건에 걸린 뒤에야 "성장주 목록에 왜 적자 기업이 있지"로 드러난다.
 */
class EpsGrowthTtmYoyCalculatorTest {

    private static final LocalDate START = LocalDate.of(2023, 1, 2);
    private static final int BARS = 300;

    private final EpsGrowthTtmYoyCalculator calculator = new EpsGrowthTtmYoyCalculator();

    @Test
    @DisplayName("주식수가 늘면 순이익 성장률보다 낮게 나온다 — 주식수가 약분되지 않는다")
    void dilutionLowersGrowthBelowNetIncomeGrowth() {
        // 순이익은 정확히 두 배. 주식수도 1년 사이 두 배가 됐다면 EPS는 그대로여야 한다.
        List<FinancialFigures> quarters = quarters(50, 100);
        List<PriceBar> bars = barsWithShares(1_000, 2_000);

        List<IndicatorPoint> points = calculator.calculate(context(bars, quarters));

        assertThat(points).isNotEmpty();
        // 주식수를 한쪽만 쓰면 약분되어 +100%가 나온다. 그것이 이 테스트가 막는 실패다.
        assertThat(points.getLast().value())
                .as("순이익 2배 · 주식수 2배면 주당순이익은 그대로다")
                .isEqualByComparingTo("0.00000000");
    }

    @Test
    @DisplayName("주식수가 그대로면 순이익 성장률과 같다")
    void withoutDilutionItMatchesNetIncomeGrowth() {
        List<FinancialFigures> quarters = quarters(50, 100);
        List<PriceBar> bars = barsWithShares(1_000, 1_000);

        List<IndicatorPoint> points = calculator.calculate(context(bars, quarters));

        assertThat(points.getLast().value()).isEqualByComparingTo("100.00000000");
    }

    @Test
    @DisplayName("직전 4개 분기가 적자면 값을 내지 않는다 — 적자 축소를 성장으로 읽지 않는다")
    void emitsNothingWhenThePriorBaseIsNotPositive() {
        // 직전 TTM −100, 최근 TTM −10. 절댓값 분모를 쓰면 +90%가 나온다.
        List<FinancialFigures> quarters = quarters(-25, -2.5);

        assertThat(calculator.calculate(context(barsWithShares(1_000, 1_000), quarters)))
                .as("적자 기저에서는 증감률이 뜻을 잃는다")
                .isEmpty();
    }

    @Test
    @DisplayName("분기가 여덟 개에 모자라면 값을 내지 않는다")
    void emitsNothingWithoutEightQuarters() {
        List<FinancialFigures> onlyFour = quarters(50, 100).subList(0, 4);

        assertThat(calculator.calculate(context(barsWithShares(1_000, 1_000), onlyFour)))
                .isEmpty();
    }

    // ── 픽스처 ────────────────────────────────────────────────────────────

    /**
     * 분기 여덟 개. 앞 넷(최근 TTM)이 {@code recentPerQuarter}, 뒤 넷(직전 TTM)이
     * {@code priorPerQuarter}다. 읽기와 같은 {@code period_end} 내림차순으로 만든다.
     */
    private static List<FinancialFigures> quarters(double priorPerQuarter,
                                                   double recentPerQuarter) {
        List<FinancialFigures> quarters = new ArrayList<>(8);
        LocalDate end = LocalDate.of(2024, 12, 31);
        for (int i = 0; i < 8; i++) {
            double perQuarter = i < 4 ? recentPerQuarter : priorPerQuarter;
            quarters.add(FinancialFiguresFixture.quarter(end.minusMonths(3L * i))
                    .netIncome(BigDecimal.valueOf(perQuarter))
                    // 계산식을 재는 시험이라 공시 시차를 두지 않는다(ADR-0128) —
                    // 봉마다 재무를 고르는 축은 IndicatorRunnerTest의 대조군이 갖는다.
                    .publishedAt(START.minusDays(1))
                    .build());
        }
        return quarters;
    }

    /** 앞쪽 절반은 {@code pastShares}, 뒤쪽 절반은 {@code nowShares}인 일봉. */
    private static List<PriceBar> barsWithShares(long pastShares, long nowShares) {
        List<PriceBar> bars = new ArrayList<>(BARS);
        for (int i = 0; i < BARS; i++) {
            BigDecimal price = BigDecimal.valueOf(1_000);
            BigDecimal shares = BigDecimal.valueOf(i < BARS / 2 ? pastShares : nowShares);
            bars.add(new PriceBar(START.plusDays(i), price, price, price, price, price,
                    1_000L, null, shares));
        }
        return bars;
    }

    private static IndicatorContext context(List<PriceBar> bars,
                                            List<FinancialFigures> quarters) {
        return new IndicatorContext(1L, bars, List.of(), quarters, Map.of());
    }
}
