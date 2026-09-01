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
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;

/**
 * 주봉 MACD-V 히스토그램 — 주봉 본선에서 그 9주 EMA를 뺀 값.
 *
 * <p>일봉판({@link MacdVHistCalculator})과 같은 뜻이고 봉만 주 단위다. 부호가 주봉 골든/데드
 * 상태를, 크기가 교차까지의 거리를 담는다.
 *
 * <p>둘을 묶는 것이 이 지표의 쓸모다 — {@code MACD_V_HIST_W > 0 AND MACD_V_HIST < 0}이
 * 「주 단위로는 아직 상승 국면인데 일봉이 눌린」 자리다.
 *
 * <p>IMPORTANT — 진행 중인 주를 포함하므로 <b>주봉 골든크로스가 주중에 켜졌다 꺼질 수 있다.</b>
 * 워치리스트에 걸면 엣지 트리거라(ADR-0026) 한 주에 여러 번 울 수 있다.
 */
@Component
@IndicatorMeta(
        name = "주봉 MACD-V 히스토그램",
        description = "주봉 MACD-V에서 그 9주 지수이동평균(시그널선)을 뺀 값. "
                + "0을 위로 지나면 주봉 골든크로스, 아래로 지나면 주봉 데드크로스다.",
        interpretation = "0보다 크면 주 단위로 골든크로스 상태라는 뜻이다. "
                + "일봉 히스토그램과 함께 보면 「주봉은 상승인데 일봉만 꺾였다」를 가릴 수 있다. "
                + "진행 중인 주를 포함하므로 주중에 부호가 뒤집혔다 돌아올 수 있다.",
        category = IndicatorCategory.DERIVED,
        valueType = IndicatorValueType.COUNT,
        direction = IndicatorMeta.Direction.HIGHER_IS_BETTER,
        decimalScale = 2,
        sortOrder = 302,
        bands = {
                @IndicatorMeta.Band(label = "골든크로스 상태", from = 0),
                @IndicatorMeta.Band(label = "데드크로스 상태", to = 0)
        }
)
public class MacdVHistWeeklyCalculator implements IndicatorCalculator {

    public static final String CODE = "MACD_V_HIST_W";

    private final WeeklyMacdVSeries series;

    public MacdVHistWeeklyCalculator(WeeklyMacdVSeries series) {
        this.series = series;
    }

    @Override
    public String code() {
        return CODE;
    }

    @Override
    public DataRequirement dataRequirement() {
        return DataRequirement.priceBars(WeeklyMacdVSeries.LOOKBACK);
    }

    @Override
    public List<IndicatorPoint> calculate(IndicatorContext context) {
        NavigableMap<LocalDate, WeeklyMacdVSeries.Point> points = series.of(context);
        List<IndicatorPoint> result = new ArrayList<>(points.size());
        for (Map.Entry<LocalDate, WeeklyMacdVSeries.Point> entry : points.entrySet()) {
            BigDecimal hist = entry.getValue().hist();
            if (hist != null) {
                result.add(new IndicatorPoint(entry.getKey(), hist));
            }
        }
        return result;
    }
}
