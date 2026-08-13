package com.stockscreener.indicator.calculator.ichimoku;

/**
 * 원본이 쓰는 수치 연산. <b>전부 {@code double}</b>이다.
 *
 * <p>MUST: {@code BigDecimal} + {@code HALF_UP}으로 바꾸지 않는다(ADR-0046). Pine은 float64이고
 * {@code math.round}가 <b>ties round up</b>(−1.5 → −1)이라 {@code HALF_UP}(−1.5 → −2)과 갈린다.
 * {@code score_d2}의 폴백 가지가 정확히 그 자리이고, 동점은 {@code ext_base}가 <b>홀수</b>일 때
 * 나므로 3(단계 1·2)만이 아니라 1(단계 4)도 해당한다.
 */
final class IchimokuMath {

    /** 13주 OLS의 상수. {@code sum((i-6)^2 for i=0..12) = 182}이고 평균 t는 6이다. */
    private static final double MEAN_T = 6.0;
    private static final double DEN = 182.0;
    static final int REGRESSION_POINTS = 13;

    private IchimokuMath() {
    }

    /**
     * Pine {@code math.round} — <b>ties round up</b>.
     *
     * <p>{@code Math.round}가 같은 규칙이지만({@code floor(x + 0.5)}) 이름만 보고 믿지 않는다.
     * 이 함수가 있는 이유는 <b>{@code Math.rint}·{@code BigDecimal.HALF_UP}과 갈리는 자리를
     * 한 곳에 모아 두기 위해서</b>다 — −0.5는 셋이 각각 0 · −0 · −1을 낸다.
     */
    static int round(double value) {
        return (int) Math.floor(value + 0.5);
    }

    /**
     * 13점 OLS 기울기의 t-통계량.
     *
     * <p>{@code y[k]}는 <b>과거 → 현재</b> 순서다. 호출부가 {@code arr[12 - k]}로 뒤집어 넘긴다 —
     * 원본의 배열이 현재(0)부터 과거로 가기 때문이다.
     *
     * <p>잔차가 0이면(완전 직선) SE가 0이고 원본은 t를 0으로 둔다. 그 값은 ±1.96 임계를 넘지
     * 못하므로 <b>"유의하지 않음"으로 떨어진다</b> — 완전 직선인데 유의하지 않다고 말하는 셈이지만
     * 원본이 그렇게 하므로 그대로 옮긴다.
     */
    static double tStatistic(double[] y) {
        double sum = 0;
        for (double v : y) {
            sum += v;
        }
        double meanY = sum / REGRESSION_POINTS;

        double num = 0;
        for (int k = 0; k < REGRESSION_POINTS; k++) {
            num += (k - MEAN_T) * (y[k] - meanY);
        }
        double slope = num / DEN;
        double intercept = meanY - slope * MEAN_T;

        double rss = 0;
        for (int k = 0; k < REGRESSION_POINTS; k++) {
            double residual = y[k] - (slope * k + intercept);
            rss += residual * residual;
        }
        double se = rss > 0 ? Math.sqrt(rss / 11.0) / Math.sqrt(DEN) : 0.0;
        return se != 0 ? slope / se : 0.0;
    }

    /** 13점 OLS 기울기 자체. 기울기 분류(DRV-39·40·53)가 쓴다. */
    static double slope(double[] y) {
        double sum = 0;
        for (double v : y) {
            sum += v;
        }
        double meanY = sum / REGRESSION_POINTS;
        double num = 0;
        for (int k = 0; k < REGRESSION_POINTS; k++) {
            num += (k - MEAN_T) * (y[k] - meanY);
        }
        return num / DEN;
    }

    /**
     * Pine {@code array.median} — 짝수 개면 <b>가운데 둘의 평균</b>이다.
     *
     * <p>인자를 정렬하므로 호출부는 버려도 되는 배열을 넘긴다.
     */
    static double median(double[] values) {
        java.util.Arrays.sort(values);
        int n = values.length;
        return n % 2 == 1 ? values[n / 2] : (values[n / 2 - 1] + values[n / 2]) / 2.0;
    }

    /** 부호. {@code ta.change}가 0이면 0이며, 그때 OBV는 그대로다. */
    static int sign(double value) {
        return value > 0 ? 1 : value < 0 ? -1 : 0;
    }
}
