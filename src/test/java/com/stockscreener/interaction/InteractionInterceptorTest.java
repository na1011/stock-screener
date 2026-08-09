package com.stockscreener.interaction;

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
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 기록이 <b>선언적</b>인가 — 표시가 붙은 것만 남고, 붙지 않은 것은 남지 않는가.
 *
 * <p>{@code docs/phase-criteria.md}의 "새 엔드포인트에 기록을 붙이는 비용이 표시 한 줄이고, 컨트롤러 본문에 로깅
 * 코드가 없다"에 대응한다. 여기서 검증하는 것은 컨트롤러가 아니라 <b>수집 계층 하나가 실제로
 * 그 표시를 보고 있는가</b>이므로, 진짜 요청을 태워야 한다.
 *
 * <p>MockMvc의 상태변경 요청에 {@code with(csrf())}가 없으면 403이다({@code .claude/rules/test.md}).
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestDatabaseGuard.class)
@Transactional
class InteractionInterceptorTest {

    private static final String SUB = "sub-interaction";

    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    private long userId;
    private long stockId;

    @Autowired
    void setUp(WebApplicationContext context) {
        this.mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    @BeforeEach
    void seed() {
        userId = jdbcTemplate.queryForObject("""
                INSERT INTO app_user (email, name, provider, provider_user_id, is_admin, is_active)
                VALUES ('interaction@example.com', '기록', 'GOOGLE', ?, false, true)
                RETURNING id
                """, Long.class, SUB);
        stockId = jdbcTemplate.queryForObject("""
                INSERT INTO stock (market_code, ticker, name, is_active)
                VALUES ('KOSPI', '900101', '기록종목', true)
                ON CONFLICT (market_code, ticker) DO UPDATE SET name = EXCLUDED.name
                RETURNING id
                """, Long.class);
        jdbcTemplate.update("DELETE FROM interaction_log");
    }

    @Test
    @WithMockUser(username = SUB)
    @DisplayName("표시가 붙은 엔드포인트는 누가·무엇을·언제가 함께 남는다")
    void annotatedHandlerIsRecorded() throws Exception {
        mockMvc.perform(post("/api/watchlist").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"stockId":%d,"screenId":null,"memo":null}
                                """.formatted(stockId)))
                .andExpect(status().isCreated());

        Map<String, Object> row = onlyRow();
        assertThat(row.get("action")).isEqualTo(InteractionAction.WATCHLIST_ADD.name());
        assertThat(row.get("user_id")).isEqualTo(userId);
        assertThat(row.get("http_method")).isEqualTo("POST");
        assertThat(row.get("path")).isEqualTo("/api/watchlist");
        assertThat(row.get("status_code")).isEqualTo(201);
        assertThat(row.get("duration_ms")).isNotNull();
    }

    @Test
    @WithMockUser(username = SUB)
    @DisplayName("표시가 없는 엔드포인트는 남지 않는다 — 조회까지 남기면 의도가 드러나는 행위가 묻힌다")
    void unannotatedHandlerIsNotRecorded() throws Exception {
        mockMvc.perform(get("/api/watchlist")).andExpect(status().isOk());
        mockMvc.perform(get("/api/indicators")).andExpect(status().isOk());

        assertThat(rows()).isEmpty();
    }

    @Test
    @WithMockUser(username = SUB)
    @DisplayName("URI 경로 변수가 detail에 담긴다 — 무엇을 대상으로 한 행위인지 알아야 조회가 쓸모 있다")
    void pathVariablesLandInDetail() throws Exception {
        long itemId = watch();
        jdbcTemplate.update("DELETE FROM interaction_log");

        mockMvc.perform(delete("/api/watchlist/" + itemId).with(csrf()))
                .andExpect(status().isNoContent());

        Map<String, Object> row = onlyRow();
        assertThat(row.get("action")).isEqualTo(InteractionAction.WATCHLIST_REMOVE.name());
        assertThat(detailOf(row)).containsEntry("id", String.valueOf(itemId));
    }

    @Test
    @WithMockUser(username = SUB)
    @DisplayName("실패한 시도도 상태 코드와 함께 남는다 — 성공만 남기면 사용자가 겪은 마찰이 안 보인다")
    void failedAttemptIsRecordedWithStatus() throws Exception {
        mockMvc.perform(delete("/api/watchlist/9999999").with(csrf()))
                .andExpect(status().isNotFound());

        Map<String, Object> row = onlyRow();
        assertThat(row.get("action")).isEqualTo(InteractionAction.WATCHLIST_REMOVE.name());
        assertThat(row.get("status_code")).isEqualTo(404);
    }

    // ── 도우미 ────────────────────────────────────────────────────────────

    private long watch() throws Exception {
        String body = mockMvc.perform(post("/api/watchlist").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"stockId":%d,"screenId":null,"memo":null}
                                """.formatted(stockId)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).get("id").asLong();
    }

    private List<Map<String, Object>> rows() {
        return jdbcTemplate.queryForList("SELECT * FROM interaction_log ORDER BY id");
    }

    private Map<String, Object> onlyRow() {
        List<Map<String, Object>> rows = rows();
        assertThat(rows).as("인터랙션이 정확히 한 건 남아야 한다").hasSize(1);
        return rows.getFirst();
    }

    @SuppressWarnings("unchecked")
    private Map<String, String> detailOf(Map<String, Object> row) {
        Object detail = row.get("detail");
        assertThat(detail).as("detail이 비어 있다").isNotNull();
        return objectMapper.readValue(detail.toString(), Map.class);
    }
}
