package com.stockscreener.pipeline.batch;

import com.stockscreener.notification.AlertEvent;
import com.stockscreener.notification.AlertPort;
import com.stockscreener.notification.AlertSeverity;
import com.stockscreener.pipeline.datasource.DataSourceException;
import com.stockscreener.pipeline.datasource.SecurityRef;
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

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Phase 2에서 가장 중요한 테스트. 종목 단위 실패 격리가 실제로 성립하는지 검증한다.
 *
 * <p>IMPORTANT — {@code @Transactional}을 <b>쓰지 않는다.</b> {@code REQUIRES_NEW}의 격리는
 * 커밋된 트랜잭션을 전제로 하므로, 테스트를 바깥 트랜잭션으로 감싸면 검증하려는 성질 자체가
 * 사라진다. 대신 {@code @AfterEach}로 명시적으로 정리한다(progress.md에 예고된 패턴).
 *
 * <p>{@code stock}에 실제 행이 필요한 이유는 {@code batch_execution_failure.stock_id}에
 * FK가 걸려 있기 때문이다.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import({TestDatabaseGuard.class, StockFailureIsolationTest.RecordingAlertConfig.class})
class StockFailureIsolationTest {

    private static final String JOB_NAME = "test-collection";
    private static final LocalDate TARGET_DATE = LocalDate.of(2026, 8, 5);

    @Autowired
    private BatchExecutions batchExecutions;

    @Autowired
    private BatchExecutionRecorder recorder;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private RecordingAlertPort alertPort;

    private final List<Long> createdStockIds = new ArrayList<>();

    @BeforeEach
    void resetAlerts() {
        alertPort.events.clear();
    }

    /**
     * 명시적 정리. 롤백에 기댈 수 없으므로 만든 것을 직접 지운다.
     *
     * <p>실패 기록은 {@code ON DELETE CASCADE}로 함께 사라지지만, 실행 행과 종목은
     * 직접 지워야 한다. 순서가 중요하다 — FK가 걸려 있다.
     */
    @AfterEach
    void cleanUp() {
        jdbcTemplate.update("DELETE FROM batch_execution WHERE job_name = ?", JOB_NAME);
        for (long stockId : createdStockIds) {
            jdbcTemplate.update("DELETE FROM stock WHERE id = ?", stockId);
        }
        createdStockIds.clear();
    }

    @Test
    @DisplayName("한 종목이 실패해도 나머지는 저장되고, 실패한 종목을 나중에 식별할 수 있다")
    void isolatesFailureToSingleStock() throws Exception {
        SecurityRef first = insertStock("000001", "정상1");
        SecurityRef broken = insertStock("000002", "실패");
        SecurityRef third = insertStock("000003", "정상2");

        BatchExecutionContext execution = batchExecutions.start(
                JOB_NAME, TARGET_DATE, BatchExecutionRecorder.TriggeredBy.SCHEDULER, 3, BatchExecutionRecorder.DeclaredScope.FULL);

        for (SecurityRef security : List.of(first, broken, third)) {
            execution.process(security, CollectionPhase.PRICE, s -> {
                if (s.stockId() == broken.stockId()) {
                    throw new DataSourceException(
                            DataSourceException.Kind.TRANSIENT, "stub-source", "일시적 오류");
                }
                return 500L;
            });
        }
        BatchStatus status = execution.finish();

        // 성공한 종목은 집계에 남고, 실패한 종목만 빠진다.
        assertThat(status).isEqualTo(BatchStatus.PARTIAL_SUCCESS);
        assertThat(execution.successCount()).isEqualTo(2);
        assertThat(execution.failureCount()).isEqualTo(1);
        assertThat(execution.writtenRowCount()).isEqualTo(1000L);

        // 어느 종목이 왜 실패했는지 식별할 수 있다 — 부분 재실행이 여기 의존한다.
        List<Map<String, Object>> failures = jdbcTemplate.queryForList(
                "SELECT * FROM batch_execution_failure WHERE execution_id = ?",
                execution.executionId());
        assertThat(failures).hasSize(1);
        assertThat(failures.get(0).get("stock_id")).isEqualTo(broken.stockId());
        assertThat(failures.get(0).get("failed_phase")).isEqualTo("PRICE");
        assertThat(failures.get(0).get("failure_kind")).isEqualTo("TRANSIENT");
        assertThat(failures.get(0).get("source_name")).isEqualTo("stub-source");
        assertThat((String) failures.get(0).get("message")).contains("일시적 오류");
        assertThat(failures.get(0).get("resolved")).isEqualTo(false);
    }

    /**
     * <b>다른 실행</b>이 같은 종목·같은 단계를 성공시키면 앞선 실패가 닫힌다.
     *
     * <p>이전에는 해결 표시가 {@code daily-update}의 시세 단계 하나에만 붙어 있었고, 게다가
     * 실패를 낸 실행 id로 좁혀 갱신했다. 그래서 <b>실패를 낸 실행과 그것을 채운 실행이 다르면</b>
     * 데이터가 멀쩡해진 뒤에도 원장이 미해결이라 말했다 — {@code company-profile}의 52건이
     * 정확히 그 상태였다. spec §4의 "미해결 에러 건수"가 그만큼 거짓이었다.
     */
    @Test
    @DisplayName("다른 실행이 같은 단계를 성공시키면 앞선 실패가 해결로 닫힌다")
    void laterExecutionResolvesEarlierFailureOfSamePhase() throws Exception {
        SecurityRef broken = insertStock("000020", "먼저실패");

        BatchExecutionContext first = batchExecutions.start(
                JOB_NAME, TARGET_DATE, BatchExecutionRecorder.TriggeredBy.SCHEDULER, 1, BatchExecutionRecorder.DeclaredScope.FULL);
        first.process(broken, CollectionPhase.SECURITY_MASTER, s -> {
            throw new DataSourceException(
                    DataSourceException.Kind.TRANSIENT, "stub-source", "일시적 오류");
        });
        first.finish();
        assertThat(unresolved(broken)).as("1차 직후 미해결").isEqualTo(1);

        BatchExecutionContext second = batchExecutions.start(
                JOB_NAME, TARGET_DATE, BatchExecutionRecorder.TriggeredBy.SCHEDULER, 1, BatchExecutionRecorder.DeclaredScope.FULL);
        second.process(broken, CollectionPhase.SECURITY_MASTER, s -> 1L);
        second.finish();

        assertThat(unresolved(broken))
                .as("2차가 같은 단계를 성공시키면 앞선 실패가 닫힌다")
                .isZero();
    }

    /**
     * <b>다른 단계</b>의 성공은 남의 실패를 닫지 않는다.
     *
     * <p>이 대조군이 없으면 위 테스트는 "무조건 닫는다"로도 통과한다. 시세 수집 성공을 근거로
     * 업종정보 실패를 지우면 <b>어느 단계가 실제로 다시 돌았는지가 사라져</b>, 실패를 조용히
     * 삼키지 않겠다는 파이프라인 전체의 전제가 그 지점에서 깨진다.
     */
    @Test
    @DisplayName("IMPORTANT — 다른 단계의 성공은 남의 실패를 닫지 않는다")
    void successInOtherPhaseDoesNotResolveUnrelatedFailure() throws Exception {
        SecurityRef broken = insertStock("000021", "업종실패");

        BatchExecutionContext first = batchExecutions.start(
                JOB_NAME, TARGET_DATE, BatchExecutionRecorder.TriggeredBy.SCHEDULER, 1, BatchExecutionRecorder.DeclaredScope.FULL);
        first.process(broken, CollectionPhase.SECURITY_MASTER, s -> {
            throw new DataSourceException(
                    DataSourceException.Kind.TRANSIENT, "stub-source", "일시적 오류");
        });
        first.finish();

        BatchExecutionContext second = batchExecutions.start(
                JOB_NAME, TARGET_DATE, BatchExecutionRecorder.TriggeredBy.SCHEDULER, 1, BatchExecutionRecorder.DeclaredScope.FULL);
        second.process(broken, CollectionPhase.PRICE, s -> 500L);
        second.finish();

        assertThat(unresolved(broken))
                .as("시세가 성공해도 업종정보 실패는 그대로다")
                .isEqualTo(1);
    }

    /**
     * 단계는 「받은 것의 종류」다 — 요약 재무({@code FINANCIAL})의 성공이 전체 재무제표({@code FULL_STATEMENT}) 실패를 닫지 않는다.
     * 한 단계였을 때 FR·FSR이 서로의 실패를 닫아 spec §4의 미해결 수가 거짓이었다(2026-10-31).
     */
    @Test
    @DisplayName("IMPORTANT — 요약 재무의 성공은 전체 재무제표 실패를 닫지 않는다(받는 것이 다르면 단계가 다르다)")
    void summaryFinancialSuccessDoesNotResolveFullStatementFailure() throws Exception {
        SecurityRef broken = insertStock("000022", "전체실패");

        BatchExecutionContext first = batchExecutions.start(
                JOB_NAME, TARGET_DATE, BatchExecutionRecorder.TriggeredBy.SCHEDULER, 1, BatchExecutionRecorder.DeclaredScope.FULL);
        first.process(broken, CollectionPhase.FULL_STATEMENT, s -> {
            throw new DataSourceException(
                    DataSourceException.Kind.TRANSIENT, "stub-source", "일시적 오류");
        });
        first.finish();

        BatchExecutionContext other = batchExecutions.start(
                JOB_NAME, TARGET_DATE, BatchExecutionRecorder.TriggeredBy.SCHEDULER, 1, BatchExecutionRecorder.DeclaredScope.FULL);
        other.process(broken, CollectionPhase.FINANCIAL, s -> 3L);
        other.finish();
        assertThat(unresolved(broken)).as("받는 것이 다른 단계의 성공으로는 안 닫힌다").isEqualTo(1);
    }

    /**
     * 같은 원칙 — 평일 15일 창({@code PRICE})의 성공이 초기 적재의 이력({@code PRICE_HISTORY}) 실패를 닫지 않는다. 한 단계였을 때 긴 창 거절
     * ({@code [05] SERVICETIMEOUT_ERROR})이 다음 평일에 「해결」로 접혀 짧은 이력이 신호 없이 남았다(ADR-0141 「남는 구멍」 · 2026-10-31).
     */
    @Test
    @DisplayName("IMPORTANT — 평일 창 시세의 성공은 초기 적재 이력 실패를 닫지 않는다")
    void windowPriceSuccessDoesNotResolveHistoryFailure() throws Exception {
        SecurityRef refused = insertStock("000023", "이력거절");

        BatchExecutionContext first = batchExecutions.start(
                JOB_NAME, TARGET_DATE, BatchExecutionRecorder.TriggeredBy.SCHEDULER, 1, BatchExecutionRecorder.DeclaredScope.FULL);
        first.process(refused, CollectionPhase.PRICE_HISTORY, s -> {
            throw new DataSourceException(
                    DataSourceException.Kind.TRANSIENT, "stub-source", "공공데이터포털 오류 [05] SERVICETIMEOUT_ERROR");
        });
        first.finish();

        BatchExecutionContext daily = batchExecutions.start(
                JOB_NAME, TARGET_DATE, BatchExecutionRecorder.TriggeredBy.SCHEDULER, 1, BatchExecutionRecorder.DeclaredScope.FULL);
        daily.process(refused, CollectionPhase.PRICE, s -> 10L);
        daily.finish();
        assertThat(unresolved(refused)).as("15일 창 성공으로는 이력 실패가 안 닫힌다").isEqualTo(1);
    }

    private int unresolved(SecurityRef security) {
        return jdbcTemplate.queryForObject("""
                SELECT count(*) FROM batch_execution_failure
                WHERE stock_id = ? AND NOT resolved
                """, Integer.class, security.stockId());
    }

    @Test
    @DisplayName("실패한 종목의 쓰기는 롤백되고 성공한 종목의 쓰기는 커밋된다")
    void rollsBackOnlyTheFailingStock() throws Exception {
        SecurityRef ok = insertStock("000010", "정상");
        SecurityRef broken = insertStock("000011", "실패");

        BatchExecutionContext execution = batchExecutions.start(
                JOB_NAME, TARGET_DATE, BatchExecutionRecorder.TriggeredBy.SCHEDULER, 2, BatchExecutionRecorder.DeclaredScope.FULL);

        // 각 작업이 자기 종목의 이름을 바꾼다. 실패 종목의 변경만 되돌아가야 한다.
        for (SecurityRef security : List.of(ok, broken)) {
            execution.process(security, CollectionPhase.PRICE, s -> {
                jdbcTemplate.update("UPDATE stock SET name = ? WHERE id = ?", "변경됨", s.stockId());
                if (s.stockId() == broken.stockId()) {
                    throw new DataSourceException(
                            DataSourceException.Kind.NOT_FOUND, "stub-source", "종목 없음");
                }
                return 1L;
            });
        }
        execution.finish();

        assertThat(nameOf(ok.stockId())).isEqualTo("변경됨");
        // 이것이 REQUIRES_NEW가 실제로 걸려 있다는 증거다. 자기 호출로 프록시를 우회했다면
        // 이 값도 "변경됨"이 되거나, 반대로 성공 종목까지 되돌아간다.
        assertThat(nameOf(broken.stockId())).isEqualTo("실패");
    }

    @Test
    @DisplayName("워커 둘이 동시에 돌아도 실패한 종목만 롤백되고 집계가 맞는다")
    void isolatesFailureWhenStocksRunConcurrently() throws Exception {
        SecurityRef ok = insertStock("000012", "정상");
        SecurityRef broken = insertStock("000013", "실패");

        BatchExecutionContext execution = batchExecutions.start(
                JOB_NAME, TARGET_DATE, BatchExecutionRecorder.TriggeredBy.SCHEDULER, 2, BatchExecutionRecorder.DeclaredScope.FULL);

        // MUST: 배리어로 **두 트랜잭션이 동시에 열려 있게** 만든다. 없으면 앞이 끝난 뒤 뒤가
        // 도는 순차와 구분되지 않아 이 시험이 위의 것과 같은 것을 잰다.
        CyclicBarrier bothInsideTransaction = new CyclicBarrier(2);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (SecurityRef security : List.of(ok, broken)) {
                futures.add(pool.submit(() ->
                        execution.process(security, CollectionPhase.PRICE, s -> {
                            jdbcTemplate.update("UPDATE stock SET name = ? WHERE id = ?",
                                    "변경됨", s.stockId());
                            awaitQuietly(bothInsideTransaction);
                            if (s.stockId() == broken.stockId()) {
                                throw new DataSourceException(
                                        DataSourceException.Kind.NOT_FOUND, "stub-source", "종목 없음");
                            }
                            return 1L;
                        })));
            }
            for (Future<?> future : futures) {
                future.get(30, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }
        BatchStatus status = execution.finish();

        // ADR-0007의 성질이 병렬에서도 그대로 서는가 — REQUIRES_NEW는 스레드에 묶이므로
        // 워커마다 자기 트랜잭션을 연다. 여기가 그것을 실제로 겹쳐서 확인하는 자리다.
        assertThat(nameOf(ok.stockId())).isEqualTo("변경됨");
        assertThat(nameOf(broken.stockId())).isEqualTo("실패");
        assertThat(execution.successCount()).isEqualTo(1);
        assertThat(execution.failureCount()).isEqualTo(1);
        assertThat(status).isEqualTo(BatchStatus.PARTIAL_SUCCESS);
    }

    /** 배리어 대기. 깨지면 시험이 조용히 순차가 되므로 삼키지 않고 올린다. */
    private static void awaitQuietly(CyclicBarrier barrier) {
        try {
            barrier.await(30, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("배리어 대기가 인터럽트됐다", e);
        } catch (Exception e) {
            throw new IllegalStateException("배리어가 깨졌다 — 두 워커가 겹치지 않았다", e);
        }
    }

    @Test
    @DisplayName("전 종목이 성공하면 SUCCESS이고 알림을 보내지 않는다")
    void staysSilentWhenEverythingSucceeds() throws Exception {
        SecurityRef security = insertStock("000020", "정상");

        BatchExecutionContext execution = batchExecutions.start(
                JOB_NAME, TARGET_DATE, BatchExecutionRecorder.TriggeredBy.SCHEDULER, 1, BatchExecutionRecorder.DeclaredScope.FULL);
        execution.process(security, CollectionPhase.PRICE, s -> 10L);
        BatchStatus status = execution.finish();

        assertThat(status).isEqualTo(BatchStatus.SUCCESS);
        assertThat(alertPort.events).isEmpty();
    }

    @Test
    @DisplayName("실패가 많아도 알림은 요약 한 건이다 — 건마다 보내면 알림이 무의미해진다")
    void sendsSingleSummaryAlert() throws Exception {
        List<SecurityRef> securities = List.of(
                insertStock("000030", "A"), insertStock("000031", "B"), insertStock("000032", "C"));

        BatchExecutionContext execution = batchExecutions.start(
                JOB_NAME, TARGET_DATE, BatchExecutionRecorder.TriggeredBy.SCHEDULER, 3, BatchExecutionRecorder.DeclaredScope.FULL);
        for (SecurityRef security : securities) {
            execution.process(security, CollectionPhase.PRICE, s -> {
                throw new DataSourceException(
                        DataSourceException.Kind.TRANSIENT, "stub-source", "실패");
            });
        }
        execution.finish();

        assertThat(alertPort.events).hasSize(1);
        AlertEvent event = alertPort.events.get(0);
        assertThat(event.severity()).isEqualTo(AlertSeverity.WARNING);
        assertThat(event.body()).contains("3건 실패");
        assertThat(event.context()).containsEntry("executionId", String.valueOf(execution.executionId()));
    }

    @Test
    @DisplayName("IMPORTANT — 알림의 단위는 종목이 아니라 건이다. 한 종목이 여러 번 세어진다")
    void countsTasksNotStocks() throws Exception {
        // 종목 하나를 두 단계로 처리한다. 실제로는 일일 갱신이 시세·지표를, 지표 백필이
        // 같은 종목을 거래일마다 처리하는 형태다. 단위를 "종목"이라 부르면 350종목짜리
        // 유니버스에서 "84716종목 실패"라는 알림이 나간다 — 실제로 나갔다.
        SecurityRef security = insertStock("000033", "두 단계");

        BatchExecutionContext execution = batchExecutions.start(
                JOB_NAME, TARGET_DATE, BatchExecutionRecorder.TriggeredBy.SCHEDULER, 1, BatchExecutionRecorder.DeclaredScope.FULL);
        for (CollectionPhase phase : List.of(CollectionPhase.PRICE, CollectionPhase.FINANCIAL)) {
            execution.process(security, phase, s -> {
                throw new DataSourceException(
                        DataSourceException.Kind.TRANSIENT, "stub-source", "실패");
            });
        }
        execution.finish();

        assertThat(execution.failureCount()).isEqualTo(2);
        assertThat(alertPort.events.get(0).body())
                .contains("2건 실패")
                .doesNotContain("종목");
    }

    @Test
    @DisplayName("인증 실패는 배치를 중단시킨다 — 키가 틀렸는데 850종목을 시도할 이유가 없다")
    void abortsEntireBatchOnAuthFailure() throws Exception {
        SecurityRef security = insertStock("000040", "종목");

        BatchExecutionContext execution = batchExecutions.start(
                JOB_NAME, TARGET_DATE, BatchExecutionRecorder.TriggeredBy.SCHEDULER, 1, BatchExecutionRecorder.DeclaredScope.FULL);

        assertThatThrownBy(() -> execution.process(security, CollectionPhase.PRICE, s -> {
            throw new DataSourceException(
                    DataSourceException.Kind.AUTH, "stub-source", "키가 유효하지 않다");
        })).isInstanceOf(DataSourceException.class);

        // 중단하더라도 실패는 기록된다 — 조용히 사라지지 않는다.
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM batch_execution_failure WHERE execution_id = ?",
                Integer.class, execution.executionId())).isEqualTo(1);

        execution.failFatally(new DataSourceException(
                DataSourceException.Kind.AUTH, "stub-source", "키가 유효하지 않다"));
        assertThat(alertPort.events).hasSize(1);
        assertThat(alertPort.events.get(0).severity()).isEqualTo(AlertSeverity.CRITICAL);
    }

    @Test
    @DisplayName("예상하지 못한 예외는 UNEXPECTED로 구분해 기록한다 — 버그가 수집 실패에 묻히면 안 된다")
    void recordsUnexpectedExceptionsSeparately() throws Exception {
        SecurityRef security = insertStock("000050", "종목");

        BatchExecutionContext execution = batchExecutions.start(
                JOB_NAME, TARGET_DATE, BatchExecutionRecorder.TriggeredBy.SCHEDULER, 1, BatchExecutionRecorder.DeclaredScope.FULL);
        execution.process(security, CollectionPhase.FINANCIAL, s -> {
            throw new IllegalStateException("계산 로직 버그");
        });
        execution.finish();

        Map<String, Object> failure = jdbcTemplate.queryForMap(
                "SELECT * FROM batch_execution_failure WHERE execution_id = ?",
                execution.executionId());
        assertThat(failure.get("failure_kind")).isEqualTo("UNEXPECTED");
        assertThat((String) failure.get("message")).contains("계산 로직 버그");
        assertThat((String) failure.get("stack_trace")).contains("IllegalStateException");
    }

    @Test
    @DisplayName("실패한 부분만 재실행 대상으로 읽고, 성공하면 해결로 표시한다")
    void supportsPartialRerun() throws Exception {
        SecurityRef ok = insertStock("000060", "정상");
        SecurityRef broken = insertStock("000061", "실패");

        BatchExecutionContext first = batchExecutions.start(
                JOB_NAME, TARGET_DATE, BatchExecutionRecorder.TriggeredBy.SCHEDULER, 2, BatchExecutionRecorder.DeclaredScope.FULL);
        for (SecurityRef security : List.of(ok, broken)) {
            first.process(security, CollectionPhase.PRICE, s -> {
                if (s.stockId() == broken.stockId()) {
                    throw new DataSourceException(
                            DataSourceException.Kind.TRANSIENT, "stub-source", "일시적 오류");
                }
                return 1L;
            });
        }
        first.finish();

        // 재실행 대상은 실패한 종목뿐이다 — 성공한 종목의 API를 다시 호출하지 않는다.
        List<StockFailure> targets = recorder.unresolvedFailures(first.executionId());
        assertThat(targets).containsExactly(new StockFailure(broken.stockId(), CollectionPhase.PRICE));

        BatchExecutionContext retry = batchExecutions.startRetry(
                JOB_NAME, TARGET_DATE, targets.size(), first.executionId());
        retry.process(broken, CollectionPhase.PRICE, s -> 1L);
        assertThat(retry.finish()).isEqualTo(BatchStatus.SUCCESS);

        // 해결 표시를 손으로 부르지 않는다 — process가 성공하면 그 자리에서 닫힌다.
        // 예전에는 잡이 직접 불러야 했고, 그래서 부르지 않는 잡의 실패는 영영 남았다.
        // 해결된 실패는 미해결 목록에서 빠진다 — 아니면 "미해결 건수"가 영원히 줄지 않는다.
        assertThat(recorder.unresolvedFailures(first.executionId())).isEmpty();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT retried_from_id FROM batch_execution WHERE id = ?",
                Long.class, retry.executionId())).isEqualTo(first.executionId());
    }

    @Test
    @DisplayName("같은 종목이 여러 번 실패해도 기록이 부풀지 않는다 — 재시도 루프가 행을 늘리면 안 된다")
    void keepsFailureRecordIdempotent() throws Exception {
        SecurityRef security = insertStock("000070", "종목");

        BatchExecutionContext execution = batchExecutions.start(
                JOB_NAME, TARGET_DATE, BatchExecutionRecorder.TriggeredBy.SCHEDULER, 1, BatchExecutionRecorder.DeclaredScope.FULL);
        for (int attempt = 0; attempt < 3; attempt++) {
            execution.process(security, CollectionPhase.PRICE, s -> {
                throw new DataSourceException(
                        DataSourceException.Kind.TRANSIENT, "stub-source", "실패");
            });
        }
        execution.finish();

        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM batch_execution_failure WHERE execution_id = ?",
                Integer.class, execution.executionId())).isEqualTo(1);
    }

    @Test
    @DisplayName("쿼터 소진 중단은 ABORTED다 — 실패가 아니라 정상적인 일시정지다")
    void abortIsNotFailure() throws Exception {
        SecurityRef security = insertStock("000080", "종목");

        BatchExecutionContext execution = batchExecutions.start(
                JOB_NAME, TARGET_DATE, BatchExecutionRecorder.TriggeredBy.SCHEDULER, 100, BatchExecutionRecorder.DeclaredScope.FULL);
        execution.process(security, CollectionPhase.PRICE, s -> 1L);
        execution.abort("일일 호출 한도 소진");

        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM batch_execution WHERE id = ?",
                String.class, execution.executionId())).isEqualTo("ABORTED");
        assertThat(alertPort.events.get(0).severity()).isEqualTo(AlertSeverity.WARNING);
        assertThat(alertPort.events.get(0).body()).contains("이어서 처리한다");
    }

    private SecurityRef insertStock(String ticker, String name) {
        Long id = jdbcTemplate.queryForObject("""
                INSERT INTO stock (market_code, ticker, name)
                VALUES ('KOSPI', ?, ?)
                RETURNING id
                """, Long.class, ticker, name);
        createdStockIds.add(id);
        return new SecurityRef(id, "KOSPI", ticker);
    }

    private String nameOf(long stockId) {
        return jdbcTemplate.queryForObject(
                "SELECT name FROM stock WHERE id = ?", String.class, stockId);
    }

    /** 알림을 실제로 보내는 대신 모아둔다. 요약 한 건인지 세어보기 위함이다. */
    static class RecordingAlertPort implements AlertPort {
        final List<AlertEvent> events = new ArrayList<>();

        @Override
        public void send(AlertEvent event) {
            events.add(event);
        }
    }

    @TestConfiguration
    static class RecordingAlertConfig {
        @Bean
        @Primary
        RecordingAlertPort recordingAlertPort() {
            return new RecordingAlertPort();
        }
    }
}
