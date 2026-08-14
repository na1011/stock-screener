package com.stockscreener.pipeline.batch;

import com.stockscreener.pipeline.datasource.RawFinancialReport;
import com.stockscreener.pipeline.support.FakeFinancialDataSource;
import com.stockscreener.support.TestDatabaseGuard;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 재무 결손 보정 잡을 검증한다(ADR-0040).
 *
 * <p>고정하려는 것이 둘이다. <b>정말 채우는가</b>(APPEND_ONLY로 부르면 아무 일도 일어나지
 * 않으므로 잡이 통째로 무의미해진다)와 <b>대상 0건으로 조용히 성공하지 않는가</b>이다.
 * 후자는 이 잡이 고치려는 결함과 정확히 같은 모양이라 특히 위험하다.
 *
 * <p>IMPORTANT — {@code @Transactional}을 쓰지 않는다. 종목 단위 격리가 {@code REQUIRES_NEW}로
 * 커밋된 트랜잭션을 요구한다(ADR-0007). 대신 {@code @AfterEach}로 직접 지운다.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import({TestDatabaseGuard.class, FinancialRefillJobTest.FakeFinancialConfig.class})
class FinancialRefillJobTest {

    private static final LocalDate AS_OF = LocalDate.of(2026, 8, 10);
    private static final String UNIVERSE = "KOSPI_TOP200";

    @Autowired
    private FinancialRefillJob job;

    @Autowired
    private FakeFinancialDataSource fakeFinancialSource;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private long memberId;
    private long outsiderId;

    @BeforeEach
    void seed() {
        cleanUp();
        memberId = insertStock("005930", "삼성전자");
        outsiderId = insertStock("035420", "NAVER");
        // 편입은 하나뿐이다. 다른 하나는 "활성이지만 편입되지 않은 종목"이며 대상에서 빠져야 한다.
        jdbcTemplate.update("""
                INSERT INTO universe_membership (universe_code, stock_id, effective_from)
                VALUES (?, ?, ?)
                """, UNIVERSE, memberId, AS_OF.minusYears(1));
        fakeFinancialSource.fetchedStockIds.clear();
    }

    @AfterEach
    void cleanUp() {
        jdbcTemplate.update("DELETE FROM batch_execution_failure");
        jdbcTemplate.update("DELETE FROM batch_execution");
        // MUST: financial_statement를 stock보다 먼저 지운다. 이 잡이 만드는 행이 여기 남으면
        // DELETE FROM stock이 FK 위반으로 실패하고, 정리가 중단되면 종목이 커밋된 채 남아
        // KOSPI/005930을 넣는 다른 테스트가 전부 깨진다 — 증상이 이 테스트와 무관해 보인다.
        // 같은 사고가 indicator_value로 이미 한 번 났다(.claude/rules/test.md).
        jdbcTemplate.update("DELETE FROM financial_statement");
        jdbcTemplate.update("DELETE FROM universe_membership");
        jdbcTemplate.update("DELETE FROM stock WHERE ticker IN ('005930', '035420')");
    }

    @Test
    @DisplayName("이미 저장된 행의 NULL 컬럼을 채운다 — APPEND_ONLY로 부르면 아무 일도 일어나지 않는다")
    void fillsNullColumnsOfStoredRow() {
        insertReportWithoutBalanceDetail(memberId);
        fakeFinancialSource.withReports(memberId, List.of(report()));

        BatchStatus status = job.run(AS_OF, BatchExecutionRecorder.TriggeredBy.MANUAL, 3, List.of());

        assertThat(status).isEqualTo(BatchStatus.SUCCESS);
        assertThat(currentAssetsOf(memberId)).isEqualByComparingTo("9000");
        // 채운 것이지 쌓은 것이 아니다.
        assertThat(rowCountOf(memberId)).isEqualTo(1);
    }

    @Test
    @DisplayName("편입 중인 활성 종목만 대상으로 삼는다")
    void targetsOnlyActiveUniverseMembers() {
        job.run(AS_OF, BatchExecutionRecorder.TriggeredBy.MANUAL, 3, List.of());

        assertThat(fakeFinancialSource.fetchedStockIds).containsExactly(memberId);
        assertThat(fakeFinancialSource.fetchedStockIds).doesNotContain(outsiderId);
    }

    @Test
    @DisplayName("티커가 하나도 매칭되지 않으면 조용히 성공하지 않고 실패한다")
    void failsLoudlyWhenNoTickerMatches() {
        assertThatThrownBy(() -> job.run(
                AS_OF, BatchExecutionRecorder.TriggeredBy.MANUAL, 3, List.of("999999")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("999999");

        assertThat(fakeFinancialSource.fetchedStockIds).isEmpty();
    }

    @Test
    @DisplayName("티커를 주면 편입 종목과의 교집합만 처리한다")
    void narrowsToIntersectionOfGivenTickers() {
        // 035420은 활성이지만 편입되어 있지 않다 — 하나만 남아야 한다.
        job.run(AS_OF, BatchExecutionRecorder.TriggeredBy.MANUAL, 3,
                List.of("005930", "035420"));

        assertThat(fakeFinancialSource.fetchedStockIds).containsExactly(memberId);
    }

    private long insertStock(String ticker, String name) {
        return jdbcTemplate.queryForObject("""
                INSERT INTO stock (market_code, ticker, name) VALUES ('KOSPI', ?, ?) RETURNING id
                """, Long.class, ticker, name);
    }

    /** V16 이전에 저장된 행을 재현한다 — 유동자산 자리가 비어 있다. */
    private void insertReportWithoutBalanceDetail(long stockId) {
        jdbcTemplate.update("""
                INSERT INTO financial_statement
                    (stock_id, fiscal_year, fiscal_period, period_start, period_end, published_at,
                     revision_no, is_latest, report_code, currency_code,
                     revenue, operating_income, net_income, total_assets, total_equity,
                     total_liabilities)
                VALUES (?, 2025, 'FY', '2025-01-01', '2025-12-31', '2026-03-15', 0, true,
                        '11011', 'KRW', 5000, 1200, 1000, 20000, 12000, 8000)
                """, stockId);
    }

    /** 위 행과 같은 공시일이며 유동자산이 들어 있다. */
    private static RawFinancialReport report() {
        return new RawFinancialReport(
                2025, "FY",
                LocalDate.of(2025, 1, 1), LocalDate.of(2025, 12, 31),
                LocalDate.of(2026, 3, 15),
                "11011", "KRW",
                new BigDecimal("5000"), new BigDecimal("1200"), new BigDecimal("1000"),
                new BigDecimal("20000"), new BigDecimal("12000"), new BigDecimal("8000"),
                new BigDecimal("9000"), new BigDecimal("4000"), new BigDecimal("6000"),
                null, null, null, null, null, null);
    }

    private BigDecimal currentAssetsOf(long stockId) {
        return jdbcTemplate.queryForObject(
                "SELECT current_assets FROM financial_statement WHERE stock_id = ? AND is_latest",
                BigDecimal.class, stockId);
    }

    private int rowCountOf(long stockId) {
        return jdbcTemplate.queryForObject(
                "SELECT count(*) FROM financial_statement WHERE stock_id = ?", Integer.class, stockId);
    }

    /**
     * 재무 소스를 Fake로 등록한다.
     *
     * <p>실제 DART 어댑터는 {@code @ConditionalOnApiKey}라 테스트에서 등록되지 않으므로
     * 같은 시장에 두 소스가 생기는 충돌은 없다.
     */
    @TestConfiguration
    static class FakeFinancialConfig {

        @Bean
        FakeFinancialDataSource fakeFinancialDataSource() {
            return new FakeFinancialDataSource("KOSPI", "KOSDAQ");
        }
    }
}
