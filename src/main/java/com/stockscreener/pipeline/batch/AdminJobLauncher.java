package com.stockscreener.pipeline.batch;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.task.TaskExecutionAutoConfiguration;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * 관리자가 화면에서 띄우는 잡.
 *
 * <p>세 가지를 여기서 정한다 — <b>무엇을 띄울 수 있는가</b>, <b>겹쳐 돌지 않는가</b>,
 * <b>HTTP 응답이 잡을 기다리지 않는가</b>(ADR-0029).
 *
 * <p>IMPORTANT — {@code InitialLoadJob}은 목록에 없다. 재무 초기 적재가 DART 9,800콜로 일일
 * 한도의 절반을 한 번에 태우므로, 화면의 버튼 하나로 두면 오조작 한 번이 그날 쿼터를 날린다.
 * 그 경로는 {@code BootstrapRunner}({@code --job=initial-load})에 그대로 남는다.
 *
 * <p>{@code @Async}를 쓰지 않고 {@link AsyncTaskExecutor}에 직접 제출한다. 자기 호출이
 * 프록시를 거치지 않는 함정을 다시 들이지 않기 위해서다 — ADR-0007이 같은 이유로
 * {@code StockTaskRunner}를 분리했고, 그 함정은 <b>동기로 조용히 도는 것</b>으로 나타나
 * 테스트로는 잘 드러나지 않는다.
 */
@Component
public class AdminJobLauncher {

    private static final Logger log = LoggerFactory.getLogger(AdminJobLauncher.class);

    /**
     * 지금 도는 중인 잡 이름.
     *
     * <p>IMPORTANT — 단일 실행 판정의 <b>정본이 이것이고 DB의 RUNNING 행이 아니다.</b>
     * 애플리케이션이 잡 도중에 죽으면 그 행은 RUNNING으로 남는데, DB를 근거로 삼으면 그 잡을
     * 다시는 띄울 수 없게 된다. 인스턴스가 하나라는 전제 위에 서 있고, 그 전제는 ADR-0025가
     * 이미 명시했다.
     *
     * <p>남는 RUNNING 행 자체는 이제 {@link BatchExecutions}의 종료 훅과
     * {@link BatchExecutionSweeper}가 닫는다 — <b>다만 그 둘도 이 판정의 근거는 아니다.</b>
     * 행을 닫는 것과 "지금 도는가"를 답하는 것은 다른 질문이고, 둘을 겸하게 하면 위 문단의
     * 이유로 되돌아간다.
     *
     * <p>MUST NOT: 이것으로 <b>다른 JVM</b>의 잡을 막을 수 있다고 가정하지 않는다.
     * {@code BootstrapRunner}는 별도 프로세스로 뜨고 이 집합을 아예 거치지 않는다.
     */
    private final Set<String> running = ConcurrentHashMap.newKeySet();

    private final AsyncTaskExecutor taskExecutor;
    private final DailyUpdateJob dailyUpdateJob;
    private final Map<String, JobRunner> jobs;
    private final List<Descriptor> catalog;

    /**
     * IMPORTANT — {@code @Qualifier}를 지우지 않는다. 스케줄러를 켜면
     * ({@code pipeline.scheduler.enabled=true}) Boot이 {@code taskScheduler} 빈을 만드는데,
     * 그것도 {@link AsyncTaskExecutor}를 구현해 후보가 둘이 된다. 이름으로 고르지 않으면
     * <b>컨텍스트 기동이 실패한다.</b>
     *
     * <p>이 결함이 늦게 드러난 이유는 스케줄러가 기본 꺼짐이라 <b>운영 구성만 이 가지를
     * 밟기 때문</b>이다. {@code ./gradlew build}도 평소 {@code bootRun}도 초록이었고,
     * launchd로 실제 운영 인자를 준 첫 기동에서야 죽었다.
     * {@code SchedulerEnabledContextTest}가 이제 그 구성을 빌드에서 밟는다.
     */
    public AdminJobLauncher(
                            @Qualifier(TaskExecutionAutoConfiguration.APPLICATION_TASK_EXECUTOR_BEAN_NAME)
                            AsyncTaskExecutor taskExecutor,
                            DailyUpdateJob dailyUpdateJob,
                            UniverseRebalanceJob universeRebalanceJob,
                            IndicatorBackfillJob indicatorBackfillJob) {
        this.taskExecutor = taskExecutor;
        this.dailyUpdateJob = dailyUpdateJob;
        this.jobs = Map.of(
                DailyUpdateJob.JOB_NAME,
                request -> dailyUpdateJob.run(
                        request.orDefault(request.targetDate(), LocalDate.now().minusDays(1)),
                        BatchExecutionRecorder.TriggeredBy.ADMIN),

                UniverseRebalanceJob.JOB_NAME,
                request -> universeRebalanceJob.run(
                        request.orDefault(request.targetDate(), LocalDate.now()),
                        BatchExecutionRecorder.TriggeredBy.ADMIN),

                IndicatorBackfillJob.JOB_NAME,
                request -> indicatorBackfillJob.run(
                        request.orDefault(request.from(), LocalDate.now().minusDays(7)),
                        request.orDefault(request.to(), LocalDate.now()),
                        BatchExecutionRecorder.TriggeredBy.ADMIN));

        this.catalog = List.of(
                new Descriptor(DailyUpdateJob.JOB_NAME, "일일 갱신",
                        "대상 일자의 시세를 모으고 지표를 계산한 뒤 워치리스트를 판정한다.",
                        Parameters.TARGET_DATE, 350),
                new Descriptor(UniverseRebalanceJob.JOB_NAME, "유니버스 리밸런싱",
                        "시가총액 상위 N을 다시 계산해 편입·편출을 반영한다.",
                        Parameters.TARGET_DATE, 1),
                new Descriptor(IndicatorBackfillJob.JOB_NAME, "지표 백필",
                        "이미 저장된 시세로 구간의 지표를 다시 계산한다. 외부 API를 부르지 않는다.",
                        Parameters.DATE_RANGE, 0));
    }

    /** 화면이 나열할 잡 목록. 프론트가 이름을 하드코딩하지 않게 서버가 준다. */
    public List<Descriptor> catalog() {
        return catalog;
    }

    public boolean isRunning(String jobName) {
        return running.contains(jobName);
    }

    /** 지금 도는 중인 잡 이름 전부. 목록 응답이 "실행 중" 배지를 그리는 근거다. */
    public Set<String> runningJobs() {
        return Set.copyOf(running);
    }

    public boolean isLaunchable(String jobName) {
        return jobs.containsKey(jobName);
    }

    /**
     * 잡을 띄운다. 호출은 즉시 돌아온다.
     *
     * @throws IllegalArgumentException 목록 밖 잡. 호출부가 {@code isLaunchable}로 먼저 거른다.
     * @return 이미 도는 중이면 {@code false} — 새로 띄우지 않았다.
     */
    public boolean launch(String jobName, JobRequest request) {
        JobRunner runner = jobs.get(jobName);
        if (runner == null) {
            throw new IllegalArgumentException("화면에서 띄울 수 있는 잡이 아니다: " + jobName);
        }
        return submit(jobName, () -> runner.run(request));
    }

    /**
     * 실패한 부분만 다시 돌린다.
     *
     * <p>{@code daily-update}만 지원한다. 나머지 잡의 실패는 종목 단위가 아니거나(유니버스)
     * 외부 호출이 없어(백필) 부분 재실행의 대상이 아니며, 호출부가 그것을 먼저 거른다.
     *
     * @return 이미 도는 중이면 {@code false}.
     */
    public boolean relaunchFailures(long previousExecutionId, LocalDate targetDate) {
        return submit(DailyUpdateJob.JOB_NAME,
                () -> dailyUpdateJob.retryFailures(previousExecutionId, targetDate));
    }

    /** 부분 재실행을 지원하는 잡인가. 실패 단위가 종목인 잡만 해당한다. */
    public static boolean supportsRetry(String jobName) {
        return DailyUpdateJob.JOB_NAME.equals(jobName);
    }

    /**
     * 잡 이름을 선점하고 비동기로 제출한다.
     *
     * <p>{@code finally}에서 반드시 푼다. 예외로 빠져나가면서 이름이 남으면 그 잡은 재시작
     * 전까지 다시 띄울 수 없는데, 화면에는 아무것도 도는 중이 아닌 것으로 보인다.
     *
     * <p>IMPORTANT — 여기서 {@link RuntimeException}을 잡는 것은 catch-all이 아니라
     * <b>스레드 경계</b>다. 제출한 뒤에는 예외를 받을 호출부가 없으므로, 잡지 않으면 그 예외는
     * 워커 스레드의 기본 처리로 넘어가 <b>어디에도 남지 않는다.</b> 잡 안에서 일어난 실패는
     * {@code BatchExecutionContext}가 기록하고 알리지만, 그 바깥에서 터진 것은 이 한 줄이
     * 유일한 흔적이다. 다시 던지지 않는 이유는 던져 봐야 받을 사람이 없기 때문이다.
     */
    private boolean submit(String jobName, Supplier<BatchStatus> job) {
        if (!running.add(jobName)) {
            log.info("이미 도는 중이라 새로 띄우지 않는다. jobName={}", jobName);
            return false;
        }

        taskExecutor.execute(() -> {
            try {
                BatchStatus status = job.get();
                log.info("관리자가 띄운 잡이 끝났다. jobName={} status={}", jobName, status);
            } catch (RuntimeException e) {
                log.error("관리자가 띄운 잡이 기록 바깥에서 터졌다. jobName={} — "
                        + "batch_execution에는 남지 않았을 수 있다.", jobName, e);
            } finally {
                running.remove(jobName);
            }
        });
        return true;
    }

    /** 실행 파라미터. 비어 있으면 잡마다 정한 기본값을 쓴다. */
    public record JobRequest(LocalDate targetDate, LocalDate from, LocalDate to) {

        public static JobRequest empty() {
            return new JobRequest(null, null, null);
        }

        private LocalDate orDefault(LocalDate value, LocalDate fallback) {
            return value != null ? value : fallback;
        }
    }

    /** 화면에 내려보내는 잡 설명. */
    public record Descriptor(String name, String label, String description,
                             Parameters parameters, int externalCallEstimate) {
    }

    /** 어떤 입력을 받는가. 화면이 날짜 칸을 몇 개 그릴지 정한다. */
    public enum Parameters {
        /** 대상 일자 하나. */
        TARGET_DATE,
        /** 시작·종료 일자. */
        DATE_RANGE
    }

    @FunctionalInterface
    private interface JobRunner {
        BatchStatus run(JobRequest request);
    }
}
