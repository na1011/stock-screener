package com.stockscreener.probe.backtest;

/**
 * t분포 양측 p-값. 조건 단위 수익률의 다중검정 보정이 이것을 필요로 한다(P17).
 *
 * <p><b>정규근사를 쓰지 않는 이유는 표본이 8이기 때문이다.</b> 독립 6개월 구간이 8개뿐이라
 * 자유도가 7이고, 그 꼬리는 정규분포보다 훨씬 두껍다 — {@code t=2.4}에서 정규는 p≈0.016,
 * df=7은 p≈0.047이다. 근사를 쓰면 <b>있지도 않은 유의성이 3배 부풀고</b>, 그것이 이 phase가
 * 가장 경계하는 형태의 오류다(phase-criteria §P17 ③).
 *
 * <p>정칙화 불완전베타 함수로 계산한다: 양측 {@code p = I_{df/(df+t²)}(df/2, 1/2)}.
 * 연분수는 Lentz 방법이며 {@code StudentTTest}가 알려진 임계값으로 대조한다.
 */
final class StudentT {

    private static final int MAX_ITERATIONS = 300;
    private static final double EPSILON = 1e-14;
    private static final double TINY = 1e-300;

    private StudentT() {
    }

    /** 자유도 {@code df}에서 {@code |t|} 이상이 나올 양측 확률. */
    static double twoSidedPValue(double t, int df) {
        if (df <= 0) {
            return Double.NaN;
        }
        if (Double.isNaN(t)) {
            return Double.NaN;
        }
        double x = df / (df + t * t);
        return regularizedIncompleteBeta(x, df / 2.0, 0.5);
    }

    /** Holm 보정. 입력 순서를 유지한 채 보정된 p를 낸다. */
    static double[] holm(double[] pValues) {
        int n = pValues.length;
        Integer[] order = new Integer[n];
        for (int i = 0; i < n; i++) {
            order[i] = i;
        }
        java.util.Arrays.sort(order, java.util.Comparator.comparingDouble(i -> pValues[i]));

        double[] adjusted = new double[n];
        double running = 0.0;
        for (int rank = 0; rank < n; rank++) {
            int index = order[rank];
            // 단조성을 유지한다 — 보정된 p는 순위를 따라 감소하지 않아야 한다.
            running = Math.max(running, Math.min(1.0, (n - rank) * pValues[index]));
            adjusted[index] = running;
        }
        return adjusted;
    }

    private static double regularizedIncompleteBeta(double x, double a, double b) {
        if (x <= 0.0) {
            return 0.0;
        }
        if (x >= 1.0) {
            return 1.0;
        }
        double front = Math.exp(logGamma(a + b) - logGamma(a) - logGamma(b)
                + a * Math.log(x) + b * Math.log(1.0 - x));
        // 연분수는 x < (a+1)/(a+b+2)에서 수렴이 빠르다. 아니면 대칭식으로 뒤집는다.
        if (x < (a + 1.0) / (a + b + 2.0)) {
            return front * betaContinuedFraction(x, a, b) / a;
        }
        return 1.0 - Math.exp(logGamma(a + b) - logGamma(a) - logGamma(b)
                + b * Math.log(1.0 - x) + a * Math.log(x))
                * betaContinuedFraction(1.0 - x, b, a) / b;
    }

    /** Lentz의 수정 연분수. */
    private static double betaContinuedFraction(double x, double a, double b) {
        double qab = a + b;
        double qap = a + 1.0;
        double qam = a - 1.0;
        double c = 1.0;
        double d = 1.0 - qab * x / qap;
        if (Math.abs(d) < TINY) {
            d = TINY;
        }
        d = 1.0 / d;
        double h = d;

        for (int m = 1; m <= MAX_ITERATIONS; m++) {
            int m2 = 2 * m;
            double aa = m * (b - m) * x / ((qam + m2) * (a + m2));
            d = 1.0 + aa * d;
            if (Math.abs(d) < TINY) {
                d = TINY;
            }
            c = 1.0 + aa / c;
            if (Math.abs(c) < TINY) {
                c = TINY;
            }
            d = 1.0 / d;
            h *= d * c;

            aa = -(a + m) * (qab + m) * x / ((a + m2) * (qap + m2));
            d = 1.0 + aa * d;
            if (Math.abs(d) < TINY) {
                d = TINY;
            }
            c = 1.0 + aa / c;
            if (Math.abs(c) < TINY) {
                c = TINY;
            }
            d = 1.0 / d;
            double delta = d * c;
            h *= delta;

            if (Math.abs(delta - 1.0) < EPSILON) {
                return h;
            }
        }
        // 수렴하지 않았다는 것을 값으로 감추지 않는다 — 조용히 틀린 p보다 멈추는 편이 낫다.
        throw new IllegalStateException(
                "불완전베타 연분수가 %d회에 수렴하지 않았다: x=%s a=%s b=%s"
                        .formatted(MAX_ITERATIONS, x, a, b));
    }

    /** Lanczos 근사. */
    private static double logGamma(double x) {
        double[] coefficients = {
                676.5203681218851, -1259.1392167224028, 771.32342877765313,
                -176.61502916214059, 12.507343278686905, -0.13857109526572012,
                9.9843695780195716e-6, 1.5056327351493116e-7};
        if (x < 0.5) {
            return Math.log(Math.PI / Math.sin(Math.PI * x)) - logGamma(1.0 - x);
        }
        double shifted = x - 1.0;
        double a = 0.99999999999980993;
        double t = shifted + 7.5;
        for (int i = 0; i < coefficients.length; i++) {
            a += coefficients[i] / (shifted + i + 1);
        }
        return 0.5 * Math.log(2 * Math.PI) + (shifted + 0.5) * Math.log(t) - t + Math.log(a);
    }
}
