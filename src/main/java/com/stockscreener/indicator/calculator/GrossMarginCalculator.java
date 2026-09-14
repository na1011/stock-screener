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

import java.math.BigDecimal;
import java.util.List;

/**
 * 매출총이익률 = (매출 − 매출원가) ÷ 매출 × 100. 연간(FY) 기준이다.
 *
 * <p>공식과 함정은 {@link GrossProfit}에 있다 — 특히 <b>매출원가가 없는 것이 결손이 아닐
 * 수 있다</b>는 것.
 */
@Component
@IndicatorMeta(
        name = "매출총이익률",
        description = "최근 회계연도의 매출에서 매출원가를 뺀 몫이 매출의 몇 퍼센트인지. "
                + "원가를 빼고 얼마가 남는지를 나타내며, 판매비·관리비는 아직 빼기 전이다. "
                + "매출원가를 따로 표시하지 않는 업종에서는 값이 나오지 않는다. "
                + "연간 재무만 쓰므로 1년에 한 번 바뀐다.",
        interpretation = "높을수록 제품·서비스 자체의 마진이 두껍다는 뜻이다. "
                + "업종별 차이가 매우 커서 같은 업종끼리 견주는 편이 낫다 — "
                + "제조업과 유통업의 정상 범위가 다르다.",
        category = IndicatorCategory.FUNDAMENTAL,
        valueType = IndicatorValueType.PERCENT,
        unit = "%",
        direction = IndicatorMeta.Direction.HIGHER_IS_BETTER,
        decimalScale = 2,
        sortOrder = 51
)
public class GrossMarginCalculator implements IndicatorCalculator {

    public static final String CODE = "GROSS_MARGIN";

    @Override
    public String code() {
        return CODE;
    }

    @Override
    public DataRequirement dataRequirement() {
        return DataRequirement.priceAndFinancial(1, 1);
    }

    @Override
    public List<IndicatorPoint> calculate(IndicatorContext context) {
        return FinancialMath.byPublication(context, GrossMarginCalculator::valueOf);
    }

    /** 그 봉 시점에 공시돼 있던 재무로 낸 값. 없으면 {@code null}이다(ADR-0128). */
    private static BigDecimal valueOf(IndicatorContext context) {
        FinancialFigures latest = GrossProfit.latestAnnual(context.annualFigures());
        BigDecimal grossProfit = GrossProfit.of(latest);
        if (grossProfit == null) {
            return null;
        }
        return FinancialMath.percentage(grossProfit, latest.revenue());
    }
}
