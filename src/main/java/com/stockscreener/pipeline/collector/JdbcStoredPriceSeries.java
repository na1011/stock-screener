package com.stockscreener.pipeline.collector;

import com.stockscreener.pipeline.datasource.StoredPriceSeries;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;

/**
 * {@link StoredPriceSeries}를 {@code price_daily}에서 읽는다.
 *
 * <p>구현이 이 패키지에 있는 이유는 <b>DB를 만지는 클래스가 여기 모여 있기</b> 때문이다
 * ({@link PriceWriter}·{@link PriceCalendar}·{@link CorporateActionLedger}). 포트만 저쪽에
 * 두면 어댑터가 {@code JdbcTemplate}을 몰라도 되고, 어댑터 테스트가 DB 없이 성립한다.
 *
 * <p>{@code price_daily}의 기본키가 {@code (stock_id, trade_date)}(V1)이므로 구간 하나가
 * 인덱스 범위 스캔 하나로 끝난다.
 */
@Component
public class JdbcStoredPriceSeries implements StoredPriceSeries {

    private final JdbcTemplate jdbcTemplate;

    public JdbcStoredPriceSeries(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    @Transactional(readOnly = true)
    public List<Bar> between(long stockId, LocalDate from, LocalDate to) {
        return jdbcTemplate.query("""
                SELECT trade_date, close, listed_shares FROM price_daily
                WHERE stock_id = ? AND trade_date BETWEEN ? AND ?
                ORDER BY trade_date
                """,
                (rs, rowNum) -> new Bar(
                        rs.getObject("trade_date", LocalDate.class),
                        rs.getBigDecimal("close"),
                        rs.getBigDecimal("listed_shares")),
                stockId, from, to);
    }
}
