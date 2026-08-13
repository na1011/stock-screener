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

/** 120일 단순이동평균. 약 반년의 추세로, 국내에서 흔히 '경기선'이라 부른다. */
@Component
@IndicatorMeta(
        name = "120일 이동평균",
        description = "최근 120거래일 수정종가의 평균. 약 6개월간의 장기 추세를 나타낸다.",
        interpretation = "주가가 이 선 위에서 유지되면 장기 상승 국면으로 본다. "
                + "200일선보다 민감해 추세 전환을 조금 이르게 보여준다.",
        category = IndicatorCategory.TECHNICAL,
        valueType = IndicatorValueType.PRICE,
        direction = IndicatorMeta.Direction.NEUTRAL,
        decimalScale = 2,
        sortOrder = 102
)
public class Sma120Calculator implements IndicatorCalculator {

    public static final String CODE = "SMA_120";
    private static final int PERIOD = 120;

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
