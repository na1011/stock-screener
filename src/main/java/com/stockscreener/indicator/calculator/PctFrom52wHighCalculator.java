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
 * 52주 최고가 대비 낙폭 — {@code (수정종가 ÷ 52주 최고가 − 1) × 100}. 값은 언제나 0 이하다.
 *
 * <p>{@link PctFrom52wLowCalculator}와 짝이다. 저점 대비가 하단을 막고 이것이 상단을 막아
 * <b>"과열되지도 훼손되지도 않은 구간"</b>을 만든다.
 *
 * <p>고점 대비 25% 이상 빠지면서 200일선 위를 유지하는 것은 산술적으로 드물다. 그래서 이
 * 지표는 단독 게이트라기보다 200일선 조건의 보완 장치에 가깝고, 0에 가까운 값(신고가 부근)을
 * 읽어내는 쪽에서 더 자주 쓰인다.
 */
@Component
@IndicatorMeta(
        name = "52주 최고가 대비",
        description = "최근 252거래일 최고가에서 지금 종가가 몇 퍼센트 내려와 있는지. 분할 조정 가격으로 계산하며 값은 0 이하다.",
        interpretation = "0에 가까우면 신고가 부근이라는 뜻이다. "
                + "−25%보다 깊으면 중간 조정이 아니라 구조적 하락일 수 있어 "
                + "무엇이 바뀌었는지 확인할 필요가 있다.",
        category = IndicatorCategory.TECHNICAL,
        valueType = IndicatorValueType.PERCENT,
        unit = "%",
        decimalScale = 1,
        direction = IndicatorMeta.Direction.HIGHER_IS_BETTER,
        sortOrder = 161,
        bands = {
                @IndicatorMeta.Band(label = "신고가 부근", from = -5),
                @IndicatorMeta.Band(label = "얕은 조정", from = -15, to = -5),
                @IndicatorMeta.Band(label = "중간 조정", from = -25, to = -15),
                @IndicatorMeta.Band(label = "깊은 조정", to = -25)
        }
)
public class PctFrom52wHighCalculator implements IndicatorCalculator {

    public static final String CODE = "PCT_FROM_52W_HIGH";

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
        return FiftyTwoWeekRange.percentFromExtreme(context.priceBars(), false);
    }
}
