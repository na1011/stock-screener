package com.stockscreener.stock.web;

import com.stockscreener.stock.PriceReference;
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

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 조회 API — 지표 카탈로그, 종목 상세, 지지·저항, 유니버스, 오류 규약.
 *
 * <p>가장 중요한 검사는 <b>지지·저항이 지표와 섞이지 않는다</b>는 것이다. frontend.md가
 * 그 구분을 요구하는 이유를 명시한다 — 사용자가 참고정보를 확정 신호로 오해하면 실제
 * 금전적 손실로 이어진다.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestDatabaseGuard.class)
@Transactional
class ReadApiTest {

    private static final String SUB = "sub-read-api";
    private static final LocalDate AS_OF = LocalDate.of(2026, 8, 5);
    private static final String UNIVERSE = "READTESTUNIV";

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
        jdbcTemplate.update("""
                INSERT INTO market (code, country_code, currency_code, timezone)
                VALUES ('TESTMKT','KR','KRW','Asia/Seoul') ON CONFLICT DO NOTHING
                """);
        jdbcTemplate.update("""
                INSERT INTO app_user (email, name, provider, provider_user_id, is_admin, is_active)
                VALUES ('read@example.com','조회','GOOGLE',?,false,true) ON CONFLICT DO NOTHING
                """, SUB);
        jdbcTemplate.update("""
                INSERT INTO universe (code, name, market_code)
                VALUES (?, '조회 테스트 유니버스', 'TESTMKT') ON CONFLICT DO NOTHING
                """, UNIVERSE);

        stockId = jdbcTemplate.queryForObject("""
                INSERT INTO stock (market_code, ticker, name, sector)
                VALUES ('TESTMKT','910001','조회테스트주','반도체') RETURNING id
                """, Long.class);

        jdbcTemplate.update("""
                INSERT INTO universe_membership (universe_code, stock_id, effective_from)
                VALUES (?, ?, ?)
                """, UNIVERSE, stockId, AS_OF.minusYears(1));

        jdbcTemplate.update("""
                INSERT INTO indicator_value (stock_id, trade_date, indicator_code, value)
                VALUES (?, ?, 'PER', ?)
                ON CONFLICT (trade_date, indicator_code, stock_id) DO UPDATE SET value = EXCLUDED.value
                """, stockId, AS_OF, new BigDecimal("8.5"));

        // 지지·저항 계산에 쓸 봉. trade_value는 일부러 NULL로 둔다 — 응답이 견뎌야 한다.
        for (int i = 0; i < 130; i++) {
            LocalDate date = AS_OF.minusDays(130 - i);
            BigDecimal close = BigDecimal.valueOf((i / 10) % 2 == 0 ? 9_000 : 11_000);
            jdbcTemplate.update("""
                    INSERT INTO price_daily (stock_id, trade_date, open, high, low, close,
                                             adj_close, adj_factor, volume)
                    VALUES (?, ?, ?, ?, ?, ?, ?, 1, 1000)
                    ON CONFLICT DO NOTHING
                    """, stockId, date, close,
                    close.multiply(new BigDecimal("1.01")), close.multiply(new BigDecimal("0.99")),
                    close, close);
        }
        jdbcTemplate.update("""
                INSERT INTO price_daily (stock_id, trade_date, open, high, low, close,
                                         adj_close, adj_factor, volume)
                VALUES (?, ?, 10000, 10100, 9900, 10000, 10000, 1, 5000)
                ON CONFLICT DO NOTHING
                """, stockId, AS_OF);
    }

    // ── 지표 카탈로그 ──────────────────────────────────────────────────────

    @Test
    @WithMockUser(username = SUB)
    @DisplayName("IMPORTANT — 백분위의 유리한 방향을 서버가 계산해서 준다")
    void percentileGuideCarriesFavorableDirection() throws Exception {
        String body = mockMvc.perform(get("/api/indicators"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        JsonNode indicators = objectMapper.readTree(body).get("indicators");
        JsonNode perPctl = find(indicators, "PER_PCTL");
        JsonNode roePctl = find(indicators, "ROE_PCTL");

        // 백분위는 언제나 오름차순인데 "상위 20%"의 부등호는 원본의 유불리에 따라 뒤집힌다.
        // 프론트가 이것을 판단하려면 지표 코드를 알아야 하고, 그것은 옮겨간 하드코딩 분기다.
        assertThat(perPctl.get("percentile").get("favorableEnd").asText()).isEqualTo("LOW");
        assertThat(perPctl.get("percentile").get("topQuintileOperator").asText()).isEqualTo("LTE");
        assertThat(roePctl.get("percentile").get("favorableEnd").asText()).isEqualTo("HIGH");
        assertThat(roePctl.get("percentile").get("topQuintileOperator").asText()).isEqualTo("GTE");
        assertThat(perPctl.get("percentileOf").asText()).isEqualTo("PER");
    }

    @Test
    @WithMockUser(username = SUB)
    @DisplayName("지표의 의미와 유리한 방향이 함께 실린다 — 값만 나열하지 않는다")
    void catalogCarriesMeaning() throws Exception {
        String body = mockMvc.perform(get("/api/indicators"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        JsonNode per = find(objectMapper.readTree(body).get("indicators"), "PER");
        assertThat(per.get("description").asText()).isNotBlank();
        assertThat(per.get("interpretation").asText()).isNotBlank();
        assertThat(per.get("higherIsBetter").asBoolean()).isFalse();
        assertThat(per.get("defaultColumn").asBoolean()).isTrue();
    }

    @Test
    @WithMockUser(username = SUB)
    @DisplayName("IMPORTANT — 카탈로그에 지지·저항 코드가 없다. 조건의 좌변이 될 수 없다")
    void catalogHasNoSupportResistanceCode() throws Exception {
        String body = mockMvc.perform(get("/api/indicators"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(body).doesNotContain("SUPPORT").doesNotContain("RESISTANCE");
    }

    @Test
    @WithMockUser(username = SUB)
    @DisplayName("연산자 목록을 함께 준다 — 프론트가 한글 라벨을 복제하지 않게 한다")
    void catalogCarriesOperators() throws Exception {
        mockMvc.perform(get("/api/indicators"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.operators[?(@.name=='BETWEEN')].requiresUpperBound")
                        .value(org.hamcrest.Matchers.hasItem(true)))
                .andExpect(jsonPath("$.operators[?(@.name=='LT')].label")
                        .value(org.hamcrest.Matchers.hasItem("미만")));
    }

    // ── 종목 상세 ─────────────────────────────────────────────────────────

    @Test
    @WithMockUser(username = SUB)
    @DisplayName("종목 상세가 메타·시세·지표를 준다. 거래대금이 없어도 200이다")
    void stockDetail() throws Exception {
        mockMvc.perform(get("/api/stocks/" + stockId).param("asOf", AS_OF.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stock.ticker").value("910001"))
                .andExpect(jsonPath("$.stock.sector").value("반도체"))
                .andExpect(jsonPath("$.stock.universes[0].code").value(UNIVERSE))
                .andExpect(jsonPath("$.price.close").value(10000))
                // V9 이전 행에는 없고 소스가 안 주면 NULL이다. 0으로 채우면 사실이 아니다.
                .andExpect(jsonPath("$.price.tradeValue").doesNotExist())
                .andExpect(jsonPath("$.indicators[?(@.code=='PER')].value")
                        .value(org.hamcrest.Matchers.hasItem(8.5)));
    }

    @Test
    @WithMockUser(username = SUB)
    @DisplayName("상세 응답에 지지·저항이 없다 — 다른 엔드포인트다")
    void detailDoesNotCarryPriceReference() throws Exception {
        String body = mockMvc.perform(get("/api/stocks/" + stockId).param("asOf", AS_OF.toString()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        // 형제 필드로 두면 프론트가 같은 패널에 그리기 쉽고, 계산 실패가 상세 전체를 500으로 만든다.
        assertThat(body).doesNotContain("SUPPORT_RESISTANCE_REFERENCE").doesNotContain("zones");
    }

    @Test
    @WithMockUser(username = SUB)
    @DisplayName("없는 종목은 404다")
    void missingStockIsNotFound() throws Exception {
        mockMvc.perform(get("/api/stocks/999999999"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.type").value("urn:stockscreener:stock-not-found"));
    }

    // ── 지지·저항 ─────────────────────────────────────────────────────────

    @Test
    @WithMockUser(username = SUB)
    @DisplayName("IMPORTANT — 한계 표시가 페이로드의 필수 필드다. 프론트가 빠뜨릴 수 없다")
    void priceReferenceAlwaysCarriesDisclaimer() throws Exception {
        String body = mockMvc.perform(
                        get("/api/stocks/" + stockId + "/price-reference").param("asOf", AS_OF.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.kind").value(PriceReference.KIND))
                .andExpect(jsonPath("$.method").value(PriceReference.METHOD))
                .andReturn().getResponse().getContentAsString();

        JsonNode reference = objectMapper.readTree(body);
        assertThat(reference.get("disclaimer").asText())
                .contains("확정된 매매 신호가 아니다")
                .contains("진입가·손절가로 사용하지 말 것");
        assertThat(reference.get("window").get("tradingDays").asInt()).isPositive();
    }

    @Test
    @WithMockUser(username = SUB)
    @DisplayName("IMPORTANT — 구간에 지표 코드가 없다. 근거(터치 수)는 항상 함께 온다")
    void zonesAreNotIndicatorsAndCarryEvidence() throws Exception {
        String body = mockMvc.perform(
                        get("/api/stocks/" + stockId + "/price-reference").param("asOf", AS_OF.toString()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        JsonNode zones = objectMapper.readTree(body).get("zones");
        assertThat(zones.size()).isPositive();
        zones.forEach(zone -> {
            // 지표 코드가 붙으면 조건의 좌변으로 쓸 수 있다는 인상을 준다.
            assertThat(zone.has("code")).isFalse();
            assertThat(zone.has("indicatorCode")).isFalse();
            // 숫자만 주지 않고 왜 그 구간인지를 함께 준다.
            assertThat(zone.get("touchCount").asInt()).isGreaterThanOrEqualTo(2);
            assertThat(zone.get("lastTouchedOn").isNull()).isFalse();
            assertThat(zone.get("role").asText())
                    .isIn("SUPPORT", "RESISTANCE", "IN_RANGE");
        });
    }

    @Test
    @WithMockUser(username = SUB)
    @DisplayName("진입가·손절가에 해당하는 단일 값을 내지 않는다")
    void neverSuggestsExecutionPrices() throws Exception {
        String body = mockMvc.perform(
                        get("/api/stocks/" + stockId + "/price-reference").param("asOf", AS_OF.toString()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(body)
                .doesNotContain("entryPrice").doesNotContain("stopLoss").doesNotContain("targetPrice");
    }

    // ── 유니버스 ──────────────────────────────────────────────────────────

    @Test
    @WithMockUser(username = SUB)
    @DisplayName("편입 종목이 없는 유니버스는 선택지에 나오지 않는다")
    void universeListExcludesEmptyOnes() throws Exception {
        String body = mockMvc.perform(get("/api/universes"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        JsonNode universes = objectMapper.readTree(body);
        assertThat(universes.size()).isPositive();
        universes.forEach(u -> assertThat(u.get("memberCount").asInt()).isPositive());
        // 데이터가 없는 미국은 자동으로 빠진다. v1.1에서 붙으면 자동으로 나타난다.
        assertThat(body).doesNotContain("SP500");
    }

    // ── 오류 규약 ─────────────────────────────────────────────────────────

    @Test
    @WithMockUser(username = SUB)
    @DisplayName("잘못된 JSON은 problem+json으로 400이고 스택트레이스를 담지 않는다")
    void malformedJsonIsProblemDetail() throws Exception {
        String body = mockMvc.perform(post("/api/screening")
                        .with(org.springframework.security.test.web.servlet.request
                                .SecurityMockMvcRequestPostProcessors.csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{ this is not json"))
                .andExpect(status().isBadRequest())
                .andReturn().getResponse().getContentAsString();

        JsonNode problem = objectMapper.readTree(body);
        assertThat(problem.has("status")).isTrue();
        assertThat(problem.has("title")).isTrue();
        // 프레임워크 예외로 떨어져도 프론트의 재시도 판별이 동작해야 한다.
        assertThat(problem.get("retryable").asBoolean()).isFalse();
        assertThat(body).doesNotContain("at org.springframework");
    }

    @Test
    @WithMockUser(username = SUB)
    @DisplayName("없는 경로는 404다")
    void unknownPathIsNotFound() throws Exception {
        mockMvc.perform(get("/api/nope")).andExpect(status().isNotFound());
    }

    private static JsonNode find(JsonNode indicators, String code) {
        for (JsonNode node : indicators) {
            if (code.equals(node.get("code").asText())) {
                return node;
            }
        }
        throw new AssertionError("카탈로그에 " + code + "가 없다");
    }
}
