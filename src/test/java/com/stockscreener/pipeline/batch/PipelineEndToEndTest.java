package com.stockscreener.pipeline.batch;

import com.stockscreener.notification.AlertEvent;
import com.stockscreener.notification.AlertPort;
import com.stockscreener.pipeline.collector.UniverseReconciler;
import com.stockscreener.pipeline.datasource.DataSourceException;
import com.stockscreener.pipeline.datasource.PriceDataSource;
import com.stockscreener.pipeline.datasource.RawPriceBar;
import com.stockscreener.pipeline.support.FakePriceDataSource;
import com.stockscreener.support.TestDatabaseGuard;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 유니버스 리밸런싱 → 초기 적재 → 일일 갱신의 전 과정을 Fake 소스로 검증한다.
 *
 * <p>API 키 없이 파이프라인 전체가 도는지를 확인하는 것이 목적이다. 실제 어댑터가 붙어도
 * 이 테스트는 그대로 유효하다 — 포트 계약만 쓰기 때문이다.
 *
 * <p>{@code @Transactional}을 쓰지 않는다. 종목 단위 격리가 커밋된 트랜잭션을 요구한다.
 */
/*
 * 실제 벤더 어댑터를 제외하고 돈다. 이 테스트의 관심사는 Fake 소스로 파이프라인 전체가
 * 도는지이며, 실제 어댑터가 함께 등록되면 DataSourceRegistry가 같은 시장에 두 소스가
 * 있다고 보고 기동에 실패한다 — 그 실패 자체는 레지스트리가 의도대로 동작한다는 증거다.
 * 제외는 아래 FakeSourceConfig의 @ComponentScan 필터가 담당한다.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import({TestDatabaseGuard.class, PipelineEndToEndTest.FakeSourceConfig.class})
class PipelineEndToEndTest {

    private static final LocalDate AS_OF = LocalDate.of(2026, 8, 5);

    /** 시드 CSV의 첫 종목. 시드 파일이 바뀌면 함께 바뀐다. */
    private static final String SEEDED_TICKER = "005930";

    @Autowired
    private UniverseRebalanceJob universeRebalanceJob;

    @Autowired
    private InitialLoadJob initialLoadJob;

    @Autowired
    private DailyUpdateJob dailyUpdateJob;

    @Autowired
    private UniverseReconciler reconciler;

    @Autowired
    private FakePriceDataSource fakePriceSource;

    @Autowired
    private RecordingAlertPort alertPort;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void reset() {
        cleanUp();
        alertPort.events.clear();
        // 컨텍스트가 캐시되므로 Fake의 상태가 테스트 간에 새어나간다.
        fakePriceSource.reset();
    }

    @AfterEach
    void cleanUp() {
        jdbcTemplate.update("DELETE FROM batch_execution_failure");
        jdbcTemplate.update("DELETE FROM batch_execution");
        jdbcTemplate.update("DELETE FROM price_daily");
        jdbcTemplate.update("DELETE FROM universe_membership");
        jdbcTemplate.update("DELETE FROM stock");
    }

    @Test
    @DisplayName("리밸런싱이 시드에서 종목을 만들고 유니버스에 편입한다")
    void rebalanceCreatesStocksFromSeed() {
        BatchStatus status = universeRebalanceJob.run(
                AS_OF, BatchExecutionRecorder.TriggeredBy.MANUAL);

        assertThat(status).isEqualTo(BatchStatus.SUCCESS);
        assertThat(reconciler.currentMembers("KOSPI200")).isNotEmpty();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM stock WHERE ticker = ?", Integer.class, SEEDED_TICKER))
                .isEqualTo(1);
    }

    @Test
    @DisplayName("리밸런싱은 멱등하다 — 두 번 돌려도 종목과 편입 행이 늘지 않는다")
    void rebalanceIsIdempotent() {
        universeRebalanceJob.run(AS_OF, BatchExecutionRecorder.TriggeredBy.MANUAL);
        int stocksAfterFirst = countStocks();
        int membershipsAfterFirst = countMemberships();

        universeRebalanceJob.run(AS_OF, BatchExecutionRecorder.TriggeredBy.MANUAL);

        assertThat(countStocks()).isEqualTo(stocksAfterFirst);
        assertThat(countMemberships()).isEqualTo(membershipsAfterFirst);
    }

    @Test
    @DisplayName("초기 적재가 편입 종목의 시세를 받아온다")
    void initialLoadFetchesHistory() {
        universeRebalanceJob.run(AS_OF, BatchExecutionRecorder.TriggeredBy.MANUAL);
        long stockId = stockIdOf(SEEDED_TICKER);
        fakePriceSource.withBars(stockId, List.of(
                bar("2026-08-03", "70000"), bar("2026-08-04", "71000")));

        initialLoadJob.run(AS_OF, BatchExecutionRecorder.TriggeredBy.MANUAL);

        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM price_daily WHERE stock_id = ?", Integer.class, stockId))
                .isEqualTo(2);
    }

    @Test
    @DisplayName("초기 적재는 이미 적재된 종목을 건너뛴다 — 쿼터 소진 후 재실행의 근거다")
    void initialLoadSkipsAlreadyLoadedStocks() {
        universeRebalanceJob.run(AS_OF, BatchExecutionRecorder.TriggeredBy.MANUAL);
        long stockId = stockIdOf(SEEDED_TICKER);
        fakePriceSource.withBars(stockId, List.of(bar("2026-08-03", "70000")));

        initialLoadJob.run(AS_OF, BatchExecutionRecorder.TriggeredBy.MANUAL);
        fakePriceSource.fetchedStockIds.clear();
        initialLoadJob.run(AS_OF, BatchExecutionRecorder.TriggeredBy.MANUAL);

        assertThat(fakePriceSource.fetchedStockIds)
                .as("이미 이력이 있는 종목은 다시 호출하지 않는다")
                .doesNotContain(stockId);
    }

    @Test
    @DisplayName("일일 갱신이 편입 종목만 수집한다")
    void dailyUpdateCollectsCurrentMembers() {
        universeRebalanceJob.run(AS_OF, BatchExecutionRecorder.TriggeredBy.MANUAL);
        long stockId = stockIdOf(SEEDED_TICKER);
        fakePriceSource.withBars(stockId, List.of(bar("2026-08-05", "72000")));

        BatchStatus status = dailyUpdateJob.run(
                AS_OF, BatchExecutionRecorder.TriggeredBy.SCHEDULER);

        assertThat(status).isEqualTo(BatchStatus.SUCCESS);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT close FROM price_daily WHERE stock_id = ? AND trade_date = ?",
                BigDecimal.class, stockId, AS_OF)).isEqualByComparingTo("72000");
    }

    @Test
    @DisplayName("주말에는 아무것도 하지 않는다 — 매 주말 850건의 실패 알림이 오면 안 된다")
    void dailyUpdateSkipsWeekend() {
        universeRebalanceJob.run(AS_OF, BatchExecutionRecorder.TriggeredBy.MANUAL);
        alertPort.events.clear();

        LocalDate saturday = LocalDate.of(2026, 8, 8);
        BatchStatus status = dailyUpdateJob.run(
                saturday, BatchExecutionRecorder.TriggeredBy.SCHEDULER);

        assertThat(status).isEqualTo(BatchStatus.SUCCESS);
        assertThat(alertPort.events).isEmpty();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM batch_execution WHERE job_name = ? AND target_date = ?",
                Integer.class, DailyUpdateJob.JOB_NAME, saturday)).isZero();
    }

    @Test
    @DisplayName("일부 종목이 실패해도 나머지가 수집되고 요약 알림 한 건이 간다")
    void dailyUpdateIsolatesFailures() {
        universeRebalanceJob.run(AS_OF, BatchExecutionRecorder.TriggeredBy.MANUAL);
        List<Long> members = reconciler.currentMembers("KOSPI200");
        long failing = members.get(0);
        for (Long member : members) {
            fakePriceSource.withBars(member, List.of(bar("2026-08-05", "1000")));
        }
        fakePriceSource.failingWith(failing, new DataSourceException(
                DataSourceException.Kind.TRANSIENT, "fake-price", "일시적 오류"));
        alertPort.events.clear();

        BatchStatus status = dailyUpdateJob.run(
                AS_OF, BatchExecutionRecorder.TriggeredBy.SCHEDULER);

        assertThat(status).isEqualTo(BatchStatus.PARTIAL_SUCCESS);
        // 실패한 종목만 빠지고 나머지는 저장된다.
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM price_daily WHERE trade_date = ?", Integer.class, AS_OF))
                .isEqualTo(members.size() - 1);
        // 알림은 종목마다가 아니라 요약 한 건이다.
        assertThat(alertPort.events).hasSize(1);
    }

    @Test
    @DisplayName("실패한 종목만 재실행하고 해결로 표시한다")
    void retriesOnlyFailedStocks() {
        universeRebalanceJob.run(AS_OF, BatchExecutionRecorder.TriggeredBy.MANUAL);
        List<Long> members = reconciler.currentMembers("KOSPI200");
        long failing = members.get(0);
        for (Long member : members) {
            fakePriceSource.withBars(member, List.of(bar("2026-08-05", "1000")));
        }
        fakePriceSource.failingWith(failing, new DataSourceException(
                DataSourceException.Kind.TRANSIENT, "fake-price", "일시적 오류"));
        dailyUpdateJob.run(AS_OF, BatchExecutionRecorder.TriggeredBy.SCHEDULER);

        long executionId = jdbcTemplate.queryForObject("""
                SELECT id FROM batch_execution WHERE job_name = ? ORDER BY id DESC LIMIT 1
                """, Long.class, DailyUpdateJob.JOB_NAME);

        // 이번엔 성공하게 만든다.
        fakePriceSource.clearFailure(failing);
        fakePriceSource.fetchedStockIds.clear();

        dailyUpdateJob.retryFailures(executionId, AS_OF);

        assertThat(fakePriceSource.fetchedStockIds)
                .as("재실행은 실패한 종목만 호출한다 — 성공 종목의 API를 다시 부르지 않는다")
                .containsExactly(failing);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT count(*) FROM batch_execution_failure
                WHERE execution_id = ? AND NOT resolved
                """, Integer.class, executionId)).isZero();
    }

    private int countStocks() {
        return jdbcTemplate.queryForObject("SELECT count(*) FROM stock", Integer.class);
    }

    private int countMemberships() {
        return jdbcTemplate.queryForObject(
                "SELECT count(*) FROM universe_membership", Integer.class);
    }

    private long stockIdOf(String ticker) {
        return jdbcTemplate.queryForObject(
                "SELECT id FROM stock WHERE ticker = ?", Long.class, ticker);
    }

    private static RawPriceBar bar(String date, String close) {
        BigDecimal c = new BigDecimal(close);
        return new RawPriceBar(LocalDate.parse(date), c, c, c, c, 1_000_000L, null, false);
    }

    static class RecordingAlertPort implements AlertPort {
        final List<AlertEvent> events = new ArrayList<>();

        @Override
        public void send(AlertEvent event) {
            events.add(event);
        }
    }

    @TestConfiguration
    static class FakeSourceConfig {

        /**
         * 빈 하나만 등록한다. 같은 인스턴스를 두 개의 {@code @Bean}으로 노출하면
         * {@code DataSourceRegistry}가 같은 시장에 두 소스가 등록된 것으로 보고 기동에 실패한다.
         */
        @Bean
        FakePriceDataSource fakePriceDataSource() {
            return new FakePriceDataSource("KOSPI", "KOSDAQ", "US");
        }

        @Bean
        @Primary
        RecordingAlertPort recordingAlertPort() {
            return new RecordingAlertPort();
        }
    }
}
