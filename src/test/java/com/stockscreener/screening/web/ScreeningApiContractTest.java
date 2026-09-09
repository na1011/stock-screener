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
import java.util.ArrayList;
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
    /** 픽스처 종목이 "전체"에 들기 위한 활성 유니버스(ADR-0087). */
    private static final String CONTRACT_UNIVERSE = "TESTUNIVCONTRACT";

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
                INSERT INTO universe (code, name, market_code)
                VALUES (?, '계약 테스트 유니버스', 'TESTMKT') ON CONFLICT DO NOTHING
                """, CONTRACT_UNIVERSE);
        // 이 시장의 지표 달력. 없으면 AsOfResolver가 503을 낸다.
        //
        // MUST: 픽스처가 **자기 시장의** 달력을 심는다. 예전에는 이 줄이 없어도 통과했는데,
        // 그것은 테스트 DB의 시드 유니버스(KOSPI200·KOSDAQ150)가 **편입 0건인 채로 활성**이라
        // 그 시장의 달력이 딸려 들어온 것이다 — 이 테스트와 아무 상관 없는 행에 기대고 있었다.
        // 그 우연이 사라지면 기준일 해석이 통째로 무너진다.
        jdbcTemplate.update("""
                INSERT INTO indicator_trade_date (market_code, trade_date)
                VALUES ('TESTMKT', ?) ON CONFLICT DO NOTHING
                """, AS_OF);
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

    /**
     * <b>하한을 정하는 것이 둘이다</b>(ADR-0135) — 지표 보존 창과 <b>편입 이력의 하한</b>.
     * 편입 술어를 시점화하고 복원을 안 돌리면 보존 창의 <b>90~98%</b>가 <b>조용한 0건</b>이 되는데
     * (실측: 255일 중 국내 231 · 미국 249), <b>200에 0행은 「조건이 빡빡하다」로 읽혀</b> 사용자가
     * 조건을 고치러 간다. 그 상태를 400으로 만드는 것이 이 인터록의 쓸모다.
     *
     * <p><b>문구가 어느 하한에 걸렸는지 말해야 한다.</b> 편입 하한에 걸린 경우 <b>지표는 있으므로</b>
     * "지표는 최근 구간만 보존한다"는 거짓말이 된다.
     */
    @Test
    @WithMockUser(username = SUB)
    @DisplayName("IMPORTANT — 편입 하한 이전은 조용한 0건이 아니라 400이고, 지표 탓으로 말하지 않는다")
    void rejectsDatesBeforeTheMembershipFloor() throws Exception {
        // 지표는 그날 있는데 편입 이력이 그보다 늦게 시작한다 — 인터록이 없으면 200에 0행이다.
        LocalDate before = AS_OF.minusDays(3);
        jdbcTemplate.update("""
                INSERT INTO indicator_trade_date (market_code, trade_date)
                VALUES ('TESTMKT', ?) ON CONFLICT DO NOTHING
                """, before);
        // 픽스처의 편입은 2020년부터라 하한이 그보다 이르다. 하한을 기준일 뒤로 옮겨
        // 「지표는 있는데 원장이 못 답하는」 상태를 만든다.
        jdbcTemplate.update("""
                UPDATE universe_membership SET effective_from = ? WHERE universe_code = ?
                """, AS_OF, CONTRACT_UNIVERSE);

        String body = mockMvc.perform(post("/api/screening").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"universeCode":"%s",
                                 "conditions": {"conditions":[
                                   {"indicatorCode":"PER","operator":"LT","operand":{"type":"LITERAL","value":10}}]},
                                 "asOf":"%s"}
                                """.formatted(CONTRACT_UNIVERSE, before)))
                .andExpect(status().isBadRequest())
                .andReturn().getResponse().getContentAsString();

        JsonNode problem = objectMapper.readTree(body);
        assertThat(problem.get("type").asText()).endsWith("indicator-history-unavailable");
        assertThat(problem.get("boundBy").asText())
                .as("어느 하한에 걸렸는지를 프로그램이 읽을 수 있어야 한다")
                .isEqualTo("MEMBERSHIP");
        assertThat(problem.get("detail").asText())
                .as("편입 하한에 걸렸는데 「지표가 보존 밖」이라고 말하면 읽는 쪽이 틀린 데를 고치러 간다")
                .contains("편입 이력")
                .doesNotContain("지표는 최근 구간만 보존한다");
        assertThat(problem.has("retentionFrom")).isTrue();
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
                                {"presetCode":"VALUE",
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
                {"presetCode":"VALUE","asOf":"2026-08-05"}
                """);

        assertThat(response.get("screen").get("code").asText()).isEqualTo("VALUE");
        assertThat(response.get("screen").get("type").asText()).isEqualTo("PRESET");
        assertThat(response.get("evaluationMode").asText()).isEqualTo("SCORING");
        // 조건 사전이 실려야 화면이 "조건별 획득 점수"를 그릴 수 있다.
        assertThat(response.get("conditions").size()).isGreaterThan(0);
        assertThat(response.get("conditions").get(0).get("operatorLabel").asText()).isNotBlank();
    }

    @Test
    @WithMockUser(username = SUB)
    @DisplayName("어느 모집단을 썼는지 서버가 판단해서 준다 — 주의 문구를 언제 보일지가 여기 달렸다")
    void declaresWhichPopulationsAreUsed() throws Exception {
        // 화면이 카탈로그를 따로 받아 판단하면, 그 요청만 실패해도 주의 문구가 조용히
        // 사라지고 화면은 모집단을 밝히지 않은 채 "상위 20%"라고 말하게 된다.
        JsonNode plain = run("""
                {"conditions": {"conditions":[
                   {"indicatorCode":"PER","operator":"LT","operand":{"type":"LITERAL","value":10}}]}}
                """);
        assertThat(populations(plain)).isEmpty();

        JsonNode universe = run("""
                {"conditions": {"conditions":[
                   {"indicatorCode":"PER_PCTL","operator":"LTE","operand":{"type":"LITERAL","value":0.2}}]}}
                """);
        assertThat(populations(universe)).containsExactly("UNIVERSE");
    }

    @Test
    @WithMockUser(username = SUB)
    @DisplayName("IMPORTANT — 업종 조건을 쓰면 그 표본 수가 결과 표 컬럼으로 함께 온다")
    void addsPeerCountColumnAlongsideSectorMetrics() throws Exception {
        // 임계 5는 5 미만만 막는다. 5와 40의 차이가 값에 남지 않으므로, 이 컬럼이 없으면
        // 사용자가 "업종 상위 50%"를 다섯 중 셋이라는 뜻으로도 읽는다.
        JsonNode response = run("""
                {"conditions": {"conditions":[
                   {"indicatorCode":"PER_SECTOR_PCTL","operator":"LTE","operand":{"type":"LITERAL","value":0.5}}]}}
                """);

        List<String> columns = new ArrayList<>();
        response.get("columns").forEach(node -> columns.add(node.asText()));
        assertThat(columns).contains("PER_SECTOR_PCTL", "PER_SECTOR_N");
    }

    @Test
    @WithMockUser(username = SUB)
    @DisplayName("IMPORTANT — 모집단이 둘이면 둘 다 밝힌다. 하나로 접으면 조건마다 뜻이 달라진 것이 감춰진다")
    void declaresBothPopulationsWhenMixed() throws Exception {
        JsonNode mixed = run("""
                {"conditions": {"conditions":[
                   {"indicatorCode":"PER_PCTL","operator":"LTE","operand":{"type":"LITERAL","value":0.2}},
                   {"indicatorCode":"PER_SECTOR_PCTL","operator":"LTE","operand":{"type":"LITERAL","value":0.5}}]}}
                """);

        assertThat(populations(mixed)).containsExactly("UNIVERSE", "SECTOR");
    }

    /** 열거 순서로 정렬돼 오므로 조건 순서가 바뀌어도 화면의 문구 순서는 흔들리지 않는다. */
    private static List<String> populations(JsonNode response) {
        List<String> found = new ArrayList<>();
        response.get("relativePopulations").forEach(node -> found.add(node.asText()));
        return found;
    }

    /**
     * 활성 조회 대상의 통화가 갈리면 「전체」 실행을 거절한다(ADR-0107 §규칙 · 사용자 결정).
     *
     * <p><b>MUST NOT: 두 번째 시장을 {@code seed()}에 심지 않는다.</b> 이 클래스는
     * {@code universeCode}를 한 번도 안 쓰고 {@link #run}이 {@code isOk()}를 단정하므로,
     * {@code @BeforeEach}에 두면 <b>여덟이 400으로 붉고</b> {@code rejectsDatesOutsideRetention}은
     * {@code type}이 {@code universe-required}로 바뀌어 깨진다. 롤백 단위가 시험이므로
     * 여기서 심는 것으로 충분하다.
     *
     * <p><b>대조군은 둘이다.</b> 하나는 아래 「대상을 고르면 200」이고, 다른 하나는
     * <b>이 클래스의 나머지 시험 전부</b>다 — 두 번째 시장이 없으면 대상 미지정도 200이라는
     * 것을 그것들이 이미 매번 확인한다.
     */
    @Test
    @WithMockUser(username = SUB)
    @DisplayName("IMPORTANT — 활성 대상의 통화가 갈리면 「전체」를 거절한다. 대상을 고르면 돈다")
    void refusesAllUniversesWhenActiveCurrenciesDiffer() throws Exception {
        jdbcTemplate.update("""
                INSERT INTO market (code, country_code, currency_code, timezone)
                VALUES ('TESTUSD','US','USD','America/New_York') ON CONFLICT DO NOTHING
                """);
        jdbcTemplate.update("""
                INSERT INTO universe (code, name, market_code)
                VALUES ('TESTUNIVUSD', '계약 테스트 달러 유니버스', 'TESTUSD') ON CONFLICT DO NOTHING
                """);
        // 편입이 있어야 통화 집합에 든다 — MarketScope가 편입 있는 활성 유니버스만 센다.
        long usdStock = jdbcTemplate.queryForObject("""
                INSERT INTO stock (market_code, ticker, name) VALUES ('TESTUSD','TESTUSD1','달러주')
                RETURNING id
                """, Long.class);
        jdbcTemplate.update("""
                INSERT INTO universe_membership (universe_code, stock_id, effective_from)
                VALUES ('TESTUNIVUSD', ?, ?)
                """, usdStock, AS_OF.minusYears(1));

        String conditions = """
                {"conditions":[
                   {"indicatorCode":"PER","operator":"LT","operand":{"type":"LITERAL","value":10}}]}""";

        String body = mockMvc.perform(post("/api/screening").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"conditions\": " + conditions + "}"))
                .andExpect(status().isBadRequest())
                .andReturn().getResponse().getContentAsString();

        JsonNode problem = objectMapper.readTree(body);
        assertThat(problem.get("type").asText()).endsWith("universe-required");
        assertThat(problem.get("retryable").asBoolean())
                .as("사용자가 대상을 고르면 되는 일이라 재시도가 아니다")
                .isFalse();
        // 무엇이 갈렸는지를 실어야 화면이 "어느 쪽 기준인지 정해지지 않는다"를 말할 수 있다.
        List<String> currencies = new ArrayList<>();
        problem.get("currencies").forEach(node -> currencies.add(node.asText()));
        assertThat(currencies).containsExactlyInAnyOrder("KRW", "USD");

        // 대조군 — 같은 조건이 대상을 고르면 그대로 돈다. 거절이 조건 탓이 아니라는 증거다.
        JsonNode ok = run("{\"conditions\": " + conditions
                + ", \"universeCode\": \"" + CONTRACT_UNIVERSE + "\"}");
        assertThat(ok.get("currencyCode").asText())
                .as("결과가 선 시장의 통화. 화면의 모든 금액 표기가 이것 하나에 걸린다")
                .isEqualTo("KRW");
    }

    @Test
    @WithMockUser(username = SUB)
    @DisplayName("응답이 이 결과가 선 시장의 통화를 밝힌다 — 화면이 시장 코드에서 유도하지 않는다")
    void declaresTheCurrencyOfTheResult() throws Exception {
        JsonNode response = run("""
                {"conditions": {"conditions":[
                   {"indicatorCode":"PER","operator":"LT","operand":{"type":"LITERAL","value":10}}]}}
                """);

        assertThat(response.get("currencyCode").asText()).isEqualTo("KRW");
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

    /**
     * 종목을 만들고 <b>활성 유니버스에 편입시킨다.</b>
     *
     * <p>편입까지 하는 이유는 "전체"가 {@code stock} 전량이 아니기 때문이다(ADR-0087) —
     * 어느 활성 유니버스에도 없으면 조건과 무관하게 응답에서 빠지고, 그러면 이 계약 테스트가
     * 재려는 것(HTTP 결과와 인메모리 판정의 일치)이 <b>양쪽 다 0건이라 항등식</b>이 된다.
     */
    private long insertStock(String ticker, String name) {
        long stockId = jdbcTemplate.queryForObject("""
                INSERT INTO stock (market_code, ticker, name) VALUES ('TESTMKT', ?, ?) RETURNING id
                """, Long.class, ticker, name);
        jdbcTemplate.update("""
                INSERT INTO universe_membership (universe_code, stock_id, effective_from)
                VALUES (?, ?, ?) ON CONFLICT DO NOTHING
                """, CONTRACT_UNIVERSE, stockId, LocalDate.of(2020, 1, 2));
        return stockId;
    }

    private void insertValue(long stockId, String code, String value) {
        jdbcTemplate.update("""
                INSERT INTO indicator_value (stock_id, trade_date, indicator_code, value)
                VALUES (?, ?, ?, ?)
                ON CONFLICT (trade_date, indicator_code, stock_id) DO UPDATE SET value = EXCLUDED.value
                """, stockId, AS_OF, code, new BigDecimal(value));
    }
}
