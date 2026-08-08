package com.stockscreener.web;

import com.stockscreener.auth.CurrentUserLookup;

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
 * <p>판정 자체는 {@link CurrentUserLookup}에 있다. 여기 남는 것은 <b>찾지 못했을 때 어떻게
 * 할 것인가</b>뿐이고, 그 답이 이 자리에서만 "던진다"이기 때문에 분리했다 — 인터랙션 기록은
 * 같은 판정을 쓰면서 찾지 못해도 던지지 않는다.
 *
 * <p>부수 효과로 테스트가 {@code @WithMockUser} + 실제 {@code app_user} 행으로 끝나,
 * 커스텀 인증 애너테이션 없이도 <b>실체적인</b> 검증이 된다.
 *
 * <p>IMPORTANT — 찾지 못하면 예외를 던진다. 0이나 null을 소유자로 넣으면 그 행은 아무에게도
 * 보이지 않는 유령이 되고, DB를 직접 들여다보기 전까지 원인을 알 수 없다.
 */
@Component
public class CurrentUserIdArgumentResolver implements HandlerMethodArgumentResolver {

    private final CurrentUserLookup lookup;

    public CurrentUserIdArgumentResolver(CurrentUserLookup lookup) {
        this.lookup = lookup;
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

        return lookup.resolve(authentication)
                .orElseThrow(() -> new IllegalStateException(
                        "인증은 통과했으나 app_user에 해당 사용자가 없다: " + authentication.getName()
                                + " — 로그인 시 사용자 생성이 실패했거나 행이 지워졌다."));
    }
}
