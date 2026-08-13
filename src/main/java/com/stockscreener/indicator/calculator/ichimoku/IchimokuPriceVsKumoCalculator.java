package com.stockscreener.indicator.calculator.ichimoku;

import com.stockscreener.indicator.IndicatorCategory;
import com.stockscreener.indicator.IndicatorMeta;
import com.stockscreener.indicator.IndicatorValueType;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/**
 * 주봉 종가가 구름의 어디에 있는가 — TradingView 스크리너의 {@code Price above Cloud}.
 *
 * <p>−1·0·1이 <b>순서형</b>이다. 구름 아래 &lt; 구름 안 &lt; 구름 위이고 중간값이 "판정 불가"가
 * 아니라 실제 상태(구름 안 = 방향 미정)라, 다른 일목 지표와 달리 부등호를 그대로 걸 수 있다.
 */
@Component
@IndicatorMeta(
        name = "일목 가격 대 구름",
        description = "주봉 종가가 구름 위면 1, 구름 안이면 0, 구름 아래면 −1이다.",
        interpretation = "구름 위는 상승 추세, 아래는 하락 추세로 본다. "
                + "구름 안은 방향이 정해지지 않은 구간이며 구름이 두꺼울수록 오래 머문다.",
        category = IndicatorCategory.DERIVED,
        valueType = IndicatorValueType.COUNT,
        decimalScale = 0,
        direction = IndicatorMeta.Direction.HIGHER_IS_BETTER,
        sortOrder = 311,
        bands = {
                @IndicatorMeta.Band(label = "구름 위", from = 1),
                @IndicatorMeta.Band(label = "구름 안", from = 0, to = 1),
                @IndicatorMeta.Band(label = "구름 아래", to = 0)
        })
public class IchimokuPriceVsKumoCalculator extends IchimokuIndicator {

    public static final String CODE = "ICHI_PRICE_VS_KUMO";

    public IchimokuPriceVsKumoCalculator(IchimokuAnalyses analyses) {
        super(analyses);
    }

    @Override
    public String code() {
        return CODE;
    }

    @Override
    protected BigDecimal valueOf(IchimokuAnalysis analysis) {
        return of(analysis.priceVsKumo());
    }
}
