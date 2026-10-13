package com.stockscreener.pipeline.scheduler;

import com.stockscreener.indicator.IndicatorCalendar;
import com.stockscreener.pipeline.collector.ShareBasisDeriver;
import com.stockscreener.pipeline.batch.AdjustmentRebuildJob;
import com.stockscreener.pipeline.batch.BatchExecutionContext;
import com.stockscreener.pipeline.batch.BatchExecutionRecorder;
import com.stockscreener.pipeline.batch.BatchShutdownException;
import com.stockscreener.pipeline.batch.FilingConsumptionJob;
import com.stockscreener.pipeline.batch.FilingListingJob;
import com.stockscreener.pipeline.batch.BatchExecutions;
import com.stockscreener.pipeline.batch.BatchShutdownException;
import com.stockscreener.pipeline.batch.BatchStatus;
import com.stockscreener.pipeline.batch.CorporateActionJob;
import com.stockscreener.pipeline.batch.DailyUpdateJob;
import com.stockscreener.pipeline.batch.FinancialRefillJob;
import com.stockscreener.pipeline.batch.FullStatementRefillJob;
import com.stockscreener.pipeline.batch.GovernanceDeriveJob;
import com.stockscreener.pipeline.batch.GovernanceLoadJob;
import com.stockscreener.pipeline.batch.IndicatorBackfillJob;
import com.stockscreener.pipeline.batch.IndicatorReconciler;
import com.stockscreener.pipeline.batch.JobFreshnessAlerter;
import com.stockscreener.pipeline.batch.JobFreshnessPolicy;
import com.stockscreener.pipeline.batch.UniverseIntegrityAlerter;
import com.stockscreener.pipeline.batch.UniverseRebalanceJob;

import com.stockscreener.pipeline.lock.WriteLock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
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

    /** 오늘(KST)의 13:30 대상일 — 스케줄러가 쓰는 규약과 같다. */
    private static final LocalDate TARGET_DATE =
            DailyUpdateJob.targetDateFor(LocalDate.now(java.time.ZoneId.of("Asia/Seoul")));

    private DailyUpdateJob dailyUpdateJob;
    private CorporateActionJob corporateActionJob;
    private AdjustmentRebuildJob adjustmentRebuildJob;
    private IndicatorBackfillJob indicatorBackfillJob;
    private GovernanceLoadJob governanceLoadJob;
    private GovernanceDeriveJob governanceDeriveJob;
    private FullStatementRefillJob fullStatementRefillJob;
    private FinancialRefillJob financialRefillJob;
    private JobFreshnessPolicy freshnessPolicy;
    private UniverseRebalanceJob universeRebalanceJob;
    private UniverseIntegrityAlerter universeIntegrityAlerter;
    private ShareBasisDeriver shareBasisDeriver;
    private IndicatorCalendar indicatorCalendar;
    private BatchExecutions batchExecutions;
    private WriteLock writeLock;
    private FilingListingJob filingListingJob;
    private FilingConsumptionJob filingConsumptionJob;
    private PipelineScheduler scheduler;

    @BeforeEach
    void setUp() {
        dailyUpdateJob = mock(DailyUpdateJob.class);
        corporateActionJob = mock(CorporateActionJob.class);
        adjustmentRebuildJob = mock(AdjustmentRebuildJob.class);
        indicatorBackfillJob = mock(IndicatorBackfillJob.class);
        governanceLoadJob = mock(GovernanceLoadJob.class);
        governanceDeriveJob = mock(GovernanceDeriveJob.class);
        fullStatementRefillJob = mock(FullStatementRefillJob.class);
        financialRefillJob = mock(FinancialRefillJob.class);
        freshnessPolicy = mock(JobFreshnessPolicy.class);
        universeRebalanceJob = mock(UniverseRebalanceJob.class);
        universeIntegrityAlerter = mock(UniverseIntegrityAlerter.class);
        shareBasisDeriver = mock(ShareBasisDeriver.class);
        indicatorCalendar = mock(IndicatorCalendar.class);
        batchExecutions = mock(BatchExecutions.class);
        writeLock = WriteLock.inactive("단위 시험");
        filingListingJob = mock(FilingListingJob.class);
        filingConsumptionJob = mock(FilingConsumptionJob.class);
        // 평상시: 13:30이 지표 달력을 대상일까지 늘려 두었다.
        when(indicatorCalendar.latestIndicatorDate(anyCollection())).thenReturn(Optional.of(TARGET_DATE));

        when(indicatorBackfillJob.rewriteOriginLayer(any(), any(), anyList())).thenReturn(BatchStatus.SUCCESS);
        when(indicatorBackfillJob.rebuildDerived(any(), any())).thenReturn(BatchStatus.SUCCESS);

        // MUST: IndicatorReconciler를 목으로 바꾸지 않는다. 「① 실패면 ② 안 돎」·「상한 없음」·「유도가 먼저」를
        // 단언하는 곳이 이 시험인데, 목을 주면 그 단언이 전부 스케줄러 밖으로 나가 죽는다 —
        // 초록은 그대로이고 검증만 사라진다.
        scheduler = new PipelineScheduler(
                dailyUpdateJob, universeRebalanceJob,
                corporateActionJob, adjustmentRebuildJob,
                governanceLoadJob, governanceDeriveJob, fullStatementRefillJob,
                financialRefillJob, mock(JobFreshnessAlerter.class), freshnessPolicy,
                new IndicatorReconciler(indicatorBackfillJob, shareBasisDeriver, governanceDeriveJob,
                        indicatorCalendar),
                universeIntegrityAlerter, batchExecutions, writeLock, filingListingJob, filingConsumptionJob);
    }

    @Test
    @DisplayName("14:16 공시 목록은 자기 크론에서 정기 실행으로 돈다 — 14:15 무거운 잡과 묶으면 그 잡의 예외가 그날 목록을 뺀다")
    void filingListingRunsOnItsOwnCron() {
        scheduler.runFilingListing();

        verify(filingListingJob).run(any(), eq(BatchExecutionRecorder.TriggeredBy.SCHEDULER));
        verifyNoInteractions(fullStatementRefillJob, financialRefillJob, governanceLoadJob);
    }

    @Test
    @DisplayName("14:16은 목록 뒤에 소비를 잇는다 — 같은 날 쌓은 행을 같은 날 먹인다(2차)")
    void consumptionFollowsListing() {
        scheduler.runFilingListing();

        org.mockito.InOrder order = org.mockito.Mockito.inOrder(filingListingJob, filingConsumptionJob);
        order.verify(filingListingJob).run(any(), eq(BatchExecutionRecorder.TriggeredBy.SCHEDULER));
        order.verify(filingConsumptionJob).run(any(), eq(BatchExecutionRecorder.TriggeredBy.SCHEDULER));
    }

    @Test
    @DisplayName("목록이 종료 신호로 끊기면 소비를 시작하지 않는다 — 내려가는 프로세스가 쿼터를 더 태우지 않는다")
    void shutdownDuringListingSkipsConsumption() {
        when(filingListingJob.run(any(), any())).thenThrow(new BatchShutdownException("종료"));

        scheduler.runFilingListing();

        verifyNoInteractions(filingConsumptionJob);
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
    @DisplayName("계수가 안 바뀌면 사슬 안 화해가 없다 — 평상시가 이쪽이고 14:20 화해가 따로 돈다")
    void noReconcileInTheChainWhenNothingWasInvalidated() {
        givenInvalidation();

        scheduler.runCorporateActionChain();

        verifyNoInteractions(indicatorBackfillJob, shareBasisDeriver, governanceDeriveJob);
    }

    @Test
    @DisplayName("무효화가 있으면 사슬 안에서 ① 지운 종목 → ② 창 전체 — 무거운 잡 날 한 시간 공백을 막는다")
    void invalidationIsReconciledInTheChain() {
        givenInvalidation("005440", LocalDate.parse("2020-01-02"));

        scheduler.runCorporateActionChain();

        InOrder order = inOrder(indicatorBackfillJob);
        ArgumentCaptor<LocalDate> asOf = ArgumentCaptor.forClass(LocalDate.class);
        // 좁힌 ① 은 GAP_FILL이다 — SCHEDULER로 남기면 14:20 전량 ① 이 실패해도 사슬이 돈 날마다
        // 그 시계가 되돌아간다(JobFreshnessPolicy의 「스케줄러 실행은 언제나 전량」).
        order.verify(indicatorBackfillJob).rewriteOriginLayer(asOf.capture(),
                eq(BatchExecutionRecorder.TriggeredBy.GAP_FILL), eq(List.of("005440")));
        // ② 는 언제나 창 전체라 SCHEDULER 그대로다.
        order.verify(indicatorBackfillJob).rebuildDerived(eq(asOf.getValue()),
                eq(BatchExecutionRecorder.TriggeredBy.SCHEDULER));
        // MUST: 화해의 asOf는 13:30 일일 갱신의 대상일이다. 다르면 asOf의 주식수를 따라가는 지표가
        // 지금 편입 종목에서 두 판 사이를 매일 오간다.
        assertThat(asOf.getValue())
                .isEqualTo(DailyUpdateJob.targetDateFor(LocalDate.now(java.time.ZoneId.of("Asia/Seoul"))));
        // 옛 손 도구(날짜 루프 백필)는 정기 경로에서 안 부른다.
        verify(indicatorBackfillJob, never()).run(any(), any(), any(), anyList(), any());
    }

    @Test
    @DisplayName("IMPORTANT — 사슬은 입력 유도를 안 부른다 — 유도가 던지면 지운 종목이 화해 없이 한 시간 빈다")
    void chainDoesNotDeriveBeforeReconciling() {
        givenInvalidation("005440", LocalDate.parse("2020-01-02"));
        doThrow(new IllegalStateException("기저 판정 실패")).when(shareBasisDeriver).derive(anyString());

        scheduler.runCorporateActionChain();

        // 유도는 14:18 크론의 몫이다. 여기서 부르면 위 예외가 화해 앞에서 사슬을 끊는다.
        verifyNoInteractions(shareBasisDeriver, governanceDeriveJob);
        verify(indicatorBackfillJob).rebuildDerived(any(), any());
    }

    @Test
    @DisplayName("IMPORTANT — 무효화가 아무리 커도 화해한다 — 상한을 넘으면 지운 채 두던 것이 이 결함이었다")
    void hugeInvalidationIsStillReconciled() {
        List<String> many = java.util.stream.IntStream.range(0, 300).mapToObj(i -> "T" + i).toList();
        when(adjustmentRebuildJob.runAndReport(any(), any(), anyList(), any()))
                .thenReturn(new AdjustmentRebuildJob.Invalidation(
                        BatchStatus.SUCCESS, many, LocalDate.parse("2026-05-01")));

        scheduler.runCorporateActionChain();

        verify(indicatorBackfillJob).rewriteOriginLayer(any(), any(), eq(many));
        verify(indicatorBackfillJob).rebuildDerived(any(), any());
    }

    @Test
    @DisplayName("14:20 화해는 ① 보존 창 전원 → ② 창 전체이고 유도를 안 부른다 — 유도 실패가 화해를 못 막게 크론이 갈렸다")
    void scheduledReconcileCoversEveryoneWithoutDeriving() {
        scheduler.reconcileIndicators();

        InOrder order = inOrder(indicatorBackfillJob);
        ArgumentCaptor<LocalDate> asOf = ArgumentCaptor.forClass(LocalDate.class);
        order.verify(indicatorBackfillJob).rewriteOriginLayer(asOf.capture(),
                eq(BatchExecutionRecorder.TriggeredBy.SCHEDULER), eq(List.of()));
        order.verify(indicatorBackfillJob).rebuildDerived(eq(asOf.getValue()),
                eq(BatchExecutionRecorder.TriggeredBy.SCHEDULER));
        assertThat(asOf.getValue())
                .isEqualTo(DailyUpdateJob.targetDateFor(LocalDate.now(java.time.ZoneId.of("Asia/Seoul"))));
        verifyNoInteractions(shareBasisDeriver, governanceDeriveJob);
    }

    @Test
    @DisplayName("IMPORTANT — 13:30이 안 돈 날은 지표 달력 최신일로 화해한다 — 대상일로 쓰면 몇 종목짜리 날이 화면 최신일이 된다")
    void reconcileNeverRunsAheadOfTheIndicatorCalendar() {
        LocalDate lastFilled = TARGET_DATE.minusDays(1);
        when(indicatorCalendar.latestIndicatorDate(anyCollection())).thenReturn(Optional.of(lastFilled));

        scheduler.reconcileIndicators();

        verify(indicatorBackfillJob).rewriteOriginLayer(eq(lastFilled), any(), eq(List.of()));
        verify(indicatorBackfillJob).rebuildDerived(eq(lastFilled), any());
    }

    @Test
    @DisplayName("14:18 유도는 기저 판정 → 거버넌스 유도(창 편입)이고 지표를 안 건드린다")
    void scheduledDeriveRunsBothInputsOnly() {
        scheduler.deriveReconcileInputs();

        InOrder order = inOrder(shareBasisDeriver, governanceDeriveJob);
        order.verify(shareBasisDeriver).derive("US");
        // 빈 티커 = 기본 모집단(보존 창 편입) — GovernanceDeriveJobTest가 그 갈래를 고정한다.
        order.verify(governanceDeriveJob).run(any(), eq(BatchExecutionRecorder.TriggeredBy.SCHEDULER),
                eq(List.of()));
        verifyNoInteractions(indicatorBackfillJob);
    }

    @Test
    @DisplayName("① 이 FAILED면 ②를 안 돈다 — 잡 전체가 멈춘 날은 다음 평일에 맡긴다")
    void derivedIsSkippedWhenOriginFailed() {
        when(indicatorBackfillJob.rewriteOriginLayer(any(), any(), anyList())).thenReturn(BatchStatus.FAILED);

        scheduler.reconcileIndicators();

        verify(indicatorBackfillJob, never()).rebuildDerived(any(), any());
    }

    @Test
    @DisplayName("① 이 부분 실패여도 ②는 돈다 — 종목 하나의 실패가 전 종목의 과거 순위를 낡게 두면 안 된다")
    void derivedRunsAfterPartialOrigin() {
        when(indicatorBackfillJob.rewriteOriginLayer(any(), any(), anyList()))
                .thenReturn(BatchStatus.PARTIAL_SUCCESS);

        scheduler.reconcileIndicators();

        verify(indicatorBackfillJob).rebuildDerived(any(), any());
    }

    @Test
    @DisplayName("① 이 던지면 ②를 안 돌고 예외가 스케줄러 밖으로 간다 — 삼키면 그날의 실패가 안 보인다")
    void derivedIsSkippedWhenOriginThrows() {
        when(indicatorBackfillJob.rewriteOriginLayer(any(), any(), anyList()))
                .thenThrow(new IllegalStateException("원본층 실패"));

        assertThatThrownBy(() -> scheduler.reconcileIndicators()).isInstanceOf(IllegalStateException.class);
        verify(indicatorBackfillJob, never()).rebuildDerived(any(), any());
    }

    @Test
    @DisplayName("화해·유도가 종료로 중단돼도 스케줄러 밖으로 안 던진다 — @Scheduled 밖으로 나가면 Sentry 이슈가 쌓인다")
    void swallowsShutdownFromReconcile() {
        when(indicatorBackfillJob.rewriteOriginLayer(any(), any(), anyList()))
                .thenThrow(new BatchShutdownException("종료 중이다"));
        doThrow(new BatchShutdownException("종료 중이다")).when(governanceDeriveJob).run(any(), any(), anyList());

        scheduler.reconcileIndicators();
        scheduler.deriveReconcileInputs();
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
    @DisplayName("정기 시도가 같으면(둘 다 없음) 한 번도 성공 못 한 잡을 보정이 먼저 집는다 — 0일로 접으면 영원히 안 돈다")
    void neverRunJobWinsTheOverdueFallback() {
        // daysSinceLastSuccess가 「없음」을 0이 아니라 무한대로 내는 것이 동률 규칙의 전제다. 첫 키(마지막 정기 시도)는
        // 둘 다 없음(목의 null)이라 같다 — 첫 키가 갈리면 그것이 먼저다(leastRecentlyAttemptedOverdueJobGoesFirst).
        when(freshnessPolicy.daysSinceLastSuccess(eq(GovernanceLoadJob.JOB_NAME), any()))
                .thenReturn(Long.MAX_VALUE);
        overdue(FinancialRefillJob.JOB_NAME, 30);
        fresh(FullStatementRefillJob.JOB_NAME);

        // 수요일 잡이 최근에 돌았으므로 보정이 돈다. 그때 60일 밀린 것보다 한 번도 안 돈 것이 먼저다.
        assertThat(scheduler.heavyJobDueToday(DayOfWeek.WEDNESDAY))
                .isEqualTo(GovernanceLoadJob.JOB_NAME);
    }

    @Test
    @DisplayName("연체가 여럿이고 정기 시도가 전부 없으면 마지막 성공에서 오래된 것부터 — 키가 같을 때의 동률 규칙")
    void tieOnNoAttemptsFallsBackToStaleness() {
        overdue(GovernanceLoadJob.JOB_NAME, 30);
        overdue(FullStatementRefillJob.JOB_NAME, 45);
        overdue(FinancialRefillJob.JOB_NAME, 40);

        // 월요일 — 배정이 없다. 셋 다 정기 시도가 없으니(목의 null) 동률이고, 가장 오래 밀린 FSR이다.
        assertThat(scheduler.heavyJobDueToday(DayOfWeek.MONDAY)).isEqualTo(FullStatementRefillJob.JOB_NAME);
    }

    @Test
    @DisplayName("연체가 여럿이면 정기 시도가 가장 오래된(없음이 맨 앞) 것이 먼저다 — 매번 미완료로 끝나는 잡이 차례를 독차지하지 않게")
    void leastRecentlyAttemptedOverdueJobGoesFirst() {
        overdue(GovernanceLoadJob.JOB_NAME, 60);
        overdue(FinancialRefillJob.JOB_NAME, 30);
        fresh(FullStatementRefillJob.JOB_NAME);
        when(freshnessPolicy.lastScheduledAttemptAt(GovernanceLoadJob.JOB_NAME))
                .thenReturn(Instant.parse("2026-10-29T05:15:00Z"));

        assertThat(scheduler.heavyJobDueToday(DayOfWeek.FRIDAY))
                .as("G가 더 오래 밀렸어도 어제 시도했고 FR은 한 번도 시도 못 했다")
                .isEqualTo(FinancialRefillJob.JOB_NAME);

        when(freshnessPolicy.lastScheduledAttemptAt(FinancialRefillJob.JOB_NAME))
                .thenReturn(Instant.parse("2026-10-30T05:15:00Z"));
        assertThat(scheduler.heavyJobDueToday(DayOfWeek.MONDAY))
                .as("FR이 그 뒤에 시도했으면 이번엔 G 차례다")
                .isEqualTo(GovernanceLoadJob.JOB_NAME);
    }

    /**
     * 운영 시계를 재생한다(2026-10-07 운영 SELECT · ADR-0174) — 마지막 성공 G 09-20 16:14:41 · FSR 09-23 00:26:25 ·
     * FR 10-03 21:17:41(KST) · SCHEDULER 행 0. G가 <b>매번 미완료</b>로 끝나 시계를 못 밀고, FSR·FR은 돌면 끝까지 마친다고 놓는다.
     *
     * <p>새 키(마지막 정기 시도)면 10-19 G · 10-20 G · 10-21 FSR · 10-22~29 G · 10-30 FR이다. 대조군(지금까지의 키 = 마지막
     * 성공만 · 시도를 모름)은 G가 늘 가장 오래 밀려 FR이 배정 요일 11-05(목)까지 밀린다 — 그 엿새가 이 고침의 이득이다.
     */
    @Test
    @DisplayName("운영 시계 재생 — G가 매번 미완료여도 FR이 10-30에 돈다(대조: 시도를 모르는 키는 11-05)")
    void replayOfTheOperationalClock() {
        Map<String, LocalDate> picked = replay(true);
        assertThat(picked).containsExactly(
                Map.entry(GovernanceLoadJob.JOB_NAME + "@2026-10-19", LocalDate.of(2026, 10, 19)),
                Map.entry(GovernanceLoadJob.JOB_NAME + "@2026-10-20", LocalDate.of(2026, 10, 20)),
                Map.entry(FullStatementRefillJob.JOB_NAME + "@2026-10-21", LocalDate.of(2026, 10, 21)),
                Map.entry(GovernanceLoadJob.JOB_NAME + "@2026-10-22", LocalDate.of(2026, 10, 22)),
                Map.entry(GovernanceLoadJob.JOB_NAME + "@2026-10-23", LocalDate.of(2026, 10, 23)),
                Map.entry(GovernanceLoadJob.JOB_NAME + "@2026-10-26", LocalDate.of(2026, 10, 26)),
                Map.entry(GovernanceLoadJob.JOB_NAME + "@2026-10-27", LocalDate.of(2026, 10, 27)),
                Map.entry(GovernanceLoadJob.JOB_NAME + "@2026-10-28", LocalDate.of(2026, 10, 28)),
                Map.entry(GovernanceLoadJob.JOB_NAME + "@2026-10-29", LocalDate.of(2026, 10, 29)),
                Map.entry(FinancialRefillJob.JOB_NAME + "@2026-10-30", LocalDate.of(2026, 10, 30)));

        Map<String, LocalDate> control = replay(false);
        assertThat(control.entrySet().stream()
                .filter(entry -> entry.getKey().startsWith(FinancialRefillJob.JOB_NAME))
                .map(Map.Entry::getValue).findFirst())
                .as("대조군 — 시도를 모르는 키는 FR을 배정 요일까지 미룬다")
                .contains(LocalDate.of(2026, 11, 5));
    }

    /**
     * 10-19~11-05 평일 14:15(KST)마다 {@code heavyJobDueToday}를 부르고 그 결과로 시계를 민다. FR을 처음 고른 날에서 멈춘다.
     *
     * @param knowsAttempts 거짓이면 정기 시도를 늘 「없음」으로 답한다 — 지금까지의 키와 같은 순서를 낸다
     */
    private Map<String, LocalDate> replay(boolean knowsAttempts) {
        java.time.ZoneId kst = java.time.ZoneId.of("Asia/Seoul");
        Map<String, Instant> lastSuccess = new java.util.HashMap<>(Map.of(
                GovernanceLoadJob.JOB_NAME, LocalDateTime.parse("2026-09-20T16:14:41").atZone(kst).toInstant(),
                FullStatementRefillJob.JOB_NAME, LocalDateTime.parse("2026-09-23T00:26:25").atZone(kst).toInstant(),
                FinancialRefillJob.JOB_NAME, LocalDateTime.parse("2026-10-03T21:17:41").atZone(kst).toInstant()));
        Map<String, Instant> lastAttempt = new java.util.HashMap<>();
        Instant[] now = new Instant[1];
        JobFreshnessPolicy policy = mock(JobFreshnessPolicy.class);
        when(policy.daysSinceLastSuccess(anyString(), any())).thenAnswer(call ->
                Duration.between(lastSuccess.get(call.<String>getArgument(0)), now[0]).toDays());
        when(policy.lastScheduledAttemptAt(anyString())).thenAnswer(call ->
                knowsAttempts ? lastAttempt.get(call.<String>getArgument(0)) : null);
        PipelineScheduler replayed = new PipelineScheduler(
                dailyUpdateJob, universeRebalanceJob, corporateActionJob, adjustmentRebuildJob,
                governanceLoadJob, governanceDeriveJob, fullStatementRefillJob, financialRefillJob,
                mock(JobFreshnessAlerter.class), policy,
                new IndicatorReconciler(indicatorBackfillJob, shareBasisDeriver, governanceDeriveJob, indicatorCalendar),
                universeIntegrityAlerter, batchExecutions, WriteLock.inactive("단위 시험"), mock(FilingListingJob.class),
                mock(FilingConsumptionJob.class));

        Map<String, LocalDate> picked = new java.util.LinkedHashMap<>();
        for (LocalDate day = LocalDate.of(2026, 10, 19); !day.isAfter(LocalDate.of(2026, 11, 5));
             day = day.plusDays(1)) {
            if (day.getDayOfWeek() == DayOfWeek.SATURDAY || day.getDayOfWeek() == DayOfWeek.SUNDAY) {
                continue;
            }
            now[0] = day.atTime(14, 15).atZone(kst).toInstant();
            String due = replayed.heavyJobDueToday(day.getDayOfWeek());
            if (due == null) {
                continue;
            }
            picked.put(due + "@" + day, day);
            lastAttempt.put(due, now[0]);
            // G는 매번 시장 미완료로 끝나 시계를 못 민다 — 나머지는 끝까지 마친다.
            if (!GovernanceLoadJob.JOB_NAME.equals(due)) {
                lastSuccess.put(due, now[0]);
            }
            if (FinancialRefillJob.JOB_NAME.equals(due)) {
                break;
            }
        }
        return picked;
    }

    @Test
    @DisplayName("잡이 실행 행을 만들기 전에 던지면 FAILED 시도 행을 「시작 실패」로 남기고 다시 던진다 — 순서의 키가 멈추지 않게")
    void startFailureLeavesAnAttemptRow() {
        overdue(GovernanceLoadJob.JOB_NAME, 40);
        fresh(FullStatementRefillJob.JOB_NAME, FinancialRefillJob.JOB_NAME);
        IllegalStateException boom = new IllegalStateException("대상 해석 실패");
        doThrow(boom).when(governanceLoadJob).run(any(), any(), any(), any(), anyList(), anyList());
        BatchExecutionContext attempt = mock(BatchExecutionContext.class);
        when(batchExecutions.start(eq(GovernanceLoadJob.JOB_NAME), any(),
                eq(BatchExecutionRecorder.TriggeredBy.SCHEDULER), eq(0),
                eq(BatchExecutionRecorder.DeclaredScope.FULL))).thenReturn(attempt);

        assertThatThrownBy(() -> scheduler.runHeavyJobOfTheDay()).isSameAs(boom);

        verify(attempt).failUnexpectedly("시작 실패", boom);
    }

    /**
     * 쓰기 잠금을 안 쥐는 크론과 그 사유. 15:30 신선도 점검은 읽기와 알림뿐이다(ADR-0175 — 쥐면 그 자체가 크론 사이 틈의 손 쓰기를
     * 막는 쓰기 창이 된다).
     */
    private static final java.util.Set<String> READ_ONLY_CRONS = java.util.Set.of("checkJobFreshness");

    @Test
    @DisplayName("잠금을 못 쥐고 끝난 발화(종료 신호): 잡도 시도 행도 「마지막 정기 시도」 조회도 없다 — 시도가 아니다(ADR-0175)")
    @org.junit.jupiter.api.extension.ExtendWith(org.springframework.boot.test.system.OutputCaptureExtension.class)
    void skippedForTheWriteLockLeavesNoAttempt(org.springframework.boot.test.system.CapturedOutput output) {
        overdue(GovernanceLoadJob.JOB_NAME, 40);
        fresh(FullStatementRefillJob.JOB_NAME, FinancialRefillJob.JOB_NAME);
        WriteLock refusing = mock(WriteLock.class);
        when(refusing.awaitForCron(anyString())).thenReturn(Optional.empty());
        FilingListingJob blockedListing = mock(FilingListingJob.class);
        FilingConsumptionJob blockedConsumption = mock(FilingConsumptionJob.class);
        PipelineScheduler blocked = new PipelineScheduler(
                dailyUpdateJob, universeRebalanceJob, corporateActionJob, adjustmentRebuildJob,
                governanceLoadJob, governanceDeriveJob, fullStatementRefillJob, financialRefillJob,
                mock(JobFreshnessAlerter.class), freshnessPolicy,
                new IndicatorReconciler(indicatorBackfillJob, shareBasisDeriver, governanceDeriveJob, indicatorCalendar),
                universeIntegrityAlerter, batchExecutions, refusing, blockedListing, blockedConsumption);

        // 성질로 센다(잠금 결정 7) — 손으로 든 목록이면 새 쓰기 크론이 잠금 없이 붙어도 이 시험이 못 본다.
        List<String> writeCrons = new java.util.ArrayList<>();
        for (java.lang.reflect.Method method : PipelineScheduler.class.getDeclaredMethods()) {
            if (method.isAnnotationPresent(org.springframework.scheduling.annotation.Scheduled.class)
                    && !READ_ONLY_CRONS.contains(method.getName())) {
                writeCrons.add(method.getName());
            }
        }
        assertThat(writeCrons).as("쓰기 크론 — 15:30 점검 밖 전부(14:16 공시 목록 포함)").hasSize(7);
        for (String cron : writeCrons) {
            try {
                PipelineScheduler.class.getMethod(cron).invoke(blocked);
            } catch (ReflectiveOperationException e) {
                throw new AssertionError(cron + "을 부르지 못했다", e);
            }
        }

        // 획득이 바깥 try 맨 앞이라 attemptBefore 조회보다 먼저 끝난다 — 건너뜀이 「시도」로 세어지지 않는다.
        verify(freshnessPolicy, never()).lastScheduledAttemptAt(anyString());
        verifyNoInteractions(batchExecutions, governanceLoadJob, dailyUpdateJob, corporateActionJob,
                adjustmentRebuildJob, indicatorBackfillJob, universeRebalanceJob, blockedListing, blockedConsumption);
        for (String cron : writeCrons) {
            verify(refusing).awaitForCron(cron);
            // 끝 줄은 못 돈 발화에도 찍힌다 — 없으면 ops/crons-done.sh가 그날을 「남은 발화」로 막아 배포·종료가 서지 않는다.
            assertThat(output.getAll()).as(cron + "의 끝 줄").contains("크론 발화를 마쳤다. 크론=" + cron);
        }
    }

    @Test
    @DisplayName("잠금 표 실패로 대기가 던지면 크론도 던진다 — 시작 실패 행을 안 쓰고(토큰이 없다) 끝 줄은 찍는다")
    @org.junit.jupiter.api.extension.ExtendWith(org.springframework.boot.test.system.OutputCaptureExtension.class)
    void lockStoreFailurePropagatesWithoutAnAttemptRow(org.springframework.boot.test.system.CapturedOutput output) {
        overdue(GovernanceLoadJob.JOB_NAME, 40);
        fresh(FullStatementRefillJob.JOB_NAME, FinancialRefillJob.JOB_NAME);
        WriteLock failing = mock(WriteLock.class);
        IllegalStateException boom = new IllegalStateException("잠금 표 실패");
        when(failing.awaitForCron(anyString())).thenThrow(boom);
        PipelineScheduler broken = new PipelineScheduler(
                dailyUpdateJob, universeRebalanceJob, corporateActionJob, adjustmentRebuildJob,
                governanceLoadJob, governanceDeriveJob, fullStatementRefillJob, financialRefillJob,
                mock(JobFreshnessAlerter.class), freshnessPolicy,
                new IndicatorReconciler(indicatorBackfillJob, shareBasisDeriver, governanceDeriveJob, indicatorCalendar),
                universeIntegrityAlerter, batchExecutions, failing, mock(FilingListingJob.class),
                mock(FilingConsumptionJob.class));

        assertThatThrownBy(broken::runHeavyJobOfTheDay).isSameAs(boom);

        verifyNoInteractions(batchExecutions, governanceLoadJob);
        verify(freshnessPolicy, never()).lastScheduledAttemptAt(anyString());
        assertThat(output.getAll()).contains("크론 발화를 마쳤다. 크론=runHeavyJobOfTheDay");
    }

    @Test
    @DisplayName("잡이 행을 만든 뒤 던지면 시도 행을 또 안 만든다 — 그 행은 잡이 failUnexpectedly로 이미 마감했다")
    void failureAfterStartDoesNotAddAnotherRow() {
        overdue(GovernanceLoadJob.JOB_NAME, 40);
        fresh(FullStatementRefillJob.JOB_NAME, FinancialRefillJob.JOB_NAME);
        IllegalStateException boom = new IllegalStateException("도중 실패");
        doThrow(boom).when(governanceLoadJob).run(any(), any(), any(), any(), anyList(), anyList());
        // 잡이 행을 만들었다 — 마지막 정기 시도가 부르기 전과 뒤에 다르다.
        when(freshnessPolicy.lastScheduledAttemptAt(GovernanceLoadJob.JOB_NAME))
                .thenReturn(null, Instant.parse("2026-10-20T05:15:00Z"));

        assertThatThrownBy(() -> scheduler.runHeavyJobOfTheDay()).isSameAs(boom);

        verifyNoInteractions(batchExecutions);
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
    @DisplayName("거버넌스 무거운 잡은 적재만 한다 — 유도는 14:18이 매 평일 창 편입 전원으로 돈다")
    void governanceLoadLeavesDerivingTo1418() {
        overdue(GovernanceLoadJob.JOB_NAME, 40);
        fresh(FullStatementRefillJob.JOB_NAME, FinancialRefillJob.JOB_NAME);

        scheduler.runHeavyJobOfTheDay();

        // MUST: span을 주지 않는다(넷째 인자 null). 표마다 필요한 해 수가 다른데 스칼라 하나로
        // 덮으면 SHARE_TOTALS의 3년 차분과 OUTSIDE_DIRECTOR의 3년 합이 원리적으로 불성립한다.
        //
        // 여기 있던 eq(2)는 「회계연도가 역년 둘에 걸치니 두 해를 받는다」가 근거였는데,
        // 그 근거가 2026-09-20에 무너졌다 — 52/53주 회계연도는 **세 역년**에 걸치고
        // (SNA FY2025 = 2024-12-29~2026-01-03) 막고 있던 것은 span이 아니라 요청 역년의
        // **위쪽 끝**이었다. 뒤로 넓히는 것은 실측 +1종목, 위로 올리는 것은 +158이었다.
        // 그 시험이 오해를 잠가 두었으므로 주석까지 함께 고친다.
        verify(governanceLoadJob).run(any(), eq(BatchExecutionRecorder.TriggeredBy.SCHEDULER),
                eq(null), eq(null), anyList(), anyList());
        // 적재 뒤 유도가 빠지면 화면이 옛 값 그대로인데 둘 다 성공한다 — 그 연결은 이제 14:18 크론이다
        // (scheduledDeriveRunsBothInputsOnly · PipelineSchedulerTest의 순서 시험).
        verifyNoInteractions(governanceDeriveJob, fullStatementRefillJob, financialRefillJob);
    }

    @Test
    @DisplayName("재무 정정이 있어도 무거운 잡 안에서 백필하지 않는다 — 14:20 화해가 상한 없이 채운다")
    void financialRefillLeavesBackfillTo1420() {
        overdue(FinancialRefillJob.JOB_NAME, 40);
        fresh(GovernanceLoadJob.JOB_NAME, FullStatementRefillJob.JOB_NAME);
        when(financialRefillJob.runAndReport(any(), any(), any(), anyList(), any()))
                .thenReturn(new FinancialRefillJob.Invalidation(
                        BatchStatus.SUCCESS, List.of("005930", "000660")));

        scheduler.runHeavyJobOfTheDay();

        verify(financialRefillJob).runAndReport(any(), eq(BatchExecutionRecorder.TriggeredBy.SCHEDULER),
                eq(null), anyList(), eq(null));
        // 정정 반영은 그 종목의 지표를 지운다. 채우는 것과 주식수 기저 재판정은 14:18·14:20이 한다.
        verifyNoInteractions(indicatorBackfillJob, shareBasisDeriver);
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
    @DisplayName("15시 30분 점검이 편입 원자료도 잰다 — 지표 축은 일일 갱신 뒤에만 뜻이 있다")
    void freshnessCheckAlsoMeasuresMembership() {
        scheduler.checkJobFreshness();

        verify(universeIntegrityAlerter).evaluateAndAlert();
    }

    @Test
    @DisplayName("정기 호출은 전부 FULL로 찍힌다 — NARROW로 찍히면 그 SCHEDULER 행이 시계에서 빠져 같은 잡이 매 평일 다시 돈다")
    @SuppressWarnings("unchecked")
    void everyScheduledCallIsFullScope() {
        // 스케줄러가 실제로 넘기는 인자를 잡아 잡의 범위 규칙에 그대로 먹인다 — 인자를 손으로 옮겨 적으면 스케줄러가 바뀌어도
        // 이 시험이 초록이다. 기준일 규칙은 기록 계층이 모든 잡에 같게 더하므로 실제 시계로 함께 묻는다.
        BatchExecutionRecorder recorder = new BatchExecutionRecorder(null, java.time.Clock.systemUTC());
        java.util.function.Consumer<LocalDate> current = asOf -> assertThat(recorder.recordedScope(
                "scheduled", asOf, BatchExecutionRecorder.TriggeredBy.SCHEDULER, BatchExecutionRecorder.DeclaredScope.FULL))
                .as("정기 호출의 기준일 %s가 「지금」이다", asOf)
                .isEqualTo(BatchExecutionRecorder.DeclaredScope.FULL);
        BatchExecutionRecorder.TriggeredBy scheduled = BatchExecutionRecorder.TriggeredBy.SCHEDULER;

        // 13:30
        scheduler.runDailyUpdate();
        ArgumentCaptor<LocalDate> dailyDate = ArgumentCaptor.forClass(LocalDate.class);
        verify(dailyUpdateJob).run(dailyDate.capture(), eq(scheduled));
        current.accept(dailyDate.getValue());

        // 14:00 사슬 — 무효화가 있는 날(사슬 안 화해까지 탄다)
        givenInvalidation("005440", LocalDate.parse("2020-01-02"));
        scheduler.runCorporateActionChain();
        ArgumentCaptor<LocalDate> caDate = ArgumentCaptor.forClass(LocalDate.class);
        ArgumentCaptor<LocalDate> caFrom = ArgumentCaptor.forClass(LocalDate.class);
        ArgumentCaptor<List<String>> caTickers = ArgumentCaptor.forClass(List.class);
        ArgumentCaptor<String> caUniverse = ArgumentCaptor.forClass(String.class);
        verify(corporateActionJob).run(caDate.capture(), eq(scheduled), caFrom.capture(), caTickers.capture(),
                caUniverse.capture());
        assertThat(CorporateActionJob.scopeOf(caFrom.getValue(), caTickers.getValue(), caUniverse.getValue()))
                .isEqualTo(BatchExecutionRecorder.DeclaredScope.FULL);
        current.accept(caDate.getValue());
        ArgumentCaptor<LocalDate> adjDate = ArgumentCaptor.forClass(LocalDate.class);
        ArgumentCaptor<List<String>> adjTickers = ArgumentCaptor.forClass(List.class);
        ArgumentCaptor<String> adjUniverse = ArgumentCaptor.forClass(String.class);
        verify(adjustmentRebuildJob).runAndReport(adjDate.capture(), eq(scheduled), adjTickers.capture(),
                adjUniverse.capture());
        assertThat(AdjustmentRebuildJob.scopeOf(adjTickers.getValue(), adjUniverse.getValue()))
                .isEqualTo(BatchExecutionRecorder.DeclaredScope.FULL);
        current.accept(adjDate.getValue());

        // 14:15 무거운 잡 셋 — 하루에 하나라 연체를 하나씩 바꿔 가며 세 번 부른다
        when(financialRefillJob.runAndReport(any(), any(), any(), anyList(), any()))
                .thenReturn(new FinancialRefillJob.Invalidation(BatchStatus.SUCCESS, List.of()));
        for (String heavy : List.of(GovernanceLoadJob.JOB_NAME, FullStatementRefillJob.JOB_NAME,
                FinancialRefillJob.JOB_NAME)) {
            fresh(GovernanceLoadJob.JOB_NAME, FullStatementRefillJob.JOB_NAME, FinancialRefillJob.JOB_NAME);
            overdue(heavy, 40);
            scheduler.runHeavyJobOfTheDay();
        }
        ArgumentCaptor<LocalDate> gDate = ArgumentCaptor.forClass(LocalDate.class);
        ArgumentCaptor<Integer> gYear = ArgumentCaptor.forClass(Integer.class);
        ArgumentCaptor<Integer> gSpan = ArgumentCaptor.forClass(Integer.class);
        ArgumentCaptor<List<com.stockscreener.pipeline.datasource.GovernanceEndpoint>> gEndpoints =
                ArgumentCaptor.forClass(List.class);
        ArgumentCaptor<List<String>> gTickers = ArgumentCaptor.forClass(List.class);
        verify(governanceLoadJob).run(gDate.capture(), eq(scheduled), gYear.capture(), gSpan.capture(),
                gEndpoints.capture(), gTickers.capture());
        assertThat(GovernanceLoadJob.scopeOf(gYear.getValue(), gSpan.getValue(), gEndpoints.getValue(),
                gTickers.getValue())).isEqualTo(BatchExecutionRecorder.DeclaredScope.FULL);
        current.accept(gDate.getValue());
        ArgumentCaptor<LocalDate> fsrDate = ArgumentCaptor.forClass(LocalDate.class);
        ArgumentCaptor<Integer> fsrYears = ArgumentCaptor.forClass(Integer.class);
        ArgumentCaptor<List<String>> fsrTickers = ArgumentCaptor.forClass(List.class);
        ArgumentCaptor<Boolean> fsrAllQuarters = ArgumentCaptor.forClass(Boolean.class);
        verify(fullStatementRefillJob).run(fsrDate.capture(), eq(scheduled), fsrYears.capture(),
                fsrTickers.capture(), any(Boolean.class), fsrAllQuarters.capture());
        assertThat(FullStatementRefillJob.scopeOf(fsrYears.getValue(), fsrTickers.getValue(),
                fsrAllQuarters.getValue())).isEqualTo(BatchExecutionRecorder.DeclaredScope.FULL);
        current.accept(fsrDate.getValue());
        ArgumentCaptor<LocalDate> frDate = ArgumentCaptor.forClass(LocalDate.class);
        ArgumentCaptor<Integer> frYears = ArgumentCaptor.forClass(Integer.class);
        ArgumentCaptor<List<String>> frTickers = ArgumentCaptor.forClass(List.class);
        ArgumentCaptor<String> frUniverse = ArgumentCaptor.forClass(String.class);
        verify(financialRefillJob).runAndReport(frDate.capture(), eq(scheduled), frYears.capture(),
                frTickers.capture(), frUniverse.capture());
        assertThat(FinancialRefillJob.scopeOf(frYears.getValue(), frTickers.getValue(), frUniverse.getValue()))
                .isEqualTo(BatchExecutionRecorder.DeclaredScope.FULL);
        current.accept(frDate.getValue());

        // 14:18 · 14:20
        scheduler.deriveReconcileInputs();
        scheduler.reconcileIndicators();
        ArgumentCaptor<LocalDate> deriveDate = ArgumentCaptor.forClass(LocalDate.class);
        ArgumentCaptor<List<String>> deriveTickers = ArgumentCaptor.forClass(List.class);
        verify(governanceDeriveJob).run(deriveDate.capture(), eq(scheduled), deriveTickers.capture());
        assertThat(GovernanceDeriveJob.scopeOf(deriveTickers.getValue())).isEqualTo(BatchExecutionRecorder.DeclaredScope.FULL);
        current.accept(deriveDate.getValue());
        // ① 은 사슬 안(GAP_FILL · 좁힘)과 14:20(SCHEDULER · 전원) 두 번 불렸다. SCHEDULER로 찍히는 것만 FULL이어야 한다.
        ArgumentCaptor<LocalDate> originDate = ArgumentCaptor.forClass(LocalDate.class);
        ArgumentCaptor<BatchExecutionRecorder.TriggeredBy> originTrigger =
                ArgumentCaptor.forClass(BatchExecutionRecorder.TriggeredBy.class);
        ArgumentCaptor<List<String>> originTickers = ArgumentCaptor.forClass(List.class);
        verify(indicatorBackfillJob, org.mockito.Mockito.times(2)).rewriteOriginLayer(originDate.capture(),
                originTrigger.capture(), originTickers.capture());
        for (int i = 0; i < originTrigger.getAllValues().size(); i++) {
            if (originTrigger.getAllValues().get(i) == scheduled) {
                assertThat(IndicatorBackfillJob.originScopeOf(originTickers.getAllValues().get(i)))
                        .isEqualTo(BatchExecutionRecorder.DeclaredScope.FULL);
                current.accept(originDate.getAllValues().get(i));
            }
        }
        assertThat(originTrigger.getAllValues()).contains(scheduled);
        ArgumentCaptor<LocalDate> derivedDate = ArgumentCaptor.forClass(LocalDate.class);
        verify(indicatorBackfillJob, org.mockito.Mockito.times(2)).rebuildDerived(derivedDate.capture(),
                eq(scheduled));
        derivedDate.getAllValues().forEach(current);

        // 토요일 리밸런싱 — 지수를 고르지 않는 겹이다(꼬리의 업종 호출은 MembershipGapFillerTest).
        scheduler.runUniverseRebalance();
        ArgumentCaptor<LocalDate> rebalanceDate = ArgumentCaptor.forClass(LocalDate.class);
        verify(universeRebalanceJob).run(rebalanceDate.capture(), eq(scheduled));
        current.accept(rebalanceDate.getValue());
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
