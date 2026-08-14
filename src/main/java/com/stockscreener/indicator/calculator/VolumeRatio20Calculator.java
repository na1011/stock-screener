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
 * 거래량 비율 — 오늘 거래량이 <b>평소의 몇 배인가</b>(20일 평균 대비).
 *
 * <p>거래량 자체는 종목마다 자릿수가 달라 비교가 안 된다. 자기 평균으로 나누면 무차원이 되어
 * <b>대형주와 소형주의 "붐빔"을 같은 자로 잴 수 있다.</b> 1.0이 평소이고 2.0이면 두 배다.
 *
 * <p>IMPORTANT — 이것은 <b>방향을 말하지 않는다.</b> 거래가 몰렸다는 것이 오르는 쪽인지
 * 내리는 쪽인지는 여기 없다 — 그것은 {@code CMF_21}·{@code OBV_T_13}이 답한다. 그래서
 * 유불리 방향을 {@code NEUTRAL}로 둔다: 급증이 좋은 신호라고 화면이 말하면 안 된다.
 */
@Component
@IndicatorMeta(
        name = "거래량 비율(20일)",
        description = "오늘 거래량이 최근 20일 평균의 몇 배인지. 1.0이면 평소만큼 거래됐다는 뜻이다.",
        interpretation = "2.0을 넘으면 평소의 두 배 넘게 거래된 것이다. "
                + "다만 몰린 거래가 사자 쪽인지 팔자 쪽인지는 이 값이 말하지 않는다.",
        category = IndicatorCategory.OSCILLATOR,
        valueType = IndicatorValueType.RATIO,
        // 급증이 좋은 신호가 아니다. 방향은 CMF·OBV가 답한다.
        direction = IndicatorMeta.Direction.NEUTRAL,
        decimalScale = 2,
        sortOrder = 216,
        bands = {
                @IndicatorMeta.Band(label = "급증", from = 2),
                @IndicatorMeta.Band(label = "활발", from = 1.2, to = 2),
                @IndicatorMeta.Band(label = "평소", from = 0.8, to = 1.2),
                @IndicatorMeta.Band(label = "한산", to = 0.8)
        })
public class VolumeRatio20Calculator implements IndicatorCalculator {

    public static final String CODE = "VOLUME_RATIO_20";
    private static final int PERIOD = 20;
    private static final int SCALE = 8;

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

        // MUST: 수정 축척으로 맞춘다. 분할 전후의 거래량을 그대로 견주면 분할 당일부터
        // 비율이 배수만큼 튀는데, 그 값은 "급증"으로 그럴듯하게 읽힌다.
        List<BigDecimal> volumes = new ArrayList<>(bars.size());
        for (PriceBar bar : bars) {
            volumes.add(PriceScaling.adjustedVolume(bar));
        }
        List<BigDecimal> sums = MovingAverages.rollingSum(volumes, PERIOD);
        BigDecimal period = BigDecimal.valueOf(PERIOD);

        List<IndicatorPoint> points = new ArrayList<>(bars.size());
        for (int i = 0; i < bars.size(); i++) {
            BigDecimal sum = sums.get(i);
            if (sum == null || sum.signum() == 0) {
                // 창이 덜 찼거나 20일 내내 거래가 없었다. 나눌 수 없으므로 행을 쓰지 않는다.
                continue;
            }
            BigDecimal average = sum.divide(period, SCALE, RoundingMode.HALF_UP);
            points.add(new IndicatorPoint(
                    bars.get(i).tradeDate(),
                    volumes.get(i).divide(average, SCALE, RoundingMode.HALF_UP)));
        }
        return points;
    }
}
