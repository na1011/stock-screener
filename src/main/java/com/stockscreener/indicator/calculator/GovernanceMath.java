package com.stockscreener.indicator.calculator;

import com.stockscreener.indicator.FinancialFigures;
import com.stockscreener.indicator.GovernanceFigures;
import com.stockscreener.indicator.GovernanceMetric;
import com.stockscreener.indicator.GovernanceValue;
import com.stockscreener.indicator.IndicatorContext;
import com.stockscreener.indicator.IndicatorPoint;
import com.stockscreener.indicator.PriceBar;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;

/**
 * 거버넌스 사실을 봉에 맞춰 고른다. <b>계단 함수이지 시계열의 사본이 아니다</b>(ADR-0081).
 *
 * <p>IMPORTANT — 재무 값 하나를 창 전체에 펴지 않는 이유가 이 클래스의
 * 존재 이유다. 사업보고서는 회계연도가 끝난 <b>이듬해 3월</b>에 나온다(실측 공시일 중앙값
 * 2026-03-18). 최신 값 하나를 창 전체에 펴면 <b>공시 전 구간이 미래를 알고 계산한 값</b>이 되고,
 * P19의 할인율 셋이 이미 갖는 그 성질을 새로 하나 더 들이게 된다.
 *
 * <p>그 대가는 숫자로 적어 둔다(2026-09-08 실측 · <b>창이 매일 미끄러지므로 그날의 수다</b>) —
 * 쓰기 창 255거래일 중 137이 FY2025 공시일 중앙값 앞이다. 그 구간에서는 최신 회계연도에만
 * 서는 지표가 값을 내지 않는다. <b>결함이 아니라 그날 정말
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
     * {@code fiscalYear}의 사실 중 그 거래일에 <b>공시돼 있던</b> 가장 최신 판.
     *
     * <p>MUST: 거래일을 받는다. 안 받으면 과거 해를 찾을 때 <b>아직 안 나온 보고서의 판</b>을
     * 집어 차분과 합이 미래를 알고 계산한 값이 된다 — 목록이 공시일 내림차순이라 그 실수가
     * 조용히 성립한다.
     */
    public static GovernanceFigures ofYear(IndicatorContext context, int fiscalYear,
                                           LocalDate tradeDate, GovernanceMetric metric) {
        for (GovernanceFigures figures : context.governanceFacts()) {
            if (figures.fiscalYear() != fiscalYear) {
                continue;
            }
            GovernanceValue value = figures.get(metric);
            if (value != null && !value.publishedAt().isAfter(tradeDate)) {
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

    /**
     * 봉마다 <b>그날 공시돼 있던 회계연도</b>를 골라 값을 낸다. 거버넌스 계산기의 공용 뼈대다.
     *
     * <p>MUST: 한 봉의 모든 입력을 <b>같은 {@link GovernanceFigures}</b>에서 가져간다. 지표마다
     * 따로 고르면 분자와 분모가 다른 해가 되는데 값이 그럴듯해 드러나지 않는다.
     *
     * @param anchor 그 봉에 값이 성립하는지 정하는 지표. 대개 분모다
     * @param value  {@code (그 해의 사실, 봉)} → 값. 낼 수 없으면 null을 돌려준다
     */
    public static List<IndicatorPoint> overBars(
            IndicatorContext context, GovernanceMetric anchor,
            java.util.function.BiFunction<GovernanceFigures, PriceBar, BigDecimal> value) {
        List<IndicatorPoint> points = new java.util.ArrayList<>();
        for (PriceBar bar : context.priceBars()) {
            GovernanceFigures figures = figuresOn(context, bar.tradeDate(), anchor);
            if (figures == null) {
                continue;
            }
            BigDecimal computed = value.apply(figures, bar);
            if (computed != null) {
                points.add(new IndicatorPoint(bar.tradeDate(), computed));
            }
        }
        return points;
    }

    /** 그 해의 값. 없으면 null이다. */
    public static BigDecimal valueOf(GovernanceFigures figures, GovernanceMetric metric) {
        GovernanceValue found = figures == null ? null : figures.get(metric);
        return found == null ? null : found.value();
    }

    /**
     * {@code years}년 전 회계연도와의 <b>차분</b>. 두 해가 다 있어야 값을 낸다.
     *
     * <p>MUST: 누적값에 이것을 쓴다. 이익소각은 <b>상장 이래 누적</b>이라 그대로 한 해치로 읽으면
     * 상장 이래 전량이 그 해에 소각된 것으로 나온다.
     *
     * <p>한 해라도 없으면 <b>부분 차분을 내지 않는다.</b> 이름이 {@code _3Y}인데 2년치를 세면
     * {@code phase-criteria}의 <i>「지표 이름이 그 값이 실제로 세는 것을 말한다」</i>를 어긴다.
     */
    public static BigDecimal difference(IndicatorContext context, GovernanceFigures figures,
                                        GovernanceMetric metric, int years, LocalDate tradeDate) {
        BigDecimal now = valueOf(figures, metric);
        BigDecimal past = valueOf(
                ofYear(context, figures.fiscalYear() - years, tradeDate, metric), metric);
        return now == null || past == null ? null : now.subtract(past);
    }

    /**
     * 최근 {@code years}개 회계연도의 <b>합</b>. 그 해들이 다 있어야 값을 낸다.
     *
     * <p>{@link #difference}와 같은 이유로 부분 합을 내지 않는다.
     */
    public static BigDecimal sumOfYears(IndicatorContext context, GovernanceFigures figures,
                                        GovernanceMetric metric, int years, LocalDate tradeDate) {
        BigDecimal total = BigDecimal.ZERO;
        for (int offset = 0; offset < years; offset++) {
            BigDecimal value = valueOf(
                    ofYear(context, figures.fiscalYear() - offset, tradeDate, metric), metric);
            if (value == null) {
                return null;
            }
            total = total.add(value);
        }
        return total;
    }

    /**
     * 그 회계연도의 연간 재무. 없으면 null이다.
     *
     * <p>거버넌스 값을 재무로 나누는 지표가 <b>같은 해를 짝지어야</b> 하는 자리다 — 최신 재무를
     * 그냥 쓰면 배당성향의 분자가 FY2024인데 분모가 FY2025가 되고 값이 그럴듯하다.
     *
     * <p><b>관문 켬이면 그 봉 시점에 공시돼 있던 판을 짝짓는다</b>({@link IndicatorContext#visibleAt} — ADR-0128). 꺼짐은 실행 시점의
     * 최신 판이라 과거 봉에 나중 정정 순이익이 실렸다(관문 재고 「`annualOf` 시점 위반」 — 미국 판마다 순이익이 다른 라벨 254·169 ·
     * 국내 12·9). 값을 바꾸므로 관문 뒤에 둔다. 새 분기 발표로 막지 않는다 — 짝이 연도로 고정돼 새 분기가 그 해 FY를 낡게 하지 않는다
     * ({@code FinancialMath.financialsAt}의 예외 · 주식수만 읽는 지표와 같은 근거).
     */
    public static FinancialFigures annualOf(IndicatorContext context, int fiscalYear, LocalDate barDate) {
        IndicatorContext source = context.gateOn() ? context.visibleAt(barDate) : context;
        for (FinancialFigures figures : source.annualFigures()) {
            if (figures.fiscalYear() == fiscalYear
                    && figures.fiscalPeriod() == FinancialFigures.FiscalPeriod.FY) {
                return figures;
            }
        }
        return null;
    }
}
