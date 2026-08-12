package com.stockscreener.pipeline.collector.web;

import com.stockscreener.auth.AllowlistOidcUserService;
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

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 원장을 사람이 판정하는 HTTP 표면 (ADR-0050).
 *
 * <p>진짜 PostgreSQL을 쓴다. 여기서 확인하려는 것의 절반이 <b>증거 SQL</b>(창 함수·LATERAL
 * 조인)이고, 그것을 가짜로 바꾸면 아무것도 검증되지 않는다.
 *
 * <p>픽스처는 루닛 2026-05-04 실측을 그대로 옮긴다 — 권리락 이후 75일 창에 주식수 점프가
 * <b>셋</b>이고 그중 하나만 그 권리락을 설명한다. 이 모양이 "증거는 계산하되 고르지 않는다"를
 * 재는 유일한 형태다.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestDatabaseGuard.class)
@Transactional
@WithMockUser(authorities = {AllowlistOidcUserService.ROLE_USER, AllowlistOidcUserService.ROLE_ADMIN})
class AdminCorporateActionApiTest {

    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private long stockId;
    private long rowId;

    @Autowired
    void setUp(WebApplicationContext context) {
        this.mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    @BeforeEach
    void seed() {
        jdbcTemplate.update("DELETE FROM corporate_action");
        stockId = jdbcTemplate.queryForObject("""
                INSERT INTO stock (market_code, ticker, name, is_active)
                VALUES ('KOSDAQ', '900328', '루닛픽스처', true)
                ON CONFLICT (market_code, ticker) DO UPDATE SET name = EXCLUDED.name
                RETURNING id
                """, Long.class);
        jdbcTemplate.update("DELETE FROM price_daily WHERE stock_id = ?", stockId);

        // 권리락 직전·당일 — 주식수는 그대로다. 실측 39건이 이 모양이고 휴리스틱은 종류를 못 낸다.
        bar("2026-04-30", "41450", 29_284_502L);
        bar("2026-05-04", "20800", 29_284_502L);
        // 창 안의 점프 셋. 가운데만 이 권리락을 설명한다.
        bar("2026-05-15", "20000", 37_191_318L);   // 전환권행사 ×1.27
        bar("2026-05-26", "17900", 74_382_636L);   // 진짜 신주상장 ×2.0
        bar("2026-06-19", "18000", 74_458_180L);   // 잡음 ×1.001

        rowId = jdbcTemplate.queryForObject("""
                INSERT INTO corporate_action
                    (stock_id, effective_date, type, ratio, detection_source, status, source_form)
                VALUES (?, '2026-05-04', 'UNKNOWN', 1.9928, 'HEURISTIC', 'OBSERVED', NULL)
                RETURNING id
                """, Long.class, stockId);
    }

    @Test
    @DisplayName("목록이 종목명과 효력일의 관측값을 함께 준다 — 티커만으로는 판정할 수 없다")
    void listCarriesObservation() throws Exception {
        mockMvc.perform(get("/api/admin/corporate-actions").param("ticker", "900328"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].stockName").value("루닛픽스처"))
                .andExpect(jsonPath("$[0].prevClose").value(41450))
                .andExpect(jsonPath("$[0].close").value(20800))
                // 권리락 당일 주식수가 그대로다 — 그것이 무상증자류의 표식이다.
                .andExpect(jsonPath("$[0].sameDayShareRatio").value(1.0));
    }

    @Test
    @DisplayName("IMPORTANT — type으로 좁힐 수 있다. 값을 지어내야 하는 행과 예·아니오만 답할 행은 다른 일이다")
    void listFiltersByTypeAndStatus() throws Exception {
        mockMvc.perform(get("/api/admin/corporate-actions").param("type", "UNKNOWN"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));
        mockMvc.perform(get("/api/admin/corporate-actions").param("type", "SPLIT"))
                .andExpect(jsonPath("$.length()").value(0));
        mockMvc.perform(get("/api/admin/corporate-actions").param("status", "APPROVED"))
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    @DisplayName("IMPORTANT — 증거가 점프를 셋 다 주고 하나만 일치로 표시한다. 고르는 것은 사람이다")
    void detailListsEveryJumpAndMarksOnlyTheMatchingOne() throws Exception {
        mockMvc.perform(get("/api/admin/corporate-actions/" + rowId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.shareJumps.length()").value(3))
                // 05-15 전환권행사 — relative 0.6373이라 권리락 폭 밖이다.
                .andExpect(jsonPath("$.shareJumps[0].date").value("2026-05-15"))
                .andExpect(jsonPath("$.shareJumps[0].matchesPrice").value(false))
                // 05-26 진짜 신주상장 — relative 1.0036.
                .andExpect(jsonPath("$.shareJumps[1].date").value("2026-05-26"))
                .andExpect(jsonPath("$.shareJumps[1].ratio").value(2.0))
                .andExpect(jsonPath("$.shareJumps[1].matchesPrice").value(true))
                // 06-19 잡음 — relative 0.5023.
                .andExpect(jsonPath("$.shareJumps[2].matchesPrice").value(false));
    }

    @Test
    @DisplayName("판정이 종류와 비율을 고치고 갱신된 증거를 그대로 돌려준다")
    void judgementFixesTypeAndRatio() throws Exception {
        mockMvc.perform(put("/api/admin/corporate-actions/" + rowId).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"status":"APPROVED","type":"BONUS_ISSUE","ratio":2.0,
                                 "listingDate":"2026-05-26","note":"신주상장으로 확인"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.action.type").value("BONUS_ISSUE"))
                .andExpect(jsonPath("$.action.status").value("APPROVED"))
                .andExpect(jsonPath("$.action.listingDate").value("2026-05-26"))
                .andExpect(jsonPath("$.action.note").value("신주상장으로 확인"))
                // 승인해도 적용은 아니다 — 재구축이 돌기 전까지 applied_at은 비어 있다.
                .andExpect(jsonPath("$.action.appliedAt").doesNotExist());
    }

    @Test
    @DisplayName("요약이 종류를 모르는 행 수를 따로 센다 — 그 수가 이 phase의 성공을 가른다")
    void summarySeparatesUnknownType() throws Exception {
        mockMvc.perform(get("/api/admin/corporate-actions/summary"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].status").value("OBSERVED"))
                .andExpect(jsonPath("$[0].total").value(1))
                .andExpect(jsonPath("$[0].unknownType").value(1));
    }

    @Test
    @DisplayName("종류를 모르는 채로 승인하면 400이고 필드가 지목된다")
    void approvingAnUnknownRowIsRejected() throws Exception {
        mockMvc.perform(put("/api/admin/corporate-actions/" + rowId).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"APPROVED\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("urn:stockscreener:invalid-request"))
                .andExpect(jsonPath("$.errors[0].field").value("type"));
    }

    @Test
    @DisplayName("IMPORTANT — 음수 비율은 400이다. DB 제약까지 가면 500이 된다")
    void negativeRatioIsFourHundredNotFiveHundred() throws Exception {
        mockMvc.perform(put("/api/admin/corporate-actions/" + rowId).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"UNKNOWN\",\"ratio\":-5}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("ratio"));
    }

    @Test
    @DisplayName("사람이 APPLIED를 찍을 수 없다 — 계수는 그대로인데 원장이 반영됐다고 말하게 된다")
    void humanCannotForgeApplied() throws Exception {
        mockMvc.perform(put("/api/admin/corporate-actions/" + rowId).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"APPLIED\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("status"));
    }

    @Test
    @DisplayName("없는 id는 404이고 프론트가 분기할 수 있는 type을 준다")
    void unknownIdIsNotFound() throws Exception {
        mockMvc.perform(get("/api/admin/corporate-actions/9999999"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.type").value("urn:stockscreener:corporate-action-not-found"));

        mockMvc.perform(put("/api/admin/corporate-actions/9999999").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"REJECTED\"}"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("관리자가 아니면 403이다 — 원장 판정은 저장된 시세를 소급해 바꾸는 근거다")
    @WithMockUser(authorities = AllowlistOidcUserService.ROLE_USER)
    void nonAdminIsForbidden() throws Exception {
        mockMvc.perform(get("/api/admin/corporate-actions")).andExpect(status().isForbidden());
    }

    private void bar(String date, String close, long listedShares) {
        BigDecimal c = new BigDecimal(close);
        jdbcTemplate.update("""
                INSERT INTO price_daily
                    (stock_id, trade_date, open, high, low, close, volume, listed_shares,
                     adj_factor, adj_close)
                VALUES (?, ?, ?, ?, ?, ?, 1000000, ?, 1, ?)
                """, stockId, LocalDate.parse(date), c, c, c, c, listedShares, c);
    }
}
