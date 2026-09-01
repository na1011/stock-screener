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
 * 주봉 MACD-V — 일봉판과 <b>같은 공식을 주봉에 건다</b>({@code 주봉 MACD ÷ 주봉 ATR × 100}).
 *
 * <p>일봉이 며칠짜리 흔들림을 담는 데 견줘 주봉은 몇 주짜리 국면을 담는다. 둘을 나란히 놓아야
 * 「주봉은 아직 상승인데 일봉만 꺾였다」가 읽힌다 — {@code OBV_T_13}과 {@code ICHI_OBV_T}가
 * 같은 이유로 두 장이다.
 *
 * <p>MUST: 공식을 일봉판과 같게 유지한다. 변동성 기간도 {@code ATR_14}에 맞춘 14다 —
 * 원 논문은 26이지만 갈리면 이름이 같은 두 카드가 다른 것을 재게 되고 값이 그럴듯해 안 드러난다.
 *
 * <p>MUST NOT: <b>구간(bands)까지 같아야 한다고 읽지 않는다.</b> {@code ICHI_OBV_T}가 임계를
 * 일봉과 맞춘 근거는 그 값이 <b>분포에 무관한 t-통계량</b>이라 ±1.96의 뜻이 두 축에서 같기
 * 때문이다. MACD-V의 ±150은 통계량이 아니라 <b>일봉 관습</b>이고 주봉에서 참인지는
 * 분포를 재서 정한다. 아래 값은 그 측정 전의 잠정치다.
 */
@Component
@IndicatorMeta(
        name = "주봉 MACD-V",
        description = "주봉으로 계산한 MACD-V. 일봉과 같은 공식이고 봉만 주 단위로 접은 것이다. "
                + "일봉이 며칠짜리 흔들림을, 이 값이 몇 주짜리 국면을 담는다.",
        interpretation = "0보다 크면 주 단위 상승 모멘텀이 우세하다는 뜻이다. "
                + "진행 중인 주를 포함해 계산하므로 주중에도 값이 바뀌며, "
                + "주가 끝나야 그 주의 값이 확정된다.",
        category = IndicatorCategory.OSCILLATOR,
        valueType = IndicatorValueType.COUNT,
        direction = IndicatorMeta.Direction.HIGHER_IS_BETTER,
        decimalScale = 2,
        sortOrder = 202,
        bands = {
                @IndicatorMeta.Band(label = "과열", from = 150),
                @IndicatorMeta.Band(label = "상승 모멘텀", from = 50, to = 150),
                @IndicatorMeta.Band(label = "중립", from = -50, to = 50),
                @IndicatorMeta.Band(label = "하락 모멘텀", from = -150, to = -50),
                @IndicatorMeta.Band(label = "과매도", to = -150)
        }
)
public class MacdVWeeklyCalculator implements IndicatorCalculator {

    public static final String CODE = "MACD_V_W";

    private final WeeklyMacdVSeries series;

    public MacdVWeeklyCalculator(WeeklyMacdVSeries series) {
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
            BigDecimal value = entry.getValue().value();
            if (value != null) {
                result.add(new IndicatorPoint(entry.getKey(), value));
            }
        }
        return result;
    }
}
