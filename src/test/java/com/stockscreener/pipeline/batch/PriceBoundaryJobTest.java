package com.stockscreener.pipeline.batch;

import com.stockscreener.pipeline.collector.PriceObservationWindow;
import com.stockscreener.support.JpaSliceTest;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 시세 경계 교정 잡(ADR-0181). 실제 경계 파일의 CAI · FLY 줄을 쓴다(닻 = 표지, 2026-08-28).
 */
@JpaSliceTest
@Import(PriceObservationWindow.class)
class PriceBoundaryJobTest {

    private static final LocalDate ANCHOR = LocalDate.of(2026, 8, 28);

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PriceObservationWindow window;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @TempDir
    Path exportDir;

    private PriceBoundaryJob job;
    private long cai;
    private long sn;
    private long fly;
    private long kr;

    @BeforeEach
    void rows() {
        BatchExecutions executions = mock(BatchExecutions.class);
        BatchExecutionContext context = mock(BatchExecutionContext.class);
        when(executions.start(any(), any(), any(), anyInt(), any())).thenReturn(context);
        when(context.finish(any())).thenReturn(BatchStatus.SUCCESS);
        job = new PriceBoundaryJob(window, executions, jdbc, transactionManager, exportDir);

        cai = usRow("CAI");
        bar(cai, "2021-11-22", "56", 100);
        bar(cai, "2025-06-18", "28", 15_347_884);
        jdbc.update("""
                INSERT INTO corporate_action (stock_id, effective_date, type, ratio, detection_source, status)
                VALUES (?, DATE '2020-04-02', 'SPLIT', 2, 'SOURCE_DISCLOSURE', 'APPLIED')""", cai);

        sn = usRow("SNZ");
        bar(sn, "2023-07-27", "0.3607", 0);
        bar(sn, "2023-07-28", "0.3607", 0);
        bar(sn, "2023-07-31", "30", 1_000);
        bar(sn, "2023-08-01", "30", 0);

        fly = usRow("FLY");
        bar(fly, "2021-06-15", "17", 100);
        jdbc.update("""
                INSERT INTO universe_membership (universe_code, stock_id, effective_from, effective_to)
                VALUES ('US_TOP1000', ?, DATE '2021-06-01', DATE '2021-07-01')""", fly);

        kr = jdbc.queryForObject("INSERT INTO stock (market_code, ticker, name) VALUES ('KOSDAQ', '950160', '코오롱티슈진') RETURNING id",
                Long.class);
        bar(kr, "2020-01-02", "8010", 0);
    }

    @Test
    @DisplayName("계획 — 경계 앞 봉 · 머리 평봉 · 경계 앞 원장 행을 행마다 내고, 편입 구간과 겹친 행은 거절한다 · 아무것도 안 쓴다")
    void planWritesNothing() {
        List<PriceBoundaryJob.Target> targets = job.plan();

        assertThat(targets).extracting(PriceBoundaryJob.Target::ticker).containsExactlyInAnyOrder("CAI", "SNZ", "FLY");
        PriceBoundaryJob.Target c = target(targets, "CAI");
        assertThat(c.dates()).containsExactly(LocalDate.of(2021, 11, 22));
        assertThat(c.ledgerRows()).hasSize(1);
        assertThat(target(targets, "SNZ").dates()).as("중간 평봉 08-01은 대상이 아니다")
                .containsExactly(LocalDate.of(2023, 7, 27), LocalDate.of(2023, 7, 28));
        assertThat(target(targets, "FLY").refused()).isTrue();

        job.run(LocalDate.of(2026, 10, 19), BatchExecutionRecorder.TriggeredBy.MANUAL, false);
        assertThat(count("SELECT count(*) FROM price_daily")).isEqualTo(8);
    }

    @Test
    @DisplayName("적용 — 지우기 전에 CSV를 내보내고, 원장 행은 REJECTED로 둔다 · 거절 행과 국내는 그대로 · 두 번째 판은 지울 것이 0")
    void applyExportsThenDeletes() throws Exception {
        job.run(LocalDate.of(2026, 10, 19), BatchExecutionRecorder.TriggeredBy.MANUAL, true);

        assertThat(dates(cai)).containsExactly(LocalDate.of(2025, 6, 18));
        assertThat(dates(sn)).containsExactly(LocalDate.of(2023, 7, 31), LocalDate.of(2023, 8, 1));
        assertThat(dates(fly)).as("편입 구간과 겹쳐 거절").containsExactly(LocalDate.of(2021, 6, 15));
        assertThat(dates(kr)).as("국내 대조군").hasSize(1);
        assertThat(jdbc.queryForObject("SELECT status FROM corporate_action WHERE stock_id = ?", String.class, cai)).isEqualTo("REJECTED");
        try (Stream<Path> files = Files.list(exportDir)) {
            List<String> lines = Files.readAllLines(files.findFirst().orElseThrow());
            assertThat(lines).hasSize(4);
            assertThat(lines.get(1)).contains("CAI,2021-11-22");
        }

        assertThat(job.plan()).extracting(PriceBoundaryJob.Target::ticker).containsExactly("FLY");
    }

    private static PriceBoundaryJob.Target target(List<PriceBoundaryJob.Target> targets, String ticker) {
        return targets.stream().filter(t -> t.ticker().equals(ticker)).findFirst().orElseThrow();
    }

    private long usRow(String ticker) {
        return jdbc.queryForObject("""
                INSERT INTO stock (market_code, ticker, name, alpaca_anchor_symbol, alpaca_anchor_date)
                VALUES ('US', ?, ?, ?, ?) RETURNING id""", Long.class, ticker, ticker + " Inc.", ticker, ANCHOR);
    }

    private void bar(long stockId, String date, String close, long volume) {
        BigDecimal c = new BigDecimal(close);
        jdbc.update("""
                INSERT INTO price_daily (stock_id, trade_date, open, high, low, close, adj_close, volume)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)""", stockId, LocalDate.parse(date), c, c, c, c, c, volume);
    }

    private List<LocalDate> dates(long stockId) {
        return jdbc.queryForList("SELECT trade_date FROM price_daily WHERE stock_id = ? ORDER BY 1", LocalDate.class, stockId);
    }

    private int count(String sql) {
        return jdbc.queryForObject(sql, Integer.class);
    }
}
