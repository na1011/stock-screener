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
 * <p>한 해의 세후 투하자본이익률은 {@code 영업이익 × (1 − 그 해 유효세율) ÷ 그 해 기말
 * 투하자본}이다. {@link RoicAfterTaxCalculator}와 달리 <b>분자와 세율이 같은 회계연도</b>다 —
 * 연간 행에 법인세비용과 세전이익이 함께 있기 때문이고, 그래서 기간이 섞이지 않는다.
 *
 * <h2>MUST: 다섯 해가 연속인지 {@code period_end} 간격으로 잰다</h2>
 *
 * <p>행 수만 세면 한 해 빠진 종목이 통과하고, 그 종목은 <b>이력이 짧아서가 아니라 구멍
 * 때문에</b> 좋은 값을 받는다. 그리고 <b>다섯 해 전부에 값이 있어야 한다</b> — 세전이익이 0
 * 이하인 해가 하나라도 있으면 세율이 뜻을 잃어 그 해 값이 없고, 그러면 이 지표도 없다.
 * 실측으로 그 조건이 커버리지를 크게 정한다.
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
                + "다섯 해가 연속으로 있어야 하고, 세전이익이 적자인 해나 투하자본이 0 이하인 해가 "
                + "하나라도 있으면 값을 내지 않는다. "
                + "세율은 그 해의 것을 쓰므로 분자와 세율의 기간이 같다. "
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
        BigDecimal result = null;
        for (int i = 0; i < YEARS; i++) {
            FinancialFigures figures = annual.get(i);
            BigDecimal nopat = FinancialMath.nopatOf(figures);
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
