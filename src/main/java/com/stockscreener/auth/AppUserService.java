package com.stockscreener.auth;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 로그인한 사용자를 저장·갱신한다.
 *
 * <p>{@code allowed_email}(허용 여부의 근거)과 {@code app_user}(실제 로그인한 사람)를
 * 분리해서 다룬다. 허용목록에만 있고 아직 로그인하지 않은 사람은 Google sub도 이름도 없고,
 * 저장된 화면이 참조할 id도 없기 때문이다.
 */
@Service
public class AppUserService {

    private final JdbcTemplate jdbcTemplate;

    public AppUserService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 로그인 시점에 사용자를 만들거나 갱신한다.
     *
     * <p>인증의 기준은 {@code providerUserId}(Google sub)다. 이메일은 바뀔 수 있지만
     * sub는 불변이므로, 같은 사람이 이메일을 바꿔도 저장된 화면을 잃지 않는다.
     *
     * <p>{@code isAdmin}은 {@code allowed_email}에서 복사한다 — 근거는 허용목록이고
     * 이 컬럼은 세션 판정을 위한 사본이다. 그래서 권한 회수는 다음 로그인부터 적용된다.
     *
     * @return 사용자 id
     */
    @Transactional
    public long loginOrRegister(String providerUserId, String email, String name,
                                String pictureUrl, boolean admin) {
        String normalized = AllowedEmailService.normalize(email);
        return jdbcTemplate.queryForObject("""
                INSERT INTO app_user (email, name, picture_url, provider, provider_user_id,
                                      is_admin, is_active, last_login_at, created_at, updated_at)
                VALUES (?, ?, ?, 'GOOGLE', ?, ?, true, now(), now(), now())
                ON CONFLICT (provider, provider_user_id) DO UPDATE SET
                    email         = EXCLUDED.email,
                    name          = EXCLUDED.name,
                    picture_url   = EXCLUDED.picture_url,
                    is_admin      = EXCLUDED.is_admin,
                    is_active     = true,
                    last_login_at = now(),
                    updated_at    = now()
                RETURNING id
                """, Long.class, normalized, name, pictureUrl, providerUserId, admin);
    }
}
