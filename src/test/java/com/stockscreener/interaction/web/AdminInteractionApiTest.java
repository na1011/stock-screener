package com.stockscreener.interaction.web;

import com.stockscreener.auth.AllowlistOidcUserService;
import com.stockscreener.interaction.InteractionAction;
import com.stockscreener.support.TestDatabaseGuard;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
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
 * 누가 언제 어떤 행위를 했는지 조회·필터링되는가 (spec §4).
 *
 * <p>기록이 남는지는 {@code InteractionInterceptorTest}가 본다. 여기서 확인하는 것은
 * <b>쌓인 것을 운영자가 실제로 찾아낼 수 있는가</b>다 — 쌓기만 하고 못 찾으면 NFR이 무의미해진다.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestDatabaseGuard.class)
@Transactional
@WithMockUser(authorities = {AllowlistOidcUserService.ROLE_USER, AllowlistOidcUserService.ROLE_ADMIN})
class AdminInteractionApiTest {

    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private long userId;
    private long otherUserId;

    @Autowired
    void setUp(WebApplicationContext context) {
        this.mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    @BeforeEach
    void seed() {
        jdbcTemplate.update("DELETE FROM interaction_log");
        userId = insertUser("log-a@example.com", "sub-log-a");
        otherUserId = insertUser("log-b@example.com", "sub-log-b");
    }

    @Test
    @DisplayName("최근 순으로 오고 사용자 이메일이 함께 온다 — id만으로는 누구인지 모른다")
    void listsRecentFirstWithEmail() throws Exception {
        insertLog(userId, InteractionAction.SCREENING_RUN, "2026-08-05 10:00:00+09");
        insertLog(userId, InteractionAction.SCREEN_CREATE, "2026-08-05 11:00:00+09");

        mockMvc.perform(get("/api/admin/interactions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].action").value("SCREEN_CREATE"))
                .andExpect(jsonPath("$[0].userEmail").value("log-a@example.com"));
    }

    @Test
    @DisplayName("사용자와 행위로 좁혀진다")
    void filtersByUserAndAction() throws Exception {
        insertLog(userId, InteractionAction.SCREENING_RUN, "2026-08-05 10:00:00+09");
        insertLog(otherUserId, InteractionAction.SCREENING_RUN, "2026-08-05 10:30:00+09");
        insertLog(userId, InteractionAction.LOGIN, "2026-08-05 09:00:00+09");

        mockMvc.perform(get("/api/admin/interactions").param("userId", String.valueOf(userId)))
                .andExpect(jsonPath("$.length()").value(2));

        mockMvc.perform(get("/api/admin/interactions").param("action", "SCREENING_RUN"))
                .andExpect(jsonPath("$.length()").value(2));

        mockMvc.perform(get("/api/admin/interactions")
                        .param("userId", String.valueOf(userId))
                        .param("action", "SCREENING_RUN"))
                .andExpect(jsonPath("$.length()").value(1));
    }

    @Test
    @DisplayName("IMPORTANT — to에 오늘을 넣으면 오늘 것이 나온다. 경계를 잘못 잡으면 통째로 빠진다")
    void toBoundaryIncludesThatWholeDay() throws Exception {
        insertLog(userId, InteractionAction.LOGIN, "2026-08-05 23:30:00+09");

        // occurred_at은 타임스탬프다. `<= to`로 비교하면 그날 00:00 이후가 전부 빠지고,
        // 증상은 "오늘을 지정했는데 오늘 것이 안 나온다"로만 보인다.
        mockMvc.perform(get("/api/admin/interactions")
                        .param("from", "2026-08-05")
                        .param("to", "2026-08-05"))
                .andExpect(jsonPath("$.length()").value(1));

        mockMvc.perform(get("/api/admin/interactions")
                        .param("from", "2026-08-06")
                        .param("to", "2026-08-06"))
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    @DisplayName("detail이 객체로 온다 — 문자열로 주면 화면이 다시 파싱해야 한다")
    void detailComesBackAsObject() throws Exception {
        jdbcTemplate.update("""
                INSERT INTO interaction_log (user_id, action, http_method, path, status_code, detail, occurred_at)
                VALUES (?, 'WATCHLIST_REMOVE', 'DELETE', '/api/watchlist/7', 204,
                        CAST('{"id":"7"}' AS jsonb), now())
                """, userId);

        mockMvc.perform(get("/api/admin/interactions"))
                .andExpect(jsonPath("$[0].detail.id").value("7"));
    }

    @Test
    @DisplayName("필터 선택지는 기록에 등장한 사용자만 준다")
    void actorsComeFromTheLog() throws Exception {
        insertLog(userId, InteractionAction.LOGIN, "2026-08-05 09:00:00+09");

        mockMvc.perform(get("/api/admin/interactions/actors"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].email").value("log-a@example.com"));
    }

    @Test
    @DisplayName("limit 상한을 넘겨도 그 위로는 오지 않는다")
    void limitIsBounded() throws Exception {
        for (int i = 0; i < 3; i++) {
            insertLog(userId, InteractionAction.LOGIN, "2026-08-05 09:0" + i + ":00+09");
        }

        mockMvc.perform(get("/api/admin/interactions").param("limit", "2"))
                .andExpect(jsonPath("$.length()").value(2));
        mockMvc.perform(get("/api/admin/interactions").param("limit", "99999"))
                .andExpect(jsonPath("$.length()").value(3));
    }

    @Test
    @WithMockUser(authorities = AllowlistOidcUserService.ROLE_USER)
    @DisplayName("일반 사용자는 로그를 볼 수 없다 — 남이 무엇을 했는지 드러난다")
    void normalUserIsForbidden() throws Exception {
        mockMvc.perform(get("/api/admin/interactions")).andExpect(status().isForbidden());
    }

    // ── 도우미 ────────────────────────────────────────────────────────────

    private long insertUser(String email, String sub) {
        return jdbcTemplate.queryForObject("""
                INSERT INTO app_user (email, name, provider, provider_user_id, is_admin, is_active)
                VALUES (?, '기록', 'GOOGLE', ?, false, true)
                RETURNING id
                """, Long.class, email, sub);
    }

    private void insertLog(long user, InteractionAction action, String occurredAt) {
        jdbcTemplate.update("""
                INSERT INTO interaction_log (user_id, action, http_method, path, status_code, occurred_at)
                VALUES (?, ?, 'POST', '/api/x', 200, CAST(? AS timestamptz))
                """, user, action.name(), occurredAt);
    }
}
