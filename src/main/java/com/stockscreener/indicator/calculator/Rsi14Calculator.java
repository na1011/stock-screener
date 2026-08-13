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
 * 14일 상대강도지수(RSI).
 *
 * <p>IMPORTANT — 평활은 <b>Wilder 방식</b>이다. 단순평균이나 일반 EMA로 계산하면 값이 달라지고,
 * 그러면 사용자가 증권사 HTS에서 보는 RSI와 어긋난다. 지표를 자체 계산하는 목적은 시장 간
 * 계산식을 통일하는 것이지 업계 관행과 다른 값을 만드는 것이 아니다.
 *
 * <p>Wilder 평활은 {@code 다음 = (이전 × (n-1) + 이번) / n}이며, 이는 α = 1/n인 EMA와 같다.
 * 일반적인 EMA의 α = 2/(n+1)과 다르다는 점이 흔한 구현 오류의 원인이다.
 */
@Component
@IndicatorMeta(
        name = "RSI(14)",
        description = "최근 14거래일 동안의 상승폭과 하락폭의 비율. 0~100 사이의 값을 가진다.",
        interpretation = "통상 70 이상이면 단기 과열(과매수), 30 이하면 단기 침체(과매도)로 본다. "
                + "다만 강한 추세에서는 한쪽에 오래 머물 수 있어 단독 매매 근거로는 약하다.",
        category = IndicatorCategory.TECHNICAL,
        valueType = IndicatorValueType.COUNT,
        direction = IndicatorMeta.Direction.NEUTRAL,
        decimalScale = 2,
        sortOrder = 120,
        // 70·30은 interpretation이 이미 적어 둔 경계다. 여기서 새로 정하는 것이 아니다.
        bands = {
                @IndicatorMeta.Band(label = "과매수", from = 70),
                @IndicatorMeta.Band(label = "상승 우위", from = 50, to = 70),
                @IndicatorMeta.Band(label = "하락 우위", from = 30, to = 50),
                @IndicatorMeta.Band(label = "과매도", to = 30)
        }
)
public class Rsi14Calculator implements IndicatorCalculator {

    public static final String CODE = "RSI_14";
    private static final int PERIOD = 14;
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    /** 변화량을 만들려면 첫 봉이 하나 더 필요하다. */
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

        BigDecimal period = BigDecimal.valueOf(PERIOD);
        BigDecimal gainSum = BigDecimal.ZERO;
        BigDecimal lossSum = BigDecimal.ZERO;

        // 첫 구간은 단순 평균으로 시드한다(Wilder 원안).
        for (int i = 1; i <= PERIOD; i++) {
            BigDecimal change = bars.get(i).adjClose().subtract(bars.get(i - 1).adjClose());
            if (change.signum() > 0) {
                gainSum = gainSum.add(change);
            } else {
                lossSum = lossSum.subtract(change);
            }
        }
        BigDecimal avgGain = gainSum.divide(period, MovingAverages.SCALE, RoundingMode.HALF_UP);
        BigDecimal avgLoss = lossSum.divide(period, MovingAverages.SCALE, RoundingMode.HALF_UP);

        List<IndicatorPoint> points = new ArrayList<>(bars.size() - PERIOD);
        points.add(new IndicatorPoint(bars.get(PERIOD).tradeDate(), rsi(avgGain, avgLoss)));

        BigDecimal periodLessOne = BigDecimal.valueOf(PERIOD - 1L);
        for (int i = PERIOD + 1; i < bars.size(); i++) {
            BigDecimal change = bars.get(i).adjClose().subtract(bars.get(i - 1).adjClose());
            BigDecimal gain = change.signum() > 0 ? change : BigDecimal.ZERO;
            BigDecimal loss = change.signum() < 0 ? change.negate() : BigDecimal.ZERO;

            avgGain = avgGain.multiply(periodLessOne).add(gain)
                    .divide(period, MovingAverages.SCALE, RoundingMode.HALF_UP);
            avgLoss = avgLoss.multiply(periodLessOne).add(loss)
                    .divide(period, MovingAverages.SCALE, RoundingMode.HALF_UP);

            points.add(new IndicatorPoint(bars.get(i).tradeDate(), rsi(avgGain, avgLoss)));
        }
        return points;
    }

    /**
     * 하락이 전혀 없으면 RSI는 100이다.
     *
     * <p>0으로 나누기를 피하려는 특례가 아니라 정의상 그렇다 — 상승만 있었다는 뜻이다.
     * 이 분기가 없으면 강한 상승 종목에서 배치가 {@link ArithmeticException}으로 죽는다.
     */
    private static BigDecimal rsi(BigDecimal avgGain, BigDecimal avgLoss) {
        if (avgLoss.signum() == 0) {
            return avgGain.signum() == 0
                    // 14일간 변화가 전혀 없었다. 중립으로 둔다.
                    ? BigDecimal.valueOf(50).setScale(MovingAverages.SCALE)
                    : HUNDRED.setScale(MovingAverages.SCALE);
        }
        BigDecimal rs = avgGain.divide(avgLoss, MovingAverages.MC);
        return HUNDRED.subtract(
                HUNDRED.divide(BigDecimal.ONE.add(rs), MovingAverages.MC))
                .setScale(MovingAverages.SCALE, RoundingMode.HALF_UP);
    }
}
