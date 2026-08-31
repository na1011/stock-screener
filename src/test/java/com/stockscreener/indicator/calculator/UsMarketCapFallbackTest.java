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
 * 봉이 상장주식수를 주지 않는 시장에서 시가총액이 <b>나는지</b>를 잰다(ADR-0097).
 *
 * <p>미국 봉은 {@code listed_shares}가 전량 NULL이다(실측 2,772행). 이 폴백이 없으면
 * {@code MARKET_CAP}과 그것에 의존하는 {@code FCF_YIELD}·{@code NCAV_TO_MARKET}·
 * {@code ALTMAN_Z}가 미국에서 <b>영구 0행</b>이고, 시총 게이트를 가진 프리셋이 전부 0건이 된다.
 *
 * <p><b>국내가 안 바뀌는 것이 이 테스트의 다른 절반이다.</b> 국내 봉은 그 값이 차 있어
 * (실측 543,370행 중 NULL 0) 폴백을 타지 않는다 — 아래 둘째 단정이 그 갈래를 고정한다.
 */
class UsMarketCapFallbackTest {

    private static final LocalDate DAY = LocalDate.of(2026, 8, 28);
    private static final BigDecimal CLOSE = new BigDecimal("200");

    private final MarketCapCalculator calculator = new MarketCapCalculator();

    @Test
    @DisplayName("봉에 상장주식수가 없어도 재무에서 받아 값을 낸다 — 미국이 이 갈래다")
    void fallsBackToFinancialsWhenBarHasNoShares() {
        IndicatorContext context = context(null, new BigDecimal("1000000"));

        List<IndicatorPoint> points = calculator.calculate(context);

        assertThat(points).hasSize(1);
        assertThat(points.get(0).value())
                .as("종가 200 × 재무 주식수 1,000,000")
                .isEqualByComparingTo("200000000");
    }

    @Test
    @DisplayName("봉에 상장주식수가 있으면 그것이 이긴다 — 국내는 한 값도 안 바뀐다")
    void priceBarWinsWhenPresent() {
        IndicatorContext context = context(new BigDecimal("500"), new BigDecimal("1000000"));

        List<IndicatorPoint> points = calculator.calculate(context);

        assertThat(points).hasSize(1);
        assertThat(points.get(0).value())
                .as("봉의 500주를 쓴다 — 재무의 1,000,000주가 아니다")
                .isEqualByComparingTo("100000");
    }

    @Test
    @DisplayName("양쪽 다 없으면 값을 내지 않는다 — 0으로 두면 시총 0인 회사가 된다")
    void noValueWhenNeitherHasShares() {
        IndicatorContext context = context(null, null);

        // 0으로 채우면 어떤 하한 조건에도 안 걸려 조용히 결과에서 빠진다.
        assertThat(calculator.calculate(context)).isEmpty();
    }

    private static IndicatorContext context(BigDecimal barShares, BigDecimal financialShares) {
        PriceBar bar = new PriceBar(DAY, CLOSE, CLOSE, CLOSE, CLOSE, CLOSE, 1000L, null, barShares);
        List<FinancialFigures> quarters = financialShares == null
                ? List.of()
                : List.of(FinancialFiguresFixture.quarter(LocalDate.of(2026, 6, 30))
                        // 봉(2026-08-28)보다 앞선 공시일이어야 sharesOf가 이 분기를 고른다
                        // (ADR-0103). 기본 유도값 periodEnd+2개월은 봉보다 이틀 뒤다.
                        .publishedAt(LocalDate.of(2026, 8, 5))
                        .sharesOutstanding(financialShares).build());
        return new IndicatorContext(1L, List.of(bar), List.of(), quarters, Map.of());
    }
}
