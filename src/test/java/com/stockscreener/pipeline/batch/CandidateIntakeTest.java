package com.stockscreener.pipeline.batch;

import com.stockscreener.notification.AlertEvent;
import com.stockscreener.notification.AlertPort;
import com.stockscreener.pipeline.datasource.us.SecTickerCikCache;
import com.stockscreener.support.TestDatabaseGuard;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link CandidateIntake}(ADR-0178) — 후보 풀을 화해한 실행이 <b>그 실행이 만든</b> 미국 행에만 업종 → 재무를 붙이는지. 이미 있던 행은
 * 장부가 공시를 놓치지 않으므로 안 부르고, SEC 매핑에 없는 티커는 재무를 안 부른다.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestDatabaseGuard.class)
class CandidateIntakeTest {

    private static final LocalDate AS_OF = LocalDate.of(2026, 10, 17);

    @Autowired
    private com.stockscreener.pipeline.collector.StockRegistrar stockRegistrar;

    @Autowired
    private JdbcTemplate jdbc;

    private CompanyProfileJob profile;
    private FinancialRefillJob refill;
    private SecTickerCikCache cikCache;
    private final List<AlertEvent> alerts = new ArrayList<>();
    private CandidateIntake intake;

    @BeforeEach
    void setUp() throws Exception {
        cleanUp();
        profile = mock(CompanyProfileJob.class);
        refill = mock(FinancialRefillJob.class);
        cikCache = mock(SecTickerCikCache.class);
        SecTickerCikCache.Snapshot snapshot = mock(SecTickerCikCache.Snapshot.class);
        when(snapshot.findCik(anyString())).thenReturn(Optional.empty());
        when(snapshot.findCik("ZZQ2")).thenReturn(Optional.of(99_000_021L));
        when(cikCache.freshSnapshot()).thenReturn(snapshot);
        AlertPort recording = alerts::add;
        intake = new CandidateIntake(jdbc, profile, refill, cikCache, recording, stockRegistrar);
    }

    @AfterEach
    void cleanUp() {
        jdbc.update("DELETE FROM financial_statement WHERE stock_id IN (SELECT id FROM stock WHERE ticker LIKE 'ZZQ%')");
        jdbc.update("DELETE FROM universe_membership WHERE stock_id IN (SELECT id FROM stock WHERE ticker LIKE 'ZZQ%')");
        jdbc.update("DELETE FROM stock WHERE ticker IN ('ZZQ1', 'ZZQ2', 'ZZQ3W', 'ZZQ4F', 'ZZQ5M')");
        alerts.clear();
    }

    @Test
    @DisplayName("이번 실행이 만든 행에만 업종을 붙이고, 재무는 그중 SEC 매핑 있는 것만 — 이미 있던 행은 안 부른다")
    void feedsOnlyRowsThisRunCreated() {
        stock("ZZQ1");
        Instant since = intake.now();
        stock("ZZQ2");
        stock("ZZQ3W");

        intake.run(AS_OF, since);

        verify(profile).runForCandidates(AS_OF, BatchExecutionRecorder.TriggeredBy.GAP_FILL, List.of("ZZQ2", "ZZQ3W"));
        verify(refill).runForCandidates(AS_OF, BatchExecutionRecorder.TriggeredBy.GAP_FILL, List.of("ZZQ2"));
        assertThat(alerts).isEmpty();
    }

    @Test
    @DisplayName("새 행이 없으면 아무 잡도 안 부른다 — 비면 전량이 되는 잡이 있다")
    void doesNothingWithoutNewRows() {
        stock("ZZQ1");
        Instant since = intake.now();

        intake.run(AS_OF, since);

        verify(profile, never()).runForCandidates(any(), any(), any());
        verify(refill, never()).runForCandidates(any(), any(), any());
    }

    @Test
    @DisplayName("인입이 실패하면 던지지 않고 티커와 함께 알린다 — 리밸런싱은 이미 끝났다")
    void failureAlertsInsteadOfThrowing() {
        Instant since = intake.now();
        stock("ZZQ2");
        when(refill.runForCandidates(any(), any(), any())).thenThrow(new IllegalArgumentException("대상 0"));

        intake.run(AS_OF, since);

        assertThat(alerts).singleElement().satisfies(a -> {
            assertThat(a.title()).isEqualTo("후보 인입 실패");
            assertThat(a.body()).contains("ZZQ2");
        });
    }

    @Test
    @DisplayName("종료 신호는 되던진다 — 내려가는 프로세스가 콜을 더 태우지 않는다")
    void shutdownPropagates() {
        Instant since = intake.now();
        stock("ZZQ2");
        when(profile.runForCandidates(any(), any(), any())).thenThrow(new BatchShutdownException("종료"));

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> intake.run(AS_OF, since))
                .isInstanceOf(BatchShutdownException.class);
        verify(refill, never()).runForCandidates(any(), eq(BatchExecutionRecorder.TriggeredBy.GAP_FILL), any());
    }

    @Test
    @DisplayName("후보 손 FR — 대상은 매핑 ∪ 재무 행 있음(편입 제외) · 업종은 SIC 없는 대상에만 먼저 · 재무는 연수·이른 판을 그대로")
    void manualRefillTargetsMappedOrStoredCandidatesSectorFirst() {
        stock("ZZQ2");
        stock("ZZQ3W");
        long stored = stock("ZZQ4F");
        jdbc.update("UPDATE stock SET sic_code = '2834' WHERE id = ?", stored);
        financialRow(stored);
        long member = stock("ZZQ5M");
        financialRow(member);
        jdbc.update("INSERT INTO universe_membership (universe_code, stock_id, effective_from) VALUES ('US_TOP1000', ?, ?)",
                member, AS_OF.minusYears(1));
        when(profile.runForRefillWindow(any(), any(), any())).thenReturn(BatchStatus.SUCCESS);
        when(refill.runForCandidatesManually(any(), any(), any(), any(), org.mockito.ArgumentMatchers.anyBoolean()))
                .thenReturn(new FinancialRefillJob.Invalidation(BatchStatus.SUCCESS, List.of(), 1L));

        BatchStatus status = intake.refill(AS_OF, 11, List.of(), true);

        assertThat(status).isEqualTo(BatchStatus.SUCCESS);
        org.mockito.ArgumentCaptor<List<String>> sector = org.mockito.ArgumentCaptor.captor();
        verify(profile).runForRefillWindow(eq(AS_OF), eq(BatchExecutionRecorder.TriggeredBy.MANUAL), sector.capture());
        assertThat(sector.getValue()).as("SIC 없는 대상만 — ZZQ4F는 업종이 있다").contains("ZZQ2")
                .doesNotContain("ZZQ4F", "ZZQ3W", "ZZQ5M");
        org.mockito.ArgumentCaptor<List<String>> targets = org.mockito.ArgumentCaptor.captor();
        verify(refill).runForCandidatesManually(eq(AS_OF), eq(BatchExecutionRecorder.TriggeredBy.MANUAL), eq(11),
                targets.capture(), eq(true));
        assertThat(targets.getValue()).as("매핑 밖이어도 재무 행이 있으면 부른다 · 매핑도 행도 없으면 · 편입이면 안 부른다")
                .contains("ZZQ2", "ZZQ4F").doesNotContain("ZZQ3W", "ZZQ5M");
    }

    @Test
    @DisplayName("후보 손 FR — 업종 수집이 FAILED면 재무로 안 넘어간다(SIC 없이 주식수가 생기면 비사업회사 거름을 통과한다)")
    void manualRefillStopsWhenSectorFails() {
        stock("ZZQ2");
        when(profile.runForRefillWindow(any(), any(), any())).thenReturn(BatchStatus.FAILED);

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> intake.refill(AS_OF, 11, List.of("ZZQ2"), true))
                .isInstanceOf(IllegalStateException.class);
        verify(refill, never()).runForCandidatesManually(any(), any(), any(), any(),
                org.mockito.ArgumentMatchers.anyBoolean());
    }

    @Test
    @DisplayName("후보 손 FR — 대상이 0이면 던진다(대상 0건 성공은 막으려는 결함과 같은 모양)")
    void manualRefillRefusesZeroTargets() {
        stock("ZZQ3W");

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> intake.refill(AS_OF, 11, List.of("ZZQ3W"), true))
                .isInstanceOf(IllegalArgumentException.class);
        verify(profile, never()).runForRefillWindow(any(), any(), any());
    }

    private void financialRow(long stockId) {
        jdbc.update("""
                INSERT INTO financial_statement (stock_id, fiscal_year, fiscal_period, period_start, period_end, published_at,
                    revision_no, is_latest, report_code, currency_code)
                VALUES (?, 2025, 'FY', '2025-01-01', '2025-12-31', '2026-02-15', 0, true, '10-K', 'USD')
                """, stockId);
    }

    private long stock(String ticker) {
        // created_at은 문장 시각이 아니라 트랜잭션 시각이다 — 자동 커밋이라 문장마다 새 시각이다.
        return jdbc.queryForObject("INSERT INTO stock (market_code, ticker, name, created_at, updated_at) "
                + "VALUES ('US', ?, ?, now(), now()) RETURNING id", Long.class, ticker, ticker);
    }
}
