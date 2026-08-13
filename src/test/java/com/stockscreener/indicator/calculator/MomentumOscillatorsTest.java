package com.stockscreener.indicator.calculator;

import com.stockscreener.indicator.IndicatorContext;
import com.stockscreener.indicator.IndicatorPoint;
import com.stockscreener.indicator.PriceBar;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 모멘텀 계열 오실레이터.
 *
 * <p>둘의 공통 함정은 <b>축척을 섞는 것</b>이다. ROC는 분모가 12일 전 종가라 원주가를 쓰면
 * 분할이 −80% 급락이 되고, CCI는 전형가격에 원주가 고저와 수정 종가가 섞이면 평균에서
 * 벗어난 정도가 통째로 어긋난다. 둘 다 값이 정상 범위 안이라 화면으로는 안 드러난다.
 */
class MomentumOscillatorsTest {

    private static final LocalDate START = LocalDate.of(2024, 1, 2);

    @Nested
    @DisplayName("변화율(12일)")
    class Roc {

        private final Roc12Calculator calculator = new Roc12Calculator();

        @Test
        @DisplayName("12일 전 대비 등락률을 낸다")
        void measuresTwelveDayChange() {
            // 100에서 시작해 13번째 봉이 110이면 +10%다.
            List<PriceBar> bars = new ArrayList<>();
            for (int i = 0; i < 13; i++) {
                bars.add(bar(START.plusDays(i), i == 12 ? 110 : 100));
            }

            List<IndicatorPoint> points = calculator.calculate(contextOf(bars));

            assertThat(points).hasSize(1);
            assertThat(points.get(0).value()).isEqualByComparingTo("10");
        }

        @Test
        @DisplayName("수정종가로 잰다 — 분할을 −80% 급락으로 읽지 않는다")
        void usesAdjustedClose() {
            // 원주가는 마지막에 1/5로 꺾이지만 수정종가는 평평하다. 등락률은 0이어야 한다.
            List<PriceBar> bars = new ArrayList<>();
            for (int i = 0; i < 13; i++) {
                BigDecimal raw = i == 12 ? bd(100) : bd(500);
                bars.add(new PriceBar(START.plusDays(i), raw, raw, raw, raw, bd(100),
                        1000L, null, null));
            }

            assertThat(calculator.calculate(contextOf(bars)).get(0).value())
                    .as("원주가를 쓰면 −80%가 나온다")
                    .isEqualByComparingTo("0");
        }

        @Test
        @DisplayName("봉이 13개 미만이면 값이 없다")
        void needsThirteenBars() {
            List<PriceBar> bars = new ArrayList<>();
            for (int i = 0; i < 12; i++) {
                bars.add(bar(START.plusDays(i), 100));
            }

            assertThat(calculator.calculate(contextOf(bars))).isEmpty();
        }
    }

    @Nested
    @DisplayName("CCI(20)")
    class Cci {

        private final Cci20Calculator calculator = new Cci20Calculator();

        @Test
        @DisplayName("평균 위로 벗어나면 양수, 아래면 음수다")
        void signFollowsDeviationFromMean() {
            // 19봉은 100, 마지막만 130. 마지막 전형가격이 평균보다 높으므로 양수다.
            List<PriceBar> bars = new ArrayList<>();
            for (int i = 0; i < 20; i++) {
                bars.add(bar(START.plusDays(i), i == 19 ? 130 : 100));
            }

            List<IndicatorPoint> points = calculator.calculate(contextOf(bars));

            assertThat(points).hasSize(1);
            assertThat(points.get(0).value().signum()).isEqualTo(1);
            assertThat(points.get(0).value().doubleValue())
                    .as("한 봉만 크게 튀면 ±100을 훌쩍 넘는다")
                    .isGreaterThan(100);
        }

        @Test
        @DisplayName("전형가격은 고가·저가·종가의 평균이다 — 종가만 보지 않는다")
        void usesTypicalPriceNotClose() {
            // 종가는 20봉 내내 100인데 고가·저가만 마지막에 벌어진다. 종가만 보면 값이
            // 정의되지 않지만(평균편차 0) 전형가격을 쓰면 마지막 봉이 평균에서 벗어난다.
            List<PriceBar> bars = new ArrayList<>();
            for (int i = 0; i < 20; i++) {
                boolean last = i == 19;
                bars.add(new PriceBar(START.plusDays(i), bd(100),
                        last ? bd(160) : bd(100), last ? bd(100) : bd(100),
                        bd(100), bd(100), 1000L, null, null));
            }

            assertThat(calculator.calculate(contextOf(bars)))
                    .as("종가만 보면 평균편차가 0이라 값이 안 나온다")
                    .isNotEmpty();
        }

        @Test
        @DisplayName("고가·저가를 보정해 쓴다 — 축척이 섞이면 벗어난 정도가 통째로 어긋난다")
        void usesAdjustedHighAndLow() {
            // 수정계수 0.5. 보정하면 20봉 전부 전형가격이 같아 평균편차가 0이고 값이 없다.
            // 원주가 고저에 수정 종가를 섞으면 편차가 생겨 값이 나와 버린다.
            List<PriceBar> bars = new ArrayList<>();
            for (int i = 0; i < 20; i++) {
                bars.add(new PriceBar(START.plusDays(i), bd(200), bd(200), bd(200), bd(200),
                        bd(100), 1000L, null, null));
            }

            assertThat(calculator.calculate(contextOf(bars)))
                    .as("값이 나오면 원주가 고저와 수정 종가를 섞은 것이다")
                    .isEmpty();
        }

        @Test
        @DisplayName("20일간 전형가격이 같으면 값을 내지 않는다 — 나눌 수 없다")
        void skipsWhenMeanDeviationIsZero() {
            List<PriceBar> bars = new ArrayList<>();
            for (int i = 0; i < 20; i++) {
                bars.add(bar(START.plusDays(i), 100));
            }

            assertThat(calculator.calculate(contextOf(bars))).isEmpty();
        }
    }

    // ── 픽스처 ────────────────────────────────────────────────────────────

    /** 고가·저가·종가가 같고 수정계수가 1인 봉. */
    private static PriceBar bar(LocalDate date, double price) {
        BigDecimal p = bd(price);
        return new PriceBar(date, p, p, p, p, p, 1000L, null, null);
    }

    private static BigDecimal bd(double value) {
        return BigDecimal.valueOf(value);
    }

    private static IndicatorContext contextOf(List<PriceBar> bars) {
        return new IndicatorContext(1L, bars, List.of(), List.of(), Map.of());
    }
}
