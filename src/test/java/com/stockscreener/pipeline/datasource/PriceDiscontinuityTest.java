package com.stockscreener.pipeline.datasource;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 폭이 다른 세 판정이 <b>각각 자기 일을 하는가</b>.
 *
 * <p>이 파일이 있는 이유는 값을 합치고 싶어지기 때문이다. 합치면 셋 중 하나가 반드시 제 일을 못
 * 하는데, 그 손상은 <b>값이 그럴듯해서</b> 어디에도 안 드러난다. 아래 실측 표본이 각 폭의 경계
 * 양쪽에 놓여 있어 폭을 옮기면 깨진다.
 *
 * <p>이사 전에는 {@code CONSISTENCY_*}(지금의 {@code SUPPORT_*})를 지키는 테스트가 하나도 없었다 —
 * 모든 픽스처의 relative가 정확히 1.0이라 2.0을 1.1로 좁혀도 초록이었다.
 */
class PriceDiscontinuityTest {

    private static BigDecimal n(String value) {
        return new BigDecimal(value);
    }

    @Nested
    @DisplayName("정상 매매로 설명되지 않는 하루 변동인가")
    class Discontinuous {

        @Test
        @DisplayName("IMPORTANT — 비대칭이다. 한쪽 값을 양방향에 쓰면 +30~42.9%가 그물을 빠져나간다")
        void isAsymmetric() {
            // 1/0.70 = 1.4286. 위쪽 임계를 그것으로 두면 이 값이 통과해 버린다.
            assertThat(PriceDiscontinuity.isDiscontinuous(n("1350"), n("1000"))).isTrue();
            assertThat(PriceDiscontinuity.isDiscontinuous(n("690"), n("1000"))).isTrue();

            // 경계 안쪽. 상한가·하한가 자체는 불연속이 아니다.
            assertThat(PriceDiscontinuity.isDiscontinuous(n("1300"), n("1000"))).isFalse();
            assertThat(PriceDiscontinuity.isDiscontinuous(n("700"), n("1000"))).isFalse();
        }

        @Test
        @DisplayName("실측 표본이 잡힌다 — 대한전선 ×10.63, 아시아나 ×4.28, 루닛 ×0.50")
        void catchesMeasuredSamples() {
            assertThat(PriceDiscontinuity.isDiscontinuous(n("15860"), n("1492"))).isTrue();
            assertThat(PriceDiscontinuity.isDiscontinuous(n("18000"), n("4210"))).isTrue();
            assertThat(PriceDiscontinuity.isDiscontinuous(n("20800"), n("41450"))).isTrue();
        }

        @Test
        @DisplayName("결손은 불연속이 아니다 — 나눗셈에 넣으면 예외가 된다")
        void missingBarsAreNotDiscontinuous() {
            assertThat(PriceDiscontinuity.isDiscontinuous(null, n("1000"))).isFalse();
            assertThat(PriceDiscontinuity.isDiscontinuous(n("1000"), null)).isFalse();
            assertThat(PriceDiscontinuity.isDiscontinuous(BigDecimal.ZERO, n("1000"))).isFalse();
            assertThat(PriceDiscontinuity.isDiscontinuous(n("1000"), BigDecimal.ZERO)).isFalse();
        }
    }

    @Nested
    @DisplayName("그 비율의 권리락으로 그날 봉이 설명되는가")
    class ExplainsPriceGap {

        @Test
        @DisplayName("IMPORTANT — 루닛 2026-05-04의 점프 셋을 실제로 가른다. 이것이 이 폭의 존재 이유다")
        void discriminatesAmongCandidateJumps() {
            BigDecimal observed = n("0.501809");   // 20,800 / 41,450

            // 2026-05-15 ×1.27 — 전환권행사다. relative 0.6373
            assertThat(PriceDiscontinuity.explainsPriceGap(observed, n("1.2700000157"))).isFalse();
            // 2026-05-26 ×2.00 — 진짜 신주상장이다. relative 1.0036
            assertThat(PriceDiscontinuity.explainsPriceGap(observed, n("2.0000000000"))).isTrue();
            // 2026-06-19 ×1.001 — 잡음이다. relative 0.5023
            assertThat(PriceDiscontinuity.explainsPriceGap(observed, n("1.0010156134"))).isFalse();
        }

        @Test
        @DisplayName("MUST NOT: SUPPORT 폭(2.0/0.5)으로 대신하지 않는다 — 셋이 전부 통과해 판정력이 0이 된다")
        void theWiderBandWouldPassAllThree() {
            BigDecimal close = n("20800");
            BigDecimal prevClose = n("41450");

            assertThat(PriceDiscontinuity.supportsShareRatio(n("1.2700000157"), close, prevClose))
                    .as("넓은 폭은 전환권행사도 통과시킨다 — 그래서 고르는 데 쓸 수 없다")
                    .isTrue();
            assertThat(PriceDiscontinuity.supportsShareRatio(n("2.0000000000"), close, prevClose))
                    .isTrue();
            assertThat(PriceDiscontinuity.supportsShareRatio(n("1.0010156134"), close, prevClose))
                    .isTrue();
        }

        @Test
        @DisplayName("경계 양쪽 — 폭을 좁히면 실측 무상증자가 잘린다")
        void boundsAreLoadBearing() {
            // 씨어스 2026-03-24: 관측 0.298524 × 3.0 = 0.8956. 0.9로 조이면 잘린다.
            assertThat(PriceDiscontinuity.explainsPriceGap(n("0.298524"), n("3.0"))).isTrue();
            // 실리콘투 2022-07-14: 0.216360 × 6.0 = 1.2982. 1.1로 조이면 잘린다.
            assertThat(PriceDiscontinuity.explainsPriceGap(n("0.216360"), n("6.0"))).isTrue();
            // 경계 밖.
            assertThat(PriceDiscontinuity.explainsPriceGap(n("0.5"), n("1.28"))).isFalse();
            assertThat(PriceDiscontinuity.explainsPriceGap(n("0.5"), n("2.72"))).isFalse();
        }
    }

    @Nested
    @DisplayName("주식수 배수가 가격과 아무 관계도 없지는 않은가")
    class SupportsShareRatio {

        @Test
        @DisplayName("IMPORTANT — 아시아나 2021-01-15(relative 1.4252)이 살아야 한다. 좁히면 잘린다")
        void keepsTheMeasuredOutlier() {
            // 4,210 → 18,000, 주식수 1/3. relative = 18000 × (1/3) / 4210 = 1.4252
            assertThat(PriceDiscontinuity.supportsShareRatio(
                    n("0.3333333333"), n("18000"), n("4210"))).isTrue();
            // 권리락 폭(1.35)으로 대신하면 이 사건이 떨어진다 — 두 판정이 다른 이유다.
            assertThat(PriceDiscontinuity.explainsPriceGap(
                    n("4.275534"), n("0.3333333333"))).isFalse();
        }

        @Test
        @DisplayName("우연히 단순 유리수인 주식수 변화는 거부된다")
        void rejectsCoincidentalRatios() {
            // 가격은 반토막인데 주식수가 절반으로 줄었다 — 방향이 반대다. relative 0.25
            assertThat(PriceDiscontinuity.supportsShareRatio(
                    n("0.5"), n("500"), n("1000"))).isFalse();
            // 경계 양쪽.
            assertThat(PriceDiscontinuity.supportsShareRatio(n("2.0"), n("1000"), n("1000")))
                    .isTrue();
            assertThat(PriceDiscontinuity.supportsShareRatio(n("2.01"), n("1000"), n("1000")))
                    .isFalse();
        }
    }

    @Nested
    @DisplayName("종류를 모르는 불연속의 관측값")
    class ObservedRatio {

        @Test
        @DisplayName("IMPORTANT — 방향이 ratio 계약과 같다. 뒤집히면 어디에서도 안 걸린다")
        void hasTheSameDirectionAsTheRatioContract() {
            // 5:1 분할이면 가격이 1/5이 되고 "1주가 몇 주가 되었는가"는 5다.
            assertThat(PriceDiscontinuity.observedRatio(n("20000"), n("100000")))
                    .isEqualByComparingTo("5");
            // 아시아나 2021-01-15. 뒤집으면 4.2755가 되는데 18배 차이인데도 UNKNOWN은
            // Type.allows도 ck_ca_ratio_sign도 통과시킨다.
            assertThat(PriceDiscontinuity.observedRatio(n("18000"), n("4210")))
                    .isEqualByComparingTo("0.2338888889");
        }

        @Test
        @DisplayName("종가가 0이면 데이터 손상이다 — 조용히 값을 내지 않는다")
        void refusesBrokenInput() {
            assertThatThrownBy(() -> PriceDiscontinuity.observedRatio(BigDecimal.ZERO, n("1000")))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> PriceDiscontinuity.observedRatio(null, n("1000")))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }
}
