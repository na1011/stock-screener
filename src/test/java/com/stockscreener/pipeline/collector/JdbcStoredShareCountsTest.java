package com.stockscreener.pipeline.collector;

import com.stockscreener.pipeline.datasource.StoredShareCounts;
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
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 유니버스가 쓰는 주식수의 계약을 잰다.
 *
 * <p><b>이 클래스가 지키는 것 셋</b>이고 셋 다 「값이 나오는」 방향으로 틀린다 —
 * 결측이 아니라 <b>그럴듯한 숫자</b>가 나와 화면에서 구별되지 않는다.
 * <ol>
 *   <li>{@code is_latest}는 「최신 기」가 아니라 「그 기의 최신 개정본」이다.</li>
 *   <li>낡은 기의 주식수를 오늘 종가에 곱하지 않는다.</li>
 *   <li>통화 판정이 {@code IndicatorDataReader.CURRENCY_GATE}와 갈리지 않는다.</li>
 * </ol>
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
@Import(TestDatabaseGuard.class)
class JdbcStoredShareCountsTest {

    private static final LocalDate OLDEST = LocalDate.of(2024, 8, 31);

    @Autowired
    private StoredShareCounts shareCounts;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("IMPORTANT — is_latest가 아니라 최신 기의 주식수를 낸다")
    void picksTheLatestPeriodNotTheLargestRevision() {
        long stockId = insertStock("US", "AAPLT", "Apple Test Inc.");
        // 실측 모양: AAPL의 is_latest 행이 38개고 옛 기의 주식수가 최신기보다 약 1.165배 크다.
        insertQuarter(stockId, 2024, "Q1", LocalDate.of(2024, 12, 31), "USD", "17001802000");
        insertQuarter(stockId, 2026, "Q3", LocalDate.of(2026, 6, 27), "USD", "14594180000");

        assertThat(find("AAPLT").orElseThrow().shares())
                .as("max()로 뽑으면 옛 기의 값이 이기고 시총이 약 1.165배 크게 나온다")
                .isEqualByComparingTo(new BigDecimal("14594180000"));
    }

    @Test
    @DisplayName("같은 기에 개정본이 여럿이면 나중 판을 쓴다")
    void picksTheLatestRevisionWithinAPeriod() {
        long stockId = insertStock("US", "REVT", "Revised Test Inc.");
        insertRevision(stockId, 2026, "Q2", LocalDate.of(2026, 3, 28), "USD", "100", 0, false);
        insertRevision(stockId, 2026, "Q2", LocalDate.of(2026, 3, 28), "USD", "200", 1, true);

        assertThat(find("REVT").orElseThrow().shares()).isEqualByComparingTo(new BigDecimal("200"));
    }

    @Test
    @DisplayName("경계보다 이른 기만 있으면 내지 않는다 — 없으면 십수 년 전 값이 오늘 종가에 곱해진다")
    void dropsStaleShareCounts() {
        long stockId = insertStock("US", "STALE", "Stale Inc.");
        // sec-edgar-financial.md 실측: V가 2010-01-27, BRK가 2011-04-29로 멈춰 있었다.
        insertQuarter(stockId, 2011, "Q1", LocalDate.of(2011, 4, 29), "USD", "941481");

        assertThat(find("STALE")).isEmpty();
    }

    @Test
    @DisplayName("주식수가 NULL이거나 0이면 내지 않는다 — 0은 「작은 회사」와 구별되지 않는다")
    void dropsMissingAndZeroShareCounts() {
        long nullShares = insertStock("US", "NULS", "Null Shares Inc.");
        insertQuarter(nullShares, 2026, "Q2", LocalDate.of(2026, 3, 28), "USD", null);
        long zeroShares = insertStock("US", "ZERO", "Zero Shares Inc.");
        insertQuarter(zeroShares, 2026, "Q2", LocalDate.of(2026, 3, 28), "USD", "0");

        assertThat(find("NULS")).isEmpty();
        assertThat(find("ZERO")).isEmpty();
    }

    @Test
    @DisplayName("미국 시장의 USD는 정상이다 — 판정 축은 「USD인가」가 아니라 「시장 통화와 같은가」다")
    void usdIsNormalInUsMarket() {
        long stockId = insertStock("US", "USDOK", "Usd Ok Inc.");
        insertQuarter(stockId, 2026, "Q2", LocalDate.of(2026, 3, 28), "USD", "100");

        assertThat(find("USDOK").orElseThrow().currencyMismatched())
                .as("여기가 참이 되면 미국 유니버스가 통째로 비고 그 0건은 「후보 없음」과 구별되지 않는다")
                .isFalse();
    }

    /**
     * <b>MUST: 이 술어를 {@code IndicatorDataReader.CURRENCY_GATE}와 같게 유지한다.</b>
     * 그쪽이 {@code is_latest}를 보지 않으므로 여기도 보지 않는다 — 갈리면 이 종목이
     * <b>유니버스에는 담기는데 재무 지표는 한 행도 안 나는</b> 상태가 된다.
     * 실측으로 미국에서 그렇게 새는 것이 134종목이다(2026-08-31).
     */
    @Test
    @DisplayName("개정 전 판에만 통화가 다르면 여기서도 걸린다 — CURRENCY_GATE와 안 갈린다")
    void mismatchOnASupersededRevisionIsSeenHereToo() {
        long stockId = insertStock("US", "SUPER", "Superseded Inc.");
        insertRevision(stockId, 2026, "Q2", LocalDate.of(2026, 3, 28), "TWD", "100", 0, false);
        insertRevision(stockId, 2026, "Q2", LocalDate.of(2026, 3, 28), "USD", "100", 1, true);

        assertThat(find("SUPER").orElseThrow().currencyMismatched()).isTrue();
    }

    @Test
    @DisplayName("SIC를 함께 낸다 — REIT·SPAC 배제가 이름이 아니라 코드로 판정된다")
    void carriesSicCode() {
        long stockId = insertStock("US", "REITT", "Reit Test Inc.");
        jdbcTemplate.update("UPDATE stock SET sic_code = '6798' WHERE id = ?", stockId);
        insertQuarter(stockId, 2026, "Q2", LocalDate.of(2026, 3, 28), "USD", "100");

        assertThat(find("REITT").orElseThrow().sicCode()).isEqualTo("6798");
    }

    @Test
    @DisplayName("다른 시장의 종목은 내지 않는다")
    void scopesToTheRequestedMarket() {
        long stockId = insertStock("KOSPI", "999991", "국내 종목");
        insertQuarter(stockId, 2026, "Q2", LocalDate.of(2026, 3, 28), "KRW", "100");

        assertThat(find("999991")).isEmpty();
    }

    private Optional<StoredShareCounts.ShareCount> find(String ticker) {
        List<StoredShareCounts.ShareCount> counts =
                shareCounts.activeByMarket("US", OLDEST);
        return counts.stream().filter(c -> c.ticker().equals(ticker)).findFirst();
    }

    private long insertStock(String marketCode, String ticker, String name) {
        return jdbcTemplate.queryForObject("""
                INSERT INTO stock (market_code, ticker, name) VALUES (?, ?, ?) RETURNING id
                """, Long.class, marketCode, ticker, name);
    }

    private void insertQuarter(long stockId, int year, String period, LocalDate periodEnd,
                               String currencyCode, String shares) {
        insertRevision(stockId, year, period, periodEnd, currencyCode, shares, 0, true);
    }

    private void insertRevision(long stockId, int year, String period, LocalDate periodEnd,
                                String currencyCode, String shares, int revision, boolean latest) {
        jdbcTemplate.update("""
                INSERT INTO financial_statement
                    (stock_id, fiscal_year, fiscal_period, period_start, period_end,
                     published_at, revision_no, is_latest, currency_code, shares_outstanding)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, stockId, year, period, periodEnd.minusMonths(3), periodEnd,
                periodEnd.plusMonths(2), revision, latest, currencyCode,
                shares == null ? null : new BigDecimal(shares));
    }
}
