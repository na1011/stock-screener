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

        assertThat(earlier.insert(stockId)).isEqualTo(2);
        assertThat(earlier.insert(stockId)).as("멱등").isZero();

        assertThat(vintages("FY")).containsExactly("2026-03-13 0 false 5000", "2026-06-19 1 true 5000");
        assertThat(vintages("Q4")).as("같은 보고서의 유도 Q4도 함께").containsExactly("2026-03-13 0 false 1250",
                "2026-06-19 1 true 1250");
    }

    @Test
    @DisplayName("정정이 숫자를 바꿨으면 원본 날짜로 넣지 않는다 — 그 값은 원본 날에 공개되지 않았다")
    void keepsTheCorrectionDateWhenTheNumbersChanged() {
        annual(LocalDate.of(2026, 6, 19));
        filing("20260313001191", LocalDate.of(2026, 3, 13), CHANGED);
        filing("20260619000667", LocalDate.of(2026, 6, 19), SAME);

        assertThat(earlier.insert(stockId)).isZero();
        assertThat(vintages("FY")).containsExactly("2026-06-19 0 true 5000");
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

        assertThat(earlier.insert(stockId)).isZero();
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
