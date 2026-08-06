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
 * 자기자본이익률(ROE) = 순이익(TTM) ÷ 자기자본 × 100.
 *
 * <p>순이익은 최근 4분기 합을 쓰고 자기자본은 <b>가장 최근 분기의 잔액</b>을 쓴다. 자기자본은
 * 저량(stock) 항목이라 합산하면 4배가 된다 — 유량과 저량을 섞는 것이 이 계산의 대표적 오류다.
 *
 * <p>자기자본이 0 이하(자본잠식)면 값을 내지 않는다. 분모가 음수면 적자 기업의 ROE가 양수로
 * 나와 "ROE가 높은 종목" 조건에 자본잠식 기업이 올라온다.
 */
@Component
@IndicatorMeta(
        name = "ROE",
        description = "최근 1년 순이익을 자기자본으로 나눈 값. "
                + "주주가 맡긴 자본으로 얼마나 이익을 냈는지를 나타낸다.",
        interpretation = "높을수록 자본을 효율적으로 써서 이익을 냈다는 뜻이다. "
                + "통상 15% 이상이면 수익성이 우수한 편으로 본다. "
                + "다만 부채가 많아도 높아질 수 있어 부채비율과 함께 본다.",
        category = IndicatorCategory.FUNDAMENTAL,
        valueType = IndicatorValueType.PERCENT,
        unit = "%",
        direction = IndicatorMeta.Direction.HIGHER_IS_BETTER,
        decimalScale = 2,
        defaultColumn = true,
        sortOrder = 30
)
public class RoeCalculator implements IndicatorCalculator {

    public static final String CODE = "ROE";

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
        List<FinancialFigures> quarters = context.quarterlyFigures();
        BigDecimal netIncome =
                FinancialMath.sumTrailingFourQuarters(quarters, FinancialFigures::netIncome);
        if (netIncome == null) {
            return List.of();
        }

        BigDecimal equity = quarters.get(0).totalEquity();
        if (equity == null || equity.signum() <= 0) {
            // 자본잠식이거나 항목이 비었다.
            return List.of();
        }

        return FinancialMath.spreadOverTradeDates(
                context.priceBars(), FinancialMath.percentage(netIncome, equity));
    }
}
