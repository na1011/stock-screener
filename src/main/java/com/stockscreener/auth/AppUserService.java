package com.stockscreener.auth;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

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

    /**
     * Google {@code sub}로 사용자 id를 찾는다.
     *
     * <p>운영 경로에서는 로그인 시점에 {@link AppUserPrincipal}이 id를 이미 들고 있으므로
     * 이 조회가 필요 없다. 이것은 주체가 {@code AppUserPrincipal}이 아닌 경우의 폴백이다 —
     * 세션이 남아 있는 채로 배포가 일어났거나, 테스트가 합성 주체를 심었을 때다.
     *
     * <p>폴백이 없으면 그 경우들이 조용히 소유자 없는 상태로 흘러가거나, 테스트마다
     * 커스텀 인증 애너테이션을 만들어야 한다. {@code uq_user_provider} 인덱스를 직격한다.
     *
     * @return 없으면 비어 있음. 호출자가 그것을 오류로 다룬다 — 0이나 null을 소유자로 쓰지 않는다.
     */
    @Transactional(readOnly = true)
    public Optional<Long> findIdByProviderUserId(String providerUserId) {
        List<Long> ids = jdbcTemplate.queryForList(
                "SELECT id FROM app_user WHERE provider = 'GOOGLE' AND provider_user_id = ?",
                Long.class, providerUserId);
        return ids.isEmpty() ? Optional.empty() : Optional.of(ids.get(0));
    }

    /**
     * 화면 머리말에 표시할 사용자 정보.
     *
     * <p>주체({@code AppUserPrincipal})에서 꺼내지 않고 테이블에서 읽는다. 주체는 로그인
     * 시점의 스냅샷이라 이름·이메일이 그 뒤에 바뀌면 세션이 끝날 때까지 옛 값을 보여준다.
     *
     * <p>IMPORTANT — 여기에 {@code is_admin}을 싣지 않는다. 그 컬럼은 로그인 시점에 허용목록에서
     * 복사한 사본이고, 실제로 요청을 통과시키는 것은 <b>세션의 권한</b>이다. 둘이 어긋나면
     * (권한 회수 후 재로그인 전) 화면은 관리자 메뉴를 그리는데 요청은 403이 된다.
     * 관리자 여부의 정본은 {@code Authentication}이다.
     */
    @Transactional(readOnly = true)
    public Optional<UserProfile> findProfileById(long userId) {
        List<UserProfile> rows = jdbcTemplate.query(
                "SELECT id, email, name FROM app_user WHERE id = ?",
                (rs, n) -> new UserProfile(rs.getLong("id"), rs.getString("email"), rs.getString("name")),
                userId);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    /** @param name Google이 이름을 주지 않으면 null이다. 화면이 이메일로 대체한다. */
    public record UserProfile(long id, String email, String name) {
    }
}
