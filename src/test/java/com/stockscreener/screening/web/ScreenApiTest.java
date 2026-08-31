package com.stockscreener.screening.web;

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

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

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
 * 저장된 화면 CRUD의 인가 경계.
 *
 * <p>두 규칙이 의도적으로 다르다 — 남의 커스텀 화면은 <b>404</b>(존재를 드러내지 않는다),
 * 프리셋 쓰기는 <b>403</b>(이미 보이므로 없는 척할 이유가 없다).
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestDatabaseGuard.class)
@Transactional
class ScreenApiTest {

    private static final String OWNER = "sub-owner";
    private static final String OTHER = "sub-other";

    private static final String VALID_BODY = """
            {"name":"내 가치주","description":"설명","evaluationMode":"FILTER",
             "conditions":{"conditions":[
               {"indicatorCode":"PER","operator":"LT","operand":{"type":"LITERAL","value":10}}]}}
            """;

    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    void setUp(WebApplicationContext context) {
        this.mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    @BeforeEach
    void seedUsers() {
        insertUser(OWNER, "owner@example.com");
        insertUser(OTHER, "other@example.com");
    }

    @Test
    @WithMockUser(username = OWNER)
    @DisplayName("생성·조회·수정·삭제가 왕복한다")
    void roundTrip() throws Exception {
        long id = createScreen();

        mockMvc.perform(get("/api/screens/" + id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("내 가치주"))
                .andExpect(jsonPath("$.type").value("CUSTOM"))
                .andExpect(jsonPath("$.editable").value(true))
                .andExpect(jsonPath("$.conditions.conditions[0].indicatorCode").value("PER"));

        mockMvc.perform(put("/api/screens/" + id).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"고친 이름","evaluationMode":"SCORING",
                                 "conditions":{"conditions":[
                                   {"indicatorCode":"PBR","operator":"LT",
                                    "operand":{"type":"LITERAL","value":1},"weight":2}]}}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("고친 이름"))
                .andExpect(jsonPath("$.evaluationMode").value("SCORING"))
                // 조건이 통째로 교체된다 — 옛 조건이 남으면 실행 결과가 화면과 달라진다.
                .andExpect(jsonPath("$.conditions.conditions.length()").value(1))
                .andExpect(jsonPath("$.conditions.conditions[0].indicatorCode").value("PBR"));

        mockMvc.perform(delete("/api/screens/" + id).with(csrf())).andExpect(status().isNoContent());
        mockMvc.perform(get("/api/screens/" + id)).andExpect(status().isNotFound());
    }

    @Test
    @WithMockUser(username = OWNER)
    @DisplayName("IMPORTANT — 남의 화면은 403이 아니라 404다. 존재 여부를 드러내지 않는다")
    void otherUsersScreenIsNotFound() throws Exception {
        long id = createScreen();

        // 403을 주면 id를 훑어 다른 사용자의 화면 개수를 알아낼 수 있다.
        mockMvc.perform(get("/api/screens/" + id).with(user(OTHER)))
                .andExpect(status().isNotFound());
        mockMvc.perform(delete("/api/screens/" + id).with(csrf()).with(user(OTHER)))
                .andExpect(status().isNotFound());
    }

    @Test
    @WithMockUser(username = OWNER)
    @DisplayName("프리셋 수정·삭제는 403이다 — 목록에 이미 보이므로 없는 척하지 않는다")
    void presetIsReadOnly() throws Exception {
        Long presetId = jdbcTemplate.queryForObject(
                "SELECT id FROM screen WHERE code = 'VALUE'", Long.class);

        mockMvc.perform(put("/api/screens/" + presetId).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.type").value("urn:stockscreener:preset-is-read-only"));

        mockMvc.perform(delete("/api/screens/" + presetId).with(csrf()))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(username = OWNER)
    @DisplayName("프리셋은 읽을 수 있고 editable=false로 표시된다")
    void presetIsReadableButNotEditable() throws Exception {
        Long presetId = jdbcTemplate.queryForObject(
                "SELECT id FROM screen WHERE code = 'VALUE'", Long.class);

        mockMvc.perform(get("/api/screens/" + presetId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.type").value("PRESET"))
                .andExpect(jsonPath("$.editable").value(false));
    }

    @Test
    @WithMockUser(username = OWNER)
    @DisplayName("같은 이름을 두 번 저장하면 409다")
    void duplicateNameIsConflict() throws Exception {
        createScreen();

        mockMvc.perform(post("/api/screens").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("urn:stockscreener:duplicate-screen-name"));
    }

    @Test
    @WithMockUser(username = OWNER)
    @DisplayName("목록은 프리셋과 자기 화면만 보여준다")
    void listShowsPresetsAndOwnScreensOnly() throws Exception {
        createScreen();

        String body = mockMvc.perform(get("/api/screens").with(user(OTHER)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        JsonNode list = objectMapper.readTree(body);
        assertThat(list.size()).isGreaterThan(0);
        list.forEach(screen -> assertThat(screen.get("name").asText()).isNotEqualTo("내 가치주"));
    }

    @Test
    @WithMockUser(username = OWNER)
    @DisplayName("중첩 조건 그룹은 400이고 목록 전체의 문제임을 알린다")
    void rejectsNestedGroups() throws Exception {
        mockMvc.perform(post("/api/screens").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"중첩","evaluationMode":"FILTER",
                                 "conditions":{"conditions":[
                                   {"indicatorCode":"PER","operator":"LT","operand":{"type":"LITERAL","value":10}}],
                                  "childGroups":[{"logicalOperator":"OR","conditions":[
                                   {"indicatorCode":"PBR","operator":"LT","operand":{"type":"LITERAL","value":1}}]}]}}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("urn:stockscreener:invalid-condition"));
    }

    @Test
    @WithMockUser(username = OWNER)
    @DisplayName("이름이 없으면 400이고 어느 필드인지 알려준다")
    void rejectsMissingName() throws Exception {
        mockMvc.perform(post("/api/screens").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"evaluationMode":"FILTER","conditions":{"conditions":[
                                   {"indicatorCode":"PER","operator":"LT","operand":{"type":"LITERAL","value":10}}]}}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("urn:stockscreener:invalid-request"))
                .andExpect(jsonPath("$.errors[0].field").value("name"));
    }

    private long createScreen() throws Exception {
        String body = mockMvc.perform(post("/api/screens").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).get("id").asLong();
    }

    /** 다른 사용자로 같은 요청을 보낸다. */
    private static org.springframework.test.web.servlet.request.RequestPostProcessor user(String sub) {
        return org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors
                .user(sub);
    }

    private void insertUser(String sub, String email) {
        jdbcTemplate.update("""
                INSERT INTO app_user (email, name, provider, provider_user_id, is_admin, is_active)
                VALUES (?, '테스트', 'GOOGLE', ?, false, true) ON CONFLICT DO NOTHING
                """, email, sub);
    }
}
