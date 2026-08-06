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
import java.util.ArrayList;
import java.util.List;

/**
 * 볼린저밴드 %B — 밴드 안에서 현재가가 차지하는 위치.
 *
 * <p>밴드는 20일 이동평균 ± (표준편차 × 2)이며, %B는 그 하단을 0, 상단을 1로 두고 종가가
 * 어디쯤인지를 나타낸다. 밴드의 상·하단을 각각 지표로 저장하지 않고 %B 하나만 두는 이유는
 * <b>상·하단이 가격 단위여서 종목 간 비교가 안 되기 때문</b>이다. %B는 무차원이라 그대로
 * 스크리닝 조건이 된다.
 *
 * <p>표준편차는 <b>모표준편차</b>(n으로 나눔)를 쓴다. 볼린저 원안이 그렇고, 표본표준편차
 * (n-1)로 계산하면 값이 미세하게 커져 HTS와 어긋난다.
 */
@Component
@IndicatorMeta(
        name = "볼린저 %B",
        description = "20일 볼린저밴드 안에서 종가의 상대적 위치. "
                + "밴드 하단이 0, 상단이 1이며 범위를 벗어나면 0 미만이나 1 초과가 된다.",
        interpretation = "1에 가까우면 최근 변동폭의 위쪽 끝, 0에 가까우면 아래쪽 끝에 있다는 뜻이다. "
                + "1을 넘으면 단기 과열, 0 아래면 단기 침체로 보지만 강한 추세에서는 오래 머물 수 있다.",
        category = IndicatorCategory.TECHNICAL,
        valueType = IndicatorValueType.RATIO,
        direction = IndicatorMeta.Direction.NEUTRAL,
        decimalScale = 4,
        sortOrder = 150
)
public class BollingerPercentBCalculator implements IndicatorCalculator {

    public static final String CODE = "BOLLINGER_PCT_B";
    private static final int PERIOD = 20;
    private static final BigDecimal MULTIPLIER = BigDecimal.valueOf(2);

    @Override
    public String code() {
        return CODE;
    }

    @Override
    public DataRequirement dataRequirement() {
        return DataRequirement.priceBars(PERIOD);
    }

    @Override
    public List<IndicatorPoint> calculate(IndicatorContext context) {
        List<PriceBar> bars = context.priceBars();
        if (bars.size() < PERIOD) {
            return List.of();
        }

        BigDecimal period = BigDecimal.valueOf(PERIOD);
        List<IndicatorPoint> points = new ArrayList<>(bars.size() - PERIOD + 1);

        for (int end = PERIOD - 1; end < bars.size(); end++) {
            int start = end - PERIOD + 1;

            BigDecimal sum = BigDecimal.ZERO;
            for (int i = start; i <= end; i++) {
                sum = sum.add(bars.get(i).adjClose());
            }
            BigDecimal mean = sum.divide(period, MovingAverages.SCALE, RoundingMode.HALF_UP);

            BigDecimal squaredDeviations = BigDecimal.ZERO;
            for (int i = start; i <= end; i++) {
                BigDecimal deviation = bars.get(i).adjClose().subtract(mean);
                squaredDeviations = squaredDeviations.add(deviation.multiply(deviation));
            }
            BigDecimal variance =
                    squaredDeviations.divide(period, MovingAverages.SCALE, RoundingMode.HALF_UP);
            BigDecimal stdDev = variance.sqrt(MovingAverages.MC);

            // 하단에서 상단까지의 거리 = (평균 + 2σ) − (평균 − 2σ) = 4σ
            BigDecimal halfWidth = stdDev.multiply(MULTIPLIER);
            BigDecimal fullWidth = halfWidth.multiply(MULTIPLIER);
            if (fullWidth.signum() == 0) {
                // 20일간 종가가 완전히 동일했다. 밴드 폭이 0이면 위치를 정의할 수 없다 —
                // 값을 내지 않는 편이 0이나 0.5를 지어내는 것보다 정직하다.
                continue;
            }

            BigDecimal lower = mean.subtract(halfWidth);
            BigDecimal percentB = bars.get(end).adjClose().subtract(lower)
                    .divide(fullWidth, MovingAverages.MC)
                    .setScale(MovingAverages.SCALE, RoundingMode.HALF_UP);
            points.add(new IndicatorPoint(bars.get(end).tradeDate(), percentB));
        }
        return points;
    }
}
