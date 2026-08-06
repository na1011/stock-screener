package com.stockscreener.web;

import com.stockscreener.auth.AppUserPrincipal;
import com.stockscreener.auth.AppUserService;

import org.springframework.core.MethodParameter;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

/**
 * {@link CurrentUserId}가 붙은 파라미터에 {@code app_user.id}를 넣는다.
 *
 * <p>두 경로가 있다.
 * <ol>
 *   <li><b>운영</b> — 주체가 {@link AppUserPrincipal}이면 로그인 시 실어둔 id를 그대로 쓴다. 쿼리 0회.
 *   <li><b>폴백</b> — 그 외의 인증된 주체는 {@code getName()}(Google sub)으로 조회한다.
 * </ol>
 *
 * <p>폴백을 두는 이유가 테스트 편의만은 아니다. 세션이 살아 있는 채로 배포가 일어나면 직렬화된
 * 옛 주체가 남아 있을 수 있고, 폴백이 없으면 그 사용자들이 다시 로그인할 때까지 화면 저장이
 * 실패한다. 부수 효과로 테스트가 {@code @WithMockUser} + 실제 {@code app_user} 행으로 끝나,
 * 커스텀 인증 애너테이션 없이도 <b>실체적인</b> 검증이 된다.
 *
 * <p>IMPORTANT — 찾지 못하면 예외를 던진다. 0이나 null을 소유자로 넣으면 그 행은 아무에게도
 * 보이지 않는 유령이 되고, DB를 직접 들여다보기 전까지 원인을 알 수 없다.
 */
@Component
public class CurrentUserIdArgumentResolver implements HandlerMethodArgumentResolver {

    private final AppUserService appUserService;

    public CurrentUserIdArgumentResolver(AppUserService appUserService) {
        this.appUserService = appUserService;
    }

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        return parameter.hasParameterAnnotation(CurrentUserId.class)
                && (parameter.getParameterType() == Long.class || parameter.getParameterType() == long.class);
    }

    @Override
    public Object resolveArgument(MethodParameter parameter, ModelAndViewContainer mavContainer,
                                  NativeWebRequest webRequest, WebDataBinderFactory binderFactory) {

        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            // SecurityConfig가 이미 401을 주므로 여기 도달하면 설정이 어긋난 것이다.
            throw new IllegalStateException(
                    "인증되지 않은 요청이 @CurrentUserId 핸들러에 도달했다 — SecurityConfig의 경로 설정을 확인하라.");
        }

        if (authentication.getPrincipal() instanceof AppUserPrincipal principal) {
            return principal.getUserId();
        }

        String providerUserId = authentication.getName();
        return appUserService.findIdByProviderUserId(providerUserId)
                .orElseThrow(() -> new IllegalStateException(
                        "인증은 통과했으나 app_user에 해당 사용자가 없다: " + providerUserId
                                + " — 로그인 시 사용자 생성이 실패했거나 행이 지워졌다."));
    }
}
