package com.stockscreener.probe.backtest;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link StudentT}를 <b>알려진 임계값</b>으로 대조한다.
 *
 * <p>이 검사가 필요한 이유는 p-값이 <b>틀려도 그럴듯하기 때문</b>이다. 연분수가 조금 어긋나면
 * 0.03이 0.06이 되는 식으로 나오고, 그 차이는 결론의 부호를 바꾸면서 어떤 증상도 내지 않는다.
 * 조건 15개에 Holm을 걸어 「유의하다」를 말하려면 이 함수가 먼저 옳아야 한다.
 *
 * <p>기대값은 교과서 t-표의 양측 5%·2% 임계점이다.
 */
class StudentTTest {

    /** t-표의 양측 5% 임계점. 이 t에서 p가 정확히 0.05여야 한다. */
    @Test
    @DisplayName("양측 5% 임계점에서 p가 0.05다")
    void matchesTwoSidedFivePercentCriticalValues() {
        assertThat(StudentT.twoSidedPValue(12.706, 1)).isCloseTo(0.05, within(0.001));
        assertThat(StudentT.twoSidedPValue(2.447, 6)).isCloseTo(0.05, within(0.001));
        assertThat(StudentT.twoSidedPValue(2.365, 7)).isCloseTo(0.05, within(0.001));
        assertThat(StudentT.twoSidedPValue(2.228, 10)).isCloseTo(0.05, within(0.001));
        assertThat(StudentT.twoSidedPValue(2.042, 30)).isCloseTo(0.05, within(0.001));
    }

    @Test
    @DisplayName("양측 1% 임계점에서 p가 0.01이다")
    void matchesTwoSidedOnePercentCriticalValues() {
        assertThat(StudentT.twoSidedPValue(3.707, 6)).isCloseTo(0.01, within(0.001));
        assertThat(StudentT.twoSidedPValue(3.169, 10)).isCloseTo(0.01, within(0.001));
    }

    /**
     * <b>정규근사를 쓰면 안 되는 이유를 수로 고정한다.</b> df=7에서 t=2.4의 p는 정규분포의
     * 0.016이 아니라 0.047 언저리다 — 근사를 쓰면 유의성이 3배 부푼다.
     */
    @Test
    @DisplayName("작은 자유도의 꼬리가 정규분포보다 두껍다")
    void tailIsHeavierThanNormal() {
        double t7 = StudentT.twoSidedPValue(2.4, 7);
        assertThat(t7).isGreaterThan(0.04).isLessThan(0.055);
        // 자유도가 커지면 정규로 수렴한다.
        assertThat(StudentT.twoSidedPValue(2.4, 5000)).isCloseTo(0.0164, within(0.001));
    }

    @Test
    @DisplayName("t=0이면 p=1이고, t가 커지면 p가 단조 감소한다")
    void behavesMonotonically() {
        assertThat(StudentT.twoSidedPValue(0.0, 7)).isCloseTo(1.0, within(1e-9));
        assertThat(StudentT.twoSidedPValue(1.0, 7))
                .isGreaterThan(StudentT.twoSidedPValue(2.0, 7));
        // 부호는 양측 p에 영향을 주지 않는다.
        assertThat(StudentT.twoSidedPValue(-2.365, 7))
                .isCloseTo(StudentT.twoSidedPValue(2.365, 7), within(1e-12));
    }

    /**
     * Holm은 순서를 유지하고 단조여야 한다. <b>단조성을 빠뜨리면 더 작은 p가 더 큰 보정값을
     * 받는 역전이 생기는데</b>, 그때도 값은 그럴듯하게 나온다.
     */
    @Test
    @DisplayName("Holm 보정이 순서를 유지하고 단조 증가한다")
    void holmIsOrderPreservingAndMonotone() {
        double[] adjusted = StudentT.holm(new double[] {0.01, 0.04, 0.03, 0.005});

        // 가장 작은 p(0.005)는 4배, 다음(0.01)은 3배 — 단 앞의 값보다 작아질 수 없다.
        assertThat(adjusted[3]).isCloseTo(0.02, within(1e-12));
        assertThat(adjusted[0]).isCloseTo(0.03, within(1e-12));
        assertThat(adjusted[2]).isCloseTo(0.06, within(1e-12));
        assertThat(adjusted[1]).isCloseTo(0.06, within(1e-12));

        // 입력 순서가 그대로 보존된다 — 정렬해 돌려주면 조건과 값이 어긋난다.
        assertThat(adjusted).hasSize(4);
    }

    @Test
    @DisplayName("Holm 보정값은 1을 넘지 않는다")
    void holmIsCappedAtOne() {
        double[] adjusted = StudentT.holm(new double[] {0.5, 0.9, 0.8});
        for (double p : adjusted) {
            assertThat(p).isLessThanOrEqualTo(1.0);
        }
    }

    private static org.assertj.core.data.Offset<Double> within(double tolerance) {
        return org.assertj.core.data.Offset.offset(tolerance);
    }
}
