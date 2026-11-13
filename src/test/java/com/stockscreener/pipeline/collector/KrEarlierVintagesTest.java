package com.stockscreener.pipeline.collector;

import com.stockscreener.support.TestDatabaseGuard;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 국내 「같은 숫자를 처음 공개한 판」 넣기(ADR-0195 · [A65]). 모양은 2026-10-31 실측에서 옮겼다 — KB금융 FY2025는 원본 03-13 · 정정
 * 03-24 · 06-19이고 저장은 06-19 하나였으며, 원본과 06-19 정정의 XBRL 인스턴스가 바이트까지 같았다.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
@Import(TestDatabaseGuard.class)
class KrEarlierVintagesTest {

    private static final String SAME = """
            [{"s":"I","c":"ifrs-full_ProfitLoss","b":"2025-01-01","e":"2025-12-31","k":"C","m":[],"v":"5000"},
             {"s":"I","c":"ifrs-full_Equity","b":null,"e":"2025-12-31","k":"C","m":[],"v":"90000"},
             {"s":"I","c":"ifrs-full_Equity","b":null,"e":"2025-12-31","k":"C","m":[["A","B","I"]],"v":"1"}]""";
    private static final String CHANGED = """
            [{"s":"I","c":"ifrs-full_ProfitLoss","b":"2025-01-01","e":"2025-12-31","k":"C","m":[],"v":"5100"},
             {"s":"I","c":"ifrs-full_Equity","b":null,"e":"2025-12-31","k":"C","m":[],"v":"90000"}]""";

    @Autowired
    private KrEarlierVintages earlier;
    @Autowired
    private JdbcTemplate jdbc;

    private long stockId;

    @BeforeEach
    void stock() {
        stockId = jdbc.queryForObject("""
                INSERT INTO stock (market_code, ticker, name, accounting_month) VALUES ('KOSPI', '900901', '시험금융', 12) RETURNING id
                """, Long.class);
    }

    @Test
    @DisplayName("원본과 숫자가 같은 정정에 기댄 첫 판이면 원본 접수일로 같은 값의 판을 하나 넣는다 — 기존 행은 그대로 · 다시 돌아도 안 는다")
    void insertsTheOriginalDateWhenTheNumbersAreUnchanged() {
        annual(LocalDate.of(2026, 6, 19));
        filing("20260313001191", LocalDate.of(2026, 3, 13), SAME);
        filing("20260324000835", LocalDate.of(2026, 3, 24), SAME);
        filing("20260619000667", LocalDate.of(2026, 6, 19), SAME);

        assertThat(earlier.insert(stockId).inserted()).isEqualTo(2);
        assertThat(earlier.insert(stockId).inserted()).as("멱등").isZero();

        assertThat(vintages("FY")).containsExactly("2026-03-13 0 false 5000", "2026-06-19 1 true 5000");
        assertThat(vintages("Q4")).as("같은 보고서의 유도 Q4도 함께").containsExactly("2026-03-13 0 false 1250",
                "2026-06-19 1 true 1250");
    }

    @Test
    @DisplayName("복사본은 원천을 적고 매 실행 원천 값으로 다시 맞춘다(관문 재적재가 원천에 채운 칸이 복사본에도 온다)")
    void gatedCopiesFollowTheirSource() {
        KrEarlierVintages gated = new KrEarlierVintages(jdbc, new FinancialWriter(jdbc, new NoOpIndicatorInvalidationPort()));
        annual(LocalDate.of(2026, 6, 19));
        filing("20260313001191", LocalDate.of(2026, 3, 13), SAME);
        filing("20260619000667", LocalDate.of(2026, 6, 19), SAME);
        assertThat(gated.insert(stockId).inserted()).isEqualTo(2);

        jdbc.update("UPDATE financial_statement SET discontinued_income = 7, discontinued_rule = 1 WHERE stock_id = ? AND is_latest", stockId);
        assertThat(gated.insert(stockId).inserted()).as("넣을 것은 없다").isZero();
        assertThat(jdbc.queryForList("""
                SELECT discontinued_income FROM financial_statement WHERE stock_id = ? AND published_at = DATE '2026-03-13'
                """, java.math.BigDecimal.class, stockId)).as("원천 값으로 다시 맞췄다").allSatisfy(v -> assertThat(v).isEqualByComparingTo("7"));
    }

    @Test
    @DisplayName("FY 판에 같은 날 4분기 판이 없으면 FY − 그날 보이던 1~3분기로 짓는다(잔액은 그 FY 판 · 뒤에 낸 정정 분기는 안 쓴다)")
    void gatedRestoresTheFourthQuarterOfEveryAnnualVintage() {
        KrEarlierVintages gated = new KrEarlierVintages(jdbc, new FinancialWriter(jdbc, new NoOpIndicatorInvalidationPort()));
        jdbc.update("""
                INSERT INTO financial_statement (stock_id, fiscal_year, fiscal_period, period_start, period_end, published_at,
                    revision_no, is_latest, report_code, currency_code, net_income, total_assets)
                VALUES (?, 2025, 'Q1', DATE '2025-01-01', DATE '2025-03-31', DATE '2025-05-15', 0, true, '11013', 'KRW', 1000, 1),
                       (?, 2025, 'Q2', DATE '2025-04-01', DATE '2025-06-30', DATE '2025-08-14', 0, true, '11012', 'KRW', 1000, 1),
                       (?, 2025, 'Q3', DATE '2025-07-01', DATE '2025-09-30', DATE '2025-11-14', 0, false, '11014', 'KRW', 1000, 1),
                       (?, 2025, 'Q3', DATE '2025-07-01', DATE '2025-09-30', DATE '2026-04-01', 1, true, '11014', 'KRW', 1100, 1),
                       (?, 2025, 'FY', DATE '2025-01-01', DATE '2025-12-31', DATE '2026-03-13', 0, false, '11011', 'KRW', 5000, 80),
                       (?, 2025, 'FY', DATE '2025-01-01', DATE '2025-12-31', DATE '2026-06-19', 1, true, '11011', 'KRW', 5200, 90),
                       (?, 2025, 'Q4', DATE '2025-10-01', DATE '2025-12-31', DATE '2026-06-19', 0, true, '11011', 'KRW', 2100, 90)
                """, stockId, stockId, stockId, stockId, stockId, stockId, stockId);

        // 소스 판은 지은 판보다 먼저 만들어졌다 — 복사본 원천 붙이기(같은 라벨의 더 늦은 판보다 뒤에 만들어진 행)가 지은 판을 안 잡아야 한다.
        jdbc.update("UPDATE financial_statement SET created_at = now() - interval '1 day' WHERE stock_id = ?", stockId);

        assertThat(gated.insert(stockId).inserted()).as("넣는 것은 이른 판 넣기만 센다").isZero();
        assertThat(gated.insert(stockId).inserted()).isZero();
        assertThat(gated.restoreFourthQuarters(stockId)).as("멱등").isZero();

        assertThat(vintages("Q4")).containsExactly("2026-03-13 0 false 2000", "2026-06-19 1 true 2100");
        assertThat(jdbc.queryForObject("""
                SELECT total_assets FROM financial_statement WHERE stock_id = ? AND fiscal_period = 'Q4' AND published_at = DATE '2026-03-13'
                """, java.math.BigDecimal.class, stockId)).as("잔액은 그 FY 판").isEqualByComparingTo("80");

        jdbc.update("UPDATE financial_statement SET net_income = 5050 WHERE stock_id = ? AND fiscal_period = 'FY' AND NOT is_latest", stockId);
        assertThat(gated.restoreFourthQuarters(stockId)).as("원천 FY 판이 바뀌면 다시 맞춘다").isOne();
        assertThat(vintages("Q4")).containsExactly("2026-03-13 0 false 2050", "2026-06-19 1 true 2100");

        jdbc.update("DELETE FROM financial_statement WHERE stock_id = ? AND fiscal_period = 'FY' AND NOT is_latest", stockId);
        assertThat(vintages("Q4")).as("원천 FY 판이 회수되면 함께 간다").containsExactly("2026-06-19 1 true 2100");
    }

    @Test
    @DisplayName("FY 판 날짜의 소스 4분기 이른 판은 빈 유량 칸만 같은 항등식으로 채운다(무효화는 안 한다 — 화해가 덮는다)")
    void gatedFillsTheEmptyFlowsOfASourceFourthQuarterVintage() {
        List<String> invalidations = new java.util.ArrayList<>();
        KrEarlierVintages gated = new KrEarlierVintages(jdbc, new FinancialWriter(jdbc,
                (stock, from, reason) -> invalidations.add(from.toString())));
        jdbc.update("""
                INSERT INTO financial_statement (stock_id, fiscal_year, fiscal_period, period_start, period_end, published_at,
                    revision_no, is_latest, report_code, currency_code, net_income, net_income_controlling)
                VALUES (?, 2025, 'Q1', DATE '2025-01-01', DATE '2025-03-31', DATE '2025-05-15', 0, true, '11013', 'KRW', 1000, 900),
                       (?, 2025, 'Q2', DATE '2025-04-01', DATE '2025-06-30', DATE '2025-08-14', 0, true, '11012', 'KRW', 1000, 900),
                       (?, 2025, 'Q3', DATE '2025-07-01', DATE '2025-09-30', DATE '2025-11-14', 0, true, '11014', 'KRW', 1000, 900),
                       (?, 2025, 'FY', DATE '2025-01-01', DATE '2025-12-31', DATE '2026-03-13', 0, false, '11011', 'KRW', 5000, 4500),
                       (?, 2025, 'FY', DATE '2025-01-01', DATE '2025-12-31', DATE '2026-06-19', 1, true, '11011', 'KRW', 5200, 4700),
                       (?, 2025, 'Q4', DATE '2025-10-01', DATE '2025-12-31', DATE '2026-03-13', 0, false, '11011', 'KRW', 2000, null),
                       (?, 2025, 'Q4', DATE '2025-10-01', DATE '2025-12-31', DATE '2026-06-19', 1, true, '11011', 'KRW', 2200, 2000)
                """, stockId, stockId, stockId, stockId, stockId, stockId, stockId);

        assertThat(gated.restoreFourthQuarters(stockId)).isOne();
        assertThat(jdbc.queryForList("""
                SELECT net_income || '/' || net_income_controlling FROM financial_statement
                WHERE stock_id = ? AND fiscal_period = 'Q4' ORDER BY published_at
                """, String.class, stockId)).as("값이 있는 칸은 그대로 · 빈 칸만").containsExactly("2000.00/1800.00", "2200.00/2000.00");
        assertThat(invalidations).as("화해(ADR-0166)가 같은 날 다시 쓴다 — 0행 구간을 안 만든다").isEmpty();
        assertThat(gated.restoreFourthQuarters(stockId)).as("멱등").isZero();
    }

    @Test
    @DisplayName("국내 관문 켬(K17): 1~3분기가 FY의 중단영업 규칙으로 안 읽혔으면 지은 4분기의 규칙은 비운다 — 꺼짐은 FY 규칙을 복사한다(대조군)")
    void fourthQuarterRuleNeedsAllFourInputs() {
        jdbc.update("""
                INSERT INTO financial_statement (stock_id, fiscal_year, fiscal_period, period_start, period_end, published_at,
                    revision_no, is_latest, report_code, currency_code, net_income, discontinued_income, discontinued_rule)
                VALUES (?, 2025, 'Q1', DATE '2025-01-01', DATE '2025-03-31', DATE '2025-05-15', 0, true, '11013', 'KRW', 1000, NULL, NULL),
                       (?, 2025, 'Q2', DATE '2025-04-01', DATE '2025-06-30', DATE '2025-08-14', 0, true, '11012', 'KRW', 1000, NULL, NULL),
                       (?, 2025, 'Q3', DATE '2025-07-01', DATE '2025-09-30', DATE '2025-11-14', 0, true, '11014', 'KRW', 1000, NULL, NULL),
                       (?, 2025, 'FY', DATE '2025-01-01', DATE '2025-12-31', DATE '2026-03-13', 0, false, '11011', 'KRW', 5000, 0, 1),
                       (?, 2025, 'FY', DATE '2025-01-01', DATE '2025-12-31', DATE '2026-06-19', 1, true, '11011', 'KRW', 5200, 0, 1),
                       (?, 2025, 'Q4', DATE '2025-10-01', DATE '2025-12-31', DATE '2026-06-19', 0, true, '11011', 'KRW', 2200, 0, 1)
                """, stockId, stockId, stockId, stockId, stockId, stockId);
        FinancialWriter writer = new FinancialWriter(jdbc, new NoOpIndicatorInvalidationPort());

        new KrEarlierVintages(jdbc, writer, false).restoreFourthQuarters(stockId);
        assertThat(restoredRule()).as("꺼짐 — 지금 동작(FY 규칙 복사)").isEqualByComparingTo("1");

        new KrEarlierVintages(jdbc, writer, true).restoreFourthQuarters(stockId);
        assertThat(restoredRule()).as("켬 — 1~3분기가 그 규칙으로 안 읽혔다").isNull();
    }

    private java.math.BigDecimal restoredRule() {
        return jdbc.queryForObject("""
                SELECT discontinued_rule FROM financial_statement WHERE stock_id = ? AND fiscal_period = 'Q4' AND published_at = DATE '2026-03-13'
                """, java.math.BigDecimal.class, stockId);
    }

    @Test
    @DisplayName("1~3분기가 다 안 보이면 4분기 판을 짓지 않는다")
    void doesNotRestoreWithoutTheGateOrTheQuarters() {
        jdbc.update("""
                INSERT INTO financial_statement (stock_id, fiscal_year, fiscal_period, period_start, period_end, published_at,
                    revision_no, is_latest, report_code, currency_code, net_income)
                VALUES (?, 2025, 'Q1', DATE '2025-01-01', DATE '2025-03-31', DATE '2025-05-15', 0, true, '11013', 'KRW', 1000),
                       (?, 2025, 'Q2', DATE '2025-04-01', DATE '2025-06-30', DATE '2025-08-14', 0, true, '11012', 'KRW', 1000),
                       (?, 2025, 'Q3', DATE '2025-07-01', DATE '2025-09-30', DATE '2026-04-01', 0, true, '11014', 'KRW', 1100),
                       (?, 2025, 'FY', DATE '2025-01-01', DATE '2025-12-31', DATE '2026-03-13', 0, false, '11011', 'KRW', 5000),
                       (?, 2025, 'FY', DATE '2025-01-01', DATE '2025-12-31', DATE '2026-06-19', 1, true, '11011', 'KRW', 5200),
                       (?, 2025, 'Q4', DATE '2025-10-01', DATE '2025-12-31', DATE '2026-06-19', 0, true, '11011', 'KRW', 2100)
                """, stockId, stockId, stockId, stockId, stockId, stockId);

        earlier.insert(stockId);
        assertThat(vintages("Q4")).containsExactly("2026-06-19 0 true 2100");
        KrEarlierVintages gated = new KrEarlierVintages(jdbc, new FinancialWriter(jdbc, new NoOpIndicatorInvalidationPort()));
        assertThat(gated.restoreFourthQuarters(stockId)).as("03-13엔 3분기가 아직 없다").isZero();
    }

    @Test
    @DisplayName("정정이 숫자를 바꿨으면 원본 날짜로 넣지 않는다 — 그 값은 원본 날에 공개되지 않았다")
    void keepsTheCorrectionDateWhenTheNumbersChanged() {
        annual(LocalDate.of(2026, 6, 19));
        filing("20260313001191", LocalDate.of(2026, 3, 13), CHANGED);
        filing("20260619000667", LocalDate.of(2026, 6, 19), SAME);

        assertThat(earlier.insert(stockId).inserted()).isZero();
        assertThat(vintages("FY")).containsExactly("2026-06-19 0 true 5000");
    }

    @Test
    @DisplayName("회사 확장 주당 사실(판독기 판 3)은 판 비교에 안 든다 — 이름표 문구만 다른 정정도 원본 날짜로 넣는다")
    void entityFactsDoNotDecideTheComparison() {
        annual(LocalDate.of(2026, 6, 19));
        filing("20260313001191", LocalDate.of(2026, 3, 13), withEntity("""
                ,{"s":"E","c":"udf_IS_1OfEarningsPerShareAbstract","b":"2025-01-01","e":"2025-12-31","k":"C","m":[],"v":"100","l":"보통주기본주당이익"}"""));
        filing("20260619000667", LocalDate.of(2026, 6, 19), withEntity("""
                ,{"s":"E","c":"udf_IS_1OfEarningsPerShareAbstract","b":"2025-01-01","e":"2025-12-31","k":"C","m":[],"v":"100","l":"보통주 기본주당이익"}"""));

        assertThat(earlier.insert(stockId).inserted()).isEqualTo(2);
    }

    @Test
    @DisplayName("재료가 없으면 판정하지 않는다 — 원본 재료를 아직 못 받은 보고서는 그대로다")
    void needsBothMaterials() {
        annual(LocalDate.of(2026, 6, 19));
        jdbc.update("""
                INSERT INTO kr_report_xbrl (rcept_no, stock_id, corp_code, detail_type, report_name, rcept_date, bsns_year, reprt_code)
                VALUES ('20260313001191', ?, '00688996', 'A001', '사업보고서 (2025.12)', DATE '2026-03-13', 2025, '11011')
                """, stockId);
        filing("20260619000667", LocalDate.of(2026, 6, 19), SAME);

        assertThat(earlier.insert(stockId).inserted()).isZero();
    }

    /** {@link #SAME} 끝에 사실 하나를 붙인다. */
    private static String withEntity(String fact) {
        return SAME.substring(0, SAME.lastIndexOf(']')) + fact.strip() + "]";
    }

    private void annual(LocalDate publishedAt) {
        jdbc.update("""
                INSERT INTO financial_statement (stock_id, fiscal_year, fiscal_period, period_start, period_end, published_at,
                    revision_no, is_latest, report_code, currency_code, net_income)
                VALUES (?, 2025, 'FY', DATE '2025-01-01', DATE '2025-12-31', ?, 0, true, '11011', 'KRW', 5000),
                       (?, 2025, 'Q4', DATE '2025-10-01', DATE '2025-12-31', ?, 0, true, '11011', 'KRW', 1250)
                """, stockId, publishedAt, stockId, publishedAt);
    }

    private void filing(String rceptNo, LocalDate rceptDate, String facts) {
        jdbc.update("""
                INSERT INTO kr_report_xbrl (rcept_no, stock_id, corp_code, detail_type, report_name, rcept_date, bsns_year, reprt_code,
                    xbrl_status, reader_version, facts, fetched_at)
                VALUES (?, ?, '00688996', 'A001', '사업보고서 (2025.12)', ?, 2025, '11011', 'OK', 1, CAST(? AS jsonb), now())
                """, rceptNo, stockId, rceptDate, facts);
    }

    private List<String> vintages(String period) {
        return jdbc.query("""
                SELECT published_at, revision_no, is_latest, net_income FROM financial_statement
                WHERE stock_id = ? AND fiscal_period = ? ORDER BY published_at
                """, (rs, i) -> rs.getObject("published_at", LocalDate.class) + " " + rs.getInt("revision_no") + " "
                + rs.getBoolean("is_latest") + " " + rs.getBigDecimal("net_income").stripTrailingZeros().toPlainString(),
                stockId, period);
    }
}
