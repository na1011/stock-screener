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

        Long unresolved = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM batch_execution_failure WHERE NOT resolved", Long.class);

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
            (Long) rs.getObject("retried_from_id"),
            rs.getString("triggered_by"),
            rs.getString("note"),
            rs.getInt("unresolved_count"));

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

    /** 실행 한 건. {@code unresolvedFailureCount}가 재실행 버튼을 켤지 정한다. */
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
            Long retriedFromId,
            String triggeredBy,
            String note,
            int unresolvedFailureCount) {
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

    /** 잡별 최근 실행과 미해결 실패 총건수. */
    public record Summary(List<Execution> latestByJob, long unresolvedFailureCount) {
    }
}
