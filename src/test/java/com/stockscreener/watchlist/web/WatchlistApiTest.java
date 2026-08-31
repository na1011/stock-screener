package com.stockscreener.watchlist.web;

import com.stockscreener.support.TestDatabaseGuard;

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
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

import tools.jackson.databind.ObjectMapper;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 워치리스트 CRUD와 인가 경계.
 *
 * <p>{@code ScreenApiTest}와 같은 규칙을 확인한다 — <b>남의 항목은 404다.</b> 403을 주면
 * id를 훑어 다른 사용자가 무엇을 담았는지 개수까지 알아낼 수 있다.
 *
 * <p>MockMvc에 {@code with(csrf())}가 없으면 403이다. CSRF 지연 로딩을 꺼둔 결과다
 * ({@code .claude/rules/test.md}).
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestDatabaseGuard.class)
@Transactional
class WatchlistApiTest {

    private static final String OWNER = "sub-watch-owner";
    private static final String OTHER = "sub-watch-other";

    private static final String SCREEN_BODY = """
            {"name":"알림용 조건","evaluationMode":"FILTER",
             "conditions":{"conditions":[
               {"indicatorCode":"PER","operator":"LT","operand":{"type":"LITERAL","value":10}}]}}
            """;

    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    private long stockId;

    @Autowired
    void setUp(WebApplicationContext context) {
        this.mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    @BeforeEach
    void seed() {
        insertUser(OWNER, "watch-owner@example.com");
        insertUser(OTHER, "watch-other@example.com");
        stockId = insertStock("900001", "관심종목");
    }

    @Test
    @WithMockUser(username = OWNER)
    @DisplayName("담기·조회·수정·해제가 왕복한다")
    void roundTrip() throws Exception {
        long id = watch(stockId, null, "싸 보인다");

        mockMvc.perform(get("/api/watchlist"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(id))
                .andExpect(jsonPath("$[0].ticker").value("900001"))
                .andExpect(jsonPath("$[0].stockName").value("관심종목"))
                .andExpect(jsonPath("$[0].memo").value("싸 보인다"))
                // 알림 조건이 없으면 화면이 "알림 없음"으로 그린다.
                .andExpect(jsonPath("$[0].screenId").doesNotExist())
                // IMPORTANT — null이지 false가 아니다. 담은 직후와 "조건에 안 맞음"이
                // 같아 보이면 사용자는 배치가 돌았는지조차 알 수 없다.
                .andExpect(jsonPath("$[0].satisfied").doesNotExist());

        long screenId = createScreen();
        mockMvc.perform(put("/api/watchlist/" + id).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody(stockId, screenId, "조건 붙임")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.screenId").value(screenId))
                .andExpect(jsonPath("$.screenName").value("알림용 조건"))
                .andExpect(jsonPath("$.screenType").value("CUSTOM"));

        mockMvc.perform(delete("/api/watchlist/" + id).with(csrf()))
                .andExpect(status().isNoContent());
        mockMvc.perform(get("/api/watchlist"))
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    @WithMockUser(username = OWNER)
    @DisplayName("남의 항목은 404다 — 403은 존재를 알려준다")
    void otherUsersItemIsNotFound() throws Exception {
        long id = watch(stockId, null, null);

        mockMvc.perform(get("/api/watchlist").with(user(OTHER)))
                .andExpect(jsonPath("$.length()").value(0));
        mockMvc.perform(put("/api/watchlist/" + id).with(csrf()).with(user(OTHER))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody(stockId, null, "가로채기")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.type").value("urn:stockscreener:watchlist-item-not-found"));
        mockMvc.perform(delete("/api/watchlist/" + id).with(csrf()).with(user(OTHER)))
                .andExpect(status().isNotFound());
    }

    @Test
    @WithMockUser(username = OWNER)
    @DisplayName("같은 종목을 두 번 담으면 409다")
    void duplicateStockIsConflict() throws Exception {
        watch(stockId, null, null);

        mockMvc.perform(post("/api/watchlist").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody(stockId, null, null)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("urn:stockscreener:already-watched"));
    }

    @Test
    @WithMockUser(username = OWNER)
    @DisplayName("없는 종목은 404다 — FK 위반을 500으로 흘리지 않는다")
    void unknownStockIsNotFound() throws Exception {
        mockMvc.perform(post("/api/watchlist").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody(9_999_999L, null, null)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.type").value("urn:stockscreener:stock-not-found"));
    }

    @Test
    @DisplayName("남의 화면은 알림 조건으로 걸 수 없다 — 걸리면 남의 화면 존재를 탐색할 수 있다")
    void cannotUseOtherUsersScreenAsCondition() throws Exception {
        long screenId = createScreenAs(OTHER);

        mockMvc.perform(post("/api/watchlist").with(csrf()).with(user(OWNER))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody(stockId, screenId, null)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.type").value("urn:stockscreener:screen-not-found"));
    }

    @Test
    @WithMockUser(username = OWNER)
    @DisplayName("프리셋은 알림 조건으로 쓸 수 있다")
    void presetIsUsableAsCondition() throws Exception {
        Long presetId = jdbcTemplate.queryForObject(
                "SELECT id FROM screen WHERE code = 'VALUE'", Long.class);

        mockMvc.perform(post("/api/watchlist").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody(stockId, presetId, null)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.screenType").value("PRESET"));
    }

    @Test
    @WithMockUser(username = OWNER)
    @DisplayName("조건을 바꾸면 직전 판정 상태가 초기화된다 — 안 그러면 새 조건의 첫 알림이 침묵한다")
    void changingScreenResetsEdgeState() throws Exception {
        long screenId = createScreen();
        long id = watch(stockId, screenId, null);

        // 배치가 "충족"으로 판정한 상태를 흉내낸다.
        jdbcTemplate.update("UPDATE watchlist_item SET last_satisfied = true WHERE id = ?", id);

        long otherScreenId = createNamedScreen("다른 조건");
        mockMvc.perform(put("/api/watchlist/" + id).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody(stockId, otherScreenId, null)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.satisfied").doesNotExist());
    }

    @Test
    @WithMockUser(username = OWNER)
    @DisplayName("조건이 그대로면 직전 판정 상태를 유지한다 — 메모만 고쳤다고 다시 알리면 안 된다")
    void editingMemoKeepsEdgeState() throws Exception {
        long screenId = createScreen();
        long id = watch(stockId, screenId, null);
        jdbcTemplate.update("UPDATE watchlist_item SET last_satisfied = true WHERE id = ?", id);

        mockMvc.perform(put("/api/watchlist/" + id).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody(stockId, screenId, "메모만 고침")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.satisfied").value(true));
    }

    @Test
    @WithMockUser(username = OWNER)
    @DisplayName("워치리스트가 참조 중인 화면은 지울 수 없고 몇 건이 막는지 알려준다")
    void screenInUseReportsCount() throws Exception {
        long screenId = createScreen();
        watch(stockId, screenId, null);

        mockMvc.perform(delete("/api/screens/" + screenId).with(csrf()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("urn:stockscreener:screen-in-use"))
                // 건수가 없으면 사용자는 워치리스트를 하나씩 열어보게 된다.
                .andExpect(jsonPath("$.watchlistCount").value(1));
    }

    @Test
    @WithMockUser(username = OWNER)
    @DisplayName("메모가 500자를 넘으면 400이고 어느 필드인지 알려준다")
    void rejectsTooLongMemo() throws Exception {
        mockMvc.perform(post("/api/watchlist").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody(stockId, null, "가".repeat(501))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("urn:stockscreener:invalid-request"))
                .andExpect(jsonPath("$.errors[0].field").value("memo"));
    }

    // ── 도우미 ────────────────────────────────────────────────────────────

    private long watch(long stock, Long screenId, String memo) throws Exception {
        String body = mockMvc.perform(post("/api/watchlist").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody(stock, screenId, memo)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).get("id").asLong();
    }

    private static String requestBody(long stock, Long screenId, String memo) {
        return """
                {"stockId":%d,"screenId":%s,"memo":%s}
                """.formatted(stock,
                screenId == null ? "null" : screenId.toString(),
                memo == null ? "null" : "\"" + memo + "\"");
    }

    private long createScreen() throws Exception {
        return createScreenBody(SCREEN_BODY, null);
    }

    private long createNamedScreen(String name) throws Exception {
        return createScreenBody(SCREEN_BODY.replace("알림용 조건", name), null);
    }

    private long createScreenAs(String sub) throws Exception {
        return createScreenBody(SCREEN_BODY.replace("알림용 조건", "남의 조건"), user(sub));
    }

    private long createScreenBody(String body, RequestPostProcessor as) throws Exception {
        var request = post("/api/screens").with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(body);
        if (as != null) {
            request = request.with(as);
        }
        String response = mockMvc.perform(request)
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response).get("id").asLong();
    }

    private void insertUser(String sub, String email) {
        jdbcTemplate.update("""
                INSERT INTO app_user (email, name, provider, provider_user_id, is_admin, is_active)
                VALUES (?, '테스트', 'GOOGLE', ?, false, true) ON CONFLICT DO NOTHING
                """, email, sub);
    }

    private long insertStock(String ticker, String name) {
        return jdbcTemplate.queryForObject("""
                INSERT INTO stock (market_code, ticker, name, is_active)
                VALUES ('KOSPI', ?, ?, true)
                ON CONFLICT (market_code, ticker) DO UPDATE SET name = EXCLUDED.name
                RETURNING id
                """, Long.class, ticker, name);
    }
}
