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

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * MACD 본선 — 12일 EMA에서 26일 EMA를 뺀 값.
 *
 * <p>두 EMA의 시작 시점이 다르다는 점이 구현의 핵심이다. 12일 EMA가 먼저 생기지만
 * 26일 EMA가 없는 구간에서는 뺄 수 없으므로, <b>26일 EMA가 존재하는 시점부터만</b> 값을 낸다.
 * 이것을 맞추지 않으면 앞 구간이 통째로 어긋난 채 그럴듯한 값이 저장된다.
 *
 * <p>시그널선(9일 EMA)과 히스토그램은 별도 지표로 두지 않았다. 필요해지면 이 지표에
 * {@code dependsOn}으로 의존하는 계산기를 파일 하나로 추가하면 된다.
 */
@Component
@IndicatorMeta(
        name = "MACD",
        description = "12일 지수이동평균에서 26일 지수이동평균을 뺀 값. "
                + "단기 흐름이 장기 흐름보다 얼마나 앞서 있는지를 나타낸다.",
        interpretation = "0보다 크면 단기 흐름이 장기 흐름보다 강하다는 뜻이다. "
                + "값 자체는 주가 수준에 비례하므로 다른 종목과 직접 비교하지 않는다.",
        category = IndicatorCategory.TECHNICAL,
        valueType = IndicatorValueType.PRICE,
        direction = IndicatorMeta.Direction.NEUTRAL,
        decimalScale = 4,
        sortOrder = 130
)
public class MacdCalculator implements IndicatorCalculator {

    public static final String CODE = "MACD";
    private static final int FAST = 12;
    private static final int SLOW = 26;

    @Override
    public String code() {
        return CODE;
    }

    @Override
    public DataRequirement dataRequirement() {
        return DataRequirement.priceBars(SLOW);
    }

    @Override
    public List<IndicatorPoint> calculate(IndicatorContext context) {
        List<PriceBar> bars = context.priceBars();
        if (bars.size() < SLOW) {
            return List.of();
        }

        List<BigDecimal> closes = MovingAverages.adjCloses(bars);
        List<BigDecimal> fast = MovingAverages.ema(closes, FAST);
        List<BigDecimal> slow = MovingAverages.ema(closes, SLOW);
        if (slow.isEmpty()) {
            return List.of();
        }

        // fast[i]는 bars[FAST-1+i], slow[i]는 bars[SLOW-1+i]에 해당한다.
        // 느린 쪽 기준으로 맞추기 위해 빠른 쪽을 그 차이만큼 건너뛴다.
        int offset = SLOW - FAST;
        List<IndicatorPoint> points = new ArrayList<>(slow.size());
        for (int i = 0; i < slow.size(); i++) {
            BigDecimal value = fast.get(i + offset).subtract(slow.get(i));
            points.add(new IndicatorPoint(bars.get(SLOW - 1 + i).tradeDate(), value));
        }
        return points;
    }
}
