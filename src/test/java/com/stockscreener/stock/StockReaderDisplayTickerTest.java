package com.stockscreener.stock;

import com.stockscreener.support.TestDatabaseGuard;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 결과 표의 표지 — 시대가 닫힌 미국 행(비운 표지 {@code T~마지막 날} · ADR-0180)은 과거 기준일 명부에 들고(D4 · D6), 화면에는 그 행이 마지막으로
 * 쓴 기호로 낸다(탐색 {@code US-PAST-LISTING-PLAN-1017.md} P0-e). 열린 행 · 국내 행은 그대로다.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
@Import(TestDatabaseGuard.class)
class StockReaderDisplayTickerTest {

    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private StockReader reader;

    @Test
    void closedRowShowsItsLastSymbolAndOpenRowsAreUnchanged() {
        long closed = row("EAXT~2026-08-04");
        long open = row("EAXU");

        var rows = reader.findRows(List.of(closed, open), LocalDate.of(2026, 3, 2));

        assertThat(rows.get(closed).ticker()).isEqualTo("EAXT");
        assertThat(rows.get(open).ticker()).isEqualTo("EAXU");
    }

    private long row(String ticker) {
        return jdbc.queryForObject("INSERT INTO stock (market_code, ticker, name) VALUES ('US', ?, 'x') RETURNING id", Long.class, ticker);
    }
}
