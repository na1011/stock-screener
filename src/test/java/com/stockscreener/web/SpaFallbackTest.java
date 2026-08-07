package com.stockscreener.web;

import com.stockscreener.support.TestDatabaseGuard;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ClassPathResource;
import org.springframework.security.test.context.support.WithAnonymousUser;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * SPA 폴백 — 클라이언트 라우트는 {@code index.html}로 되돌리되 백엔드 경로는 되돌리지 않는다.
 *
 * <p>가장 중요한 검사는 <b>폴백이 API를 삼키지 않는다</b>는 것이다. 삼키면 없는 API 경로가
 * 404 대신 HTML을 200으로 돌려주고, 프론트는 그것을 JSON으로 파싱하다 죽는다. 그 증상은
 * 라우팅 설정이 아니라 프론트 버그처럼 보인다.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestDatabaseGuard.class)
class SpaFallbackTest {

    private MockMvc mockMvc;

    @Autowired
    void setUp(WebApplicationContext context) {
        this.mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    @BeforeAll
    static void requireBundle() {
        // 번들이 없으면 아래 단언이 전부 404가 되고 원인이 드러나지 않는다. 먼저 가른다.
        assertThat(new ClassPathResource(WebMvcConfig.INDEX_LOCATION).exists())
                .as("classpath:%s가 없다. -PskipFrontend로 빌드했거나 frontend/dist가 비어 있다 — "
                        + "./gradlew processResources를 먼저 돌려라.", WebMvcConfig.INDEX_LOCATION)
                .isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"/login", "/screens", "/stocks/42", "/stocks/42/무언가"})
    @WithAnonymousUser
    @DisplayName("클라이언트 라우트는 새로고침해도 index.html로 온다")
    void clientRoutesFallBackToIndex(String path) throws Exception {
        mockMvc.perform(get(path))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("text/html"))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("id=\"app\"")));
    }

    @Test
    @WithAnonymousUser
    @DisplayName("루트는 Boot의 welcome page가 index.html로 forward한다")
    void rootServesIndex() throws Exception {
        // 루트만 경로가 다르다. 정적 위치에 index.html이 있으면 Boot의
        // WelcomePageHandlerMapping이 "/"를 가로채 forward:index.html로 넘긴다 —
        // 우리 폴백 리졸버까지 오지 않는다. 실제 컨테이너에서는 그 forward가 실행되어
        // 같은 파일이 나가지만, MockMvc는 forward를 실행하지 않으므로 본문이 비어 있다.
        // 그래서 여기서는 본문이 아니라 forward 대상을 단언한다.
        mockMvc.perform(get("/"))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .forwardedUrl("index.html"));
    }

    @Test
    @WithMockUser
    @DisplayName("없는 API 경로는 404다 — 폴백이 삼키지 않는다")
    void unknownApiPathIsNotFound() throws Exception {
        mockMvc.perform(get("/api/nope")).andExpect(status().isNotFound());
    }

    @Test
    @WithMockUser
    @DisplayName("없는 정적 자산은 404다 — index.html을 주면 브라우저가 HTML을 JS로 실행한다")
    void missingAssetIsNotFound() throws Exception {
        // 번들 파일명에는 해시가 붙는다. 없는 자산을 요청했다는 것은 배포가 어긋났다는 뜻이고,
        // 그때 200 HTML을 주면 "Unexpected token '<'"만 남아 원인이 보이지 않는다.
        mockMvc.perform(get("/assets/index-없는해시.js")).andExpect(status().isNotFound());
    }

    @Test
    @WithAnonymousUser
    @DisplayName("본문 서체는 익명에게도 나가고 영구 캐시가 붙는다")
    void fontIsServedImmutable() throws Exception {
        // 로그인 화면부터 서체가 필요하므로 익명으로 확인한다. 캐시 헤더를 함께 단언하는
        // 이유는 이것이 없으면 "/**" 핸들러의 no-cache에 걸려 2MB짜리 조건부 요청이 방문마다
        // 오가기 때문이다 — 화면은 멀쩡해 보이고 느려지기만 한다(ADR-0022).
        mockMvc.perform(get("/fonts/PretendardVariable-1.3.9.woff2"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control",
                        org.hamcrest.Matchers.containsString("immutable")));
    }

    @Test
    @WithAnonymousUser
    @DisplayName("없는 서체는 404다 — index.html을 주면 브라우저가 HTML을 서체로 파싱한다")
    void missingFontIsNotFound() throws Exception {
        mockMvc.perform(get("/fonts/없는서체.woff2")).andExpect(status().isNotFound());
    }
}
