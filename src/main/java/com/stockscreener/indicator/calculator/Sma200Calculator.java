package com.stockscreener.indicator.calculator;

import com.stockscreener.indicator.DataRequirement;
import com.stockscreener.indicator.IndicatorCalculator;
import com.stockscreener.indicator.IndicatorCategory;
import com.stockscreener.indicator.IndicatorContext;
import com.stockscreener.indicator.IndicatorMeta;
import com.stockscreener.indicator.IndicatorPoint;
import com.stockscreener.indicator.IndicatorValueType;
import com.stockscreener.indicator.PriceBar;

import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 200일 단순이동평균.
 *
 * <p>IMPORTANT — {@link PriceBar#adjClose()}로 계산한다. 원주가를 쓰면 액면분할 당일
 * 이동평균이 실제의 분할비율만큼 왜곡되어 거짓 신호가 나오고, 이후 200거래일간
 * 값이 무의미해진다.
 */
@Component
@IndicatorMeta(
        name = "200일 이동평균",
        description = "최근 200거래일 수정종가의 평균. 장기 추세의 기준선으로 쓴다.",
        interpretation = "주가가 이 선 위에 있으면 장기 상승 추세로, 아래에 있으면 하락 추세로 해석한다.",
        category = IndicatorCategory.TECHNICAL,
        valueType = IndicatorValueType.PRICE,
        direction = IndicatorMeta.Direction.NEUTRAL,
        decimalScale = 2,
        sortOrder = 100
)
public class Sma200Calculator implements IndicatorCalculator {

    public static final String CODE = "SMA_200";
    private static final int PERIOD = 200;

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
        // 이력이 부족하면 아무 값도 내지 않는다. 부정확한 값을 채우는 대신 행을 만들지 않으면
        // 조건 판정에서 이 종목이 자연히 제외된다.
        return MovingAverages.sma(context.priceBars(), PERIOD);
    }
}
