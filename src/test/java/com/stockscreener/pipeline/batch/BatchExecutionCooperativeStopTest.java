package com.stockscreener.pipeline.batch;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.stockscreener.notification.AlertSeverity;
import com.stockscreener.pipeline.datasource.SecurityRef;
import com.stockscreener.support.TestDatabaseGuard;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDate;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 종료가 시작되면 <b>종목 루프가 다음 종목으로 넘어가지 않는지</b> 본다.
 *
 * <p>이 테스트가 따로 있는 이유 — {@link BatchExecutionShutdownRaceTest}로는 이것을 재지 못한다.
 * 그쪽 가짜 작업은 60초를 자다가 <b>인터럽트로</b> 끝나고, 그 인터럽트가 닫힌 풀을 만나
 * 기록 단계에서 죽으므로 루프가 <b>고치기 전에도</b> 2회차에 못 간다. 즉 진입 횟수를 세도
 * 1 → 1이라 갈리지 않는다. 그 테스트가 재는 것은 {@code close()} 소요이고 그 밴드가
 * ADR-0078의 유예 상한(15초)과 {@code ops/stop.sh}의 대기(45초)를 지키는 유일한 동적
 * 펜스이므로 <b>손대지 않는다.</b>
 *
 * <p>그래서 여기서는 <b>컨텍스트를 닫지 않고</b> {@code closeOpenExecutions()}만 직접 부른다.
 * 종료 유예를 안 태우므로 빌드에 시간이 붙지 않고, 커넥션 풀이 살아 있어 <b>루프가 계속
 * 돌 수 있는 상태</b>가 된다 — 협조적 중단이 없으면 5회 전부 도는 것이 그래서 대조군이 된다.
 *
 * <p>MUST: {@code @Transactional}을 쓰지 않는다. 기록이 {@code REQUIRES_NEW}로 커밋되는 것이
 * 전제다({@link StockFailureIsolationTest}와 같은 이유).
 *
 * <p>MUST: 워커를 반드시 거둔다. 안 거두면 정리된 행을 향해 계속 돌며 무관한 다음 테스트를
 * 깨뜨린다({@link BatchExecutionShutdownRaceTest}가 같은 이유를 적고 있다).
 *
 * <p>IMPORTANT — {@code closeOpenExecutions()}는 <b>이 컨텍스트의 열린 실행 전부</b>를 닫는다
 * ({@link BatchExecutions}의 {@code open}이 싱글턴 맵이다). 같은 캐시 컨텍스트를 쓰는 다른
 * 테스트가 실행을 열어 둔 채 끝내면 그것까지 ABORTED가 된다. 지금은 테스트가 순차로 돌고
 * 그런 테스트가 없어 성립하지만, <b>병렬 실행을 켜면 이 전제가 깨진다.</b>
 */
@SpringBootTest
@ActiveProfiles("test")
@Import({TestDatabaseGuard.class, StockFailureIsolationTest.RecordingAlertConfig.class})
class BatchExecutionCooperativeStopTest {

    private static final String JOB_NAME = "test-cooperative-stop";
    private static final String TICKER = "999902";
    private static final LocalDate TARGET_DATE = LocalDate.of(2026, 8, 5);

    /** 루프가 돌 종목 수. 협조적 중단이 없으면 이 수만큼 전부 돈다. */
    private static final int TASK_COUNT = 5;

    @Autowired
    private BatchExecutions batchExecutions;

    @Autowired
    private StockFailureIsolationTest.RecordingAlertPort alertPort;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @AfterEach
    void cleanUp() {
        // 실패 행은 batch_execution에 CASCADE로 달려 있다. 실행 → 종목 순이어야 stock의
        // FK가 걸리지 않는다(.claude/rules/test.md).
        jdbcTemplate.update("DELETE FROM batch_execution WHERE job_name = ?", JOB_NAME);
        jdbcTemplate.update("DELETE FROM stock WHERE ticker = ?", TICKER);
        alertPort.events.clear();
    }

    @Test
    @DisplayName("종료가 시작되면 종목 루프가 다음 종목으로 넘어가지 않는다")
    void stopsTheStockLoopAtTheNextBoundary() throws Exception {
        long stockId = jdbcTemplate.queryForObject("""
                INSERT INTO stock (market_code, ticker, name)
                VALUES ('KOSPI', ?, ?)
                RETURNING id
                """, Long.class, TICKER, "협조중단");
        SecurityRef security = new SecurityRef(stockId, "KOSPI", TICKER);

        BatchExecutionContext execution = batchExecutions.start(
                JOB_NAME, TARGET_DATE, BatchExecutionRecorder.TriggeredBy.MANUAL, TASK_COUNT);

        AtomicInteger entries = new AtomicInteger();
        AtomicReference<Throwable> workerError = new AtomicReference<>();
        CountDownLatch firstEntered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);

        Thread worker = new Thread(() -> {
            try {
                for (int i = 0; i < TASK_COUNT; i++) {
                    execution.process(security, CollectionPhase.PRICE, target -> {
                        // 첫 종목만 붙잡아 둔다. 그 사이에 종료가 시작되고, 놓아준 뒤
                        // 루프가 다음 종목으로 가는지가 이 테스트의 질문이다.
                        if (entries.incrementAndGet() == 1) {
                            firstEntered.countDown();
                            awaitRelease(release);
                        }
                        return 1L;
                    });
                }
                execution.finish();
            } catch (Throwable t) {
                // MUST NOT: 삼키지 않는다. 워커 스레드의 예외는 아무 데도 안 남으므로
                // 붙잡아 본문에서 단언한다 — 삼키면 무엇이 루프를 끊었는지가 사라진다.
                workerError.set(t);
            }
        }, "cooperative-stop-worker");
        worker.start();

        try {
            assertThat(firstEntered.await(20, TimeUnit.SECONDS))
                    .as("워커가 첫 종목에 못 들어갔다 — 이 회차는 아무것도 재지 못했다")
                    .isTrue();

            batchExecutions.closeOpenExecutions();
        } finally {
            release.countDown();
            worker.join(TimeUnit.SECONDS.toMillis(30));
        }
        assertThat(worker.isAlive())
                .as("워커를 거두지 못했다 — 정리된 행을 향해 계속 돌며 다음 테스트를 깨뜨린다")
                .isFalse();

        assertThat(entries.get())
                .as("종료가 시작됐는데 루프가 %d개를 마저 돌았다 — 그만큼 쿼터를 더 태운다",
                        TASK_COUNT)
                .isEqualTo(1);
        assertThat(workerError.get())
                .as("루프를 끊은 것이 협조적 중단 신호가 아니다 — 다른 이유로 끊겼으면 "
                        + "이 테스트는 원하는 것을 재지 않았다")
                .isInstanceOf(BatchShutdownException.class);
        assertThat(statusOf(execution.executionId())).isEqualTo("ABORTED");
        assertThat(noteOf(execution.executionId())).contains("프로세스가 종료돼 중단됐다");
        assertThat(alertPort.events)
                .as("종료 마감은 알리지 않기로 한 경로다(ADR-0078)")
                .isEmpty();
    }

    @Test
    @DisplayName("구간·계획 신고도 끊는다 — 종목 루프만 막으면 루프 뒤 단계가 그대로 돈다")
    void gatesStageAndPlanToo() {
        BatchExecutionContext execution = batchExecutions.start(
                JOB_NAME, TARGET_DATE, BatchExecutionRecorder.TriggeredBy.MANUAL, 1);

        batchExecutions.closeOpenExecutions();

        // 이 둘이 안 막히면 DailyUpdateJob의 백분위·업종 상대값·워치리스트가 종료 뒤에도
        // 그대로 돈다 — 그 셋은 process()를 지나지 않으므로 종목 게이트가 안 닿는다.
        assertThatThrownBy(() -> execution.stage("백분위 계산"))
                .isInstanceOf(BatchShutdownException.class);
        assertThatThrownBy(() -> execution.plan(CollectionPhase.INDICATOR, 350))
                .isInstanceOf(BatchShutdownException.class);
        assertThatThrownBy(execution::abortIfAbandoned)
                .isInstanceOf(BatchShutdownException.class);
    }

    @Test
    @DisplayName("종료 중 알림은 막지 않고 로그로 내린다 — 막기만 하면 기록이 아예 사라진다")
    void degradesOperatorAlertToLogWhileShuttingDown() {
        BatchExecutionContext execution = batchExecutions.start(
                JOB_NAME, TARGET_DATE, BatchExecutionRecorder.TriggeredBy.MANUAL, 1);

        execution.alertOperator(AlertSeverity.WARNING, "종료 전", "이것은 나가야 한다");
        assertThat(alertPort.events)
                .as("평상시 알림까지 막으면 이 가드는 기능을 죽인 것이다")
                .hasSize(1);

        batchExecutions.closeOpenExecutions();

        ListAppender<ILoggingEvent> logs = new ListAppender<>();
        logs.start();
        Logger contextLogger = (Logger) LoggerFactory.getLogger(BatchExecutionContext.class);
        contextLogger.addAppender(logs);
        try {
            execution.alertOperator(AlertSeverity.WARNING, "기업 행위 판정 대기", "후보 3건");
        } finally {
            contextLogger.detachAppender(logs);
        }

        // 동기 전송이고 웹훅이 최악 15초라 종료 예산을 먹는다(ADR-0078).
        assertThat(alertPort.events)
                .as("종료 경로에서 알림 포트를 불렀다 — 그 대기가 종료 예산에 그대로 얹힌다")
                .hasSize(1);
        // MUST: 여기가 이 가드의 절반이다. 막기만 하면 이 알림에 대응하는 개별 로그가 없는
        // 자리에서 사실이 통째로 사라진다 — 「기업 행위 판정 대기」가 바로 그 자리다.
        assertThat(logs.list)
                .as("알림을 막았는데 로그도 안 남겼다 — 그 사실이 어디에도 없게 된다")
                .anySatisfy(event -> {
                    assertThat(event.getFormattedMessage()).contains("기업 행위 판정 대기");
                    assertThat(event.getFormattedMessage()).contains("후보 3건");
                });
    }

    @Test
    @DisplayName("종료 신호는 예상하지 못한 실패로 세지 않는다 — 세면 이중 마감 경고가 종료마다 난다")
    void shutdownSignalIsNotAnUnexpectedFailure() {
        BatchExecutionContext execution = batchExecutions.start(
                JOB_NAME, TARGET_DATE, BatchExecutionRecorder.TriggeredBy.MANUAL, 1);
        batchExecutions.closeOpenExecutions();

        // 잡 열한 개가 일반 catch (RuntimeException)에서 이렇게 부른다.
        execution.failUnexpectedly(new BatchShutdownException("신호"));

        assertThat(statusOf(execution.executionId()))
                .as("종료 신호가 FAILED로 덮었다")
                .isEqualTo("ABORTED");
        assertThat(alertPort.events)
                .as("종료 신호로 '예상하지 못한 오류' 알림이 나갔다")
                .isEmpty();
    }

    /**
     * {@code StockTask}가 {@code InterruptedException}을 선언하지 않으므로 여기서 받는다.
     * 인터럽트는 이 테스트의 시나리오가 아니다 — 오면 플래그를 복원하고 실패로 만든다.
     */
    private static void awaitRelease(CountDownLatch release) {
        try {
            if (!release.await(20, TimeUnit.SECONDS)) {
                throw new IllegalStateException("첫 종목이 풀려나지 못했다 — 이 회차는 무의미하다");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("첫 종목이 인터럽트됐다", e);
        }
    }

    private String statusOf(long executionId) {
        return jdbcTemplate.queryForObject(
                "SELECT status FROM batch_execution WHERE id = ?", String.class, executionId);
    }

    private String noteOf(long executionId) {
        return jdbcTemplate.queryForObject(
                "SELECT note FROM batch_execution WHERE id = ?", String.class, executionId);
    }
}
