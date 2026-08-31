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
 * <p>미국 {@code price_daily}는 {@code listed_shares}가 1,680,969행 전부 NULL이라(실측
 * 2026-08-31) {@link FinancialMath}의 재무 폴백을 탄다. 그 폴백이 <b>{@code quarters.get(0)}
 * 한 행을 어느 봉에나 쓰던 동안</b> {@code SHARES_GROWTH_3Y}는 <b>항등 0</b>이었다 —
 * {@code shiftToBar}의 배수가 {@code SharesGrowth3yCalculator}의
 * {@code corporateActionMultiple}과 구간 가법성으로 정확히 상쇄되기 때문이다.
 * ADR-0103이 그것을 <b>봉 시점에 공시돼 있던 분기</b>를 고르는 것으로 바꿨다.
 *
 * <p>{@code SHARES_GROWTH_3Y}는 {@code VALUE}·{@code BUFFETT}·{@code LYNCH_FAST_GROWER}·
 * {@code LYNCH_STALWART} <b>넷의 배점 조건</b>이다. 상수 0이면 미국 종목 전부가
 * 「희석하지 않은 회사」 점수를 공짜로 받는다 — {@code PresetCatalog}이 {@code BETA_104W}에
 * 대해 <i>"축이 아니라 상수가 된다"</i>고 경고한 그 상태다.
 *
 * <p><b>MUST: 세 시험을 함께 읽는다.</b> 첫째는 <b>분포가 생기는 것</b>을, 둘째는
 * <b>그 시점에 공시된 분기가 없으면 값을 안 내는 것</b>을, 셋째는 <b>대조군</b>으로
 * 계산기를 실제로 타고 있다는 것을 잠근다. 첫째만 두면 「값을 안 내는 쪽」으로 걸린 처방과
 * 「제대로 고르는 쪽」으로 걸린 처방이 <b>구분되지 않는다</b> — 실제로 처음 고쳤을 때
 * 이 시험이 <b>빈 결과</b>로 붉었고, 그것은 분포가 생겼다는 증거가 아니었다.
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

    private static IndicatorContext usContext(List<FinancialFigures> quarters) {
        List<PriceBar> bars = new ArrayList<>(BARS);
        LocalDate d = START;
        for (int i = 0; i < BARS; i++) {
            bars.add(usBar(d));
            d = d.plusDays(1);
        }
        // 기업행위 원장이 비어 있다 — 미국 원장이 US_TOP1000 안에서 93종목뿐이라
        // 907종목이 이 형태다(실측 2026-08-31).
        return new IndicatorContext(1L, null, bars, List.of(), quarters, List.of(), Map.of());
    }

    /**
     * {@code period_end} 내림차순 — {@code IndicatorContext}의 계약이다. 공시일과 주식수가
     * <b>분기마다 다른</b> 것이 이 픽스처의 요점이다. 하나뿐이면
     * {@code publishedIndexOn}이 과거 봉에서 −1을 내 <b>빈 결과</b>가 되고,
     * 그러면 분포를 재는 것이 아니라 침묵을 재게 된다.
     */
    private static List<FinancialFigures> quarterlySeries() {
        // 값이 나는 구간은 nowBar 2025-01-21~03-11 · pastBar 2023-01-02~02-20이다(봉 800개,
        // LOOKBACK 750). 공시일 하나를 **pastBar 구간 안**(2023-01-20)에 두어야 과거 끝이
        // 도중에 갈리고 값이 여럿이 된다 — 전부 창 밖에 두면 값이 하나로 뭉쳐
        // 「분포가 생겼다」를 못 잰다.
        return List.of(
                quarter(LocalDate.of(2024, 9, 30), LocalDate.of(2024, 11, 5), "1270000"),
                quarter(LocalDate.of(2024, 6, 30), LocalDate.of(2024, 8, 6), "1200000"),
                quarter(LocalDate.of(2022, 12, 31), LocalDate.of(2023, 1, 20), "1100000"),
                quarter(LocalDate.of(2022, 6, 30), LocalDate.of(2022, 8, 5), "1000000"));
    }

    private static FinancialFigures quarter(
            LocalDate periodEnd, LocalDate publishedAt, String shares) {
        return FinancialFiguresFixture.quarter(periodEnd)
                .publishedAt(publishedAt)
                .sharesOutstanding(new BigDecimal(shares))
                .build();
    }

    @Test
    @DisplayName("봉에 상장주식수가 없어도 분기마다 다른 수를 골라 실제 희석을 잰다 — 상수가 아니다")
    void sharesGrowthBecomesAnAxisWhenQuartersDiffer() {
        List<IndicatorPoint> points =
                new SharesGrowth3yCalculator().calculate(usContext(quarterlySeries()));

        assertThat(points)
                .as("750봉을 넘겼고 과거 봉 시점에 공시된 분기가 있으므로 값이 난다")
                .isNotEmpty();
        assertThat(points.stream().map(IndicatorPoint::value).distinct().toList())
                .as("봉마다 고른 분기가 달라지므로 값이 여럿이다 — 이것이 축이라는 증거다")
                .hasSizeGreaterThan(1);
        assertThat(points.stream().map(IndicatorPoint::value))
                .as("하나라도 0이 아니어야 한다. 전부 0이면 상쇄가 그대로인 것이다")
                .anySatisfy(v -> assertThat(v.signum()).isNotZero());
        assertThat(points.get(0).value())
                .as("첫 봉의 과거 끝은 2022-06-30 공시분이라 1,000,000 → 1,270,000 = 27%다")
                .isEqualByComparingTo("27");
        assertThat(points.get(points.size() - 1).value())
                .as("마지막 봉의 과거 끝은 2022-12-31 공시분으로 갈려 1,100,000 → 1,270,000이다")
                .isEqualByComparingTo("15.45454545");
    }

    @Test
    @DisplayName("그 봉 시점에 공시된 분기가 없으면 값을 안 낸다 — 최신 분기를 되돌려 쓰지 않는다")
    void producesNothingWhenNoQuarterWasPublishedYet() {
        // 분기가 하나뿐이고 공시일이 창의 끝이라, 과거 봉에는 그날 알 수 있었던 수가 없다.
        List<IndicatorPoint> points = new SharesGrowth3yCalculator().calculate(
                usContext(List.of(quarter(
                        LocalDate.of(2024, 12, 31), LocalDate.of(2025, 2, 28), "1000000"))));

        assertThat(points)
                .as("최신 분기를 과거 봉에 되돌려 쓰면 미래를 알고 계산한 값이 된다")
                .isEmpty();
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
