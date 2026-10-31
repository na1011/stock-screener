package com.stockscreener.pipeline.collector;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * 적재된 거래소 세션 달력에 묻는다(ADR-0091). {@link MarketIndexCalendar}의 거래소판이다.
 *
 * <p><b>IMPORTANT — 판정이 boolean이 아니다.</b> {@code false}는 「그날 닫혔다」와 「우리가
 * 모른다」를 합치는데, 그 합침이 정확히 이 축이 없애려는 것이다. 적재 창 밖과 달력이 없는
 * 거래소는 {@link SessionStatus#UNKNOWN}이고 <b>휴장이 아니다</b> — 「휴장」으로 접으면
 * 수집 실패가 정상으로 읽혀 영구히 침묵한다.
 *
 * <p>적재 창을 별도 표로 두지 않고 {@code min/max(session_date)}로 답하는 이유는 그 편이
 * <b>안전한 쪽으로 퇴화</b>하기 때문이다 — 부분 적재로 창이 좁아지면 판정불가가 늘 뿐 틀린
 * 답이 늘지 않는다. 다만 그것이 막는 것은 <b>양 끝이 잘리는 경우</b>뿐이다. 창 가운데가 비면
 * 빠진 날이 「휴장」으로 읽히므로, 그쪽은 {@link BarGapReader}의 반대 방향 판정이 잡는다.
 */
@Component
public class MarketSessionCalendar {

    /** 그날 그 거래소가 열렸는가. 세 값이며 <b>모른다가 값이다.</b> */
    public enum SessionStatus {
        /** 정규장이 열렸다. */
        SESSION,
        /** 적재 창 안인데 달력에 없다 = 휴장. */
        CLOSED,
        /** 적재 창 밖이거나 그 거래소의 달력이 없다. <b>휴장이 아니다.</b> */
        UNKNOWN
    }

    private final JdbcTemplate jdbcTemplate;

    public MarketSessionCalendar(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 그 거래소의 그날 상태.
     *
     * <p>{@code exchangeCode}가 {@code null}이면 {@link SessionStatus#UNKNOWN}이다 —
     * 국내 종목의 {@code listing_exchange}가 그 값이고, 국내에는 달력 소스가 없다.
     */
    @Transactional(readOnly = true)
    public SessionStatus statusOf(String exchangeCode, LocalDate date) {
        if (exchangeCode == null || date == null) {
            return SessionStatus.UNKNOWN;
        }
        Optional<LocalDate> lo = earliestSession(exchangeCode);
        Optional<LocalDate> hi = latestSession(exchangeCode);
        if (lo.isEmpty() || hi.isEmpty()
                || date.isBefore(lo.get()) || date.isAfter(hi.get())) {
            return SessionStatus.UNKNOWN;
        }
        Long count = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM market_calendar WHERE exchange_code = ? AND session_date = ?",
                Long.class, exchangeCode, date);
        return count != null && count > 0 ? SessionStatus.SESSION : SessionStatus.CLOSED;
    }

    /** 그 거래소가 {@code [from, to]}에서 정규장을 연 날. 적재 창 밖은 그냥 없다. */
    @Transactional(readOnly = true)
    public List<LocalDate> sessionsBetween(String exchangeCode, LocalDate from, LocalDate to) {
        return jdbcTemplate.queryForList("""
                SELECT session_date FROM market_calendar
                 WHERE exchange_code = ? AND session_date BETWEEN ? AND ?
                 ORDER BY session_date
                """, LocalDate.class, exchangeCode, from, to);
    }

    /** 그 거래소에 적재된 가장 이른 세션. 하나도 없으면 비어 있음. */
    @Transactional(readOnly = true)
    public Optional<LocalDate> earliestSession(String exchangeCode) {
        return boundary("min", exchangeCode);
    }

    /** 그 거래소에 적재된 가장 늦은 세션. 하나도 없으면 비어 있음. */
    @Transactional(readOnly = true)
    public Optional<LocalDate> latestSession(String exchangeCode) {
        return boundary("max", exchangeCode);
    }

    /**
     * 정규장이 {@code cutoff} 이전에 끝난 가장 늦은 세션. 하나도 없으면 비어 있음.
     *
     * <p>{@code core_end}는 절대 시각이라 시간대 환산 없이 비교한다 — 조기폐장일은 앞당겨진 종료가 그대로 쓰인다.
     */
    @Transactional(readOnly = true)
    public Optional<LocalDate> latestSessionEndedBy(String exchangeCode, java.time.Instant cutoff) {
        List<LocalDate> rows = jdbcTemplate.queryForList(
                "SELECT max(session_date) FROM market_calendar WHERE exchange_code = ? AND core_end <= ?",
                LocalDate.class, exchangeCode, java.sql.Timestamp.from(cutoff));
        return rows.isEmpty() ? Optional.empty() : Optional.ofNullable(rows.get(0));
    }

    private Optional<LocalDate> boundary(String aggregate, String exchangeCode) {
        List<LocalDate> rows = jdbcTemplate.queryForList(
                "SELECT " + aggregate + "(session_date) FROM market_calendar WHERE exchange_code = ?",
                LocalDate.class, exchangeCode);
        return rows.isEmpty() ? Optional.empty() : Optional.ofNullable(rows.get(0));
    }
}
