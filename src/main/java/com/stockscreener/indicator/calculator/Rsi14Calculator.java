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
 * 14일 상대강도지수(RSI).
 *
 * <p>공식의 정본은 {@link RelativeStrengthIndex}이며 기간만 다른 {@code RSI_2}와 공유한다.
 * Wilder 평활을 쓰는 이유와 흔한 구현 오류는 그쪽 javadoc에 있다.
 */
@Component
@IndicatorMeta(
        name = "RSI(14)",
        description = "최근 14거래일 동안의 상승폭과 하락폭의 비율. 0~100 사이의 값을 가진다.",
        interpretation = "통상 70 이상이면 단기 과열(과매수), 30 이하면 단기 침체(과매도)로 본다. "
                + "다만 강한 추세에서는 한쪽에 오래 머물 수 있어 단독 매매 근거로는 약하다.",
        category = IndicatorCategory.TECHNICAL,
        valueType = IndicatorValueType.COUNT,
        direction = IndicatorMeta.Direction.NEUTRAL,
        decimalScale = 2,
        sortOrder = 120,
        // 70·30은 interpretation이 이미 적어 둔 경계다. 여기서 새로 정하는 것이 아니다.
        bands = {
                @IndicatorMeta.Band(label = "과매수", from = 70),
                @IndicatorMeta.Band(label = "상승 우위", from = 50, to = 70),
                @IndicatorMeta.Band(label = "하락 우위", from = 30, to = 50),
                @IndicatorMeta.Band(label = "과매도", to = 30)
        }
)
public class Rsi14Calculator implements IndicatorCalculator {

    public static final String CODE = "RSI_14";
    private static final int PERIOD = 14;

    /** 변화량을 만들려면 첫 봉이 하나 더 필요하다. */
    @Override
    public DataRequirement dataRequirement() {
        return DataRequirement.priceBars(PERIOD + 1);
    }

    @Override
    public String code() {
        return CODE;
    }

    @Override
    public List<IndicatorPoint> calculate(IndicatorContext context) {
        return RelativeStrengthIndex.rsi(context.priceBars(), PERIOD);
    }
}
