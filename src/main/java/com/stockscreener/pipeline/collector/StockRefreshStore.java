package com.stockscreener.pipeline.collector;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * 종목 × 무거운 잡의 「정기 범위로 맞췄다」 표지({@code stock_job_refresh} · V69 · ADR-0177)를 쓰고, 그 표지로 편입 뒤 뒤처진
 * 종목을 고른다.
 *
 * <p>MUST: {@link #mark}는 잡의 종목 람다 안에서 부른다 — {@code StockTaskRunner}의 종목 트랜잭션에 함께 묶여, 쓰기가 되돌아가면
 * 표지도 되돌아간다. 따로 커밋하면 「안 썼는데 맞췄다」가 남는다.
 */
@Component
public class StockRefreshStore {

    private final JdbcTemplate jdbc;

    public StockRefreshStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 그 종목을 이 잡이 정기 범위로 맞췄다. */
    public void mark(long stockId, String jobName, long executionId) {
        jdbc.update("""
                INSERT INTO stock_job_refresh (stock_id, job_name, refreshed_at, execution_id)
                VALUES (?, ?, now(), ?)
                ON CONFLICT (stock_id, job_name) DO UPDATE
                    SET refreshed_at = EXCLUDED.refreshed_at, execution_id = EXCLUDED.execution_id
                """, stockId, jobName, executionId);
    }

    /**
     * 편입 뒤로 그 잡이 정기 범위로 맞춘 적이 없는 활성 편입 종목.
     *
     * @param markets 그 잡의 소스가 있는 시장 — 소스 없는 시장의 종목은 영영 맞춰질 수 없으므로 고르지 않는다
     * @param cutoffs 유니버스 코드 → 이 표가 생기기 전 그 유니버스 전원을 실패 0으로 덮은 마지막 넓은 실행의 시작. 표지가 NULL인
     *                종목은 지금 편입이 그 뒤에 생겼을 때만 뒤처졌다(없는 유니버스는 NULL이면 뒤처졌다)
     * @return 마지막 실패가 오래된 순(실패 없음이 먼저) → 편입이 이른 순 — 매일 실패하는 종목이 상한 앞자리를 막지 않는다
     */
    public List<Behind> behind(String jobName, Collection<String> markets, Map<String, Instant> cutoffs) {
        if (markets.isEmpty()) {
            return List.of();
        }
        List<Object> args = new ArrayList<>();
        StringBuilder cutoffRows = new StringBuilder();
        for (Map.Entry<String, Instant> e : cutoffs.entrySet()) {
            cutoffRows.append(cutoffRows.isEmpty() ? "" : ", ").append("(?, ?::timestamptz)");
            args.add(e.getKey());
            args.add(Timestamp.from(e.getValue()));
        }
        String cutoffTable = cutoffRows.isEmpty()
                ? "SELECT NULL::text AS universe_code, NULL::timestamptz AS cutoff WHERE false"
                : "SELECT * FROM (VALUES " + cutoffRows + ") v(universe_code, cutoff)";
        args.addAll(markets.stream().sorted().toList());
        args.add(jobName);
        args.add(jobName);
        // 활성 편입의 술어는 StockRegistrar.activeUniverseMemberIds와 같다 — 잡이 대상으로 잡지 않는 종목을 고르면 영영 못 맞춘다.
        String sql = """
                WITH cut AS (%s),
                cur AS (
                    SELECT m.stock_id, max(m.created_at) AS joined_at, min(m.universe_code) AS universe_code
                    FROM universe_membership m
                    JOIN stock s ON s.id = m.stock_id
                    JOIN universe u ON u.code = m.universe_code AND u.is_active
                    WHERE m.effective_to IS NULL AND s.is_active AND s.market_code IN (%s)
                    GROUP BY m.stock_id)
                SELECT c.stock_id, s.ticker, s.market_code, c.universe_code, c.joined_at, r.refreshed_at,
                       (SELECT max(f.occurred_at) FROM batch_execution_failure f
                        JOIN batch_execution e ON e.id = f.execution_id
                        WHERE f.stock_id = c.stock_id AND e.job_name = ?) AS last_failure
                FROM cur c
                JOIN stock s ON s.id = c.stock_id
                LEFT JOIN stock_job_refresh r ON r.stock_id = c.stock_id AND r.job_name = ?
                LEFT JOIN cut ON cut.universe_code = c.universe_code
                WHERE (r.refreshed_at IS NULL AND (cut.cutoff IS NULL OR c.joined_at > cut.cutoff))
                   OR r.refreshed_at < c.joined_at
                ORDER BY last_failure NULLS FIRST, c.joined_at, s.ticker
                """.formatted(cutoffTable, String.join(",", java.util.Collections.nCopies(markets.size(), "?")));
        return jdbc.query(sql, (rs, i) -> {
            Timestamp refreshed = rs.getTimestamp("refreshed_at");
            Timestamp failed = rs.getTimestamp("last_failure");
            return new Behind(rs.getLong("stock_id"), rs.getString("ticker"), rs.getString("market_code"),
                    rs.getString("universe_code"), rs.getTimestamp("joined_at").toInstant(),
                    refreshed == null ? null : refreshed.toInstant(), failed == null ? null : failed.toInstant());
        }, args.toArray());
    }

    /**
     * 그날 그 잡의 넓은 실행(시작 때 FULL을 선언한 실행 — 마감에서 미완료로 내려간 것 포함)이 시작됐는가. 방금 같은 종목을 시도했고,
     * 같은 날 다시 돌면 그 경로의 일일 한도를 넘길 수 있다.
     */
    public boolean wideRunStartedOn(String jobName, LocalDate day) {
        Boolean started = jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM batch_execution
                               WHERE job_name = ? AND target_date = ?
                                 AND (scope = 'FULL' OR scope_reason = 'MARKET_INCOMPLETE'))
                """, Boolean.class, jobName, java.sql.Date.valueOf(day));
        return Boolean.TRUE.equals(started);
    }

    /** 뒤처진 종목 하나. {@code refreshedAt}이 NULL이면 편입 뒤로 한 번도 안 맞췄다. */
    public record Behind(long stockId, String ticker, String market, String universeCode, Instant joinedAt,
                         Instant refreshedAt, Instant lastFailure) {
    }
}
