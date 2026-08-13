package com.stockscreener.indicator.calculator;

import com.stockscreener.indicator.DataRequirement;
import com.stockscreener.indicator.IndicatorCalculator;
import com.stockscreener.indicator.IndicatorCategory;
import com.stockscreener.indicator.IndicatorContext;
import com.stockscreener.indicator.IndicatorMeta;
import com.stockscreener.indicator.IndicatorPoint;
import com.stockscreener.indicator.IndicatorValueType;

import org.springframework.stereotype.Component;

import java.util.List;

/** 20일 단순이동평균. 단기 추세선이다. */
@Component
@IndicatorMeta(
        name = "20일 이동평균",
        description = "최근 20거래일 수정종가의 평균. 약 한 달간의 단기 추세를 나타낸다.",
        interpretation = "주가가 이 선 위에 있으면 단기 상승 흐름으로 본다. "
                + "장기선(120·200일)을 아래에서 위로 통과하면 추세 전환 신호로 해석하는 경우가 많다.",
        category = IndicatorCategory.TECHNICAL,
        valueType = IndicatorValueType.PRICE,
        direction = IndicatorMeta.Direction.NEUTRAL,
        decimalScale = 2,
        sortOrder = 100
)
public class Sma20Calculator implements IndicatorCalculator {

    public static final String CODE = "SMA_20";
    private static final int PERIOD = 20;

    @Override
    public String code() {
        return CODE;
    }

    @Override
    public DataRequirement dataRequirement() {
        return DataRequirement.priceBars(PERIOD);
    }

    @Override
    public List<IndicatorPoint> calculate(IndicatorContext context) {
        return MovingAverages.sma(context.priceBars(), PERIOD);
    }
}
