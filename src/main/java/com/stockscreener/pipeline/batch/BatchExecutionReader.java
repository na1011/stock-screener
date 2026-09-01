package com.stockscreener.pipeline.batch;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 관리자 화면이 읽는 배치 실행 이력.
 *
 * <p>쓰는 쪽({@link BatchExecutionRecorder})과 나눈 이유는 트랜잭션 성질이 정반대이기
 * 때문이다 — 기록은 전부 {@code REQUIRES_NEW}로 즉시 커밋해야 하고(실패가 롤백과 함께
 * 증발하면 안 된다), 조회는 읽기 전용이다. 한 클래스에 두면 그 차이가 메서드 애너테이션에만
 * 남아 다음 사람이 새 메서드에 잘못된 전파를 붙인다.
 *
 * <p>실패 목록에 {@code stack_trace}를 싣지 않는다. 350건이 실패한 날 응답이 수 MB가 되고,
 * 재실행 여부를 판단하는 데 필요한 것은 {@code failure_kind}와 {@code message}다. 깊은 진단은
 * Sentry가 전담한다(spec §4 — 에러 로그를 별도 화면으로 중복 구축하지 않는다).
 */
@Service
public class BatchExecutionReader {

    /** 목록 조회의 기본·최대 건수. 화면이 훑는 단위이지 페이징이 아니다. */
    public static final int DEFAULT_LIMIT = 50;
    public static final int MAX_LIMIT = 500;

    private static final String EXECUTION_COLUMNS = """
            e.id, e.job_name, e.target_date, e.status, e.started_at, e.finished_at,
            e.target_count, e.success_count, e.failure_count, e.written_row_count,
            e.planned_task_count, e.progress_at, e.stage_label,
            e.retried_from_id, e.triggered_by, e.note,
            (SELECT count(*) FROM batch_execution_failure f
              WHERE f.execution_id = e.id AND NOT f.resolved) AS unresolved_count
            """;

    private final JdbcTemplate jdbcTemplate;

    public BatchExecutionReader(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 실행 이력을 최근 순으로 훑는다.
     *
     * <p>필터는 전부 선택이다. {@code null}이면 그 축으로 좁히지 않는다 — 조건을 데이터로 다뤄
     * 쿼리를 조립하는 것이 이 프로젝트의 기준이다({@code .claude/rules/backend.md}).
     */
    @Transactional(readOnly = true)
    public List<Execution> list(LocalDate from, LocalDate to, String jobName, BatchStatus status,
                               Integer limit) {

        StringBuilder sql = new StringBuilder(
                "SELECT " + EXECUTION_COLUMNS + " FROM batch_execution e WHERE 1 = 1");
        List<Object> args = new ArrayList<>();

        if (from != null) {
            sql.append(" AND e.target_date >= ?");
            args.add(from);
        }
        if (to != null) {
            sql.append(" AND e.target_date <= ?");
            args.add(to);
        }
        if (jobName != null && !jobName.isBlank()) {
            sql.append(" AND e.job_name = ?");
            args.add(jobName);
        }
        if (status != null) {
            sql.append(" AND e.status = ?");
            args.add(status.name());
        }

        sql.append(" ORDER BY e.started_at DESC, e.id DESC LIMIT ?");
        args.add(boundedLimit(limit));

        return jdbcTemplate.query(sql.toString(), EXECUTION_MAPPER, args.toArray());
    }

    @Transactional(readOnly = true)
    public Optional<Execution> findById(long executionId) {
        return jdbcTemplate.query(
                        "SELECT " + EXECUTION_COLUMNS + " FROM batch_execution e WHERE e.id = ?",
                        EXECUTION_MAPPER, executionId)
                .stream().findFirst();
    }

    /**
     * 실행 하나의 실패 종목.
     *
     * <p>종목명을 조인해 주는 이유는 화면이 티커만 보여주면 운영자가 무엇이 실패했는지
     * 알 수 없기 때문이다. 재실행 대상을 고르는 판단이 여기 달려 있다.
     */
    @Transactional(readOnly = true)
    public List<Failure> failuresOf(long executionId) {
        return jdbcTemplate.query("""
                SELECT f.id, f.stock_id, s.ticker, s.name AS stock_name,
                       f.failed_phase, f.source_name, f.failure_kind, f.message,
                       f.resolved, f.occurred_at
                FROM batch_execution_failure f
                JOIN stock s ON s.id = f.stock_id
                WHERE f.execution_id = ?
                ORDER BY f.resolved, s.ticker
                """, FAILURE_MAPPER, executionId);
    }

    /**
     * 실행 하나의 단계별 집계.
     *
     * <p>합계로는 "그 단계가 실제로 무언가를 받아왔는가"를 판정할 수 없어서 있다 — 시세가
     * 0행이어도 지표 단계가 종목당 수천 행을 써서 합계가 0을 덮는다.
     *
     * <p>{@code phase}를 {@code CollectionPhase}로 좁히지 않고 문자열로 내보낸다. 서버가 단계를
     * 하나 더했는데 화면이 낡았을 때, 단계 표가 통째로 죽는 것보다 낯선 이름 한 줄이 섞이는
     * 편이 낫다({@code frontend.md}). 실패 목록의 {@code failedPhase}와 같은 판단이다.
     */
    @Transactional(readOnly = true)
    public List<Phase> phasesOf(long executionId) {
        return jdbcTemplate.query("""
                SELECT phase, planned_count, success_count, failure_count, written_row_count
                FROM batch_execution_phase
                WHERE execution_id = ?
                ORDER BY phase
                """, PHASE_MAPPER, executionId);
    }

    /**
     * 실행 하나의 실패를 종류·소스별로 묶은 것.
     *
     * <p>350행짜리 목록을 눈으로 세지 않고 "무엇이 깨졌나"에 답하기 위한 것이다. 대응이
     * 종류마다 다르다 — {@code RATE_LIMITED}는 기다리면 되고, {@code MALFORMED}가 대량이면
     * 어댑터를 고쳐야 하며, {@code UNEXPECTED}는 우리 코드의 버그다.
     */
    @Transactional(readOnly = true)
    public List<FailureGroup> failureBreakdown(long executionId) {
        return jdbcTemplate.query("""
                SELECT failure_kind, source_name, count(*) AS total,
                       count(*) FILTER (WHERE NOT resolved) AS unresolved
                FROM batch_execution_failure
                WHERE execution_id = ?
                GROUP BY failure_kind, source_name
                ORDER BY count(*) DESC, failure_kind
                """, FAILURE_GROUP_MAPPER, executionId);
    }

    /**
     * 운영 현황 요약 (spec §4).
     *
     * <p>잡별 <b>가장 최근 한 건</b>과 미해결 실패 총건수다. 전체 목록의 앞부분을 자르지 않는
     * 이유는, 일일 갱신이 자주 돌면 유니버스 리밸런싱의 마지막 결과가 목록 밖으로 밀려나
     * "그 잡이 언제 마지막으로 성공했는가"를 요약에서 볼 수 없게 되기 때문이다.
     */
    @Transactional(readOnly = true)
    public Summary summary() {
        List<Execution> latest = jdbcTemplate.query("""
                SELECT * FROM (
                    SELECT DISTINCT ON (e.job_name) %s
                    FROM batch_execution e
                    ORDER BY e.job_name, e.started_at DESC, e.id DESC
                ) latest
                ORDER BY started_at DESC
                """.formatted(EXECUTION_COLUMNS), EXECUTION_MAPPER);

        // MUST: 편입 종목으로 좁힌다. 해소는 그 종목이 그 단계에서 **다시 성공**할 때만
        // 일어나는데(BatchExecutionContext.resolveIfPreviouslyFailed) 활성 유니버스 밖 종목은
        // 어떤 잡의 기본 대상도 아니라 **다시 성공할 기회가 원리적으로 없다.** 좁히지 않으면
        // 이 수는 영원히 안 줄고, 그러면 진짜 실패가 하나 생겨도 총계가 1 늘 뿐이라 안 드러난다.
        //
        // 실측(2026-09-01): 좁히기 전 2,281건이 전부 US_LISTED 적재의 SEC 실패였고
        // (NOT_FOUND 1,984 · MALFORMED 297, 종목 2,130개) **편입 중은 0개**였다.
        // NOT_FOUND는 SEC 매핑에 없는 ETF·펀드·우선주라 정상이다(batch-load skill).
        //
        // MUST NOT: 실행별 unresolved_count(EXECUTION_COLUMNS)를 함께 좁히지 않는다. 그것은
        // **그 실행에서 안 풀린 것**이고 축이 다르다. 게다가 재실행 버튼을 켜는 근거라
        // (AdminBatchController) 좁히면 화면이 서버와 모순된다.
        Long unresolved = jdbcTemplate.queryForObject("""
                SELECT count(*) FROM batch_execution_failure f
                WHERE NOT f.resolved
                  AND EXISTS (SELECT 1 FROM universe_membership m
                               JOIN universe u ON u.code = m.universe_code AND u.is_active
                              WHERE m.stock_id = f.stock_id AND m.effective_to IS NULL)
                """, Long.class);

        return new Summary(latest, unresolved == null ? 0 : unresolved);
    }

    private static int boundedLimit(Integer limit) {
        if (limit == null || limit <= 0) {
            return DEFAULT_LIMIT;
        }
        return Math.min(limit, MAX_LIMIT);
    }

    private static final RowMapper<Execution> EXECUTION_MAPPER = (rs, rowNum) -> new Execution(
            rs.getLong("id"),
            rs.getString("job_name"),
            rs.getObject("target_date", LocalDate.class),
            BatchStatus.valueOf(rs.getString("status")),
            instantOf(rs, "started_at"),
            instantOf(rs, "finished_at"),
            rs.getInt("target_count"),
            rs.getInt("success_count"),
            rs.getInt("failure_count"),
            rs.getLong("written_row_count"),
            // MUST: getObject다. getInt는 NULL을 0으로 준다 — "계량이 없는 옛 실행"이
            // "0건 계획"으로 둔갑하고, 화면은 그것을 분모 0의 진행률로 그린다.
            (Integer) rs.getObject("planned_task_count"),
            instantOf(rs, "progress_at"),
            rs.getString("stage_label"),
            (Long) rs.getObject("retried_from_id"),
            rs.getString("triggered_by"),
            rs.getString("note"),
            rs.getInt("unresolved_count"));

    private static final RowMapper<Phase> PHASE_MAPPER = (rs, rowNum) -> new Phase(
            rs.getString("phase"),
            rs.getInt("planned_count"),
            rs.getInt("success_count"),
            rs.getInt("failure_count"),
            rs.getLong("written_row_count"));

    private static final RowMapper<FailureGroup> FAILURE_GROUP_MAPPER = (rs, rowNum) ->
            new FailureGroup(
                    rs.getString("failure_kind"),
                    rs.getString("source_name"),
                    rs.getInt("total"),
                    rs.getInt("unresolved"));

    private static final RowMapper<Failure> FAILURE_MAPPER = (rs, rowNum) -> new Failure(
            rs.getLong("id"),
            rs.getLong("stock_id"),
            rs.getString("ticker"),
            rs.getString("stock_name"),
            rs.getString("failed_phase"),
            rs.getString("source_name"),
            rs.getString("failure_kind"),
            rs.getString("message"),
            rs.getBoolean("resolved"),
            instantOf(rs, "occurred_at"));

    private static Instant instantOf(ResultSet rs, String column) throws SQLException {
        Timestamp timestamp = rs.getTimestamp(column);
        return timestamp == null ? null : timestamp.toInstant();
    }

    /**
     * 실행 한 건. {@code unresolvedFailureCount}가 재실행 버튼을 켤지 정한다.
     *
     * @param targetCount      대상 <b>종목</b> 수. 진행률의 분모가 아니다(ADR-0037).
     * @param plannedTaskCount 계획된 <b>작업 단위</b> 수. 이것이 분모다. 잡이 신고하지 않았거나
     *                         V14 이전 실행이면 {@code null}이고, 화면은 {@code —}로 그린다.
     * @param progressAt       마지막 진척 반영 시각. {@code RUNNING}인데 이 값이 오래됐으면
     *                         멎은 것이다 — 상태만으로는 그 둘이 구분되지 않는다.
     * @param stageLabel       지금 어느 구간인가. 마감하면 비워진다.
     */
    public record Execution(
            long id,
            String jobName,
            LocalDate targetDate,
            BatchStatus status,
            Instant startedAt,
            Instant finishedAt,
            int targetCount,
            int successCount,
            int failureCount,
            long writtenRowCount,
            Integer plannedTaskCount,
            Instant progressAt,
            String stageLabel,
            Long retriedFromId,
            String triggeredBy,
            String note,
            int unresolvedFailureCount) {
    }

    /** 단계 하나의 집계. {@code phase}는 유니온으로 좁히지 않는다 — 매퍼의 주석에 이유가 있다. */
    public record Phase(
            String phase,
            int plannedCount,
            int successCount,
            int failureCount,
            long writtenRowCount) {
    }

    /** 같은 종류·같은 소스의 실패를 묶은 것. */
    public record FailureGroup(
            String failureKind,
            String sourceName,
            int totalCount,
            int unresolvedCount) {
    }

    public record Failure(
            long id,
            long stockId,
            String ticker,
            String stockName,
            String failedPhase,
            String sourceName,
            String failureKind,
            String message,
            boolean resolved,
            Instant occurredAt) {
    }

    /**
     * 잡별 최근 실행과 미해결 실패 건수.
     *
     * @param unresolvedFailureCount <b>편입 중인 종목의</b> 미해결 실패다. 실행별
     *        {@code unresolvedFailureCount}와 축이 다르다 — 저쪽은 그 실행에서 안 풀린
     *        것 전부이고, 유니버스 밖 종목은 다시 성공할 기회가 없어 영원히 안 줄어든다.
     */
    public record Summary(List<Execution> latestByJob, long unresolvedFailureCount) {
    }
}
