package com.stockscreener.indicator.calculator;

import com.stockscreener.indicator.DataRequirement;
import com.stockscreener.indicator.FinancialFigures;
import com.stockscreener.indicator.IndicatorCalculator;
import com.stockscreener.indicator.IndicatorCategory;
import com.stockscreener.indicator.IndicatorContext;
import com.stockscreener.indicator.IndicatorMeta;
import com.stockscreener.indicator.IndicatorPoint;
import com.stockscreener.indicator.IndicatorValueType;

import java.math.BigDecimal;
import java.util.List;

import org.springframework.stereotype.Component;

/**
 * 5년 최저 ROIC(세후) = 최근 5개 회계연도 세후 투하자본이익률의 가장 낮은 값.
 *
 * <p>한 해의 세후 투하자본이익률은 {@code 영업이익 × (1 − 정규화 세율) ÷ 그 해 기말
 * 투하자본}이다. <b>세율은 다섯 해에 걸쳐 하나</b>이고 {@link RoicAfterTaxCalculator}와 같은
 * 값을 쓴다 — 그래야 TTM과 5년 계열이 같은 세율 위에 서고, 화면에서 나란히 볼 때 어긋나지
 * 않는다.
 *
 * <h2>MUST: 다섯 해가 연속인지 {@code period_end} 간격으로 잰다</h2>
 *
 * <p>행 수만 세면 한 해 빠진 종목이 통과하고, 그 종목은 <b>이력이 짧아서가 아니라 구멍
 * 때문에</b> 좋은 값을 받는다.
 *
 * <p><b>MUST NOT: 세전이익이 0 이하인 해를 결측으로 만들지 않는다.</b> 세율은 그 회사의
 * 정규화 세율 하나이므로 <b>그 해에 없는 것은 세율이지 영업이익이 아니다.</b> 예전에 해마다
 * 세율을 구하던 판에서는 그 조건 하나가 값이 없던 271종목 중 198을 만들었고, 그중 69는
 * <b>딱 한 해</b>가 세전 적자였다. 근거는 {@code FinancialMath.normalizedTaxRate}에 있다.
 *
 * <p>MUST NOT: 연간 영업이익을 {@link FinancialMath#sumTrailingFourQuarters}로 합하지 않는다.
 * 연간 행의 영업이익은 <b>이미 그 회계연도 전체</b>이고 자산·유동부채는 <b>그 해 기말 잔액</b>
 * 이라, 한 행에서 분자와 분모를 함께 뽑는 것이 옳다. 합하면 5년 누적 이익을 1년 자본으로
 * 나누게 되는데 <b>값이 그럴듯해 드러나지 않는다.</b>
 *
 * <p>MUST NOT: 투하자본이 0 이하인 해를 그대로 나누지 않는다. 영업적자가 <b>양수</b>로 나온다.
 *
 * <p>금융업에서 값이 없는 것은 {@code ROIC_PRETAX}와 같은 이유다 — 분모의 재료가 오지 않는다.
 */
@Component
@IndicatorMeta(
        name = "5년 최저 ROIC(세후)",
        description = "최근 5개 회계연도 각각의 세후 투하자본이익률 가운데 가장 낮은 값. "
                + "다섯 해가 연속으로 있어야 하고, 투하자본이 0 이하인 해가 하나라도 있으면 "
                + "값을 내지 않는다. "
                + "세율은 그 회사의 5년 유효세율 중앙값 하나를 다섯 해에 똑같이 적용한다 — "
                + "세전이익이 적자인 해도 영업이익이 있으면 값이 나온다. "
                + "유동부채를 구분하지 않는 금융업에서는 값이 나오지 않는다.",
        interpretation = "높을수록 좋은 해뿐 아니라 나쁜 해에도 세금까지 내고 자본의 수익성을 "
                + "유지했다는 뜻이다. 한 시점 값은 일회성 이익으로 한 해만 튄 회사를 걸러내지 못하는데, "
                + "이 값은 다섯 해 중 가장 나쁜 해를 보므로 그 함정에 걸리지 않는다. "
                + "세후 투하자본이익률보다 뚜렷하게 낮으면 최근 실적이 과거보다 좋아졌다는 뜻이기도 하다.",
        category = IndicatorCategory.FUNDAMENTAL,
        valueType = IndicatorValueType.PERCENT,
        unit = "%",
        direction = IndicatorMeta.Direction.HIGHER_IS_BETTER,
        sortOrder = 58,
        bands = {
                @IndicatorMeta.Band(label = "매우 높음", from = 12),
                @IndicatorMeta.Band(label = "우수", from = 8, to = 12),
                @IndicatorMeta.Band(label = "양호", from = 4, to = 8),
                @IndicatorMeta.Band(label = "낮음", from = 0, to = 4),
                @IndicatorMeta.Band(label = "영업적자 해가 있다", to = 0)
        }
)
public class RoicAfterTaxMin5yCalculator implements IndicatorCalculator {

    public static final String CODE = "ROIC_AFTERTAX_MIN_5Y";

    /** {@code OPM_MIN_5Y}와 같은 회계연도 수. 두 값이 같은 창을 봐야 대비가 뜻을 갖는다. */
    static final int YEARS = 5;

    @Override
    public String code() {
        return CODE;
    }

    @Override
    public DataRequirement dataRequirement() {
        // MUST NOT: financialYears만 선언하지 않는다. priceBarsNeeded가 0이면
        // UnadjustedActionGuard가 창 0으로 불려 미보정 기업행위 억제(ADR-0055)가 무효가 된다.
        // +1은 창 앞머리에서 최신 FY가 아직 공시되지 않아 한 해가 미끄러지는 것을 받는다.
        return DataRequirement.priceAndFinancial(1, YEARS + 1);
    }

    @Override
    public List<IndicatorPoint> calculate(IndicatorContext context) {
        return FinancialMath.byPublication(context, RoicAfterTaxMin5yCalculator::valueOf);
    }

    /** @return 다섯 해가 연속하고 모두 값이 있을 때만. 아니면 null */
    private static BigDecimal valueOf(IndicatorContext context) {
        List<FinancialFigures> annual = context.annualFigures();
        if (!FinancialMath.isConsecutiveAnnual(annual, YEARS)) {
            return null;
        }
        // MUST: 세율은 해마다 구하지 않고 그 회사의 정규화 세율 하나를 쓴다. 해마다 구하면
        // 세전이익이 0 이하인 해에서 null이 되어 **다섯 해가 통째로 빠진다** — 실측으로 값이
        // 없던 271종목 중 198이 그 갈래였고 69는 딱 한 해가 적자였다. 근거는
        // FinancialMath.normalizedTaxRate의 javadoc에 있다.
        BigDecimal rate = FinancialMath.normalizedTaxRate(annual, YEARS);
        if (rate == null) {
            return null;
        }
        BigDecimal result = null;
        for (int i = 0; i < YEARS; i++) {
            FinancialFigures figures = annual.get(i);
            BigDecimal nopat = FinancialMath.nopatOf(figures, rate);
            BigDecimal assets = figures.totalAssets();
            BigDecimal currentLiabilities = figures.currentLiabilities();
            if (nopat == null || assets == null || currentLiabilities == null) {
                return null;
            }
            BigDecimal investedCapital = assets.subtract(currentLiabilities);
            if (investedCapital.signum() <= 0) {
                // 분모가 음수면 영업적자가 양수로 나온다. 위 javadoc 참조.
                return null;
            }
            BigDecimal yearly = FinancialMath.percentage(nopat, investedCapital);
            result = result == null || yearly.compareTo(result) < 0 ? yearly : result;
        }
        return result;
    }
}
