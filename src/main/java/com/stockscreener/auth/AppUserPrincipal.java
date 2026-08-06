package com.stockscreener.auth;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.OidcUserInfo;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;

import java.util.Collection;

/**
 * 로그인 세션에 담기는 사용자. Google이 준 정보에 <b>우리 쪽 id</b>를 얹는다.
 *
 * <p>존재 이유는 하나다 — 저장된 화면과 워치리스트는 {@code app_user.id}를 소유자로 갖는데,
 * 인증 주체가 들고 있는 것은 Google {@code sub}뿐이다. 이것이 없으면 화면을 저장하는 모든
 * 요청이 매번 {@code app_user}를 조회해야 하고, 그 조회를 빠뜨린 곳은 소유자를 잘못 넣는다.
 *
 * <p>{@code sub}가 아니라 로컬 id를 소유자로 쓰는 이유는 ADR-0004에 있다 — 이메일은 바뀔 수
 * 있고 인증 제공자도 바뀔 수 있지만, 저장된 화면은 그대로 남아야 한다.
 */
public class AppUserPrincipal extends DefaultOidcUser {

    private final long userId;

    public AppUserPrincipal(Collection<? extends GrantedAuthority> authorities,
                            OidcIdToken idToken,
                            OidcUserInfo userInfo,
                            long userId) {
        super(authorities, idToken, userInfo, "sub");
        this.userId = userId;
    }

    /** {@code app_user.id}. 저장된 화면·워치리스트의 소유자 식별자다. */
    public long getUserId() {
        return userId;
    }
}
