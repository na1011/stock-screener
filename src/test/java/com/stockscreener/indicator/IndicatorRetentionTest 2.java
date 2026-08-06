package com.stockscreener.indicator;

import com.stockscreener.support.TestDatabaseGuard;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 지표 보존 정책(ADR-0011)을 검증한다.
 *
 * <p>가장 중요한 것은 <b>원자료를 지우지 않는다</b>는 것이다. 지표는 원자료에서 재생성되지만
 * 그 역은 불가능하고, 외부 API가 과거 구간을 계속 제공한다는 보장도 없다.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
@Import(TestDatabaseGuard.class)
class IndicatorRetentionTest {

    private static final LocalDate AS_OF = LocalDate.of(2026, 8, 5);

    @Autowired
    private IndicatorRetentionPolicy retentionPolicy;

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
    @DisplayName("보존 기간을 넘긴 지표만 지운다")
    void purgesOnlyBeyondRetention() {
        int total = IndicatorRetentionPolicy.RETAINED_TRADING_DAYS + 20;
        for (int i = 0; i < total; i++) {
            insertIndicatorValue(AS_OF.minusDays(i));
        }

        int deleted = retentionPolicy.purge();

        assertThat(deleted).isPositive();
        assertThat(countIndicatorValues())
                .as("최근 %d거래일만 남는다", IndicatorRetentionPolicy.RETAINED_TRADING_DAYS)
                .isEqualTo(IndicatorRetentionPolicy.RETAINED_TRADING_DAYS);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT max(trade_date) FROM indicator_value", LocalDate.class))
                .as("최신 일자는 반드시 남아야 한다 — 스크리닝이 그것만 읽는다")
                .isEqualTo(AS_OF);
    }

    @Test
    @DisplayName("보존 기간보다 적게 쌓여 있으면 아무것도 지우지 않는다")
    void purgesNothingWhenWithinRetention() {
        for (int i = 0; i < 10; i++) {
            insertIndicatorValue(AS_OF.minusDays(i));
        }

        assertThat(retentionPolicy.purge()).isZero();
        assertThat(countIndicatorValues()).isEqualTo(10);
    }

    @Test
    @DisplayName("원자료는 지우지 않는다 — 지표는 재생성되지만 원자료는 되돌릴 수 없다")
    void neverPurgesRawData() {
        int total = IndicatorRetentionPolicy.RETAINED_TRADING_DAYS + 20;
        for (int i = 0; i < total; i++) {
            LocalDate date = AS_OF.minusDays(i);
            insertIndicatorValue(date);
            jdbcTemplate.update("""
                    INSERT INTO price_daily
                        (stock_id, trade_date, open, high, low, close, adj_close, adj_factor, volume)
                    VALUES (?, ?, 1000, 1000, 1000, 1000, 1000, 1, 1000)
                    """, stockId, date);
        }

        retentionPolicy.purge();

        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM price_daily WHERE stock_id = ?", Integer.class, stockId))
                .as("시세는 한 행도 지워지지 않는다")
                .isEqualTo(total);
    }

    private void insertIndicatorValue(LocalDate date) {
        jdbcTemplate.update("""
                INSERT INTO indicator_value (stock_id, trade_date, indicator_code, value)
                VALUES (?, ?, 'SMA_20', ?)
                """, stockId, date, BigDecimal.TEN);
    }

    private int countIndicatorValues() {
        return jdbcTemplate.queryForObject(
                "SELECT count(*) FROM indicator_value WHERE stock_id = ?", Integer.class, stockId);
    }
}
