package com.stockscreener.indicator.calculator;

import com.stockscreener.indicator.FinancialFigures;
import com.stockscreener.indicator.IndicatorContext;
import com.stockscreener.indicator.PriceBar;
import com.stockscreener.indicator.ShareAdjustment;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 재무에서 폴백한 주식수를 <b>봉 시점으로 옮기는 것</b>을 잰다(ADR-0096).
 *
 * <p>실측에서 나온 자리다. NVDA는 2024-06-10에 10:1 분할했고 종가가 {@code 1,208.88 → 121.79}로
 * 바뀌는데, 저장된 재무는 {@code period_end 2024-04-28}의 <b>2,460,000,000</b>(분할 전)이다.
 * 미국 봉은 {@code listed_shares}가 전량 NULL이라 이 폴백을 타므로, 보정하지 않으면
 * <b>분할 뒤 약 55거래일의 시가총액이 정확히 1/10</b>이 된다. 결측이 아니라 그럴듯한 숫자다.
 *
 * <p><b>양방향이라는 것이 이 테스트의 요점이다.</b> 재무 스냅샷 하나를 읽기 창 전체에 펴는 것이
 * 이 프로젝트의 계약이라 봉은 보고 종료일보다 앞일 수도 뒤일 수도 있다 — 한쪽만 보정하면
 * 반대쪽이 배수만큼 틀린 채 남는다.
 *
 * <p>대조군: {@code shiftToBar}를 걷고 {@code fromFinancials}를 그대로 돌려주면 아래 둘이 붉는다.
 */
class FinancialSharesSplitTest {

    /** NVDA 실측. 효력일과 신주상장일이 같은 분할이다(원장 id=99, APPLIED). */
    private static final LocalDate SPLIT = LocalDate.of(2024, 6, 10);
    private static final BigDecimal RATIO = new BigDecimal("10");

    private static final LocalDate BEFORE_SPLIT = LocalDate.of(2024, 6, 7);
    private static final LocalDate AFTER_SPLIT = LocalDate.of(2024, 6, 11);

    private static final BigDecimal PRE_SPLIT_SHARES = new BigDecimal("2460000000");
    private static final BigDecimal POST_SPLIT_SHARES = new BigDecimal("24530000000");

    @Test
    @DisplayName("봉이 보고 종료일보다 뒤면 그 사이 분할을 곱한다")
    void multipliesWhenBarIsAfterReport() {
        // period_end 2024-04-28의 분할 전 주식수를 분할 뒤 봉에 쓴다.
        FinancialFigures q = quarter(
                LocalDate.of(2024, 4, 28), LocalDate.of(2024, 5, 29), PRE_SPLIT_SHARES);
        IndicatorContext context = context(AFTER_SPLIT, q);

        BigDecimal shares = FinancialMath.sharesOf(context, bar(AFTER_SPLIT), List.of(q));

        // 보정 없이는 2.46B이고, 분할 후 종가와 곱하면 시가총액이 정확히 1/10이 된다.
        assertThat(shares).isEqualByComparingTo("24600000000");
    }

    @Test
    @DisplayName("기말 전에 공시된 판은 봉보다 뒤 기말이라 나눈다 — 이 갈래가 남는 유일한 이유다")
    void dividesWhenBarIsBeforeReport() {
        // period_end 2024-07-28의 분할 후 주식수를 분할 전 봉에 쓴다.
        //
        // ADR-0103 뒤 이 갈래에 닿으려면 **기말보다 먼저 공시된 판**이어야 한다. 고른 판의
        // 기말이 봉보다 앞이면 언제나 곱셈으로 가기 때문이다. 리더가 그런 행을 5행 세어
        // 두었고, 그 5행에서 보정을 빼면 배수만큼 틀린 값이 조용히 나온다.
        FinancialFigures q = quarter(
                LocalDate.of(2024, 7, 28), LocalDate.of(2024, 6, 1), POST_SPLIT_SHARES);
        IndicatorContext context = context(BEFORE_SPLIT, q);

        BigDecimal shares = FinancialMath.sharesOf(context, bar(BEFORE_SPLIT), List.of(q));

        // 보정 없이는 24.53B이고, 분할 전 종가(1,208.88)와 곱하면 시가총액이 10배가 된다.
        assertThat(shares).isEqualByComparingTo("2453000000");
    }

    @Test
    @DisplayName("두 시점 사이에 사건이 없으면 그대로다 — 곱셈이 스케일을 흔들지 않는다")
    void unchangedWhenNoActionBetween() {
        FinancialFigures q = quarter(
                LocalDate.of(2024, 4, 28), LocalDate.of(2024, 5, 29), PRE_SPLIT_SHARES);
        IndicatorContext context = context(BEFORE_SPLIT, q);

        // 봉이 분할 전이므로 그 사이에 사건이 없다.
        assertThat(FinancialMath.sharesOf(context, bar(BEFORE_SPLIT), List.of(q)))
                .isEqualByComparingTo(PRE_SPLIT_SHARES);
    }

    @Test
    @DisplayName("봉에 상장주식수가 있으면 폴백을 타지 않는다 — 국내가 이 갈래다")
    void priceBarWins() {
        FinancialFigures q = quarter(
                LocalDate.of(2024, 4, 28), LocalDate.of(2024, 5, 29), PRE_SPLIT_SHARES);
        IndicatorContext context = context(AFTER_SPLIT, q);
        PriceBar withShares = new PriceBar(AFTER_SPLIT, ten(), ten(), ten(), ten(), ten(),
                1000L, null, new BigDecimal("777"));

        // 국내 543,735봉은 listed_shares가 전부 차 있다(실측 NULL 0행 · 0인 행도 0). 이 단정이 깨지면
        // 국내 지표가 재무 주식수로 갈아탄 것이고, 그것은 조용히 다른 값이다.
        assertThat(FinancialMath.sharesOf(context, withShares, List.of(q)))
                .isEqualByComparingTo("777");
    }

    private static IndicatorContext context(LocalDate barDate, FinancialFigures quarter) {
        return new IndicatorContext(
                1L, null, List.of(bar(barDate)), List.of(), List.of(quarter),
                List.of(new ShareAdjustment(SPLIT, SPLIT, RATIO)),
                List.of(), new TreeMap<>(), List.of(), Map.of());
    }

    private static PriceBar bar(LocalDate date) {
        // listed_shares가 null인 것이 미국 봉의 모양이다(1,680,969행 전량 NULL, 실측).
        return new PriceBar(date, ten(), ten(), ten(), ten(), ten(), 1000L, null, null);
    }

    private static BigDecimal ten() {
        return BigDecimal.TEN;
    }

    /**
     * <b>공시일을 명시한다.</b> 픽스처의 기본 유도값이 {@code periodEnd + 2개월}이라 아래 봉들보다
     * 뒤인데, {@code sharesOf}는 <b>봉 시점에 공시돼 있던</b> 분기만 고른다(ADR-0103). 기본값을
     * 그대로 쓰면 세 시험이 「그 시점에 공시된 분기가 없다」로 떨어져 <b>보정이 아니라 시점
     * 선택을 재게 된다.</b>
     */
    private static FinancialFigures quarter(
            LocalDate periodEnd, LocalDate publishedAt, BigDecimal shares) {
        return FinancialFiguresFixture.quarter(periodEnd)
                .publishedAt(publishedAt)
                .sharesOutstanding(shares)
                .build();
    }
}
