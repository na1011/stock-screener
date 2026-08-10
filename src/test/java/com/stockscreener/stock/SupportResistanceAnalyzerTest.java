package com.stockscreener.stock;

import com.stockscreener.indicator.PriceBar;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 합성 봉으로 결정적으로 검증한다. 난수도 시간도 DB도 쓰지 않는다.
 *
 * <p>가장 중요한 검사는 <b>액면분할</b>이다. 수정 공간으로 옮기지 않으면 분할 전 가격대에
 * 존재하지 않는 지지선이 생기고, 그 값은 그럴듯해서 화면을 봐도 드러나지 않는다.
 */
class SupportResistanceAnalyzerTest {

    private static final LocalDate START = LocalDate.of(2026, 1, 5);

    private final SupportResistanceAnalyzer analyzer = new SupportResistanceAnalyzer();

    @Test
    @DisplayName("봉이 모자라면 값을 지어내지 않는다")
    void reportsInsufficientDataBelowMinimum() {
        PriceReference reference = analyzer.analyze(flatBars(59, 10_000));

        assertThat(reference.insufficientData()).isTrue();
        assertThat(reference.zones()).isEmpty();
        // 한계 표시는 데이터가 없어도 실린다 — 프론트가 문구를 빠뜨릴 수 없게 한다.
        assertThat(reference.disclaimer()).isNotBlank();
        assertThat(reference.kind()).isEqualTo(PriceReference.KIND);
    }

    @Test
    @DisplayName("같은 가격대를 반복해서 두드리면 하나의 구간으로 묶이고 터치 수가 센다")
    void repeatedVisitsBecomeOneZone() {
        // 9,000 ~ 10,000을 오가는 톱니. 저점 9,000이 반복적으로 의미를 가진다.
        List<PriceBar> bars = new ArrayList<>();
        for (int i = 0; i < 120; i++) {
            int price = (i / 10) % 2 == 0 ? 9_000 : 10_000;
            bars.add(bar(START.plusDays(i), price));
        }

        PriceReference reference = analyzer.analyze(bars);

        assertThat(reference.insufficientData()).isFalse();
        assertThat(reference.zones()).isNotEmpty();
        assertThat(reference.zones()).anySatisfy(zone ->
                assertThat(zone.touchCount()).isGreaterThanOrEqualTo(2));
    }

    @Test
    @DisplayName("IMPORTANT — 연속으로 머문 봉은 터치 1회다. 5일 눌림목이 5회가 되면 안 된다")
    void consecutiveBarsCountAsSingleTouch() {
        // 9,000에서 정확히 두 번, 각각 여러 날 머문다.
        List<PriceBar> bars = new ArrayList<>();
        for (int i = 0; i < 120; i++) {
            boolean atLow = (i >= 20 && i < 30) || (i >= 80 && i < 90);
            bars.add(bar(START.plusDays(i), atLow ? 9_000 : 12_000));
        }

        PriceReference reference = analyzer.analyze(bars);

        // 오래 머문 구간이 여러 번 반등한 구간보다 강해 보이면 강도 표시가 무의미해진다.
        assertThat(reference.zones())
                .allSatisfy(zone -> assertThat(zone.touchCount()).isLessThanOrEqualTo(4));
    }

    @Test
    @DisplayName("IMPORTANT — 분석 구간에 액면분할이 있어도 분할 전 가격대에 가짜 구간이 생기지 않는다")
    void splitDoesNotCreatePhantomZones() {
        // 앞 60봉은 원주가 50,000(수정종가 10,000 → 배율 0.2), 뒤 60봉은 원주가 10,000.
        // 수정 공간에서는 전 구간이 10,000 근처라 50,000대 구간이 나오면 안 된다.
        List<PriceBar> bars = new ArrayList<>();
        for (int i = 0; i < 60; i++) {
            int raw = 50_000 + (i % 5) * 500;
            bars.add(splitBar(START.plusDays(i), raw, raw / 5));
        }
        for (int i = 60; i < 120; i++) {
            int raw = 10_000 + (i % 5) * 100;
            bars.add(splitBar(START.plusDays(i), raw, raw));
        }

        PriceReference reference = analyzer.analyze(bars);

        assertThat(reference.insufficientData()).isFalse();
        // 수정 공간을 쓰지 않으면 50,000 근처에 지지 구간이 생긴다 — 존재하지 않는 가격대다.
        assertThat(reference.zones()).allSatisfy(zone ->
                assertThat(zone.upper()).isLessThan(BigDecimal.valueOf(20_000)));
        assertThat(reference.lastClose()).isLessThan(BigDecimal.valueOf(20_000));
    }

    @Test
    @DisplayName("마지막 종가를 기준으로 지지·저항·구간내가 갈린다")
    void rolesAreRelativeToLastClose() {
        List<PriceBar> bars = new ArrayList<>();
        for (int i = 0; i < 110; i++) {
            int price = (i / 10) % 2 == 0 ? 8_000 : 12_000;
            bars.add(bar(START.plusDays(i), price));
        }
        // 마지막 10봉은 중간값에서 마무리한다.
        for (int i = 110; i < 120; i++) {
            bars.add(bar(START.plusDays(i), 10_000));
        }

        PriceReference reference = analyzer.analyze(bars);
        BigDecimal lastClose = reference.lastClose();

        assertThat(reference.zones()).allSatisfy(zone -> {
            switch (zone.role()) {
                case SUPPORT -> assertThat(zone.upper()).isLessThan(lastClose);
                case RESISTANCE -> assertThat(zone.lower()).isGreaterThan(lastClose);
                case IN_RANGE -> {
                    assertThat(zone.lower()).isLessThanOrEqualTo(lastClose);
                    assertThat(zone.upper()).isGreaterThanOrEqualTo(lastClose);
                }
            }
        });
    }

    @Test
    @DisplayName("한 번만 스친 가격은 구간이 되지 않는다")
    void singleTouchIsNotAZone() {
        // 계속 10,000인데 딱 한 번 15,000을 찍는다.
        List<PriceBar> bars = new ArrayList<>(flatBars(120, 10_000));
        bars.set(50, bar(START.plusDays(50), 15_000));

        PriceReference reference = analyzer.analyze(bars);

        assertThat(reference.zones()).allSatisfy(zone ->
                assertThat(zone.touchCount()).isGreaterThanOrEqualTo(2));
        assertThat(reference.zones()).noneSatisfy(zone ->
                assertThat(zone.lower()).isGreaterThan(BigDecimal.valueOf(14_000)));
    }

    @Test
    @DisplayName("결과가 결정적이다 — 같은 봉이면 언제나 같은 값이다")
    void isDeterministic() {
        List<PriceBar> bars = new ArrayList<>();
        for (int i = 0; i < 120; i++) {
            bars.add(bar(START.plusDays(i), 9_000 + (i % 7) * 300));
        }

        assertThat(analyzer.analyze(bars)).isEqualTo(analyzer.analyze(bars));
    }

    @Test
    @DisplayName("수정종가가 없는 봉이 섞여도 깨지지 않는다")
    void toleratesBarsWithoutAdjustedClose() {
        List<PriceBar> bars = new ArrayList<>();
        for (int i = 0; i < 120; i++) {
            int price = (i / 10) % 2 == 0 ? 9_000 : 11_000;
            bars.add(i == 30
                    ? new PriceBar(START.plusDays(i), null, null, null,
                            BigDecimal.valueOf(price), null, 1000L, null, null)
                    : bar(START.plusDays(i), price));
        }

        assertThat(analyzer.analyze(bars).insufficientData()).isFalse();
    }

    private static List<PriceBar> flatBars(int count, int price) {
        List<PriceBar> bars = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            bars.add(bar(START.plusDays(i), price));
        }
        return bars;
    }

    /** 고가·저가에 ±1% 폭을 준 평범한 봉. 수정종가 = 원주가(분할 없음). */
    private static PriceBar bar(LocalDate date, int close) {
        BigDecimal c = BigDecimal.valueOf(close);
        BigDecimal high = c.multiply(new BigDecimal("1.01"));
        BigDecimal low = c.multiply(new BigDecimal("0.99"));
        return new PriceBar(date, c, high, low, c, c, 1000L, null, null);
    }

    /** 원주가와 수정종가가 다른 봉(분할 구간). */
    private static PriceBar splitBar(LocalDate date, int rawClose, int adjClose) {
        BigDecimal raw = BigDecimal.valueOf(rawClose);
        return new PriceBar(date, raw,
                raw.multiply(new BigDecimal("1.01")), raw.multiply(new BigDecimal("0.99")),
                raw, BigDecimal.valueOf(adjClose), 1000L, null, null);
    }
}
