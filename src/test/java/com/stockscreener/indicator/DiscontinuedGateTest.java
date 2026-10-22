package com.stockscreener.indicator;

import com.stockscreener.indicator.IndicatorDataReader.FinancialVisibility;
import com.stockscreener.pipeline.collector.CorporateActionLedger;
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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 판독기가 중단영업 두 컬럼을 <b>그 행 시장의 관문</b>으로만 읽는지 본다(F2b · ADR-0184).
 *
 * <p>한쪽 관문을 연 상태에서 다른 시장 행이 이 축을 타면 그 시장의 PER · EPS가 관문 없이 바뀐다 — 「us 켬 · kr 끔에서 국내 행
 * 무변화」(탐색 {@code GATE-SETTINGS-PLAN.md} v3)가 이 시험이다. 두 관문을 다 끄면 질의 자체가 두 컬럼을 안 싣는다(운영 DB에는
 * 아직 컬럼이 없다).
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
@Import(TestDatabaseGuard.class)
class DiscontinuedGateTest {

    private static final LocalDate AS_OF = LocalDate.of(2026, 8, 30);

    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private CorporateActionLedger corporateActions;
    @Autowired
    private ShareClassStore shareClasses;

    @Test
    @DisplayName("미국 관문만 켜면 미국 행만 중단영업을 싣고 국내 행은 그대로다 — 반대도 같다")
    void eachMarketReadsOnlyBehindItsOwnGate() {
        long us = insertStock("US", "ZZD1", "미국 중단영업");
        long kr = insertStock("KOSPI", "999981", "국내 중단영업");
        insertAnnual(us, "USD");
        insertAnnual(kr, "KRW");

        IndicatorDataReader usOnly = reader(true, false);
        IndicatorDataReader krOnly = reader(false, true);
        IndicatorDataReader off = reader(false, false);

        assertThat(discontinued(usOnly, us)).isEqualByComparingTo("120");
        assertThat(discontinued(usOnly, kr)).as("국내 관문이 꺼졌다").isNull();
        assertThat(discontinued(krOnly, kr)).isEqualByComparingTo("120");
        assertThat(discontinued(krOnly, us)).as("미국 관문이 꺼졌다").isNull();
        assertThat(discontinued(off, us)).isNull();
        assertThat(discontinued(off, kr)).isNull();
        assertThat(usOnly.readAnnualFigures(us, AS_OF, 5, FinancialVisibility.KNOWN_ON).get(0)
                .discontinuedIncomeControlling()).as("시점 경로도 같은 컬럼을 싣는다").isEqualByComparingTo("100");
    }

    private IndicatorDataReader reader(boolean usGate, boolean krGate) {
        return new IndicatorDataReader(jdbcTemplate, corporateActions, shareClasses, usGate, krGate);
    }

    private static BigDecimal discontinued(IndicatorDataReader reader, long stockId) {
        return reader.readAnnualFigures(stockId, AS_OF, 5, FinancialVisibility.LATEST).get(0).discontinuedIncome();
    }

    private long insertStock(String marketCode, String ticker, String name) {
        return jdbcTemplate.queryForObject("""
                INSERT INTO stock (market_code, ticker, name) VALUES (?, ?, ?) RETURNING id
                """, Long.class, marketCode, ticker, name);
    }

    private void insertAnnual(long stockId, String currencyCode) {
        jdbcTemplate.update("""
                INSERT INTO financial_statement
                    (stock_id, fiscal_year, fiscal_period, period_start, period_end,
                     published_at, revision_no, is_latest, currency_code, net_income, net_income_controlling,
                     discontinued_income, discontinued_income_controlling)
                VALUES (?, 2024, 'FY', '2024-01-01', '2024-12-31', '2025-03-31', 0, true, ?, 1000, 900, 120, 100)
                """, stockId, currencyCode);
    }
}
