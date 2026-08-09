package com.stockscreener.auth;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * 허용 이메일 목록을 관리하고 접근 여부를 판정한다.
 *
 * <p>spec §6이 정한 "Google OAuth + 허용 이메일 목록"에서 <b>허용 여부 판별</b>을 담당한다.
 * 인증(누구인지 확인)은 Google에 위임하고, 인가(들여보낼지)는 서버가 직접 한다.
 *
 * <p>이 클래스는 Google 자격증명 없이 완전히 동작한다. 입력이 이메일 문자열이고
 * 출력이 판정 결과이므로, OAuth 핸드쉐이크가 구현되기 전에도 검증할 수 있다.
 */
@Service
public class AllowedEmailService {

    private final JdbcTemplate jdbcTemplate;

    public AllowedEmailService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 이 이메일이 서비스에 접근할 수 있는가.
     *
     * <p>MUST: 이메일을 정규화해서 비교한다. Google이 돌려주는 이메일의 대소문자는
     * 사용자가 입력한 대로이고, 관리자가 목록에 넣을 때 앞뒤 공백이 붙기도 한다.
     * 정규화하지 않으면 허용해둔 지인이 조용히 차단되고 원인을 찾기 어렵다.
     */
    @Transactional(readOnly = true)
    public AccessDecision decide(String email) {
        String normalized = normalize(email);
        if (normalized.isEmpty()) {
            return AccessDecision.denied();
        }

        List<Boolean> found = jdbcTemplate.queryForList(
                "SELECT is_admin FROM allowed_email WHERE lower(email) = ?",
                Boolean.class, normalized);

        if (found.isEmpty()) {
            return AccessDecision.denied();
        }
        return AccessDecision.allowed(Boolean.TRUE.equals(found.get(0)));
    }

    /**
     * 허용목록에 추가한다. 이미 있으면 관리자 권한만 갱신한다.
     *
     * <p>IMPORTANT — <b>마지막 관리자 검사를 걸지 않는다.</b> 이것은 목록 상태를 그대로 쓰는
     * 저수준 API이고, 관리자를 강등하는 것이 정당한 호출이다(권한 회수 시나리오). 검사가 붙는
     * 곳은 화면이 부르는 {@link #add}·{@link #update}·{@link #remove}다 — 완료 판정이 막으려는
     * 것은 "관리자가 <b>화면에서</b> 스스로 잠기는 것"이고, 그 상태의 복구 수단이 이 저수준
     * 경로(와 SQL)이기 때문이다. 여기까지 잠그면 복구 수단이 사라진다.
     */
    @Transactional
    public void allow(String email, boolean admin, String note) {
        String normalized = normalize(email);
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("이메일이 비어 있다");
        }
        jdbcTemplate.update("""
                INSERT INTO allowed_email (email, is_admin, note, created_at, updated_at)
                VALUES (?, ?, ?, now(), now())
                ON CONFLICT (lower(email)) DO UPDATE SET
                    is_admin = EXCLUDED.is_admin,
                    note = COALESCE(EXCLUDED.note, allowed_email.note),
                    updated_at = now()
                """, normalized, admin, note);
    }

    /**
     * 허용목록에서 제거한다.
     *
     * <p>사용자의 저장된 화면은 지우지 않는다 — 다시 초대했을 때 남아 있어야 하기 때문이다.
     * 그래서 {@code app_user}와 {@code allowed_email} 사이에 FK가 없다.
     *
     * <p>{@link #allow}와 같은 이유로 마지막 관리자 검사가 없다. 화면이 부르는 것은
     * {@link #remove}다.
     */
    @Transactional
    public boolean revoke(String email) {
        return jdbcTemplate.update(
                "DELETE FROM allowed_email WHERE lower(email) = ?", normalize(email)) > 0;
    }

    // ── 관리자 화면 (spec §4 "계정 관리") ────────────────────────────────

    /**
     * 허용목록 전체.
     *
     * <p>{@code app_user}를 LEFT JOIN해 마지막 로그인을 함께 준다. <b>허용목록에만 있고 아직
     * 로그인하지 않은 사람이 정상 상태</b>이므로(ADR-0004) 그 둘을 화면이 구분할 수 있어야
     * 한다 — 구분이 없으면 초대장을 다시 보내야 할 사람을 알 방법이 없다.
     *
     * <p>조인은 표시용이다. 인가 경로({@link #decide})는 여전히 {@code allowed_email}만 본다.
     */
    @Transactional(readOnly = true)
    public List<AllowedEmail> list() {
        return jdbcTemplate.query("""
                SELECT a.id, a.email, a.is_admin, a.note, a.created_at, a.updated_at,
                       u.last_login_at
                FROM allowed_email a
                LEFT JOIN app_user u ON lower(u.email) = lower(a.email)
                ORDER BY a.is_admin DESC, lower(a.email)
                """, ROW_MAPPER);
    }

    /**
     * 새 이메일을 허용한다.
     *
     * <p>{@link #allow}와 달리 이미 있으면 <b>거절한다.</b> 관리자가 "추가"를 눌렀는데 기존
     * 항목의 권한이 조용히 바뀌면, 바뀐 줄 모르는 채로 권한을 준 것이 된다.
     */
    @Transactional
    public AllowedEmail add(String email, boolean admin, String note) {
        String normalized = normalize(email);
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("이메일이 비어 있다");
        }
        if (!jdbcTemplate.queryForList(
                "SELECT 1 FROM allowed_email WHERE lower(email) = ?", normalized).isEmpty()) {
            throw new DuplicateAllowedEmailException(normalized);
        }

        Long id = jdbcTemplate.queryForObject("""
                INSERT INTO allowed_email (email, is_admin, note, created_at, updated_at)
                VALUES (?, ?, ?, now(), now())
                RETURNING id
                """, Long.class, normalized, admin, note);
        return findById(id).orElseThrow();
    }

    /** 관리자 권한과 메모를 바꾼다. 이메일은 바꿀 수 없다 — 다른 사람을 초대하는 것이다. */
    @Transactional
    public AllowedEmail update(long id, boolean admin, String note) {
        long adminsBefore = adminCount();
        int updated = jdbcTemplate.update("""
                UPDATE allowed_email SET is_admin = ?, note = ?, updated_at = now()
                WHERE id = ?
                """, admin, note, id);
        if (updated == 0) {
            throw new AllowedEmailNotFoundException(id);
        }
        requireSurvivingAdmin(adminsBefore);
        return findById(id).orElseThrow();
    }

    @Transactional
    public void remove(long id) {
        long adminsBefore = adminCount();
        if (jdbcTemplate.update("DELETE FROM allowed_email WHERE id = ?", id) == 0) {
            throw new AllowedEmailNotFoundException(id);
        }
        requireSurvivingAdmin(adminsBefore);
    }

    @Transactional(readOnly = true)
    public Optional<AllowedEmail> findById(long id) {
        return jdbcTemplate.query("""
                SELECT a.id, a.email, a.is_admin, a.note, a.created_at, a.updated_at,
                       u.last_login_at
                FROM allowed_email a
                LEFT JOIN app_user u ON lower(u.email) = lower(a.email)
                WHERE a.id = ?
                """, ROW_MAPPER, id).stream().findFirst();
    }

    /**
     * 마지막 관리자를 없애는 변경을 거부한다.
     *
     * <p>IMPORTANT — 변경을 <b>적용한 뒤에</b> 센다. 미리 시뮬레이션하면 경로마다 "이 변경이
     * 관리자 수를 줄이는가"를 따로 판단해야 하고, 새 경로를 더할 때 그 판단을 빠뜨린 사실은
     * <b>아무도 관리자 화면에 못 들어가게 된 뒤에야</b> 드러난다. 여기서 던지면 트랜잭션이
     * 통째로 롤백되므로 적용해 보고 되돌리는 편이 안전하다.
     *
     * <p>막는 것은 "관리자가 0명인 상태"가 아니라 <b>이 변경이 0명으로 만든 것</b>이다.
     * 애초에 관리자가 없던 상태(초기 구축, 테스트 픽스처)에서 항목을 더하거나 지우는 것까지
     * 막으면, 첫 관리자를 넣을 방법이 없어진다.
     *
     * <p>롤백은 이 메서드를 부르는 {@code @Transactional} 경계가 한다. 호출부가 이미
     * 트랜잭션 안이면(테스트가 그렇다) 그 경계까지 롤백이 미뤄지므로, 되돌림을 실제로
     * 검증하려는 테스트는 자기 트랜잭션 없이 돌아야 한다({@code .claude/rules/test.md}).
     */
    private void requireSurvivingAdmin(long adminsBefore) {
        if (adminsBefore > 0 && adminCount() == 0) {
            throw new LastAdminException();
        }
    }

    private long adminCount() {
        Long count = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM allowed_email WHERE is_admin", Long.class);
        return count == null ? 0 : count;
    }

    private static final RowMapper<AllowedEmail> ROW_MAPPER = (rs, rowNum) -> new AllowedEmail(
            rs.getLong("id"),
            rs.getString("email"),
            rs.getBoolean("is_admin"),
            rs.getString("note"),
            instantOf(rs, "created_at"),
            instantOf(rs, "updated_at"),
            instantOf(rs, "last_login_at"));

    private static Instant instantOf(ResultSet rs, String column) throws SQLException {
        Timestamp timestamp = rs.getTimestamp(column);
        return timestamp == null ? null : timestamp.toInstant();
    }

    /**
     * 허용목록 한 줄.
     *
     * @param lastLoginAt 아직 로그인하지 않았으면 {@code null}. 정상 상태다(ADR-0004).
     */
    public record AllowedEmail(long id, String email, boolean admin, String note,
                               Instant createdAt, Instant updatedAt, Instant lastLoginAt) {
    }

    /** 관리자가 한 명도 남지 않는 변경이다. 그 상태의 복구 수단은 SQL뿐이다. */
    public static class LastAdminException extends RuntimeException {
        public LastAdminException() {
            super("마지막 관리자의 권한은 회수할 수 없다");
        }
    }

    public static class DuplicateAllowedEmailException extends RuntimeException {
        private final String email;

        public DuplicateAllowedEmailException(String email) {
            super("이미 허용목록에 있는 이메일이다: " + email);
            this.email = email;
        }

        public String email() {
            return email;
        }
    }

    public static class AllowedEmailNotFoundException extends RuntimeException {
        private final long id;

        public AllowedEmailNotFoundException(long id) {
            super("허용목록 항목을 찾을 수 없다: " + id);
            this.id = id;
        }

        public long id() {
            return id;
        }
    }

    /**
     * 이메일 정규화. 소문자로 바꾸고 앞뒤 공백을 제거한다.
     *
     * <p>DB의 유니크 인덱스도 {@code lower(email)}에 걸려 있어 관리자가 직접 INSERT해도
     * 중복이 막힌다.
     */
    static String normalize(String email) {
        return email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
    }
}
