package com.stockscreener.indicator.calculator.ichimoku;

import com.stockscreener.indicator.calculator.SeriesMath;
import com.stockscreener.indicator.calculator.WeeklyBar;

import java.util.List;

/**
 * 완결된 주봉 위의 파생 시계열. <b>종목당 한 번</b> 만든다.
 *
 * <p>여기 담긴 값은 전부 <b>대상 일자와 무관</b>하다 — 완결된 주의 OHLC는 나중에 바뀌지 않고,
 * 전환·기준·선행A/B·ATR·OBV는 그 주까지의 완결된 주만으로 정해지기 때문이다. 대상 일자마다
 * 달라지는 것은 <b>진행 중인 주 하나뿐</b>이며 그것은 {@link WeeklyWindow}가 맡는다.
 *
 * <p>이 분리가 없으면 산출 일자마다 236주 전체를 다시 접게 되고, 한 실행이 약 137일자를 내므로
 * (실측) 종목당 그 비용이 137배가 된다. <b>여기서 새면 값이 맞아도 백필이 실용 시간 안에
 * 끝나지 않는다</b>({@code phase-criteria.md} §P12).
 */
final class WeeklySeries {

    /** 원본의 배열 크기. {@code idx_26ago = i_50 + 26}이 최대 155라 156이다(D-8 패치). */
    static final int ARR_SIZE = 156;

    /**
     * 값을 내기 위해 필요한 최소 주봉 수(ADR-0046 §승인된 이탈 1).
     *
     * <p>원본의 {@code sufficient_data} 가드는 <b>117주</b>만 보장한다 —
     * {@code close_arr[0..129]} 중 0이 13개까지 허용되기 때문이다. 그런데
     * {@code span_b_arr[129]}의 소비는 {@code 129 + 26 + 51 = 206}까지 가므로 실제로는
     * <b>207주</b>가 필요하다. 그 사이 종목은 {@code nz()}가 0을 채워
     * {@code close > max(0,0)}·{@code c_i > c_26(=0)}이 항상 참이 되고, 결과가
     * <b>강세 방향 거짓 양성</b>으로 나온다.
     */
    static final int MIN_WEEKS = 207;

    private static final int TENKAN = 9;
    private static final int KIJUN = 26;
    private static final int SPAN_B = 52;
    private static final int SHIFT = 26;

    private final List<WeeklyBar> weeks;
    private final double[] tenkan;
    private final double[] kijun;
    private final double[] spanA;
    private final double[] spanB;
    private final double[] high52;
    private final double[] low52;
    private final double[] atr14;
    private final double[] atr26;
    private final double[] obv;

    private WeeklySeries(List<WeeklyBar> weeks, double[] tenkan, double[] kijun, double[] spanA,
                         double[] spanB, double[] high52, double[] low52,
                         double[] atr14, double[] atr26, double[] obv) {
        this.weeks = weeks;
        this.tenkan = tenkan;
        this.kijun = kijun;
        this.spanA = spanA;
        this.spanB = spanB;
        this.high52 = high52;
        this.low52 = low52;
        this.atr14 = atr14;
        this.atr26 = atr26;
        this.obv = obv;
    }

    static WeeklySeries of(List<WeeklyBar> weeks) {
        int n = weeks.size();
        double[] tenkan = new double[n];
        double[] kijun = new double[n];
        double[] spanA = new double[n];
        double[] spanB = new double[n];
        double[] high52 = new double[n];
        double[] low52 = new double[n];

        for (int j = 0; j < n; j++) {
            tenkan[j] = midpoint(weeks, j, TENKAN);
            kijun[j] = midpoint(weeks, j, KIJUN);
            high52[j] = highest(weeks, j, SPAN_B);
            low52[j] = lowest(weeks, j, SPAN_B);
        }
        for (int j = 0; j < n; j++) {
            // span_a_w = (tenkan_w[26] + kijun_w[26]) / 2 — 26주 전의 값이다.
            int back = j - SHIFT;
            spanA[j] = back >= 0 ? (tenkan[back] + kijun[back]) / 2.0 : Double.NaN;
            // span_b_w = (highest(high[26], 52) + lowest(low[26], 52)) / 2
            spanB[j] = back >= 0 ? (high52[back] + low52[back]) / 2.0 : Double.NaN;
        }

        return new WeeklySeries(weeks, tenkan, kijun, spanA, spanB, high52, low52,
                averageTrueRange(weeks, 14), averageTrueRange(weeks, 26), onBalanceVolume(weeks));
    }

    /**
     * {@code ta.atr(length)} — 참범위의 RMA다.
     *
     * <p>시드가 <b>첫 {@code length}개의 단순평균</b>이고 그 뒤로
     * {@code (tr + (n-1) * prev) / n}이다. 첫 봉의 참범위는 직전 종가가 없어 {@code high - low}다.
     * 그 시드 위치가 우리 이력의 시작(2020-01-02)이라 TradingView와 다른데,
     * <b>어긋나도 파면 안 되는 자리</b>로 ADR-0046에 적혀 있다.
     */
    private static double[] averageTrueRange(List<WeeklyBar> weeks, int length) {
        int n = weeks.size();
        double[] atr = new double[n];
        double seedSum = 0;
        double prev = Double.NaN;
        for (int j = 0; j < n; j++) {
            double tr = trueRange(weeks, j);
            if (j < length) {
                seedSum += tr;
                atr[j] = j == length - 1 ? seedSum / length : Double.NaN;
            } else {
                atr[j] = (tr + (length - 1) * prev) / length;
            }
            prev = atr[j];
        }
        return atr;
    }

    private static double trueRange(List<WeeklyBar> weeks, int j) {
        WeeklyBar bar = weeks.get(j);
        if (j == 0) {
            return bar.high() - bar.low();
        }
        double prevClose = weeks.get(j - 1).close();
        return Math.max(bar.high() - bar.low(),
                Math.max(Math.abs(bar.high() - prevClose), Math.abs(bar.low() - prevClose)));
    }

    /**
     * {@code ta.obv} — 종가 변화의 부호를 곱한 거래량의 누적.
     *
     * <p>절대 수준은 창의 시작에 달려 있어 TradingView와 완전히 다르다. 그러나 {@code drv_26}은
     * 13점 OLS의 <b>t-통계량</b>이라 평행이동·배율에 불변이고 {@code drv_41}은 부호만 본다 —
     * <b>여기가 아니다.</b>
     */
    private static double[] onBalanceVolume(List<WeeklyBar> weeks) {
        int n = weeks.size();
        double[] obv = new double[n];
        for (int j = 1; j < n; j++) {
            obv[j] = obv[j - 1]
                    + SeriesMath.sign(weeks.get(j).close() - weeks.get(j - 1).close())
                    * weeks.get(j).volume();
        }
        return obv;
    }

    private static double midpoint(List<WeeklyBar> weeks, int j, int length) {
        return (highest(weeks, j, length) + lowest(weeks, j, length)) / 2.0;
    }

    private static double highest(List<WeeklyBar> weeks, int j, int length) {
        double max = Double.NEGATIVE_INFINITY;
        for (int k = Math.max(0, j - length + 1); k <= j; k++) {
            max = Math.max(max, weeks.get(k).high());
        }
        return max;
    }

    private static double lowest(List<WeeklyBar> weeks, int j, int length) {
        double min = Double.POSITIVE_INFINITY;
        for (int k = Math.max(0, j - length + 1); k <= j; k++) {
            min = Math.min(min, weeks.get(k).low());
        }
        return min;
    }

    List<WeeklyBar> weeks() {
        return weeks;
    }

    int size() {
        return weeks.size();
    }

    double tenkan(int j) {
        return tenkan[j];
    }

    double kijun(int j) {
        return kijun[j];
    }

    double spanA(int j) {
        return spanA[j];
    }

    double spanB(int j) {
        return spanB[j];
    }

    double high52(int j) {
        return high52[j];
    }

    double low52(int j) {
        return low52[j];
    }

    double atr14(int j) {
        return atr14[j];
    }

    double atr26(int j) {
        return atr26[j];
    }

    double obv(int j) {
        return obv[j];
    }

    /** 그 주까지의 최고가 — 진행 중인 주를 얹어 다시 셀 때 쓴다. */
    double highestOfCompleted(int j, int length) {
        return highest(weeks, j, length);
    }

    double lowestOfCompleted(int j, int length) {
        return lowest(weeks, j, length);
    }
}
