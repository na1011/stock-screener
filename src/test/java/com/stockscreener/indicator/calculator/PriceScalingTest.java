package com.stockscreener.indicator.calculator;

import com.stockscreener.indicator.PriceBar;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 축척 변환의 방향을 못박는다.
 *
 * <p>이 파일이 {@link PriceScaling#adjustedVolume}의 <b>유일한 호출자</b>다 — 거래량을 쓰는
 * 계산기가 아직 없고 첫 소비자는 P12의 주봉 OBV다(ADR-0046). 소비자가 오기 전에 부호를
 * 고정해 두는 것이 이 테스트의 목적이며, <b>뒤집혀도 값은 그럴듯하다</b>는 것이 그 이유다.
 *
 * <p>MUST: 가격과 거래량을 <b>같은 봉으로</b> 함께 잰다. 따로 재면 둘이 같은 방향인지
 * 반대 방향인지를 어느 단언도 묻지 않게 된다.
 */
class PriceScalingTest {

    private static final LocalDate DAY = LocalDate.of(2023, 5, 15);

    @Nested
    @DisplayName("액면병합 — 대한전선 2023-05-16 실측 10:1")
    class ReverseSplit {

        /** 병합 전 봉: 종가 1,492. 계수 10이 붙어 수정종가는 14,920이다. */
        private final PriceBar bar = bar(1_492, 14_920, 250_000L);

        @Test
        @DisplayName("가격은 올라간다 — 병합 전 1,492원짜리 고가가 수정 축척에서 10배다")
        void priceScalesUp() {
            assertThat(PriceScaling.adjusted(bar, BigDecimal.valueOf(1_500)))
                    .isEqualByComparingTo("15000");
        }

        @Test
        @DisplayName("거래량은 내려간다 — 같은 봉에서 가격의 역수다")
        void volumeScalesDown() {
            assertThat(PriceScaling.adjustedVolume(bar)).isEqualByComparingTo("25000");
        }

        @Test
        @DisplayName("역수를 뒤집으면 250,000이 2,500,000이 되어 이 단언이 깨진다")
        void directionIsNotAnIdentity() {
            BigDecimal flipped = BigDecimal.valueOf(bar.volume())
                    .multiply(bar.adjClose())
                    .divide(bar.close(), MovingAverages.SCALE, RoundingMode.HALF_UP);
            assertThat(PriceScaling.adjustedVolume(bar)).isNotEqualByComparingTo(flipped);
            assertThat(flipped).isEqualByComparingTo("2500000");
        }
    }

    @Nested
    @DisplayName("액면분할 5:1")
    class Split {

        /** 분할 전 봉: 종가 50,000. 계수 1/5이 붙어 수정종가는 10,000이다. */
        private final PriceBar bar = bar(50_000, 10_000, 1_000L);

        @Test
        @DisplayName("가격은 내려가고 거래량은 올라간다 — 분할 전 1주가 분할 후 5주다")
        void volumeScalesUpWhilePriceScalesDown() {
            assertThat(PriceScaling.adjusted(bar, bar.close())).isEqualByComparingTo("10000");
            assertThat(PriceScaling.adjustedVolume(bar)).isEqualByComparingTo("5000");
        }

        @Test
        @DisplayName("adj_factor로 검산하면 volume ÷ adj_factor다")
        void matchesDivisionByAdjustmentFactor() {
            BigDecimal adjFactor = new BigDecimal("0.2");
            assertThat(PriceScaling.adjustedVolume(bar)).isEqualByComparingTo(
                    BigDecimal.valueOf(bar.volume())
                            .divide(adjFactor, MovingAverages.SCALE,
                                    RoundingMode.HALF_UP));
        }
    }

    @Test
    @DisplayName("보정된 적 없는 봉은 거래량이 원값 그대로다")
    void unadjustedBarKeepsRawVolume() {
        assertThat(PriceScaling.adjustedVolume(bar(3_000, 3_000, 12_345L)))
                .isEqualByComparingTo("12345");
    }

    @Test
    @DisplayName("종가가 0이면 나눌 수 없으므로 원값을 쓴다 — 배치를 죽이지 않는다")
    void zeroCloseFallsBackToRawVolume() {
        assertThat(PriceScaling.adjustedVolume(bar(0, 0, 777L))).isEqualByComparingTo("777");
    }

    private static PriceBar bar(int close, int adjClose, long volume) {
        BigDecimal c = BigDecimal.valueOf(close);
        return new PriceBar(DAY, c, c, c, c, BigDecimal.valueOf(adjClose), volume, null, null);
    }
}
