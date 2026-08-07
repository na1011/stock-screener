package com.stockscreener.auth.web;

import com.stockscreener.auth.AllowlistOidcUserService;
import com.stockscreener.support.TestDatabaseGuard;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.context.support.WithAnonymousUser;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code GET /api/me} — SPA의 세션 프로브.
 *
 * <p>{@code admin}이 세션의 권한에서 나오는지가 핵심 검사다. {@code app_user.is_admin}에서
 * 읽으면 권한을 회수한 뒤 재로그인 전까지 화면은 관리자 메뉴를 그리는데 요청은 403이 된다.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestDatabaseGuard.class)
@Transactional
class MeControllerTest {

    private static final String SUB = "sub-me-controller";

    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    void setUp(WebApplicationContext context) {
        this.mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    @BeforeEach
    void seed() {
        jdbcTemplate.update("""
                INSERT INTO app_user (email, name, provider, provider_user_id, is_admin, is_active)
                VALUES ('me@example.com','나','GOOGLE',?,true,true) ON CONFLICT DO NOTHING
                """, SUB);
    }

    @Test
    @WithAnonymousUser
    @DisplayName("익명은 401이다 — 그것이 곧 로그인 화면을 그리라는 신호다")
    void anonymousIsUnauthorized() throws Exception {
        mockMvc.perform(get("/api/me")).andExpect(status().isUnauthorized());
    }

    @Test
    @WithMockUser(username = SUB, authorities = AllowlistOidcUserService.ROLE_USER)
    @DisplayName("로그인한 사용자는 자기 정보를 받는다")
    void returnsProfile() throws Exception {
        mockMvc.perform(get("/api/me"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value("me@example.com"))
                .andExpect(jsonPath("$.name").value("나"))
                .andExpect(jsonPath("$.userId").isNumber());
    }

    @Test
    @WithMockUser(username = SUB, authorities = AllowlistOidcUserService.ROLE_USER)
    @DisplayName("admin은 테이블이 아니라 세션 권한에서 나온다")
    void adminComesFromAuthorities() throws Exception {
        // 이 사용자의 app_user.is_admin은 true지만 세션에는 ROLE_ADMIN이 없다.
        // 테이블에서 읽었다면 여기서 true가 나오고, 화면은 관리자 메뉴를 그린 뒤 403을 만난다.
        mockMvc.perform(get("/api/me")).andExpect(jsonPath("$.admin").value(false));
    }

    @Test
    @WithMockUser(username = SUB,
            authorities = {AllowlistOidcUserService.ROLE_USER, AllowlistOidcUserService.ROLE_ADMIN})
    @DisplayName("ROLE_ADMIN을 가진 세션은 admin이 참이다")
    void adminIsTrueWithRole() throws Exception {
        mockMvc.perform(get("/api/me")).andExpect(jsonPath("$.admin").value(true));
    }
}
