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
 * {@code TURNOVER_RATIO}가 <b>재무 폴백에 실제로 도달하는가</b>.
 *
 * <p>ADR-0097 §결정·§규칙과 {@code alpaca-bars.md}, 그리고 계산기 자신의 javadoc이 셋 다
 * 이 지표를 <b>재무 폴백 대상</b>으로 적었는데, {@code listedShares == null}을 먼저 보는 가드가
 * {@code sharesOf} <b>앞</b>에 남아 있어 미국에서 <b>0행</b>이었다. 그 커밋
 * ({@code 07ee195})은 {@code MarketCapCalculator}에는 가드를 {@code sharesOf} 뒤로 옮겼고
 * 여기만 안 고쳤다 — ADR-0097 §검증이 <b>대조군을 그 하나로만</b> 돌려서 안 잡혔다.
 *
 * <p><b>MUST: 세 갈래를 함께 잠근다.</b> 폴백 하나만 잠그면 「봉이 이긴다」가 깨져 국내가
 * 조용히 재무 주식수로 갈아타도 초록이고, 그 셋째가 없으면 주식수가 아예 없을 때 0이나
 * 무한이 나가는 것을 못 막는다.
 */
class TurnoverRatioFallbackTest {

    private static final LocalDate DAY = LocalDate.of(2026, 8, 28);

    /** 미국 봉 — {@code listedShares}가 null인 것이 이 픽스처의 전부다(실측 전량 NULL). */
    private static PriceBar bar(BigDecimal listedShares) {
        BigDecimal price = new BigDecimal("100");
        return new PriceBar(DAY, price, price, price, price, price, 20_000L, null, listedShares);
    }

    private static FinancialFigures quarter(String shares) {
        return FinancialFiguresFixture.quarter(LocalDate.of(2026, 6, 30))
                .publishedAt(LocalDate.of(2026, 8, 5))
                .sharesOutstanding(new BigDecimal(shares))
                .build();
    }

    private static IndicatorContext context(PriceBar bar, List<FinancialFigures> quarters) {
        return new IndicatorContext(
                1L, null, List.of(bar), List.of(), quarters, List.of(), Map.of());
    }

    @Test
    @DisplayName("봉에 상장주식수가 없으면 재무에서 폴백한다 — 미국이 이 갈래이고 그전엔 0행이었다")
    void fallsBackToFinancialsWhenBarHasNoShareCount() {
        List<IndicatorPoint> points = new TurnoverRatioCalculator()
                .calculate(context(bar(null), List.of(quarter("1000000"))));

        assertThat(points)
                .as("가드가 sharesOf 앞에 있으면 여기서 빈 목록이 나온다")
                .hasSize(1);
        assertThat(points.get(0).value())
                .as("거래량 20,000 ÷ 주식수 1,000,000 = 2%")
                .isEqualByComparingTo("2");
    }

    @Test
    @DisplayName("봉에 상장주식수가 있으면 그것이 이긴다 — 국내 동작은 안 바뀐다")
    void priceBarWins() {
        List<IndicatorPoint> points = new TurnoverRatioCalculator()
                .calculate(context(bar(new BigDecimal("2000000")), List.of(quarter("1000000"))));

        // 국내 543,735봉은 listed_shares가 전부 차 있다(NULL 0행 · 0인 행도 0행, 실측).
        // 이 단정이 깨지면 국내 회전율이 조용히 재무 주식수로 갈아탄 것이다.
        assertThat(points.get(0).value())
                .as("봉의 2,000,000을 쓰면 1%다. 2%가 나오면 재무가 이긴 것이다")
                .isEqualByComparingTo("1");
    }

    @Test
    @DisplayName("봉에도 재무에도 주식수가 없으면 값을 안 낸다 — 0으로 두면 회전율이 무한이 된다")
    void noValueWhenNeitherHasShareCount() {
        assertThat(new TurnoverRatioCalculator().calculate(context(bar(null), List.of())))
                .isEmpty();
    }
}
