package com.stockscreener.auth.web;

import com.stockscreener.auth.AllowlistOidcUserService;
import com.stockscreener.auth.AppUserService;
import com.stockscreener.web.CurrentUserId;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 현재 세션의 사용자.
 *
 * <p>익명 요청은 다른 {@code /api/**}와 똑같이 401을 받는다({@code SecurityConfig}). 프론트는
 * 그 401을 "로그인 화면을 그려라"로 읽는다 — 그래서 로그인 여부 판별에 별도 규약이 없다.
 */
@RestController
public class MeController {

    private final AppUserService appUserService;

    public MeController(AppUserService appUserService) {
        this.appUserService = appUserService;
    }

    @GetMapping("/api/me")
    public MeResponse me(@CurrentUserId long userId, Authentication authentication) {
        AppUserService.UserProfile profile = appUserService.findProfileById(userId)
                // 인증을 통과했는데 행이 없다면 설정이나 데이터가 어긋난 것이다. 빈 응답으로
                // 넘기면 화면에 이름 없는 사용자가 로그인한 것처럼 보이고 원인이 드러나지 않는다.
                .orElseThrow(() -> new IllegalStateException(
                        "인증은 통과했으나 app_user에 해당 사용자가 없다: id=" + userId));

        return new MeResponse(profile.id(), profile.email(), profile.name(), isAdmin(authentication));
    }

    private static boolean isAdmin(Authentication authentication) {
        return authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .anyMatch(AllowlistOidcUserService.ROLE_ADMIN::equals);
    }
}
