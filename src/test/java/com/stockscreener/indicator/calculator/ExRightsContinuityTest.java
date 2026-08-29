package com.stockscreener.indicator.calculator;

import com.stockscreener.indicator.FinancialFigures;
import com.stockscreener.indicator.IndicatorCalculator;
import com.stockscreener.indicator.IndicatorContext;
import com.stockscreener.indicator.IndicatorPoint;
import com.stockscreener.indicator.PriceBar;
import com.stockscreener.indicator.ShareAdjustment;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 한 사건이 바꾸는 것을 전부 바꿨는가 — 권리락 구간의 밸류에이션과 주식수 증가율(ADR-0051).
 *
 * <p>네 지표를 <b>한 파일에서 함께</b> 재는 것이 요점이다. {@code sharesOn}을 거치는 자리가
 * {@code MARKET_CAP}·{@code PER}·{@code PBR} 셋으로 흩어져 있어, 따로 재면 <b>하나를
 * 빠뜨린 것이 조용하다.</b>
 *
 * <p>IMPORTANT — 픽스처가 {@value #BARS}봉인 이유는 {@code SHARES_GROWTH_3Y}가
 * {@code bars.size() <= 750}이면 <b>빈 목록</b>을 내기 때문이다. 짧은 픽스처에서는 그 지표에
 * 대한 단언이 공집합 단언이 되어 <b>구현이 무엇이든 통과한다.</b>
 *
 * <p>병합 표본({@code ratio < 1})을 함께 두는 이유는 ADR-0049의 <i>"방향 테스트는 왕복이고
 * 병합 표본을 반드시 넣는다"</i>가 {@code ratio}의 새 소비자에게도 걸리기 때문이다 —
 * 지금까지 그 값을 쓰는 곳은 재구축(가격에 역수)뿐이었고 여기서 주식수(곱)가 둘째가 된다.
 */
class ExRightsContinuityTest {

    /** 750봉 워밍업 + 실제로 값을 볼 50봉. */
    private static final int BARS = 800;

    private static final LocalDate START = LocalDate.of(2023, 1, 2);

    /** 무상증자 권리락. 이 봉부터 가격이 반토막이다. */
    private static final int EX_RIGHTS = 770;
    /** 권리락 구간에 낀 전환권행사. 진짜 희석이라 살아남아야 한다(루닛 2026-05-15 ×1.27). */
    private static final int CONVERSION = 776;
    /** 신주상장. 이 봉부터 주식수가 두 배다. 권리락과 14봉 벌어진다. */
    private static final int NEW_LISTING = 784;

    private static final BigDecimal EQUITY = new BigDecimal("400000000000");
    private static final BigDecimal QUARTERLY_NET_INCOME = new BigDecimal("2500000000");

    @Nested
    @DisplayName("무상증자 — 가격은 권리락에, 주식수는 신주상장에 바뀐다")
    class BonusIssue {

        private final IndicatorContext context = bonusIssueContext();

        @Test
        @DisplayName("시가총액이 권리락에서 절벽을 갖지 않는다")
        void marketCapHasNoCliff() {
            Map<LocalDate, BigDecimal> caps = seriesOf(new MarketCapCalculator(), context);

            // 40,000 × 10,000,000 = 4e11. 권리락 당일은 20,000 × 10,000,000 × 2로 같은 값이다.
            assertThat(caps.get(day(EX_RIGHTS - 1))).isEqualByComparingTo("400000000000");
            assertThat(caps.get(day(EX_RIGHTS))).isEqualByComparingTo("400000000000");
            // 신주상장 당일은 원값만으로 이미 맞다 — 보정이 그 자리에서 끊겨야 한다.
            assertThat(caps.get(day(NEW_LISTING))).isEqualByComparingTo("508000000000");
            assertThat(caps.get(day(NEW_LISTING - 1))).isEqualByComparingTo("508000000000");
        }

        @Test
        @DisplayName("권리락 구간에 낀 전환권행사는 살아남는다 — 그것은 진짜 희석이다")
        void realDilutionInsideTheWindowSurvives() {
            Map<LocalDate, BigDecimal> caps = seriesOf(new MarketCapCalculator(), context);

            assertThat(caps.get(day(CONVERSION - 1))).isEqualByComparingTo("400000000000");
            assertThat(caps.get(day(CONVERSION))).isEqualByComparingTo("508000000000");
        }

        @Test
        @DisplayName("PBR이 권리락에서 절벽을 갖지 않는다")
        void pbrHasNoCliff() {
            Map<LocalDate, BigDecimal> pbr = seriesOf(new PbrCalculator(), context);

            // close × shares ÷ equity = 4e11 ÷ 4e11 = 1
            assertThat(pbr.get(day(EX_RIGHTS - 1))).isEqualByComparingTo("1");
            assertThat(pbr.get(day(EX_RIGHTS))).isEqualByComparingTo("1");
        }

        @Test
        @DisplayName("PER이 권리락에서 절벽을 갖지 않는다")
        void perHasNoCliff() {
            Map<LocalDate, BigDecimal> per = seriesOf(new PerCalculator(), context);

            // close × shares ÷ TTM순이익 = 4e11 ÷ 1e10 = 40
            assertThat(per.get(day(EX_RIGHTS - 1))).isEqualByComparingTo("40");
            assertThat(per.get(day(EX_RIGHTS))).isEqualByComparingTo("40");
        }

        @Test
        @DisplayName("보정하지 않으면 셋 다 절반이 된다 — 이 테스트가 항등식이 아니라는 증거")
        void withoutTheLedgerAllThreeHalve() {
            IndicatorContext blind = withoutAdjustments(context);

            assertThat(seriesOf(new MarketCapCalculator(), blind).get(day(EX_RIGHTS)))
                    .isEqualByComparingTo("200000000000");
            assertThat(seriesOf(new PbrCalculator(), blind).get(day(EX_RIGHTS)))
                    .isEqualByComparingTo("0.5");
            assertThat(seriesOf(new PerCalculator(), blind).get(day(EX_RIGHTS)))
                    .isEqualByComparingTo("20");
        }

        @Test
        @DisplayName("무상증자로 는 주식수는 희석이 아니다 — 전환권행사분만 남는다")
        void sharesGrowthKeepsOnlyRealDilution() {
            Map<LocalDate, BigDecimal> growth =
                    seriesOf(new SharesGrowth3yCalculator(), context);

            // 10,000,000 → 25,400,000이지만 그중 ×2는 무상증자다. 남는 것은 ×1.27뿐.
            assertThat(growth.get(day(BARS - 1))).isEqualByComparingTo("27.00000000");
            // 권리락 구간 안에서도 같은 값이어야 한다 — sharesOn의 계단과 배수가 상쇄된다.
            assertThat(growth.get(day(NEW_LISTING - 1))).isEqualByComparingTo("27.00000000");
        }

        @Test
        @DisplayName("보정하지 않으면 154%가 되어 필수 게이트를 못 넘는다")
        void withoutTheLedgerSharesGrowthReadsAsDilution() {
            assertThat(seriesOf(new SharesGrowth3yCalculator(), withoutAdjustments(context))
                    .get(day(BARS - 1)))
                    .isEqualByComparingTo("154.00000000");
        }
    }

    @Nested
    @DisplayName("액면병합 — 두 날짜가 같아 구간이 공집합이다")
    class ReverseSplit {

        private final IndicatorContext context = reverseSplitContext();

        @Test
        @DisplayName("주식수가 준 것은 희석의 반대가 아니다 — 걷어내면 0이다")
        void sharesGrowthCancelsTheMerge() {
            assertThat(seriesOf(new SharesGrowth3yCalculator(), context).get(day(BARS - 1)))
                    .isEqualByComparingTo("0.00000000");
        }

        @Test
        @DisplayName("걷어내지 않으면 −90%다 — 자사주 소각으로 읽힌다")
        void withoutTheLedgerItReadsAsBuyback() {
            assertThat(seriesOf(new SharesGrowth3yCalculator(), withoutAdjustments(context))
                    .get(day(BARS - 1)))
                    .isEqualByComparingTo("-90.00000000");
        }

        @Test
        @DisplayName("나눗셈을 곱셈으로 뒤집으면 −99%가 되어 위 단언이 깨진다")
        void directionIsNotAnIdentity() {
            BigDecimal past = new BigDecimal("100000000");
            BigDecimal now = new BigDecimal("10000000");
            BigDecimal ratio = new BigDecimal("0.1");

            BigDecimal correct = now.divide(past.multiply(ratio), MovingAverages.MC);
            BigDecimal flipped = now.divide(past.divide(ratio, MovingAverages.MC),
                    MovingAverages.MC);

            assertThat(correct).isEqualByComparingTo("1");
            assertThat(flipped).isEqualByComparingTo("0.01");
        }

        @Test
        @DisplayName("두 날짜가 같으면 시가총액 보정이 걸리지 않는다")
        void marketCapIsUntouched() {
            Map<LocalDate, BigDecimal> caps = seriesOf(new MarketCapCalculator(), context);

            assertThat(caps.get(day(EX_RIGHTS - 1))).isEqualByComparingTo("100000000000");
            assertThat(caps.get(day(EX_RIGHTS))).isEqualByComparingTo("100000000000");
        }
    }

    @Test
    @DisplayName("신주상장일 없는 기업 행위는 만들 수 없다 — 리더가 효력일로 접어서 넘긴다")
    void listingDateMustBeFoldedBeforeItReachesTheIndicatorLayer() {
        assertThatThrownBy(() -> new ShareAdjustment(day(EX_RIGHTS), null, BigDecimal.TWO))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("효력일로 접어서");
    }

    // --- 픽스처 -------------------------------------------------------------

    /**
     * 루닛 2026과 같은 모양 — 권리락에서 가격이 반토막, 14봉 뒤 신주상장에서 주식수가 두 배,
     * 그 사이에 전환권행사 ×1.27이 하나 낀다.
     */
    private static IndicatorContext bonusIssueContext() {
        List<PriceBar> bars = new ArrayList<>(BARS);
        for (int i = 0; i < BARS; i++) {
            BigDecimal close = BigDecimal.valueOf(i < EX_RIGHTS ? 40_000 : 20_000);
            long shares = i < CONVERSION ? 10_000_000L : 12_700_000L;
            if (i >= NEW_LISTING) {
                shares *= 2;
            }
            bars.add(bar(day(i), close, BigDecimal.valueOf(shares)));
        }
        return context(bars, List.of(
                new ShareAdjustment(day(EX_RIGHTS), day(NEW_LISTING), BigDecimal.TWO)));
    }

    /** 대한전선 2023과 같은 모양 — 10:1 병합이라 가격과 주식수가 <b>같은 날</b> 바뀐다. */
    private static IndicatorContext reverseSplitContext() {
        List<PriceBar> bars = new ArrayList<>(BARS);
        for (int i = 0; i < BARS; i++) {
            boolean merged = i >= EX_RIGHTS;
            bars.add(bar(day(i),
                    BigDecimal.valueOf(merged ? 10_000 : 1_000),
                    BigDecimal.valueOf(merged ? 10_000_000L : 100_000_000L)));
        }
        return context(bars, List.of(
                new ShareAdjustment(day(EX_RIGHTS), day(EX_RIGHTS), new BigDecimal("0.1"))));
    }

    private static IndicatorContext context(List<PriceBar> bars,
                                            List<ShareAdjustment> adjustments) {
        return new IndicatorContext(1L, null, bars, List.of(), quarters(), adjustments, Map.of());
    }

    /** 같은 봉·같은 재무에 원장만 없앤 컨텍스트. 단언이 실제로 원장에 걸려 있는지 가른다. */
    private static IndicatorContext withoutAdjustments(IndicatorContext context) {
        return new IndicatorContext(context.stockId(), context.industryDivisionKey(),
                context.priceBars(), context.annualFigures(), context.quarterlyFigures(),
                List.of(), Map.of());
    }

    /** TTM 순이익 100억, 자본 4,000억. 사건과 무관하게 고정이라 값 변화의 원인이 주식수뿐이다. */
    private static List<FinancialFigures> quarters() {
        List<FinancialFigures> quarters = new ArrayList<>(4);
        LocalDate periodEnd = LocalDate.of(2022, 12, 31);
        for (int i = 0; i < 4; i++) {
            quarters.add(FinancialFiguresFixture.quarter(periodEnd.minusMonths(3L * i))
                    .netIncome(QUARTERLY_NET_INCOME)
                    .totalEquity(EQUITY)
                    .build());
        }
        return quarters;
    }

    private static PriceBar bar(LocalDate date, BigDecimal close, BigDecimal listedShares) {
        return new PriceBar(date, close, close, close, close, close, 1_000L, null, listedShares);
    }

    private static LocalDate day(int index) {
        return START.plusDays(index);
    }

    private static Map<LocalDate, BigDecimal> seriesOf(IndicatorCalculator calculator,
                                                       IndicatorContext context) {
        return calculator.calculate(context).stream()
                .collect(Collectors.toMap(IndicatorPoint::tradeDate, IndicatorPoint::value));
    }
}
