package com.stockscreener.pipeline.collector;

import com.stockscreener.pipeline.datasource.StoredShareCounts;
import com.stockscreener.pipeline.datasource.us.DepositaryCoverStore;
import com.stockscreener.pipeline.datasource.us.shareclass.ShareClassStore;
import com.stockscreener.support.TestDatabaseGuard;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 판마다 예탁 비율(ADR-0183)의 기저 판정 — 관문 마이그레이션 V70_1(시험 프로파일만 만든다). 꼴은 드라이런 1의 실제 모양을 줄인 것이다.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
@Import(TestDatabaseGuard.class)
class DepositaryBasisDeriveTest {

    private static final long CIK = 515151;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ShareClassStore shareClasses;

    @Autowired
    private DepositaryCoverStore depositary;

    private ShareBasisDeriver gateOn() {
        return new ShareBasisDeriver(jdbcTemplate, shareClasses, depositary, true);
    }

    @Test
    @DisplayName("예탁증권 줄 행은 공시마다 정한 k로 배수 1/k · DEPOSITARY — 표지 없는 6-K는 앞 표지를 잇고 순위 주식수가 ADS 단위가 된다")
    void writesOneOverKPerFiling() {
        long stockId = seed("DEPX", "American Depositary Shares, each representing 3 ordinary shares");

        gateOn().derive("US");

        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                SELECT report_code, share_basis_status, share_basis_multiple FROM financial_statement
                WHERE stock_id = ? ORDER BY published_at""", stockId);
        assertThat(rows).hasSize(3);
        for (Map<String, Object> r : rows) {
            assertThat(r.get("share_basis_status")).as((String) r.get("report_code")).isEqualTo("DEPOSITARY");
            assertThat(((BigDecimal) r.get("share_basis_multiple")).doubleValue()).isCloseTo(1.0 / 3, org.assertj.core.data.Offset.offset(1e-9));
        }
        assertThat(jdbcTemplate.queryForList("SELECT how FROM sec_depositary_ratio WHERE stock_id = ? ORDER BY filed",
                String.class, stockId)).containsExactly("OWN", "CARRY_FORWARD", "OWN");

        StoredShareCounts.ShareCount count = new JdbcStoredShareCounts(jdbcTemplate, shareClasses, false)
                .activeByMarket("US", LocalDate.of(2024, 1, 1), LocalDate.of(2025, 7, 1)).stream()
                .filter(c -> c.ticker().equals("DEPX")).findFirst().orElseThrow();
        assertThat(count.shares().doubleValue()).as("보통주 621 ÷ 3 = ADS 207").isCloseTo(207, org.assertj.core.data.Offset.offset(1e-6));
    }

    @Test
    @DisplayName("정한 k는 다시 안 짓는다 — 뒤에 원장 사건이 들어와도 이어받은 판이 소급해 미정으로 안 뒤집힌다")
    void decidedRatiosAreFrozen() {
        long stockId = seed("DEPY", "American Depositary Shares, each representing 3 ordinary shares");
        gateOn().derive("US");
        jdbcTemplate.update("""
                INSERT INTO corporate_action (stock_id, effective_date, type, ratio, detection_source, status)
                VALUES (?, ?, 'SPLIT', 2, 'SOURCE_DISCLOSURE', 'APPLIED')
                """, stockId, LocalDate.of(2024, 8, 1));

        gateOn().derive("US");

        assertThat(jdbcTemplate.queryForObject("""
                SELECT share_basis_status FROM financial_statement WHERE stock_id = ? AND report_code = '6-K:0000515151-24-000002'
                """, String.class, stockId)).isEqualTo("DEPOSITARY");
    }

    @Test
    @DisplayName("표지 줄이 전부 보통주인 발행사(TRI 꼴 40-F)는 손대지 않는다")
    void ordinaryLineIssuerIsUntouched() {
        long stockId = seed("ORDX", "Common Shares");

        gateOn().derive("US");

        assertThat(jdbcTemplate.queryForList("SELECT share_basis_status FROM financial_statement WHERE stock_id = ?",
                String.class, stockId)).containsOnlyNulls();
    }

    @Test
    @DisplayName("클래스 축 계수는 보통주 계수다 — 비율 변경 역병합(UXIN 꼴 ×0.1 · k 3 → 30)은 1 · 기초 분할(HDB 꼴 ×2 · k 그대로)은 2")
    void classAxisSplitsAreOrdinaryUnits() {
        long stockId = jdbcTemplate.queryForObject("""
                INSERT INTO stock (market_code, ticker, name, sec_cik) VALUES ('US', 'UXNX', 'UXNX ADS', 777) RETURNING id
                """, Long.class);
        jdbcTemplate.update("""
                INSERT INTO corporate_action (stock_id, effective_date, type, ratio, detection_source, status) VALUES
                (?, '2022-10-28', 'REVERSE_SPLIT', 0.1, 'SOURCE_DISCLOSURE', 'APPLIED'),
                (?, '2025-09-08', 'SPLIT', 2, 'SOURCE_DISCLOSURE', 'APPLIED')
                """, stockId, stockId);
        depositary.saveRatio(stockId, "a", LocalDate.of(2022, 7, 1), 3.0, com.stockscreener.pipeline.datasource.us.listing.DepositaryRatioJudge.How.OWN);
        depositary.saveRatio(stockId, "b", LocalDate.of(2023, 7, 1), 30.0, com.stockscreener.pipeline.datasource.us.listing.DepositaryRatioJudge.How.OWN);
        depositary.saveRatio(stockId, "c", LocalDate.of(2026, 3, 1), 30.0, com.stockscreener.pipeline.datasource.us.listing.DepositaryRatioJudge.How.OWN);

        assertThat(shareClasses.splits(stockId)).extracting(s -> s.ratio()).containsExactly(1.0, 2.0);
        assertThat(shareClasses.adsSplits(stockId)).extracting(s -> s.ratio()).containsExactly(0.1, 2.0);
    }

    private long seed(String ticker, String title) {
        long stockId = jdbcTemplate.queryForObject("""
                INSERT INTO stock (market_code, ticker, name, sec_cik) VALUES ('US', ?, ?, ?) RETURNING id
                """, Long.class, ticker, ticker + " Holdings", CIK + ticker.charAt(3));
        long cik = CIK + ticker.charAt(3);
        row(stockId, "20-F:0000515151-24-000001", 2023, "FY", LocalDate.of(2023, 12, 31), LocalDate.of(2024, 4, 1), "600");
        row(stockId, "6-K:0000515151-24-000002", 2024, "Q2", LocalDate.of(2024, 6, 30), LocalDate.of(2024, 9, 1), "610");
        row(stockId, "20-F:0000515151-25-000001", 2024, "FY", LocalDate.of(2024, 12, 31), LocalDate.of(2025, 4, 1), "621");
        cover(cik, "0000515151-24-000001", LocalDate.of(2024, 4, 1), ticker, title);
        cover(cik, "0000515151-25-000001", LocalDate.of(2025, 4, 1), ticker, title);
        return stockId;
    }

    private void row(long stockId, String reportCode, int year, String period, LocalDate periodEnd, LocalDate publishedAt,
                     String shares) {
        jdbcTemplate.update("""
                INSERT INTO financial_statement
                    (stock_id, fiscal_year, fiscal_period, period_start, period_end, published_at, revision_no, is_latest,
                     currency_code, shares_outstanding, report_code)
                VALUES (?, ?, ?, ?, ?, ?, 0, true, 'USD', ?, ?)
                """, stockId, year, period, periodEnd.minusMonths(3), periodEnd, publishedAt, new BigDecimal(shares), reportCode);
    }

    private void cover(long cik, String accession, LocalDate filed, String symbol, String title) {
        depositary.saveCover(cik, accession, "20-F", filed, "OK", """
                {"documentType":"20-F","durations":{},"cover":{"Security12bTitle":[["-","%s"]],"TradingSymbol":[["-","%s"]]}}
                """.formatted(title, symbol), null, null);
    }
}
