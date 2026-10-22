package com.stockscreener.pipeline.batch;

import com.stockscreener.notification.AlertEvent;
import com.stockscreener.notification.AlertPort;
import com.stockscreener.pipeline.collector.CorporateActionCollector;
import com.stockscreener.pipeline.collector.StockRegistrar;
import com.stockscreener.pipeline.datasource.CorporateAction;
import com.stockscreener.pipeline.datasource.DataSourceException;
import com.stockscreener.pipeline.datasource.StoredShareCounts;
import com.stockscreener.pipeline.datasource.us.AlpacaCorporateActionAdapter;
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

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link CandidateSplitJob}(ADR-0178) — 순위 후보의 공시 뒤 분할을 원장에 적고, 같은 사슬의 재구축이 그것을 {@code APPLIED}로 올려
 * 순위 판독기({@code JdbcStoredShareCounts})가 옮긴 주식수를 내는지 끝까지 잇는다. 대조군은 둘이다: 보존 창 편입(편입 수집이 맡는다)과
 * 공시일 앞의 분할(그 판이 이미 반영했다).
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestDatabaseGuard.class)
class CandidateSplitJobTest {

    private static final LocalDate AS_OF = LocalDate.of(2026, 10, 15);
    private static final LocalDate PUBLISHED = LocalDate.of(2026, 5, 1);
    private static final List<String> JOBS = List.of(CandidateSplitJob.JOB_NAME, CandidateSplitJob.DEPOSITARY_JOB_NAME, AdjustmentRebuildJob.JOB_NAME);

    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private StoredShareCounts shareCounts;
    @Autowired
    private StockRegistrar stockRegistrar;
    @Autowired
    private CorporateActionCollector collector;
    @Autowired
    private AdjustmentRebuildJob rebuildJob;
    @Autowired
    private StockTaskExecutor executor;
    @Autowired
    private BatchExecutionRecorder recorder;
    @Autowired
    private ApplicationContext context;

    private final List<AlertEvent> alerts = new ArrayList<>();
    private AlpacaCorporateActionAdapter source;
    private CandidateSplitJob job;
    private long candidate;
    private long member;
    private long alreadyReflected;

    @BeforeEach
    void setUp() {
        cleanUp();
        source = mock(AlpacaCorporateActionAdapter.class);
        AlertPort recording = alerts::add;
        job = new CandidateSplitJob(source, shareCounts, stockRegistrar, collector,
                new BatchExecutions(executor, recorder, recording, context));
        candidate = stock("ZZS1");
        member = stock("ZZS2");
        alreadyReflected = stock("ZZS3");
        jdbc.update("INSERT INTO universe_membership (universe_code, stock_id, effective_from) VALUES ('US_TOP1000', ?, ?)",
                member, AS_OF.minusYears(1));
        for (long id : List.of(candidate, member, alreadyReflected)) {
            jdbc.update("""
                    INSERT INTO financial_statement (stock_id, fiscal_year, fiscal_period, period_start, period_end,
                                                     published_at, revision_no, is_latest, currency_code, shares_outstanding)
                    VALUES (?, 2026, 'Q1', '2026-01-01', '2026-03-31', ?, 0, true, 'USD', 300000000)
                    """, id, PUBLISHED);
        }
    }

    @AfterEach
    void cleanUp() {
        String ours = "(SELECT id FROM stock WHERE ticker IN ('ZZS1', 'ZZS2', 'ZZS3'))";
        jdbc.update("DELETE FROM corporate_action WHERE stock_id IN " + ours);
        jdbc.update("DELETE FROM sec_depositary_cover WHERE cik = 919191");
        jdbc.update("DELETE FROM financial_statement WHERE stock_id IN " + ours);
        jdbc.update("DELETE FROM universe_membership WHERE stock_id IN " + ours);
        for (String name : JOBS) {
            jdbc.update("DELETE FROM batch_execution_failure WHERE execution_id IN "
                    + "(SELECT id FROM batch_execution WHERE job_name = ?)", name);
            jdbc.update("DELETE FROM batch_execution_phase WHERE execution_id IN "
                    + "(SELECT id FROM batch_execution WHERE job_name = ?)", name);
            jdbc.update("DELETE FROM batch_execution WHERE job_name = ?", name);
        }
        jdbc.update("DELETE FROM stock WHERE ticker IN ('ZZS1', 'ZZS2', 'ZZS3')");
        alerts.clear();
    }

    @Test
    @DisplayName("후보의 공시 뒤 병합을 적고 재구축이 APPLIED로 올려 순위 주식수가 옮겨진다 — 편입 종목과 공시 앞 병합은 안 건드린다")
    void candidateReverseSplitReachesTheRankingShares() throws Exception {
        assertThat(sharesOf("ZZS1")).as("대조: 병합 전 주식수 그대로").isEqualByComparingTo("300000000");
        when(source.fetchMarketSplits(any(), any())).thenReturn(new AlpacaCorporateActionAdapter.MarketSplits(Map.of(
                "ZZS1", List.of(reverse(LocalDate.of(2026, 8, 21))),
                "ZZS2", List.of(reverse(LocalDate.of(2026, 8, 21))),
                "ZZS3", List.of(reverse(LocalDate.of(2026, 4, 1)))), Map.of()));

        assertThat(job.run(AS_OF, BatchExecutionRecorder.TriggeredBy.SCHEDULER)).isEqualTo(BatchStatus.SUCCESS);

        assertThat(statusesOf(candidate)).containsExactly("APPROVED");
        assertThat(statusesOf(member)).as("편입은 기업 행위 수집이 맡는다").isEmpty();
        assertThat(statusesOf(alreadyReflected)).as("공시일 앞 병합은 그 판이 이미 반영했다").isEmpty();
        assertThat(sharesOf("ZZS1")).as("APPROVED는 순위가 안 읽는다 — 재구축 전").isEqualByComparingTo("300000000");

        AdjustmentRebuildJob.Invalidation invalidation = rebuildJob.runAndReport(AS_OF,
                BatchExecutionRecorder.TriggeredBy.MANUAL, List.of(), null);

        assertThat(statusesOf(candidate)).as("봉이 없어도 재구축이 표시한다").containsExactly("APPLIED");
        assertThat(invalidation.tickers()).as("창 밖 종목은 화해로 안 넘긴다").doesNotContain("ZZS1");
        assertThat(sharesOf("ZZS1")).as("1:10 병합 뒤 주식수").isEqualByComparingTo("30000000");
    }

    @Test
    @DisplayName("소스 레코드가 깨진 후보는 종목 실패로 남고 나머지는 적는다")
    void malformedSymbolFailsOnlyThatStock() throws Exception {
        when(source.fetchMarketSplits(any(), any())).thenReturn(new AlpacaCorporateActionAdapter.MarketSplits(
                Map.of("ZZS1", List.of(reverse(LocalDate.of(2026, 8, 21)))),
                Map.of("ZZS3", new DataSourceException(DataSourceException.Kind.MALFORMED, "fake", "부호가 어긋난다"))));

        assertThat(job.run(AS_OF, BatchExecutionRecorder.TriggeredBy.SCHEDULER)).isEqualTo(BatchStatus.PARTIAL_SUCCESS);

        assertThat(statusesOf(candidate)).containsExactly("APPROVED");
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM batch_execution_failure f JOIN batch_execution e ON e.id = f.execution_id
                WHERE e.job_name = ? AND f.stock_id = ?
                """, Long.class, CandidateSplitJob.JOB_NAME, alreadyReflected)).isEqualTo(1L);
    }

    @Test
    @DisplayName("소스가 없으면 조용히 성공하지 않는다 — 치명 실패로 남긴다")
    void missingSourceFailsLoudly() {
        CandidateSplitJob withoutSource = new CandidateSplitJob((AlpacaCorporateActionAdapter) null, shareCounts, stockRegistrar, collector,
                new BatchExecutions(executor, recorder, alerts::add, context));

        assertThat(withoutSource.run(AS_OF, BatchExecutionRecorder.TriggeredBy.SCHEDULER)).isEqualTo(BatchStatus.FAILED);
    }

    @Test
    @DisplayName("예탁 사건 손 잡 — 예탁 표지를 받은 등록인의 행이면 편입이든 후보든 2019 뒤 사건을 종목 조회로 적고, 관문 꺼짐이면 서지 않는다")
    void depositaryHistoryRecordsSince2019() throws Exception {
        jdbc.update("UPDATE stock SET sec_cik = 919191 WHERE id IN (?, ?)", candidate, member);
        jdbc.update("""
                INSERT INTO sec_depositary_cover (cik, accession, form, filed, reader_version, status, facts)
                VALUES (919191, 'A-1', '20-F', '2025-04-01', 1, 'OK', '{}'::jsonb)
                """);
        LocalDate event = LocalDate.of(2022, 10, 28);
        when(source.fetchCorporateActions(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.eq(com.stockscreener.pipeline.datasource.us.listing.DepositaryRatioJudge.LEDGER_FROM),
                org.mockito.ArgumentMatchers.eq(AS_OF)))
                .thenReturn(com.stockscreener.pipeline.datasource.CorporateActionDataSource.Fetched.of(List.of(reverse(event))));
        CandidateSplitJob gateOn = new CandidateSplitJob(source, shareCounts, stockRegistrar, collector,
                new BatchExecutions(executor, recorder, alerts::add, context), jdbc, true);

        assertThat(gateOn.runDepositaryHistory(AS_OF, BatchExecutionRecorder.TriggeredBy.MANUAL)).isEqualTo(BatchStatus.SUCCESS);
        assertThat(statusesOf(candidate)).hasSize(1);
        assertThat(statusesOf(member)).hasSize(1);
        assertThat(statusesOf(alreadyReflected)).as("예탁 표지 없는 등록인").isEmpty();
        assertThatThrownBy(() -> job.runDepositaryHistory(AS_OF, BatchExecutionRecorder.TriggeredBy.MANUAL))
                .isInstanceOf(IllegalStateException.class);
    }

    private static CorporateAction reverse(LocalDate exDate) {
        return new CorporateAction(exDate, exDate, exDate, CorporateAction.Type.REVERSE_SPLIT, new BigDecimal("0.1"),
                CorporateAction.DetectionSource.SOURCE_DISCLOSURE, "reverse_split");
    }

    private BigDecimal sharesOf(String ticker) {
        return shareCounts.activeByMarket("US", AS_OF.minusYears(2), AS_OF).stream()
                .filter(c -> c.ticker().equals(ticker)).findFirst().orElseThrow().shares();
    }

    private List<String> statusesOf(long stockId) {
        return jdbc.queryForList("SELECT status FROM corporate_action WHERE stock_id = ? ORDER BY effective_date",
                String.class, stockId);
    }

    private long stock(String ticker) {
        return jdbc.queryForObject("INSERT INTO stock (market_code, ticker, name) VALUES ('US', ?, ?) RETURNING id",
                Long.class, ticker, ticker);
    }
}
