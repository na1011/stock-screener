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
 * 14일 평균실체범위(ATR). 변동성의 크기를 가격 단위로 나타낸다.
 *
 * <p>IMPORTANT — 고가·저가에 <b>수정계수를 적용해서</b> 쓴다. {@code PriceBar}의 고가·저가는
 * 원주가인 반면 {@code adjClose}만 수정되어 있어, 둘을 그대로 섞으면 액면분할 구간에서
 * "고가 − 종가"가 분할비율만큼 튄다. 같은 봉의 {@code adjClose / close} 비율을 곱해
 * 축척을 맞춘다.
 *
 * <p>ATR은 값의 크기가 주가에 비례해 <b>종목 간 비교가 불가능하다</b>. 그래서 이 지표 자체를
 * 조건으로 쓰기보다 MACD-V처럼 다른 지표를 정규화하는 분모로 쓴다.
 *
 * <p>평활은 RSI와 같은 Wilder 방식이다.
 */
@Component
@IndicatorMeta(
        name = "ATR(14)",
        description = "최근 14거래일의 하루 평균 변동폭을 가격 단위로 나타낸 값. "
                + "전일 종가를 포함한 실체범위(True Range)의 평균이다.",
        interpretation = "값이 클수록 하루 등락폭이 크다는 뜻으로, 변동성이 높음을 나타낸다. "
                + "주가 수준에 비례하므로 다른 종목과 직접 비교하지 않는다.",
        category = IndicatorCategory.TECHNICAL,
        valueType = IndicatorValueType.PRICE,
        direction = IndicatorMeta.Direction.NEUTRAL,
        decimalScale = 2,
        sortOrder = 120
)
public class Atr14Calculator implements IndicatorCalculator {

    public static final String CODE = "ATR_14";
    private static final int PERIOD = 14;

    /** 실체범위는 전일 종가를 쓰므로 봉이 하나 더 필요하다. */
    @Override
    public DataRequirement dataRequirement() {
        return DataRequirement.priceBars(PERIOD + 1);
    }

    @Override
    public String code() {
        return CODE;
    }

    @Override
    public List<IndicatorPoint> calculate(IndicatorContext context) {
        List<PriceBar> bars = context.priceBars();
        if (bars.size() < PERIOD + 1) {
            return List.of();
        }

        List<BigDecimal> trueRanges = new ArrayList<>(bars.size() - 1);
        for (int i = 1; i < bars.size(); i++) {
            trueRanges.add(trueRange(bars.get(i), bars.get(i - 1)));
        }

        BigDecimal period = BigDecimal.valueOf(PERIOD);
        BigDecimal sum = BigDecimal.ZERO;
        for (int i = 0; i < PERIOD; i++) {
            sum = sum.add(trueRanges.get(i));
        }
        BigDecimal atr = sum.divide(period, MovingAverages.SCALE, RoundingMode.HALF_UP);

        List<IndicatorPoint> points = new ArrayList<>(trueRanges.size() - PERIOD + 1);
        points.add(new IndicatorPoint(bars.get(PERIOD).tradeDate(), atr));

        BigDecimal periodLessOne = BigDecimal.valueOf(PERIOD - 1L);
        for (int i = PERIOD; i < trueRanges.size(); i++) {
            atr = atr.multiply(periodLessOne).add(trueRanges.get(i))
                    .divide(period, MovingAverages.SCALE, RoundingMode.HALF_UP);
            // trueRanges[i]는 bars[i+1]의 것이다 — 첫 봉에는 실체범위가 없어 한 칸 밀려 있다.
            points.add(new IndicatorPoint(bars.get(i + 1).tradeDate(), atr));
        }
        return points;
    }

    /** max(고가−저가, |고가−전일종가|, |저가−전일종가|). 셋 다 수정 축척으로 맞춘 뒤 계산한다. */
    private static BigDecimal trueRange(PriceBar current, PriceBar previous) {
        BigDecimal high = adjusted(current, current.high());
        BigDecimal low = adjusted(current, current.low());
        BigDecimal previousClose = previous.adjClose();

        BigDecimal range = high.subtract(low);
        BigDecimal highGap = high.subtract(previousClose).abs();
        BigDecimal lowGap = low.subtract(previousClose).abs();
        return range.max(highGap).max(lowGap);
    }

    /**
     * 원주가 항목을 그 봉의 수정 축척으로 옮긴다.
     *
     * <p>{@code close}가 0이면 나눌 수 없다. 실제로는 발생하지 않지만
     * ({@code price_daily}에 0 종가가 들어오면 그것 자체가 수집 결함이다) 배치를
     * 죽이지 않도록 원값을 그대로 쓴다.
     */
    private static BigDecimal adjusted(PriceBar bar, BigDecimal rawValue) {
        if (bar.close().signum() == 0) {
            return rawValue;
        }
        return rawValue.multiply(bar.adjClose())
                .divide(bar.close(), MovingAverages.SCALE, RoundingMode.HALF_UP);
    }
}
