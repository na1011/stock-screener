package com.stockscreener.indicator.calculator;

import com.stockscreener.indicator.DataRequirement;
import com.stockscreener.indicator.FinancialFigures;
import com.stockscreener.indicator.IndicatorCalculator;
import com.stockscreener.indicator.IndicatorCategory;
import com.stockscreener.indicator.IndicatorContext;
import com.stockscreener.indicator.IndicatorMeta;
import com.stockscreener.indicator.IndicatorPoint;
import com.stockscreener.indicator.IndicatorValueType;

import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 매출 성장률 = (최근 연간 매출 − 직전 연간 매출) ÷ |직전 연간 매출| × 100.
 *
 * <p><b>연간(FY) 재무를 쓴다.</b> 분기 TTM으로 계산할 수도 있지만, 연간끼리 비교하는 편이
 * 계절성의 영향을 받지 않고 사용자가 공시에서 보는 숫자와도 일치한다.
 *
 * <p>분모에 절댓값을 쓰는 이유는 직전 매출이 음수인 극단적 경우(환입 등) 부호가 뒤집혀
 * 역성장이 성장으로 보이는 것을 막기 위해서다. 직전 매출이 0이면 증가율을 정의할 수 없어
 * 값을 내지 않는다.
 */
@Component
@IndicatorMeta(
        name = "매출 성장률",
        description = "최근 회계연도 매출액이 직전 회계연도 대비 얼마나 늘었는지를 백분율로 나타낸 값.",
        interpretation = "높을수록 외형이 빠르게 성장하고 있다는 뜻이다. "
                + "이익 성장률과 함께 보아야 하며, 매출만 늘고 이익이 따라오지 않는 경우도 있다.",
        category = IndicatorCategory.FUNDAMENTAL,
        valueType = IndicatorValueType.PERCENT,
        unit = "%",
        direction = IndicatorMeta.Direction.HIGHER_IS_BETTER,
        decimalScale = 2,
        sortOrder = 60
)
public class RevenueGrowthCalculator implements IndicatorCalculator {

    public static final String CODE = "REVENUE_GROWTH";

    @Override
    public String code() {
        return CODE;
    }

    @Override
    public DataRequirement dataRequirement() {
        // 전년 대비이므로 연간 재무 2개가 필요하다.
        return DataRequirement.priceAndFinancial(1, 2);
    }

    @Override
    public List<IndicatorPoint> calculate(IndicatorContext context) {
        return FinancialMath.spreadOverTradeDates(
                context.priceBars(),
                YearOverYear.growth(context.annualFigures(), FinancialFigures::revenue));
    }
}
