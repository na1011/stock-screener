package com.stockscreener.indicator.calculator;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 길이 일반화가 <b>13점의 값을 바꾸지 않았는지</b>를 고정한다.
 *
 * <p>{@code IchimokuMath}에서 올라오면서 상수 셋(평균 t 6.0 · 제곱합 182.0 · 자유도 11.0)이
 * 배열 길이에서 유도되도록 바뀌었다(ADR-0057). 일목의 13 수식자는 전부 <b>정수</b>를 내므로
 * 여기서 소수 셋째 자리가 어긋나도 부호가 같으면 어떤 테스트도 깨지지 않는다 — 그래서 값을
 * 직접 못박는다.
 */
class SeriesMathTest {

    /** n=13의 상수. {@code (n−1)/2 = 6}, {@code n(n²−1)/12 = 182}, {@code n−2 = 11}. */
    @Test
    @DisplayName("13점 기울기가 손으로 푼 값과 같다 — 일반화가 옛 상수를 재현한다")
    void slopeOfThirteenPointsMatchesTheClosedForm() {
        // y = 2k + 5인 완전 직선. 기울기는 정확히 2다.
        double[] line = new double[13];
        for (int k = 0; k < 13; k++) {
            line[k] = 2.0 * k + 5.0;
        }

        assertThat(SeriesMath.slope(line)).isEqualTo(2.0, org.assertj.core.data.Offset.offset(1e-12));
    }

    @Test
    @DisplayName("완전 직선의 t는 0이다 — 잔차가 없으면 SE가 0이고 원본이 t를 0으로 둔다")
    void perfectLineYieldsZeroT() {
        double[] line = new double[13];
        for (int k = 0; k < 13; k++) {
            line[k] = 3.0 * k;
        }

        // 직관과 어긋나는 자리다. 완전한 추세인데 "유의하지 않음"으로 떨어진다(ADR-0046).
        assertThat(SeriesMath.tStatistic(line)).isZero();
    }

    @Test
    @DisplayName("t의 부호가 기울기를 따르고 잡음이 크면 임계 아래로 떨어진다")
    void tStatisticFollowsSlopeAndShrinksWithNoise() {
        double[] rising = {1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 14};
        double[] falling = new double[13];
        for (int k = 0; k < 13; k++) {
            falling[k] = -rising[k];
        }
        double[] noise = {0, 5, -5, 5, -5, 5, -5, 5, -5, 5, -5, 5, 0};

        assertThat(SeriesMath.tStatistic(rising)).isGreaterThan(1.96);
        assertThat(SeriesMath.tStatistic(falling)).isLessThan(-1.96);
        assertThat(Math.abs(SeriesMath.tStatistic(noise))).isLessThan(1.96);
    }

    @Test
    @DisplayName("길이가 13이 아니어도 같은 규칙이 선다 — 일봉 OBV가 이것에 기댄다")
    void generalisesBeyondThirteenPoints() {
        // 같은 직선을 길이만 달리해 넣는다. 기울기는 길이와 무관하게 2다.
        for (int n : new int[] {3, 7, 13, 21}) {
            double[] line = new double[n];
            for (int k = 0; k < n; k++) {
                line[k] = 2.0 * k + 1.0;
            }
            assertThat(SeriesMath.slope(line))
                    .as("n=%d", n)
                    .isEqualTo(2.0, org.assertj.core.data.Offset.offset(1e-12));
        }
    }

    @Test
    @DisplayName("점이 셋 미만이면 던진다 — 조용히 앞부분만 읽지 않는다")
    void tooFewPointsThrow() {
        // 예전 구현은 길이를 인자가 아니라 상수 13에서 읽었다. 14개를 넘기면 앞 13개만 쓴
        // 값이 예외도 경고도 없이 나왔다. 그 실패 방식을 다시 만들지 않는다.
        assertThatThrownBy(() -> SeriesMath.tStatistic(new double[] {1, 2}))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("셋 이상");
    }

    @Test
    @DisplayName("짝수 개의 중앙값은 가운데 둘의 평균이다 — Pine array.median")
    void medianOfEvenCountAveragesTheMiddleTwo() {
        assertThat(SeriesMath.median(new double[] {4, 1, 3, 2})).isEqualTo(2.5);
        assertThat(SeriesMath.median(new double[] {5, 1, 3})).isEqualTo(3.0);
    }

    @Test
    @DisplayName("부호는 0을 0으로 둔다 — 그때 OBV는 그대로다")
    void signKeepsZeroAsZero() {
        assertThat(SeriesMath.sign(0.0)).isZero();
        assertThat(SeriesMath.sign(-0.1)).isEqualTo(-1);
        assertThat(SeriesMath.sign(0.1)).isEqualTo(1);
    }
}
