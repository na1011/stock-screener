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
 * CCI(20) — 전형가격이 20일 평균에서 <b>평균편차의 몇 배</b>만큼 벗어났는가.
 *
 * <p>{@code (TP − SMA₂₀(TP)) / (0.015 × 평균편차)}이고 전형가격 TP는 {@code (고가+저가+종가)/3}이다.
 * 상수 0.015는 램버트가 "값의 70~80%가 ±100 안에 들도록" 고른 것이라 <b>±100이 곧 관습적 경계</b>다.
 *
 * <p>볼린저 %B·스토캐스틱과 <b>재는 축이 다르다.</b> 볼린저는 표준편차, 스토캐스틱은 고저 범위,
 * 이것은 <b>평균편차</b>다. 표준편차와 달리 제곱을 하지 않아 이상치 하나에 덜 끌려간다.
 *
 * <p>MUST: 고가·저가를 {@link PriceScaling}으로 보정해 쓴다. 종가만 수정하고 고저를 원주가로
 * 두면 전형가격이 섞인 축척이 되는데, 값은 여전히 ±수백 안이라 그럴듯해 보인다.
 */
@Component
@IndicatorMeta(
        name = "CCI(20)",
        description = "전형가격(고가·저가·종가의 평균)이 최근 20일 평균에서 얼마나 벗어났는지를 "
                + "나타낸 값. 통상 ±100 안에서 움직인다.",
        interpretation = "+100을 넘으면 평소보다 뚜렷하게 비싸고 −100 아래면 뚜렷하게 싸다는 뜻이다. "
                + "추세 초입에는 100을 넘어선 뒤에도 계속 오르는 경우가 많아, "
                + "되돌림 신호로 쓸지 돌파 신호로 쓸지는 추세 강도와 함께 본다.",
        category = IndicatorCategory.OSCILLATOR,
        valueType = IndicatorValueType.RATIO,
        decimalScale = 2,
        direction = IndicatorMeta.Direction.NEUTRAL,
        sortOrder = 206,
        bands = {
                @IndicatorMeta.Band(label = "과열", from = 100),
                @IndicatorMeta.Band(label = "중립", from = -100, to = 100),
                @IndicatorMeta.Band(label = "침체", to = -100)
        }
)
public class Cci20Calculator implements IndicatorCalculator {

    public static final String CODE = "CCI_20";
    private static final int PERIOD = 20;
    /** 램버트의 상수. 값의 대부분이 ±100 안에 들도록 고른 것이다. */
    private static final BigDecimal LAMBERT = new BigDecimal("0.015");
    private static final BigDecimal THREE = BigDecimal.valueOf(3);

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

        BigDecimal[] typical = new BigDecimal[bars.size()];
        for (int i = 0; i < bars.size(); i++) {
            typical[i] = typicalPrice(bars.get(i));
        }

        BigDecimal period = BigDecimal.valueOf(PERIOD);
        List<IndicatorPoint> points = new ArrayList<>(bars.size() - PERIOD + 1);

        for (int end = PERIOD - 1; end < bars.size(); end++) {
            int start = end - PERIOD + 1;

            BigDecimal sum = BigDecimal.ZERO;
            for (int i = start; i <= end; i++) {
                sum = sum.add(typical[i]);
            }
            BigDecimal mean = sum.divide(period, MovingAverages.SCALE, RoundingMode.HALF_UP);

            // 평균편차다 — 표준편차가 아니다. 제곱하지 않으므로 이상치 하나에 덜 끌려간다.
            BigDecimal deviationSum = BigDecimal.ZERO;
            for (int i = start; i <= end; i++) {
                deviationSum = deviationSum.add(typical[i].subtract(mean).abs());
            }
            BigDecimal meanDeviation =
                    deviationSum.divide(period, MovingAverages.SCALE, RoundingMode.HALF_UP);
            if (meanDeviation.signum() == 0) {
                // 20일간 전형가격이 완전히 같았다(대개 장기 거래정지다). 나눌 수 없다.
                continue;
            }

            BigDecimal cci = typical[end].subtract(mean)
                    .divide(LAMBERT.multiply(meanDeviation), MovingAverages.MC)
                    .setScale(MovingAverages.SCALE, RoundingMode.HALF_UP);
            points.add(new IndicatorPoint(bars.get(end).tradeDate(), cci));
        }
        return points;
    }

    /** (고가+저가+종가)/3. 셋 다 수정 축척이어야 한다. */
    static BigDecimal typicalPrice(PriceBar bar) {
        return PriceScaling.adjustedHigh(bar)
                .add(PriceScaling.adjustedLow(bar))
                .add(bar.adjClose())
                .divide(THREE, MovingAverages.SCALE, RoundingMode.HALF_UP);
    }
}
