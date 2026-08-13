package com.stockscreener.indicator.calculator;

import java.util.Arrays;

/**
 * 시계열 하나에 대한 수치 연산. <b>전부 {@code double}</b>이다.
 *
 * <p>주봉 일목이 쓰던 {@code IchimokuMath}에서 올라왔다(ADR-0057). 올린 이유는 <b>일봉 OBV가
 * 같은 OLS t-통계량을 필요로 했기 때문</b>이다 — 그 파일은 package-private이라 밖에서 못 쓰고,
 * 15줄을 복사하면 두 곳이 어긋날 때까지 아무도 모른다.
 *
 * <p>MUST: {@code BigDecimal} + {@code HALF_UP}으로 바꾸지 않는다(ADR-0046). 일목의 이식원인
 * Pine은 float64이고, 여기 값들은 그 대조의 기준선이다.
 *
 * <p>길이는 <b>인자 배열이 정한다.</b> 올라오기 전에는 13에 묶여 있었고 상수 셋(평균 t·제곱합·
 * 자유도)이 그 값으로 본문에 박혀 있었다. 길이를 요구하는 함수를 public으로 두면 14개짜리를
 * 넘겼을 때 앞 13개만 읽은 값이 <b>예외도 경고도 없이</b> 나온다.
 */
public final class SeriesMath {

    private SeriesMath() {
    }

    /**
     * OLS 기울기의 t-통계량. {@code y[k]}는 <b>과거 → 현재</b> 순서다.
     *
     * <p>잔차가 0이면(완전 직선) SE가 0이고 t를 0으로 둔다. 그 값은 ±1.96 임계를 넘지 못하므로
     * <b>"유의하지 않음"으로 떨어진다</b> — 완전 직선인데 유의하지 않다고 말하는 셈이지만
     * 일목의 이식원이 그렇게 하므로 그대로 둔다(ADR-0046).
     *
     * @throws IllegalArgumentException 점이 셋 미만이면. 자유도가 {@code n - 2}라 둘 이하에서는
     *                                  SE가 정의되지 않는다.
     */
    public static double tStatistic(double[] y) {
        int n = requireRegressable(y);
        double meanT = meanIndex(n);
        double den = indexSquareSum(n);

        double slope = slope(y);
        double intercept = mean(y) - slope * meanT;

        double rss = 0;
        for (int k = 0; k < n; k++) {
            double residual = y[k] - (slope * k + intercept);
            rss += residual * residual;
        }
        double se = rss > 0 ? Math.sqrt(rss / (n - 2.0)) / Math.sqrt(den) : 0.0;
        return se != 0 ? slope / se : 0.0;
    }

    /** OLS 기울기 자체. 일목의 기울기 분류(DRV-39·40·53)가 쓴다. */
    public static double slope(double[] y) {
        int n = requireRegressable(y);
        double meanT = meanIndex(n);
        double meanY = mean(y);

        double num = 0;
        for (int k = 0; k < n; k++) {
            num += (k - meanT) * (y[k] - meanY);
        }
        return num / indexSquareSum(n);
    }

    /**
     * Pine {@code array.median} — 짝수 개면 <b>가운데 둘의 평균</b>이다.
     *
     * <p>인자를 정렬하므로 호출부는 버려도 되는 배열을 넘긴다.
     */
    public static double median(double[] values) {
        Arrays.sort(values);
        int n = values.length;
        return n % 2 == 1 ? values[n / 2] : (values[n / 2 - 1] + values[n / 2]) / 2.0;
    }

    /** 부호. {@code ta.change}가 0이면 0이며, 그때 OBV는 그대로다. */
    public static int sign(double value) {
        return value > 0 ? 1 : value < 0 ? -1 : 0;
    }

    /** 인덱스 0..n-1의 평균. n=13이면 6이다. */
    private static double meanIndex(int n) {
        return (n - 1) / 2.0;
    }

    /** {@code sum((k - meanIndex)^2)} = {@code n(n²−1)/12}. n=13이면 182다. */
    private static double indexSquareSum(int n) {
        return n * ((double) n * n - 1) / 12.0;
    }

    private static double mean(double[] y) {
        double sum = 0;
        for (double v : y) {
            sum += v;
        }
        return sum / y.length;
    }

    private static int requireRegressable(double[] y) {
        if (y.length < 3) {
            throw new IllegalArgumentException(
                    "회귀에는 점이 셋 이상 필요하다(자유도 n−2): " + y.length);
        }
        return y.length;
    }
}
