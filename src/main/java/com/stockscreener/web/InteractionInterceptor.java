package com.stockscreener.web;

import com.stockscreener.auth.CurrentUserLookup;
import com.stockscreener.interaction.InteractionRecorder;
import com.stockscreener.interaction.RecordInteraction;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.HandlerMapping;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * {@link RecordInteraction}이 붙은 핸들러의 호출을 남긴다.
 *
 * <p>수집을 여기 하나로 모으는 이유는 spec §8이 "매 기능을 개발할 때마다 로깅 코드를 별도로
 * 작성해야 하는 부담이 없어야" 한다고 요구하기 때문이다. 컨트롤러는 애너테이션 한 줄만 붙인다.
 *
 * <p>AOP가 아니라 인터셉터인 이유는 셋이다. (1) {@code afterCompletion}이 <b>최종 상태 코드</b>를
 * 그대로 본다 — AOP 어드바이스는 예외가 어떤 상태로 번역될지 모른다. (2) URI 템플릿 변수가
 * 요청 속성에 이미 들어 있다. (3) 프록시 경계를 하나 더 만들지 않는다(ADR-0007이 자기 호출로
 * 겪은 그 경계다).
 *
 * <p>IMPORTANT — {@code afterCompletion}에서 기록한다. {@code postHandle}은 예외가 났을 때
 * 아예 호출되지 않아, <b>실패한 시도가 통째로 기록에서 빠진다.</b> 사용자가 겪은 마찰이
 * 로그에 안 보이는 것이 정확히 이 NFR이 막으려는 상태다.
 */
@Component
public class InteractionInterceptor implements HandlerInterceptor {

    /** 시작 시각. 애너테이션이 붙은 요청에만 심는다. */
    private static final String START_NANOS = InteractionInterceptor.class.getName() + ".startNanos";

    private final InteractionRecorder recorder;
    private final CurrentUserLookup userLookup;

    public InteractionInterceptor(InteractionRecorder recorder, CurrentUserLookup userLookup) {
        this.recorder = recorder;
        this.userLookup = userLookup;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (annotationOf(handler) != null) {
            request.setAttribute(START_NANOS, System.nanoTime());
        }
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
                                Object handler, Exception ex) {

        RecordInteraction annotation = annotationOf(handler);
        if (annotation == null) {
            return;
        }

        recorder.record(
                annotation.value(),
                currentUserId(),
                request.getMethod(),
                request.getRequestURI(),
                response.getStatus(),
                elapsedMillis(request),
                detailOf(request, annotation));
    }

    /** 핸들러가 컨트롤러 메서드가 아니거나(정적 자원) 표시가 없으면 {@code null}. */
    private static RecordInteraction annotationOf(Object handler) {
        if (handler instanceof HandlerMethod method) {
            return method.getMethodAnnotation(RecordInteraction.class);
        }
        return null;
    }

    /**
     * 사용자를 특정하지 못해도 {@code null}로 기록한다.
     *
     * <p>{@code SecurityContextHolder}는 이 시점에 아직 살아 있다 — 컨텍스트를 비우는 것은
     * 필터 체인의 {@code finally}이고, 그것은 DispatcherServlet이 돌아간 <b>뒤</b>다.
     */
    private Long currentUserId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return userLookup.resolve(authentication).orElse(null);
    }

    private static Integer elapsedMillis(HttpServletRequest request) {
        Object started = request.getAttribute(START_NANOS);
        if (!(started instanceof Long startNanos)) {
            return null;
        }
        return (int) TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
    }

    /**
     * URI 템플릿 변수와 화이트리스트 쿼리 파라미터.
     *
     * <p>요청 본문은 담지 않는다 — 담으려면 요청 스트림을 캐싱해야 하고, 그 비용과 저장되는
     * 개인정보에 비해 얻는 것이 적다(ADR-0028).
     */
    @SuppressWarnings("unchecked")
    private static Map<String, String> detailOf(HttpServletRequest request, RecordInteraction annotation) {
        Map<String, String> detail = new LinkedHashMap<>();

        Object templateVariables = request.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE);
        if (templateVariables instanceof Map<?, ?> variables) {
            detail.putAll((Map<String, String>) variables);
        }

        for (String name : annotation.queryParams()) {
            String value = request.getParameter(name);
            if (value != null && !value.isBlank()) {
                detail.put(name, value);
            }
        }
        return detail;
    }
}
