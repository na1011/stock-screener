package com.stockscreener.indicator;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 미보정 기업 행위가 창 안에 있는 산출 일자를 고르는 규칙의 완료 판정(ADR-0055).
 *
 * <p>합성 봉으로만 잰다. 실데이터로는 이 경계를 <b>원리적으로 잴 수 없다</b> — 보존창
 * 안에 효력일이 있는 종목이 207940 하나인데 그 종목은 매매거래정지 때문에 효력일 이전 봉이
 * 보존창 안에 하나도 없다. 즉 "사건 이전 일자에는 값이 남는다"의 대조군이 공집합이라,
 * 규칙이 맞아도 틀려도 화면이 같다.
 */
class UnadjustedActionGuardTest {

    private static final LocalDate START = LocalDate.of(2026, 1, 5);

    @Test
    @DisplayName("사건이 없으면 아무 일자도 빼지 않는다 — 전 종목의 99%가 이 경로다")
    void suppressesNothingWithoutActions() {
        assertThat(UnadjustedActionGuard.suppressedDates(bars(10), List.of(), 5)).isEmpty();
    }

    @Test
    @DisplayName("창이 사건을 삼키는 일자만 뺀다 — 사건 이전 일자는 그대로 남는다")
    void suppressesOnlyDatesWhoseWindowSpansTheAction() {
        List<PriceBar> bars = bars(10);
        LocalDate effectiveDate = bars.get(5).tradeDate();

        var suppressed = UnadjustedActionGuard.suppressedDates(bars, List.of(effectiveDate), 3);

        // 창 3봉이므로 인덱스 i의 창은 [i-2, i]다. 사건일(5)을 삼키면서 창 시작이 그보다
        // 앞인 것은 5·6뿐이고, 7의 창은 [5,7]이라 시작이 정확히 사건일이다.
        assertThat(suppressed).containsExactly(
                bars.get(5).tradeDate(), bars.get(6).tradeDate());
        assertThat(suppressed)
                .as("사건이 미래인 일자는 축척이 하나뿐이라 값이 맞는다")
                .doesNotContain(bars.get(4).tradeDate());
        assertThat(suppressed)
                .as("창이 사건일에서 시작하면 창 안이 전부 사건 이후라 값이 맞는다")
                .doesNotContain(bars.get(7).tradeDate(), bars.get(8).tradeDate());
    }

    @Test
    @DisplayName("창 시작이 정확히 효력일이면 빼지 않는다 — 부등호를 뒤집으면 이 단언이 깨진다")
    void doesNotSuppressWhenTheWindowStartsExactlyOnTheAction() {
        List<PriceBar> bars = bars(10);
        LocalDate effectiveDate = bars.get(5).tradeDate();

        // 경계는 언제나 `사건 인덱스 + 창 - 1`이다. 그 일자의 창은 사건일에서 시작하므로
        // 창 안 모든 봉이 사건 이후이고 축척이 하나다 — 여기서 빼면 지표마다 하루씩 더
        // 지워지는데, 값이 사라지는 방향이라 어떤 테스트도 화면도 그것을 말하지 않는다.
        assertThat(UnadjustedActionGuard.suppressedDates(bars, List.of(effectiveDate), 3))
                .as("창 3봉의 경계는 인덱스 7이다")
                .doesNotContain(bars.get(7).tradeDate())
                .containsExactly(bars.get(5).tradeDate(), bars.get(6).tradeDate());

        assertThat(UnadjustedActionGuard.suppressedDates(bars, List.of(effectiveDate), 2))
                .as("창 2봉의 경계는 인덱스 6이다. 부등호를 닫힘으로 바꾸면 둘 다 깨진다")
                .doesNotContain(bars.get(6).tradeDate())
                .containsExactly(bars.get(5).tradeDate());
    }

    @Test
    @DisplayName("창이 길수록 더 많은 일자가 빠진다 — 창 길이가 판정에 실제로 쓰인다")
    void longerWindowSuppressesMoreDates() {
        List<PriceBar> bars = bars(30);
        LocalDate effectiveDate = bars.get(10).tradeDate();

        assertThat(UnadjustedActionGuard.suppressedDates(bars, List.of(effectiveDate), 1))
                .as("창 1봉은 자기 자신뿐이라 두 축척을 이어붙일 수 없다")
                .isEmpty();
        assertThat(UnadjustedActionGuard.suppressedDates(bars, List.of(effectiveDate), 5))
                .hasSize(4);
        assertThat(UnadjustedActionGuard.suppressedDates(bars, List.of(effectiveDate), 20))
                .hasSize(19);
    }

    @Test
    @DisplayName("창이 시세보다 길면 잘린 창으로 판정한다 — 없는 봉을 있다고 치지 않는다")
    void truncatedWindowUsesTheOldestAvailableBar() {
        List<PriceBar> bars = bars(5);
        LocalDate beforeFirstBar = bars.get(0).tradeDate().minusDays(1);

        assertThat(UnadjustedActionGuard.suppressedDates(bars, List.of(beforeFirstBar), 1000))
                .as("사건이 가장 오래된 봉보다 앞이면 창 안 모든 봉이 사건 이후다")
                .isEmpty();
    }

    @Test
    @DisplayName("거래정지로 봉이 비어도 날짜로 판정한다 — 인덱스 산술이면 창이 어긋난다")
    void spansTradingHaltsBecauseComparisonIsByDate() {
        // 007340의 모양이다: 2024-09-25~10-07이 정지라 봉이 통째로 빈다.
        List<PriceBar> bars = List.of(
                bar(LocalDate.of(2024, 9, 23)),
                bar(LocalDate.of(2024, 9, 24)),
                bar(LocalDate.of(2024, 10, 8)),
                bar(LocalDate.of(2024, 10, 10)));
        LocalDate effectiveDate = LocalDate.of(2024, 10, 8);

        var suppressed = UnadjustedActionGuard.suppressedDates(bars, List.of(effectiveDate), 2);

        assertThat(suppressed)
                .as("정지 구간을 건너뛴 두 봉이 서로 다른 축척이라 그 창은 값을 낼 수 없다")
                .containsExactly(LocalDate.of(2024, 10, 8));
    }

    @Test
    @DisplayName("사건이 여럿이면 어느 하나라도 창에 들면 뺀다")
    void anyActionInsideTheWindowIsEnough() {
        List<PriceBar> bars = bars(20);
        List<LocalDate> actions = List.of(bars.get(3).tradeDate(), bars.get(15).tradeDate());

        var suppressed = UnadjustedActionGuard.suppressedDates(bars, actions, 3);

        assertThat(suppressed).containsExactly(
                bars.get(3).tradeDate(), bars.get(4).tradeDate(),
                bars.get(15).tradeDate(), bars.get(16).tradeDate());
    }

    private static List<PriceBar> bars(int count) {
        return java.util.stream.IntStream.range(0, count)
                .mapToObj(i -> bar(START.plusDays(i)))
                .toList();
    }

    private static PriceBar bar(LocalDate tradeDate) {
        BigDecimal close = new BigDecimal("1000");
        return new PriceBar(tradeDate, close, close, close, close, close, 1000L, null, null);
    }
}
