package com.stockscreener.pipeline.datasource;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code price_daily}의 CHECK 제약을 삽입 <b>전에</b> 걸러내는지 검증한다.
 *
 * <p>DB 제약에 맡기면 손상된 봉 하나가 그 종목의 정상 봉까지 트랜잭션과 함께 날린다.
 * 무료 API의 손상된 데이터는 드물지 않으므로 미리 거른다.
 */
class RawPriceBarTest {

    @Test
    @DisplayName("정상 봉을 통과시킨다")
    void acceptsValidBar() {
        assertThat(bar("100", "110", "95", "105", 1000).isValid()).isTrue();
    }

    @Test
    @DisplayName("고가가 저가보다 낮으면 거른다")
    void rejectsHighBelowLow() {
        assertThat(bar("100", "90", "95", "92", 1000).isValid()).isFalse();
    }

    @Test
    @DisplayName("종가가 고가를 넘으면 거른다 — 통과하면 ATR·지지저항이 조용히 망가진다")
    void rejectsCloseAboveHigh() {
        assertThat(bar("100", "110", "95", "115", 1000).isValid()).isFalse();
    }

    @Test
    @DisplayName("시가가 저가보다 낮으면 거른다")
    void rejectsOpenBelowLow() {
        assertThat(bar("90", "110", "95", "105", 1000).isValid()).isFalse();
    }

    @Test
    @DisplayName("거래량이 음수면 거른다")
    void rejectsNegativeVolume() {
        assertThat(bar("100", "110", "95", "105", -1).isValid()).isFalse();
    }

    @Test
    @DisplayName("고가·저가·시가·종가가 모두 같은 봉을 허용한다 — 상한가 직행이 실제로 있다")
    void acceptsFlatBar() {
        assertThat(bar("100", "100", "100", "100", 0).isValid()).isTrue();
    }

    @Test
    @DisplayName("거래대금은 없어도 된다 — 합성하지 않고 null로 둔다")
    void allowsNullTradeValue() {
        RawPriceBar priceBar = new RawPriceBar(
                LocalDate.of(2026, 8, 5),
                new BigDecimal("100"), new BigDecimal("110"),
                new BigDecimal("95"), new BigDecimal("105"),
                1000, null, null, false);

        assertThat(priceBar.tradeValue()).isNull();
        assertThat(priceBar.isValid()).isTrue();
    }

    private static RawPriceBar bar(String open, String high, String low, String close, long volume) {
        return new RawPriceBar(
                LocalDate.of(2026, 8, 5),
                new BigDecimal(open), new BigDecimal(high),
                new BigDecimal(low), new BigDecimal(close),
                volume, null, null, false);
    }
}
