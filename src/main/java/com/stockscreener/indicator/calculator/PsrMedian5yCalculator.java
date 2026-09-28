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
 * 자기 자신의 5년 PSR 중앙값. 구조와 함정은 {@link ValuationHistory}가 정본이다.
 *
 * <p><b>셋 중 표본이 가장 잘 남는 축이다.</b> 매출은 원리적으로 음수가 없어 적자에도, 자본잠식에도
 * 봉이 빠지지 않는다. {@code PER_MEDIAN_5Y}가 적자 구간을 버려 218종목인 자리에서 이 지표는
 * 5년 창을 채운 종목이면 대체로 값을 낸다.
 *
 * <p>매출은 <b>유량</b>이므로 그 시점에 공시돼 있던 자리부터 <b>4분기를 더한다.</b>
 */
@Component
@IndicatorMeta(
        name = "5년 PSR 중앙값",
        description = "지난 5년 동안 이 종목의 PSR이 놓였던 자리의 한가운데 값. "
                + "매 거래일의 주가를 그날 공시돼 있던 4개 분기 매출로 나눠 모은 뒤 중앙값을 취한다."
                + IndicatorMeta.PERIOD_BASIS_NOTE,
        interpretation = "지금 PSR을 옆에 두고 읽는 참조값이다. 현재 PSR이 이 값보다 낮으면 "
                + "이 회사 기준으로 싼 구간에 있다는 뜻이다. 이익이 들쭉날쭉해 5년 PER 중앙값이 "
                + "나오지 않는 종목에서도 값이 남는 것이 이 축의 쓸모다. 유리한 방향이 따로 없다.",
        category = IndicatorCategory.FUNDAMENTAL,
        valueType = IndicatorValueType.RATIO,
        unit = "배",
        direction = IndicatorMeta.Direction.NEUTRAL,
        decimalScale = 2,
        sortOrder = 26
)
public class PsrMedian5yCalculator implements IndicatorCalculator {

    public static final String CODE = "PSR_MEDIAN_5Y";

    @Override
    public String code() {
        return CODE;
    }

    @Override
    public DataRequirement dataRequirement() {
        return DataRequirement.priceAndFinancial(
                ValuationHistory.LOOKBACK + 1, EpsCagr5yCalculator.YEARS + 1);
    }

    @Override
    public List<IndicatorPoint> calculate(IndicatorContext context) {
        return ValuationHistory.median(context, (ctx, bar, series, published) -> {
            BigDecimal ttm = series.ttm(FinancialFigures::revenue, published);
            if (ttm == null || ttm.signum() <= 0) {
                return null;
            }
            return FinancialMath.perShareOn(ctx, bar, ttm, series);
        });
    }
}
