package com.stockscreener.pipeline.batch;

import com.stockscreener.notification.AlertEvent;
import com.stockscreener.notification.AlertPort;
import com.stockscreener.pipeline.collector.StockRefreshStore;
import com.stockscreener.pipeline.collector.StockRefreshStore.Behind;
import com.stockscreener.pipeline.datasource.DataSourceException;
import com.stockscreener.pipeline.datasource.DataSourceRegistry;
import com.stockscreener.pipeline.datasource.RawFinancialReport;
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
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

/**
 * {@link FreshnessCatchUpJob}(ADR-0177) — 편입 뒤로 정기 범위로 맞춘 적이 없는 편입을 좁힌 FR·FSR로 맞추고, 그 종목 쓰기와
 * 같은 트랜잭션에서 표지를 쓴다. 표지 술어 하나가 F6·상한·손 실행·치명 실패를 다 덮는지 대조군과 짝지어 묻는다.
 *
 * <p>{@code @Transactional}을 쓰지 않는다 — 종목 단위 격리가 {@code REQUIRES_NEW}로 커밋된 트랜잭션을 요구한다(ADR-0007).
 * G는 시험 프로파일에 소스가 없어 「소스 없는 시장은 고르지 않는다」만 묻는다 — 실제 표지는 D4(작은 DB · 운영 원장)가 잰다.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import({TestDatabaseGuard.class, FreshnessCatchUpJobTest.FakeSources.class})
class FreshnessCatchUpJobTest {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final String KR_TICKER = "990021";
    private static final String KR_OLD = "990022";
    private static final String US_TICKER = "ZZFR";
    private static final List<String> TICKERS = List.of(KR_TICKER, KR_OLD, US_TICKER);
    private static final List<String> JOBS = List.of(FreshnessCatchUpJob.JOB_NAME, FinancialRefillJob.JOB_NAME,
            FullStatementRefillJob.JOB_NAME, GovernanceLoadJob.JOB_NAME, GovernanceDeriveJob.JOB_NAME);

    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private StockRefreshStore store;
    @Autowired
    private FinancialRefillJob financialRefill;
    @Autowired
    private FullStatementRefillJob fullStatementRefill;
    @Autowired
    private GovernanceLoadJob governanceLoad;
    @Autowired
    private GovernanceDeriveJob governanceDerive;
    @Autowired
    private DataSourceRegistry registry;
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
    private FreshnessCatchUpJob job;
    private LocalDate today;
    private long krStock;
    private long krOld;
    private long usStock;

    @BeforeEach
    void setUp() {
        cleanUp();
        today = LocalDate.now(KST);
        AlertPort recording = alerts::add;
        job = new FreshnessCatchUpJob(store, financialRefill, fullStatementRefill, governanceLoad, governanceDerive,
                mock(IndicatorReconciler.class), registry, new BatchExecutions(executor, recorder, recording, context),
                recording);
        krStock = stock("KOSPI", KR_TICKER, "KOSPI200");
        krOld = stock("KOSPI", KR_OLD, "KOSPI200");
        usStock = stock("US", US_TICKER, "US_TOP1000");
        for (long id : List.of(krStock, krOld, usStock)) {
            financialSource.withReports(id, List.of(report(id == usStock ? "USD" : "KRW")));
        }
    }

    @AfterEach
    void cleanUp() {
        String ours = "(SELECT id FROM stock WHERE ticker IN ('" + String.join("', '", TICKERS) + "'))";
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
        jdbc.update("DELETE FROM stock WHERE ticker IN ('" + String.join("', '", TICKERS) + "')");
        financialSource.reset();
        fullStatementSource.reset();
        alerts.clear();
    }

    @Test
    @DisplayName("술어: 표지 NULL은 편입이 컷오프 뒤일 때만 뒤처짐 · 표지가 편입보다 이르면 뒤처짐 (대조군: 컷오프 앞 편입 · 편입 뒤 표지)")
    void behindPredicate() {
        Instant cutoff = Instant.parse("2026-09-21T00:00:00Z");
        jdbc.update("UPDATE universe_membership SET created_at = ? WHERE stock_id = ?",
                java.sql.Timestamp.from(cutoff.minusSeconds(86_400)), krOld);
        Map<String, Instant> cutoffs = Map.of("KOSPI200", cutoff);

        assertThat(tickers(store.behind(FinancialRefillJob.JOB_NAME, Set.of("KOSPI"), cutoffs)))
                .as("컷오프 뒤 편입은 표지가 없으면 뒤처졌다 · 컷오프 앞 편입은 그 넓은 실행이 함께 맞췄다")
                .containsExactly(KR_TICKER);

        jdbc.update("INSERT INTO stock_job_refresh (stock_id, job_name, refreshed_at, execution_id) VALUES (?, ?, ?, 1)",
                krOld, FinancialRefillJob.JOB_NAME, java.sql.Timestamp.from(cutoff.minusSeconds(3 * 86_400)));
        jdbc.update("UPDATE universe_membership SET created_at = now() WHERE stock_id = ?", krOld);
        assertThat(tickers(store.behind(FinancialRefillJob.JOB_NAME, Set.of("KOSPI"), cutoffs)))
                .as("재편입 — 표지가 지금 편입보다 이르다")
                .containsExactlyInAnyOrder(KR_TICKER, KR_OLD);

        store.mark(krStock, FinancialRefillJob.JOB_NAME, 2);
        assertThat(tickers(store.behind(FinancialRefillJob.JOB_NAME, Set.of("KOSPI"), cutoffs)))
                .as("편입 뒤 표지는 맞춘 것이다")
                .containsExactly(KR_OLD);
        assertThat(store.behind(FinancialRefillJob.JOB_NAME, Set.of(), cutoffs)).as("소스 있는 시장이 없으면 고르지 않는다").isEmpty();
    }

    @Test
    @DisplayName("따라잡기: FR은 두 시장 · FSR은 국내만 GAP_FILL로 맞추고 표지를 쓴다 — 두 번째 실행은 대상 0(멱등)")
    void catchesUpAndIsIdempotent() {
        BatchStatus status = job.run(today, BatchExecutionRecorder.TriggeredBy.SCHEDULER, List.of());

        assertThat(status).isEqualTo(BatchStatus.SUCCESS);
        assertThat(marked(FinancialRefillJob.JOB_NAME)).containsExactlyInAnyOrder(KR_TICKER, KR_OLD, US_TICKER);
        assertThat(marked(FullStatementRefillJob.JOB_NAME)).as("FSR 소스는 국내뿐이다").containsExactlyInAnyOrder(KR_TICKER, KR_OLD);
        assertThat(fullStatementSource.fetchedStockIds).doesNotContain(usStock);
        assertThat(execution(FinancialRefillJob.JOB_NAME)).containsEntry("triggered_by", "GAP_FILL").containsEntry("scope", "NARROW");
        assertThat(marked(GovernanceLoadJob.JOB_NAME)).as("시험 프로파일에 G 소스가 없다 — 고르지 않는다").isEmpty();

        int frRuns = runs(FinancialRefillJob.JOB_NAME);
        job.run(today, BatchExecutionRecorder.TriggeredBy.SCHEDULER, List.of());
        assertThat(runs(FinancialRefillJob.JOB_NAME)).as("다 맞췄으면 FR을 부르지 않는다(비면 전량이 된다)").isEqualTo(frRuns);
    }

    @Test
    @DisplayName("실패한 종목은 표지가 없어 다음 차례 대상으로 남는다 · 성공한 종목만 빠진다")
    void failedStockStaysBehind() {
        financialSource.failingWith(krStock, new DataSourceException(DataSourceException.Kind.TRANSIENT, "fake", "끊김"));

        job.run(today, BatchExecutionRecorder.TriggeredBy.SCHEDULER, List.of());

        assertThat(marked(FinancialRefillJob.JOB_NAME)).containsExactlyInAnyOrder(KR_OLD, US_TICKER);
        assertThat(tickers(store.behind(FinancialRefillJob.JOB_NAME, Set.of("KOSPI", "US"), Map.of())))
                .containsExactly(KR_TICKER);
    }

    @Test
    @DisplayName("정기 범위가 아니면 표지를 안 쓴다 — 좁힌 연수 · 보충 누락 · 과거 기준일 (대조군: 기본 연수·오늘이면 쓴다)")
    void marksOnlyRegularReach() {
        financialRefill.runAndReport(today, BatchExecutionRecorder.TriggeredBy.MANUAL, 1, List.of(KR_TICKER), null);
        assertThat(marked(FinancialRefillJob.JOB_NAME)).as("좁힌 연수(국내 소비 꼴)").isEmpty();

        financialRefill.runAndReport(today.minusYears(1), BatchExecutionRecorder.TriggeredBy.MANUAL, null,
                List.of(KR_TICKER), null);
        assertThat(marked(FinancialRefillJob.JOB_NAME)).as("과거 기준일").isEmpty();

        financialSource.unfetchedFor(usStock, List.of(DataSourceException.Kind.TRANSIENT));
        financialRefill.runAndReport(today, BatchExecutionRecorder.TriggeredBy.MANUAL, null,
                List.of(KR_TICKER, US_TICKER), null);
        assertThat(marked(FinancialRefillJob.JOB_NAME)).as("보충을 못 받은 미국 종목은 빠진다 · 국내는 쓴다")
                .containsExactly(KR_TICKER);
    }

    @Test
    @DisplayName("훅이 던져 그 종목 쓰기가 되돌아가면 표지도 없다 — 표지는 훅 뒤 · 같은 종목 람다 안이다(대조군: 안 던지면 남는다)")
    void markRollsBackWithTheStock() {
        financialRefill.runForConsumption(today, BatchExecutionRecorder.TriggeredBy.MANUAL, null, List.of(KR_TICKER),
                (executionId, security, outcome) -> {
                    throw new DataSourceException(DataSourceException.Kind.MALFORMED, "fake", "훅 고장");
                });
        assertThat(marked(FinancialRefillJob.JOB_NAME)).isEmpty();

        financialRefill.runForConsumption(today, BatchExecutionRecorder.TriggeredBy.MANUAL, null, List.of(KR_TICKER),
                (executionId, security, outcome) -> { });
        assertThat(marked(FinancialRefillJob.JOB_NAME)).containsExactly(KR_TICKER);
    }

    @Test
    @DisplayName("그날 그 잡의 넓은 실행이 시작됐으면 건너뛴다 — 미완료로 내려간 행도 넓은 실행이다(대조군: 좁힌 실행은 아니다)")
    void skipsJobWhoseWideRunStartedToday() {
        jdbc.update("""
                INSERT INTO batch_execution (job_name, target_date, status, triggered_by, scope, scope_reason)
                VALUES (?, ?, 'PARTIAL_SUCCESS', 'SCHEDULER', 'NARROW', 'MARKET_INCOMPLETE')
                """, FinancialRefillJob.JOB_NAME, today);
        jdbc.update("""
                INSERT INTO batch_execution (job_name, target_date, status, triggered_by, scope, scope_reason)
                VALUES (?, ?, 'SUCCESS', 'GAP_FILL', 'NARROW', 'GAP_FILL')
                """, FullStatementRefillJob.JOB_NAME, today);

        job.run(today, BatchExecutionRecorder.TriggeredBy.SCHEDULER, List.of());

        assertThat(marked(FinancialRefillJob.JOB_NAME)).isEmpty();
        assertThat(marked(FullStatementRefillJob.JOB_NAME)).containsExactlyInAnyOrder(KR_TICKER, KR_OLD);
    }

    @Test
    @DisplayName("손 실행의 --tickers는 술어 안에서만 좁힌다 — 나머지는 다음 차례 대상으로 남는다")
    void manualTickersNarrowWithinThePredicate() {
        job.run(today, BatchExecutionRecorder.TriggeredBy.MANUAL, List.of(KR_TICKER));

        assertThat(marked(FinancialRefillJob.JOB_NAME)).containsExactly(KR_TICKER);
        assertThat(tickers(store.behind(FinancialRefillJob.JOB_NAME, Set.of("KOSPI", "US"), Map.of())))
                .containsExactlyInAnyOrder(KR_OLD, US_TICKER);
        assertThat(execution(FreshnessCatchUpJob.JOB_NAME)).containsEntry("scope", "NARROW");
    }

    @Test
    @DisplayName("상한: 시장마다 N까지 — 넘는 종목은 버리지 않고 술어에 남는다")
    void limitsPerMarket() {
        List<Behind> many = new ArrayList<>();
        for (int i = 0; i < FreshnessCatchUpJob.LIMIT_KR + 3; i++) {
            many.add(new Behind(i, "K" + i, "KOSPI", "KOSPI200", Instant.now(), null, null));
        }
        many.add(new Behind(999, "U1", "US", "US_TOP1000", Instant.now(), null, null));

        List<String> picked = FreshnessCatchUpJob.limited(many);

        assertThat(picked).hasSize(FreshnessCatchUpJob.LIMIT_KR + 1).contains("U1").doesNotContain("K50", "K51", "K52");
    }

    @Test
    @DisplayName("술어 순서: 마지막 실패가 오래된 순(실패 없음이 먼저) — 매일 실패하는 종목이 상한 앞자리를 막지 않는다")
    void failingStocksGoLast() {
        financialSource.failingWith(krOld, new DataSourceException(DataSourceException.Kind.TRANSIENT, "fake", "끊김"));
        financialRefill.runAndReport(today, BatchExecutionRecorder.TriggeredBy.MANUAL, 1, List.of(KR_OLD), null);

        assertThat(tickers(store.behind(FinancialRefillJob.JOB_NAME, Set.of("KOSPI"), Map.of())))
                .containsExactly(KR_TICKER, KR_OLD);
    }

    @Test
    @DisplayName("뒤처진 지 평일 5일을 넘으면 잡별로 알린다(대조군: 막 편입한 종목은 안 알린다)")
    void alertsOverdue() {
        financialSource.failingWith(krStock, new DataSourceException(DataSourceException.Kind.TRANSIENT, "fake", "끊김"));
        jdbc.update("UPDATE universe_membership SET created_at = now() - interval '20 days' WHERE stock_id = ?", krStock);

        job.run(today, BatchExecutionRecorder.TriggeredBy.SCHEDULER, List.of());

        assertThat(alerts).anySatisfy(a -> {
            assertThat(a.title()).endsWith("편입 원자료가 정기 범위로 맞춰지지 않고 남았다");
            assertThat(a.body()).contains("[" + FinancialRefillJob.JOB_NAME + "] 1종목").contains(KR_TICKER)
                    .doesNotContain(KR_OLD);
        });
    }

    @Test
    @DisplayName("꼬리는 던지지 않는다 — 실패는 ERROR 알림으로 남고 리밸런싱을 실패로 만들지 않는다")
    void tailDoesNotThrow() {
        FreshnessCatchUpJob broken = new FreshnessCatchUpJob(store, financialRefill, fullStatementRefill, governanceLoad,
                governanceDerive, mock(IndicatorReconciler.class), registry, null, alerts::add);

        broken.runAfterRebalance(BatchExecutionRecorder.TriggeredBy.SCHEDULER);

        assertThat(alerts).anySatisfy(a -> assertThat(a.title()).contains("편입 원자료 따라잡기 실패"));
        assertThatThrownBy(() -> broken.run(today, BatchExecutionRecorder.TriggeredBy.SCHEDULER, List.of()))
                .as("대조군: 평일 경로는 던진다 — 사슬이 모아 끝에서 다시 던진다")
                .isInstanceOf(NullPointerException.class);
    }

    private List<String> marked(String jobName) {
        return jdbc.queryForList("""
                SELECT s.ticker FROM stock_job_refresh r JOIN stock s ON s.id = r.stock_id
                WHERE r.job_name = ? AND s.ticker IN ('%s') ORDER BY s.ticker
                """.formatted(String.join("', '", TICKERS)), String.class, jobName);
    }

    private static List<String> tickers(List<Behind> behind) {
        return behind.stream().map(Behind::ticker).filter(TICKERS::contains).toList();
    }

    private int runs(String jobName) {
        Integer n = jdbc.queryForObject("SELECT count(*) FROM batch_execution WHERE job_name = ?", Integer.class, jobName);
        return n == null ? 0 : n;
    }

    private Map<String, Object> execution(String jobName) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT id, triggered_by, scope FROM batch_execution WHERE job_name = ? ORDER BY id DESC LIMIT 1", jobName);
        return rows.isEmpty() ? Map.of() : rows.getFirst();
    }

    private long stock(String market, String ticker, String universe) {
        long id = jdbc.queryForObject("INSERT INTO stock (market_code, ticker, name) VALUES (?, ?, ?) RETURNING id",
                Long.class, market, ticker, ticker);
        jdbc.update("INSERT INTO universe_membership (universe_code, stock_id, effective_from) VALUES (?, ?, ?)",
                universe, id, LocalDate.now(KST).minusYears(1));
        return id;
    }

    private static RawFinancialReport report(String currency) {
        return new RawFinancialReport(
                2026, "Q2",
                LocalDate.of(2026, 4, 1), LocalDate.of(2026, 6, 30),
                LocalDate.of(2026, 8, 14),
                "11012", currency,
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
