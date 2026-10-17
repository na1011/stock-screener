package com.stockscreener.pipeline.collector;

import com.stockscreener.pipeline.datasource.StoredUsRows;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.List;

/** {@link StoredUsRows}를 {@code stock}에서 읽는다. */
@Component
public class JdbcStoredUsRows implements StoredUsRows {

    private final JdbcTemplate jdbc;

    public JdbcStoredUsRows(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<Row> rows() {
        return jdbc.query("""
                SELECT id, ticker, name, alpaca_anchor_symbol, alpaca_anchor_date, sec_cik, era_from, era_to FROM stock
                WHERE market_code = 'US' AND alpaca_anchor_symbol IS NOT NULL
                -- 시대 무관: 과거 날짜 순위의 행 재료 — 고르기는 기준일마다 시대 술어(주식수 판독)와 범주 이력이 한다
                ORDER BY id
                """, (rs, i) -> new Row(rs.getLong("id"), rs.getString("ticker"), rs.getString("name"),
                rs.getString("alpaca_anchor_symbol"), rs.getObject("alpaca_anchor_date", LocalDate.class),
                rs.getObject("sec_cik", Long.class), rs.getObject("era_from", LocalDate.class),
                rs.getObject("era_to", LocalDate.class)));
    }
}
