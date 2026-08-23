package com.stockscreener.indicator.calculator;

import com.stockscreener.indicator.GovernanceFigures;
import com.stockscreener.indicator.GovernanceMetric;
import com.stockscreener.indicator.GovernanceValue;
import com.stockscreener.indicator.IndicatorContext;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;

/**
 * 거버넌스 사실을 봉에 맞춰 고른다. <b>계단 함수이지 시계열의 사본이 아니다</b>(ADR-0081).
 *
 * <p>IMPORTANT — {@code FinancialMath.spreadOverTradeDates}를 쓰지 않는 이유가 이 클래스의
 * 존재 이유다. 사업보고서는 회계연도가 끝난 <b>이듬해 3월</b>에 나온다(실측 공시일 중앙값
 * 2026-03-18). 최신 값 하나를 창 전체에 펴면 <b>공시 전 구간이 미래를 알고 계산한 값</b>이 되고,
 * P19의 할인율 셋이 이미 갖는 그 성질을 새로 하나 더 들이게 된다.
 *
 * <p>그 대가는 숫자로 적어 둔다 — 쓰기 창 180거래일 중 <b>74가 FY2025 공시일 중앙값 앞</b>이라
 * 그 구간에서는 최신 회계연도에만 서는 지표가 값을 내지 않는다. <b>결함이 아니라 그날 정말
 * 몰랐던 값이다.</b>
 */
public final class GovernanceMath {

    private GovernanceMath() {
    }

    /**
     * {@code tradeDate}에 <b>공시돼 있던</b> 회계연도 중 가장 최신의 값.
     *
     * <p>MUST: 반환형이 값이 아니라 {@link GovernanceValue}다. 공시일을 함께 주지 않으면
     * 배당수익률이 기업 행위를 걷어낼 수 없다 — 주당 배당금은 보고서 시점의 주식수 기준이고
     * 분모는 그날 종가라, 사이에 분할이 있으면 <b>배수만큼 틀린 채 그럴듯한 값</b>이 된다.
     *
     * @return 없으면 null. 판단은 호출자 몫이며 <b>없는 것을 0으로 접지 않는다</b>
     */
    public static GovernanceValue on(IndicatorContext context, GovernanceMetric metric,
                                     LocalDate tradeDate) {
        // 목록이 회계연도 내림차순이므로 앞에서부터 첫 성립이 곧 최신이다.
        for (GovernanceFigures figures : context.governanceFacts()) {
            GovernanceValue value = figures.get(metric);
            if (value != null && !value.publishedAt().isAfter(tradeDate)) {
                return value;
            }
        }
        return null;
    }

    /**
     * {@code tradeDate}에 공시돼 있던 <b>가장 최신 회계연도</b>. 값이 하나도 없으면 null이다.
     *
     * <p>여러 지표를 함께 읽는 계산기가 <b>같은 회계연도에서</b> 가져오게 하는 자리다 —
     * 지표마다 따로 고르면 분자와 분모가 다른 해가 되는데 값이 그럴듯해 안 드러난다.
     */
    public static GovernanceFigures figuresOn(IndicatorContext context, LocalDate tradeDate,
                                              GovernanceMetric anchor) {
        for (GovernanceFigures figures : context.governanceFacts()) {
            GovernanceValue value = figures.get(anchor);
            if (value != null && !value.publishedAt().isAfter(tradeDate)) {
                return figures;
            }
        }
        return null;
    }

    /**
     * {@code year}의 사실. <b>공시 시점을 보지 않는다</b> — 호출자가 이미
     * {@link #figuresOn}으로 창을 정한 뒤 그 창 안에서 과거 해를 찾을 때만 쓴다.
     */
    public static GovernanceFigures ofYear(IndicatorContext context, int fiscalYear) {
        for (GovernanceFigures figures : context.governanceFacts()) {
            if (figures.fiscalYear() == fiscalYear) {
                return figures;
            }
        }
        return null;
    }

    /**
     * 비율. 분모가 0 이하면 null이다.
     *
     * <p>MUST: 분모가 0 이하일 때 값을 내지 않는다. 적자 기업의 배당성향을 음수로 내면
     * 정렬에서 「배당을 아주 적게 준다」로 앉는데(활성 유니버스 최신 FY 340행 중 67이 적자다),
     * 그것은 {@code phase-criteria} P14의 <i>「증감률의 기저가 음수일 때 값을 내지 않는다」</i>와
     * 같은 병이다.
     */
    public static BigDecimal ratio(BigDecimal numerator, BigDecimal denominator, int scale) {
        if (numerator == null || denominator == null || denominator.signum() <= 0) {
            return null;
        }
        return numerator.divide(denominator, scale, RoundingMode.HALF_UP);
    }

    /** 백분율(%). 분모가 0 이하면 null이다. */
    public static BigDecimal percent(BigDecimal numerator, BigDecimal denominator) {
        BigDecimal ratio = ratio(numerator, denominator, FinancialMath.SCALE);
        return ratio == null ? null : ratio.multiply(BigDecimal.valueOf(100));
    }

    /**
     * 두 표가 말하는 총발행주식수가 서로 <b>맞는지</b> 확인하고 그 값을 낸다.
     *
     * <p>IMPORTANT — 표를 가로지르는 나눗셈에만 쓴다. 소스가 <b>단위를 잘못 낸 회사가 실재한다</b> —
     * 229640 LS에코에너지의 주식 총수 표가 발행주식수를 10<sup>6</sup>배로 실어 보낸다(소액주주 표는
     * 정상이다). 한쪽만 쓰면 그 종목의 최대주주 지분율이 <b>0.00006%</b>가 되는데 값이 작아
     * 눈에 안 띈다.
     *
     * <p>실측: FY2025에 둘 다 있는 286종목 중 <b>216이 완전히 같고 21이 5%를 넘게 어긋나며
     * 1이 50%를 넘는다.</b> 어긋나면 값을 내지 않는 쪽이 옳다 — 어느 쪽이 맞는지 우리가 모른다.
     */
    public static BigDecimal agreedTotalShares(GovernanceFigures figures) {
        GovernanceValue issued = figures.get(GovernanceMetric.ISSUED_SHARES_COMMON);
        GovernanceValue minorityTotal = figures.get(GovernanceMetric.MINORITY_TOTAL_SHARES);
        if (issued == null) {
            return minorityTotal == null ? null : minorityTotal.value();
        }
        if (minorityTotal == null) {
            return issued.value();
        }
        BigDecimal gap = issued.value().subtract(minorityTotal.value()).abs();
        BigDecimal tolerance = minorityTotal.value().multiply(new BigDecimal("0.05"));
        return gap.compareTo(tolerance) > 0 ? null : minorityTotal.value();
    }

    /** 몇 %인가를 그대로 두되 표시 자릿수만 줄인다. */
    public static BigDecimal scaled(BigDecimal value) {
        return value == null ? null : value.setScale(FinancialMath.SCALE, RoundingMode.HALF_UP);
    }

    /** 계산기가 봉 목록을 받아 결과를 담을 때 쓰는 빈 목록. */
    public static <T> List<T> none() {
        return List.of();
    }
}
