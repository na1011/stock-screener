package com.stockscreener.indicator.calculator;

import com.stockscreener.indicator.IndicatorPoint;
import com.stockscreener.indicator.PriceBar;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

/**
 * 이동평균 계산의 공통 구현.
 *
 * <p>SMA는 기간만 다른 지표가 여럿이고 EMA·MACD가 같은 평활을 재사용한다. 계산기마다 창을
 * 다시 구현하면 그중 하나의 반올림만 달라져도 지표 간 비교가 조용히 어긋난다.
 *
 * <p>MUST — 이동평균은 {@link PriceBar#adjClose()}로 계산한다(ADR-0002). 원주가를 쓰면
 * 액면분할 당일 값이 분할비율만큼 튀어 거짓 신호가 된다.
 */
final class MovingAverages {

    /**
     * 저장 컬럼이 {@code numeric(24,8)}이라 그보다 정밀하게 계산해도 저장에서 잘린다.
     * 나눗셈 중간값은 이 자릿수로 맞춘다.
     */
    static final int SCALE = 8;

    /** 나눗셈이 무한소수가 되는 경우를 위해 정밀도를 고정한다. */
    static final MathContext MC = new MathContext(20, RoundingMode.HALF_UP);

    private MovingAverages() {
    }

    /**
     * 단순이동평균. 이력이 {@code period}에 못 미치면 빈 목록이다.
     *
     * <p>창을 미끄러뜨리며 더하고 빼기 때문에 봉 수에 선형이다. 매 시점 구간을 다시
     * 합산하면 200일 이동평균이 종목마다 200배의 덧셈이 된다.
     */
    static List<IndicatorPoint> sma(List<PriceBar> bars, int period) {
        if (bars.size() < period) {
            return List.of();
        }
        List<IndicatorPoint> points = new ArrayList<>(bars.size() - period + 1);
        BigDecimal window = BigDecimal.ZERO;
        for (int i = 0; i < bars.size(); i++) {
            window = window.add(bars.get(i).adjClose());
            if (i >= period) {
                window = window.subtract(bars.get(i - period).adjClose());
            }
            if (i >= period - 1) {
                points.add(new IndicatorPoint(
                        bars.get(i).tradeDate(),
                        window.divide(BigDecimal.valueOf(period), SCALE, RoundingMode.HALF_UP)));
            }
        }
        return points;
    }

    /**
     * 지수이동평균. 첫 값은 {@code period}개의 SMA로 시드한다.
     *
     * <p>시드를 두는 이유는 EMA가 재귀식이라 시작점이 필요하기 때문이다. 첫 종가를 그대로
     * 시드로 쓰는 구현도 흔하지만, 그러면 초기 구간이 실제 추세보다 첫 봉에 과하게 끌린다.
     * SMA 시드는 그 편향을 없애고, 대신 앞의 {@code period-1}개 시점에는 값을 내지 않는다.
     */
    static List<BigDecimal> ema(List<BigDecimal> values, int period) {
        if (values.size() < period) {
            return List.of();
        }
        // 평활계수 α = 2 / (기간 + 1)
        BigDecimal alpha = BigDecimal.valueOf(2)
                .divide(BigDecimal.valueOf(period + 1L), MC);
        BigDecimal oneMinusAlpha = BigDecimal.ONE.subtract(alpha);

        BigDecimal seed = BigDecimal.ZERO;
        for (int i = 0; i < period; i++) {
            seed = seed.add(values.get(i));
        }
        BigDecimal current = seed.divide(BigDecimal.valueOf(period), SCALE, RoundingMode.HALF_UP);

        List<BigDecimal> result = new ArrayList<>(values.size() - period + 1);
        result.add(current);
        for (int i = period; i < values.size(); i++) {
            current = values.get(i).multiply(alpha, MC)
                    .add(current.multiply(oneMinusAlpha, MC))
                    .setScale(SCALE, RoundingMode.HALF_UP);
            result.add(current);
        }
        return result;
    }

    /** 수정종가 시계열. EMA·RSI처럼 값 목록만 필요한 계산에 쓴다. */
    static List<BigDecimal> adjCloses(List<PriceBar> bars) {
        List<BigDecimal> values = new ArrayList<>(bars.size());
        for (PriceBar bar : bars) {
            values.add(bar.adjClose());
        }
        return values;
    }
}
