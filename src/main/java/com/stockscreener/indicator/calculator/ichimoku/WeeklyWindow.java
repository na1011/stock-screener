package com.stockscreener.indicator.calculator.ichimoku;

import com.stockscreener.indicator.calculator.SeriesMath;

/**
 * 한 대상 일자에서 본 주봉 창. <b>인덱스 0이 진행 중인 주</b>이고 클수록 과거다 —
 * 원본의 {@code close_arr}·{@code span_b_arr} 등과 같은 방향이다.
 *
 * <p>인덱스 1 이상은 {@link WeeklySeries}의 완결된 주를 그대로 읽는다. 대상 일자마다 다시
 * 계산하는 것은 <b>인덱스 0뿐</b>이다 — 완결된 주의 값은 대상 일자와 무관하기 때문이다.
 *
 * <p>MUST: 접근자를 인라인하지 말고 이 형태로 둔다. 원본이 {@code array.get(x_arr, i)}로 읽는
 * 자리를 1:1로 옮겨야 부등호 하나가 뒤집힌 것을 나중에 대조로 찾을 수 있다. 13 수식자·leaf·
 * 9등급은 전부 <b>정수</b>를 내므로 값만 봐서는 틀린 것을 알 수 없다.
 */
final class WeeklyWindow {

    private final WeeklySeries series;
    /** 진행 중인 주가 {@link WeeklySeries}에서 차지하는 인덱스. */
    private final int current;

    private final double close0;
    private final double high0;
    private final double low0;
    private final double tenkan0;
    private final double kijun0;
    private final double spanA0;
    private final double spanB0;
    private final double high52_0;
    private final double low52_0;
    private final double atr14_0;
    private final double atr26_0;
    private final double obv0;

    /**
     * @param partial 진행 중인 주 — 그 주의 첫 거래일부터 <b>대상 일자까지</b>의 집계다.
     *                대상 일자가 그 주의 마지막 거래일이면 완결된 주와 같다
     */
    WeeklyWindow(WeeklySeries series, int current, WeeklyBar partial) {
        this.series = series;
        this.current = current;

        this.close0 = partial.close();
        this.high0 = partial.high();
        this.low0 = partial.low();

        // 전환·기준은 진행 중인 주를 포함해 다시 센다. 나머지 창은 완결된 주다.
        this.tenkan0 = (Math.max(partial.high(), series.highestOfCompleted(current - 1, 8))
                + Math.min(partial.low(), series.lowestOfCompleted(current - 1, 8))) / 2.0;
        this.kijun0 = (Math.max(partial.high(), series.highestOfCompleted(current - 1, 25))
                + Math.min(partial.low(), series.lowestOfCompleted(current - 1, 25))) / 2.0;
        this.high52_0 = Math.max(partial.high(), series.highestOfCompleted(current - 1, 51));
        this.low52_0 = Math.min(partial.low(), series.lowestOfCompleted(current - 1, 51));

        // 선행 A·B는 26주 전의 값으로 만들어지므로 진행 중인 주가 들어가지 않는다.
        // WeeklySeries가 인덱스 current에 대해 낸 값이 그대로 옳다.
        this.spanA0 = series.spanA(current);
        this.spanB0 = series.spanB(current);

        double prevClose = series.weeks().get(current - 1).close();
        double trueRange = Math.max(partial.high() - partial.low(),
                Math.max(Math.abs(partial.high() - prevClose), Math.abs(partial.low() - prevClose)));
        // RMA를 한 스텝만 전진시킨다. 완결된 주까지의 평활값은 이미 있다.
        this.atr14_0 = (trueRange + 13 * series.atr14(current - 1)) / 14.0;
        this.atr26_0 = (trueRange + 25 * series.atr26(current - 1)) / 26.0;
        this.obv0 = series.obv(current - 1)
                + SeriesMath.sign(partial.close() - prevClose) * partial.volume();
    }

    private int at(int i) {
        return current - i;
    }

    double close(int i) {
        return i == 0 ? close0 : series.weeks().get(at(i)).close();
    }

    double high(int i) {
        return i == 0 ? high0 : series.weeks().get(at(i)).high();
    }

    double low(int i) {
        return i == 0 ? low0 : series.weeks().get(at(i)).low();
    }

    double tenkan(int i) {
        return i == 0 ? tenkan0 : series.tenkan(at(i));
    }

    double kijun(int i) {
        return i == 0 ? kijun0 : series.kijun(at(i));
    }

    double spanA(int i) {
        return i == 0 ? spanA0 : series.spanA(at(i));
    }

    double spanB(int i) {
        return i == 0 ? spanB0 : series.spanB(at(i));
    }

    double high52(int i) {
        return i == 0 ? high52_0 : series.high52(at(i));
    }

    double low52(int i) {
        return i == 0 ? low52_0 : series.low52(at(i));
    }

    double atr26(int i) {
        return i == 0 ? atr26_0 : series.atr26(at(i));
    }

    double atr14Current() {
        return atr14_0;
    }

    double obv(int i) {
        return i == 0 ? obv0 : series.obv(at(i));
    }

    double kumoTop(int i) {
        return Math.max(spanA(i), spanB(i));
    }

    double kumoBottom(int i) {
        return Math.min(spanA(i), spanB(i));
    }

    /**
     * 구름 대비 위치. <b>D-1 패치: 경계 등호를 뺀다</b> — {@code close == bot}은 변동성 영역이라
     * 위도 아래도 아니다. 등호를 넣으면 3역호전 첫 조건이 한 칸 느슨해진다.
     */
    int kumoPosition(int i) {
        double c = close(i);
        return c > kumoTop(i) ? 1 : c < kumoBottom(i) ? -1 : 0;
    }
}
