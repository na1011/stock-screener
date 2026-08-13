package com.stockscreener.pipeline.batch;

import com.stockscreener.pipeline.datasource.RawFinancialReport;
import com.stockscreener.pipeline.datasource.StatementPeriod;
import com.stockscreener.pipeline.support.FakeFullStatementDataSource;
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
 * 전체 재무제표 결손 보정 잡을 검증한다.
 *
 * <p>고정하려는 것이 셋이다. <b>영업활동현금흐름이 실제로 채워지는가</b>(안 채워지면 이 phase
 * 전체가 무의미하다), <b>분기를 함부로 켜지 않는가</b>(전 종목 분기는 얻는 것 없이 호출이
 * 4배다), <b>무엇을 했는지가 실행 기록에 남는가</b>(리미터의 일일 카운터는 인메모리라
 * 프로세스가 끝나면 사라진다).
 *
 * <p>IMPORTANT — {@code @Transactional}을 쓰지 않는다. 종목 단위 격리가 {@code REQUIRES_NEW}로
 * 커밋된 트랜잭션을 요구한다(ADR-0007). 대신 {@code @AfterEach}로 직접 지운다.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import({TestDatabaseGuard.class, FullStatementRefillJobTest.FakeFullStatementConfig.class})
class FullStatementRefillJobTest {

    private static final LocalDate AS_OF = LocalDate.of(2026, 8, 13);
    private static final String UNIVERSE = "KOSPI_TOP200";

    @Autowired
    private FullStatementRefillJob job;

    @Autowired
    private FakeFullStatementDataSource fakeSource;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private long memberId;
    private long outsiderId;

    @BeforeEach
    void seed() {
        cleanUp();
        memberId = insertStock("005930", "삼성전자");
        outsiderId = insertStock("035420", "NAVER");
        jdbcTemplate.update("""
                INSERT INTO universe_membership (universe_code, stock_id, effective_from)
                VALUES (?, ?, ?)
                """, UNIVERSE, memberId, AS_OF.minusYears(1));
        fakeSource.fetchedStockIds.clear();
        fakeSource.requestedPeriods.clear();
    }

    @AfterEach
    void cleanUp() {
        jdbcTemplate.update("DELETE FROM batch_execution_failure");
        jdbcTemplate.update("DELETE FROM batch_execution");
        // MUST: financial_statement를 stock보다 먼저 지운다. 남으면 DELETE FROM stock이 FK로
        // 실패하고, 정리가 중단되면 KOSPI/005930을 넣는 다른 테스트가 전부 깨진다.
        jdbcTemplate.update("DELETE FROM financial_statement");
        jdbcTemplate.update("DELETE FROM universe_membership");
        jdbcTemplate.update("DELETE FROM stock WHERE ticker IN ('005930', '035420')");
    }

    @Test
    @DisplayName("이미 저장된 행의 영업활동현금흐름을 채운다 — 이 잡의 존재 이유다")
    void fillsOperatingCashFlowOfStoredRow() {
        insertReportWithoutCashFlow(memberId);
        fakeSource.withReports(memberId, List.of(annualReport(2025, "1500")));

        BatchStatus status = job.run(
                AS_OF, BatchExecutionRecorder.TriggeredBy.MANUAL, 5, List.of(), false);

        assertThat(status).isEqualTo(BatchStatus.SUCCESS);
        assertThat(operatingCashFlowOf(memberId)).isEqualByComparingTo("1500");
        // 채운 것이지 쌓은 것이 아니다.
        assertThat(rowCountOf(memberId)).isEqualTo(1);
    }

    @Test
    @DisplayName("기본은 사업보고서만 부른다 — 보고서 넷을 다 부르면 호출이 4배다")
    void requestsAnnualReportsOnly() {
        job.run(AS_OF, BatchExecutionRecorder.TriggeredBy.MANUAL, 5, List.of(), false);

        assertThat(fakeSource.requestedPeriods)
                .isNotEmpty()
                .allSatisfy(periods -> assertThat(periods)
                        .containsExactly(StatementPeriod.FY));
    }

    @Test
    @DisplayName("분기는 종목을 지정해야 받을 수 있다 — 전 종목 분기는 얻는 것 없이 호출만 4배다")
    void refusesQuartersWithoutTickers() {
        assertThatThrownBy(() -> job.run(
                AS_OF, BatchExecutionRecorder.TriggeredBy.MANUAL, 5, List.of(), true))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("분기");

        assertThat(fakeSource.fetchedStockIds).isEmpty();
    }

    @Test
    @DisplayName("종목을 지정하면 분기까지 받는다")
    void requestsQuartersForNamedTickers() {
        job.run(AS_OF, BatchExecutionRecorder.TriggeredBy.MANUAL, 5, List.of("005930"), true);

        assertThat(fakeSource.requestedPeriods)
                .allSatisfy(periods -> assertThat(periods)
                        .containsExactlyInAnyOrder(StatementPeriod.values()));
    }

    @Test
    @DisplayName("실제 호출 수와 받지 못한 보고서 수가 실행 기록에 남는다")
    void recordsCallCountAndMissingReports() {
        // 2025 하나만 준다. 나머지 다섯 해(2021~2024, 2026)는 공시가 없는 셈이다 —
        // 그 차이가 곧 "상장 전 회계연도"를 가르는 근거가 된다.
        fakeSource.withReports(memberId, List.of(annualReport(2025, "1500")));

        job.run(AS_OF, BatchExecutionRecorder.TriggeredBy.MANUAL, 5, List.of(), false);

        String note = jdbcTemplate.queryForObject(
                "SELECT note FROM batch_execution WHERE job_name = ? ORDER BY id DESC LIMIT 1",
                String.class, FullStatementRefillJob.JOB_NAME);
        assertThat(note)
                .contains("요청=6건")
                .contains("실제콜=6")
                .contains("쓴보고서=1")
                .contains("공시없음=5")
                .contains("불일치=0");
    }

    @Test
    @DisplayName("편입 중인 활성 종목만 대상으로 삼는다")
    void targetsOnlyActiveUniverseMembers() {
        job.run(AS_OF, BatchExecutionRecorder.TriggeredBy.MANUAL, 5, List.of(), false);

        assertThat(fakeSource.fetchedStockIds).containsExactly(memberId);
        assertThat(fakeSource.fetchedStockIds).doesNotContain(outsiderId);
    }

    @Test
    @DisplayName("티커가 하나도 매칭되지 않으면 조용히 성공하지 않고 실패한다")
    void failsLoudlyWhenNoTickerMatches() {
        assertThatThrownBy(() -> job.run(
                AS_OF, BatchExecutionRecorder.TriggeredBy.MANUAL, 5, List.of("999999"), false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("999999");

        assertThat(fakeSource.fetchedStockIds).isEmpty();
    }

    private long insertStock(String ticker, String name) {
        return jdbcTemplate.queryForObject("""
                INSERT INTO stock (market_code, ticker, name) VALUES ('KOSPI', ?, ?) RETURNING id
                """, Long.class, ticker, name);
    }

    /** 주요계정이 남긴 행을 재현한다 — 영업활동현금흐름 자리가 비어 있다. */
    private void insertReportWithoutCashFlow(long stockId) {
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

    /** 위 행과 <b>같은 공시일</b>이며 영업활동현금흐름이 들어 있다. */
    private static RawFinancialReport annualReport(int fiscalYear, String operatingCashFlow) {
        return new RawFinancialReport(
                fiscalYear, "FY",
                LocalDate.of(fiscalYear, 1, 1), LocalDate.of(fiscalYear, 12, 31),
                LocalDate.of(2026, 3, 15),
                "11011", "KRW",
                new BigDecimal("5000"), new BigDecimal("1200"), new BigDecimal("1000"),
                new BigDecimal("20000"), new BigDecimal("12000"), new BigDecimal("8000"),
                new BigDecimal("9000"), new BigDecimal("4000"), new BigDecimal("6000"),
                new BigDecimal(operatingCashFlow), null, null, null);
    }

    private BigDecimal operatingCashFlowOf(long stockId) {
        return jdbcTemplate.queryForObject(
                "SELECT operating_cash_flow FROM financial_statement WHERE stock_id = ? AND is_latest",
                BigDecimal.class, stockId);
    }

    private int rowCountOf(long stockId) {
        return jdbcTemplate.queryForObject(
                "SELECT count(*) FROM financial_statement WHERE stock_id = ?", Integer.class, stockId);
    }

    /**
     * 전체 재무제표 소스를 Fake로 등록한다.
     *
     * <p>실제 DART 어댑터는 {@code @ConditionalOnApiKey}라 테스트에서 등록되지 않으므로
     * 같은 시장에 두 소스가 생기는 충돌은 없다.
     */
    @TestConfiguration
    static class FakeFullStatementConfig {

        @Bean
        FakeFullStatementDataSource fakeFullStatementDataSource() {
            return new FakeFullStatementDataSource("KOSPI", "KOSDAQ");
        }
    }
}
