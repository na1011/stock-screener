package com.stockscreener.web;

import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.resource.PathResourceResolver;

import java.io.IOException;
import java.time.Duration;
import java.util.List;

/**
 * HTTP 계층의 공통 설정.
 *
 * <p>{@code @EnableWebMvc}를 붙이지 않는다 — 붙이면 Boot의 자동 구성(메시지 컨버터,
 * 정적 자원, ProblemDetail 설정)이 통째로 꺼지고, 그 사실이 응답 형식이 바뀌고 나서야 드러난다.
 */
@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    /** SPA 번들의 진입 문서. Gradle의 {@code processResources}가 여기에 놓는다(ADR-0018). */
    static final String INDEX_LOCATION = "static/index.html";

    /**
     * 폴백을 적용하지 않을 경로 접두.
     *
     * <p>IMPORTANT — 이것이 없으면 없는 API 경로가 404 대신 {@code index.html}을 200으로
     * 돌려준다. 프론트는 그것을 JSON으로 파싱하다 "Unexpected token '&lt;'"로 죽고, 원인이
     * 라우팅 설정이라는 것은 드러나지 않는다.
     */
    private static final List<String> BACKEND_PREFIXES = List.of("api/", "actuator/");

    private final CurrentUserIdArgumentResolver currentUserIdArgumentResolver;

    public WebMvcConfig(CurrentUserIdArgumentResolver currentUserIdArgumentResolver) {
        this.currentUserIdArgumentResolver = currentUserIdArgumentResolver;
    }

    @Override
    public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
        resolvers.add(currentUserIdArgumentResolver);
    }

    /**
     * SPA 번들을 서빙하고 클라이언트 라우트를 {@code index.html}로 되돌린다.
     *
     * <p>브라우저가 {@code /stocks/42}를 새로고침하면 서버에 그 경로로 요청이 온다. 서버에는
     * 해당 핸들러도 파일도 없으므로 폴백이 없으면 404다 — 화면은 링크로만 도달할 수 있게 되고
     * 주소 공유도 깨진다.
     *
     * <p>{@code /assets/**}를 따로 잡는 이유는 <b>폴백을 적용하지 않기 위해서</b>다. 번들
     * 파일명에는 해시가 붙으므로 없는 자원을 요청했다는 것은 배포가 어긋났다는 뜻이고, 그때
     * {@code index.html}을 200으로 돌려주면 브라우저가 HTML을 JS로 실행하려다 실패한다.
     * 404가 나야 원인이 보인다. 해시가 있으니 캐시는 영구로 둘 수 있다.
     */
    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/assets/**")
                .addResourceLocations("classpath:/static/assets/")
                .setCacheControl(CacheControl.maxAge(Duration.ofDays(365)).cachePublic().immutable());

        // 서체는 2MB짜리 한 덩어리다(ADR-0022). 아래 "/**"에 걸리면 no-cache가 붙어 방문마다
        // 조건부 요청이 오간다. 파일명에 버전이 박혀 있으므로 immutable로 둘 수 있고, 버전을
        // 올리면 이름이 바뀌어 캐시가 저절로 갈린다. 폴백을 걸지 않는 이유는 /assets/**와 같다 —
        // 없는 서체에 index.html을 200으로 주면 브라우저는 HTML을 서체로 파싱하려다 조용히 만다.
        registry.addResourceHandler("/fonts/**")
                .addResourceLocations("classpath:/static/fonts/")
                .setCacheControl(CacheControl.maxAge(Duration.ofDays(365)).cachePublic().immutable());

        // 이 등록이 Boot 자동 구성의 "/**" 등록을 대체한다(우리 설정이 나중에 적용된다).
        // index.html은 캐시하지 않는다 — 캐시되면 새 번들을 배포해도 옛 자산 해시를 가리킨다.
        registry.addResourceHandler("/**")
                .addResourceLocations("classpath:/static/")
                .setCacheControl(CacheControl.noCache())
                .resourceChain(true)
                .addResolver(new SpaFallbackResolver());
    }

    /** 실제 자원이 없으면 {@code index.html}로 되돌린다. 단, 백엔드 경로는 되돌리지 않는다. */
    private static final class SpaFallbackResolver extends PathResourceResolver {

        private final Resource index = new ClassPathResource(INDEX_LOCATION);

        @Override
        protected Resource getResource(String resourcePath, Resource location) throws IOException {
            Resource requested = super.getResource(resourcePath, location);
            if (requested != null) {
                return requested;
            }
            if (BACKEND_PREFIXES.stream().anyMatch(resourcePath::startsWith)) {
                return null;
            }
            return index.exists() ? index : null;
        }
    }
}
