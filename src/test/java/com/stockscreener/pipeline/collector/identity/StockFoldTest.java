package com.stockscreener.pipeline.collector.identity;

import com.stockscreener.pipeline.collector.FinancialWriter;
import com.stockscreener.pipeline.collector.NoOpIndicatorInvalidationPort;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 두 행 접기(ADR-0180 · 계획 v3.1 「옮기기 규칙」). 옛 행 T가 실체의 이력을 들고, 새 행 S(개명된 실체가 새로 등록된 것)의 자식은 옮기거나
 * 같은 사건이면 버린다. 값이 다르면 그 짝을 멈춘다.
 */
@JpaSliceTest
@Import({StockFold.class, FinancialWriter.class, NoOpIndicatorInvalidationPort.DefaultInvalidationPortConfig.class})
class StockFoldTest {

    private static final LocalDate RENAMED = LocalDate.of(2026, 9, 2);

    @Autowired
    private StockFold fold;

    @Autowired
    private JdbcTemplate jdbc;

    private long t;
    private long s;

    @BeforeEach
    void rows() {
        t = jdbc.queryForObject("INSERT INTO stock (market_code, ticker, name) VALUES ('US', 'HLXT', 'Helix') RETURNING id", Long.class);
        s = jdbc.queryForObject("INSERT INTO stock (market_code, ticker, name, listing_exchange) VALUES ('US', 'HOST', 'Helix Offshore', 'NYSE') RETURNING id",
                Long.class);
    }

    @Test
    @DisplayName("같은 접수번호는 값이 같으면 버리고, S에만 있는 접수번호는 옮겨 공시일 순으로 다시 매긴다 · 최신은 라벨마다 하나")
    void financialsMoveAndRenumber() {
        fs(t, "0001-26-000001", LocalDate.of(2026, 2, 20), 0, true, "100");
        fs(s, "0001-26-000001", LocalDate.of(2026, 2, 20), 0, false, "100");
        fs(s, "0001-26-000002", LocalDate.of(2026, 4, 20), 1, true, "110");

        StockFold.Plan plan = fold.plan(t, s);
        assertThat(plan.tables().get("financial_statement")).isEqualTo(new StockFold.Tally(1, 1, 0));

        fold.apply(t, s, RENAMED);

        assertThat(jdbc.queryForList("""
                SELECT split_part(report_code, ':', 2) || '|' || revision_no || '|' || is_latest FROM financial_statement
                WHERE stock_id = ? ORDER BY published_at""", String.class, t))
                .containsExactly("0001-26-000001|0|false", "0001-26-000002|1|true");
        assertThat(count("SELECT count(*) FROM stock WHERE id = ?", s)).as("S 행은 지운다").isZero();
        assertThat(jdbc.queryForObject("SELECT ticker FROM stock WHERE id = ?", String.class, t)).isEqualTo("HOST");
        assertThat(jdbc.queryForObject("SELECT symbol || '|' || valid_to FROM stock_symbol_era WHERE stock_id = ?", String.class, t))
                .isEqualTo("HLXT|" + RENAMED);
    }

    @Test
    @DisplayName("같은 접수번호인데 값이 다르면 그 짝을 멈춘다 — 접지 않는다")
    void differingFinancialsBlock() {
        fs(t, "0001-26-000001", LocalDate.of(2026, 2, 20), 0, true, "100");
        fs(s, "0001-26-000001", LocalDate.of(2026, 2, 20), 0, true, "999");

        assertThat(fold.plan(t, s).blocked()).isTrue();
        assertThatThrownBy(() -> fold.apply(t, s, RENAMED)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("US_LISTED 이음 — T가 그날 닫히고 S가 그날 열렸으면 T 하나로 잇는다 · 관측 편입은 옮긴다")
    void listedSeamJoinsAndObservedMoves() {
        LocalDate seam = LocalDate.of(2026, 10, 17);
        jdbc.update("INSERT INTO universe_membership (universe_code, stock_id, effective_from, effective_to) VALUES ('US_LISTED', ?, DATE '2026-08-29', ?)", t, seam);
        jdbc.update("INSERT INTO universe_membership (universe_code, stock_id, effective_from) VALUES ('US_LISTED', ?, ?)", s, seam);
        jdbc.update("INSERT INTO universe_membership (universe_code, stock_id, effective_from) VALUES ('US_TOP1000', ?, ?)", s, seam);

        StockFold.Plan plan = fold.plan(t, s);
        assertThat(plan.tables().get("universe_membership")).isEqualTo(new StockFold.Tally(1, 1, 0));
        fold.apply(t, s, RENAMED);

        assertThat(jdbc.queryForList("""
                SELECT universe_code || '|' || effective_from || '|' || coalesce(effective_to::text, '열림') FROM universe_membership
                WHERE stock_id = ? ORDER BY universe_code""", String.class, t))
                .containsExactly("US_LISTED|2026-08-29|열림", "US_TOP1000|2026-10-17|열림");
    }

    @Test
    @DisplayName("가격 — 같은 날 같은 봉은 버리고 다른 날은 옮긴다 · 같은 날 다른 봉이면 멈춘다")
    void pricesCompareOnOverlap() {
        bar(t, LocalDate.of(2026, 10, 15), "10");
        bar(s, LocalDate.of(2026, 10, 15), "10");
        bar(s, LocalDate.of(2026, 10, 16), "11");

        assertThat(fold.plan(t, s).tables().get("price_daily")).isEqualTo(new StockFold.Tally(1, 1, 0));

        bar(s, LocalDate.of(2026, 10, 14), "12");
        bar(t, LocalDate.of(2026, 10, 14), "13");
        assertThat(fold.plan(t, s).blocked()).isTrue();
    }

    @Test
    @DisplayName("규칙 없는 자식 표가 생기면 잡는다 — 손으로 든 목록이 새 표를 놓치지 않게")
    void uncoveredChildTableIsReported() {
        assertThat(fold.uncoveredChildTables()).isEmpty();
        jdbc.execute("CREATE TABLE fold_probe_child (stock_id bigint REFERENCES stock (id))");

        assertThat(fold.uncoveredChildTables()).containsExactly("fold_probe_child");
    }

    private void fs(long stockId, String accession, LocalDate published, int revision, boolean latest, String revenue) {
        jdbc.update("""
                INSERT INTO financial_statement (stock_id, fiscal_year, fiscal_period, period_start, period_end, published_at,
                                                 currency_code, report_code, revision_no, is_latest, revenue)
                VALUES (?, 2025, 'FY', DATE '2025-01-01', DATE '2025-12-31', ?, 'USD', ?, ?, ?, ?)""",
                stockId, published, "10-K:" + accession, revision, latest, new BigDecimal(revenue));
    }

    private void bar(long stockId, LocalDate date, String close) {
        jdbc.update("""
                INSERT INTO price_daily (stock_id, trade_date, open, high, low, close, adj_close, volume)
                VALUES (?, ?, ?, ?, ?, ?, ?, 100)""", stockId, date, new BigDecimal(close), new BigDecimal(close),
                new BigDecimal(close), new BigDecimal(close), new BigDecimal(close));
    }

    private int count(String sql, Object... args) {
        return jdbc.queryForObject(sql, Integer.class, args);
    }
}
