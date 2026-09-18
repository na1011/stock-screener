package com.stockscreener.pipeline.scheduler;

import com.stockscreener.indicator.IndicatorRetentionPolicy;
import com.stockscreener.pipeline.batch.AdjustmentRebuildJob;
import com.stockscreener.pipeline.batch.BatchExecutionRecorder;
import com.stockscreener.pipeline.batch.BatchShutdownException;
import com.stockscreener.pipeline.batch.BatchStatus;
import com.stockscreener.pipeline.batch.CorporateActionJob;
import com.stockscreener.pipeline.batch.DailyUpdateJob;
import com.stockscreener.pipeline.batch.FinancialRefillJob;
import com.stockscreener.pipeline.batch.FullStatementRefillJob;
import com.stockscreener.pipeline.batch.GovernanceDeriveJob;
import com.stockscreener.pipeline.batch.GovernanceLoadJob;
import com.stockscreener.pipeline.batch.IndicatorBackfillJob;
import com.stockscreener.pipeline.batch.JobFreshnessAlerter;
import com.stockscreener.pipeline.batch.JobFreshnessPolicy;
import com.stockscreener.pipeline.batch.MembershipGapFiller;
import com.stockscreener.pipeline.batch.UniverseIntegrityAlerter;
import com.stockscreener.pipeline.batch.UniverseRebalanceJob;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 스케줄러가 <b>무엇을 어떤 순서로 부르는가</b>를 고정한다.
 *
 * <p>{@link PipelineSchedulerTest}는 <b>언제 도는가</b>(크론 문자열)만 본다. 그것으로 충분했던
 * 것은 기존 두 스케줄이 <b>날짜 하나를 계산해 잡 하나를 부르는 것</b>이 전부였기 때문이다 —
 * 분기가 0개다. 여기서 고정하는 셋은 다르다: 기업 행위 사슬에 분기가 있고, 무거운 잡 선택에
 * 셋이 있다. 그리고 <b>하루 한 번 무인으로 돌며 틀려도 로그 한 줄로 끝난다.</b>
 *
 * <p>IMPORTANT — 이것은 크론이 <b>실제로 발화했는지</b>를 답하지 않는다. 그것은 저장소가 아니라
 * 운영에만 있는 사실이라 ADR-0116의 premise가 {@code batch_execution}에서 잰다.
 */
class PipelineSchedulerChainTest {

    private static final LocalDate KR_CUTOFF = LocalDate.parse("2025-12-03");
    private static final LocalDate US_CUTOFF = LocalDate.parse("2025-12-10");

    private CorporateActionJob corporateActionJob;
    private AdjustmentRebuildJob adjustmentRebuildJob;
    private IndicatorBackfillJob indicatorBackfillJob;
    private GovernanceLoadJob governanceLoadJob;
    private GovernanceDeriveJob governanceDeriveJob;
    private FullStatementRefillJob fullStatementRefillJob;
    private FinancialRefillJob financialRefillJob;
    private JobFreshnessPolicy freshnessPolicy;
    private IndicatorRetentionPolicy retentionPolicy;
    private UniverseRebalanceJob universeRebalanceJob;
    private MembershipGapFiller membershipGapFiller;
    private UniverseIntegrityAlerter universeIntegrityAlerter;
    private PipelineScheduler scheduler;

    @BeforeEach
    void setUp() {
        corporateActionJob = mock(CorporateActionJob.class);
        adjustmentRebuildJob = mock(AdjustmentRebuildJob.class);
        indicatorBackfillJob = mock(IndicatorBackfillJob.class);
        governanceLoadJob = mock(GovernanceLoadJob.class);
        governanceDeriveJob = mock(GovernanceDeriveJob.class);
        fullStatementRefillJob = mock(FullStatementRefillJob.class);
        financialRefillJob = mock(FinancialRefillJob.class);
        freshnessPolicy = mock(JobFreshnessPolicy.class);
        retentionPolicy = mock(IndicatorRetentionPolicy.class);
        universeRebalanceJob = mock(UniverseRebalanceJob.class);
        universeIntegrityAlerter = mock(UniverseIntegrityAlerter.class);

        when(retentionPolicy.writeCutoffs()).thenReturn(Map.of(
                "KOSPI", Optional.of(KR_CUTOFF), "US", Optional.of(US_CUTOFF)));

        scheduler = new PipelineScheduler(
                mock(DailyUpdateJob.class), universeRebalanceJob,
                corporateActionJob, adjustmentRebuildJob, indicatorBackfillJob,
                governanceLoadJob, governanceDeriveJob, fullStatementRefillJob,
                financialRefillJob, mock(JobFreshnessAlerter.class), freshnessPolicy,
                retentionPolicy, universeIntegrityAlerter);
    }

    @Test
    @DisplayName("사슬이 수집 뒤에 재구축을 부른다 — 끊으면 계수 없는 APPROVED가 매 평일 쌓인다")
    void chainRebuildsAfterCollecting() {
        givenInvalidation();

        scheduler.runCorporateActionChain();

        // MUST: universeCode에 null을 준다. 비활성 유니버스를 손으로 고르는 갈래는
        // 자동 실행에 걸지 않는다(ADR-0088·0090).
        verify(corporateActionJob).run(any(), eq(BatchExecutionRecorder.TriggeredBy.SCHEDULER),
                eq(null), eq(List.of()), eq(null));
        verify(adjustmentRebuildJob).runAndReport(any(),
                eq(BatchExecutionRecorder.TriggeredBy.SCHEDULER), eq(List.of()), eq(null));
    }

    @Test
    @DisplayName("계수가 안 바뀌면 백필을 안 부른다 — 평상시가 이쪽이고 여기서 도는 것이 낭비다")
    void noBackfillWhenNothingWasInvalidated() {
        givenInvalidation();

        scheduler.runCorporateActionChain();

        verifyNoInteractions(indicatorBackfillJob);
    }

    @Test
    @DisplayName("백필 구간을 보존 경계로 자른다 — 무효화는 첫 봉부터 지우므로 안 자르면 여덟 배를 돈다")
    void backfillRangeIsClampedToRetention() {
        // 무효화 시작일이 2020-01-02(그 종목의 첫 봉)인데 보존 경계는 2025-12-03이다.
        // 경계 밖 일자는 계산해도 저장되지 않으므로(ADR-0011) 돌 이유가 없다.
        givenInvalidation("005440", LocalDate.parse("2020-01-02"));

        scheduler.runCorporateActionChain();

        ArgumentCaptor<LocalDate> from = ArgumentCaptor.forClass(LocalDate.class);
        verify(indicatorBackfillJob).run(from.capture(), any(),
                eq(BatchExecutionRecorder.TriggeredBy.SCHEDULER), eq(List.of("005440")), eq(null));
        // MUST: 시장별 경계 중 **가장 이른 것**을 쓴다. 늦은 것을 쓰면 보존이 더 긴 시장의
        // 저장 가능한 구간을 함께 잘라 그 시장 지표가 지워진 채로 남는다.
        assertThat(from.getValue()).isEqualTo(KR_CUTOFF.plusDays(1));
    }

    @Test
    @DisplayName("무효화 시작일이 경계보다 늦으면 그대로 쓴다 — 경계로 넓히면 헛돈다")
    void backfillRangeKeepsALaterStart() {
        LocalDate later = LocalDate.parse("2026-05-01");
        givenInvalidation("005440", later);

        scheduler.runCorporateActionChain();

        ArgumentCaptor<LocalDate> from = ArgumentCaptor.forClass(LocalDate.class);
        verify(indicatorBackfillJob).run(from.capture(), any(), any(), anyList(), any());
        assertThat(from.getValue()).isEqualTo(later);
    }

    @Test
    @DisplayName("무효화가 상한을 넘으면 백필을 안 돌린다 — 자동으로 시간 단위 작업을 시작하면 그날이 밀린다")
    void hugeInvalidationIsNotBackfilledAutomatically() {
        String[] many = new String[11];
        for (int i = 0; i < many.length; i++) {
            many[i] = "T" + i;
        }
        when(adjustmentRebuildJob.runAndReport(any(), any(), anyList(), any()))
                .thenReturn(new AdjustmentRebuildJob.Invalidation(
                        BatchStatus.SUCCESS, List.of(many), LocalDate.parse("2026-05-01")));

        scheduler.runCorporateActionChain();

        // 지운 채로 남는다. 그것이 **조용하지 않은** 상태이고, 로그가 종목을 이름으로 남긴다.
        verifyNoInteractions(indicatorBackfillJob);
    }

    @Test
    @DisplayName("배정 요일의 잡이 연체면 그것을 고른다 — 하루에 무거운 잡은 하나다")
    void picksTheJobAssignedToToday() {
        overdue(GovernanceLoadJob.JOB_NAME, 40);
        fresh(FullStatementRefillJob.JOB_NAME, FinancialRefillJob.JOB_NAME);

        assertThat(scheduler.heavyJobDueToday(DayOfWeek.TUESDAY))
                .isEqualTo(GovernanceLoadJob.JOB_NAME);
        assertThat(scheduler.heavyJobDueToday(DayOfWeek.WEDNESDAY))
                .as("수요일 잡이 최근에 돌았어도 연체된 화요일 잡을 집는다 — 그것이 연체 보정이다")
                .isEqualTo(GovernanceLoadJob.JOB_NAME);
    }

    @Test
    @DisplayName("연체 보정이 놓친 요일을 집는다 — 요일만 두면 한 번 놓친 잡이 다음 달까지 밀린다")
    void overdueJobIsPickedOnAnotherDay() {
        overdue(FinancialRefillJob.JOB_NAME, 60);
        fresh(GovernanceLoadJob.JOB_NAME, FullStatementRefillJob.JOB_NAME);

        // 목요일 잡인데 화요일에 집는다. 앱이 사람이 켠 동안만 돌아(ADR-0043) 지정 요일을
        // 놓치는 일이 실제로 일어나기 때문이다 — 13:30 크론조차 실측 발화가 평일의 87%다.
        assertThat(scheduler.heavyJobDueToday(DayOfWeek.TUESDAY))
                .isEqualTo(FinancialRefillJob.JOB_NAME);
    }

    @Test
    @DisplayName("배정 요일 잡이 연체면 더 오래 밀린 것이 있어도 그것이 먼저다 — 요일이 주 스케줄이다")
    void assignedDayWinsOverAMoreOverdueJob() {
        // 연체 보정은 **놓친 요일을 메우는 장치**이지 우선순위 큐가 아니다. 뒤집으면 요일 배정이
        // 뜻을 잃고, 그러면 「화요일에 무엇이 도는가」를 아무도 예측하지 못한다.
        when(freshnessPolicy.daysSinceLastSuccess(eq(GovernanceLoadJob.JOB_NAME), any()))
                .thenReturn(Long.MAX_VALUE);
        overdue(FullStatementRefillJob.JOB_NAME, 30);
        fresh(FinancialRefillJob.JOB_NAME);

        assertThat(scheduler.heavyJobDueToday(DayOfWeek.WEDNESDAY))
                .isEqualTo(FullStatementRefillJob.JOB_NAME);
    }

    @Test
    @DisplayName("한 번도 안 돈 잡을 보정이 가장 먼저 집는다 — 0일로 접으면 영원히 안 돈다")
    void neverRunJobWinsTheOverdueFallback() {
        // daysSinceLastSuccess가 「없음」을 0이 아니라 무한대로 내는 것이 이 판정의 전제다.
        when(freshnessPolicy.daysSinceLastSuccess(eq(GovernanceLoadJob.JOB_NAME), any()))
                .thenReturn(Long.MAX_VALUE);
        overdue(FinancialRefillJob.JOB_NAME, 30);
        fresh(FullStatementRefillJob.JOB_NAME);

        // 수요일 잡이 최근에 돌았으므로 보정이 돈다. 그때 60일 밀린 것보다 한 번도 안 돈 것이 먼저다.
        assertThat(scheduler.heavyJobDueToday(DayOfWeek.WEDNESDAY))
                .isEqualTo(GovernanceLoadJob.JOB_NAME);
    }

    @Test
    @DisplayName("최근에 다 돌았으면 아무것도 안 돈다 — 같은 값을 다시 받으면 콜만 태운다")
    void nothingRunsWhenEverythingIsFresh() {
        fresh(GovernanceLoadJob.JOB_NAME, FullStatementRefillJob.JOB_NAME,
                FinancialRefillJob.JOB_NAME);

        assertThat(scheduler.heavyJobDueToday(DayOfWeek.TUESDAY)).isNull();

        scheduler.runHeavyJobOfTheDay();
        verifyNoInteractions(governanceLoadJob, fullStatementRefillJob, financialRefillJob);
    }

    @Test
    @DisplayName("거버넌스는 적재 뒤 유도까지 간다 — 적재만 하면 화면이 옛 값 그대로인데 둘 다 성공한다")
    void governanceLoadIsFollowedByDerive() {
        overdue(GovernanceLoadJob.JOB_NAME, 40);
        fresh(FullStatementRefillJob.JOB_NAME, FinancialRefillJob.JOB_NAME);

        scheduler.runHeavyJobOfTheDay();

        // span 2가 최소치다 — 회계연도가 역년 둘에 걸치는 종목은 두 해를 다 받아야 사실이 난다.
        verify(governanceLoadJob).run(any(), eq(BatchExecutionRecorder.TriggeredBy.SCHEDULER),
                eq(null), eq(2), anyList(), anyList());
        verify(governanceDeriveJob).run(any(), eq(BatchExecutionRecorder.TriggeredBy.SCHEDULER),
                anyList());
        verifyNoInteractions(fullStatementRefillJob, financialRefillJob);
    }

    @Test
    @DisplayName("재무 정정이 있으면 백필까지 간다 — 안 이으면 그 종목이 화면에서 통째로 사라진다")
    void financialRefillIsFollowedByBackfill() {
        overdue(FinancialRefillJob.JOB_NAME, 40);
        fresh(GovernanceLoadJob.JOB_NAME, FullStatementRefillJob.JOB_NAME);
        when(financialRefillJob.runAndReport(any(), any(), any(), anyList(), any()))
                .thenReturn(new FinancialRefillJob.Invalidation(
                        BatchStatus.SUCCESS, List.of("005930", "000660")));

        scheduler.runHeavyJobOfTheDay();

        // 정정 반영은 그 종목의 indicator_value를 회계기간 시작일 이후 전부 지운다. 실측으로
        // 표본 30종목 실행이 2종목을 145/256거래일로 떨어뜨렸고, 에러도 경고도 나지 않았다.
        ArgumentCaptor<List<String>> tickers = ArgumentCaptor.forClass(List.class);
        verify(indicatorBackfillJob).run(any(), any(),
                eq(BatchExecutionRecorder.TriggeredBy.SCHEDULER), tickers.capture(), eq(null));
        assertThat(tickers.getValue()).containsExactly("005930", "000660");
    }

    @Test
    @DisplayName("재무 정정이 없으면 백필을 안 부른다 — 평상시가 이쪽이다")
    void skipsBackfillWhenNoFinancialRevision() {
        overdue(FinancialRefillJob.JOB_NAME, 40);
        fresh(GovernanceLoadJob.JOB_NAME, FullStatementRefillJob.JOB_NAME);
        when(financialRefillJob.runAndReport(any(), any(), any(), anyList(), any()))
                .thenReturn(new FinancialRefillJob.Invalidation(BatchStatus.SUCCESS, List.of()));

        scheduler.runHeavyJobOfTheDay();

        verifyNoInteractions(indicatorBackfillJob);
    }

    @Test
    @DisplayName("리밸런싱은 잡에 맡기고 꼬리를 직접 안 부른다 — 꼬리가 잡 안으로 갔다(손·관리자 경로도 타야 한다)")
    void delegatesRebalancingIncludingItsTail() {
        scheduler.runUniverseRebalance();

        verify(universeRebalanceJob).run(any(), eq(BatchExecutionRecorder.TriggeredBy.SCHEDULER));
    }

    @Test
    @DisplayName("리밸런싱이 종료로 중단돼도 스케줄러 밖으로 안 던진다 — @Scheduled 밖으로 나가면 Sentry 이슈가 쌓인다")
    void swallowsShutdownFromRebalancing() {
        doThrow(new BatchShutdownException("종료 중이다"))
                .when(universeRebalanceJob).run(any(), any());

        scheduler.runUniverseRebalance();
    }

    @Test
    @DisplayName("15시 10분 점검이 편입 원자료도 잰다 — 지표 축은 일일 갱신 뒤에만 뜻이 있다")
    void freshnessCheckAlsoMeasuresMembership() {
        scheduler.checkJobFreshness();

        verify(universeIntegrityAlerter).evaluateAndAlert();
    }

    private void givenInvalidation() {
        when(adjustmentRebuildJob.runAndReport(any(), any(), anyList(), any()))
                .thenReturn(new AdjustmentRebuildJob.Invalidation(
                        BatchStatus.SUCCESS, List.of(), null));
    }

    private void givenInvalidation(String ticker, LocalDate earliest) {
        when(adjustmentRebuildJob.runAndReport(any(), any(), anyList(), any()))
                .thenReturn(new AdjustmentRebuildJob.Invalidation(
                        BatchStatus.SUCCESS, List.of(ticker), earliest));
    }

    private void overdue(String jobName, long days) {
        when(freshnessPolicy.daysSinceLastSuccess(eq(jobName), any())).thenReturn(days);
    }

    private void fresh(String... jobNames) {
        for (String jobName : jobNames) {
            when(freshnessPolicy.daysSinceLastSuccess(eq(jobName), any())).thenReturn(1L);
        }
    }
}
