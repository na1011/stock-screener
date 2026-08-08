package com.stockscreener.interaction;

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
import java.util.Map;

/**
 * 관리자 화면이 읽는 인터랙션 기록 (spec §4 "누가 언제 어떤 주요 행위를 했는지 조회·필터링").
 *
 * <p>{@link InteractionRecorder}와 나눈 이유는 실패 처리가 정반대이기 때문이다 — 기록은
 * 던지지 않고 삼키지도 않는 특수한 계약을 지고 있고, 조회는 평범하게 던진다. 한 클래스에 두면
 * 그 특수함이 조회 메서드에까지 번진다.
 *
 * <p>사용자 이메일을 조인해 준다. id만 주면 화면이 "누가"를 말할 수 없고, 관리자는 로그를
 * 보면서 계정 화면을 따로 열어야 한다.
 */
@Service
public class InteractionLogReader {

    public static final int DEFAULT_LIMIT = 100;
    public static final int MAX_LIMIT = 1000;

    private final JdbcTemplate jdbcTemplate;
    private final RowMapper<Entry> mapper;

    public InteractionLogReader(JdbcTemplate jdbcTemplate, InteractionDetailCodec detailCodec) {
        this.jdbcTemplate = jdbcTemplate;
        // RowMapper가 static이 아닌 이유. detail이 jsonb라 파싱이 필요하고, 그 형식을 아는
        // 곳은 코덱 하나여야 한다(IndicatorCatalog가 구간을 읽는 것과 같은 구조다).
        this.mapper = (rs, rowNum) -> new Entry(
                rs.getLong("id"),
                (Long) rs.getObject("user_id"),
                rs.getString("user_email"),
                rs.getString("user_name"),
                InteractionAction.valueOf(rs.getString("action")),
                rs.getString("http_method"),
                rs.getString("path"),
                (Integer) rs.getObject("status_code"),
                (Integer) rs.getObject("duration_ms"),
                detailCodec.fromJson(rs.getString("detail")),
                instantOf(rs, "occurred_at"));
    }

    /**
     * 최근 순으로 훑는다. 모든 필터가 선택이다.
     *
     * <p>{@code from}·{@code to}는 <b>일자</b>다. {@code to}에 하루를 더해 비교하는 이유는
     * {@code occurred_at}이 타임스탬프라 {@code <= to}로 비교하면 그날 00:00 이후가 통째로
     * 빠지기 때문이다 — 오늘을 지정했는데 오늘 것이 안 나오는 증상으로 드러난다.
     */
    @Transactional(readOnly = true)
    public List<Entry> list(LocalDate from, LocalDate to, Long userId, InteractionAction action,
                            Integer limit) {

        StringBuilder sql = new StringBuilder("""
                SELECT l.id, l.user_id, u.email AS user_email, u.name AS user_name,
                       l.action, l.http_method, l.path, l.status_code, l.duration_ms,
                       l.detail, l.occurred_at
                FROM interaction_log l
                LEFT JOIN app_user u ON u.id = l.user_id
                WHERE 1 = 1
                """);
        List<Object> args = new ArrayList<>();

        if (from != null) {
            sql.append(" AND l.occurred_at >= ?");
            args.add(Timestamp.from(from.atStartOfDay(java.time.ZoneId.systemDefault()).toInstant()));
        }
        if (to != null) {
            sql.append(" AND l.occurred_at < ?");
            args.add(Timestamp.from(
                    to.plusDays(1).atStartOfDay(java.time.ZoneId.systemDefault()).toInstant()));
        }
        if (userId != null) {
            sql.append(" AND l.user_id = ?");
            args.add(userId);
        }
        if (action != null) {
            sql.append(" AND l.action = ?");
            args.add(action.name());
        }

        sql.append(" ORDER BY l.occurred_at DESC, l.id DESC LIMIT ?");
        args.add(boundedLimit(limit));

        return jdbcTemplate.query(sql.toString(), mapper, args.toArray());
    }

    /**
     * 기록에 실제로 등장한 사용자.
     *
     * <p>허용목록이 아니라 <b>로그가 정본</b>이다. 허용목록에서 빠진 사람의 과거 기록도 남아
     * 있고(권한 회수가 기록을 지우지 않는다), 그 사람으로 필터할 수 없으면 "누가 무엇을 했는가"의
     * 절반이 조회 불가능해진다.
     */
    @Transactional(readOnly = true)
    public List<Actor> actors() {
        return jdbcTemplate.query("""
                SELECT DISTINCT l.user_id, u.email, u.name
                FROM interaction_log l
                JOIN app_user u ON u.id = l.user_id
                ORDER BY u.email
                """, (rs, rowNum) -> new Actor(
                        rs.getLong("user_id"), rs.getString("email"), rs.getString("name")));
    }

    private static int boundedLimit(Integer limit) {
        if (limit == null || limit <= 0) {
            return DEFAULT_LIMIT;
        }
        return Math.min(limit, MAX_LIMIT);
    }

    private static Instant instantOf(ResultSet rs, String column) throws SQLException {
        Timestamp timestamp = rs.getTimestamp(column);
        return timestamp == null ? null : timestamp.toInstant();
    }

    /**
     * 기록 한 줄.
     *
     * @param userId 사용자를 특정하지 못한 기록은 {@code null}이다. 행위 자체는 남는다.
     * @param detail URI 템플릿 변수와 지정한 쿼리 파라미터. 없으면 빈 맵이다.
     */
    public record Entry(
            long id,
            Long userId,
            String userEmail,
            String userName,
            InteractionAction action,
            String httpMethod,
            String path,
            Integer statusCode,
            Integer durationMs,
            Map<String, String> detail,
            Instant occurredAt) {
    }

    public record Actor(long userId, String email, String name) {
    }
}
