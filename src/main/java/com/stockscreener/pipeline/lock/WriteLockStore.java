package com.stockscreener.pipeline.lock;

import java.sql.Array;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;
import java.util.UUID;

/**
 * {@code write_lock} 행의 읽기와 CAS 갱신(ADR-0175).
 *
 * <p><b>MUST: 앱의 커넥션 풀을 쓰지 않는다 — 부를 때마다 자기 연결을 열고 autocommit으로 닫는다.</b> 이유가 셋이다.
 * <ol>
 *   <li>풀의 {@code JdbcTemplate}은 호출부의 트랜잭션에 합류한다. 획득이 그 트랜잭션과 함께 롤백되면 잠금이 「쥔 줄 알았는데
 *       없는」 상태가 되고, 반대로 그 트랜잭션이 행 잠금을 쥔 채 다른 스레드가 반납하면 서로를 기다린다.</li>
 *   <li>반납은 컨텍스트가 닫히고 풀이 파괴된 <b>뒤에</b> 해야 한다 — 그 사이 종료 마감({@code BatchExecutions.onContextClosed})이
 *       보호 표에 쓰기 때문이다(잠금 v3 계획 밖 6). 그때는 풀이 없다.</li>
 *   <li>박동 커넥션은 보유 중에만 있어야 한다(ADR-0163 — 유휴 커넥션이 DB 종료를 막는다). 부를 때마다 열고 닫으면 저절로 그렇다.</li>
 * </ol>
 *
 * <p>모든 갱신은 {@code version}을 올린다(박동만 예외 — 산 보유자의 박동이 대기자의 표지 CAS를 깨지 않게). 갱신 메서드는
 * 바뀐 행이 있었는지를 돌려주고 판정은 부르는 쪽({@link WriteLock})이 한다.
 */
class WriteLockStore {

    private final String url;
    private final String username;
    private final String password;
    private final String applicationName;

    WriteLockStore(String url, String username, String password, String applicationName) {
        this.url = url;
        this.username = username;
        this.password = password;
        this.applicationName = applicationName;
    }

    boolean tableExists() {
        return query(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT to_regclass('public.write_lock') IS NOT NULL");
                 ResultSet rs = statement.executeQuery()) {
                rs.next();
                return rs.getBoolean(1);
            }
        });
    }

    LockRow read() {
        return query(connection -> {
            try (PreparedStatement statement = connection.prepareStatement("""
                    SELECT now() AS db_now, l.*
                      FROM (SELECT 1) one
                      LEFT JOIN public.write_lock l ON l.db_name = current_database()
                    """);
                 ResultSet rs = statement.executeQuery()) {
                rs.next();
                Instant dbNow = instant(rs, "db_now");
                if (rs.getObject("version") == null) {
                    return LockRow.absent(dbNow);
                }
                String kind = rs.getString("holder_kind");
                Array members = rs.getArray("members");
                return new LockRow(
                        rs.getLong("version"),
                        kind == null ? null : LockRow.HolderKind.valueOf(kind),
                        (UUID) rs.getObject("token"),
                        rs.getString("purpose"),
                        rs.getString("session_id"),
                        longOrNull(rs, "holder_pid"),
                        longOrNull(rs, "holder_start"),
                        members == null ? List.of() : Arrays.asList((String[]) members.getArray()),
                        instant(rs, "acquired_at"),
                        instant(rs, "heartbeat_at"),
                        instant(rs, "last_activity_at"),
                        longOrNull(rs, "waiter_pid"),
                        longOrNull(rs, "waiter_start"),
                        rs.getString("waiter_purpose"),
                        instant(rs, "waiter_since"),
                        dbNow);
            }
        });
    }

    /**
     * JVM이 잠금을 쥔다. {@code observedVersion}이 {@code null}이면 행이 없던 것을 관측했다 — INSERT만 이긴다. 대기 표지는
     * 지운다: 쥐는 쪽은 언제나 그 표지의 주인이거나, 표지 주인이 죽었다고 판정한 쪽이다({@link WriteLock}).
     */
    boolean acquireJvm(Long observedVersion, LockRow.HolderKind kind, UUID token, String purpose, ProcessIdentity holder) {
        return update("""
                INSERT INTO public.write_lock AS l
                    (db_name, version, holder_kind, token, purpose, session_id, holder_pid, holder_start, members,
                     acquired_at, heartbeat_at, last_activity_at, waiter_pid, waiter_start, waiter_purpose, waiter_since)
                VALUES (current_database(), 1, ?, ?, ?, NULL, ?, ?, '{}', now(), now(), now(), NULL, NULL, NULL, NULL)
                ON CONFLICT (db_name) DO UPDATE SET
                    version = l.version + 1, holder_kind = EXCLUDED.holder_kind, token = EXCLUDED.token,
                    purpose = EXCLUDED.purpose, session_id = NULL, holder_pid = EXCLUDED.holder_pid,
                    holder_start = EXCLUDED.holder_start, members = '{}', acquired_at = now(), heartbeat_at = now(),
                    last_activity_at = now(), waiter_pid = NULL, waiter_start = NULL, waiter_purpose = NULL,
                    waiter_since = NULL
                WHERE l.version = ?
                """, kind.name(), token, purpose, holder.pid(), holder.startEpochSecond(), observedVersion) == 1;
    }

    /** 세션 구성원 목록을 바꾼다(합류). 관측한 version 그대로이고 그 세션이 아직 쥐고 있을 때만. */
    boolean replaceMembers(long observedVersion, String sessionId, List<String> members) {
        return query(connection -> {
            try (PreparedStatement statement = connection.prepareStatement("""
                    UPDATE public.write_lock
                       SET version = version + 1, members = ?, last_activity_at = now()
                     WHERE db_name = current_database() AND version = ? AND token IS NOT NULL AND session_id = ?
                    """)) {
                statement.setArray(1, connection.createArrayOf("text", members.toArray()));
                statement.setLong(2, observedVersion);
                statement.setString(3, sessionId);
                return statement.executeUpdate() == 1;
            }
        });
    }

    /** 세션 구성원 하나를 뺀다(합류 JVM의 퇴장). 마지막 활동 시각을 이때로 — TTL은 여기서부터 잰다. */
    boolean removeMember(String sessionId, String member) {
        return update("""
                UPDATE public.write_lock
                   SET version = version + 1, members = array_remove(members, ?), last_activity_at = now()
                 WHERE db_name = current_database() AND session_id = ? AND ? = ANY(members)
                """, member, sessionId, member) == 1;
    }

    boolean markWaiter(long observedVersion, ProcessIdentity waiter, String purpose) {
        return update("""
                UPDATE public.write_lock
                   SET version = version + 1, waiter_pid = ?, waiter_start = ?, waiter_purpose = ?,
                       waiter_since = CASE WHEN waiter_pid = ? AND waiter_start = ? THEN waiter_since ELSE now() END
                 WHERE db_name = current_database() AND version = ?
                """, waiter.pid(), waiter.startEpochSecond(), purpose,
                waiter.pid(), waiter.startEpochSecond(), observedVersion) == 1;
    }

    boolean clearWaiter(ProcessIdentity waiter) {
        return update("""
                UPDATE public.write_lock
                   SET version = version + 1, waiter_pid = NULL, waiter_start = NULL, waiter_purpose = NULL,
                       waiter_since = NULL
                 WHERE db_name = current_database() AND waiter_pid = ? AND waiter_start = ?
                """, waiter.pid(), waiter.startEpochSecond()) == 1;
    }

    /** 이 토큰이 아직 보유 중일 때만 비운다. 대기 표지는 남긴다 — 표지의 주인이 다음 차례다. */
    boolean release(UUID token) {
        return update("""
                UPDATE public.write_lock
                   SET version = version + 1, holder_kind = NULL, token = NULL, purpose = NULL, session_id = NULL,
                       holder_pid = NULL, holder_start = NULL, members = '{}', acquired_at = NULL,
                       heartbeat_at = NULL, last_activity_at = now()
                 WHERE db_name = current_database() AND token = ?
                """, token) == 1;
    }

    /** 박동. version을 올리지 않는다(위 javadoc). 0행이면 이 토큰은 더 이상 보유자가 아니다. */
    boolean heartbeat(UUID token) {
        return update("""
                UPDATE public.write_lock SET heartbeat_at = now()
                 WHERE db_name = current_database() AND token = ?
                """, token) == 1;
    }

    private int update(String sql, Object... args) {
        return query(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                for (int i = 0; i < args.length; i++) {
                    statement.setObject(i + 1, args[i]);
                }
                return statement.executeUpdate();
            }
        });
    }

    private <T> T query(SqlWork<T> work) {
        Properties properties = new Properties();
        if (username != null) {
            properties.setProperty("user", username);
        }
        if (password != null && !password.isEmpty()) {
            properties.setProperty("password", password);
        }
        properties.setProperty("ApplicationName", applicationName);
        try (Connection connection = DriverManager.getConnection(url, properties)) {
            connection.setAutoCommit(true);
            return work.run(connection);
        } catch (SQLException e) {
            throw new WriteLockStoreException("쓰기 잠금 표를 읽거나 쓰지 못했다: " + e.getMessage(), e);
        }
    }

    private static Long longOrNull(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    @FunctionalInterface
    private interface SqlWork<T> {
        T run(Connection connection) throws SQLException;
    }

    /** 잠금 표에 닿지 못했다. 획득 경로에서는 거절과 같게 다룬다 — 보유를 확인 못 한 채 쓰지 않는다. */
    static class WriteLockStoreException extends RuntimeException {
        WriteLockStoreException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
