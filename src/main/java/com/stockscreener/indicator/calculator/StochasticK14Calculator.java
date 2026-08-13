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
 * 스토캐스틱 Slow %K — 최근 14일 고저 범위에서 종가가 차지하는 위치를 3일 평균한 값.
 *
 * <p>Fast %K는 {@code (종가 − 14일 최저) / (14일 최고 − 14일 최저) × 100}이고, Slow %K는 그것을
 * 3일 단순평균한 것이다. <b>Slow(14,3,3)로 고정한다</b> — 자체 계산의 목적은 시장 간 계산식
 * 통일이지 HTS와 다른 값을 만드는 것이 아니다({@link Rsi14Calculator}가 같은 근거로 14를 고정한다).
 *
 * <p>MUST: 고가·저가를 {@link PriceScaling}으로 보정해 쓴다. {@link PriceBar#high()}·
 * {@link PriceBar#low()}는 <b>원주가</b>라 분할 이력이 있는 종목에서 범위가 통째로 어긋나는데,
 * 값은 여전히 0~100 안이라 <b>그럴듯해 보인다.</b>
 */
@Component
@IndicatorMeta(
        name = "스토캐스틱 %K",
        description = "최근 14일의 고가~저가 범위에서 오늘 종가가 어디쯤인지를 0~100으로 나타낸 값"
                + "(3일 평균). 100에 가까울수록 범위의 위쪽에서 마감했다는 뜻이다.",
        interpretation = "80을 넘으면 단기 과열, 20 아래면 단기 침체로 본다. "
                + "다만 강한 추세에서는 한쪽에 오래 머물 수 있어 그 자체로 방향을 뒤집는 신호는 아니다.",
        category = IndicatorCategory.OSCILLATOR,
        valueType = IndicatorValueType.PERCENT,
        unit = "%",
        decimalScale = 2,
        direction = IndicatorMeta.Direction.NEUTRAL,
        sortOrder = 210,
        bands = {
                @IndicatorMeta.Band(label = "과열", from = 80),
                @IndicatorMeta.Band(label = "중립", from = 20, to = 80),
                @IndicatorMeta.Band(label = "침체", to = 20)
        }
)
public class StochasticK14Calculator implements IndicatorCalculator {

    public static final String CODE = "STOCH_K_14";
    /** 고저 범위를 재는 창. */
    static final int PERIOD = 14;
    /** Fast %K를 매끄럽게 하는 창. 이것이 붙어야 "Slow"다. */
    static final int SMOOTHING = 3;
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    @Override
    public String code() {
        return CODE;
    }

    @Override
    public DataRequirement dataRequirement() {
        return DataRequirement.priceBars(PERIOD + SMOOTHING - 1);
    }

    @Override
    public List<IndicatorPoint> calculate(IndicatorContext context) {
        List<PriceBar> bars = context.priceBars();
        if (bars.size() < PERIOD + SMOOTHING - 1) {
            return List.of();
        }

        // Fast %K를 먼저 다 구한다. 인덱스는 bars와 같게 두고 값이 없는 자리는 null로 남긴다 —
        // 평활 창에 구멍이 있으면 그 날짜의 Slow %K도 내지 않아야 하기 때문이다.
        BigDecimal[] fastK = new BigDecimal[bars.size()];
        for (int end = PERIOD - 1; end < bars.size(); end++) {
            BigDecimal highest = null;
            BigDecimal lowest = null;
            for (int i = end - PERIOD + 1; i <= end; i++) {
                BigDecimal high = PriceScaling.adjustedHigh(bars.get(i));
                BigDecimal low = PriceScaling.adjustedLow(bars.get(i));
                highest = highest == null || high.compareTo(highest) > 0 ? high : highest;
                lowest = lowest == null || low.compareTo(lowest) < 0 ? low : lowest;
            }
            BigDecimal range = highest.subtract(lowest);
            if (range.signum() == 0) {
                // 14일간 고가와 저가가 완전히 같았다(대개 장기 거래정지다). 위치를 정의할 수
                // 없으므로 값을 내지 않는다 — 0이나 50을 지어내면 과열·침체 판정이 뒤집힌다.
                continue;
            }
            fastK[end] = bars.get(end).adjClose().subtract(lowest)
                    .multiply(HUNDRED)
                    .divide(range, MovingAverages.MC)
                    .setScale(MovingAverages.SCALE, RoundingMode.HALF_UP);
        }

        List<IndicatorPoint> points = new ArrayList<>();
        for (int end = PERIOD + SMOOTHING - 2; end < bars.size(); end++) {
            BigDecimal sum = BigDecimal.ZERO;
            boolean complete = true;
            for (int i = end - SMOOTHING + 1; i <= end; i++) {
                if (fastK[i] == null) {
                    complete = false;
                    break;
                }
                sum = sum.add(fastK[i]);
            }
            if (!complete) {
                continue;
            }
            BigDecimal slowK = sum.divide(BigDecimal.valueOf(SMOOTHING),
                    MovingAverages.SCALE, RoundingMode.HALF_UP);
            points.add(new IndicatorPoint(bars.get(end).tradeDate(), slowK));
        }
        return points;
    }
}
