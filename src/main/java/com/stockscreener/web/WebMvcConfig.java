package com.stockscreener.web;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.List;

/**
 * HTTP 계층의 공통 설정.
 *
 * <p>{@code @EnableWebMvc}를 붙이지 않는다 — 붙이면 Boot의 자동 구성(메시지 컨버터,
 * 정적 자원, ProblemDetail 설정)이 통째로 꺼지고, 그 사실이 응답 형식이 바뀌고 나서야 드러난다.
 */
@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    private final CurrentUserIdArgumentResolver currentUserIdArgumentResolver;

    public WebMvcConfig(CurrentUserIdArgumentResolver currentUserIdArgumentResolver) {
        this.currentUserIdArgumentResolver = currentUserIdArgumentResolver;
    }

    @Override
    public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
        resolvers.add(currentUserIdArgumentResolver);
    }
}
