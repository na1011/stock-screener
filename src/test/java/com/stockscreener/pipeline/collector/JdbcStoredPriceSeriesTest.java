package com.stockscreener.pipeline.collector;

import com.stockscreener.pipeline.datasource.RawPriceBar;
import com.stockscreener.pipeline.datasource.StoredPriceSeries;
import com.stockscreener.support.JpaSliceTest;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 읽기 창의 <b>경계</b>를 잰다.
 *
 * <p>어댑터 테스트는 이 포트를 스텁으로 채우므로, 스텁을 만든 사람이 고른 창이 언제나
 * 맞아떨어져 <b>경계 버그가 원리적으로 안 보인다.</b> 그래서 여기가 유일한 자리다.
 */
@JpaSliceTest
@Import({JdbcStoredPriceSeries.class, PriceWriter.class, PriceObservationWindow.class})
class JdbcStoredPriceSeriesTest {

    @Autowired
    private JdbcStoredPriceSeries series;

    @Autowired
    private PriceWriter writer;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private long stockId;

    @BeforeEach
    void insertStock() {
        stockId = jdbcTemplate.queryForObject("""
                INSERT INTO stock (market_code, ticker, name) VALUES ('KOSPI', '001440', '대한전선')
                RETURNING id
                """, Long.class);
    }

    @Test
    @DisplayName("양끝을 포함하고 거래일 오름차순으로 준다")
    void windowIsInclusiveAndOrdered() {
        writer.write(stockId, List.of(
                bar("2023-04-26", "1497", 1_244_473_009L),
                bar("2023-04-27", "1492", 1_244_473_009L),
                bar("2023-05-16", "15860", 124_447_300L),
                bar("2023-05-17", "15180", 124_447_300L)));

        List<StoredPriceSeries.Bar> bars =
                series.between(stockId, LocalDate.parse("2023-04-27"), LocalDate.parse("2023-05-16"));

        assertThat(bars).extracting(StoredPriceSeries.Bar::tradeDate)
                .containsExactly(LocalDate.parse("2023-04-27"), LocalDate.parse("2023-05-16"));
    }

    @Test
    @DisplayName("거래정지 구간을 달력으로 세지 않는다 — 이웃한 두 원소가 곧 이웃한 두 거래일이다")
    void suspensionGapIsSkippedNotFilled() {
        // 대한전선 2023은 병합 전 11거래일이 비어 있다(달력 19일). 이웃 관계가 이 계약이라
        // 효력일 탐색이 그 구간을 자연히 건너뛴다.
        writer.write(stockId, List.of(
                bar("2023-04-27", "1492", 1_244_473_009L),
                bar("2023-05-16", "15860", 124_447_300L)));

        List<StoredPriceSeries.Bar> bars =
                series.between(stockId, LocalDate.parse("2023-04-01"), LocalDate.parse("2023-05-31"));

        assertThat(bars).hasSize(2);
        assertThat(bars.get(0).listedShares()).isEqualByComparingTo("1244473009");
        assertThat(bars.get(1).listedShares()).isEqualByComparingTo("124447300");
    }

    @Test
    @DisplayName("상장주식수가 없는 옛 행은 null로 나온다 — 0으로 채우면 배수가 0이나 무한대가 된다")
    void missingListedSharesStaysNull() {
        jdbcTemplate.update("""
                INSERT INTO price_daily
                    (stock_id, trade_date, open, high, low, close, adj_close, volume)
                VALUES (?, ?, 100, 100, 100, 100, 100, 1)
                """, stockId, LocalDate.parse("2020-01-02"));

        assertThat(series.between(stockId, LocalDate.parse("2020-01-01"), LocalDate.parse("2020-01-31")))
                .singleElement()
                .extracting(StoredPriceSeries.Bar::listedShares)
                .isNull();
    }

    @Test
    @DisplayName("봉이 없는 구간은 빈 목록이다 — 상장 전·거래정지에서 정상이다")
    void emptyWindowIsNotAnError() {
        assertThat(series.between(stockId, LocalDate.parse("2019-01-01"), LocalDate.parse("2019-12-31")))
                .isEmpty();
    }

    private static RawPriceBar bar(String date, String close, long listedShares) {
        BigDecimal c = new BigDecimal(close);
        return new RawPriceBar(LocalDate.parse(date), c, c, c, c, 1_000_000L, null,
                BigDecimal.valueOf(listedShares), false);
    }
}
