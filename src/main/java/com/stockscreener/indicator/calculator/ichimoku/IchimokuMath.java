package com.stockscreener.indicator.calculator.ichimoku;

/**
 * 원본 Pine에만 있는 수치 규칙. 일반 시계열 연산은 {@link
 * com.stockscreener.indicator.calculator.SeriesMath}로 올라갔다(ADR-0057).
 *
 * <p>여기 남은 것은 <b>Pine과 Java가 갈리는 자리</b> 하나뿐이라 옮기지 않았다.
 */
final class IchimokuMath {

    /** 13주 OLS. 원본이 고정한 창이며 {@code WeeklyAnalyzer}가 배열 길이로 쓴다. */
    static final int REGRESSION_POINTS = 13;

    private IchimokuMath() {
    }

    /**
     * Pine {@code math.round} — <b>ties round up</b>.
     *
     * <p>MUST: {@code BigDecimal} + {@code HALF_UP}으로 바꾸지 않는다(ADR-0046). Pine은 float64이고
     * {@code math.round}가 −1.5 → −1인데 {@code HALF_UP}은 −1.5 → −2다. {@code score_d2}의 폴백
     * 가지가 정확히 그 자리이고, 동점은 {@code ext_base}가 <b>홀수</b>일 때 나므로 3(단계 1·2)만이
     * 아니라 1(단계 4)도 해당한다.
     *
     * <p>{@code Math.round}가 같은 규칙이지만({@code floor(x + 0.5)}) 이름만 보고 믿지 않는다.
     * 이 함수가 있는 이유는 <b>{@code Math.rint}·{@code BigDecimal.HALF_UP}과 갈리는 자리를
     * 한 곳에 모아 두기 위해서</b>다 — −0.5는 셋이 각각 0 · −0 · −1을 낸다.
     */
    static int round(double value) {
        return (int) Math.floor(value + 0.5);
    }
}
