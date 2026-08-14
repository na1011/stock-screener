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

/**
 * 150일 단순이동평균 — 주봉으로 30주선이다.
 *
 * <p>120일선이 이미 있는데 이것을 따로 두는 이유는 <b>Minervini 트렌드 템플릿과 Weinstein
 * 국면 분석이 둘 다 30주(150일)를 쓰기 때문</b>이다. 120일로 대신하면 조건은 통과시키지만
 * 그것은 원문이 아니고, 그 사실이 조건 목록 어디에도 남지 않는다.
 */
@Component
@IndicatorMeta(
        name = "150일 이동평균",
        description = "최근 150거래일 수정종가의 평균. 주봉으로 30주선이며 "
                + "국면 분석에서 상승 추세의 기준선으로 쓴다.",
        interpretation = "주가가 이 선 위에 있고 이 선이 200일선 위에 있으면 "
                + "장기 상승 국면으로 본다.",
        category = IndicatorCategory.TECHNICAL,
        valueType = IndicatorValueType.PRICE,
        direction = IndicatorMeta.Direction.NEUTRAL,
        decimalScale = 2,
        sortOrder = 103
)
public class Sma150Calculator implements IndicatorCalculator {

    public static final String CODE = "SMA_150";
    private static final int PERIOD = 150;

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
