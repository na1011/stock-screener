package com.stockscreener.auth;

import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * 인증 주체에서 {@code app_user.id}를 끌어낸다.
 *
 * <p>두 경로가 있다.
 * <ol>
 *   <li><b>운영</b> — 주체가 {@link AppUserPrincipal}이면 로그인 시 실어둔 id를 그대로 쓴다. 쿼리 0회.
 *   <li><b>폴백</b> — 그 외의 인증된 주체는 {@code getName()}(Google sub)으로 조회한다.
 * </ol>
 *
 * <p>폴백을 두는 이유가 테스트 편의만은 아니다. 세션이 살아 있는 채로 배포가 일어나면 직렬화된
 * 옛 주체가 남아 있을 수 있고, 폴백이 없으면 그 사용자들이 다시 로그인할 때까지 화면 저장이
 * 실패한다.
 *
 * <p>이 클래스가 따로 있는 이유는 <b>같은 판정을 두 곳이 필요로 하는데 실패 처리가 정반대</b>이기
 * 때문이다. 핸들러 인자 주입({@code CurrentUserIdArgumentResolver})은 찾지 못하면 던져야 하고
 * — 0이나 null을 소유자로 넣으면 그 행은 아무에게도 보이지 않는 유령이 된다 — 인터랙션 기록은
 * 찾지 못해도 던져서는 안 된다. 판정 자체를 양쪽에 복사하면 한쪽만 고쳐지는 날이 온다.
 */
@Component
public class CurrentUserLookup {

    private final AppUserService appUserService;

    public CurrentUserLookup(AppUserService appUserService) {
        this.appUserService = appUserService;
    }

    /** 찾지 못하면 비어 있다. 인증되지 않은 주체도 비어 있다. */
    public Optional<Long> resolve(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()) {
            return Optional.empty();
        }
        if (authentication.getPrincipal() instanceof AppUserPrincipal principal) {
            return Optional.of(principal.getUserId());
        }
        return appUserService.findIdByProviderUserId(authentication.getName());
    }
}
