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
 * 52주 최저가 대비 상승률 — {@code (수정종가 ÷ 52주 최저가 − 1) × 100}.
 *
 * <p>하락 추세 중의 일시 반등을 상승 전환으로 오인하는 것을 막는다. 200일선 위에 있으면서도
 * 52주 저점 근처인 국면은 드물지만 급락 후 급반등에서 실제로 나타난다 — 그때 200일선은 아직
 * 하락 관성을 갖고 있어 며칠 만에 추세 조건이 무효화된다.
 *
 * <p>저가는 원주가라 {@link PriceScaling}으로 축척을 맞춘 뒤 쓴다. 맞추지 않으면 분할 구간의
 * 최저가가 분할비율만큼 낮게 잡혀 상승률이 터무니없이 커진다.
 */
@Component
@IndicatorMeta(
        name = "52주 최저가 대비",
        description = "최근 252거래일 최저가에서 지금 종가가 몇 퍼센트 올라 있는지. 분할 조정 가격으로 계산한다.",
        interpretation = "값이 클수록 저점에서 멀리 올라온 상태다. "
                + "너무 작으면 하락 추세 중의 일시 반등일 수 있고, "
                + "너무 크면 이미 많이 올라 분할 진입 여지가 좁다는 뜻이다.",
        category = IndicatorCategory.TECHNICAL,
        valueType = IndicatorValueType.PERCENT,
        unit = "%",
        decimalScale = 1,
        direction = IndicatorMeta.Direction.NEUTRAL,
        sortOrder = 130,
        bands = {
                @IndicatorMeta.Band(label = "저점에서 크게 상승", from = 80),
                @IndicatorMeta.Band(label = "추세 전환 확인 구간", from = 30, to = 80),
                @IndicatorMeta.Band(label = "저점 부근", from = 10, to = 30),
                @IndicatorMeta.Band(label = "저점 근접", to = 10)
        }
)
public class PctFrom52wLowCalculator implements IndicatorCalculator {

    public static final String CODE = "PCT_FROM_52W_LOW";

    @Override
    public String code() {
        return CODE;
    }

    @Override
    public DataRequirement dataRequirement() {
        return DataRequirement.priceBars(FiftyTwoWeekRange.WINDOW);
    }

    @Override
    public List<IndicatorPoint> calculate(IndicatorContext context) {
        return FiftyTwoWeekRange.percentFromExtreme(context.priceBars(), true);
    }
}
