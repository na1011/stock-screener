package com.stockscreener.pipeline.batch;

import com.stockscreener.pipeline.datasource.DataSourceException;
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
    private static final String UNIVERSE = "KOSPI200";

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
        // 부재 관측도 stock을 참조한다 — 같은 이유로 여기서 함께 지운다.
        jdbcTemplate.update("DELETE FROM financial_column_absence");
        jdbcTemplate.update("DELETE FROM financial_statement");
        jdbcTemplate.update("DELETE FROM universe_membership");
        jdbcTemplate.update("DELETE FROM stock WHERE ticker IN ('005930', '035420')");
    }

    @Test
    @DisplayName("이미 저장된 행의 NULL 컬럼을 채운다 — APPEND_ONLY로 부르면 아무 일도 일어나지 않는다")
    void fillsNullColumnsOfStoredRow() {
        insertReportWithoutBalanceDetail(memberId);
        fakeFinancialSource.withReports(memberId, List.of(report()));

        BatchStatus status = job.run(AS_OF, BatchExecutionRecorder.TriggeredBy.MANUAL, 3, List.of(), null);

        assertThat(status).isEqualTo(BatchStatus.SUCCESS);
        assertThat(currentAssetsOf(memberId)).isEqualByComparingTo("9000");
        // 채운 것이지 쌓은 것이 아니다.
        assertThat(rowCountOf(memberId)).isEqualTo(1);
    }

    @Test
    @DisplayName("실행 기록이 보충 칸을 낸다 — 미해결은 이 실행이 본 것만(표 전체는 괄호) · 회수를 멈춘 종목은 이름으로(ADR-0173)")
    void noteCarriesSupplementCountsAndRetractionStoppedTickers() {
        jdbcTemplate.update("DELETE FROM sec_filing_supplement");
        try {
            // 이 실행 전에 본 실패 행 — 기본 FR이 안 도는 종목의 것이다. 표 전체에만 든다.
            jdbcTemplate.update("""
                    INSERT INTO sec_filing_supplement (accession, cik, form, filed, reader_version, status,
                        failure_kind, failure_message, fetched_at, last_seen_at)
                    VALUES ('0000999999-20-000001', 999999, '10-Q', '2020-05-01', 1, 'FAILED', 'TRANSIENT', 'x',
                        now() - interval '40 days', now() - interval '40 days')
                    """);
            fakeFinancialSource.withReports(memberId, List.of(report())).uncertainFor(memberId);

            job.run(AS_OF, BatchExecutionRecorder.TriggeredBy.MANUAL, 3, List.of(), null);

            String note = jdbcTemplate.queryForObject(
                    "SELECT note FROM batch_execution WHERE job_name = ? ORDER BY id DESC LIMIT 1",
                    String.class, FinancialRefillJob.JOB_NAME);
            assertThat(note).contains("미해결보충=0(표 전체 1)").contains("회수정지=1종목")
                    .contains("회수정지종목=005930");
        } finally {
            jdbcTemplate.update("DELETE FROM sec_filing_supplement");
            fakeFinancialSource.reset();
        }
    }

    @Test
    @DisplayName("편입 중인 활성 종목만 대상으로 삼는다")
    void targetsOnlyActiveUniverseMembers() {
        job.run(AS_OF, BatchExecutionRecorder.TriggeredBy.MANUAL, 3, List.of(), null);

        assertThat(fakeFinancialSource.fetchedStockIds).containsExactly(memberId);
        assertThat(fakeFinancialSource.fetchedStockIds).doesNotContain(outsiderId);
    }

    @Test
    @DisplayName("--universe를 주면 비활성 유니버스에도 닿는다 — 안 주면 그 종목이 대상에서 빠진다")
    void reachesInactiveUniverseOnlyWhenUniverseIsGiven() {
        Boolean wasActive = jdbcTemplate.queryForObject(
                "SELECT is_active FROM universe WHERE code = ?", Boolean.class, UNIVERSE);
        jdbcTemplate.update("UPDATE universe SET is_active = false WHERE code = ?", UNIVERSE);
        try {
            // 대조군 — 유니버스를 안 주면 activeUniverseMemberIds가 u.is_active를 조인해
            // 한 건도 안 잡는다. 미국 재무를 고칠 수 없었던 이유가 정확히 이것이다.
            job.run(AS_OF, BatchExecutionRecorder.TriggeredBy.MANUAL, 3, List.of(), null);
            assertThat(fakeFinancialSource.fetchedStockIds)
                    .as("비활성 유니버스는 기본 갈래로 안 잡힌다")
                    .isEmpty();

            job.run(AS_OF, BatchExecutionRecorder.TriggeredBy.MANUAL, 3, List.of(), UNIVERSE);
            assertThat(fakeFinancialSource.fetchedStockIds)
                    .as("유니버스를 명시하면 활성 여부를 안 본다")
                    .containsExactly(memberId);
        } finally {
            jdbcTemplate.update("UPDATE universe SET is_active = ? WHERE code = ?",
                    wasActive, UNIVERSE);
        }
    }

    @Test
    @DisplayName("--universe에 대상이 하나도 없으면 조용히 성공하지 않고 실패한다")
    void failsLoudlyWhenUniverseHasNoTargets() {
        assertThatThrownBy(() -> job.run(
                AS_OF, BatchExecutionRecorder.TriggeredBy.MANUAL, 3, List.of(), "NOPE"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("NOPE");

        assertThat(fakeFinancialSource.fetchedStockIds).isEmpty();
    }

    @Test
    @DisplayName("티커가 하나도 매칭되지 않으면 조용히 성공하지 않고 실패한다")
    void failsLoudlyWhenNoTickerMatches() {
        assertThatThrownBy(() -> job.run(
                AS_OF, BatchExecutionRecorder.TriggeredBy.MANUAL, 3, List.of("999999"), null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("999999");

        assertThat(fakeFinancialSource.fetchedStockIds).isEmpty();
    }

    @Test
    @DisplayName("티커를 주면 편입 종목과의 교집합만 처리한다")
    void narrowsToIntersectionOfGivenTickers() {
        // 035420은 활성이지만 편입되어 있지 않다 — 하나만 남아야 한다.
        job.run(AS_OF, BatchExecutionRecorder.TriggeredBy.MANUAL, 3,
                List.of("005930", "035420"), null);

        assertThat(fakeFinancialSource.fetchedStockIds).containsExactly(memberId);
    }

    @Test
    @DisplayName("정기 범위 FR이 한 시장의 소스 답을 못 받으면 마감에서 NARROW·MARKET_INCOMPLETE — 표지 없는 NOT_FOUND도 같다(ADR-0174)")
    void unansweredFailureMakesTheMarketIncomplete() {
        try {
            for (DataSourceException failure : List.of(
                    new DataSourceException(DataSourceException.Kind.TRANSIENT, "fake-financial", "503"),
                    new DataSourceException(DataSourceException.Kind.NOT_FOUND, "fake-financial", "답 미확인"))) {
                fakeFinancialSource.reset();
                fakeFinancialSource.failingWith(memberId, failure);

                BatchStatus status = job.run(today(), BatchExecutionRecorder.TriggeredBy.SCHEDULER, null, List.of(), null);

                assertThat(status).as(failure.kind().name()).isEqualTo(BatchStatus.PARTIAL_SUCCESS);
                assertThat(latest("scope")).isEqualTo("NARROW");
                assertThat(latest("scope_reason")).isEqualTo("MARKET_INCOMPLETE");
                // 시험 컨텍스트에는 미국 재무 소스가 없다 — US는 (가) 「소스 없음」으로 늘 함께 뜬다.
                assertThat(latest("array_to_string(incomplete_markets, ',')")).isEqualTo("KOSPI,US");
                assertThat(latest("note")).contains("시장 미완료 KOSPI");
            }
        } finally {
            fakeFinancialSource.reset();
        }
    }

    @Test
    @DisplayName("소스가 결정적으로 답한 NOT_FOUND(표지)는 그 시장을 끝낸 것이다 — 남는 미완료는 소스 없는 US뿐")
    void answeredFailureCompletesTheMarket() {
        try {
            fakeFinancialSource.reset();
            fakeFinancialSource.failingWith(memberId,
                    DataSourceException.answered(DataSourceException.Kind.NOT_FOUND, "fake-financial", "013"));

            job.run(today(), BatchExecutionRecorder.TriggeredBy.SCHEDULER, null, List.of(), null);

            // KOSPI는 성공 0 · 실패 1이라 (나)로 미완료다 — 표지가 끝내는 것은 (다)다. 성공이 하나 있으면 끝난다.
            assertThat(latest("array_to_string(incomplete_markets, ',')")).isEqualTo("KOSPI,US");
            assertThat(latest("note")).contains("KOSPI 성공 0 실패 1").doesNotContain("없음(답 미확인)");

            long second = insertStock("000660", "SK하이닉스");
            jdbcTemplate.update("""
                    INSERT INTO universe_membership (universe_code, stock_id, effective_from) VALUES (?, ?, ?)
                    """, UNIVERSE, second, AS_OF.minusYears(1));
            job.run(today(), BatchExecutionRecorder.TriggeredBy.SCHEDULER, null, List.of(), null);
            assertThat(latest("array_to_string(incomplete_markets, ',')")).isEqualTo("US");
        } finally {
            fakeFinancialSource.reset();
            jdbcTemplate.update("DELETE FROM universe_membership");
            jdbcTemplate.update("DELETE FROM stock WHERE ticker = '000660'");
        }
    }

    @Test
    @DisplayName("대상 0건으로 곧장 마감하는 갈래도 같은 판정을 지난다 — 소스 없는 시장은 대상이 없어도 미완료다")
    void earlyReturnAlsoJudgesMarkets() {
        jdbcTemplate.update("DELETE FROM universe_membership");

        job.run(today(), BatchExecutionRecorder.TriggeredBy.SCHEDULER, null, List.of(), null);

        assertThat(latest("target_count")).isEqualTo("0");
        assertThat(latest("scope_reason")).isEqualTo("MARKET_INCOMPLETE");
        assertThat(latest("array_to_string(incomplete_markets, ',')")).isEqualTo("US");
    }

    @Test
    @DisplayName("종목이 성공해도 보충을 받지 못했으면 그 시장은 미완료다 — 보충 계약은 종목을 안 세우므로 이 길이 아니면 안 보인다")
    void unfetchedSupplementMakesTheMarketIncomplete() {
        try {
            fakeFinancialSource.reset();
            fakeFinancialSource.withReports(memberId, List.of(report()))
                    .unfetchedFor(memberId, List.of(DataSourceException.Kind.TRANSIENT));

            BatchStatus status = job.run(today(), BatchExecutionRecorder.TriggeredBy.SCHEDULER, null, List.of(), null);

            assertThat(status).as("종목은 성공 — 실패 수는 그대로다").isEqualTo(BatchStatus.SUCCESS);
            assertThat(latest("scope_reason")).isEqualTo("MARKET_INCOMPLETE");
            assertThat(latest("array_to_string(incomplete_markets, ',')")).isEqualTo("KOSPI,US");
            assertThat(latest("note")).contains("KOSPI 보충 일시 1");
        } finally {
            fakeFinancialSource.reset();
        }
    }

    @Test
    @DisplayName("과거 편입(현재 활성 편입 아님)의 신선한 미매핑은 답함 — 그 시장을 끝낸다(상장폐지 꼴 · CRNX·APGE)")
    void retiredStockAbsenceCompletesTheMarket() {
        long retired = insertStock("000020", "상폐종목");
        long alive = insertStock("000030", "정상종목");
        try {
            for (long id : List.of(retired, alive)) {
                jdbcTemplate.update("INSERT INTO universe_membership (universe_code, stock_id, effective_from) VALUES "
                        + "('KOSPI_TOP200', ?, ?)", id, AS_OF.minusYears(1));
            }
            fakeFinancialSource.reset();
            fakeFinancialSource.withReports(alive, List.of(report()))
                    .failingWith(retired, DataSourceException.answered(DataSourceException.Kind.NOT_FOUND,
                            "fake-financial", "신선한 판에 없다"));

            job.run(today(), BatchExecutionRecorder.TriggeredBy.MANUAL, null, List.of(), "KOSPI_TOP200");

            assertThat(latest("scope_reason")).as("유니버스를 골랐다 — 시작부터 좁다").isEqualTo("ARGS");
            assertThat(latest("array_to_string(incomplete_markets, ',')")).as("KOSPI는 끝났다 — 남는 것은 소스 없는 US")
                    .isEqualTo("US");
        } finally {
            fakeFinancialSource.reset();
            cleanUpStocks("000020", "000030");
        }
    }

    @Test
    @DisplayName("현재 편입이고 최근 봉이 있는 종목의 신선한 미매핑은 답 못 받음 — 신규 상장 등록 지연 꼴이라 다음 차례에 다시 묻는다")
    void listedStockAbsenceLeavesTheMarketIncomplete() {
        long alive = insertStock("000660", "SK하이닉스");
        try {
            jdbcTemplate.update("INSERT INTO universe_membership (universe_code, stock_id, effective_from) VALUES (?, ?, ?)",
                    UNIVERSE, alive, AS_OF.minusYears(1));
            jdbcTemplate.update("""
                    INSERT INTO price_daily (stock_id, trade_date, open, high, low, close, adj_close, volume)
                    VALUES (?, ?, 1, 1, 1, 1, 1, 1)
                    """, memberId, today());
            fakeFinancialSource.reset();
            fakeFinancialSource.withReports(alive, List.of(report()))
                    .failingWith(memberId, DataSourceException.answered(DataSourceException.Kind.NOT_FOUND,
                            "fake-financial", "신선한 판에 없다"));

            job.run(today(), BatchExecutionRecorder.TriggeredBy.SCHEDULER, null, List.of(), null);

            assertThat(latest("scope_reason")).isEqualTo("MARKET_INCOMPLETE");
            assertThat(latest("array_to_string(incomplete_markets, ',')")).isEqualTo("KOSPI,US");
            assertThat(latest("note")).contains("KOSPI 없음(답 미확인) 1");
        } finally {
            fakeFinancialSource.reset();
            jdbcTemplate.update("DELETE FROM price_daily WHERE stock_id = ?", memberId);
            cleanUpStocks("000660");
        }
    }

    private void cleanUpStocks(String... tickers) {
        for (String ticker : tickers) {
            jdbcTemplate.update("DELETE FROM financial_column_absence WHERE stock_id IN (SELECT id FROM stock WHERE ticker = ?)", ticker);
            jdbcTemplate.update("DELETE FROM financial_statement WHERE stock_id IN (SELECT id FROM stock WHERE ticker = ?)", ticker);
            jdbcTemplate.update("DELETE FROM batch_execution_failure WHERE stock_id IN (SELECT id FROM stock WHERE ticker = ?)", ticker);
            jdbcTemplate.update("DELETE FROM universe_membership WHERE stock_id IN (SELECT id FROM stock WHERE ticker = ?)", ticker);
            jdbcTemplate.update("DELETE FROM stock WHERE ticker = ?", ticker);
        }
    }

    /** 정기 실행의 기준일 — 지금이 아니면 기록 계층이 PAST_DATE로 찍어 시장 판정과 무관하게 NARROW다. */
    private static LocalDate today() {
        return LocalDate.now(java.time.ZoneId.of("Asia/Seoul"));
    }

    private String latest(String column) {
        return jdbcTemplate.queryForObject("SELECT " + column + "::text FROM batch_execution WHERE job_name = ? "
                + "ORDER BY id DESC LIMIT 1", String.class, FinancialRefillJob.JOB_NAME);
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
                null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null);
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
