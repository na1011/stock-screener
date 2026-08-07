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
 * 영업이익률 = 영업이익(TTM) ÷ 매출액(TTM) × 100.
 *
 * <p>둘 다 유량(flow) 항목이라 최근 4분기를 합해 쓴다. 분기 단독 값을 쓰면 계절성이 큰
 * 업종에서 분기마다 값이 널뛴다.
 *
 * <p>매출이 0 이하면 값을 내지 않는다. 매출 없는 기업(개발 단계 바이오 등)의 영업이익률은
 * 정의되지 않는다.
 */
@Component
@IndicatorMeta(
        name = "영업이익률",
        description = "최근 1년 영업이익을 같은 기간 매출액으로 나눈 값. "
                + "본업에서 얼마나 남기는지를 나타낸다.",
        interpretation = "높을수록 본업의 수익성이 좋다는 뜻이다. "
                + "업종별 편차가 크므로 같은 업종 안에서 비교하는 것이 좋다.",
        category = IndicatorCategory.FUNDAMENTAL,
        valueType = IndicatorValueType.PERCENT,
        unit = "%",
        direction = IndicatorMeta.Direction.HIGHER_IS_BETTER,
        decimalScale = 2,
        sortOrder = 50,
        bands = {
                @IndicatorMeta.Band(label = "매우 높음", from = 20),
                @IndicatorMeta.Band(label = "높음", from = 10, to = 20),
                @IndicatorMeta.Band(label = "보통", from = 5, to = 10),
                @IndicatorMeta.Band(label = "낮음", from = 0, to = 5),
                // 0 미만은 "낮다"가 아니라 적자다. 정도의 문제가 아니라 종류가 다르다.
                @IndicatorMeta.Band(label = "영업적자", to = 0)
        }
)
public class OperatingMarginCalculator implements IndicatorCalculator {

    public static final String CODE = "OPERATING_MARGIN";

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
        BigDecimal operatingIncome =
                FinancialMath.sumTrailingFourQuarters(quarters, FinancialFigures::operatingIncome);
        BigDecimal revenue =
                FinancialMath.sumTrailingFourQuarters(quarters, FinancialFigures::revenue);
        if (operatingIncome == null || revenue == null || revenue.signum() <= 0) {
            return List.of();
        }

        return FinancialMath.spreadOverTradeDates(
                context.priceBars(), FinancialMath.percentage(operatingIncome, revenue));
    }
}
