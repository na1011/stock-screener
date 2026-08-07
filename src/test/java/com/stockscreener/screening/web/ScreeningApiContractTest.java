package com.stockscreener.screening.web;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.stockscreener.screening.ComparisonOperator;
import com.stockscreener.screening.ConditionEvaluator;
import com.stockscreener.screening.ConditionGroup;
import com.stockscreener.screening.ConditionOperand;
import com.stockscreener.screening.EvaluationMode;
import com.stockscreener.screening.IndicatorCondition;
import com.stockscreener.screening.ScreeningResult;
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

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 스크리닝 API의 계약.
 *
 * <p>가장 중요한 검사는 <b>HTTP 결과가 인메모리 판정과 같다</b>는 것이다. 같은 조건을
 * {@link ConditionEvaluator}로 직접 접은 결과와 응답을 대조한다. 이것이 ADR-0003의
 * "화면이 보여주는 선정 이유와 실제로 종목을 고른 근거가 어긋날 수 없다"를 HTTP 계층에서
 * 증명하는 지점이며, 여기가 깨지면 사용자가 손으로 대조하기 전까지 아무도 모른다.
 *
 * <p>{@code @Transactional}로 롤백한다. 커밋해두면 종목이 남아 {@code KOSPI/005930}을 넣는
 * 다른 테스트가 깨지는데, 그 증상은 이 테스트와 무관해 보인다(실제로 그렇게 깨진 적이 있다).
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestDatabaseGuard.class)
@Transactional
class ScreeningApiContractTest {

    private static final LocalDate AS_OF = LocalDate.of(2026, 8, 5);
    private static final String SUB = "google-sub-contract";

    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ConditionEvaluator evaluator;

    @Autowired
    private ObjectMapper objectMapper;

    private long cheapStock;
    private long expensiveStock;
    private long noDataStock;

    @Autowired
    void setUp(WebApplicationContext context) {
        this.mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    @BeforeEach
    void seed() {
        jdbcTemplate.update("""
                INSERT INTO market (code, country_code, currency_code, timezone)
                VALUES ('TESTMKT','KR','KRW','Asia/Seoul') ON CONFLICT DO NOTHING
                """);
        jdbcTemplate.update("""
                INSERT INTO app_user (email, name, provider, provider_user_id, is_admin, is_active)
                VALUES ('contract@example.com','계약테스트','GOOGLE',?,false,true)
                ON CONFLICT DO NOTHING
                """, SUB);

        cheapStock = insertStock("900001", "저평가주");
        expensiveStock = insertStock("900002", "고평가주");
        noDataStock = insertStock("900003", "데이터없는주");

        insertValue(cheapStock, "PER", "8");
        insertValue(cheapStock, "PBR", "0.8");
        insertValue(expensiveStock, "PER", "30");
        insertValue(expensiveStock, "PBR", "3.0");
        // noDataStock에는 지표를 넣지 않는다 — 3상태 중 NOT_EVALUABLE이 나오는 경로다.
    }

    @Test
    @WithMockUser(username = SUB)
    @DisplayName("IMPORTANT — HTTP 결과가 인메모리 판정과 완전히 일치한다")
    void httpResultEqualsInMemoryJudgement() throws Exception {
        ConditionGroup group = ConditionGroup.and(List.of(
                condition("PER", ComparisonOperator.LT, "10").withWeight(new BigDecimal("3")),
                condition("PBR", ComparisonOperator.LT, "1").withWeight(new BigDecimal("2"))));

        JsonNode response = run("""
                {"conditions": {"logicalOperator":"AND","conditions":[
                   {"indicatorCode":"PER","operator":"LT","operand":{"type":"LITERAL","value":10},"weight":3},
                   {"indicatorCode":"PBR","operator":"LT","operand":{"type":"LITERAL","value":1},"weight":2}]},
                 "evaluationMode":"SCORING","asOf":"2026-08-05"}
                """);

        // 같은 조건을 판정기로 직접 접는다. 지표 값은 픽스처가 준 그대로다.
        Map<Long, Map<String, BigDecimal>> values = Map.of(
                cheapStock, Map.of("PER", new BigDecimal("8"), "PBR", new BigDecimal("0.8")),
                expensiveStock, Map.of("PER", new BigDecimal("30"), "PBR", new BigDecimal("3.0")),
                noDataStock, Map.of());

        Map<Long, JsonNode> rowsById = new HashMap<>();
        response.get("rows").forEach(row -> rowsById.put(row.get("stockId").asLong(), row));

        for (long stockId : List.of(cheapStock, expensiveStock, noDataStock)) {
            ScreeningResult expected = evaluator.evaluate(
                    stockId, group, EvaluationMode.SCORING, values.get(stockId));
            JsonNode actual = rowsById.get(stockId);

            assertThat(actual).as("종목 %d가 응답에 없다", stockId).isNotNull();
            assertThat(new BigDecimal(actual.get("score").asText()))
                    .as("종목 %d의 점수", stockId).isEqualByComparingTo(expected.score());
            assertThat(new BigDecimal(actual.get("maxScore").asText()))
                    .isEqualByComparingTo(expected.maxScore());
            assertThat(actual.get("matchedCount").asLong()).isEqualTo(expected.matchedCount());
            assertThat(actual.get("notEvaluableCount").asLong())
                    .isEqualTo(expected.notEvaluableCount());

            for (int i = 0; i < expected.outcomes().size(); i++) {
                assertThat(actual.get("outcomes").get(i).get("status").asText())
                        .as("종목 %d 조건 %d의 상태", stockId, i)
                        .isEqualTo(expected.outcomes().get(i).status().name());
            }
        }
    }

    @Test
    @WithMockUser(username = SUB)
    @DisplayName("IMPORTANT — 판정 상태가 세 값 그대로 실린다. boolean으로 접히지 않는다")
    void exposesAllThreeOutcomeStates() throws Exception {
        JsonNode response = run("""
                {"conditions": {"conditions":[
                   {"indicatorCode":"PER","operator":"LT","operand":{"type":"LITERAL","value":10}}]},
                 "evaluationMode":"SCORING","asOf":"2026-08-05"}
                """);

        Map<Long, String> statusByStock = new HashMap<>();
        response.get("rows").forEach(row -> statusByStock.put(
                row.get("stockId").asLong(),
                row.get("outcomes").get(0).get("status").asText()));

        // "지표가 없어 판정 불가"와 "조건 불충족"이 화면에서 같아 보이면 사용자는 이유를 모른다.
        assertThat(statusByStock.get(cheapStock)).isEqualTo("MATCHED");
        assertThat(statusByStock.get(expensiveStock)).isEqualTo("NOT_MATCHED");
        assertThat(statusByStock.get(noDataStock)).isEqualTo("NOT_EVALUABLE");
    }

    @Test
    @WithMockUser(username = SUB)
    @DisplayName("FILTER는 모든 조건을 만족한 종목만 남긴다")
    void filterKeepsOnlyFullMatches() throws Exception {
        JsonNode response = run("""
                {"conditions": {"conditions":[
                   {"indicatorCode":"PER","operator":"LT","operand":{"type":"LITERAL","value":10}},
                   {"indicatorCode":"PBR","operator":"LT","operand":{"type":"LITERAL","value":1}}]},
                 "evaluationMode":"FILTER","asOf":"2026-08-05"}
                """);

        List<Long> ids = new java.util.ArrayList<>();
        response.get("rows").forEach(row -> ids.add(row.get("stockId").asLong()));
        assertThat(ids).contains(cheapStock).doesNotContain(expensiveStock, noDataStock);
    }

    @Test
    @WithMockUser(username = SUB)
    @DisplayName("IMPORTANT — 기준 일자를 비우면 서버가 정하고 응답에 에코한다")
    void resolvesAndEchoesAsOf() throws Exception {
        JsonNode response = run("""
                {"conditions": {"conditions":[
                   {"indicatorCode":"PER","operator":"LT","operand":{"type":"LITERAL","value":10}}]}}
                """);

        // 에코가 없으면 배치가 하루 밀렸을 때 사용자가 어제 데이터를 오늘 것으로 읽는다.
        assertThat(response.get("asOf").asText()).isNotBlank();
        assertThat(response.get("asOfRequested").isNull()).isTrue();
    }

    @Test
    @WithMockUser(username = SUB)
    @DisplayName("IMPORTANT — 보존 구간 밖은 빈 결과가 아니라 400이고 조회 가능 구간을 알려준다")
    void rejectsDatesOutsideRetention() throws Exception {
        String body = mockMvc.perform(post("/api/screening").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"conditions": {"conditions":[
                                   {"indicatorCode":"PER","operator":"LT","operand":{"type":"LITERAL","value":10}}]},
                                 "asOf":"2010-01-04"}
                                """))
                .andExpect(status().isBadRequest())
                .andReturn().getResponse().getContentAsString();

        JsonNode problem = objectMapper.readTree(body);
        assertThat(problem.get("type").asText()).endsWith("indicator-history-unavailable");
        assertThat(problem.get("retryable").asBoolean()).isFalse();
        // 구간 없이 거절만 하면 사용자는 날짜를 하나씩 시도하게 된다.
        assertThat(problem.has("retentionFrom")).isTrue();
        assertThat(problem.has("retentionTo")).isTrue();
    }

    @Test
    @WithMockUser(username = SUB)
    @DisplayName("실행 대상이 둘 이상이면 400 — 무엇이 이겼는지 사용자가 알 수 없다")
    void rejectsAmbiguousSource() throws Exception {
        mockMvc.perform(post("/api/screening").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"presetCode":"VALUE_KR",
                                 "conditions": {"conditions":[
                                   {"indicatorCode":"PER","operator":"LT","operand":{"type":"LITERAL","value":10}}]}}
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    @WithMockUser(username = SUB)
    @DisplayName("실행 대상이 하나도 없어도 400 — 조건 없는 전 종목 조회는 스크리닝이 아니다")
    void rejectsMissingSource() throws Exception {
        mockMvc.perform(post("/api/screening").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @WithMockUser(username = SUB)
    @DisplayName("없는 지표를 참조하면 400이고 어느 조건인지 알려준다")
    void rejectsUnknownIndicatorWithConditionIndex() throws Exception {
        String body = mockMvc.perform(post("/api/screening").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"conditions": {"conditions":[
                                   {"indicatorCode":"PER","operator":"LT","operand":{"type":"LITERAL","value":10}},
                                   {"indicatorCode":"NOPE","operator":"LT","operand":{"type":"LITERAL","value":1}}]}}
                                """))
                .andExpect(status().isBadRequest())
                .andReturn().getResponse().getContentAsString();

        JsonNode problem = objectMapper.readTree(body);
        // 인덱스가 없으면 화면이 어느 줄을 강조할지 모른다.
        assertThat(problem.get("conditionIndex").asInt()).isEqualTo(1);
        assertThat(problem.get("indicatorCode").asText()).isEqualTo("NOPE");
    }

    @Test
    @WithMockUser(username = SUB)
    @DisplayName("BETWEEN에 상한이 없으면 500이 아니라 400이다")
    void malformedBetweenIsClientError() throws Exception {
        mockMvc.perform(post("/api/screening").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"conditions": {"conditions":[
                                   {"indicatorCode":"PER","operator":"BETWEEN","operand":{"type":"LITERAL","value":5}}]}}
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    @WithMockUser(username = SUB)
    @DisplayName("중첩 조건 그룹은 v1에서 거절된다")
    void rejectsNestedGroups() throws Exception {
        mockMvc.perform(post("/api/screening").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"conditions": {"conditions":[
                                   {"indicatorCode":"PER","operator":"LT","operand":{"type":"LITERAL","value":10}}],
                                 "childGroups":[{"logicalOperator":"OR","conditions":[
                                   {"indicatorCode":"PBR","operator":"LT","operand":{"type":"LITERAL","value":1}}]}]}}
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    @WithMockUser(username = SUB)
    @DisplayName("IMPORTANT — CSRF 토큰이 없는 POST는 403이다")
    void rejectsPostWithoutCsrfToken() throws Exception {
        // 세션 쿠키 인증이므로 CSRF를 끄지 않는다. 이 단언이 없으면 누군가 편의를 위해
        // 끄더라도 아무 테스트도 깨지지 않는다.
        mockMvc.perform(post("/api/screening")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"conditions": {"conditions":[
                                   {"indicatorCode":"PER","operator":"LT","operand":{"type":"LITERAL","value":10}}]}}
                                """))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(username = SUB)
    @DisplayName("프리셋을 코드로 실행할 수 있다 — 시딩과 실행 경로가 이어져 있다")
    void runsSeededPresetByCode() throws Exception {
        JsonNode response = run("""
                {"presetCode":"VALUE_KR","asOf":"2026-08-05"}
                """);

        assertThat(response.get("screen").get("code").asText()).isEqualTo("VALUE_KR");
        assertThat(response.get("screen").get("type").asText()).isEqualTo("PRESET");
        assertThat(response.get("evaluationMode").asText()).isEqualTo("SCORING");
        // 조건 사전이 실려야 화면이 "조건별 획득 점수"를 그릴 수 있다.
        assertThat(response.get("conditions").size()).isGreaterThan(0);
        assertThat(response.get("conditions").get(0).get("operatorLabel").asText()).isNotBlank();
    }

    @Test
    @WithMockUser(username = SUB)
    @DisplayName("백분위 모집단을 응답에 밝힌다 — 유니버스별 순위가 아니다")
    void declaresPercentilePopulation() throws Exception {
        JsonNode response = run("""
                {"conditions": {"conditions":[
                   {"indicatorCode":"PER","operator":"LT","operand":{"type":"LITERAL","value":10}}]}}
                """);

        assertThat(response.get("percentilePopulation").asText()).isEqualTo("ALL_CURRENT_MEMBERS");
    }

    @Test
    @WithMockUser(username = SUB)
    @DisplayName("백분위를 쓰는지 서버가 판단해서 준다 — 모집단 주의 문구를 언제 보일지가 여기 달렸다")
    void declaresWhetherPercentileIsUsed() throws Exception {
        // 화면이 카탈로그를 따로 받아 판단하면, 그 요청만 실패해도 주의 문구가 조용히
        // 사라지고 화면은 "코스피 상위 20%"라고 말하게 된다 — 사실이 아니다.
        JsonNode plain = run("""
                {"conditions": {"conditions":[
                   {"indicatorCode":"PER","operator":"LT","operand":{"type":"LITERAL","value":10}}]}}
                """);
        assertThat(plain.get("usesPercentile").asBoolean()).isFalse();

        JsonNode percentile = run("""
                {"conditions": {"conditions":[
                   {"indicatorCode":"PER_PCTL","operator":"LTE","operand":{"type":"LITERAL","value":0.2}}]}}
                """);
        assertThat(percentile.get("usesPercentile").asBoolean()).isTrue();
    }

    private JsonNode run(String body) throws Exception {
        String json = mockMvc.perform(post("/api/screening").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(json);
    }

    private static IndicatorCondition condition(String code, ComparisonOperator operator, String value) {
        return IndicatorCondition.of(code, operator, ConditionOperand.Literal.of(value));
    }

    private long insertStock(String ticker, String name) {
        return jdbcTemplate.queryForObject("""
                INSERT INTO stock (market_code, ticker, name) VALUES ('TESTMKT', ?, ?) RETURNING id
                """, Long.class, ticker, name);
    }

    private void insertValue(long stockId, String code, String value) {
        jdbcTemplate.update("""
                INSERT INTO indicator_value (stock_id, trade_date, indicator_code, value)
                VALUES (?, ?, ?, ?)
                ON CONFLICT (trade_date, indicator_code, stock_id) DO UPDATE SET value = EXCLUDED.value
                """, stockId, AS_OF, code, new BigDecimal(value));
    }
}
