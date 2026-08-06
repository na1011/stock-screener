package com.stockscreener.auth;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserRequest;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserService;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.stereotype.Service;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Google이 인증한 사용자를 허용목록과 대조해 들여보낼지 판정한다.
 *
 * <p>인증(누구인지)은 Google이, 인가(들여보낼지)는 여기서 한다. 허용목록에 없으면
 * 세션을 만들지 않고 인증 자체를 거부한다 — 로그인은 되지만 화면이 비어 있는 상태를
 * 만들지 않는다.
 *
 * <p>{@code OidcUserService}를 상속해 Google 호출 부분은 그대로 쓰고 판정만 덧붙인다.
 * 그 덕에 이 클래스는 자격증명 없이도 {@link #authorize} 경로만 따로 테스트할 수 있다.
 */
@Service
public class AllowlistOidcUserService extends OidcUserService {

    public static final String ROLE_USER = "ROLE_USER";
    public static final String ROLE_ADMIN = "ROLE_ADMIN";

    private final AllowedEmailService allowedEmailService;
    private final AppUserService appUserService;

    public AllowlistOidcUserService(AllowedEmailService allowedEmailService, AppUserService appUserService) {
        this.allowedEmailService = allowedEmailService;
        this.appUserService = appUserService;
    }

    @Override
    public OidcUser loadUser(OidcUserRequest userRequest) throws OAuth2AuthenticationException {
        OidcUser oidcUser = super.loadUser(userRequest);
        return authorize(oidcUser);
    }

    /**
     * 인증된 사용자를 허용목록과 대조하고 권한을 부여한다.
     *
     * <p>Google 호출과 분리되어 있어 합성 {@code OidcUser}로 테스트할 수 있다.
     * 그것이 자격증명 없이 spec §9의 "허가되지 않은 계정은 접근이 차단된다"를
     * 검증하는 경로다.
     *
     * @throws OAuth2AuthenticationException 허용목록에 없는 경우
     */
    public OidcUser authorize(OidcUser oidcUser) {
        String email = oidcUser.getEmail();
        AccessDecision decision = allowedEmailService.decide(email);

        if (!decision.allowed()) {
            // 구체적인 사유를 사용자에게 노출하지 않는다. 허용목록의 존재 자체가
            // 운영 정보이고, 지인이 쓰는 서비스라도 경계는 유지한다.
            throw new OAuth2AuthenticationException(
                    new OAuth2Error("access_denied", "허용되지 않은 계정이다", null),
                    "허용목록에 없는 이메일: " + AllowedEmailService.normalize(email));
        }

        long userId = appUserService.loginOrRegister(
                oidcUser.getSubject(),
                email,
                oidcUser.getFullName(),
                oidcUser.getPicture(),
                decision.admin());

        Set<GrantedAuthority> authorities = new LinkedHashSet<>(oidcUser.getAuthorities());
        authorities.add(new SimpleGrantedAuthority(ROLE_USER));
        if (decision.admin()) {
            authorities.add(new SimpleGrantedAuthority(ROLE_ADMIN));
        }

        // 반환된 id를 버리지 않고 주체에 실어 보낸다. 화면 저장 경로가 매 요청마다
        // app_user를 다시 조회하지 않아도 되고, 조회를 빠뜨려 소유자를 잘못 넣는 일이 없다.
        return new AppUserPrincipal(authorities, oidcUser.getIdToken(), oidcUser.getUserInfo(), userId);
    }
}
