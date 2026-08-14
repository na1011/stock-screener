package com.stockscreener.stock.web;

import com.stockscreener.stock.StockReader;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 전일 대비 등락의 완료 판정.
 *
 * <p>DB 없이 잰다 — 판정이 전부 두 행과 효력일 목록만으로 결정되기 때문이다. 실데이터로
 * 재려 하면 미보정 사건이 있는 종목이 다섯뿐이고 그중 보존창 안에 대조군이 남는 것이 없어
 * <b>규칙이 맞아도 틀려도 화면이 같다</b>(같은 이유가 {@code UnadjustedActionGuardTest}에도 있다).
 */
class StockDetailPriceTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 8, 14);
    private static final LocalDate YESTERDAY = LocalDate.of(2026, 8, 13);

    /** @param close 원주가, @param adjClose 수정종가. 나머지는 이 판정과 무관하다. */
    private static StockReader.PriceRow row(LocalDate date, long close, long adjClose) {
        BigDecimal c = BigDecimal.valueOf(close);
        return new StockReader.PriceRow(date, c, c, c, c, BigDecimal.valueOf(adjClose),
                1_000L, null, null);
    }

    @Test
    @DisplayName("평상시에는 원주가 뺄셈과 같은 값이 나온다")
    void ordinaryDay() {
        StockDetailResponse.Price price = StockDetailResponse.Price.of(
                row(TODAY, 70_100, 70_100), row(YESTERDAY, 68_900, 68_900), List.of());

        assertThat(price.previousTradeDate()).isEqualTo(YESTERDAY);
        assertThat(price.change()).isEqualByComparingTo("1200.00");
        assertThat(price.changeRate()).isEqualByComparingTo("1.74");
    }

    @Test
    @DisplayName("보정된 액면분할 당일은 −50%가 아니다 — 원주가끼리 빼지 않는다는 것이 이 값이다")
    void adjustedSplitDoesNotShowFiftyPercentDrop() {
        // 2:1 분할. 원주가는 반토막인데 수정종가는 같은 축척이라 등락이 0이다.
        StockDetailResponse.Price price = StockDetailResponse.Price.of(
                row(TODAY, 35_000, 35_000), row(YESTERDAY, 70_000, 35_000), List.of());

        assertThat(price.change()).isEqualByComparingTo("0.00");
        assertThat(price.changeRate()).isEqualByComparingTo("0.00");
    }

    @Test
    @DisplayName("미보정 사건이 두 거래일 사이에 있으면 셋 다 내지 않는다 — 007340이 −81.2%로 나오던 자리")
    void suppressedWhenUnadjustedActionFallsBetween() {
        LocalDate effective = LocalDate.of(2024, 10, 8);
        // 계수를 못 만든 종목은 adj_close가 close와 같다(PriceWriter). 그래서 수정종가의 비도
        // 원주가 뺄셈과 같아지고, 가드가 없으면 102,100 → 19,200이 사실처럼 나간다.
        StockDetailResponse.Price price = StockDetailResponse.Price.of(
                row(effective, 19_200, 19_200),
                row(LocalDate.of(2024, 9, 24), 102_100, 102_100),
                List.of(effective));

        assertThat(price.previousTradeDate()).isNull();
        assertThat(price.change()).isNull();
        assertThat(price.changeRate()).isNull();
        // 시세 자체는 그대로 나간다 — 등락만 빠진다.
        assertThat(price.close()).isEqualByComparingTo("19200");
    }

    @Test
    @DisplayName("효력일이 직전 거래일과 같은 날이면 억제하지 않는다 — 두 봉이 같은 축척이다")
    void effectiveDateOnWindowStartIsNotSuppressed() {
        StockDetailResponse.Price price = StockDetailResponse.Price.of(
                row(TODAY, 70_100, 70_100), row(YESTERDAY, 68_900, 68_900), List.of(YESTERDAY));

        assertThat(price.changeRate()).isEqualByComparingTo("1.74");
    }

    @Test
    @DisplayName("몫이 무한소수여도 던지지 않는다 — MathContext가 없으면 여기서 500이 난다")
    void nonTerminatingQuotientDoesNotThrow() {
        StockDetailResponse.Price price = StockDetailResponse.Price.of(
                row(TODAY, 10_000, 10_000), row(YESTERDAY, 30_000, 30_000), List.of());

        assertThat(price.changeRate()).isEqualByComparingTo("-66.67");
        assertThat(price.change()).isEqualByComparingTo("-20000.00");
    }

    @Test
    @DisplayName("직전 거래일이 인접하지 않으면 그 날짜를 함께 낸다 — 화면이 라벨을 정할 수 있어야 한다")
    void reportsActualPreviousTradeDate() {
        LocalDate weeksAgo = LocalDate.of(2026, 7, 20);

        StockDetailResponse.Price price = StockDetailResponse.Price.of(
                row(TODAY, 70_100, 70_100), row(weeksAgo, 68_900, 68_900), List.of());

        assertThat(price.previousTradeDate()).isEqualTo(weeksAgo);
    }

    @Test
    @DisplayName("전일 봉이 없으면 0%가 아니라 아무 값도 내지 않는다")
    void noPreviousBar() {
        StockDetailResponse.Price price = StockDetailResponse.Price.of(
                row(TODAY, 70_100, 70_100), null, List.of());

        assertThat(price.previousTradeDate()).isNull();
        assertThat(price.change()).isNull();
        assertThat(price.changeRate()).isNull();
    }

    @Test
    @DisplayName("분모가 0이면 어느 쪽이든 내지 않는다 — 비의 분모는 오늘, 등락률의 분모는 어제다")
    void zeroDenominatorOnEitherSide() {
        assertThat(StockDetailResponse.Price.of(
                row(TODAY, 70_100, 0), row(YESTERDAY, 68_900, 68_900), List.of()).changeRate())
                .isNull();

        assertThat(StockDetailResponse.Price.of(
                row(TODAY, 70_100, 70_100), row(YESTERDAY, 68_900, 0), List.of()).changeRate())
                .isNull();
    }
}
