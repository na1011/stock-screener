package com.stockscreener.pipeline.batch;

import com.stockscreener.pipeline.datasource.DataSourceException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.LocalDate;
import java.util.List;

/**
 * 배치 실행 이력과 종목 단위 실패를 기록한다.
 *
 * <p>IMPORTANT — 실패 기록 메서드는 모두 {@link Propagation#REQUIRES_NEW}다. 작업
 * 트랜잭션 안에서 기록하면 그 트랜잭션이 롤백될 때 기록도 함께 사라져, <b>실패가 조용히
 * 증발한다</b>. 그러면 관리자 화면에는 아무 일도 없었던 것으로 보인다.
 * 이것이 {@code data-pipeline.md}의 "실패를 조용히 넘기지 않는다"를 지키는 실제 수단이다.
 */
@Service
public class BatchExecutionRecorder {

    private static final Logger log = LoggerFactory.getLogger(BatchExecutionRecorder.class);

    /** 스택트레이스 저장 상한. 이보다 길면 잘라낸다 — 원인 파악에 앞부분이면 충분하다. */
    private static final int MAX_STACK_TRACE_LENGTH = 8000;

    private final JdbcTemplate jdbcTemplate;

    public BatchExecutionRecorder(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 실행을 시작하고 id를 받는다.
     *
     * <p>{@code REQUIRES_NEW}인 이유는 실행 행이 즉시 커밋되어야 하기 때문이다. 종목별
     * 실패 기록이 이 id를 FK로 참조하는데, 실행 행이 아직 커밋되지 않았으면 참조가 깨진다.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public long start(String jobName, LocalDate targetDate, TriggeredBy triggeredBy, int targetCount) {
        return jdbcTemplate.queryForObject("""
                INSERT INTO batch_execution
                    (job_name, target_date, status, triggered_by, target_count, started_at)
                VALUES (?, ?, 'RUNNING', ?, ?, now())
                RETURNING id
                """, Long.class, jobName, targetDate, triggeredBy.name(), targetCount);
    }

    /** 재실행으로 시작한다. 어느 실행을 다시 돌린 것인지 남겨야 반복 실패가 보인다. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public long startRetry(String jobName, LocalDate targetDate, int targetCount, long retriedFromId) {
        return jdbcTemplate.queryForObject("""
                INSERT INTO batch_execution
                    (job_name, target_date, status, triggered_by, target_count,
                     retried_from_id, started_at)
                VALUES (?, ?, 'RUNNING', 'ADMIN', ?, ?, now())
                RETURNING id
                """, Long.class, jobName, targetDate, targetCount, retriedFromId);
    }

    /** Spring Batch 실행과 연결한다. 상세 스텝 이력이 필요하면 이 id로 조인한다. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void linkJobExecution(long executionId, long jobExecutionId) {
        jdbcTemplate.update(
                "UPDATE batch_execution SET job_execution_id = ? WHERE id = ?",
                jobExecutionId, executionId);
    }

    /**
     * 종목 하나의 실패를 기록한다.
     *
     * <p>{@code ON CONFLICT}로 갱신하는 이유는 재시도 루프가 같은 종목·단계를 여러 번
     * 기록해 행을 부풀리지 않게 하기 위해서다. 마지막 실패 사유가 남는다.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordFailure(long executionId, long stockId, CollectionPhase phase,
                              String failureKind, String sourceName, String message,
                              Throwable cause) {
        jdbcTemplate.update("""
                INSERT INTO batch_execution_failure
                    (execution_id, stock_id, failed_phase, source_name, failure_kind,
                     message, stack_trace, resolved, occurred_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, false, now())
                ON CONFLICT (execution_id, stock_id, failed_phase) DO UPDATE SET
                    source_name  = EXCLUDED.source_name,
                    failure_kind = EXCLUDED.failure_kind,
                    message      = EXCLUDED.message,
                    stack_trace  = EXCLUDED.stack_trace,
                    resolved     = false,
                    occurred_at  = now()
                """,
                executionId, stockId, phase.name(), sourceName, failureKind,
                describe(message, cause), stackTraceOf(cause));
    }

    /** 어댑터가 던진 실패를 기록한다. 종류와 소스 이름이 예외에 들어 있다. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordFailure(long executionId, long stockId, CollectionPhase phase,
                              DataSourceException exception) {
        recordFailure(executionId, stockId, phase, exception.kind().name(),
                exception.sourceName(), exception.getMessage(), exception);
    }

    /**
     * 예상하지 못한 실패를 기록한다.
     *
     * <p>{@code UNEXPECTED}로 따로 분류하는 이유는 이것이 수집 실패가 아니라 <b>우리 코드의
     * 버그</b>일 가능성이 높기 때문이다. 예상된 수집 실패와 섞이면 버그가 통계에 묻힌다.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordUnexpectedFailure(long executionId, long stockId, CollectionPhase phase,
                                        Throwable cause) {
        recordFailure(executionId, stockId, phase, "UNEXPECTED", null,
                cause.getClass().getSimpleName() + ": " + cause.getMessage(), cause);
    }

    /**
     * 아직 도는 중인 실행의 진척을 남긴다.
     *
     * <p>IMPORTANT — 이것이 없으면 카운터가 <b>마감할 때 한 번만</b> 기록되어, 56분짜리 백필이
     * 내내 {@code 0/43400}으로 보이다가 끝나는 순간 튄다. 그 화면으로는 "돌고 있다"와
     * "멎었다"를 구분할 수 없다.
     *
     * <p>MUST: {@code status = 'RUNNING'}인 행만 갱신한다. 이미 마감된 행에 늦은 진척이 닿으면
     * {@code finished_at}이 있는 채로 카운터만 되살아나는데, 그 조합은 아무 데서도 검증되지
     * 않는다. 스위퍼가 고아 행을 닫아 둔 뒤 원래 프로세스가 살아 있는 경로가 실재한다.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void progress(long executionId, BatchProgress progress) {
        int updated = jdbcTemplate.update("""
                UPDATE batch_execution SET
                    success_count      = ?,
                    failure_count      = ?,
                    written_row_count  = ?,
                    planned_task_count = ?,
                    stage_label        = ?,
                    progress_at        = now()
                WHERE id = ? AND status = 'RUNNING'
                """, progress.successCount(), progress.failureCount(), progress.writtenRowCount(),
                progress.plannedTaskCount(), progress.stageLabel(), executionId);

        // 마감된 행에는 단계 집계도 쓰지 않는다. 위 UPDATE와 갈라지면 합계와 단계별이 어긋난다.
        if (updated > 0) {
            savePhases(executionId, progress.phases());
        }
    }

    /**
     * 실행을 마감한다. 실패 건수에 따라 상태가 갈린다.
     *
     * <p>상태를 호출부가 정하지 않고 여기서 유도하는 이유는, 판단이 여러 잡에 흩어지면
     * 어떤 잡은 부분 실패를 SUCCESS로 마감하게 되기 때문이다.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public BatchStatus finish(long executionId, BatchProgress progress) {
        BatchStatus status =
                progress.failureCount() == 0 ? BatchStatus.SUCCESS : BatchStatus.PARTIAL_SUCCESS;
        updateTerminalState(executionId, status, progress, null);
        return status;
    }

    /** 배치 자체가 실패했을 때 마감한다. 여기까지 처리된 건수는 그대로 남긴다. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void fail(long executionId, BatchProgress progress, String note) {
        updateTerminalState(executionId, BatchStatus.FAILED, progress, note);
    }

    /**
     * 외부 요인으로 중단했을 때 마감한다.
     *
     * <p>일일 쿼터 소진이 대표적이다. 이것은 실패가 아니라 정상적인 일시정지이며,
     * 다음 실행이 이어서 처리한다.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void abort(long executionId, BatchProgress progress, String note) {
        updateTerminalState(executionId, BatchStatus.ABORTED, progress, note);
    }

    /**
     * 종단 상태로 마감한다.
     *
     * <p>MUST: {@code note}를 <b>덮어쓴다.</b> 예전에는 {@code COALESCE(?, note)}라 null이 오면
     * 기존 값을 보존했는데, 그러면 {@link #finish}가 성공으로 마감해도 다른 기제가 써 둔
     * 사유가 남는다 — {@code SUCCESS}인데 "프로세스가 사라져 마감되지 못했다"고 적힌 행이
     * 만들어지고, 그것은 이 계층이 없애려던 모순과 같은 종류다. 마감한 쪽이 사유의 주인이다.
     *
     * <p>{@code stage_label}은 <b>비운다.</b> "2026-05-12 · 62/124거래일"이 끝난 실행에 남아
     * 있으면 거기서 멈춘 것처럼 읽힌다 — 어디까지 갔는지는 카운터가 답한다.
     *
     * <p>MUST: {@code RUNNING}인 행만 마감한다. 이미 마감된 행을 다시 마감하면 <b>먼저 쓴
     * 결과가 조용히 덮인다</b> — 성공으로 끝난 실행이 종료 훅에 의해 ABORTED가 되는 형태로
     * 실제 경로가 있었다. 되돌리기 어려운 종류라 제자리에서 막고, 어긋난 사실은 로그로 남긴다.
     */
    private void updateTerminalState(long executionId, BatchStatus status, BatchProgress progress,
                                     String note) {
        int updated = jdbcTemplate.update("""
                UPDATE batch_execution SET
                    status             = ?,
                    success_count      = ?,
                    failure_count      = ?,
                    written_row_count  = ?,
                    planned_task_count = ?,
                    stage_label        = NULL,
                    progress_at        = now(),
                    note               = ?,
                    finished_at        = now()
                WHERE id = ? AND status = 'RUNNING'
                """, status.name(), progress.successCount(), progress.failureCount(),
                progress.writtenRowCount(), progress.plannedTaskCount(), note, executionId);

        if (updated == 0) {
            log.warn("이미 마감된 배치 실행을 다시 마감하려 했다. 기존 기록을 유지한다. id={} 시도한 상태={}",
                    executionId, status);
            return;
        }
        savePhases(executionId, progress.phases());
    }

    /**
     * 단계별 집계를 갱신한다.
     *
     * <p>{@code ON CONFLICT}인 이유는 진척 기록이 같은 실행에 대해 몇 초마다 다시 오기
     * 때문이다. 누적이 아니라 <b>덮어쓰기</b>다 — 넘어오는 값이 이미 그 시점까지의 합계다.
     */
    private void savePhases(long executionId, List<PhaseProgress> phases) {
        for (PhaseProgress phase : phases) {
            jdbcTemplate.update("""
                    INSERT INTO batch_execution_phase
                        (execution_id, phase, planned_count, success_count, failure_count,
                         written_row_count)
                    VALUES (?, ?, ?, ?, ?, ?)
                    ON CONFLICT (execution_id, phase) DO UPDATE SET
                        planned_count     = EXCLUDED.planned_count,
                        success_count     = EXCLUDED.success_count,
                        failure_count     = EXCLUDED.failure_count,
                        written_row_count = EXCLUDED.written_row_count
                    """,
                    executionId, phase.phase().name(), phase.plannedCount(), phase.successCount(),
                    phase.failureCount(), phase.writtenRowCount());
        }
    }

    /**
     * 재실행 대상을 읽는다. 관리자의 "실패한 부분만 재실행"이 이 결과를 쓴다.
     *
     * <p>{@code resolved}가 false인 것만 가져오는 이유는, 이미 재실행으로 해결된 실패를
     * 다시 시도하면 쿼터만 태우기 때문이다.
     */
    @Transactional(readOnly = true)
    public List<StockFailure> unresolvedFailures(long executionId) {
        return jdbcTemplate.query("""
                SELECT DISTINCT stock_id, failed_phase
                FROM batch_execution_failure
                WHERE execution_id = ? AND NOT resolved
                ORDER BY stock_id, failed_phase
                """,
                (rs, rowNum) -> new StockFailure(
                        rs.getLong("stock_id"),
                        CollectionPhase.valueOf(rs.getString("failed_phase"))),
                executionId);
    }

    /**
     * 재실행에 성공한 실패를 해결로 표시한다.
     *
     * <p>이 갱신이 없으면 spec §4의 "미해결 에러 건수"가 영원히 줄지 않아, 관리자 화면의
     * 요약이 무의미해진다.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int resolveFailure(long executionId, long stockId, CollectionPhase phase) {
        return jdbcTemplate.update("""
                UPDATE batch_execution_failure SET resolved = true
                WHERE execution_id = ? AND stock_id = ? AND failed_phase = ?
                """, executionId, stockId, phase.name());
    }

    private static String describe(String message, Throwable cause) {
        if (message != null && !message.isBlank()) {
            return message;
        }
        // message가 NOT NULL이다. 사유 없는 실패 기록은 나중에 아무것도 알려주지 않으므로,
        // 예외에서라도 끌어내고 그것도 없으면 타입 이름을 남긴다.
        if (cause != null) {
            String causeMessage = cause.getMessage();
            return causeMessage != null && !causeMessage.isBlank()
                    ? causeMessage
                    : cause.getClass().getName();
        }
        return "사유가 기록되지 않았다";
    }

    private static String stackTraceOf(Throwable cause) {
        if (cause == null) {
            return null;
        }
        StringWriter writer = new StringWriter();
        cause.printStackTrace(new PrintWriter(writer));
        String trace = writer.toString();
        return trace.length() <= MAX_STACK_TRACE_LENGTH
                ? trace
                : trace.substring(0, MAX_STACK_TRACE_LENGTH) + "\n... (잘림)";
    }

    /** {@code batch_execution.triggered_by}의 값. */
    public enum TriggeredBy {
        /** 스케줄러가 정해진 시각에 띄웠다. */
        SCHEDULER,
        /** 관리자가 화면에서 띄웠다. */
        ADMIN,
        /** 사람이 직접 실행했다. 초기 적재가 여기 해당한다. */
        MANUAL
    }
}
