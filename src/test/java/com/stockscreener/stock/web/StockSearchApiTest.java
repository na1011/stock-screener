package com.stockscreener.stock.web;

import com.stockscreener.support.TestDatabaseGuard;
import com.stockscreener.web.ApiProblemType;

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

import java.time.LocalDate;

import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code GET /api/stocks?q=} — 종목 검색.
 *
 * <p>가장 중요한 검사는 <b>편입되지 않은 종목이 결과에 없다</b>는 것이다. 편입되지 않은 종목은
 * 시세도 지표도 없으므로, 검색으로 들어가면 값이 하나도 없는 상세 화면을 보게 된다.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestDatabaseGuard.class)
@Transactional
class StockSearchApiTest {

    private static final String SUB = "sub-stock-search";
    private static final String MARKET = "SRCHMKT";
    private static final String UNIVERSE = "SRCHUNIV";

    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    void setUp(WebApplicationContext context) {
        this.mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    @BeforeEach
    void seed() {
        jdbcTemplate.update("""
                INSERT INTO market (code, country_code, currency_code, timezone)
                VALUES (?, 'KR', 'KRW', 'Asia/Seoul') ON CONFLICT DO NOTHING
                """, MARKET);
        jdbcTemplate.update("""
                INSERT INTO app_user (email, name, provider, provider_user_id, is_admin, is_active)
                VALUES ('search@example.com','검색','GOOGLE',?,false,true) ON CONFLICT DO NOTHING
                """, SUB);
        jdbcTemplate.update("""
                INSERT INTO universe (code, name, market_code, is_active)
                VALUES (?, '검색 테스트 유니버스', ?, true) ON CONFLICT DO NOTHING
                """, UNIVERSE, MARKET);

        member("005930", "삼성전자");
        member("009150", "삼성전기");
        member("000660", "SK하이닉스");
        // 편입되지 않은 종목. 이름이 걸려도 결과에 나오면 안 된다.
        insertStock("900001", "삼성폐지된종목");
    }

    private void member(String ticker, String name) {
        long id = insertStock(ticker, name);
        jdbcTemplate.update("""
                INSERT INTO universe_membership (universe_code, stock_id, effective_from)
                VALUES (?, ?, ?)
                """, UNIVERSE, id, LocalDate.of(2020, 1, 2));
    }

    private long insertStock(String ticker, String name) {
        return jdbcTemplate.queryForObject("""
                INSERT INTO stock (market_code, ticker, name, sector)
                VALUES (?, ?, ?, '테스트') RETURNING id
                """, Long.class, MARKET, ticker, name);
    }

    @Test
    @WithMockUser(username = SUB)
    @DisplayName("티커 완전일치가 첫 줄에 온다")
    void exactTickerRanksFirst() throws Exception {
        mockMvc.perform(get("/api/stocks").param("q", "005930"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].ticker").value("005930"))
                .andExpect(jsonPath("$[0].name").value("삼성전자"))
                .andExpect(jsonPath("$[0].universeCodes[0]").value(UNIVERSE));
    }

    @Test
    @WithMockUser(username = SUB)
    @DisplayName("종목명 접두 일치가 부분 일치보다 앞에 온다")
    void namePrefixRanksBeforeContains() throws Exception {
        // "삼성"을 친 사용자는 삼성전자를 첫 줄에서 보아야 한다. 포함 검색만 하면 순서가 섞인다.
        mockMvc.perform(get("/api/stocks").param("q", "삼성"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].name").value("삼성전기"))
                .andExpect(jsonPath("$[1].name").value("삼성전자"));
    }

    @Test
    @WithMockUser(username = SUB)
    @DisplayName("IMPORTANT — 편입되지 않은 종목은 결과에 없다")
    void nonMembersAreExcluded() throws Exception {
        // 편입되지 않은 종목은 시세도 지표도 없다. 검색으로 들어가면 빈 상세 화면을 보게 된다.
        mockMvc.perform(get("/api/stocks").param("q", "삼성"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.ticker == '900001')]").isEmpty());
    }

    @Test
    @WithMockUser(username = SUB)
    @DisplayName("대소문자를 가리지 않는다")
    void searchIsCaseInsensitive() throws Exception {
        mockMvc.perform(get("/api/stocks").param("q", "sk하이닉스"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].ticker").value("000660"))
        ;
    }

    @Test
    @WithMockUser(username = SUB)
    @DisplayName("일치가 없으면 404가 아니라 빈 배열이다")
    void noMatchIsEmptyArray() throws Exception {
        // 404로 주면 프론트가 "검색 결과 없음"을 재시도 불가 오류로 다루게 된다.
        mockMvc.perform(get("/api/stocks").param("q", "없는종목이름"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$").isEmpty());
    }

    @Test
    @WithMockUser(username = SUB)
    @DisplayName("빈 검색어는 우리 규약의 400이다 — about:blank가 아니다")
    void blankQueryIsInvalidRequest() throws Exception {
        // 프론트는 type(URN)으로 분기한다. 스프링 기본 예외를 흘리면 그 값이 about:blank가 된다.
        mockMvc.perform(get("/api/stocks").param("q", "   "))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value(ApiProblemType.INVALID_REQUEST.type().toString()))
                .andExpect(jsonPath("$.retryable").value(false))
                .andExpect(jsonPath("$.errors[0].field").value("q"));
    }

    @Test
    @WithMockUser(username = SUB)
    @DisplayName("limit이 범위를 벗어나면 400이다")
    void limitOutOfRangeIsRejected() throws Exception {
        mockMvc.perform(get("/api/stocks").param("q", "삼성").param("limit", "0"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("limit"));

        mockMvc.perform(get("/api/stocks").param("q", "삼성").param("limit", "999"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("limit"));
    }

    @Test
    @WithMockUser(username = SUB)
    @DisplayName("limit이 결과 수를 제한한다")
    void limitCapsResults() throws Exception {
        mockMvc.perform(get("/api/stocks").param("q", "삼성").param("limit", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));
    }
}
