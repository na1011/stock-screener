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
import java.util.List;

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
        assertThat(finishedAtIsNull(execution.executionId())).isFalse();
    }

    @Test
    @DisplayName("정상 마감된 실행은 종료 훅이 다시 건드리지 않는다")
    void doesNotTouchAlreadyFinished() {
        BatchExecutionContext execution = batchExecutions.start(
                JOB_NAME, TARGET_DATE, BatchExecutionRecorder.TriggeredBy.MANUAL, 1);
        execution.finish();
        assertThat(statusOf(execution.executionId())).isEqualTo("SUCCESS");

        batchExecutions.closeOpenExecutions();

        // 마감 뒤 목록에서 빠지지 않으면 SUCCESS가 ABORTED로 덮인다.
        assertThat(statusOf(execution.executionId())).isEqualTo("SUCCESS");
    }

    @Test
    @DisplayName("종료 훅조차 돌지 못한 오래된 RUNNING 행은 스위퍼가 마감한다")
    void sweepsStaleRunningRows() {
        long stale = insertRunningStartedHoursAgo(BatchExecutionSweeper.STALE_AFTER_HOURS + 1);
        long fresh = insertRunningStartedHoursAgo(0);

        sweeper.sweepStale();

        assertThat(statusOf(stale)).isEqualTo("ABORTED");
        // 도는 중일 수 있는 실행은 건드리지 않는다. 잘못 마감해도 finish()가 덮어 스스로
        // 교정되지만, 임계값 안쪽까지 닫으면 화면이 매번 거짓을 말한다.
        assertThat(statusOf(fresh)).isEqualTo("RUNNING");
    }

    private long insertRunningStartedHoursAgo(int hours) {
        return jdbcTemplate.queryForObject("""
                INSERT INTO batch_execution
                    (job_name, target_date, status, triggered_by, target_count, started_at)
                VALUES (?, ?, 'RUNNING', 'MANUAL', 1, now() - make_interval(hours => ?))
                RETURNING id
                """, Long.class, JOB_NAME, TARGET_DATE, hours);
    }

    private String statusOf(long executionId) {
        return jdbcTemplate.queryForObject(
                "SELECT status FROM batch_execution WHERE id = ?", String.class, executionId);
    }

    private boolean finishedAtIsNull(long executionId) {
        List<Long> rows = jdbcTemplate.queryForList(
                "SELECT id FROM batch_execution WHERE id = ? AND finished_at IS NULL",
                Long.class, executionId);
        return !rows.isEmpty();
    }
}
