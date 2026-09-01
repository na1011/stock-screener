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
 * <p><b>시그널선과 히스토그램은 이 지표가 아니라 {@code MACD_V} 위에 지었다</b>
 * ({@link MacdVHistCalculator}). 여기 붙이면 <b>주가 스케일 그대로</b>라 구간(bands)을 못 붙이고
 * (ADR-0023) 종목 간 비교도 안 되어, 조건의 좌변으로도 카드로도 뜻이 약하다. 정규화된
 * {@code MACD_V} 위에서는 히스토그램의 부호가 골든/데드 상태를, 크기가 교차까지의 거리를
 * 담는다. <b>MUST NOT: 여기에 같은 것을 또 만들지 않는다.</b>
 *
 * <p>{@code detailDefault = false}는 <b>2026-09-01 사용자 결정</b>이다 — 종목 상세의 분류
 * 구획에서만 감춘다. 폐기가 아니라 조건 빌더·결과 표·「내 조건」에는 그대로 나오고(ADR-0058),
 * {@code MACD_V}가 이 값을 부모로 계속 쓴다.
 */
@Component
@IndicatorMeta(
        name = "MACD",
        description = "12일 지수이동평균에서 26일 지수이동평균을 뺀 값. "
                + "단기 흐름이 장기 흐름보다 얼마나 앞서 있는지를 나타낸다.",
        interpretation = "0보다 크면 단기 흐름이 장기 흐름보다 강하다는 뜻이다. "
                + "값 자체는 주가 수준에 비례하므로 다른 종목과 직접 비교하지 않는다.",
        category = IndicatorCategory.OSCILLATOR,
        valueType = IndicatorValueType.PRICE,
        direction = IndicatorMeta.Direction.NEUTRAL,
        decimalScale = 4,
        sortOrder = 200,
        detailDefault = false
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
