package com.stockscreener.pipeline.batch;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.core.task.SimpleAsyncTaskExecutor;

import java.time.LocalDate;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 같은 잡이 겹쳐 돌지 않는가, 그리고 기동이 잡을 기다리지 않는가.
 *
 * <p>{@code docs/phase-criteria.md}의 "잡을 화면에서 띄울 수 있고, 같은 잡이 겹쳐 돌지 않는다"에 대응한다. 판정의
 * 정본이 인프로세스 가드이므로(ADR-0029) DB 없이 여기서 전부 확인된다.
 */
class AdminJobLauncherTest {

    private final DailyUpdateJob dailyUpdateJob = mock(DailyUpdateJob.class);
    private final UniverseRebalanceJob universeRebalanceJob = mock(UniverseRebalanceJob.class);
    private final IndicatorBackfillJob indicatorBackfillJob = mock(IndicatorBackfillJob.class);
    private final CompanyProfileJob companyProfileJob = mock(CompanyProfileJob.class);
    private final CorporateActionJob corporateActionJob = mock(CorporateActionJob.class);

    private final AdminJobLauncher launcher = new AdminJobLauncher(
            new SimpleAsyncTaskExecutor(), dailyUpdateJob, universeRebalanceJob,
            indicatorBackfillJob, companyProfileJob, corporateActionJob);

    @Test
    @DisplayName("도는 중인 잡은 새로 띄우지 않는다")
    void doesNotLaunchWhileRunning() throws Exception {
        CountDownLatch hold = blockDailyUpdate();

        assertThat(launcher.launch(DailyUpdateJob.JOB_NAME, AdminJobLauncher.JobRequest.empty())).isTrue();
        await().atMost(2, TimeUnit.SECONDS).until(() -> launcher.isRunning(DailyUpdateJob.JOB_NAME));

        assertThat(launcher.launch(DailyUpdateJob.JOB_NAME, AdminJobLauncher.JobRequest.empty()))
                .as("이미 도는 중인데 두 번째 기동이 통과했다")
                .isFalse();

        hold.countDown();
        await().atMost(2, TimeUnit.SECONDS).until(() -> !launcher.isRunning(DailyUpdateJob.JOB_NAME));
    }

    @Test
    @DisplayName("기동은 잡을 기다리지 않는다 — 850종목 수집이 HTTP 응답을 붙잡으면 안 된다")
    void launchReturnsBeforeJobFinishes() throws Exception {
        CountDownLatch hold = blockDailyUpdate();

        launcher.launch(DailyUpdateJob.JOB_NAME, AdminJobLauncher.JobRequest.empty());

        // 잡은 아직 잡혀 있는데 호출은 이미 돌아왔다. 동기로 돌았다면 여기 도달하지 못한다.
        assertThat(hold.getCount()).isEqualTo(1);
        hold.countDown();
    }

    @Test
    @DisplayName("잡이 끝나면 다시 띄울 수 있다")
    void nameIsReleasedAfterCompletion() {
        when(universeRebalanceJob.run(any(), any())).thenReturn(BatchStatus.SUCCESS);

        assertThat(launcher.launch(UniverseRebalanceJob.JOB_NAME, AdminJobLauncher.JobRequest.empty()))
                .isTrue();
        await().atMost(2, TimeUnit.SECONDS)
                .until(() -> !launcher.isRunning(UniverseRebalanceJob.JOB_NAME));

        assertThat(launcher.launch(UniverseRebalanceJob.JOB_NAME, AdminJobLauncher.JobRequest.empty()))
                .isTrue();
    }

    @Test
    @DisplayName("IMPORTANT — 잡이 터져도 이름이 풀리고, 예외는 스레드 경계에서 로그로 남는다")
    void nameIsReleasedWhenJobThrows() {
        when(universeRebalanceJob.run(any(), any())).thenThrow(new IllegalStateException("터짐"));

        ListAppender<ILoggingEvent> logs = new ListAppender<>();
        logs.start();
        Logger launcherLogger = (Logger) LoggerFactory.getLogger(AdminJobLauncher.class);
        launcherLogger.addAppender(logs);

        try {
            launcher.launch(UniverseRebalanceJob.JOB_NAME, AdminJobLauncher.JobRequest.empty());

            // 화면에는 아무것도 도는 중이 아닌 것으로 보이는데 기동만 계속 거절당하는 상태가
            // 되어서는 안 된다. 그 상태의 복구 수단은 애플리케이션 재시작뿐이다.
            await().atMost(2, TimeUnit.SECONDS)
                    .until(() -> !launcher.isRunning(UniverseRebalanceJob.JOB_NAME));

            // 제출한 뒤에는 예외를 받을 호출부가 없다. 잡지 않으면 워커 스레드의 기본 처리로
            // 넘어가 어디에도 남지 않는다 — 배치가 통째로 죽은 사실이 증발한다.
            await().atMost(2, TimeUnit.SECONDS).until(() -> logs.list.stream()
                    .anyMatch(event -> event.getLevel() == Level.ERROR));
            assertThat(logs.list)
                    .filteredOn(event -> event.getLevel() == Level.ERROR)
                    .singleElement()
                    .satisfies(event -> assertThat(event.getThrowableProxy()).isNotNull());
        } finally {
            launcherLogger.detachAppender(logs);
        }
    }

    @Test
    @DisplayName("다른 잡은 동시에 돌 수 있다 — 가드는 잡 이름 단위다")
    void differentJobsRunConcurrently() throws Exception {
        CountDownLatch hold = blockDailyUpdate();
        when(universeRebalanceJob.run(any(), any())).thenReturn(BatchStatus.SUCCESS);

        launcher.launch(DailyUpdateJob.JOB_NAME, AdminJobLauncher.JobRequest.empty());
        await().atMost(2, TimeUnit.SECONDS).until(() -> launcher.isRunning(DailyUpdateJob.JOB_NAME));

        assertThat(launcher.launch(UniverseRebalanceJob.JOB_NAME, AdminJobLauncher.JobRequest.empty()))
                .isTrue();

        hold.countDown();
    }

    @Test
    @DisplayName("IMPORTANT — 초기 적재는 목록에 없다. 버튼 하나가 DART 일일 한도의 절반을 태운다")
    void initialLoadIsNotLaunchable() {
        assertThat(launcher.isLaunchable(InitialLoadJob.JOB_NAME)).isFalse();
        assertThat(launcher.catalog()).extracting(AdminJobLauncher.Descriptor::name)
                .as("목록에 잡을 더하는 것은 화면에 버튼을 하나 더하는 것이다. "
                        + "쿼터를 얼마나 태우는지 먼저 답하고 여기 적는다")
                .containsExactlyInAnyOrder(
                        DailyUpdateJob.JOB_NAME,
                        UniverseRebalanceJob.JOB_NAME,
                        IndicatorBackfillJob.JOB_NAME,
                        // 대상이 업종 결손 종목뿐이라 평소 0콜, 전량이어도 종목당 1콜(350).
                        CompanyProfileJob.JOB_NAME,
                        // 전 구간이면 종목당 2콜(350종목 = 700). DART 일일 한도의 3.5%다 —
                        // initial-load를 뺀 근거(한 번에 절반)가 여기엔 없다.
                        CorporateActionJob.JOB_NAME);

        assertThatThrownBy(() ->
                launcher.launch(InitialLoadJob.JOB_NAME, AdminJobLauncher.JobRequest.empty()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("부분 재실행은 실패 단위가 종목인 잡만 지원한다")
    void onlyDailyUpdateSupportsRetry() {
        assertThat(AdminJobLauncher.supportsRetry(DailyUpdateJob.JOB_NAME)).isTrue();
        assertThat(AdminJobLauncher.supportsRetry(UniverseRebalanceJob.JOB_NAME)).isFalse();
        assertThat(AdminJobLauncher.supportsRetry(IndicatorBackfillJob.JOB_NAME)).isFalse();
        // 기업 행위 수집의 실패 단위는 종목이지만 재실행 경로는 daily-update만 지원한다.
        // 여기가 참으로 바뀌면 relaunchFailures가 엉뚱한 잡을 띄운다 — 그 메서드는 잡 이름을
        // 안 보고 dailyUpdateJob을 부른다.
        assertThat(AdminJobLauncher.supportsRetry(CorporateActionJob.JOB_NAME)).isFalse();
    }

    @Test
    @DisplayName("파라미터를 비우면 잡마다 정한 기본값으로 돈다")
    void emptyParametersFallBackToDefaults() {
        AtomicInteger calls = new AtomicInteger();
        when(indicatorBackfillJob.run(any(), any(), any())).thenAnswer(invocation -> {
            assertThat((LocalDate) invocation.getArgument(0)).isNotNull();
            assertThat((LocalDate) invocation.getArgument(1)).isNotNull();
            calls.incrementAndGet();
            return BatchStatus.SUCCESS;
        });

        launcher.launch(IndicatorBackfillJob.JOB_NAME, AdminJobLauncher.JobRequest.empty());

        await().atMost(2, TimeUnit.SECONDS).until(() -> calls.get() == 1);
    }

    /** 일일 갱신을 잡아 둔다. 반환된 래치를 내려야 끝난다. */
    private CountDownLatch blockDailyUpdate() {
        CountDownLatch hold = new CountDownLatch(1);
        when(dailyUpdateJob.run(any(), any())).thenAnswer(invocation -> {
            hold.await(2, TimeUnit.SECONDS);
            return BatchStatus.SUCCESS;
        });
        return hold;
    }
}
