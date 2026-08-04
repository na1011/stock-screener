package com.stockscreener.auth;

import com.stockscreener.support.JpaSliceTest;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Google이 인증한 사용자에 대한 인가 판정.
 *
 * <p>합성 {@link OidcUser}를 직접 만들어 검증한다 — 인가 서버도 자격증명도 필요 없다.
 * 이것이 Google Cloud Console 작업을 기다리지 않고 spec §9의 접근 차단 요구를
 * 완전히 검증할 수 있는 이유다.
 */
@JpaSliceTest
@Import({AllowlistOidcUserService.class, AllowedEmailService.class, AppUserService.class})
class AllowlistOidcUserServiceTest {

    @Autowired
    private AllowlistOidcUserService service;

    @Autowired
    private AllowedEmailService allowedEmailService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("허용목록에 없으면 인증 자체를 거부한다 — 빈 화면을 보여주지 않는다")
    void rejectsUnlistedEmail() {
        OidcUser stranger = oidcUser("google-sub-1", "stranger@example.com", "낯선 사람");

        assertThatThrownBy(() -> service.authorize(stranger))
                .isInstanceOf(OAuth2AuthenticationException.class);
    }

    @Test
    @DisplayName("허용된 사용자에게 ROLE_USER를 준다")
    void grantsUserRole() {
        allowedEmailService.allow("friend@example.com", false, "지인");
        OidcUser friend = oidcUser("google-sub-2", "friend@example.com", "지인");

        OidcUser authorized = service.authorize(friend);

        assertThat(authorityNames(authorized)).contains(AllowlistOidcUserService.ROLE_USER);
        assertThat(authorityNames(authorized)).doesNotContain(AllowlistOidcUserService.ROLE_ADMIN);
    }

    @Test
    @DisplayName("관리자에게는 ROLE_ADMIN까지 준다")
    void grantsAdminRole() {
        allowedEmailService.allow("owner@example.com", true, "운영자");
        OidcUser owner = oidcUser("google-sub-3", "owner@example.com", "운영자");

        OidcUser authorized = service.authorize(owner);

        assertThat(authorityNames(authorized))
                .contains(AllowlistOidcUserService.ROLE_USER, AllowlistOidcUserService.ROLE_ADMIN);
    }

    @Test
    @DisplayName("로그인하면 app_user에 기록된다")
    void recordsUserOnLogin() {
        allowedEmailService.allow("friend@example.com", false, null);

        service.authorize(oidcUser("google-sub-4", "friend@example.com", "지인 이름"));

        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT email, name, is_admin, provider_user_id FROM app_user WHERE provider_user_id = ?",
                "google-sub-4");

        assertThat(row.get("email")).isEqualTo("friend@example.com");
        assertThat(row.get("name")).isEqualTo("지인 이름");
        assertThat(row.get("is_admin")).isEqualTo(false);
    }

    @Test
    @DisplayName("이메일이 바뀌어도 같은 sub면 같은 사용자다 — 저장된 화면을 잃지 않는다")
    void identifiesByProviderSubjectNotEmail() {
        allowedEmailService.allow("old@example.com", false, null);
        allowedEmailService.allow("new@example.com", false, null);

        service.authorize(oidcUser("google-sub-5", "old@example.com", "사람"));
        service.authorize(oidcUser("google-sub-5", "new@example.com", "사람"));

        Integer count = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM app_user WHERE provider_user_id = ?", Integer.class, "google-sub-5");
        String email = jdbcTemplate.queryForObject(
                "SELECT email FROM app_user WHERE provider_user_id = ?", String.class, "google-sub-5");

        assertThat(count).isEqualTo(1);
        assertThat(email).isEqualTo("new@example.com");
    }

    @Test
    @DisplayName("권한 회수는 다음 로그인부터 적용된다 — app_user.is_admin은 사본이다")
    void adminFlagRefreshesOnLogin() {
        allowedEmailService.allow("owner@example.com", true, null);
        service.authorize(oidcUser("google-sub-6", "owner@example.com", "운영자"));
        assertThat(adminFlagOf("google-sub-6")).isTrue();

        allowedEmailService.allow("owner@example.com", false, null);
        service.authorize(oidcUser("google-sub-6", "owner@example.com", "운영자"));

        assertThat(adminFlagOf("google-sub-6")).isFalse();
    }

    @Test
    @DisplayName("대소문자가 달라도 허용목록과 대조된다")
    void matchesAllowlistIgnoringCase() {
        allowedEmailService.allow("friend@example.com", false, null);
        OidcUser upper = oidcUser("google-sub-7", "Friend@Example.COM", "지인");

        OidcUser authorized = service.authorize(upper);

        assertThat(authorityNames(authorized)).contains(AllowlistOidcUserService.ROLE_USER);
    }

    private Boolean adminFlagOf(String sub) {
        return jdbcTemplate.queryForObject(
                "SELECT is_admin FROM app_user WHERE provider_user_id = ?", Boolean.class, sub);
    }

    private static List<String> authorityNames(OidcUser user) {
        return user.getAuthorities().stream().map(GrantedAuthority::getAuthority).toList();
    }

    /** Google 없이 만드는 합성 OIDC 사용자. */
    private static OidcUser oidcUser(String subject, String email, String name) {
        Map<String, Object> claims = Map.of(
                "sub", subject,
                "email", email,
                "name", name);
        OidcIdToken idToken = new OidcIdToken(
                "token-" + subject, Instant.now(), Instant.now().plusSeconds(3600), claims);
        return new DefaultOidcUser(
                List.of(new SimpleGrantedAuthority("OIDC_USER")), idToken, "sub");
    }
}
