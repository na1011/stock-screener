package com.stockscreener.indicator.calculator;

import com.stockscreener.indicator.IndicatorContext;
import com.stockscreener.indicator.IndicatorPoint;
import com.stockscreener.indicator.PriceBar;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** 거래량 계열 신규 지표 — 차이킨 자금흐름·거래량 비율. */
class VolumeIndicatorsTest {

    private final Cmf21Calculator cmf = new Cmf21Calculator();
    private final VolumeRatio20Calculator ratio = new VolumeRatio20Calculator();

    @Test
    @DisplayName("종가가 늘 고가에 닫히면 CMF가 +1이다")
    void cmfIsOneWhenCloseAlwaysAtHigh() {
        List<PriceBar> bars = bars(25, 100, 90, 100, 1000);

        List<IndicatorPoint> points = cmf.calculate(context(bars));

        assertThat(points).isNotEmpty();
        assertThat(points.get(points.size() - 1).value()).isEqualByComparingTo("1");
    }

    @Test
    @DisplayName("종가가 늘 저가에 닫히면 CMF가 −1이다")
    void cmfIsMinusOneWhenCloseAlwaysAtLow() {
        List<PriceBar> bars = bars(25, 100, 90, 90, 1000);

        assertThat(cmf.calculate(context(bars)).get(0).value()).isEqualByComparingTo("-1");
    }

    @Test
    @DisplayName("IMPORTANT — 고가와 저가가 같은 봉은 기여 0이다. 그 봉 때문에 21일치를 잃지 않는다")
    void haltedBarContributesZeroInsteadOfKillingTheWindow() {
        List<PriceBar> bars = new ArrayList<>(bars(25, 100, 90, 100, 1000));
        // 거래정지 봉 하나 — 고가·저가·종가가 같다.
        bars.set(10, bar(bars.get(10).tradeDate(), 95, 95, 95, 1000));

        List<IndicatorPoint> points = cmf.calculate(context(bars));

        assertThat(points)
                .as("한 봉을 버리면 그 종목이 21거래일치를 잃는다 — 스토캐스틱과 갈리는 지점이다")
                .isNotEmpty();
        assertThat(points.get(points.size() - 1).value())
                .as("나머지 20봉은 +1이고 한 봉만 0이라 1보다 작아야 한다")
                .isLessThan(BigDecimal.ONE);
    }

    @Test
    @DisplayName("거래량이 일정하면 비율이 1.0이다")
    void ratioIsOneWhenVolumeIsFlat() {
        assertThat(ratio.calculate(context(bars(25, 100, 90, 95, 1000))).get(0).value())
                .isEqualByComparingTo("1");
    }

    @Test
    @DisplayName("오늘 거래량이 평소의 세 배면 비율이 3에 가깝다")
    void ratioRisesWithASurge() {
        List<PriceBar> bars = new ArrayList<>(bars(25, 100, 90, 95, 1000));
        int last = bars.size() - 1;
        bars.set(last, bar(bars.get(last).tradeDate(), 100, 90, 95, 3000));

        BigDecimal value = ratio.calculate(context(bars)).get(
                ratio.calculate(context(bars)).size() - 1).value();

        // 창 안에 급증 봉 자신이 들어 있어 평균이 조금 올라간다. 정확히 3은 아니고 그 근처다.
        assertThat(value).isBetween(new BigDecimal("2.5"), new BigDecimal("3.0"));
    }

    @Test
    @DisplayName("창이 덜 차면 값을 내지 않는다 — 짧은 창으로 지어내지 않는다")
    void producesNothingBeforeTheWindowFills() {
        assertThat(cmf.calculate(context(bars(20, 100, 90, 95, 1000)))).isEmpty();
        assertThat(ratio.calculate(context(bars(19, 100, 90, 95, 1000)))).isEmpty();
    }

    @Test
    @DisplayName("IMPORTANT — 분할 구간에서 거래량 비율이 튀지 않는다. 수정 축척을 쓴다")
    void ratioSurvivesASplit() {
        // 앞 20봉은 분할 전(원 거래량 1,000 · adjClose가 종가의 1/2), 뒤는 분할 후 2,000주.
        // 보정하면 앞 구간도 2,000주 상당이라 비율이 1.0 근처여야 한다.
        List<PriceBar> bars = new ArrayList<>();
        LocalDate start = LocalDate.of(2026, 1, 2);
        for (int i = 0; i < 20; i++) {
            bars.add(new PriceBar(start.plusDays(i), BigDecimal.valueOf(100), BigDecimal.valueOf(100),
                    BigDecimal.valueOf(90), BigDecimal.valueOf(95), BigDecimal.valueOf(47.5),
                    1000L, null, null));
        }
        for (int i = 20; i < 25; i++) {
            bars.add(new PriceBar(start.plusDays(i), BigDecimal.valueOf(50), BigDecimal.valueOf(50),
                    BigDecimal.valueOf(45), BigDecimal.valueOf(47.5), BigDecimal.valueOf(47.5),
                    2000L, null, null));
        }

        List<IndicatorPoint> points = ratio.calculate(context(bars));

        assertThat(points.get(points.size() - 1).value())
                .as("보정을 빠뜨리면 분할 당일부터 비율이 배수만큼 튀고 '급증'으로 읽힌다")
                .isBetween(new BigDecimal("0.9"), new BigDecimal("1.1"));
    }

    @Test
    @DisplayName("회전율은 거래량 ÷ 상장주식수다")
    void turnoverRatioDividesVolumeByListedShares() {
        PriceBar bar = new PriceBar(LocalDate.of(2026, 1, 2), BigDecimal.valueOf(100),
                BigDecimal.valueOf(100), BigDecimal.valueOf(90), BigDecimal.valueOf(95),
                BigDecimal.valueOf(95), 50_000L, null, BigDecimal.valueOf(1_000_000));

        assertThat(new TurnoverRatioCalculator().calculate(context(List.of(bar))).get(0).value())
                .isEqualByComparingTo("5");
    }

    @Test
    @DisplayName("IMPORTANT — 회전율은 원 거래량을 쓴다. 수정 거래량을 쓰면 분할 이전이 배수만큼 커진다")
    void turnoverRatioUsesRawVolumeSoSplitsCancelOut() {
        // 분할 전 봉 — close 100, adjClose 50(2:1 분할이 뒤에 있었다), 거래량 1,000, 주식수 10,000.
        // 회전율은 1,000/10,000 = 10%다. 수정 거래량(1,000 × 100/50 = 2,000)을 쓰면 20%가 된다.
        PriceBar beforeSplit = new PriceBar(LocalDate.of(2026, 1, 2), BigDecimal.valueOf(100),
                BigDecimal.valueOf(100), BigDecimal.valueOf(90), BigDecimal.valueOf(100),
                BigDecimal.valueOf(50), 1_000L, null, BigDecimal.valueOf(10_000));

        assertThat(new TurnoverRatioCalculator().calculate(context(List.of(beforeSplit))).get(0).value())
                .as("비율은 축척에 불변이다 — 분할이면 거래량과 주식수가 함께 커진다")
                .isEqualByComparingTo("10");
    }

    @Test
    @DisplayName("상장주식수가 없으면 계산하지 않는다 — 0으로 두면 회전율이 무한이 된다")
    void turnoverRatioNeedsListedShares() {
        PriceBar bar = new PriceBar(LocalDate.of(2026, 1, 2), BigDecimal.valueOf(100),
                BigDecimal.valueOf(100), BigDecimal.valueOf(90), BigDecimal.valueOf(95),
                BigDecimal.valueOf(95), 50_000L, null, null);

        assertThat(new TurnoverRatioCalculator().calculate(context(List.of(bar)))).isEmpty();
    }

    @Test
    @DisplayName("관문 켬의 60일 회전율은 창 60봉 전부에 주식수가 있어야 값을 낸다 — 꺼짐은 빈 날을 빼고 평균한다(지금 그대로)")
    void turnoverRatio60NeedsTheFullWindowWhenGateIsOn() {
        List<PriceBar> bars = new ArrayList<>();
        LocalDate start = LocalDate.of(2026, 1, 2);
        for (int i = 0; i < 61; i++) {
            BigDecimal shares = i == 0 ? null : BigDecimal.valueOf(1_000_000);
            bars.add(new PriceBar(start.plusDays(i), BigDecimal.TEN, BigDecimal.TEN, BigDecimal.TEN, BigDecimal.TEN,
                    BigDecimal.TEN, 10_000L, null, shares));
        }
        TurnoverRatio60Calculator calculator = new TurnoverRatio60Calculator();

        assertThat(calculator.calculate(context(bars))).as("꺼짐: 첫 창(59봉)도 값").hasSize(2);
        assertThat(calculator.calculate(context(bars).withGateOn(true)))
                .as("켬: 주식수가 빈 첫 봉이 든 창은 값 없음").hasSize(1);
    }

    private static IndicatorContext context(List<PriceBar> bars) {
        return new IndicatorContext(1L, bars, List.of(), List.of(), null);
    }

    private static List<PriceBar> bars(int count, int high, int low, int close, long volume) {
        List<PriceBar> bars = new ArrayList<>(count);
        LocalDate start = LocalDate.of(2026, 1, 2);
        for (int i = 0; i < count; i++) {
            bars.add(bar(start.plusDays(i), high, low, close, volume));
        }
        return bars;
    }

    /** 수정계수가 1인 봉 — `adjClose = close`라 보정이 항등이다. */
    private static PriceBar bar(LocalDate date, int high, int low, int close, long volume) {
        BigDecimal closeValue = BigDecimal.valueOf(close);
        return new PriceBar(date, closeValue, BigDecimal.valueOf(high), BigDecimal.valueOf(low),
                closeValue, closeValue, volume, null, null);
    }
}
