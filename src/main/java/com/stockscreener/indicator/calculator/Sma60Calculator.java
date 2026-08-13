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

/** 60일 단순이동평균. 중기 추세선이며 국내에서 흔히 '수급선'이라 부른다. */
@Component
@IndicatorMeta(
        name = "60일 이동평균",
        description = "최근 60거래일 수정종가의 평균. 약 3개월간의 중기 추세를 나타낸다.",
        interpretation = "단기선과 장기선 사이의 중간 기준으로 쓴다. "
                + "주가가 이 선을 지지선 삼아 반등하는지가 중기 추세 유지의 판단 근거가 된다.",
        category = IndicatorCategory.TECHNICAL,
        valueType = IndicatorValueType.PRICE,
        direction = IndicatorMeta.Direction.NEUTRAL,
        decimalScale = 2,
        sortOrder = 101
)
public class Sma60Calculator implements IndicatorCalculator {

    public static final String CODE = "SMA_60";
    private static final int PERIOD = 60;

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
