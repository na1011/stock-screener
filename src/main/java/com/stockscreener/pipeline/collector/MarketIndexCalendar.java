package com.stockscreener.pipeline.collector;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * 지수가 실제로 적재된 거래일에 묻는다. {@link PriceCalendar}의 지수판이다.
 *
 * <p>IMPORTANT — 이 클래스가 있는 이유는 <b>결손 알림이 "이 실행이 쓴 행 수"로는 원리적으로
 * 발화하지 않기 때문</b>이다. 수집이 하루가 아니라 15일 창으로 돌므로(ADR-0042), 대상일이
 * 비어 있어도 창 안 과거 날짜의 재적재가 쓰기 행 수를 채운다. 물어야 하는 것은 "이 실행이
 * 무언가를 받았는가"가 아니라 <b>"대상일에 지수가 있는가"</b>다.
 *
 * <p>그것을 놓치면 이렇게 된다 — 지수의 공개 지연이 시세보다 하루라도 늦으면 시세와 기존 지표는
 * 대상일 T에 쓰이는데 지수만 없어 그날 {@code EXCESS_RETURN_26W}가 한 행도 안 써진다.
 * {@code IndicatorCalendar}는 {@code max(trade_date)}로 T를 기준일로 고르므로 그 지표를 조건에
 * 건 화면은 <b>매일 전 종목 0건</b>이 된다. 수집은 SUCCESS이고 알림은 없다.
 */
@Component
public class MarketIndexCalendar {

    private final JdbcTemplate jdbcTemplate;

    public MarketIndexCalendar(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** 그 지수의 그 일자에 적재된 행 수. 0이면 대상일이 비어 있다. */
    @Transactional(readOnly = true)
    public long countOn(String indexCode, LocalDate tradeDate) {
        Long count = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM market_index_daily WHERE index_code = ? AND trade_date = ?",
                Long.class, indexCode, tradeDate);
        return count == null ? 0L : count;
    }

    /** 그 지수에 적재된 가장 이른 거래일. 하나도 없으면 비어 있음. */
    @Transactional(readOnly = true)
    public Optional<LocalDate> earliestTradeDate(String indexCode) {
        List<LocalDate> rows = jdbcTemplate.queryForList(
                "SELECT min(trade_date) FROM market_index_daily WHERE index_code = ?",
                LocalDate.class, indexCode);
        return rows.isEmpty() ? Optional.empty() : Optional.ofNullable(rows.get(0));
    }

    /** 그 지수에 적재된 가장 늦은 거래일. 하나도 없으면 비어 있음. */
    @Transactional(readOnly = true)
    public Optional<LocalDate> latestTradeDate(String indexCode) {
        List<LocalDate> rows = jdbcTemplate.queryForList(
                "SELECT max(trade_date) FROM market_index_daily WHERE index_code = ?",
                LocalDate.class, indexCode);
        return rows.isEmpty() ? Optional.empty() : Optional.ofNullable(rows.get(0));
    }
}
