package com.stockscreener.pipeline.batch;

import com.stockscreener.pipeline.datasource.DataSourceException;
import com.stockscreener.pipeline.lock.ProcessIdentity;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

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

    /** 기준일이 「지금」인지 재는 날짜의 시간대. 정기 실행의 기준일이 한국 날짜다. */
    private static final ZoneId KR_ZONE = ZoneId.of("Asia/Seoul");

    /**
     * 기준일이 이만큼 지난 실행은 정기 실행과 범위가 같지 않다. 정기 실행의 기준일은 당일이거나 직전 평일(최대 3일 전 ·
     * {@code DailyUpdateJob.targetDateFor})이라 7일이면 연휴가 끼어도 안 걸린다.
     */
    private static final int CURRENT_TARGET_DAYS = 7;

    private final JdbcTemplate jdbcTemplate;
    private final Clock clock;

    // MUST: @Autowired를 지우지 않는다. 생성자가 둘이면 Spring이 고르지 못해 컨텍스트가 기동에 실패한다.
    @Autowired
    public BatchExecutionRecorder(JdbcTemplate jdbcTemplate) {
        this(jdbcTemplate, Clock.systemUTC());
    }

    /** 테스트가 「지금」을 밀 수 있게 시계를 주입받는다. */
    public BatchExecutionRecorder(JdbcTemplate jdbcTemplate, Clock clock) {
        this.jdbcTemplate = jdbcTemplate;
        this.clock = clock;
    }

    /**
     * 실행을 시작하고 id를 받는다.
     *
     * <p>{@code REQUIRES_NEW}인 이유는 실행 행이 즉시 커밋되어야 하기 때문이다. 종목별
     * 실패 기록이 이 id를 FK로 참조하는데, 실행 행이 아직 커밋되지 않았으면 참조가 깨진다.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public long start(String jobName, LocalDate targetDate, TriggeredBy triggeredBy, int targetCount,
                      DeclaredScope scope) {
        return startRecorded(jobName, targetDate, triggeredBy, targetCount, scope).id();
    }

    /**
     * 위와 같되 <b>찍은 범위</b>를 함께 낸다 — 실행 컨텍스트가 「정기 범위로 시작했는가」를 알아야 시장 미완료 알림을 그 실행에만
     * 보낸다(ADR-0174). 범위를 다시 계산하지 않는 이유는 자정 경계에서 두 계산이 갈릴 수 있어서다.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Started startRecorded(String jobName, LocalDate targetDate, TriggeredBy triggeredBy, int targetCount,
                                 DeclaredScope scope) {
        // MUST: 새 행에 범위가 비지 않게 여기서 막는다 — DB는 이력 때문에 NULL을 허용하고 신선도 판정은 NULL을
        // 전량으로 센다(실패 시 열리는 방향). 「필수 인자」라도 null은 컴파일된다.
        Objects.requireNonNull(scope, "scope — 정기 실행과 같은 범위인지와 아니면 그 사유를 잡이 정해 넘긴다(V63 · V65)");
        DeclaredScope recorded = recordedScope(jobName, targetDate, triggeredBy, scope);
        // 행의 주인(V66 · ADR-0175) — 스위퍼가 「같은 DB ∧ 그 프로세스가 죽었다」로만 이 행을 닫는다.
        ProcessIdentity owner = ProcessIdentity.current();
        Long id = jdbcTemplate.queryForObject("""
                INSERT INTO batch_execution
                    (job_name, target_date, status, triggered_by, target_count, scope, scope_reason, started_at,
                     owner_pid, owner_start, owner_db)
                VALUES (?, ?, 'RUNNING', ?, ?, ?, ?, now(), ?, ?, current_database())
                RETURNING id
                """, Long.class, jobName, targetDate, triggeredBy.name(), targetCount,
                recorded.scope().name(), reasonName(recorded), owner.pid(), owner.startEpochSecond());
        return new Started(id, recorded);
    }

    /** 시작한 실행의 id와 기록 계층이 찍은 범위. */
    public record Started(long id, DeclaredScope recorded) {
    }

    /**
     * 잡이 정한 범위에 <b>모든 잡에 같은 두 규칙</b>을 더한다. 잡마다 다시 적으면 한 곳이 빠지고, 빠진 자리는 좁힌 실행이
     * 시계를 미는 [A56] 그대로다. 사유가 여럿이면 <b>우선순위 {@code GAP_FILL > PAST_DATE > 잡 선언}</b>이다(ADR-0174) —
     * 앞의 둘은 잡이 무엇을 선언했든 정기 범위가 아니라는 사실이라, 잡의 인자 사유보다 먼저 말한다.
     * <ul>
     *   <li>{@link TriggeredBy#GAP_FILL}은 언제나 {@code NARROW}다 — 정의가 「티커로 좁힌 정기 경로」다.</li>
     *   <li>기준일이 지금(오늘 기준 {@value #CURRENT_TARGET_DAYS}일 안 ~ 내일)이 아니면 {@code NARROW}다 — {@code --as-of}를
     *       과거로 주면 창의 끝이 그 날짜라 정기 실행과 같은 범위가 아니다.</li>
     * </ul>
     *
     * <p>공개인 이유: 스케줄러 시험이 정기 호출의 기준일을 <b>같은 규칙</b>으로 묻는다. 규칙을 시험에 옮겨 적으면 둘이 갈린다.
     */
    public DeclaredScope recordedScope(String jobName, LocalDate targetDate, TriggeredBy triggeredBy,
                                       DeclaredScope declared) {
        if (triggeredBy == TriggeredBy.GAP_FILL) {
            return DeclaredScope.narrow(ScopeReason.GAP_FILL);
        }
        if (isCurrent(targetDate)) {
            return declared;
        }
        if (triggeredBy == TriggeredBy.SCHEDULER) {
            // 정기 경로가 NARROW로 찍히면 그 행이 시계에서 빠져 연체로 뜬다. 그 자체가 맞는 신호이지만(기준일이 일주일 넘게
            // 밀렸다), 원인이 여기라는 것을 남긴다.
            log.warn("정기 실행인데 기준일이 지금이 아니라 범위를 NARROW로 찍는다. job={} 기준일={} 오늘={}",
                    jobName, targetDate, today());
        }
        return DeclaredScope.narrow(ScopeReason.PAST_DATE);
    }

    /**
     * 기준일이 지금인가 — {@link #recordedScope}가 {@code PAST_DATE}를 가르는 판정 그대로다. 종목별 「정기 범위로 맞췄다」 표지
     * ({@code stock_job_refresh})도 같은 판정을 쓴다 — 과거 기준일 실행은 최근 연도를 안 받는다.
     */
    public boolean isCurrentTarget(LocalDate targetDate) {
        return isCurrent(targetDate);
    }

    private boolean isCurrent(LocalDate targetDate) {
        LocalDate today = today();
        return targetDate != null
                && !targetDate.isBefore(today.minusDays(CURRENT_TARGET_DAYS))
                && !targetDate.isAfter(today.plusDays(1));
    }

    private LocalDate today() {
        return LocalDate.ofInstant(clock.instant(), KR_ZONE);
    }

    private static String reasonName(DeclaredScope scope) {
        return scope.reason() == null ? null : scope.reason().name();
    }

    /** 재실행으로 시작한다. 어느 실행을 다시 돌린 것인지 남겨야 반복 실패가 보인다. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public long startRetry(String jobName, LocalDate targetDate, int targetCount, long retriedFromId) {
        // 실패분만 다시 도는 실행이라 언제나 NARROW다 — 정기 실행과 같은 범위가 아니다. 사유는 start와 같은 우선순위를
        // 탄다: 기준일이 지금이 아니면 PAST_DATE가 RERUN보다 먼저다(recordedScope를 안 지나므로 여기서 같은 판정을 한다).
        ScopeReason reason = isCurrent(targetDate) ? ScopeReason.RERUN : ScopeReason.PAST_DATE;
        ProcessIdentity owner = ProcessIdentity.current();
        return jdbcTemplate.queryForObject("""
                INSERT INTO batch_execution
                    (job_name, target_date, status, triggered_by, target_count,
                     retried_from_id, scope, scope_reason, started_at, owner_pid, owner_start, owner_db)
                VALUES (?, ?, 'RUNNING', 'ADMIN', ?, ?, 'NARROW', ?, now(), ?, ?, current_database())
                RETURNING id
                """, Long.class, jobName, targetDate, targetCount, retriedFromId, reason.name(),
                owner.pid(), owner.startEpochSecond());
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
        recordFailure(executionId, stockId, phase, StockTaskExecutor.UNEXPECTED, null,
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
        return finish(executionId, progress, null);
    }

    /**
     * 성공 마감에도 기록을 남긴다.
     *
     * <p>{@code note}가 실패 전용이 아닌 이유는 <b>성공한 실행이 무엇을 했는지 물어야 하는
     * 잡이 있기 때문</b>이다. 외부 호출 수가 그렇다 — 리미터의 일일 카운터는 인메모리라
     * 프로세스가 끝나면 사라지고, 소량 검증 → 전량 → 재실행이 이어지면 실제 누적을 아무도
     * 모른다. 로그에만 남기면 그 줄을 찾아야 알 수 있고, 로그는 지워진다.
     *
     * <p>MUST: 위 2-인자 쪽의 {@code @Transactional}을 "위임하니 중복"이라며 지우지 않는다.
     * 자기 호출은 프록시를 거치지 않으므로(ADR-0007) 그것을 지우면 2-인자 경로만
     * {@code REQUIRES_NEW} 없이 돌고, 그 차이는 배치가 롤백될 때에만 드러난다.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public BatchStatus finish(long executionId, BatchProgress progress, String note) {
        return finish(executionId, progress, note, List.of());
    }

    /**
     * 위와 같되 <b>마감 때 판정한 미완료 시장</b>을 함께 적는다(ADR-0174 · {@link MarketCompleteness}).
     *
     * <p>미완료 시장이 있으면 <b>같은 UPDATE에서</b> {@code FULL}로 시작한 행을 {@code NARROW}·{@code MARKET_INCOMPLETE}로
     * 내린다 — 한 방향이다({@code NARROW}를 {@code FULL}로 올리는 길은 없다). 시작부터 {@code NARROW}인 행은 사유를 안 바꾸고
     * {@code incomplete_markets}만 적는다 — 그 행은 이미 시계에 안 세고, 처음의 사유(좁힌 인자 등)가 더 앞선 사실이다.
     * 따로 UPDATE하지 않는 이유: 상태와 범위 사이에 틈이 있으면 그 사이에 읽은 신선도 판정이 미완료 실행을 시계로 센다.
     *
     * <p>status·성공·실패 수는 그대로다 — 미완료는 실패 건수의 다른 이름이 아니라 「시계에 세는가」의 답이다.
     *
     * @param incompleteMarkets 비면 판정하지 않았거나 미완료가 없었다. 순서대로 적는다
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public BatchStatus finish(long executionId, BatchProgress progress, String note,
                              List<String> incompleteMarkets) {
        BatchStatus status =
                progress.failureCount() == 0 ? BatchStatus.SUCCESS : BatchStatus.PARTIAL_SUCCESS;
        updateTerminalState(executionId, status, progress, note, incompleteMarkets);
        return status;
    }

    /** 배치 자체가 실패했을 때 마감한다. 여기까지 처리된 건수는 그대로 남긴다. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void fail(long executionId, BatchProgress progress, String note) {
        updateTerminalState(executionId, BatchStatus.FAILED, progress, note, List.of());
    }

    /**
     * 외부 요인으로 중단했을 때 마감한다.
     *
     * <p>일일 쿼터 소진이 대표적이다. 이것은 실패가 아니라 정상적인 일시정지이며,
     * 다음 실행이 이어서 처리한다.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void abort(long executionId, BatchProgress progress, String note) {
        updateTerminalState(executionId, BatchStatus.ABORTED, progress, note, List.of());
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
                                     String note, List<String> incompleteMarkets) {
        // 시장 목록은 콤마로 이어 넘기고 DB가 배열로 가른다 — 시장 코드는 영문 대문자라 콤마가 안 섞인다.
        // SET의 오른쪽은 전부 갱신 전 행을 본다(PostgreSQL) — scope_reason의 CASE가 읽는 scope는 내리기 전의 값이다.
        String markets = incompleteMarkets.isEmpty() ? null : String.join(",", incompleteMarkets);
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
                    finished_at        = now(),
                    scope              = CASE WHEN ?::text IS NOT NULL AND scope = 'FULL'
                                              THEN 'NARROW' ELSE scope END,
                    scope_reason       = CASE WHEN ?::text IS NOT NULL AND scope = 'FULL'
                                              THEN 'MARKET_INCOMPLETE' ELSE scope_reason END,
                    incomplete_markets = string_to_array(?::text, ',')
                WHERE id = ? AND status = 'RUNNING'
                """, status.name(), progress.successCount(), progress.failureCount(),
                progress.writtenRowCount(), progress.plannedTaskCount(), note,
                markets, markets, markets, executionId);

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
     * 아직 해결되지 않은 (종목, 단계) 쌍 전체.
     *
     * <p>실행 하나가 시작될 때 <b>한 번만</b> 읽는다. 성공할 때마다 조회하면 평상시(미해결 0건)에도
     * 종목 수 × 단계 수만큼 쿼리가 붙는다.
     *
     * <p>실행 id로 좁히지 않는 것이 요점이다 — 실패를 낸 실행과 그것을 해결하는 실행이
     * 대개 다르기 때문이다. 실제로 {@code company-profile}의 실패 52건이 그 다음 실행에서
     * 전부 채워졌는데도 미해결로 남아 있었다.
     */
    @Transactional(readOnly = true)
    public Map<CollectionPhase, Set<Long>> unresolvedStockPhases() {
        Map<CollectionPhase, Set<Long>> byPhase = new EnumMap<>(CollectionPhase.class);
        jdbcTemplate.query("""
                SELECT DISTINCT stock_id, failed_phase FROM batch_execution_failure
                WHERE NOT resolved
                """, rs -> {
            byPhase.computeIfAbsent(
                            CollectionPhase.valueOf(rs.getString("failed_phase")),
                            k -> ConcurrentHashMap.newKeySet())
                    .add(rs.getLong("stock_id"));
        });
        return byPhase;
    }

    /**
     * 한 종목·한 단계의 미해결 실패를 전부 해결로 표시한다.
     *
     * <p>이 갱신이 없으면 spec §4의 "미해결 에러 건수"가 영원히 줄지 않아, 관리자 화면의
     * 요약이 무의미해진다.
     *
     * <p>MUST: 단계를 함께 받는다. 시세 수집 성공을 근거로 업종정보 실패를 지우면 파이프라인
     * 전체의 전제가 그 지점에서 깨진다 — 어느 단계가 실제로 다시 돌았는지가 사라지기 때문이다.
     *
     * <p>IMPORTANT — 여기서 말하는 성공은 <b>예외가 없었다</b>이지 <b>행을 썼다</b>가 아니다
     * ({@code StockTaskExecutor}가 0행도 성공으로 센다). 거래정지처럼 정상적으로 빈 종목을
     * 영원히 미해결로 남기지 않으려고 일부러 그렇게 두었다. 그 대가로, 같은 단계를 쓰는 다른
     * 잡이 0행 성공만으로도 이 실패를 닫을 수 있다 — {@code FINANCIAL}을 쓰는 잡이 셋이고
     * 받아오는 범위가 서로 다르다.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int resolveFailures(long stockId, CollectionPhase phase) {
        return jdbcTemplate.update("""
                UPDATE batch_execution_failure SET resolved = true
                WHERE stock_id = ? AND failed_phase = ? AND NOT resolved
                """, stockId, phase.name());
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

    /**
     * {@code batch_execution.scope}의 값(V63 · V65) — 이 실행을 <b>신선도 시계에 세는가</b>.
     *
     * <p>{@code FULL}은 「정기 실행과 같은 범위로 돌았고 끝까지 마쳤다」, {@code NARROW}는 그 밖 전부이고 사유는
     * {@link ScopeReason}이 말한다. <b>범위를 정하는 것은 잡이다</b> — 티커·유니버스·연수·기간·엔드포인트로 좁혔는지는 잡만
     * 안다({@link DeclaredScope}). 기준일과 꼬리는 기록 계층이 모든 잡에 같게 더하고({@link BatchExecutionRecorder#recordedScope}),
     * 무거운 잡(FR·FSR·G)은 마감 때 시장 미완료면 {@code FULL}에서 {@code NARROW}로 내린다({@link MarketCompleteness} ·
     * ADR-0174). 신선도 판정({@code JobFreshnessPolicy})이 {@code NARROW}를 시계에서 빼므로, 좁힌 손 실행이 무거운 잡의 첫 정기
     * 실행을 미는 일([A56])도, 한 시장의 소스 답을 못 받은 정기 실행이 시계를 미는 일도 없다.
     *
     * <p>MUST: 정기 경로는 언제나 {@code FULL}로 <b>시작한다</b> — 시작부터 {@code NARROW}로 찍히면 그 SCHEDULER 행이 시계에서
     * 빠져 그 잡이 매 평일 다시 돈다. 마감 때의 {@code NARROW}(시장 미완료)는 그 「다시 돈다」가 의도다.
     */
    public enum Scope {
        FULL, NARROW
    }

    /**
     * {@code batch_execution.scope_reason}의 값(V65) — {@code NARROW}인 이유. {@code FULL} 행에는 없다(CHECK).
     *
     * <p>V65 전 {@code NARROW} 행은 {@code NULL}이다(「사유 기록 전」 · 이력 UPDATE 안 함).
     */
    public enum ScopeReason {
        /** 잡의 인자(티커·유니버스·연수·기간·엔드포인트·표)로 좁혔다. */
        ARGS,
        /** 실패분만 다시 돈 재실행이다({@link #startRetry}). */
        RERUN,
        /** 정기 경로가 티커로 좁혀 부른 꼬리다({@link TriggeredBy#GAP_FILL}). */
        GAP_FILL,
        /** 기준일이 지금(오늘 기준 7일 안 ~ 내일)이 아니다 — 과거 기준일 · 없음 · 먼 미래. */
        PAST_DATE,
        /** 정기 실행이 없는 잡이라 「정기 범위」가 정의되지 않는다({@code corporate-action-judge}). */
        NO_SCHEDULED_RANGE,
        /** 정기 범위로 돌았지만 마감 때 미완료 시장이 있었다({@link MarketCompleteness}). 마감만 이 값을 쓴다. */
        MARKET_INCOMPLETE
    }

    /**
     * 잡이 선언하는 범위와 그 사유. <b>사유는 잡이 명시적으로 넘긴다</b> — 기록 계층이 「선언 {@code NARROW} = 인자로 좁힘」으로
     * 추론하면, 인자가 아닌 이유로 좁은 잡({@code corporate-action-judge})이 거짓 사유를 단다(ADR-0174).
     *
     * <p>{@code FULL}이면 사유가 없고 {@code NARROW}면 반드시 있다 — 생성자가 막는다.
     */
    public record DeclaredScope(Scope scope, ScopeReason reason) {

        public static final DeclaredScope FULL = new DeclaredScope(Scope.FULL, null);

        public DeclaredScope {
            Objects.requireNonNull(scope, "scope");
            if ((scope == Scope.FULL) != (reason == null)) {
                throw new IllegalArgumentException("FULL은 사유가 없고 NARROW는 사유가 있어야 한다: " + scope + "/" + reason);
            }
            if (reason == ScopeReason.MARKET_INCOMPLETE) {
                // 시작 때 알 수 없는 사실이다 — 마감({@link #finish(long, BatchProgress, String, List)})만 적는다.
                throw new IllegalArgumentException("MARKET_INCOMPLETE는 마감이 적는다 — 시작 때 선언하지 않는다");
            }
        }

        public static DeclaredScope narrow(ScopeReason reason) {
            return new DeclaredScope(Scope.NARROW, reason);
        }

        /**
         * 잡의 <b>인자가</b> 정기 실행과 같은 범위인가 — 아니면 {@link ScopeReason#ARGS}. 기준일과 {@code GAP_FILL}은 여기서
         * 안 본다 — {@link BatchExecutionRecorder#recordedScope}가 모든 잡에 같게 더한다.
         */
        public static DeclaredScope byArgs(boolean sameAsScheduled) {
            return sameAsScheduled ? FULL : narrow(ScopeReason.ARGS);
        }
    }

    /** {@code batch_execution.triggered_by}의 값. */
    public enum TriggeredBy {
        /** 스케줄러가 정해진 시각에 띄웠다. */
        SCHEDULER,
        /** 관리자가 화면에서 띄웠다. */
        ADMIN,
        /** 사람이 직접 실행했다. 초기 적재가 여기 해당한다. */
        MANUAL,
        /**
         * 정기 경로가 <b>티커로 좁혀</b> 부른 실행이다 — 리밸런싱 꼬리(결손 티커)와 14:00 기업 행위
         * 사슬의 화해 ①(무효화 티커 · {@code IndicatorReconciler.originTrigger})이 그 자리다.
         *
         * <p>{@code SCHEDULER}와 갈라 두는 이유는 {@link JobFreshnessPolicy}의 불변식 때문이다 —
         * <i>"스케줄러는 언제나 기본 경로(활성 유니버스 전량)만 돈다"</i>가 성립해야 좁은 실행이
         * 시계를 되돌리지 못한다. 꼬리는 리밸런싱의 트리거를 물려받으므로 그대로 두면 <b>매주
         * 토요일의 3종목짜리 실행이 월 1회 전량 잡의 시계를 0으로 되돌린다.</b>
         *
         * <p><b>MUST: 좁힌 실행에만 쓴다.</b> 꼬리가 부르더라도 대상이 그 잡의 전량이면
         * 물려받은 트리거를 그대로 쓴다({@code company-profile}이 그 자리다 — 전량이 「업종이 빈
         * 종목」이라 실측으로 4였다). 가르는 축은 <b>「좁혔는가」이지 「꼬리인가」가 아니다.</b>
         */
        GAP_FILL
    }
}
