package com.stockscreener.indicator.calculator;

import com.stockscreener.indicator.IndicatorPoint;
import com.stockscreener.indicator.PriceBar;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

/**
 * RSI 공식의 정본. 기간만 다른 계산기 둘이 이것을 공유한다({@code RSI_14}·{@code RSI_2}).
 *
 * <p>IMPORTANT — 평활은 <b>Wilder 방식</b>이다. 단순평균이나 일반 EMA로 계산하면 값이 달라지고,
 * 그러면 사용자가 증권사 HTS에서 보는 RSI와 어긋난다. 지표를 자체 계산하는 목적은 시장 간
 * 계산식을 통일하는 것이지 업계 관행과 다른 값을 만드는 것이 아니다.
 *
 * <p>Wilder 평활은 {@code 다음 = (이전 × (n-1) + 이번) / n}이며, 이는 α = 1/n인 EMA와 같다.
 * 일반적인 EMA의 α = 2/(n+1)과 다르다는 점이 흔한 구현 오류의 원인이다.
 *
 * <p>MUST NOT: 기간별로 이 공식을 다시 짜지 않는다. 짧은 기간에서 값이 극단으로 가는 것은
 * 버그가 아니라 성질이고, 그것을 「완화」하려고 평활을 바꾸면 <b>같은 이름의 지표 둘이
 * 다른 공식을 쓰게 된다.</b>
 */
final class RelativeStrengthIndex {

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    private RelativeStrengthIndex() {
    }

    /**
     * @param bars   일봉. {@code period + 1}개 미만이면 빈 목록을 돌려준다(변화량을 만들려면
     *               첫 봉이 하나 더 필요하다).
     * @param period Wilder 평활 기간.
     */
    static List<IndicatorPoint> rsi(List<PriceBar> bars, int period) {
        if (bars.size() < period + 1) {
            return List.of();
        }

        BigDecimal n = BigDecimal.valueOf(period);
        BigDecimal gainSum = BigDecimal.ZERO;
        BigDecimal lossSum = BigDecimal.ZERO;

        // 첫 구간은 단순 평균으로 시드한다(Wilder 원안).
        for (int i = 1; i <= period; i++) {
            BigDecimal change = bars.get(i).adjClose().subtract(bars.get(i - 1).adjClose());
            if (change.signum() > 0) {
                gainSum = gainSum.add(change);
            } else {
                lossSum = lossSum.subtract(change);
            }
        }
        BigDecimal avgGain = gainSum.divide(n, MovingAverages.SCALE, RoundingMode.HALF_UP);
        BigDecimal avgLoss = lossSum.divide(n, MovingAverages.SCALE, RoundingMode.HALF_UP);

        List<IndicatorPoint> points = new ArrayList<>(bars.size() - period);
        points.add(new IndicatorPoint(bars.get(period).tradeDate(), value(avgGain, avgLoss)));

        BigDecimal nLessOne = BigDecimal.valueOf(period - 1L);
        for (int i = period + 1; i < bars.size(); i++) {
            BigDecimal change = bars.get(i).adjClose().subtract(bars.get(i - 1).adjClose());
            BigDecimal gain = change.signum() > 0 ? change : BigDecimal.ZERO;
            BigDecimal loss = change.signum() < 0 ? change.negate() : BigDecimal.ZERO;

            avgGain = avgGain.multiply(nLessOne).add(gain)
                    .divide(n, MovingAverages.SCALE, RoundingMode.HALF_UP);
            avgLoss = avgLoss.multiply(nLessOne).add(loss)
                    .divide(n, MovingAverages.SCALE, RoundingMode.HALF_UP);

            points.add(new IndicatorPoint(bars.get(i).tradeDate(), value(avgGain, avgLoss)));
        }
        return points;
    }

    /**
     * 하락이 전혀 없으면 RSI는 100이다.
     *
     * <p>0으로 나누기를 피하려는 특례가 아니라 정의상 그렇다 — 상승만 있었다는 뜻이다.
     * 이 분기가 없으면 강한 상승 종목에서 배치가 {@link ArithmeticException}으로 죽는다.
     * <b>기간이 짧을수록 이 분기에 걸리는 날이 늘어난다</b> — {@code RSI_2}는 이틀 연속
     * 상승이면 100이다.
     */
    private static BigDecimal value(BigDecimal avgGain, BigDecimal avgLoss) {
        if (avgLoss.signum() == 0) {
            return avgGain.signum() == 0
                    // 구간 내 변화가 전혀 없었다. 중립으로 둔다.
                    ? BigDecimal.valueOf(50).setScale(MovingAverages.SCALE)
                    : HUNDRED.setScale(MovingAverages.SCALE);
        }
        BigDecimal rs = avgGain.divide(avgLoss, MovingAverages.MC);
        return HUNDRED.subtract(HUNDRED.divide(BigDecimal.ONE.add(rs), MovingAverages.MC))
                .setScale(MovingAverages.SCALE, RoundingMode.HALF_UP);
    }
}
