package com.stockscreener.pipeline.batch;

import com.stockscreener.pipeline.datasource.IdentitySource;
import com.stockscreener.pipeline.datasource.us.AlpacaCorporateActionAdapter;
import com.stockscreener.support.JpaSliceTest;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 기호 시대 심기(ADR-0181) — 같은 증권 개명을 닻이 못 이은 행만 · 닻이 이미 잇는 행과 이미 심은 행은 건너뛴다. */
@JpaSliceTest
class SymbolEraSeedJobTest {

    private static final LocalDate ANCHOR = LocalDate.of(2026, 8, 28);
    private static final LocalDate RENAMED = LocalDate.of(2023, 4, 21);

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private SymbolEraSeedJob job;
    private AlpacaCorporateActionAdapter adapter;

    @BeforeEach
    void setUp() {
        BatchExecutions executions = mock(BatchExecutions.class);
        BatchExecutionContext context = mock(BatchExecutionContext.class);
        when(executions.start(any(), any(), any(), anyInt(), any())).thenReturn(context);
        when(context.finish(any())).thenReturn(BatchStatus.SUCCESS);
        adapter = mock(AlpacaCorporateActionAdapter.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<AlpacaCorporateActionAdapter> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(adapter);
        job = new SymbolEraSeedJob(provider, executions, jdbc, transactionManager);
    }

    @Test
    @DisplayName("첫 봉이 개명일인 행에 옛 기호 시대를 심는다 · 닻이 개명 앞을 이미 주는 행(대조군)과 봉 없는 행은 건너뛴다 · 두 번째 판은 0")
    void seedsOnlyRowsTheAnchorDidNotChain() throws Exception {
        long sphr = usRow("SPHR");
        bar(sphr, RENAMED);
        long hos = usRow("HOSX");
        bar(hos, LocalDate.of(2019, 9, 3));
        long candidate = usRow("NOBAR");
        when(adapter.fetchSameSecurityRenames(any(), any())).thenReturn(List.of(
                new IdentitySource.SymbolChange("MSGE", "SPHR", RENAMED),
                new IdentitySource.SymbolChange("HLXX", "HOSX", RENAMED),
                new IdentitySource.SymbolChange("OLDB", "NOBAR", RENAMED)));

        job.run(LocalDate.of(2026, 10, 19), BatchExecutionRecorder.TriggeredBy.MANUAL, true);

        assertThat(jdbc.queryForList("SELECT symbol || '~' || valid_to FROM stock_symbol_era WHERE stock_id = ?", String.class, sphr))
                .containsExactly("MSGE~" + RENAMED);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM stock_symbol_era WHERE stock_id = ?", Integer.class, hos)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM stock_symbol_era WHERE stock_id = ?", Integer.class, candidate))
                .as("봉 없는 행은 대상이 아니다").isZero();
        assertThat(job.plan(adapter.fetchSameSecurityRenames(null, null))).isEmpty();
    }

    @Test
    @DisplayName("계획만이면 아무것도 안 쓴다")
    void planOnlyWritesNothing() throws Exception {
        long sphr = usRow("SPHR");
        bar(sphr, RENAMED);
        when(adapter.fetchSameSecurityRenames(any(), any())).thenReturn(List.of(new IdentitySource.SymbolChange("MSGE", "SPHR", RENAMED)));

        job.run(LocalDate.of(2026, 10, 19), BatchExecutionRecorder.TriggeredBy.MANUAL, false);

        assertThat(jdbc.queryForObject("SELECT count(*) FROM stock_symbol_era", Integer.class)).isZero();
    }

    private long usRow(String ticker) {
        return jdbc.queryForObject("""
                INSERT INTO stock (market_code, ticker, name, alpaca_anchor_symbol, alpaca_anchor_date)
                VALUES ('US', ?, ?, ?, ?) RETURNING id""", Long.class, ticker, ticker, ticker, ANCHOR);
    }

    private void bar(long stockId, LocalDate date) {
        BigDecimal c = BigDecimal.TEN;
        jdbc.update("""
                INSERT INTO price_daily (stock_id, trade_date, open, high, low, close, adj_close, volume)
                VALUES (?, ?, ?, ?, ?, ?, ?, 100)""", stockId, date, c, c, c, c, c);
    }
}
