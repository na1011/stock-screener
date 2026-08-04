package com.stockscreener.auth;

import com.stockscreener.support.TestDatabaseGuard;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.test.context.support.WithAnonymousUser;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 엔드포인트 수준의 접근 제어.
 *
 * <p>관리자 영역이 별도 애플리케이션이 아니라 <b>권한으로</b> 분리되는 것을 검증한다
 * ({@code backend.md}). 권한 없는 사용자에게 관리 기능이 보이면 운영 구조가 드러난다.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestDatabaseGuard.class)
class SecurityConfigTest {

    private MockMvc mockMvc;

    @Autowired
    void setUp(WebApplicationContext context) {
        this.mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    @Test
    @WithAnonymousUser
    @DisplayName("미인증 사용자는 API에 접근할 수 없다 — 401을 준다(로그인 페이지 리다이렉트가 아니라)")
    void anonymousIsRejected() throws Exception {
        mockMvc.perform(get("/api/screening")).andExpect(status().isUnauthorized());
    }

    @Test
    @WithMockUser(authorities = AllowlistOidcUserService.ROLE_USER)
    @DisplayName("일반 사용자는 관리자 영역에 접근할 수 없다")
    void normalUserCannotAccessAdmin() throws Exception {
        mockMvc.perform(get("/api/admin/batches")).andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(authorities = {AllowlistOidcUserService.ROLE_USER, AllowlistOidcUserService.ROLE_ADMIN})
    @DisplayName("관리자는 관리자 영역을 통과한다 — 핸들러가 없어 404지만 인가는 통과했다")
    void adminPassesAuthorization() throws Exception {
        mockMvc.perform(get("/api/admin/batches")).andExpect(status().isNotFound());
    }

    @Test
    @WithAnonymousUser
    @DisplayName("미인증 사용자도 관리자 영역에서는 401이다 — 존재 여부를 드러내지 않는다")
    void anonymousGetsUnauthorizedOnAdmin() throws Exception {
        mockMvc.perform(get("/api/admin/batches")).andExpect(status().isUnauthorized());
    }
}
