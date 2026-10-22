package com.stockscreener.pipeline.collector;

import com.stockscreener.pipeline.datasource.GovernanceDerivation;
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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 배당 총액의 주식수(관문 켬 · ADR-0183) — 총액 = Alpaca의 ADS당 배당 × 주식수라 예탁증권 줄은 1/k를 곱한다. 20% 게이트는 그 전에
 * 보통주끼리 맞댄다.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
@Import(TestDatabaseGuard.class)
class DepositaryDividendSharesTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private GovernanceFactDeriver deriver;

    @Test
    @DisplayName("DEPOSITARY 판이면 보통주 300(EPS 함의 300과 게이트 통과)에 1/3을 곱해 ADS 100 · 미정 판이면 총액을 안 낸다")
    void depositarySharesAreInAdsUnits() {
        long deposited = stock("DIVA", "DEPOSITARY", new BigDecimal("0.3333333333"));
        long unresolved = stock("DIVB", "UNRESOLVED", null);

        BigDecimal a = only(deriver.withClassAxisShares(deposited, period()));
        BigDecimal b = only(deriver.withClassAxisShares(unresolved, period()));

        assertThat(a.doubleValue()).isCloseTo(100, org.assertj.core.data.Offset.offset(1e-6));
        assertThat(b).isNull();
    }

    private static List<GovernanceDerivation.AnnualPeriod> period() {
        return List.of(new GovernanceDerivation.AnnualPeriod(2025, LocalDate.of(2025, 1, 1), LocalDate.of(2025, 12, 31),
                LocalDate.of(2026, 3, 1), new BigDecimal("300")));
    }

    private static BigDecimal only(List<GovernanceDerivation.AnnualPeriod> out) {
        assertThat(out).hasSize(1);
        return out.get(0).sharesOutstanding();
    }

    private long stock(String ticker, String status, BigDecimal multiple) {
        long id = jdbcTemplate.queryForObject("""
                INSERT INTO stock (market_code, ticker, name) VALUES ('US', ?, ?) RETURNING id
                """, Long.class, ticker, ticker + " ADS");
        jdbcTemplate.update("""
                INSERT INTO financial_statement
                    (stock_id, fiscal_year, fiscal_period, period_start, period_end, published_at, revision_no, is_latest,
                     currency_code, shares_outstanding, net_income, eps, report_code, share_basis_status, share_basis_multiple)
                VALUES (?, 2025, 'FY', '2025-01-01', '2025-12-31', '2026-03-01', 0, true, 'USD', 300, 600, 2, '20-F:X-1', ?, ?)
                """, id, status, multiple);
        return id;
    }
}
