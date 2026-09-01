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
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;

/**
 * MACD-V 히스토그램 — 본선에서 시그널선(9일 EMA)을 뺀 값.
 *
 * <p><b>이것이 이 저장소에서 MACD-V의 골든/데드크로스를 표현하는 축이다.</b> 조건 계층은 한
 * 거래일의 스칼라 하나에 부등호를 걸 뿐이라(ADR-0003) 「두 선이 교차했다」를 직접 못 쓴다.
 * 부호가 그 상태를 담는다 — {@code > 0}이면 골든, {@code < 0}이면 데드다. 그리고 <b>크기가
 * 교차까지의 거리</b>라, {@link MacdVHistChange5Calculator 변화량}과 묶으면
 * 「골든크로스로 다가가고 있는」까지 조건이 된다.
 *
 * <p>시그널선을 별도 지표로 저장하지 않는다. 조건으로는 여기 부호가 같은 말을 더 짧게 하고,
 * 카드로는 본선과 상관 0.957이라(2026-09-01 실측) 같은 것을 두 번 보여주게 된다.
 *
 * <p>IMPORTANT — <b>부모 시계열에 날짜 구멍이 나면 EMA를 그 자리에서 다시 시드한다.</b>
 * 러너는 미보정 기업행위 억제 일자를 <b>부모 시계열에서 뺀 채</b> 자식에게 넘긴다(ADR-0055).
 * {@code MACD_V}는 부모 둘을 점별로 나누는 비재귀라 구멍이 전파되지 않지만, <b>9일 EMA는
 * 재귀라 구멍 하나가 그 뒤 전 구간에 남는다.</b> 이어 붙이면 값이 정상 범위 안이라 드러나지
 * 않는다. 구멍은 {@link IndicatorContext#priceBars()}의 거래일 달력으로 판정한다 —
 * 날짜 차이로 재면 휴장이 구멍으로 읽힌다.
 */
@Component
@IndicatorMeta(
        name = "MACD-V 히스토그램",
        description = "MACD-V 본선에서 그 9일 지수이동평균(시그널선)을 뺀 값. "
                + "두 선이 만나는 순간이 0이고, 0을 위로 지나면 골든크로스, 아래로 지나면 데드크로스다.",
        interpretation = "0보다 크면 지금 골든크로스 상태, 작으면 데드크로스 상태라는 뜻이다. "
                + "0에 가까울수록 교차가 가깝다. 부호만으로 「지금 어느 쪽인가」를, "
                + "크기로 「얼마나 가까운가」를 읽는다. "
                + "MACD-V와 같은 무차원 축이라 종목 간 비교가 된다.",
        category = IndicatorCategory.DERIVED,
        valueType = IndicatorValueType.COUNT,
        direction = IndicatorMeta.Direction.HIGHER_IS_BETTER,
        decimalScale = 2,
        sortOrder = 301,
        // 부호만 나눈다. 크기의 경계는 관습이 없어 지어내면 거짓이 된다(ADR-0023).
        bands = {
                @IndicatorMeta.Band(label = "골든크로스 상태", from = 0),
                @IndicatorMeta.Band(label = "데드크로스 상태", to = 0)
        }
)
public class MacdVHistCalculator implements IndicatorCalculator {

    public static final String CODE = "MACD_V_HIST";

    /** 시그널선의 기간. 자기 몫의 추가 봉은 {@code SIGNAL_PERIOD - 1}이다. */
    static final int SIGNAL_PERIOD = 9;

    @Override
    public String code() {
        return CODE;
    }

    @Override
    public DataRequirement dataRequirement() {
        return DataRequirement.derivedFrom(SIGNAL_PERIOD - 1, MacdVCalculator.CODE);
    }

    @Override
    public List<IndicatorPoint> calculate(IndicatorContext context) {
        NavigableMap<LocalDate, BigDecimal> macdV = context.dependency(MacdVCalculator.CODE);
        if (macdV.isEmpty()) {
            return List.of();
        }
        Map<LocalDate, Integer> calendar = tradingDayIndex(context.priceBars());

        List<IndicatorPoint> points = new ArrayList<>();
        Integer previousIndex = null;
        int consumed = 0;
        BigDecimal seedSum = BigDecimal.ZERO;
        BigDecimal signal = null;

        for (Map.Entry<LocalDate, BigDecimal> entry : macdV.entrySet()) {
            Integer index = calendar.get(entry.getKey());
            if (index == null) {
                // 달력 밖 날짜는 연속성을 판정할 수 없다. 이어 붙이지 않는다.
                continue;
            }
            if (previousIndex != null && index - previousIndex != 1) {
                consumed = 0;
                seedSum = BigDecimal.ZERO;
                signal = null;
            }
            previousIndex = index;

            BigDecimal value = entry.getValue();
            if (signal == null) {
                seedSum = seedSum.add(value);
                consumed++;
                if (consumed < SIGNAL_PERIOD) {
                    // 워밍업 구간이다. 값을 지어내지 않는다.
                    continue;
                }
                signal = seedSum.divide(BigDecimal.valueOf(SIGNAL_PERIOD),
                        MovingAverages.SCALE, RoundingMode.HALF_UP);
            } else {
                signal = MovingAverages.emaStep(signal, value, SIGNAL_PERIOD);
            }
            points.add(new IndicatorPoint(entry.getKey(), value.subtract(signal)));
        }
        return points;
    }

    /**
     * 거래일 → 창 안 순번. <b>연속성을 날짜 차이로 재지 않으려고</b> 만든다 —
     * 주말·휴장이 섞이면 하루 차이가 아닌 것이 정상이고, 그것을 구멍으로 읽으면
     * 월요일마다 EMA가 다시 시드된다.
     */
    static Map<LocalDate, Integer> tradingDayIndex(List<PriceBar> bars) {
        Map<LocalDate, Integer> index = new HashMap<>(bars.size() * 2);
        for (int i = 0; i < bars.size(); i++) {
            index.put(bars.get(i).tradeDate(), i);
        }
        return index;
    }
}
