package com.stockscreener.auth;

import com.stockscreener.support.TestDatabaseGuard;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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
    @DisplayName("관리자는 관리자 영역을 통과한다")
    void adminPassesAuthorization() throws Exception {
        // Phase 6에서 핸들러가 붙기 전까지 이 단언은 404였다("인가는 통과했으나 핸들러가 없다").
        // 그 상태를 그대로 두면 기능을 붙인 쪽이 깨지므로 함께 옮겼다 — 설계 §5가 이 갱신을
        // 판정 항목으로 적어 둔 이유다.
        mockMvc.perform(get("/api/admin/batches")).andExpect(status().isOk());
    }

    @Test
    @WithAnonymousUser
    @DisplayName("미인증 사용자도 관리자 영역에서는 401이다 — 존재 여부를 드러내지 않는다")
    void anonymousGetsUnauthorizedOnAdmin() throws Exception {
        mockMvc.perform(get("/api/admin/batches")).andExpect(status().isUnauthorized());
    }

    @ParameterizedTest
    @ValueSource(strings = {"/api/indicators", "/api/universes", "/api/screens", "/api/stocks/1", "/api/me"})
    @WithAnonymousUser
    @DisplayName("Phase 4에서 추가된 엔드포인트도 전부 인증을 요구한다")
    void newEndpointsRequireAuthentication(String path) throws Exception {
        // 새 엔드포인트를 만들 때마다 이 목록에 한 줄을 더한다. permitAll이 실수로 넓어지면
        // 여기가 깨진다 — 그 실수는 배포 후에는 눈으로 확인할 방법이 없다.
        mockMvc.perform(get(path)).andExpect(status().isUnauthorized());
    }

    @ParameterizedTest
    @ValueSource(strings = {"/", "/login", "/screens", "/stocks/1", "/아무거나"})
    @WithAnonymousUser
    @DisplayName("정적 셸은 익명에게 열려 있다 — 401이 아니다")
    void shellIsOpenToAnonymous(String path) throws Exception {
        // 여기서 401이 나오면 로그인하지 않은 사용자에게 보여줄 화면 자체가 없어진다.
        // 서버가 그리는 로그인 페이지가 없기 때문이다(엔트리포인트가 401을 준다).
        // 상태 코드를 200으로 단언하지 않는 이유는 번들 유무에 따라 404일 수 있어서다 —
        // 여기서 검증하려는 것은 서빙이 아니라 인가다. 서빙은 SpaFallbackTest가 본다.
        mockMvc.perform(get(path))
                .andExpect(result -> {
                    int status = result.getResponse().getStatus();
                    if (status == 401) {
                        throw new AssertionError(path + "이 401이다 — 정적 셸이 닫혀 있으면 "
                                + "익명 사용자는 로그인 버튼도 볼 수 없다.");
                    }
                });
    }

    @Test
    @WithAnonymousUser
    @DisplayName("actuator는 health만 열려 있다 — 나머지는 관리자만이다")
    void actuatorIsLockedDown() throws Exception {
        // actuator는 아직 클래스패스에 없다(Phase 6). 도입 시점에 anyRequest().permitAll()이
        // 조용히 구멍이 되지 않게 매처를 미리 걸어 두고, 그것이 살아 있는지 여기서 본다.
        mockMvc.perform(get("/actuator/env")).andExpect(status().isUnauthorized());
    }
}
