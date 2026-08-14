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
 * 2일 상대강도지수 — Connors의 평균회귀 전략이 쓰는 그 값이다.
 *
 * <p><b>{@code RSI_14}가 있는데 이것을 따로 두는 이유가 있다.</b> Connors의 규칙은
 * 「상승 추세 안에서 RSI(2)가 10 미만인 날을 산다」인데, 기간 14로는 그 조건이 성립하는 날이
 * 사실상 없다 — 실측에서 {@code RSI_14}의 최솟값이 28이다. 스토캐스틱이나 완화한 RSI(14)
 * 임계로 대신하면 <b>원문보다 훨씬 좁은 조건을 「Connors 이식」이라 부르게 된다.</b>
 *
 * <p>공식의 정본은 {@link RelativeStrengthIndex}다. 기간만 다르다.
 *
 * <p>MUST NOT: 값이 0이나 100에 자주 붙는 것을 결함으로 읽지 않는다. 이틀치만 보므로
 * 이틀 연속 하락이면 0, 이틀 연속 상승이면 100이 정의상 맞다. <b>단독으로 쓰지 않고
 * 추세 조건과 함께 거는 것이 원문의 설계</b>이며, 그래서 구간(bands)을 붙이지 않는다 —
 * 「과매도」라는 낱말이 단독 매수 신호로 읽히기 때문이다.
 */
@Component
@IndicatorMeta(
        name = "RSI(2)",
        description = "최근 2거래일 동안의 상승폭과 하락폭의 비율. 0~100 사이의 값을 가지며 "
                + "RSI(14)보다 훨씬 민감해 0이나 100에 자주 붙는다.",
        interpretation = "10 미만이면 이틀 사이에 매도가 몰렸다는 뜻이다. 장기 상승 추세에 "
                + "있는 종목에서만 뜻을 가지며, 하락 추세에서는 계속 낮게 머문다.",
        category = IndicatorCategory.TECHNICAL,
        valueType = IndicatorValueType.COUNT,
        direction = IndicatorMeta.Direction.NEUTRAL,
        decimalScale = 2,
        sortOrder = 121
)
public class Rsi2Calculator implements IndicatorCalculator {

    public static final String CODE = "RSI_2";
    private static final int PERIOD = 2;

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
