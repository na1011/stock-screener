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
 * 총자산이익률(ROA) = 순이익(TTM) ÷ 총자산 × 100.
 *
 * <p>순이익은 최근 4분기 합, 총자산은 <b>가장 최근 분기의 잔액</b>이다. 총자산은 저량(stock)
 * 항목이라 합산하면 4배가 된다.
 *
 * <p><b>{@code ROE}가 있는데 이것을 따로 두는 이유</b> — Piotroski F-Score의 첫 항목이
 * ROE가 아니라 ROA다. 분모가 자기자본이 아니라 총자산이라 <b>부채로 부풀린 수익성이
 * 걸러지지 않는다</b>. 그래서 F-Score는 레버리지 항목을 따로 둔다.
 *
 * <p>총자산이 0 이하면 값을 내지 않는다.
 */
@Component
@IndicatorMeta(
        name = "ROA",
        description = "최근 1년 순이익을 총자산으로 나눈 값. "
                + "빌린 돈까지 포함한 전체 자산으로 얼마나 이익을 냈는지를 나타낸다.",
        interpretation = "높을수록 자산을 효율적으로 써서 이익을 냈다는 뜻이다. "
                + "ROE와 달리 부채를 많이 써도 높아지지 않아 자본구조의 영향을 덜 받는다. "
                + "업종마다 자산 규모가 달라 같은 업종끼리 견주는 편이 낫다.",
        category = IndicatorCategory.FUNDAMENTAL,
        valueType = IndicatorValueType.PERCENT,
        unit = "%",
        direction = IndicatorMeta.Direction.HIGHER_IS_BETTER,
        decimalScale = 2,
        sortOrder = 31
)
public class RoaCalculator implements IndicatorCalculator {

    public static final String CODE = "ROA";

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
        return FinancialMath.byPublication(context, RoaCalculator::valueOf);
    }

    /** 그 봉 시점에 공시돼 있던 재무로 낸 값. 없으면 {@code null}이다(ADR-0128). */
    private static BigDecimal valueOf(IndicatorContext context) {
        FinancialMath.PeriodSeries series = FinancialMath.seriesOf(context);
        BigDecimal netIncome =
                series.ttm(FinancialFigures::continuingIncome, 0);
        if (netIncome == null) {
            return null;
        }

        BigDecimal assets = series.latestPoint().totalAssets();
        if (assets == null || assets.signum() <= 0) {
            return null;
        }
        return FinancialMath.percentage(netIncome, assets);
    }
}
