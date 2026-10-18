package com.stockscreener.pipeline.collector;

import com.stockscreener.pipeline.datasource.StoredUsRows;
import com.stockscreener.pipeline.datasource.us.UsMarket;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.List;

/** {@link StoredUsRows}를 {@code stock}에서 읽는다. */
@Component
public class JdbcStoredUsRows implements StoredUsRows {

    private final JdbcTemplate jdbc;
    private final PriceObservationWindow window;

    public JdbcStoredUsRows(JdbcTemplate jdbc, PriceObservationWindow window) {
        this.jdbc = jdbc;
        this.window = window;
    }

    @Override
    public List<Row> rows() {
        return jdbc.query("""
                SELECT id, ticker, name, alpaca_anchor_symbol, alpaca_anchor_date, sec_cik, era_from, era_to FROM stock
                WHERE market_code = 'US' AND alpaca_anchor_symbol IS NOT NULL
                -- 시대 무관: 과거 날짜 순위의 행 재료 — 고르기는 기준일마다 시대 술어(주식수 판독)와 범주 이력이 한다
                ORDER BY id
                """, (rs, i) -> {
            String anchorSymbol = rs.getString("alpaca_anchor_symbol");
            LocalDate anchorDate = rs.getObject("alpaca_anchor_date", LocalDate.class);
            LocalDate eraFrom = rs.getObject("era_from", LocalDate.class);
            LocalDate boundary = window.boundaryOf(UsMarket.CODE, anchorSymbol, anchorDate).orElse(null);
            LocalDate floor = boundary == null ? eraFrom : eraFrom == null || boundary.isAfter(eraFrom) ? boundary : eraFrom;
            return new Row(rs.getLong("id"), rs.getString("ticker"), rs.getString("name"), anchorSymbol, anchorDate,
                    rs.getObject("sec_cik", Long.class), eraFrom, rs.getObject("era_to", LocalDate.class), floor);
        });
    }
}
