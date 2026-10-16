package com.stockscreener.pipeline.batch;

import com.stockscreener.notification.AlertEvent;
import com.stockscreener.notification.AlertPort;
import com.stockscreener.pipeline.collector.FilingLedgerStore;
import com.stockscreener.pipeline.datasource.DataSourceException;
import com.stockscreener.pipeline.datasource.FilingTrace;
import com.stockscreener.pipeline.datasource.RawFinancialReport;
import com.stockscreener.pipeline.datasource.us.SecFilingClient;
import com.stockscreener.pipeline.datasource.us.SecFilingConsumption;
import com.stockscreener.pipeline.support.FakeFinancialDataSource;
import com.stockscreener.pipeline.support.FakeFullStatementDataSource;
import com.stockscreener.support.TestDatabaseGuard;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link FilingConsumptionJob}(ADR-0176 2차) — 열린 장부 행을 좁힌 FR·FSR로 먹이고, <b>그 종목을 쓴 트랜잭션 안에서</b> 소비됨을
 * 표시한다. 근거는 그 FR·FSR이 받은 응답이다.
 *
 * <p>{@code @Transactional}을 쓰지 않는다 — 종목 단위 격리가 {@code REQUIRES_NEW}로 커밋된 트랜잭션을 요구한다(ADR-0007).
 */
@SpringBootTest
@ActiveProfiles("test")
@Import({TestDatabaseGuard.class, FilingConsumptionJobTest.FakeSources.class})
class FilingConsumptionJobTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 14);
    private static final String KR_TICKER = "990011";
    private static final String KR_OUTSIDER = "990012";
    private static final String US_TICKER = "ZZCN";
    private static final String US_CANDIDATE = "ZZCO";
    private static final long US_CIK = 99_000_011L;
    private static final long CANDIDATE_CIK = 99_000_012L;
    private static final List<String> JOBS = List.of(FilingConsumptionJob.JOB_NAME,
            FilingConsumptionJob.CANDIDATE_JOB_NAME, FinancialRefillJob.JOB_NAME, FullStatementRefillJob.JOB_NAME);

    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private FilingLedgerStore store;
    @Autowired
    private FinancialRefillJob financialRefill;
    @Autowired
    private FullStatementRefillJob fullStatementRefill;
    @Autowired
    private FakeFinancialDataSource financialSource;
    @Autowired
    private FakeFullStatementDataSource fullStatementSource;
    @Autowired
    private StockTaskExecutor executor;
    @Autowired
    private BatchExecutionRecorder recorder;
    @Autowired
    private ApplicationContext context;

    private final List<AlertEvent> alerts = new ArrayList<>();
    private SecFilingClient secClient;
    private FilingConsumptionJob job;
    private long krStock;
    private long krOutsider;
    private long usStock;
    private long usCandidate;

    @BeforeEach
    void setUp() {
        cleanUp();
        AlertPort recording = alerts::add;
        secClient = mock(SecFilingClient.class);
        job = new FilingConsumptionJob(store, financialRefill, fullStatementRefill, new SecFilingConsumption(secClient),
                new BatchExecutions(executor, recorder, recording, context));
        krStock = stock("KOSPI", KR_TICKER, "KOSPI200");
        krOutsider = stock("KOSPI", KR_OUTSIDER, null);
        usStock = stock("US", US_TICKER, "US_TOP1000");
        usCandidate = stock("US", US_CANDIDATE, null);
    }

    @AfterEach
    void cleanUp() {
        String tickers = "('" + KR_TICKER + "', '" + KR_OUTSIDER + "', '" + US_TICKER + "', '" + US_CANDIDATE + "')";
        String ours = "(SELECT id FROM stock WHERE ticker IN " + tickers + ")";
        jdbc.update("DELETE FROM filing_consumption WHERE stock_id IN " + ours);
        jdbc.update("DELETE FROM sec_filing_supplement WHERE cik IN (?, ?)", US_CIK, CANDIDATE_CIK);
        jdbc.update("DELETE FROM stock_job_refresh WHERE stock_id IN " + ours);
        jdbc.update("DELETE FROM financial_column_absence WHERE stock_id IN " + ours);
        jdbc.update("DELETE FROM financial_statement WHERE stock_id IN " + ours);
        jdbc.update("DELETE FROM universe_membership WHERE stock_id IN " + ours);
        for (String name : JOBS) {
            jdbc.update("DELETE FROM batch_execution_failure WHERE execution_id IN "
                    + "(SELECT id FROM batch_execution WHERE job_name = ?)", name);
            jdbc.update("DELETE FROM batch_execution_phase WHERE execution_id IN "
                    + "(SELECT id FROM batch_execution WHERE job_name = ?)", name);
            jdbc.update("DELETE FROM batch_execution WHERE job_name = ?", name);
        }
        jdbc.update("DELETE FROM stock WHERE ticker IN " + tickers);
        financialSource.reset();
        alerts.clear();
    }

    @Test
    @DisplayName("국내: 응답 접수번호가 목록 이상이면 FR 몫 → FSR 몫 순서로 소비되고, 좁힌 실행은 GAP_FILL·NARROW다")
    void consumesKrFrThenFsr() {
        long row = krRow(krStock, "20260814000123", 2026, "11012");
        FilingTrace answered = FilingTrace.ofReports(Map.of(new FilingTrace.ReportKey(2026, "11012"), "20260814000123"));
        financialSource.withReports(krStock, List.of(report())).withTrace(krStock, answered);
        fullStatementSource.withReports(krStock, List.of(report())).withTrace(krStock, answered);

        assertThat(job.run(TODAY, BatchExecutionRecorder.TriggeredBy.SCHEDULER)).isEqualTo(BatchStatus.SUCCESS);

        Map<String, Object> r = jdbc.queryForMap("SELECT * FROM filing_consumption WHERE id = ?", row);
        assertThat(r.get("fr_ground")).isEqualTo("DART_RESPONSE");
        assertThat(r.get("fr_evidence")).isEqualTo("20260814000123");
        assertThat(r.get("fsr_evidence")).isEqualTo("20260814000123");
        assertThat(r.get("last_reason")).isNull();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM financial_statement WHERE stock_id = ?", Long.class, krStock))
                .as("소비 FR이 그 종목을 썼다").isPositive();
        assertThat(execution(FinancialRefillJob.JOB_NAME)).containsEntry("triggered_by", "GAP_FILL")
                .containsEntry("scope", "NARROW");
        assertThat(r.get("consumed_by_execution")).isEqualTo(execution(FinancialRefillJob.JOB_NAME).get("id"));
        assertThat(r.get("fsr_by_execution")).isEqualTo(execution(FullStatementRefillJob.JOB_NAME).get("id"));
        assertThat(fullStatementSource.requestedPeriods.getLast()).as("분기 공시도 먹인다 — 티커만 주면 FY뿐이다")
                .hasSize(4);
    }

    @Test
    @DisplayName("국내: 응답이 앞 판이면 열린 채 「원장 아직」으로 남고 FSR은 부르지 않는다")
    void staysOpenWhileDartLags() {
        long row = krRow(krStock, "20260814000123", 2026, "11012");
        financialSource.withReports(krStock, List.of(report())).withTrace(krStock,
                FilingTrace.ofReports(Map.of(new FilingTrace.ReportKey(2026, "11012"), "20260520000001")));

        job.run(TODAY, BatchExecutionRecorder.TriggeredBy.SCHEDULER);

        Map<String, Object> r = jdbc.queryForMap("SELECT * FROM filing_consumption WHERE id = ?", row);
        assertThat(r.get("fr_consumed_at")).isNull();
        assertThat((String) r.get("last_reason")).startsWith(SecFilingConsumption.SOURCE_PENDING)
                .contains("20260520000001");
        assertThat(r.get("attempts")).isEqualTo(1);
        assertThat(execution(FullStatementRefillJob.JOB_NAME)).isEmpty();
    }

    @Test
    @DisplayName("편입 밖 종목의 행은 잠든다 — 대상이 없으면 FR을 부르지 않는다(비면 전량이 된다)")
    void outsidersSleepAndNothingRunsWithoutTargets() {
        long row = krRow(krOutsider, "20260814000999", 2026, "11012");

        job.run(TODAY, BatchExecutionRecorder.TriggeredBy.SCHEDULER);

        assertThat(jdbc.queryForObject("SELECT attempts FROM filing_consumption WHERE id = ?", Integer.class, row))
                .isZero();
        assertThat(execution(FinancialRefillJob.JOB_NAME)).isEmpty();
    }

    @Test
    @DisplayName("대조군: 표시 뒤 훅이 던지면 그 종목의 재무 쓰기와 표시가 함께 되돌아간다 — 「썼다」와 「소비됨」이 안 갈린다")
    void hookFailureRollsBackTheWriteAndTheMark() {
        long row = krRow(krStock, "20260814000123", 2026, "11012");
        financialSource.withReports(krStock, List.of(report()));

        financialRefill.runForConsumption(TODAY, BatchExecutionRecorder.TriggeredBy.MANUAL, 0, List.of(KR_TICKER),
                (executionId, security, outcome) -> {
                    store.markFrConsumed(row, "DART_RESPONSE", "20260814000123", executionId);
                    throw new IllegalStateException("표시 뒤 던짐");
                });

        assertThat(jdbc.queryForObject("SELECT count(*) FROM financial_statement WHERE stock_id = ?", Long.class, krStock))
                .isZero();
        assertThat(jdbc.queryForObject("SELECT fr_consumed_at IS NULL FROM filing_consumption WHERE id = ?",
                Boolean.class, row)).isTrue();
    }

    @Test
    @DisplayName("미국: companyfacts · 보충 표 · submissions의 XBRL 없음으로 닫고, 셋 다 아니면 사유와 함께 연다")
    void usGrounds() throws Exception {
        long factsRow = usRow("0099000011-26-000001", "10-Q");
        long supplementRow = usRow("0099000011-26-000002", "10-Q");
        long noXbrlRow = usRow("0099000011-26-000003", "6-K");
        long pendingRow = usRow("0099000011-26-000004", "10-Q");
        jdbc.update("""
                INSERT INTO sec_filing_supplement (accession, cik, form, filed, reader_version, status, facts, fetched_at,
                                                   last_seen_at)
                VALUES ('0099000011-26-000002', ?, '10-Q', '2026-10-09', 1, 'OK', '{}'::jsonb, now(), now())
                """, US_CIK);
        financialSource.withReports(usStock, List.of()).withTrace(usStock,
                FilingTrace.ofAccessions(Set.of("0099000011-26-000001")));
        when(secClient.filings(anyLong(), any())).thenReturn(List.of(new SecFilingClient.Filing(
                "0099000011-26-000003", "6-K", LocalDate.of(2026, 10, 9), LocalDate.of(2026, 10, 9), false)));

        job.run(TODAY, BatchExecutionRecorder.TriggeredBy.SCHEDULER);

        assertThat(groundOf(factsRow)).isEqualTo("COMPANYFACTS");
        assertThat(groundOf(supplementRow)).isEqualTo("SUPPLEMENT");
        assertThat(groundOf(noXbrlRow)).isEqualTo("NO_XBRL");
        assertThat(groundOf(pendingRow)).isNull();
        assertThat(jdbc.queryForObject("SELECT last_reason FROM filing_consumption WHERE id = ?", String.class,
                pendingRow)).startsWith(SecFilingConsumption.SOURCE_PENDING);
    }

    @Test
    @DisplayName("열린 행이 평일 5일을 넘으면 사유 머리별로 알린다 — 키를 못 정한 국내 행은 「우리 쪽」")
    void alertsOverdueRows() {
        long row = krRow(krStock, "20260925000001", null, null);
        jdbc.update("UPDATE filing_consumption SET first_seen_at = now() - interval '20 days', "
                + "last_reason = '보고서 이름에서 연월을 못 읽었다' WHERE id = ?", row);
        jdbc.update("UPDATE universe_membership SET created_at = now() - interval '20 days' WHERE stock_id = ?", krStock);

        job.run(TODAY, BatchExecutionRecorder.TriggeredBy.SCHEDULER);

        assertThat(alerts).anySatisfy(a -> {
            assertThat(a.title()).endsWith("공시가 소비되지 않고 쌓였다");
            assertThat(a.body()).contains("[우리 쪽] 1건").contains(KR_TICKER);
        });
        assertThat(execution(FinancialRefillJob.JOB_NAME)).as("키 없는 행은 먹이지 않는다").isEmpty();
    }

    @Test
    @DisplayName("편입 밖에서 잠든 기간은 지연으로 세지 않는다 — 오래전에 본 행도 막 편입됐으면 경보하지 않는다(대조군: 편입이 오래되면 경보)")
    void sleepingYearsAreNotOverdue() {
        long row = krRow(krStock, "20260925000001", null, null);
        jdbc.update("UPDATE filing_consumption SET first_seen_at = now() - interval '20 days', "
                + "last_reason = '보고서 이름에서 연월을 못 읽었다' WHERE id = ?", row);
        jdbc.update("UPDATE universe_membership SET created_at = now() - interval '1 hour' WHERE stock_id = ?", krStock);

        job.run(TODAY, BatchExecutionRecorder.TriggeredBy.SCHEDULER);

        assertThat(alerts).noneSatisfy(a -> assertThat(a.title()).endsWith("공시가 소비되지 않고 쌓였다"));
    }

    @Test
    @DisplayName("후보: 클래스 축 인스턴스를 못 받은 종목의 행은 근거가 서도 닫지 않는다 — 닫으면 정기 FR 없는 후보는 다음 공시까지 숨는다(ADR-0179)")
    void unfetchedClassFactsKeepTheRowOpen() {
        long candidateRow = candidateRow("0099000012-26-000001", "10-Q");
        financialSource.withReports(usCandidate, List.of()).withTrace(usCandidate,
                FilingTrace.ofAccessions(Set.of("0099000012-26-000001")))
                .unfetchedClassFactsFor(usCandidate, List.of(DataSourceException.Kind.RATE_LIMITED));

        job.runCandidates(TODAY, BatchExecutionRecorder.TriggeredBy.SCHEDULER);

        assertThat(groundOf(candidateRow)).as("companyfacts 근거는 섰지만 클래스 축이 미해결").isNull();
        assertThat(attemptsOf(candidateRow)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT last_reason FROM filing_consumption WHERE id = ?", String.class,
                candidateRow)).startsWith("클래스 축 인스턴스 미해결");
    }

    @Test
    @DisplayName("후보: 활성 편입 없는 미국 종목의 열린 행을 같은 판정으로 먹인다 — 편입 소비는 그 행을 안 건드리고, 후보 다리는 편입 행을 안 건드린다")
    void candidatesAreFedSeparatelyFromMembers() {
        long candidateRow = candidateRow("0099000012-26-000001", "10-Q");
        long memberRow = usRow("0099000011-26-000001", "10-Q");
        financialSource.withReports(usCandidate, List.of()).withTrace(usCandidate,
                FilingTrace.ofAccessions(Set.of("0099000012-26-000001")));
        financialSource.withReports(usStock, List.of()).withTrace(usStock,
                FilingTrace.ofAccessions(Set.of("0099000011-26-000001")));

        job.run(TODAY, BatchExecutionRecorder.TriggeredBy.SCHEDULER);
        assertThat(groundOf(memberRow)).isEqualTo("COMPANYFACTS");
        assertThat(attemptsOf(candidateRow)).as("편입 소비는 후보 행을 안 본다").isZero();
        assertThat(financialSource.fetchedStockIds).doesNotContain(usCandidate);

        assertThat(job.runCandidates(TODAY, BatchExecutionRecorder.TriggeredBy.SCHEDULER)).isEqualTo(BatchStatus.SUCCESS);
        assertThat(groundOf(candidateRow)).isEqualTo("COMPANYFACTS");
        assertThat(execution(FinancialRefillJob.JOB_NAME)).containsEntry("triggered_by", "GAP_FILL")
                .containsEntry("scope", "NARROW");
        assertThat(execution(FilingConsumptionJob.CANDIDATE_JOB_NAME)).isNotEmpty();
        assertThat(financialSource.fetchedStockIds.stream().filter(id -> id == usStock).count())
                .as("후보 다리는 편입 종목을 다시 부르지 않는다").isEqualTo(1);
    }

    @Test
    @DisplayName("후보: 시도 상한에 막 닿은 행을 한 번 알리고, 그 뒤엔 새 공시가 올 때까지 안 부른다 — 새 행이 오면 다시 묻는다")
    void candidateCapStopsUntilANewFiling() {
        long row = candidateRow("0099000012-26-000001", "10-Q");
        jdbc.update("UPDATE filing_consumption SET attempts = ? WHERE id = ?",
                FilingConsumptionJob.CANDIDATE_MAX_ATTEMPTS - 1, row);
        financialSource.withReports(usCandidate, List.of()).withTrace(usCandidate, FilingTrace.ofAccessions(Set.of()));

        job.runCandidates(TODAY, BatchExecutionRecorder.TriggeredBy.SCHEDULER);
        assertThat(attemptsOf(row)).isEqualTo(FilingConsumptionJob.CANDIDATE_MAX_ATTEMPTS);
        assertThat(alerts).filteredOn(a -> a.title().contains("시도 상한")).singleElement()
                .satisfies(a -> assertThat(a.body()).contains(US_CANDIDATE).contains("0099000012-26-000001"));

        alerts.clear();
        int fetched = financialSource.fetchedStockIds.size();
        job.runCandidates(TODAY, BatchExecutionRecorder.TriggeredBy.SCHEDULER);
        assertThat(financialSource.fetchedStockIds).as("상한에 닿은 종목은 다시 안 부른다").hasSize(fetched);
        assertThat(alerts).noneSatisfy(a -> assertThat(a.title()).contains("시도 상한"));

        candidateRow("0099000012-26-000002", "10-Q");
        job.runCandidates(TODAY, BatchExecutionRecorder.TriggeredBy.SCHEDULER);
        assertThat(financialSource.fetchedStockIds).as("새 공시가 그 종목을 깨운다").hasSize(fetched + 1);
        assertThat(attemptsOf(row)).as("깨어난 종목의 옛 행도 다시 판정한다")
                .isEqualTo(FilingConsumptionJob.CANDIDATE_MAX_ATTEMPTS + 1);
    }

    @Test
    @DisplayName("후보: 손 실행은 시도 상한 없이 다시 묻는다 — 코드를 고친 뒤 다시 물을 길")
    void manualCandidateRunIgnoresTheCap() {
        long row = candidateRow("0099000012-26-000001", "10-Q");
        jdbc.update("UPDATE filing_consumption SET attempts = 9 WHERE id = ?", row);
        financialSource.withReports(usCandidate, List.of()).withTrace(usCandidate,
                FilingTrace.ofAccessions(Set.of("0099000012-26-000001")));

        job.runCandidates(TODAY, BatchExecutionRecorder.TriggeredBy.MANUAL);

        assertThat(groundOf(row)).isEqualTo("COMPANYFACTS");
        assertThat(execution(FinancialRefillJob.JOB_NAME)).containsEntry("triggered_by", "MANUAL");
    }

    @Test
    @DisplayName("후보: FR이 그 종목에서 실패하면 행은 「우리 쪽」 사유로 열린 채 남고, 다리는 던지지 않는다")
    void candidateFailureLeavesRowsOpen() {
        long row = candidateRow("0099000012-26-000001", "6-K");
        financialSource.failingWith(usCandidate, new com.stockscreener.pipeline.datasource.DataSourceException(
                com.stockscreener.pipeline.datasource.DataSourceException.Kind.NOT_FOUND, "fake", "HTTP 404"));

        job.runCandidates(TODAY, BatchExecutionRecorder.TriggeredBy.SCHEDULER);

        assertThat(groundOf(row)).isNull();
        assertThat(jdbc.queryForObject("SELECT last_reason FROM filing_consumption WHERE id = ?", String.class, row))
                .startsWith(SecFilingConsumption.OUR_SIDE);
        assertThat(attemptsOf(row)).isEqualTo(1);
    }

    @Test
    @DisplayName("FR이 그 종목에서 404로 실패해도 XBRL 없는 6-K는 그 FR 실행으로 닫히고, XBRL 있는 20-F는 「우리 쪽」으로 열린 채 남는다")
    void unreachedStockStillClosesFilingsWithoutXbrl() throws Exception {
        long sixK = candidateRow("0099000012-26-000001", "6-K");
        long twentyF = candidateRow("0099000012-26-000002", "20-F");
        financialSource.failingWith(usCandidate, new com.stockscreener.pipeline.datasource.DataSourceException(
                com.stockscreener.pipeline.datasource.DataSourceException.Kind.NOT_FOUND, "fake", "HTTP 404. companyfacts"));
        when(secClient.filings(anyLong(), any())).thenReturn(List.of(
                new SecFilingClient.Filing("0099000012-26-000001", "6-K", LocalDate.of(2026, 10, 9), LocalDate.of(2026, 10, 9), false),
                new SecFilingClient.Filing("0099000012-26-000002", "20-F", LocalDate.of(2026, 10, 9), LocalDate.of(2026, 10, 9), true)));

        job.runCandidates(TODAY, BatchExecutionRecorder.TriggeredBy.SCHEDULER);

        assertThat(groundOf(sixK)).isEqualTo("NO_XBRL");
        assertThat(jdbc.queryForObject("SELECT consumed_by_execution FROM filing_consumption WHERE id = ?", Long.class, sixK))
                .as("컬럼의 뜻은 「FR 몫의 실행」이다").isEqualTo(execution(FinancialRefillJob.JOB_NAME).get("id"));
        assertThat(groundOf(twentyF)).isNull();
        assertThat(jdbc.queryForObject("SELECT last_reason FROM filing_consumption WHERE id = ?", String.class, twentyF))
                .startsWith(SecFilingConsumption.OUR_SIDE);
    }

    @Test
    @DisplayName("후보 FR은 편입 종목 티커만 주면 대상 0으로 던진다 — 조용한 0건 성공이 아니다")
    void candidateRefillRejectsMembers() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> financialRefill.runForCandidates(TODAY,
                        BatchExecutionRecorder.TriggeredBy.MANUAL, List.of(US_TICKER)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("순위 후보");
    }

    @Test
    @DisplayName("무효화 알림: 보존 창 편입 밖 종목은 「화해가 다시 쓰지 않는다」로 따로 적는다(편입 FR에선 그 줄이 없다)")
    void invalidationAlertNamesStocksOutsideTheWindow() {
        assertThat(FinancialRefillJob.outsideWindowLine(List.of())).isEmpty();
        assertThat(FinancialRefillJob.outsideWindowLine(List.of("TLRY"))).contains("1종목").contains("TLRY")
                .contains("화해가 다시 쓰지 않는다");
    }

    @Test
    @DisplayName("국내 창은 대상 행 사업연도 최솟값부터 — 상한은 정기 FR의 기본")
    void krWindow() {
        FilingLedgerStore.OpenRow lastYear = openRow(2025);
        FilingLedgerStore.OpenRow thisYear = openRow(2026);
        assertThat(FilingConsumptionJob.yearsBack(TODAY, List.of(thisYear))).isZero();
        assertThat(FilingConsumptionJob.yearsBack(TODAY, List.of(thisYear, lastYear))).isEqualTo(1);
        assertThat(FilingConsumptionJob.yearsBack(TODAY, List.of(openRow(2001))))
                .isEqualTo(FinancialRefillJob.DEFAULT_YEARS_BACK);
    }

    private static FilingLedgerStore.OpenRow openRow(int year) {
        return new FilingLedgerStore.OpenRow(1, "KR", 1, "x", "n", null, "A002", TODAY, year, "11012", true, true,
                java.time.Instant.now(), java.time.Instant.now(), null);
    }

    private long stock(String market, String ticker, String universe) {
        long id = jdbc.queryForObject("INSERT INTO stock (market_code, ticker, name) VALUES (?, ?, ?) RETURNING id",
                Long.class, market, ticker, ticker);
        if (universe != null) {
            jdbc.update("INSERT INTO universe_membership (universe_code, stock_id, effective_from) VALUES (?, ?, ?)",
                    universe, id, TODAY.minusYears(1));
        }
        return id;
    }

    private long krRow(long stockId, String rceptNo, Integer year, String reprtCode) {
        return jdbc.queryForObject("""
                INSERT INTO filing_consumption (market, filing_no, stock_id, corp_code, form, report_name, filed_on,
                                                bsns_year, reprt_code)
                VALUES ('KR', ?, ?, '00999011', 'A002', '반기보고서 (2026.06)', ?, ?, ?) RETURNING id
                """, Long.class, rceptNo, stockId, LocalDate.parse(rceptNo.substring(0, 8),
                java.time.format.DateTimeFormatter.BASIC_ISO_DATE), year, reprtCode);
    }

    private long usRow(String accession, String form) {
        return jdbc.queryForObject("""
                INSERT INTO filing_consumption (market, filing_no, stock_id, cik, form, filed_on)
                VALUES ('US', ?, ?, ?, ?, '2026-10-09') RETURNING id
                """, Long.class, accession, usStock, US_CIK, form);
    }

    private long candidateRow(String accession, String form) {
        return jdbc.queryForObject("""
                INSERT INTO filing_consumption (market, filing_no, stock_id, cik, form, filed_on)
                VALUES ('US', ?, ?, ?, ?, '2026-10-09') RETURNING id
                """, Long.class, accession, usCandidate, CANDIDATE_CIK, form);
    }

    private int attemptsOf(long row) {
        return jdbc.queryForObject("SELECT attempts FROM filing_consumption WHERE id = ?", Integer.class, row);
    }

    private String groundOf(long row) {
        return jdbc.queryForObject("SELECT fr_ground FROM filing_consumption WHERE id = ?", String.class, row);
    }

    private Map<String, Object> execution(String jobName) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT id, triggered_by, scope FROM batch_execution WHERE job_name = ? ORDER BY id DESC LIMIT 1", jobName);
        return rows.isEmpty() ? Map.of() : rows.getFirst();
    }

    private static RawFinancialReport report() {
        return new RawFinancialReport(
                2026, "Q2",
                LocalDate.of(2026, 4, 1), LocalDate.of(2026, 6, 30),
                LocalDate.of(2026, 8, 14),
                "11012", "KRW",
                new BigDecimal("5000"), new BigDecimal("1200"), new BigDecimal("1000"),
                new BigDecimal("20000"), new BigDecimal("12000"), new BigDecimal("8000"),
                new BigDecimal("9000"), new BigDecimal("4000"), new BigDecimal("6000"),
                null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null);
    }

    @TestConfiguration
    static class FakeSources {

        @Bean
        FakeFinancialDataSource fakeFinancialDataSource() {
            return new FakeFinancialDataSource("KOSPI", "KOSDAQ", "US");
        }

        @Bean
        FakeFullStatementDataSource fakeFullStatementDataSource() {
            return new FakeFullStatementDataSource("KOSPI", "KOSDAQ");
        }
    }
}
