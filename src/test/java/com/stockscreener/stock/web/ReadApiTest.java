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

    @Autowired
    private com.stockscreener.indicator.IndicatorTradeDateIndex tradeDateIndex;

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

        // 화면 기준일은 indicator_value가 아니라 파생 색인을 읽는다(ADR-0095). 운영에서는
        // 지표를 쓰는 잡이 끝에서 이것을 부르므로, 픽스처도 같은 계약을 따른다 —
        // 손으로 색인 행을 넣으면 잡이 그것을 빠뜨려도 이 테스트가 초록이 된다.
        tradeDateIndex.rebuild();

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
                // 전일(9,000) 대비. 셋이 함께 실린다 — 화면이 "무엇에 대한 대비인지"를 잃지 않는다.
                .andExpect(jsonPath("$.price.previousTradeDate").value(AS_OF.minusDays(1).toString()))
                .andExpect(jsonPath("$.price.change").value(1000.0))
                .andExpect(jsonPath("$.price.changeRate").value(11.11))
                .andExpect(jsonPath("$.indicators[?(@.code=='PER')].value")
                        .value(org.hamcrest.Matchers.hasItem(8.5)));
    }

    @Test
    @WithMockUser(username = SUB)
    @DisplayName("미보정 기업 행위를 지나면 등락을 내지 않는다 — 계수가 없으면 수정종가가 원주가와 같다")
    void detailWithholdsChangeAcrossUnadjustedAction() throws Exception {
        // 이 배선이 빠지면 단위 테스트는 전부 통과한 채로 007340이 화면에 -81.2%를 띄운다.
        // 컨트롤러가 효력일 목록을 실제로 넘기는가만 여기서 잴 수 있다.
        jdbcTemplate.update("""
                INSERT INTO corporate_action
                    (stock_id, effective_date, type, ratio, detection_source, status)
                VALUES (?, ?, 'UNKNOWN', 5, 'HEURISTIC', 'UNSUPPORTED')
                """, stockId, AS_OF);

        mockMvc.perform(get("/api/stocks/" + stockId).param("asOf", AS_OF.toString()))
                .andExpect(status().isOk())
                // 시세 자체는 그대로 나간다. 빠지는 것은 등락 셋뿐이다.
                .andExpect(jsonPath("$.price.close").value(10000))
                .andExpect(jsonPath("$.price.previousTradeDate").doesNotExist())
                .andExpect(jsonPath("$.price.change").doesNotExist())
                .andExpect(jsonPath("$.price.changeRate").doesNotExist());
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
    @DisplayName("보정 못 한 기업 행위 자리가 항상 실린다 — 프론트가 문구를 스스로 들지 못한다")
    void detailAlwaysCarriesTheUnadjustedActionSlot() throws Exception {
        mockMvc.perform(get("/api/stocks/" + stockId).param("asOf", AS_OF.toString()))
                .andExpect(status().isOk())
                // 사건이 없는 종목에서도 문구가 실린다. 선택 필드로 두면 프론트가 자기 문구를
                // 들게 되고, 두 곳이 갈린 것은 화면에서 드러나지 않는다(PriceReference와 같은 형태).
                .andExpect(jsonPath("$.unadjustedActions.notice").exists())
                .andExpect(jsonPath("$.unadjustedActions.effectiveDates").isEmpty());

        jdbcTemplate.update("""
                INSERT INTO corporate_action
                    (stock_id, effective_date, type, ratio, detection_source, status)
                VALUES (?, DATE '2026-03-02', 'UNKNOWN', 5, 'HEURISTIC', 'UNSUPPORTED')
                """, stockId);

        mockMvc.perform(get("/api/stocks/" + stockId).param("asOf", AS_OF.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.unadjustedActions.effectiveDates[0]").value("2026-03-02"));
    }

    @Test
    @WithMockUser(username = SUB)
    @DisplayName("운영자의 판정 기록(note)은 사용자에게 나가지 않는다")
    void detailDoesNotLeakTheOperatorsJudgementNote() throws Exception {
        jdbcTemplate.update("""
                INSERT INTO corporate_action
                    (stock_id, effective_date, type, ratio, detection_source, status, note)
                VALUES (?, DATE '2026-03-02', 'UNKNOWN', 5, 'HEURISTIC', 'UNSUPPORTED',
                        '인적분할. 당일 주식수 배수가 스냅되지 않는다')
                """, stockId);

        String body = mockMvc.perform(get("/api/stocks/" + stockId).param("asOf", AS_OF.toString()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(body).doesNotContain("스냅되지 않는다");
    }

    // ── 값이 없는 이유 ─────────────────────────────────────────────────────

    @Test
    @WithMockUser(username = SUB)
    @DisplayName("IMPORTANT — 적자로 PER이 없으면 카드를 지우지 않고 사유와 함께 남긴다")
    void carriesTheReasonWhenPerIsMissingBecauseOfLoss() throws Exception {
        // PER은 없고 EPS_TTM만 음수인 상태 — 실제 적자 종목이 그렇다.
        jdbcTemplate.update("DELETE FROM indicator_value WHERE stock_id = ? AND indicator_code = 'PER'",
                stockId);
        seedIndicator("EPS_TTM", new BigDecimal("-1500"));

        String body = mockMvc.perform(get("/api/stocks/" + stockId).param("asOf", AS_OF.toString()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        JsonNode per = find(objectMapper.readTree(body).get("indicators"), "PER");
        assertThat(per.get("value").isNull())
                .as("값은 비어야 한다 — 0으로 메우면 '싼 종목'으로 읽힌다")
                .isTrue();
        assertThat(per.get("unavailableReason").asText()).contains("적자");
    }

    @Test
    @WithMockUser(username = SUB)
    @DisplayName("사유를 말할 수 없는 지표는 그대로 빠진다 — 빈 카드를 깔지 않는다")
    void dropsIndicatorsWhoseAbsenceCannotBeExplained() throws Exception {
        // PER만 지우면 indicator_value가 통째로 비어 AsOfResolver가 503을 던진다("배치가 아직
        // 안 돌았다"). 그것은 이 테스트가 재려는 것이 아니므로 다른 지표를 하나 남긴다.
        seedIndicator("PBR", new BigDecimal("1.2"));
        jdbcTemplate.update("DELETE FROM indicator_value WHERE stock_id = ? AND indicator_code = 'PER'",
                stockId);

        String body = mockMvc.perform(get("/api/stocks/" + stockId).param("asOf", AS_OF.toString()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        // EPS_TTM이 없으니 적자 규칙이 성립하지 않는다. 그러면 PER은 카드로 남지 않는다.
        assertThat(body).doesNotContain("\"code\":\"PER\"");
    }

    @Test
    @WithMockUser(username = SUB)
    @DisplayName("값이 있으면 사유를 붙이지 않는다 — 사유는 부재의 설명이지 값의 주석이 아니다")
    void doesNotAttachReasonWhenValueExists() throws Exception {
        seedIndicator("EPS_TTM", new BigDecimal("-1500"));

        String body = mockMvc.perform(get("/api/stocks/" + stockId).param("asOf", AS_OF.toString()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        JsonNode per = find(objectMapper.readTree(body).get("indicators"), "PER");
        assertThat(per.get("value").isNull()).isFalse();
        assertThat(per.get("unavailableReason").isNull()).isTrue();
    }

    @Test
    @WithMockUser(username = SUB)
    @DisplayName("보험·금융서비스의 Altman Z 부재는 결손이 아니라 설계라고 말한다")
    void explainsAltmanZExclusionForInsurers() throws Exception {
        jdbcTemplate.update("UPDATE stock SET industry_code = '65121' WHERE id = ?", stockId);

        String body = mockMvc.perform(get("/api/stocks/" + stockId).param("asOf", AS_OF.toString()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        JsonNode z = find(objectMapper.readTree(body).get("indicators"), "ALTMAN_Z");
        assertThat(z.get("value").isNull()).isTrue();
        assertThat(z.get("unavailableReason").asText()).contains("적용하지 않는");
    }

    // ── 실적 원수치 ────────────────────────────────────────────────────────

    @Test
    @WithMockUser(username = SUB)
    @DisplayName("IMPORTANT — 4분기가 유도값임을 응답이 말한다. 없으면 공시된 실적으로 읽힌다")
    void fourthQuarterIsMarkedAsDerived() throws Exception {
        seedFinancials();

        String body = mockMvc.perform(get("/api/stocks/" + stockId + "/financials"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.notice.derivedFourthQuarter").isNotEmpty())
                .andExpect(jsonPath("$.notice.quarterlyCashFlow").isNotEmpty())
                .andReturn().getResponse().getContentAsString();

        JsonNode quarterly = objectMapper.readTree(body).get("quarterly");
        assertThat(period(quarterly, 2025, "Q4").get("derived").asBoolean()).isTrue();
        assertThat(period(quarterly, 2025, "Q3").get("derived").asBoolean())
                .as("유도는 4분기만이다 — 전부 표시하면 표시가 뜻을 잃는다")
                .isFalse();
    }

    @Test
    @WithMockUser(username = SUB)
    @DisplayName("IMPORTANT — 합성 기간(period_start/end)을 내보내지 않는다. 미래 기간이 표에 뜬다")
    void doesNotCarrySyntheticPeriodBounds() throws Exception {
        seedFinancials();

        String body = mockMvc.perform(get("/api/stocks/" + stockId + "/financials"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(body)
                .as("결산월이 12월이 아닌 종목에서 이 값은 아직 오지 않은 기간을 가리킨다(ADR-0056)")
                .doesNotContain("periodStart").doesNotContain("periodEnd");
    }

    @Test
    @WithMockUser(username = SUB)
    @DisplayName("전년 동기와 비교한다 — 직전 분기와 비교하면 계절성 업종에서 뜻이 없다")
    void growthComparesTheSameQuarterLastYear() throws Exception {
        seedFinancials();

        String body = mockMvc.perform(get("/api/stocks/" + stockId + "/financials"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        JsonNode quarterly = objectMapper.readTree(body).get("quarterly");
        // 2025 Q1 매출 120, 2024 Q1 매출 100 → +20%. 직전 분기(2024 Q4 = 400)와 비교했다면 -70%다.
        assertThat(period(quarterly, 2025, "Q1").get("revenueYoy").decimalValue())
                .isEqualByComparingTo("20");
        assertThat(period(quarterly, 2024, "Q1").get("revenueYoy").isNull())
                .as("전년이 없으면 값을 만들지 않는다")
                .isTrue();
    }

    @Test
    @WithMockUser(username = SUB)
    @DisplayName("실적은 상세에 실리지 않는다 — 별도 요청이라 한쪽이 실패해도 다른 쪽이 산다")
    void detailDoesNotCarryFinancials() throws Exception {
        seedFinancials();

        String body = mockMvc.perform(get("/api/stocks/" + stockId).param("asOf", AS_OF.toString()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(body).doesNotContain("quarterly").doesNotContain("operatingCashFlow");
    }

    // ── 업종 표본 고지 ──────────────────────────────────────────────────────

    @Test
    @WithMockUser(username = SUB)
    @DisplayName("IMPORTANT — 업종 표본이 모자라면 그 사실이 상세에 남는다. 없으면 수집 실패와 구분되지 않는다")
    void detailExplainsWhySectorMetricsAreMissing() throws Exception {
        jdbcTemplate.update("UPDATE stock SET industry_code = '26111' WHERE id = ?", stockId);

        String body = mockMvc.perform(get("/api/stocks/" + stockId).param("asOf", AS_OF.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sectorContext.evaluable").value(false))
                .andExpect(jsonPath("$.sectorContext.notice").isNotEmpty())
                .andReturn().getResponse().getContentAsString();

        assertThat(body)
                .as("표본이 적은 것을 강점으로 표현하지 않는다(ADR-0044 §버린 대안)")
                .contains("업종에서 앞선다는 뜻이 아닙니다");
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

    /** 2024·2025 각 FY와 Q1~Q4. Q4는 어댑터가 유도한 행과 같은 모양(사업보고서 코드)이다. */
    private void seedFinancials() {
        for (int year : new int[]{2024, 2025}) {
            insertFinancial(year, "FY", "11011", 1000 + (year - 2024) * 200);
            insertFinancial(year, "Q1", "11013", 100 + (year - 2024) * 20);
            insertFinancial(year, "Q2", "11012", 200);
            insertFinancial(year, "Q3", "11014", 300);
            insertFinancial(year, "Q4", "11011", 400);
        }
    }

    private void insertFinancial(int year, String period, String reportCode, long revenue) {
        jdbcTemplate.update("""
                INSERT INTO financial_statement (stock_id, fiscal_year, fiscal_period,
                        period_start, period_end, published_at, revision_no, is_latest,
                        report_code, currency_code, revenue, operating_income, net_income,
                        total_assets, total_liabilities, total_equity)
                VALUES (?, ?, ?, ?, ?, ?, 0, true, ?, 'KRW', ?, ?, ?, 9000, 4000, 5000)
                ON CONFLICT DO NOTHING
                """, stockId, year, period,
                LocalDate.of(year, 1, 1), periodEnd(year, period), LocalDate.of(year + 1, 3, 10),
                reportCode, revenue, revenue / 10, revenue / 20);
    }

    private static LocalDate periodEnd(int year, String period) {
        return switch (period) {
            case "Q1" -> LocalDate.of(year, 3, 31);
            case "Q2" -> LocalDate.of(year, 6, 30);
            case "Q3" -> LocalDate.of(year, 9, 30);
            default -> LocalDate.of(year, 12, 31);
        };
    }

    private static JsonNode period(JsonNode periods, int fiscalYear, String fiscalPeriod) {
        for (JsonNode node : periods) {
            if (node.get("fiscalYear").asInt() == fiscalYear
                    && fiscalPeriod.equals(node.get("fiscalPeriod").asText())) {
                return node;
            }
        }
        throw new AssertionError(fiscalYear + " " + fiscalPeriod + "을 응답에서 찾지 못했다");
    }

    private void seedIndicator(String code, BigDecimal value) {
        jdbcTemplate.update("""
                INSERT INTO indicator (code, name, category, value_type)
                VALUES (?, ?, 'FUNDAMENTAL', 'RATIO') ON CONFLICT (code) DO NOTHING
                """, code, code);
        jdbcTemplate.update("""
                INSERT INTO indicator_value (stock_id, trade_date, indicator_code, value)
                VALUES (?, ?, ?, ?)
                ON CONFLICT (trade_date, indicator_code, stock_id) DO UPDATE SET value = EXCLUDED.value
                """, stockId, AS_OF, code, value);
    }
}
