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
        jdbcTemplate.update("DELETE FROM batch_execution WHERE job_name = ? AND note = 'BatchExecutionClosingTest'",
                IndicatorBackfillJob.ORIGIN_JOB_NAME);
    }

    @Test
    @DisplayName("프로세스가 내려가면 열려 있던 실행이 ABORTED로 마감된다")
    void closesOpenExecutionOnShutdown() {
        BatchExecutionContext execution = batchExecutions.start(
                JOB_NAME, TARGET_DATE, BatchExecutionRecorder.TriggeredBy.MANUAL, 1, BatchExecutionRecorder.Scope.FULL);
        assertThat(statusOf(execution.executionId())).isEqualTo("RUNNING");

        batchExecutions.closeOpenExecutions();

        assertThat(statusOf(execution.executionId())).isEqualTo("ABORTED");
        assertThat(noteOf(execution.executionId())).contains("프로세스가 종료돼 중단됐다");
    }

    @Test
    @DisplayName("마감하면 열린 목록에서 빠진다 — 안 빠지면 종료 훅이 성공한 실행을 덮으려 든다")
    void removesFinishedExecutionFromOpenList() {
        BatchExecutionContext execution = batchExecutions.start(
                JOB_NAME, TARGET_DATE, BatchExecutionRecorder.TriggeredBy.MANUAL, 1, BatchExecutionRecorder.Scope.FULL);

        execution.finish();

        // MUST: DB 상태가 아니라 목록을 묻는다. 상태로 물으면 배선(onTerminal)이 빠져도
        // updateTerminalState의 `AND status = 'RUNNING'`이 SUCCESS를 지켜 **초록이 나온다** —
        // 이 펜스는 실제로 그 상태로 오래 있었다. 종단 플래그가 생긴 뒤로는 그것까지
        // 덮어 주므로 더더욱 목록을 직접 봐야 한다.
        assertThat(batchExecutions.isOpen(execution.executionId()))
                .as("마감했는데 열린 목록에 남아 있다 — onTerminal 배선이 끊겼다")
                .isFalse();

        batchExecutions.closeOpenExecutions();

        assertThat(statusOf(execution.executionId())).isEqualTo("SUCCESS");
    }

    @Test
    @DisplayName("마감은 남아 있던 사유를 지운다 — SUCCESS인데 중단 사유가 붙어 있으면 안 된다")
    void finishClearsStaleNote() {
        BatchExecutionContext execution = batchExecutions.start(
                JOB_NAME, TARGET_DATE, BatchExecutionRecorder.TriggeredBy.MANUAL, 1, BatchExecutionRecorder.Scope.FULL);
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
    @DisplayName("IMPORTANT — DB 가드: 다른 프로세스가 마감된 행을 다시 마감하지 못한다")
    void databaseGuardRefusesToReopenFinishedExecution() {
        BatchExecutionContext execution = batchExecutions.start(
                JOB_NAME, TARGET_DATE, BatchExecutionRecorder.TriggeredBy.MANUAL, 1, BatchExecutionRecorder.Scope.FULL);
        execution.finish();

        // MUST: recorder를 직접 부른다. 컨텍스트를 거치면 인메모리 종단 플래그가 먼저 삼켜
        // **이 단언이 DB 가드를 전혀 재지 않게 된다.** 그 가드가 지키는 것은 다른 JVM이
        // 남긴 행이므로(BatchExecutionSweeper와의 경합) 컨텍스트 밖에서 재야 옳다.
        batchExecutions.recorder().abort(execution.executionId(),
                new BatchProgress(99, 0, 999L, "나중에 온 마감", java.util.List.of()),
                "나중에 온 마감");

        assertThat(statusOf(execution.executionId())).isEqualTo("SUCCESS");
        assertThat(noteOf(execution.executionId())).isNull();
    }

    @Test
    @DisplayName("IMPORTANT — 종단 플래그: 뒤늦은 마감은 DB도 알림도 건드리지 않는다")
    void terminalFlagSwallowsLateClosingWithinTheSameContext() {
        BatchExecutionContext execution = batchExecutions.start(
                JOB_NAME, TARGET_DATE, BatchExecutionRecorder.TriggeredBy.MANUAL, 1, BatchExecutionRecorder.Scope.FULL);

        // 종료 훅이 이긴 상황이다. 그 뒤 워커가 자기 루프를 마치고 마감하러 온다.
        batchExecutions.closeOpenExecutions();
        BatchStatus late = execution.finish();

        assertThat(statusOf(execution.executionId()))
                .as("뒤늦은 마감이 종료 사유를 덮었다")
                .isEqualTo("ABORTED");
        assertThat(noteOf(execution.executionId()))
                .as("종료 사유가 지워졌다 — 알림을 안 보내므로 이 문장이 유일한 설명이다")
                .contains("프로세스가 종료돼 중단됐다");
        assertThat(late)
                .as("진 쪽이 먼저 기록된 상태를 돌려주지 않으면 호출부가 성공으로 읽는다")
                .isEqualTo(BatchStatus.ABORTED);
    }

    @Test
    @DisplayName("도는 중에도 진척이 보인다 — 마감 때만 쓰면 56분짜리 배치가 내내 0으로 보인다")
    void writesProgressWhileRunning() {
        BatchExecutionContext execution = batchExecutions.start(
                JOB_NAME, TARGET_DATE, BatchExecutionRecorder.TriggeredBy.MANUAL, 2, BatchExecutionRecorder.Scope.FULL);

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
    @DisplayName("웹 기동 스윕은 사슬의 좁힌 화해 ①(GAP_FILL)도 닫는다 — 리밸런싱 꼬리의 GAP_FILL은 소유가 갈리지 않아 안 닫는다")
    void startupSweepClosesTheChainsNarrowedReconcile() {
        long chainOrigin = insertRunning(IndicatorBackfillJob.ORIGIN_JOB_NAME, "GAP_FILL", "now() - interval '1 hour'");
        long tail = insertRunning("GAP_FILL", "now() - interval '1 hour'");

        sweeper.sweepOnStartup();
        long bootstrapChainOrigin = insertRunning(IndicatorBackfillJob.ORIGIN_JOB_NAME, "GAP_FILL",
                "now() - interval '1 hour'");
        sweeper.sweepBootstrapOrphans();

        assertThat(statusOf(chainOrigin)).as("크래시로 남은 사슬의 ①을 아무도 안 닫으면 영원히 RUNNING이다")
                .isEqualTo("ABORTED");
        assertThat(statusOf(tail)).as("꼬리는 CLI에서도 돌아 웹이 닫으면 살아 있는 행을 마감할 수 있다")
                .isEqualTo("RUNNING");
        assertThat(statusOf(bootstrapChainOrigin)).as("부트스트랩 스윕은 웹의 행을 안 닫는다")
                .isEqualTo("RUNNING");
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
        return insertRunning(JOB_NAME, triggeredBy, startedAtExpr);
    }

    private long insertRunning(String jobName, String triggeredBy, String startedAtExpr) {
        return jdbcTemplate.queryForObject("""
                INSERT INTO batch_execution
                    (job_name, target_date, status, triggered_by, target_count, started_at, note)
                VALUES (?, ?, 'RUNNING', ?, 1, %s, 'BatchExecutionClosingTest')
                RETURNING id
                """.formatted(startedAtExpr), Long.class, jobName, TARGET_DATE, triggeredBy);
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
