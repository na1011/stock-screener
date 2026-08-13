package com.stockscreener.indicator.calculator;

import com.stockscreener.indicator.DataRequirement;
import com.stockscreener.indicator.IndicatorCalculator;
import com.stockscreener.indicator.IndicatorCategory;
import com.stockscreener.indicator.IndicatorContext;
import com.stockscreener.indicator.IndicatorMeta;
import com.stockscreener.indicator.IndicatorPoint;
import com.stockscreener.indicator.IndicatorValueType;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;

/**
 * 스토캐스틱 %D — {@link StochasticK14Calculator Slow %K}의 3일 단순평균. 시그널선이다.
 *
 * <p>%K가 %D를 아래에서 위로 뚫으면 매수 신호로 읽는 것이 관습이지만, <b>그 교차 자체를 지표로
 * 내지는 않는다.</b> 두 값이 다 있으면 조건 빌더에서 {@code STOCH_K_14 > STOCH_D_14}로 표현할
 * 수 있고, 그것이 조건을 데이터로 다루는 이 프로젝트의 방식이다.
 *
 * <p>날짜가 %K에 없으면 그 날짜의 %D도 내지 않는다 — 창에 구멍이 있는데 평균을 내면 사흘이
 * 아니라 이틀치를 셋으로 나눈 값이 된다.
 */
@Component
@IndicatorMeta(
        name = "스토캐스틱 %D",
        description = "스토캐스틱 %K를 3일 평균한 시그널선. %K보다 느리게 움직인다.",
        interpretation = "%K가 %D를 위로 지나면 단기 반등, 아래로 지나면 단기 조정으로 읽는 것이 "
                + "관습이다. 두 선이 80 위나 20 아래에서 교차할 때를 더 무겁게 본다.",
        category = IndicatorCategory.OSCILLATOR,
        valueType = IndicatorValueType.PERCENT,
        unit = "%",
        decimalScale = 2,
        direction = IndicatorMeta.Direction.NEUTRAL,
        sortOrder = 211,
        bands = {
                @IndicatorMeta.Band(label = "과열", from = 80),
                @IndicatorMeta.Band(label = "중립", from = 20, to = 80),
                @IndicatorMeta.Band(label = "침체", to = 20)
        }
)
public class StochasticD14Calculator implements IndicatorCalculator {

    public static final String CODE = "STOCH_D_14";
    private static final int SMOOTHING = 3;

    @Override
    public String code() {
        return CODE;
    }

    @Override
    public DataRequirement dataRequirement() {
        return DataRequirement.derivedFrom(SMOOTHING - 1, StochasticK14Calculator.CODE);
    }

    @Override
    public List<IndicatorPoint> calculate(IndicatorContext context) {
        NavigableMap<LocalDate, BigDecimal> fastD = context.dependency(StochasticK14Calculator.CODE);
        if (fastD.size() < SMOOTHING) {
            return List.of();
        }

        List<Map.Entry<LocalDate, BigDecimal>> series = new ArrayList<>(fastD.entrySet());
        List<IndicatorPoint> points = new ArrayList<>(series.size() - SMOOTHING + 1);
        for (int end = SMOOTHING - 1; end < series.size(); end++) {
            BigDecimal sum = BigDecimal.ZERO;
            for (int i = end - SMOOTHING + 1; i <= end; i++) {
                sum = sum.add(series.get(i).getValue());
            }
            BigDecimal value = sum.divide(BigDecimal.valueOf(SMOOTHING),
                    MovingAverages.SCALE, RoundingMode.HALF_UP);
            points.add(new IndicatorPoint(series.get(end).getKey(), value));
        }
        return points;
    }
}
