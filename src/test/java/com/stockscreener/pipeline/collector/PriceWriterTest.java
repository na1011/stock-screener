package com.stockscreener.pipeline.collector;

import com.stockscreener.pipeline.datasource.RawPriceBar;
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
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 시세 저장의 멱등성과 손상 데이터 처리를 실제 PostgreSQL에서 검증한다.
 *
 * <p>{@code ck_price_ohlc}는 DB 제약이므로 실제 DB 없이는 검증할 수 없다.
 */
@JpaSliceTest
@Import(PriceWriter.class)
class PriceWriterTest {

    @Autowired
    private PriceWriter writer;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private long stockId;

    @BeforeEach
    void insertStock() {
        stockId = jdbcTemplate.queryForObject("""
                INSERT INTO stock (market_code, ticker, name) VALUES ('KOSPI', '005930', '삼성전자')
                RETURNING id
                """, Long.class);
    }

    @Test
    @DisplayName("시세를 저장하고 adj_close를 close와 같게 둔다 — 신규 저장 시 adj_factor는 1이다")
    void writesBarsWithUnitAdjustmentFactor() {
        long written = writer.write(stockId, List.of(
                bar("2026-08-03", "100", "110", "95", "105"),
                bar("2026-08-04", "105", "115", "100", "112")));

        assertThat(written).isEqualTo(2);
        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT * FROM price_daily WHERE stock_id = ? AND trade_date = ?",
                stockId, LocalDate.parse("2026-08-03"));
        assertThat((BigDecimal) row.get("close")).isEqualByComparingTo("105");
        assertThat((BigDecimal) row.get("adj_close")).isEqualByComparingTo("105");
        assertThat((BigDecimal) row.get("adj_factor")).isEqualByComparingTo("1");
    }

    @Test
    @DisplayName("같은 배치를 두 번 실행해도 행이 늘지 않는다 — 배치는 멱등해야 한다")
    void isIdempotent() {
        List<RawPriceBar> bars = List.of(
                bar("2026-08-03", "100", "110", "95", "105"),
                bar("2026-08-04", "105", "115", "100", "112"));

        writer.write(stockId, bars);
        writer.write(stockId, bars);

        assertThat(rowCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("재수집 시 값이 갱신된다 — 소스가 뒤늦게 정정한 값이 반영되어야 한다")
    void updatesOnConflict() {
        writer.write(stockId, List.of(bar("2026-08-03", "100", "110", "95", "105")));
        writer.write(stockId, List.of(bar("2026-08-03", "100", "120", "95", "118")));

        assertThat(rowCount()).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT close FROM price_daily WHERE stock_id = ?", BigDecimal.class, stockId))
                .isEqualByComparingTo("118");
    }

    @Test
    @DisplayName("OHLC가 깨진 봉은 건너뛰고 나머지는 저장한다 — 한 봉 때문에 전체를 잃지 않는다")
    void skipsCorruptedBarsButKeepsTheRest() {
        long written = writer.write(stockId, List.of(
                bar("2026-08-03", "100", "110", "95", "105"),
                bar("2026-08-04", "100", "90", "95", "92"),   // high < low — 손상
                bar("2026-08-05", "105", "115", "100", "112")));

        assertThat(written).isEqualTo(2);
        assertThat(rowCount()).isEqualTo(2);
        assertThat(jdbcTemplate.queryForList(
                "SELECT trade_date FROM price_daily WHERE stock_id = ? ORDER BY trade_date",
                LocalDate.class, stockId))
                .containsExactly(LocalDate.parse("2026-08-03"), LocalDate.parse("2026-08-05"));
    }

    @Test
    @DisplayName("거래대금이 없으면 NULL로 둔다 — close × volume으로 합성하지 않는다")
    void leavesTradeValueNullWhenAbsent() {
        writer.write(stockId, List.of(bar("2026-08-03", "100", "110", "95", "105")));

        assertThat(jdbcTemplate.queryForObject(
                "SELECT trade_value FROM price_daily WHERE stock_id = ?", BigDecimal.class, stockId))
                .isNull();
    }

    @Test
    @DisplayName("빈 목록은 아무것도 쓰지 않는다 — 휴장일에 호출될 수 있다")
    void handlesEmptyInput() {
        assertThat(writer.write(stockId, List.of())).isZero();
        assertThat(rowCount()).isZero();
    }

    private int rowCount() {
        return jdbcTemplate.queryForObject(
                "SELECT count(*) FROM price_daily WHERE stock_id = ?", Integer.class, stockId);
    }

    private static RawPriceBar bar(String date, String open, String high, String low, String close) {
        return new RawPriceBar(
                LocalDate.parse(date),
                new BigDecimal(open), new BigDecimal(high),
                new BigDecimal(low), new BigDecimal(close),
                1_000_000L, null, null, false);
    }
}
