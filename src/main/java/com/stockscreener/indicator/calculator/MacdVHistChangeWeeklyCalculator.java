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
 * 주봉 MACD-V 히스토그램이 <b>직전 완결 주</b>보다 얼마나 움직였는가.
 *
 * <p>진행 중인 주를 포함하므로 이 값은 "이번 주 들어 히스토그램이 어느 쪽으로 얼마나 갔나"이고,
 * 주가 넘어가면 기준이 갈린다. 일봉판이 재는 5거래일과 <b>같은 길이(≈1주)</b>를 재도록 맞춘 것이라
 * 두 카드를 나란히 읽을 수 있다.
 *
 * <p>MUST NOT: 비율(%)로 내지 않는다. 히스토그램은 부호가 바뀌는 값이라 0 근처에서 비율이
 * 발산하고, 음수 대비 음수의 「몇 % 증가」가 방향을 뒤집어 읽힌다.
 */
@Component
@IndicatorMeta(
        name = "주봉 MACD-V 히스토그램 변화량(1주)",
        description = "주봉 MACD-V 히스토그램이 직전 완결 주보다 얼마나 커졌는지. "
                + "양수면 골든크로스 쪽으로, 음수면 데드크로스 쪽으로 움직이고 있다는 뜻이다.",
        interpretation = "주봉 히스토그램이 음수인데 이 값이 양수면 「주 단위로 데드 상태지만 "
                + "골든크로스로 다가가고 있다」는 뜻이다. 진행 중인 주를 포함하므로 "
                + "주 초에는 표본이 하루뿐일 수 있다.",
        category = IndicatorCategory.DERIVED,
        valueType = IndicatorValueType.COUNT,
        direction = IndicatorMeta.Direction.HIGHER_IS_BETTER,
        decimalScale = 2,
        sortOrder = 304,
        bands = {
                @IndicatorMeta.Band(label = "골든크로스 쪽으로", from = 0),
                @IndicatorMeta.Band(label = "데드크로스 쪽으로", to = 0)
        }
)
public class MacdVHistChangeWeeklyCalculator implements IndicatorCalculator {

    public static final String CODE = "MACD_V_HIST_CHG_W";

    private final WeeklyMacdVSeries series;

    public MacdVHistChangeWeeklyCalculator(WeeklyMacdVSeries series) {
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
            BigDecimal change = entry.getValue().histChange();
            if (change != null) {
                result.add(new IndicatorPoint(entry.getKey(), change));
            }
        }
        return result;
    }
}
