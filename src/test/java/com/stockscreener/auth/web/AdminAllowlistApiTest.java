package com.stockscreener.auth.web;

import com.stockscreener.auth.AllowlistOidcUserService;
import com.stockscreener.support.TestDatabaseGuard;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 허용목록을 화면에서 다룰 수 있고, <b>마지막 관리자가 스스로 잠기지 않는가</b> (spec §4).
 *
 * <p>이 화면이 생기기 전까지 지인을 초대하는 유일한 방법이 psql이었다. 그리고 자기 권한을
 * 회수해 아무도 들어갈 수 없게 되면, 복구 수단도 다시 psql뿐이다 — 설계 §5가 이 한 줄을
 * 판정 항목으로 적어 둔 이유다.
 *
 * <p>IMPORTANT — {@code @Transactional}을 <b>일부러 붙이지 않고</b> {@code @AfterEach}로
 * 정리한다. 거절이 값을 되돌리는지 확인하려면 서비스의 트랜잭션 경계가 진짜여야 하는데,
 * 테스트가 자기 트랜잭션을 열면 서비스가 거기 참여해 롤백이 테스트 끝까지 미뤄진다 —
 * 그러면 "409를 줬지만 값은 바뀐" 최악의 상태를 통과시킨다. 실제로 여기서 한 번 걸렸다.
 * ({@code .claude/rules/test.md}의 "커밋을 요구하는 테스트만 @AfterEach 정리를 쓴다".)
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestDatabaseGuard.class)
@WithMockUser(authorities = {AllowlistOidcUserService.ROLE_USER, AllowlistOidcUserService.ROLE_ADMIN})
class AdminAllowlistApiTest {

    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private long adminId;

    @Autowired
    void setUp(WebApplicationContext context) {
        this.mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    @BeforeEach
    void seed() {
        // 마지막 관리자 판정이 테이블 전체를 세므로 남은 행이 있으면 그 판정이 흔들린다.
        jdbcTemplate.update("DELETE FROM allowed_email");
        adminId = insert("owner@example.com", true, "운영자");
    }

    @AfterEach
    void cleanUp() {
        jdbcTemplate.update("DELETE FROM allowed_email");
    }

    @Test
    @DisplayName("추가·조회·수정·삭제가 왕복한다")
    void roundTrip() throws Exception {
        String body = mockMvc.perform(post("/api/admin/allowed-emails").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"Friend@Example.com","admin":false,"note":"지인"}
                                """))
                .andExpect(status().isCreated())
                // 정규화는 서버가 한다. 안 하면 Foo@x와 foo@x가 따로 등록되어 판정이 어긋난다.
                .andExpect(jsonPath("$.email").value("friend@example.com"))
                .andExpect(jsonPath("$.admin").value(false))
                // 아직 로그인하지 않은 사람이 정상 상태다(ADR-0004).
                .andExpect(jsonPath("$.lastLoginAt").doesNotExist())
                .andReturn().getResponse().getContentAsString();

        long id = Long.parseLong(body.replaceAll(".*\"id\":(\\d+).*", "$1"));

        mockMvc.perform(get("/api/admin/allowed-emails"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2));

        mockMvc.perform(put("/api/admin/allowed-emails/" + id).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"admin":true,"note":"공동 운영자"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.admin").value(true))
                .andExpect(jsonPath("$.note").value("공동 운영자"));

        mockMvc.perform(delete("/api/admin/allowed-emails/" + id).with(csrf()))
                .andExpect(status().isNoContent());
        mockMvc.perform(get("/api/admin/allowed-emails"))
                .andExpect(jsonPath("$.length()").value(1));
    }

    @Test
    @DisplayName("IMPORTANT — 마지막 관리자의 권한은 회수할 수 없다. 회수되면 복구 수단이 SQL뿐이다")
    void cannotDemoteLastAdmin() throws Exception {
        mockMvc.perform(put("/api/admin/allowed-emails/" + adminId).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"admin":false,"note":null}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("urn:stockscreener:last-admin"));

        // 롤백까지 확인한다. 409만 주고 값이 바뀌어 있으면 최악이다 — 화면은 실패로 읽는데
        // 다음 로그인부터 아무도 못 들어온다.
        assertThat(isAdmin(adminId)).isTrue();
    }

    @Test
    @DisplayName("IMPORTANT — 마지막 관리자를 삭제할 수도 없다")
    void cannotDeleteLastAdmin() throws Exception {
        mockMvc.perform(delete("/api/admin/allowed-emails/" + adminId).with(csrf()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("urn:stockscreener:last-admin"));

        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM allowed_email WHERE id = ?", Long.class, adminId))
                .isEqualTo(1);
    }

    @Test
    @DisplayName("관리자가 둘이면 한쪽을 강등할 수 있다 — 막는 것은 마지막 한 명뿐이다")
    void canDemoteWhenAnotherAdminRemains() throws Exception {
        long second = insert("second@example.com", true, "공동 운영자");

        mockMvc.perform(put("/api/admin/allowed-emails/" + second).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"admin":false,"note":null}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.admin").value(false));
    }

    @Test
    @DisplayName("이미 있는 이메일은 409다 — 추가를 눌렀는데 기존 권한이 조용히 바뀌면 안 된다")
    void duplicateEmailIsConflict() throws Exception {
        mockMvc.perform(post("/api/admin/allowed-emails").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"OWNER@example.com","admin":false,"note":null}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("urn:stockscreener:duplicate-allowed-email"));

        // 기존 항목의 관리자 권한이 그대로여야 한다. 업서트였다면 여기서 강등됐을 것이다.
        assertThat(isAdmin(adminId)).isTrue();
    }

    @Test
    @DisplayName("형식이 아닌 이메일은 400이고 어느 필드인지 알려준다 — 오타는 조용히 통과한다")
    void malformedEmailIsRejected() throws Exception {
        mockMvc.perform(post("/api/admin/allowed-emails").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"friend-at-example.com","admin":false,"note":null}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("urn:stockscreener:invalid-request"))
                .andExpect(jsonPath("$.errors[0].field").value("email"));
    }

    @Test
    @DisplayName("없는 항목은 404다")
    void unknownItemIsNotFound() throws Exception {
        mockMvc.perform(delete("/api/admin/allowed-emails/9999999").with(csrf()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.type").value("urn:stockscreener:allowed-email-not-found"));
    }

    @Test
    @WithMockUser(authorities = AllowlistOidcUserService.ROLE_USER)
    @DisplayName("일반 사용자는 허용목록에 손댈 수 없다")
    void normalUserIsForbidden() throws Exception {
        mockMvc.perform(get("/api/admin/allowed-emails")).andExpect(status().isForbidden());
        mockMvc.perform(delete("/api/admin/allowed-emails/" + adminId).with(csrf()))
                .andExpect(status().isForbidden());
    }

    // ── 도우미 ────────────────────────────────────────────────────────────

    private long insert(String email, boolean admin, String note) {
        return jdbcTemplate.queryForObject("""
                INSERT INTO allowed_email (email, is_admin, note) VALUES (?, ?, ?) RETURNING id
                """, Long.class, email, admin, note);
    }

    private boolean isAdmin(long id) {
        return Boolean.TRUE.equals(jdbcTemplate.queryForObject(
                "SELECT is_admin FROM allowed_email WHERE id = ?", Boolean.class, id));
    }
}
