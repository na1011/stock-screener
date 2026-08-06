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
 * 영업이익 성장률 — 최근 회계연도 영업이익의 전년 대비 증감률.
 *
 * <p>매출 성장률과 함께 보는 것이 전제다. 매출은 그대로인데 영업이익만 늘었다면 원가 개선이고,
 * 둘 다 늘었다면 외형 성장이다. 두 지표를 따로 두는 이유가 그 구분에 있다.
 */
@Component
@IndicatorMeta(
        name = "영업이익 성장률",
        description = "최근 회계연도 영업이익이 직전 회계연도 대비 얼마나 늘었는지를 백분율로 나타낸 값.",
        interpretation = "높을수록 본업의 이익이 빠르게 늘고 있다는 뜻이다. "
                + "직전 해가 적자였다면 값이 크게 튈 수 있어 절대 금액과 함께 보는 것이 좋다.",
        category = IndicatorCategory.FUNDAMENTAL,
        valueType = IndicatorValueType.PERCENT,
        unit = "%",
        direction = IndicatorMeta.Direction.HIGHER_IS_BETTER,
        decimalScale = 2,
        sortOrder = 70
)
public class OperatingIncomeGrowthCalculator implements IndicatorCalculator {

    public static final String CODE = "OPERATING_INCOME_GROWTH";

    @Override
    public String code() {
        return CODE;
    }

    @Override
    public DataRequirement dataRequirement() {
        return DataRequirement.priceAndFinancial(1, 2);
    }

    @Override
    public List<IndicatorPoint> calculate(IndicatorContext context) {
        return FinancialMath.spreadOverTradeDates(
                context.priceBars(),
                YearOverYear.growth(context.annualFigures(), FinancialFigures::operatingIncome));
    }
}
