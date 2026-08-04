package com.stockscreener.auth;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Locale;

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

    /** 허용목록에 추가한다. 이미 있으면 관리자 권한만 갱신한다. */
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
     */
    @Transactional
    public boolean revoke(String email) {
        return jdbcTemplate.update(
                "DELETE FROM allowed_email WHERE lower(email) = ?", normalize(email)) > 0;
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
