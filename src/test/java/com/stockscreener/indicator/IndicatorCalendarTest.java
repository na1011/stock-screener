package com.stockscreener.indicator;

import com.stockscreener.support.JpaSliceTest;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 기준 일자 해석의 재료. {@code @DataJpaTest}가 롤백하므로 아래 {@code DELETE}는
 * 이 테스트 안에서만 유효하다 — 다른 테스트가 남긴 행에 결과가 흔들리지 않게 하려는 것이다.
 */
@JpaSliceTest
@Import(IndicatorCalendar.class)
class IndicatorCalendarTest {

    @Autowired
    private IndicatorCalendar calendar;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private long stockId;

    @BeforeEach
    void setUp() {
        jdbcTemplate.update("DELETE FROM indicator_value");
        jdbcTemplate.update("""
                INSERT INTO market (code, country_code, currency_code, timezone)
                VALUES ('TESTMKT','KR','KRW','Asia/Seoul') ON CONFLICT DO NOTHING
                """);
        jdbcTemplate.update("""
                INSERT INTO indicator (code, name, category, value_type)
                VALUES ('PER','PER','FUNDAMENTAL','RATIO') ON CONFLICT DO NOTHING
                """);
        stockId = jdbcTemplate.queryForObject("""
                INSERT INTO stock (market_code, ticker, name) VALUES ('TESTMKT','000001','테스트종목')
                RETURNING id
                """, Long.class);
    }

    @Test
    @DisplayName("IMPORTANT — 지표가 하나도 없으면 비어 있다. 오늘 날짜로 대신하지 않는다")
    void emptyWhenNoIndicatorsExist() {
        // 오늘을 돌려주면 호출자가 그 날짜로 조회해 전 종목 '판정 불가'를 받고,
        // 그것을 "조건에 맞는 종목이 없음"으로 읽는다. 비어 있음이 유일하게 정직한 답이다.
        assertThat(calendar.latestIndicatorDate()).isEmpty();
        assertThat(calendar.earliestIndicatorDate()).isEmpty();
        assertThat(calendar.nearestOnOrBefore(LocalDate.of(2026, 8, 6))).isEmpty();
    }

    @Test
    @DisplayName("최신·최소 거래일을 낸다")
    void reportsRange() {
        insert(LocalDate.of(2026, 3, 2));
        insert(LocalDate.of(2026, 8, 5));
        insert(LocalDate.of(2026, 6, 1));

        assertThat(calendar.latestIndicatorDate()).contains(LocalDate.of(2026, 8, 5));
        assertThat(calendar.earliestIndicatorDate()).contains(LocalDate.of(2026, 3, 2));
    }

    @Test
    @DisplayName("휴장일을 물으면 그 이하 가장 가까운 거래일로 내려간다")
    void fallsBackToNearestTradingDay() {
        insert(LocalDate.of(2026, 8, 5));   // 수
        insert(LocalDate.of(2026, 8, 7));   // 금

        // 8/8은 토요일이라 지표가 없다. 오류로 만들면 사용자가 거래일 달력을 알아야 한다.
        assertThat(calendar.nearestOnOrBefore(LocalDate.of(2026, 8, 8)))
                .contains(LocalDate.of(2026, 8, 7));
        assertThat(calendar.nearestOnOrBefore(LocalDate.of(2026, 8, 6)))
                .contains(LocalDate.of(2026, 8, 5));
    }

    @Test
    @DisplayName("보존 구간보다 이전을 물으면 비어 있다 — 호출자가 이것을 오류로 바꾼다")
    void emptyBeforeRetentionWindow() {
        insert(LocalDate.of(2026, 3, 2));

        assertThat(calendar.nearestOnOrBefore(LocalDate.of(2020, 1, 2))).isEmpty();
    }

    private void insert(LocalDate date) {
        jdbcTemplate.update("""
                INSERT INTO indicator_value (stock_id, trade_date, indicator_code, value)
                VALUES (?, ?, 'PER', ?)
                """, stockId, date, new BigDecimal("10"));
    }
}
