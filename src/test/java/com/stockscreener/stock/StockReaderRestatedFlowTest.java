package com.stockscreener.stock;

import com.stockscreener.pipeline.collector.CorporateActionLedger;
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
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 화면 재무 표의 기저 대체 칸(국내 관문 켬 · ADR-0200) — FY의 새 기저 값(restated)이 있으면 지표와 같은 값을 그리고 전년 동기 대비를 막지 않는다 ·
 * 새 값이 없는 낡은 칸은 원값을 그리되 막는다(반증 밖 렌즈 · 2026-11-01: 화면만 옛 값을 그리고 막고 있었다).
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
@Import(TestDatabaseGuard.class)
class StockReaderRestatedFlowTest {

    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private CorporateActionLedger corporateActions;
    @Autowired
    private StockAliasIndex aliasIndex;

    @Test
    @DisplayName("새 기저 값이 있는 FY 칸은 그 값을 그리고 안 막는다 · 새 값 없는 낡은 칸은 원값 · 막는다")
    void restatedFiscalYearIsDrawnAndNotBlocked() {
        long stockId = jdbc.queryForObject("INSERT INTO stock (market_code, ticker, name) VALUES ('KOSPI', '900902', 'x') RETURNING id",
                Long.class);
        jdbc.update("""
                INSERT INTO financial_statement (stock_id, fiscal_year, fiscal_period, period_start, period_end, published_at, revision_no,
                    is_latest, report_code, currency_code, revenue, operating_income, flow_superseded)
                VALUES (?, 2024, 'FY', DATE '2024-01-01', DATE '2024-12-31', DATE '2025-03-14', 0, true, '11011', 'KRW', 1000, 100,
                        CAST(? AS jsonb))
                """, stockId, """
                {"revenue": {"since": "2026-03-13", "restated": [["2026-03-13", "900"]]}, "operating_income": "2026-03-13"}""");
        StockReader reader = new StockReader(jdbc, corporateActions, aliasIndex);

        List<StockReader.FinancialRow> rows = reader.financialsOf(stockId, 4);

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).revenue()).isEqualByComparingTo(new BigDecimal("900"));
        assertThat(rows.get(0).operatingIncome()).as("새 값이 없으면 원값").isEqualByComparingTo(new BigDecimal("100"));
        assertThat(rows.get(0).staleFlows()).containsExactly("operating_income");
    }
}
