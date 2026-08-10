package com.stockscreener.pipeline.batch;

import com.stockscreener.support.TestDatabaseGuard;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 마감되지 못한 {@code RUNNING} 행이 남지 않는지 검증한다.
 *
 * <p>이것이 없으면 <b>비정상 종료마다 영구 RUNNING 행이 하나씩 쌓인다.</b> 실제로 10건이
 * 그렇게 남았고, 관리자 화면은 "도는 중"인데 잡 카탈로그는 유휴인 모순된 상태가 됐다.
 *
 * <p>IMPORTANT — {@code @Transactional}을 <b>쓰지 않는다.</b> 기록이 {@code REQUIRES_NEW}로
 * 커밋되는 것이 전제라, 바깥 트랜잭션으로 감싸면 검증하려는 성질이 사라진다
 * ({@code StockFailureIsolationTest}와 같은 이유).
 *
 * <p>종료 훅이 <b>실제 컨텍스트 종료 경로에서</b> 도는지는 여기서 검증하지 않는다 —
 * {@link BatchExecutionShutdownHookTest}가 컨텍스트를 따로 띄워 답한다. 이 클래스가 보는
 * 것은 마감 로직 자체다.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestDatabaseGuard.class)
class BatchExecutionClosingTest {

    private static final String JOB_NAME = "test-closing";
    private static final LocalDate TARGET_DATE = LocalDate.of(2026, 8, 5);

    @Autowired
    private BatchExecutions batchExecutions;

    @Autowired
    private BatchExecutionSweeper sweeper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @AfterEach
    void cleanUp() {
        jdbcTemplate.update("DELETE FROM batch_execution WHERE job_name = ?", JOB_NAME);
    }

    @Test
    @DisplayName("프로세스가 내려가면 열려 있던 실행이 ABORTED로 마감된다")
    void closesOpenExecutionOnShutdown() {
        BatchExecutionContext execution = batchExecutions.start(
                JOB_NAME, TARGET_DATE, BatchExecutionRecorder.TriggeredBy.MANUAL, 1);
        assertThat(statusOf(execution.executionId())).isEqualTo("RUNNING");

        batchExecutions.closeOpenExecutions();

        assertThat(statusOf(execution.executionId())).isEqualTo("ABORTED");
        assertThat(noteOf(execution.executionId())).contains("프로세스가 종료돼 중단됐다");
    }

    @Test
    @DisplayName("정상 마감된 실행은 종료 훅이 다시 건드리지 않는다")
    void doesNotTouchAlreadyFinished() {
        BatchExecutionContext execution = batchExecutions.start(
                JOB_NAME, TARGET_DATE, BatchExecutionRecorder.TriggeredBy.MANUAL, 1);
        execution.finish();

        batchExecutions.closeOpenExecutions();

        // 마감 뒤 목록에서 빠지지 않으면 SUCCESS가 ABORTED로 덮인다.
        assertThat(statusOf(execution.executionId())).isEqualTo("SUCCESS");
    }

    @Test
    @DisplayName("마감은 남아 있던 사유를 지운다 — SUCCESS인데 중단 사유가 붙어 있으면 안 된다")
    void finishClearsStaleNote() {
        BatchExecutionContext execution = batchExecutions.start(
                JOB_NAME, TARGET_DATE, BatchExecutionRecorder.TriggeredBy.MANUAL, 1);
        // 다른 기제(스위퍼 등)가 사유를 써 둔 상태를 만든다.
        jdbcTemplate.update("UPDATE batch_execution SET note = ? WHERE id = ?",
                "프로세스가 사라져 마감되지 못한 실행을 정리했다.", execution.executionId());

        execution.finish();

        assertThat(statusOf(execution.executionId())).isEqualTo("SUCCESS");
        assertThat(noteOf(execution.executionId()))
                .as("성공으로 마감했는데 '프로세스가 사라졌다'가 남아 있으면 화면이 거짓을 말한다")
                .isNull();
    }

    @Test
    @DisplayName("IMPORTANT — 마감된 실행은 두 번 마감되지 않는다. 먼저 쓴 결과가 이긴다")
    void refusesToReopenFinishedExecution() {
        BatchExecutionContext execution = batchExecutions.start(
                JOB_NAME, TARGET_DATE, BatchExecutionRecorder.TriggeredBy.MANUAL, 1);
        execution.finish();

        // 열린 목록에서 이미 빠졌지만, 다른 경로가 같은 id로 마감을 시도할 수 있다.
        // 막지 않으면 성공한 실행이 중단으로 덮이고, 화면에는 아무 단서도 남지 않는다.
        execution.abort("나중에 온 마감");

        assertThat(statusOf(execution.executionId())).isEqualTo("SUCCESS");
        assertThat(noteOf(execution.executionId())).isNull();
    }

    @Test
    @DisplayName("도는 중에도 진척이 보인다 — 마감 때만 쓰면 56분짜리 배치가 내내 0으로 보인다")
    void writesProgressWhileRunning() {
        BatchExecutionContext execution = batchExecutions.start(
                JOB_NAME, TARGET_DATE, BatchExecutionRecorder.TriggeredBy.MANUAL, 2);

        execution.plan(CollectionPhase.PRICE, 2);

        assertThat(statusOf(execution.executionId())).isEqualTo("RUNNING");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT planned_task_count FROM batch_execution WHERE id = ?",
                Integer.class, execution.executionId()))
                .as("분모는 마감을 기다리지 않는다 — 기다리면 도는 동안 분모가 비어 있다")
                .isEqualTo(2);
    }

    @Test
    @DisplayName("마감된 행에는 늦은 진척이 닿지 않는다 — 스위퍼가 닫은 뒤 원 프로세스가 살아 있는 경로가 있다")
    void ignoresProgressOnFinishedExecution() {
        long executionId = insertRunning("MANUAL", "now()");
        jdbcTemplate.update(
                "UPDATE batch_execution SET status = 'ABORTED', finished_at = now() WHERE id = ?",
                executionId);

        batchExecutions.recorder().progress(executionId,
                new BatchProgress(99, 0, 999L, "늦게 온 구간", java.util.List.of()));

        assertThat(statusOf(executionId)).isEqualTo("ABORTED");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT success_count FROM batch_execution WHERE id = ?", Integer.class, executionId))
                .as("마감된 행의 카운터가 되살아나면 finished_at이 있는데 진행 중인 모순이 생긴다")
                .isZero();
    }

    @Test
    @DisplayName("웹 기동 스윕은 이전 인스턴스의 ADMIN·SCHEDULER 행만 닫는다")
    void startupSweepClosesOnlyWebOwnedRows() {
        long admin = insertRunning("ADMIN", "now() - interval '1 hour'");
        long scheduler = insertRunning("SCHEDULER", "now() - interval '1 hour'");
        long manual = insertRunning("MANUAL", "now() - interval '1 hour'");
        long fresh = insertRunning("ADMIN", "now()");

        sweeper.sweepOnStartup();

        assertThat(statusOf(admin)).isEqualTo("ABORTED");
        assertThat(statusOf(scheduler)).isEqualTo("ABORTED");
        // 부트스트랩 JVM의 적재는 몇 시간을 돌 수 있다. 종류가 다르므로 애초에 대상이 아니다.
        assertThat(statusOf(manual)).isEqualTo("RUNNING");
        // 이 JVM이 뜬 뒤에 시작된 행은 살아 있는 잡의 것이다.
        assertThat(statusOf(fresh)).isEqualTo("RUNNING");
    }

    @Test
    @DisplayName("부트스트랩 스윕은 MANUAL 행만 닫는다")
    void bootstrapSweepClosesOnlyManualRows() {
        long manual = insertRunning("MANUAL", "now() - interval '1 hour'");
        long admin = insertRunning("ADMIN", "now() - interval '1 hour'");

        sweeper.sweepBootstrapOrphans();

        assertThat(statusOf(manual)).isEqualTo("ABORTED");
        assertThat(statusOf(admin)).isEqualTo("RUNNING");
    }

    private long insertRunning(String triggeredBy, String startedAtExpr) {
        return jdbcTemplate.queryForObject("""
                INSERT INTO batch_execution
                    (job_name, target_date, status, triggered_by, target_count, started_at)
                VALUES (?, ?, 'RUNNING', ?, 1, %s)
                RETURNING id
                """.formatted(startedAtExpr), Long.class, JOB_NAME, TARGET_DATE, triggeredBy);
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
