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
 * 미국 형태의 입력(<b>봉의 상장주식수가 전량 NULL</b>)에서 주식수 기반 지표가 무엇을 내는가.
 *
 * <p><b>이 시험은 지금 결함을 재현한다.</b> 미국 {@code price_daily}는 {@code listed_shares}가
 * 1,680,969행 전부 NULL이라(실측 2026-08-31) {@link FinancialMath}의 재무 폴백을 타는데,
 * 그 폴백이 보는 것은 <b>{@code quarters.get(0)} 한 행</b>이다. 창 전체가 같은 주식수를 받으므로
 * <b>희석을 재는 지표가 축이 아니라 상수가 된다.</b>
 *
 * <p>{@code SHARES_GROWTH_3Y}는 {@code VALUE}·{@code BUFFETT}·{@code LYNCH_FAST_GROWER}·
 * {@code LYNCH_STALWART} <b>넷의 배점 조건</b>이다. 상수 0이면 미국 종목 전부가
 * 「희석하지 않은 회사」 점수를 공짜로 받는다 — {@code PresetCatalog}이 {@code BETA_104W}에
 * 대해 <i>"축이 아니라 상수가 된다"</i>고 경고한 그 상태다.
 *
 * <p><b>MUST: 고칠 때 이 시험을 뒤집는다.</b> 값이 0이 아니게 되는 것이 고쳤다는 증거이고,
 * 그때 이 시험의 이름과 단언을 함께 바꾼다. 지금 형태로 남겨 두는 이유는 <b>대조군</b>이
 * 필요해서다 — 처방을 넣었는데 값이 그대로면 처방이 안 걸린 것이다.
 */
class UsSharesResolutionTest {

    /** SHARES_GROWTH_3Y의 LOOKBACK(750봉)을 넘겨야 값이 난다. */
    private static final int BARS = 800;
    private static final LocalDate START = LocalDate.of(2023, 1, 2);

    /** 미국 봉 — {@code listedShares}가 null인 것이 이 픽스처의 전부다. */
    private static PriceBar usBar(LocalDate date) {
        BigDecimal price = new BigDecimal("100");
        return new PriceBar(date, price, price, price, price, price, 1_000L, null, null);
    }

    private static IndicatorContext usContext(BigDecimal sharesOutstanding) {
        List<PriceBar> bars = new ArrayList<>(BARS);
        LocalDate d = START;
        for (int i = 0; i < BARS; i++) {
            bars.add(usBar(d));
            d = d.plusDays(1);
        }
        List<FinancialFigures> quarters = List.of(
                FinancialFiguresFixture.quarter(LocalDate.of(2024, 12, 31))
                        .sharesOutstanding(sharesOutstanding)
                        .build());
        // 기업행위 원장이 비어 있다 — 실측으로 편입 1,000 중 911종목이 그 상태다.
        return new IndicatorContext(1L, null, bars, List.of(), quarters, List.of(), Map.of());
    }

    @Test
    @DisplayName("결함 재현 — 봉에 상장주식수가 없으면 SHARES_GROWTH_3Y가 항등 0이다")
    void sharesGrowthIsConstantZeroWhenBarsCarryNoShareCount() {
        List<IndicatorPoint> points =
                new SharesGrowth3yCalculator().calculate(usContext(new BigDecimal("1000000")));

        assertThat(points)
                .as("750봉을 넘겼으므로 값 자체는 나온다")
                .isNotEmpty();
        assertThat(points.stream().map(IndicatorPoint::value).distinct().toList())
                .as("창 전체가 같은 주식수를 받아 값이 하나뿐이다 — 축이 아니라 상수다")
                .hasSize(1);
        assertThat(points.get(points.size() - 1).value())
                .as("과거와 현재가 같은 수라 증가율이 0이 된다. 고치면 이 단언이 깨진다")
                .isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("대조군 — 봉이 상장주식수를 들면 같은 계산기가 실제 희석을 잰다")
    void sameCalculatorMeasuresRealDilutionWhenBarsCarryShareCount() {
        List<PriceBar> bars = new ArrayList<>(BARS);
        LocalDate d = START;
        BigDecimal price = new BigDecimal("100");
        for (int i = 0; i < BARS; i++) {
            // 앞은 1,000,000주, 뒤는 1,270,000주 — 27% 희석
            BigDecimal shares = new BigDecimal(i < BARS / 2 ? "1000000" : "1270000");
            bars.add(new PriceBar(d, price, price, price, price, price, 1_000L, null, shares));
            d = d.plusDays(1);
        }
        IndicatorContext context = new IndicatorContext(
                1L, null, bars, List.of(), List.of(), List.of(), Map.of());

        List<IndicatorPoint> points = new SharesGrowth3yCalculator().calculate(context);

        assertThat(points.get(points.size() - 1).value())
                .as("이 픽스처에서 값이 0이면 시험이 계산기를 안 타고 있는 것이다")
                .isEqualByComparingTo("27");
    }
}
