package com.stockscreener.indicator.calculator.ichimoku;

import com.stockscreener.indicator.PriceBar;
import com.stockscreener.indicator.calculator.PriceScaling;

import java.util.List;

/**
 * 일봉 블록. 원본에서 <b>살아 있는 것은 {@code drv_43}과 {@code drv_55} 둘뿐</b>이다(ADR-0046).
 *
 * <p>MUST: 전부 <b>수정 축척</b>으로 계산한다. TradingView 차트는 기본이 분할 보정이라 원본의
 * {@code close}·{@code high}·{@code low}는 이미 보정된 값이고, 우리 {@code price_daily}는
 * {@code adj_close}만 보정돼 있다. 원값을 쓰면 5:1 분할 종목에서
 * {@code stock_ret_26w}가 −80%가 되고 {@code ich_d_04}가 현재가 5배 자리에 구름을 만들어
 * {@code drv_43 → drv_45 =} 수식자 i가 최대 6점 흔들린다. <b>둘 다 정수이고 에러가 없다.</b>
 *
 * <p>여기 담긴 값은 <b>대상 일자와 무관</b>하다 — 인덱스 j의 값은 j까지의 일봉만으로 정해진다.
 * 그래서 종목당 한 번 만든다.
 *
 * <p>옮기지 않은 것: {@code px_01~04} · {@code atr_01·02} · {@code vol_01·02} ·
 * <b>{@code vol_05}(일봉 OBV)</b> · {@code ich_d_05·06·08} · {@code drv_48}. 전부 미소비다.
 * 살아 있는 OBV는 <b>주봉 하나</b>다.
 */
final class DailySeries {

    private static final int TENKAN = 9;
    private static final int KIJUN = 26;
    private static final int SPAN_B = 52;
    private static final int SHIFT = 26;

    private final int[] kaizen;

    private DailySeries(int[] kaizen) {
        this.kaizen = kaizen;
    }

    static DailySeries of(List<PriceBar> bars) {
        int n = bars.size();
        double[] close = new double[n];
        double[] high = new double[n];
        double[] low = new double[n];
        for (int j = 0; j < n; j++) {
            PriceBar bar = bars.get(j);
            close[j] = bar.adjClose().doubleValue();
            high[j] = PriceScaling.adjustedHigh(bar).doubleValue();
            low[j] = PriceScaling.adjustedLow(bar).doubleValue();
        }

        double[] tenkan = new double[n];
        double[] kijun = new double[n];
        double[] high52 = new double[n];
        double[] low52 = new double[n];
        for (int j = 0; j < n; j++) {
            tenkan[j] = (highest(high, j, TENKAN) + lowest(low, j, TENKAN)) / 2.0;
            kijun[j] = (highest(high, j, KIJUN) + lowest(low, j, KIJUN)) / 2.0;
            high52[j] = highest(high, j, SPAN_B);
            low52[j] = lowest(low, j, SPAN_B);
        }

        int[] kaizen = new int[n];
        for (int j = 0; j < n; j++) {
            int back = j - SHIFT;
            if (back < 0) {
                // 구름을 만들 수 없다. 원본은 nz()로 0을 채우지만 우리는 207주 가드 안에서만
                // 값을 내므로 이 자리에 도달하지 않는다. 그래도 0을 쓰지 않는다 —
                // close > max(0,0)이 언제나 참이 되어 강세 방향 거짓 양성이 된다.
                kaizen[j] = -1;
                continue;
            }
            double spanA = (tenkan[back] + kijun[back]) / 2.0;
            double spanB = (high52[back] + low52[back]) / 2.0;
            // D-1 패치: 경계 등호를 뺀다.
            int position = close[j] > Math.max(spanA, spanB) ? 1
                    : close[j] < Math.min(spanA, spanB) ? -1 : 0;
            kaizen[j] = (position == 1 ? 1 : 0)
                    + (tenkan[j] > kijun[j] ? 1 : 0)
                    + (close[j] > close[back] ? 1 : 0);
        }
        return new DailySeries(kaizen);
    }

    /** {@code drv_43} 일봉 호전 분류 0~3. 구름을 만들 수 없는 구간은 −1이다. */
    int kaizen(int index) {
        return kaizen[index];
    }

    private static double highest(double[] values, int j, int length) {
        double max = Double.NEGATIVE_INFINITY;
        for (int k = Math.max(0, j - length + 1); k <= j; k++) {
            max = Math.max(max, values[k]);
        }
        return max;
    }

    private static double lowest(double[] values, int j, int length) {
        double min = Double.POSITIVE_INFINITY;
        for (int k = Math.max(0, j - length + 1); k <= j; k++) {
            min = Math.min(min, values[k]);
        }
        return min;
    }
}
