package com.stockscreener.pipeline.batch;

import com.stockscreener.notification.AlertEvent;
import com.stockscreener.notification.AlertPort;
import com.stockscreener.pipeline.collector.EarningsReleaseStore;
import com.stockscreener.pipeline.collector.FilingLedgerStore;
import com.stockscreener.pipeline.datasource.DataSourceException;
import com.stockscreener.pipeline.datasource.FilingListSource;
import com.stockscreener.pipeline.datasource.FilingListSource.ListedRelease;
import com.stockscreener.pipeline.datasource.kr.DartCorpCodeCache;
import com.stockscreener.pipeline.datasource.kr.DartFilingListClient;
import com.stockscreener.pipeline.datasource.kr.DartFilingListClient.ListRow;
import com.stockscreener.pipeline.datasource.us.SecCurrentReportClient;
import com.stockscreener.pipeline.datasource.us.SecCurrentReportClient.CurrentReport;
import com.stockscreener.support.TestDatabaseGuard;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link EarningsReleaseJob}(ADR-0195) — 미국 항목 묻기 · 종목별 과거 훑기 · 한 종목 실패의 격리 · 정기 상한, 그리고 공시 목록이 실적 발표
 * 줄을 정기 공시와 한 트랜잭션으로 넣는 것.
 *
 * <p>{@code @Transactional}을 쓰지 않는다 — 잡이 실행 행을 자기 트랜잭션으로 커밋한다(ADR-0007).
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestDatabaseGuard.class)
class EarningsReleaseJobTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 30);
    private static final List<String> TICKERS = List.of("ZZER1", "ZZER2", "ZZER3", "990301", "990302");

    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private EarningsReleaseStore store;
    @Autowired
    private FilingLedgerStore ledger;
    @Autowired
    private StockTaskExecutor executor;
    @Autowired
    private BatchExecutionRecorder recorder;
    @Autowired
    private ApplicationContext context;

    private final List<AlertEvent> alerts = new ArrayList<>();
    private BatchExecutions executions;
    private final SecCurrentReportClient sec = mock(SecCurrentReportClient.class);
    private final DartFilingListClient dart = mock(DartFilingListClient.class);
    private final DartCorpCodeCache corpCodes = mock(DartCorpCodeCache.class);

    @BeforeEach
    void setUp() {
        cleanUp();
        AlertPort recording = alerts::add;
        executions = new BatchExecutions(executor, recorder, recording, context);
    }

    @AfterEach
    void cleanUp() {
        String ids = "(SELECT id FROM stock WHERE ticker IN ('ZZER1', 'ZZER2', 'ZZER3', '990301', '990302'))";
        jdbc.update("DELETE FROM earnings_release WHERE stock_id IN " + ids);
        jdbc.update("DELETE FROM earnings_release_sweep WHERE stock_id IN " + ids);
        jdbc.update("DELETE FROM filing_consumption WHERE stock_id IN " + ids);
        jdbc.update("DELETE FROM universe_membership WHERE stock_id IN " + ids);
        jdbc.update("DELETE FROM batch_execution_phase WHERE execution_id IN "
                + "(SELECT id FROM batch_execution WHERE job_name = ?)", EarningsReleaseJob.JOB_NAME);
        jdbc.update("DELETE FROM batch_execution_failure WHERE execution_id IN "
                + "(SELECT id FROM batch_execution WHERE job_name = ?)", EarningsReleaseJob.JOB_NAME);
        jdbc.update("DELETE FROM batch_execution WHERE job_name = ?", EarningsReleaseJob.JOB_NAME);
        jdbc.update("DELETE FROM stock WHERE ticker IN ('ZZER1', 'ZZER2', 'ZZER3', '990301', '990302')");
        alerts.clear();
    }

    @Test
    @DisplayName("훑기 — 감지 범위 미국 종목의 8-K를 항목과 함께 넣고 표지를 남긴다 · 범위 밖 종목은 안 훑는다")
    void sweepsInScopeStocks() throws Exception {
        long googl = stock("US", "ZZER1", 1652044L, null, true);
        stock("US", "ZZER2", 1652045L, null, false);
        when(sec.currentReports(eq(1652044L), eq(EarningsReleaseStore.FIRST_DATE))).thenReturn(List.of(
                new CurrentReport("0001652044-26-000090", "8-K", LocalDate.of(2026, 10, 28), "2.02,9.01",
                        java.time.Instant.parse("2026-10-28T20:01:33Z")),
                new CurrentReport("0001652044-26-000080", "8-K", LocalDate.of(2026, 9, 1), "5.02")));

        BatchStatus status = job().run(TODAY, BatchExecutionRecorder.TriggeredBy.MANUAL, 1000);

        assertThat(jdbc.queryForObject("SELECT effective_on FROM earnings_release WHERE filing_no = '0001652044-26-000090'",
                LocalDate.class)).as("16:01 ET 접수 — 그날 종가 뒤라 다음 날 봉부터 공개다").isEqualTo(LocalDate.of(2026, 10, 29));
        assertThat(jdbc.queryForObject("SELECT effective_on FROM earnings_release WHERE filing_no = '0001652044-26-000080'",
                LocalDate.class)).as("시각을 모르면 접수일").isEqualTo(LocalDate.of(2026, 9, 1));

        assertThat(jdbc.queryForList("SELECT filing_no || ' ' || items FROM earnings_release WHERE stock_id = ? ORDER BY filing_no",
                String.class, googl)).containsExactly("0001652044-26-000080 5.02", "0001652044-26-000090 2.02,9.01");
        assertThat(jdbc.queryForObject("SELECT swept_through FROM earnings_release_sweep WHERE stock_id = ?", LocalDate.class, googl))
                .isNotNull();
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM earnings_release_sweep w JOIN stock s ON s.id = w.stock_id WHERE s.ticker = 'ZZER2'
                """, Long.class)).as("범위 밖").isZero();
        assertThat(status).as("국내 소스가 시험에서 비어 있다(훑을 국내 종목 없음 → 그 시장도 끝)").isEqualTo(BatchStatus.SUCCESS);
    }

    @Test
    @DisplayName("항목 묻기 — 목록이 넣은 줄을 CIK 한 콜로 풀고, 아직 없는 접수번호는 그 줄만 남긴다 · 다른 CIK 실패가 막지 않는다")
    void resolvesUnknownItemsPerCik() throws Exception {
        long a = stock("US", "ZZER1", 1652044L, null, true);
        long b = stock("US", "ZZER3", 1652046L, null, true);
        sweptAlready(a);
        sweptAlready(b);
        store.insert("US", List.of(
                new ListedRelease("0001652044-26-000090", a, "8-K", null, LocalDate.of(2026, 10, 28)),
                new ListedRelease("0001652044-26-000091", a, "8-K", null, LocalDate.of(2026, 10, 29)),
                new ListedRelease("0001652046-26-000010", b, "8-K", null, LocalDate.of(2026, 10, 29))));
        when(sec.currentReports(eq(1652044L), any())).thenReturn(List.of(
                new CurrentReport("0001652044-26-000090", "8-K", LocalDate.of(2026, 10, 28), "2.02,9.01",
                        java.time.Instant.parse("2026-10-28T13:00:00Z"))));
        when(sec.currentReports(eq(1652046L), any())).thenThrow(
                new DataSourceException(DataSourceException.Kind.TRANSIENT, "sec", "끊김"));

        BatchStatus status = job().run(TODAY, BatchExecutionRecorder.TriggeredBy.SCHEDULER);

        assertThat(jdbc.queryForList("SELECT filing_no || ' ' || coalesce(items, '?') FROM earnings_release WHERE stock_id IN (?, ?) ORDER BY filing_no",
                String.class, a, b)).containsExactly("0001652044-26-000090 2.02,9.01", "0001652044-26-000091 ?",
                "0001652046-26-000010 ?");
        assertThat(jdbc.queryForObject("SELECT effective_on FROM earnings_release WHERE filing_no = '0001652044-26-000090'",
                LocalDate.class)).as("09:00 ET 접수 — 그날 봉부터").isEqualTo(LocalDate.of(2026, 10, 28));
        assertThat(status).as("한 CIK 실패는 그 시장의 실패로 센다").isEqualTo(BatchStatus.PARTIAL_SUCCESS);
    }

    @Test
    @DisplayName("국내 훑기 — 회사 코드로 받은 줄 중 실적 공시만 넣는다 · 정기 상한을 넘은 종목은 남겨 시장 미완료로 센다")
    void sweepsKrAndHonoursTheLimit() throws Exception {
        long first = stock("KOSPI", "990301", null, 12, true);
        long second = stock("KOSPI", "990302", null, 12, true);
        when(corpCodes.findCorpCode(any())).thenReturn(Optional.of("00126380"));
        when(dart.releases(eq("00126380"), any(), any())).thenReturn(List.of(
                new ListRow("I002", "00126380", "회사", "990301", "Y", "연결재무제표기준영업(잠정)실적(공정공시)", "20261029800001",
                        LocalDate.of(2026, 10, 29)),
                new ListRow("I001", "00126380", "회사", "990301", "Y", "결산실적공시예고", "20261001800002",
                        LocalDate.of(2026, 10, 1))));

        BatchStatus status = job().run(TODAY, BatchExecutionRecorder.TriggeredBy.SCHEDULER, 1);

        long sweptCount = jdbc.queryForObject("SELECT count(*) FROM earnings_release_sweep WHERE stock_id IN (?, ?)", Long.class,
                first, second);
        assertThat(sweptCount).as("상한 1").isEqualTo(1);
        assertThat(jdbc.queryForList("SELECT report_name FROM earnings_release WHERE stock_id IN (?, ?)", String.class, first, second))
                .containsExactly("연결재무제표기준영업(잠정)실적(공정공시)");
        assertThat(status).as("남은 종목이 있으면 끝까지 못 했다").isEqualTo(BatchStatus.PARTIAL_SUCCESS);
    }

    @Test
    @DisplayName("공시 목록이 넣는 실적 발표 줄은 그날을 닫는 트랜잭션 안이다 · 행의 시대 밖 줄은 안 붙는다")
    void listingWritesReleasesWithTheDay() {
        long a = stock("US", "ZZER1", 1652044L, null, true);
        jdbc.update("UPDATE stock SET era_from = DATE '2026-10-29' WHERE id = ?", a);
        try {
            ledger.recordDay("US", new FilingListSource.ListedDay(LocalDate.of(2031, 1, 2), FilingListSource.DayOutcome.LISTED, 0,
                    List.of(), List.of(new ListedRelease("0001652044-26-000090", a, "8-K", null, LocalDate.of(2026, 10, 28)),
                    new ListedRelease("0001652044-26-000095", a, "8-K", null, LocalDate.of(2026, 10, 29)))));

            assertThat(jdbc.queryForList("SELECT filing_no FROM earnings_release WHERE stock_id = ?", String.class, a))
                    .as("시대 시작(10-29) 앞의 10-28 줄은 안 붙는다").containsExactly("0001652044-26-000095");
        } finally {
            jdbc.update("DELETE FROM filing_listing_day WHERE listing_date = DATE '2031-01-02'");
        }
    }

    private EarningsReleaseJob job() {
        return new EarningsReleaseJob(store, executions, sec, dart, corpCodes);
    }

    private long stock(String market, String ticker, Long cik, Integer accountingMonth, boolean inScope) {
        long id = jdbc.queryForObject("""
                INSERT INTO stock (market_code, ticker, name, sec_cik, accounting_month) VALUES (?, ?, ?, ?, ?) RETURNING id
                """, Long.class, market, ticker, ticker, cik, accountingMonth);
        if (inScope) {
            String universe = jdbc.queryForObject("""
                    SELECT code FROM universe WHERE is_active AND (market_code = ? OR (? <> 'US' AND market_code <> 'US'))
                    ORDER BY code LIMIT 1
                    """, String.class, market, market);
            jdbc.update("INSERT INTO universe_membership (universe_code, stock_id, effective_from) VALUES (?, ?, DATE '2026-01-02')",
                    universe, id);
        }
        return id;
    }

    private void sweptAlready(long stockId) {
        jdbc.update("INSERT INTO earnings_release_sweep (stock_id, swept_from, swept_through) VALUES (?, DATE '2025-07-01', ?)",
                stockId, TODAY.minusDays(1));
    }
}
