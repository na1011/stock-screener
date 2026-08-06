package com.stockscreener.indicator;

import com.stockscreener.pipeline.collector.IndicatorInvalidationPort;
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

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 원자료 소급 변경 시 지표 무효화를 검증한다.
 *
 * <p>이 이음매가 없으면 액면분할·정정공시 이후 <b>틀린 지표가 그럴듯한 모습으로 남는다.</b>
 * 그 오류는 분할이 실제로 일어나기 전까지 드러나지 않으므로 구조로 막아야 한다.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
@Import(TestDatabaseGuard.class)
class IndicatorInvalidationTest {

    private static final LocalDate BASE = LocalDate.of(2026, 8, 5);

    @Autowired
    private IndicatorInvalidationPort invalidationPort;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private long stockId;

    @BeforeEach
    void insertStockAndIndicatorValues() {
        stockId = jdbcTemplate.queryForObject("""
                INSERT INTO stock (market_code, ticker, name) VALUES ('KOSPI', '005930', '삼성전자')
                RETURNING id
                """, Long.class);
        for (int i = 0; i < 5; i++) {
            jdbcTemplate.update("""
                    INSERT INTO indicator_value (stock_id, trade_date, indicator_code, value)
                    VALUES (?, ?, ?, 100)
                    """, stockId, BASE.minusDays(i), "SMA_20");
        }
    }

    @Test
    @DisplayName("Phase 3 구현이 No-Op을 대체한다 — 실제로 지우는 구현이 등록되어야 한다")
    void realImplementationReplacesNoOp() {
        assertThat(invalidationPort).isInstanceOf(StoredIndicatorInvalidationPort.class);
    }

    @Test
    @DisplayName("기준일 이후의 지표를 지운다 — 그날 하루가 아니라 이후 전체다")
    void deletesFromDateOnward() {
        invalidationPort.invalidate(stockId, BASE.minusDays(2),
                IndicatorInvalidationPort.InvalidationReason.PRICE_ADJUSTED);

        assertThat(remainingDates())
                .as("기준일 포함 이후 3일이 지워지고 그 이전 2일만 남는다")
                .containsExactlyInAnyOrder(BASE.minusDays(3), BASE.minusDays(4));
    }

    @Test
    @DisplayName("다른 종목의 지표는 건드리지 않는다")
    void doesNotTouchOtherStocks() {
        long other = jdbcTemplate.queryForObject("""
                INSERT INTO stock (market_code, ticker, name) VALUES ('KOSPI', '000660', 'SK하이닉스')
                RETURNING id
                """, Long.class);
        jdbcTemplate.update("""
                INSERT INTO indicator_value (stock_id, trade_date, indicator_code, value)
                VALUES (?, ?, 'SMA_20', 100)
                """, other, BASE);

        invalidationPort.invalidate(stockId, BASE.minusDays(10),
                IndicatorInvalidationPort.InvalidationReason.PRICE_ADJUSTED);

        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM indicator_value WHERE stock_id = ?", Integer.class, other))
                .isEqualTo(1);
    }

    private java.util.List<LocalDate> remainingDates() {
        return jdbcTemplate.queryForList(
                "SELECT trade_date FROM indicator_value WHERE stock_id = ?",
                LocalDate.class, stockId);
    }
}
