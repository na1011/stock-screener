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
public final class MovingAverages {

    /**
     * 저장 컬럼이 {@code numeric(24,8)}이라 그보다 정밀하게 계산해도 저장에서 잘린다.
     * 나눗셈 중간값은 이 자릿수로 맞춘다.
     */
    public static final int SCALE = 8;

    /** 나눗셈이 무한소수가 되는 경우를 위해 정밀도를 고정한다. */
    public static final MathContext MC = new MathContext(20, RoundingMode.HALF_UP);

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
            current = emaStep(current, values.get(i), period);
            result.add(current);
        }
        return result;
    }

    /**
     * EMA 한 걸음. {@code 새값 = x·α + 직전·(1−α)}, α = 2/(기간+1).
     *
     * <p>{@link #ema}의 루프 본문을 그대로 뽑은 것이라 값이 같다. 뽑은 이유는 <b>주봉 지표가
     * 진행 중인 주에 대해 걸음 하나만 밀어야 하기 때문</b>이다 — 거래일마다 시계열 전체로 EMA를
     * 다시 돌리면 종목당 주 수만큼 곱해진다.
     *
     * <p>IMPORTANT — <b>ATR의 평활은 이것이 아니다.</b> Wilder RMA는 α = 1/기간이라
     * 14에서 {@code 2/15 = 0.1333} 대 {@code 1/14 = 0.0714}로 <b>거의 두 배 다르다.</b>
     * 여기에 실체범위를 넣으면 ATR이 상시 작게 나오고 그것을 분모로 쓰는 MACD-V는 상시 크게
     * 나오는데, <b>컴파일도 테스트도 통과한다.</b> ATR 쪽은
     * {@link Atr14Calculator#rmaStep(BigDecimal, BigDecimal, int)}이다.
     */
    static BigDecimal emaStep(BigDecimal previous, BigDecimal value, int period) {
        BigDecimal alpha = BigDecimal.valueOf(2)
                .divide(BigDecimal.valueOf(period + 1L), MC);
        return value.multiply(alpha, MC)
                .add(previous.multiply(BigDecimal.ONE.subtract(alpha), MC))
                .setScale(SCALE, RoundingMode.HALF_UP);
    }

    /** 수정종가 시계열. EMA·RSI처럼 값 목록만 필요한 계산에 쓴다. */
    /**
     * 값 목록의 <b>구간 합</b>. {@code i}번째는 {@code [i-period+1, i]}의 합이고, 창이 차기 전
     * 구간은 {@code null}이다.
     *
     * <p>{@link #sma}가 {@code adjClose}에 묶여 있어 거래량 계열이 쓸 수 없다. 차이킨
     * 자금흐름(두 계열의 21봉 합)과 거래량 비율(20봉 평균 = 합 ÷ 20)이 함께 쓴다 —
     * 각자 짜면 반올림과 창 경계 처리가 갈리는데 값이 그럴듯해 드러나지 않는다.
     *
     * <p>입력에 {@code null}이 있으면 그 값을 포함하는 창은 전부 {@code null}이다. 0으로
     * 대체하지 않는다 — "거래가 없었다"와 "모른다"는 다른 사실이고, 후자를 0으로 접으면
     * 그 구간의 합이 조용히 작아진다.
     */
    static List<BigDecimal> rollingSum(List<BigDecimal> values, int period) {
        List<BigDecimal> sums = new ArrayList<>(values.size());
        for (int i = 0; i < values.size(); i++) {
            if (i < period - 1) {
                sums.add(null);
                continue;
            }
            BigDecimal sum = BigDecimal.ZERO;
            for (int j = i - period + 1; j <= i; j++) {
                BigDecimal value = values.get(j);
                if (value == null) {
                    sum = null;
                    break;
                }
                sum = sum.add(value);
            }
            sums.add(sum);
        }
        return sums;
    }

    static List<BigDecimal> adjCloses(List<PriceBar> bars) {
        List<BigDecimal> values = new ArrayList<>(bars.size());
        for (PriceBar bar : bars) {
            values.add(bar.adjClose());
        }
        return values;
    }
}
