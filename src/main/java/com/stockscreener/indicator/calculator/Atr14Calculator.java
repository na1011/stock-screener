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
 * "고가 − 종가"가 분할비율만큼 튄다. 그 변환은 {@link PriceScaling}에 있다 — 52주 고저도
 * 같은 것을 필요로 하며, 각자 구현하면 반올림이 어긋나기 시작한다.
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
        sortOrder = 125
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

        for (int i = PERIOD; i < trueRanges.size(); i++) {
            atr = rmaStep(atr, trueRanges.get(i), PERIOD);
            // trueRanges[i]는 bars[i+1]의 것이다 — 첫 봉에는 실체범위가 없어 한 칸 밀려 있다.
            points.add(new IndicatorPoint(bars.get(i + 1).tradeDate(), atr));
        }
        return points;
    }

    /**
     * max(고가−저가, |고가−전일종가|, |저가−전일종가|). 셋 다 수정 축척으로 맞춘 뒤 계산한다.
     *
     * <p>package-private인 것은 {@link Adx14Calculator}가 같은 값을 쓰기 때문이다. 베끼면
     * <b>수정 축척으로 맞춘다는 판단이 두 곳에 생기고</b>, 한쪽만 고쳐도 값이 정상 범위 안이라
     * 어긋난 것이 드러나지 않는다.
     */
    static BigDecimal trueRange(PriceBar current, PriceBar previous) {
        return trueRange(PriceScaling.adjustedHigh(current), PriceScaling.adjustedLow(current),
                previous.adjClose());
    }

    /**
     * 축척을 이미 맞춘 값으로 실체범위를 낸다.
     *
     * <p>{@code PriceBar}를 받는 위 오버로드가 이것을 부른다. 따로 있는 이유는 <b>주봉</b>이
     * {@code PriceBar}가 아니기 때문이다 — {@code WeeklyBar}는 리샘플러가 이미
     * {@code PriceScaling}으로 옮긴 값이라, 여기서 다시 태우면 <b>두 번 보정된다.</b>
     *
     * <p>IMPORTANT — <b>{@code previousClose}가 {@code null}이면 갭이 정의되지 않아
     * {@code 고가 − 저가}를 낸다.</b> 시계열의 첫 봉이 그렇다. <b>0을 넣지 마라</b> —
     * 갭이 주가만큼 벌어진 것으로 계산되어 시드 평균이 통째로 부풀고, 그 봉을 시드에 쓰는
     * 지표가 초기 구간에서 조용히 눌린다.
     */
    static BigDecimal trueRange(BigDecimal high, BigDecimal low, BigDecimal previousClose) {
        BigDecimal range = high.subtract(low);
        if (previousClose == null) {
            return range;
        }
        BigDecimal highGap = high.subtract(previousClose).abs();
        BigDecimal lowGap = low.subtract(previousClose).abs();
        return range.max(highGap).max(lowGap);
    }

    /**
     * Wilder RMA 한 걸음. {@code 새값 = (직전×(기간−1) + 실체범위) ÷ 기간}, 즉 α = 1/기간.
     *
     * <p>{@link #calculate}의 루프 본문을 그대로 뽑은 것이라 값이 같다. 뽑은 이유는 주봉 ATR이
     * 진행 중인 주에 대해 걸음 하나만 밀어야 하기 때문이다.
     *
     * <p>IMPORTANT — <b>{@link MovingAverages#emaStep}과 바꿔 쓰면 안 된다.</b> EMA는
     * α = 2/(기간+1)이라 14에서 {@code 0.1333} 대 {@code 0.0714}로 거의 두 배 다르다.
     * 바꿔 써도 값이 정상 범위 안이라 <b>드러나지 않는다.</b>
     */
    static BigDecimal rmaStep(BigDecimal previous, BigDecimal trueRange, int period) {
        return previous.multiply(BigDecimal.valueOf(period - 1L))
                .add(trueRange)
                .divide(BigDecimal.valueOf(period), MovingAverages.SCALE, RoundingMode.HALF_UP);
    }
}
