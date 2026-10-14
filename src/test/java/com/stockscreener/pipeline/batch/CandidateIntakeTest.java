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
        intake = new CandidateIntake(jdbc, profile, refill, cikCache, recording);
    }

    @AfterEach
    void cleanUp() {
        jdbc.update("DELETE FROM stock WHERE ticker IN ('ZZQ1', 'ZZQ2', 'ZZQ3W')");
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

    private void stock(String ticker) {
        // created_at은 문장 시각이 아니라 트랜잭션 시각이다 — 자동 커밋이라 문장마다 새 시각이다.
        jdbc.update("INSERT INTO stock (market_code, ticker, name, created_at, updated_at) VALUES ('US', ?, ?, now(), now())",
                ticker, ticker);
    }
}
