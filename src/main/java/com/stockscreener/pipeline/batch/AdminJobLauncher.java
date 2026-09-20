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
                            IndicatorBackfillJob indicatorBackfillJob,
                            CompanyProfileJob companyProfileJob,
                            CorporateActionJob corporateActionJob,
                            MarketIndexJob marketIndexJob,
                            MarketCalendarJob marketCalendarJob,
                            GovernanceDeriveJob governanceDeriveJob) {
        this.taskExecutor = taskExecutor;
        this.dailyUpdateJob = dailyUpdateJob;
        this.jobs = Map.of(
                // MUST NOT: 대상 일자 기본값을 여기서 다시 계산하지 않는다. 자체 계산
                // (minusDays(1))을 들고 있었을 때 **월요일에 날짜를 비우면 대상이 일요일**이 되어,
                // DailyUpdateJob의 주말 분기가 batch_execution 행조차 안 만들고 SUCCESS를
                // 돌려줬다 — 화면에 아무 흔적이 없다. 스케줄러와 같은 규칙을 쓴다(ADR-0042).
                DailyUpdateJob.JOB_NAME,
                request -> dailyUpdateJob.run(
                        request.orDefault(request.targetDate(),
                                DailyUpdateJob.targetDateFor(LocalDate.now())),
                        BatchExecutionRecorder.TriggeredBy.ADMIN),

                // 같은 규칙을 쓰는 이유가 여기서는 더 급하다 — 이 잡에는 주말 가드가 **없다.**
                // 토요일에 당일을 물으면 소스에 없어 어댑터가 하루씩 거슬러 올라가고 그 소급이
                // 날짜당 콜을 태운다(PipelineScheduler.runUniverseRebalance의 판단).
                UniverseRebalanceJob.JOB_NAME,
                request -> universeRebalanceJob.run(
                        request.orDefault(request.targetDate(),
                                DailyUpdateJob.targetDateFor(LocalDate.now())),
                        BatchExecutionRecorder.TriggeredBy.ADMIN),

                IndicatorBackfillJob.JOB_NAME,
                // 티커를 비워 보낸다 — 화면에 그 입력이 없고, 만들지도 않는다. 종목을 좁힌
                // 백필은 무효화 뒤 복구에 쓰는 경로이고 그 판단은 CLI에 남긴다.
                //
                // MUST: universeCode에 null을 명시한다. 아래 company-profile과 같은 이유다 —
                // 화면 버튼은 **활성 유니버스만** 돌아야 한다. 비활성 유니버스를 채우는 것은
                // 켜기 전 준비이고 그 판단은 CLI에 남긴다(IndicatorBackfillJob 클래스 javadoc).
                request -> indicatorBackfillJob.run(
                        request.orDefault(request.from(), LocalDate.now().minusDays(7)),
                        request.orDefault(request.to(), LocalDate.now()),
                        BatchExecutionRecorder.TriggeredBy.ADMIN, List.of(), null),

                // 화면에서 띄워도 되는 이유는 대상이 **활성 유니버스에서 업종이 비어 있는
                // 종목뿐**이라 평소 0콜이고, 전량이어도 종목당 1콜(국내 350)이기 때문이다.
                // InitialLoadJob을 뺀 근거(한 번에 한도 절반)가 여기엔 없다.
                //
                // MUST: universeCode에 null을 명시한다. 자동 실행은 활성 유니버스만 돈다 —
                // US_LISTED를 켜는 날 이 버튼의 대상이 2,238종목(실제 콜 423)이 되고,
                // 그때는 governance-load를 화면에서 뺀 근거(ADR-0092 · 국내 8,400콜)가 여기에 걸린다.
                // 수의 출처는 StockRegistrar.activeUniverseMemberIdsWithoutIndustry의 javadoc이다.
                CompanyProfileJob.JOB_NAME,
                request -> companyProfileJob.run(
                        request.orDefault(request.targetDate(), LocalDate.now()),
                        BatchExecutionRecorder.TriggeredBy.ADMIN, List.of(), null),

                // MUST NOT: from에 기본값을 주지 않는다. 잡이 null을 "저장된 시세의 첫 거래일"로
                // 읽고, 짧은 창은 소스가 정기보고서에만 실려 **원리적으로 0건**이 된다.
                // IndicatorBackfillJob의 minusDays(7)을 복사하면 그 조용한 0건이 된다.
                CorporateActionJob.JOB_NAME,
                // MUST: universeCode에 null을 명시한다. 자동 실행은 활성 유니버스만 돈다 —
                // 그래야 "활성"이 무엇을 뜻하는지가 한 가지로 남는다(ADR-0088·0090). JobRequest가
                // 날짜 셋뿐이라 화면에서 실을 수도 없지만, 기본값을 잡 쪽에 두면 그 사실이
                // 조용히 뒤집힌다.
                request -> corporateActionJob.run(
                        request.orDefault(request.to(), LocalDate.now()),
                        BatchExecutionRecorder.TriggeredBy.ADMIN, request.from(), List.of(), null),

                // 화면에 두는 이유는 **결손이 전 종목에 걸리기 때문**이다. 지수 한 줄이 없으면
                // 그날 EXCESS_RETURN_26W가 전 종목에서 안 써지고 알림이 나가는데, 다시 채울
                // 길이 CLI뿐이면 운영 앱을 내리거나 포트를 비켜야 한다. 전 구간을 받아도
                // 지수당 1~2콜이다.
                //
                // MUST NOT: from에 기본값을 주지 않는다. 잡이 null을 소스 이력의 시작으로 읽고,
                // 여기서 짧은 창을 만들면 결손 구간을 못 덮은 채 성공한다.
                MarketIndexJob.JOB_NAME,
                request -> marketIndexJob.run(
                        request.orDefault(request.to(), LocalDate.now()),
                        BatchExecutionRecorder.TriggeredBy.ADMIN, request.from()),
                // MUST NOT: governance-load를 여기 올리지 않는다. 전량이 국내 8,400 + 미국 4,000 =
                // 12,400콜이라 InitialLoadJob을 뺀 근거(한 번에 한도 절반)에 정확히 걸린다. 유도 잡은
                // 외부 호출이 0이라 같은 근거가 없다.
                GovernanceDeriveJob.JOB_NAME,
                request -> governanceDeriveJob.run(
                        request.orDefault(request.targetDate(), LocalDate.now()),
                        BatchExecutionRecorder.TriggeredBy.ADMIN, List.of()),
                // 거래소당 1콜(둘)이고 되돌릴 수 있다. 화면에 두는 이유는 **적재 창이 낡으면
                // 판정이 조용히 판정불가로 퇴화하기 때문**이다 — 스케줄러에 못 걸어서
                // (미국 장이 한국 시각 새벽에 닫는다) 사람이 주기적으로 눌러야 한다.
                //
                // MUST NOT: bar-gap-review는 여기 올리지 않는다. 그 잡은 실행 행을 안 만들어
                // 화면이 202를 받고 **아무것도 안 뜬다** — corporate-action-review가 카탈로그에
                // 없는 것과 같은 이유다.
                MarketCalendarJob.JOB_NAME,
                request -> marketCalendarJob.run(
                        request.orDefault(request.to(), LocalDate.now()),
                        BatchExecutionRecorder.TriggeredBy.ADMIN, request.from()));

        // MUST: 콜 어림값은 **화면이 승인을 받는 수**다(`AdminOverviewView`가
        // "외부 API를 약 N회 호출합니다. 실행할까요?"로 묻는다). 틀린 수 위에서 받은 승인은
        // 승인이 아니므로, 잡이 부르는 것이 늘면 **이 수도 같은 커밋에서 고친다.**
        // 아래 셋이 실제로 그렇게 낡아 있었다(2026-09-19 실측).
        this.catalog = List.of(
                // 종목당 1콜이고 대상이 `activeUniverseMemberIds()`다 — 실측 1,350(KR 350 + US 1,000).
                // 350은 미국이 켜지기 전 수였다(ADR-0107).
                new Descriptor(DailyUpdateJob.JOB_NAME, "일일 갱신",
                        "대상 일자의 시세를 모으고 지표를 계산한 뒤 워치리스트를 판정한다.",
                        Parameters.TARGET_DATE, 1350),
                // 라벨은 화면에 그대로 뜬다. 한국어 표시어는 "유니버스"가 아니다 —
                // `frontend/src/lib/universe.ts`가 그 판단의 정본이다.
                //
                // 1이었는데 2026-09-13에 결손 보충 꼬리가 붙어 거짓이 됐다. 기저: 미국 유니버스
                // 갱신이 자산 1콜 + 종가 묶음 약 19(`batch-load` skill) · 국내 둘 · 꼬리의 업종 수집.
                // 실측(2026-09-19 20:00): 편입 20종목 · 시세 결손 2 · 업종 4 → 약 27콜 · 20초.
                // **꼬리 때문에 변동 폭이 크다** — 그 사실을 설명에 적는다.
                new Descriptor(UniverseRebalanceJob.JOB_NAME, "지수 구성종목 갱신",
                        "시가총액 상위 N을 다시 계산해 편입·편출을 반영하고, 이어서 새 편입 종목의 "
                                + "빈 원자료를 채운다(업종은 결손 종목당 1콜, 시세는 결손 종목당 "
                                + "7년치이고 한 판 상한이 50종목이다). 결손이 많은 주에는 콜이 크게 는다.",
                        Parameters.TARGET_DATE, 25),
                new Descriptor(IndicatorBackfillJob.JOB_NAME, "지표 백필",
                        "이미 저장된 시세로 구간의 지표를 다시 계산한다. 외부 API를 부르지 않는다.",
                        Parameters.DATE_RANGE, 0),
                // 0이 아니다 — 기본 대상이 「업종 두 컬럼이 다 NULL인 활성 편입 종목」이고
                // 종목당 1콜이다. 실측 2026-09-19에 4콜(면제 셋 + 재편입 MAIN)이 돌았다.
                new Descriptor(CompanyProfileJob.JOB_NAME, "업종 정보 수집",
                        "업종이 비어 있는 편입 종목의 업종·결산월을 받는다. 종목당 1콜이라 "
                                + "결손 수만큼 든다. 소스가 끝내 안 주는 종목은 매번 다시 걸린다.",
                        Parameters.TARGET_DATE, 5),
                // 창을 받아야 하므로 DATE_RANGE다 — `to`가 곧 대상일이고, `from`을 비우면 잡이
                // 저장된 시세의 첫 거래일부터 본다. 새 Parameters 값을 만들지 않는 이유는
                // 그것이 wire-contract·types.ts·AdminOverviewView를 함께 끌고 오기 때문이다.
                new Descriptor(CorporateActionJob.JOB_NAME, "기업 행위 수집",
                        "액면분할·병합·무상증자를 모아 원장에 적는다. 수정계수는 건드리지 않는다.",
                        Parameters.DATE_RANGE, 700),
                new Descriptor(MarketIndexJob.JOB_NAME, "시장 지수 수집",
                        "코스피·코스닥 지수의 일별 종가를 받는다. from을 비우면 2020-01-02부터다.",
                        Parameters.DATE_RANGE, 2),
                new Descriptor(GovernanceDeriveJob.JOB_NAME, "거버넌스 사실 유도",
                        "이미 받아 둔 정기보고서 원문에서 지표용 값을 다시 만든다. 외부 API를 부르지 않는다.",
                        Parameters.TARGET_DATE, 0),
                new Descriptor(MarketCalendarJob.JOB_NAME, "세션 달력 수집",
                        "NYSE·NASDAQ이 정규장을 연 날을 받는다. 미국 봉이 없는 날을 휴장과 가르는 "
                                + "근거이며, from을 비우면 2016-01-01부터다.",
                        Parameters.DATE_RANGE, 2));
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
            } catch (BatchShutdownException e) {
                // 프로세스가 내려가서 잡이 스스로 멈춘 것이다. error로 찍으면 배포마다
                // Sentry 이슈가 하나씩 난다 — sentry.logging.minimum-event-level이 error다.
                log.info("관리자가 띄운 잡이 종료로 중단됐다. jobName={} — "
                        + "batch_execution에 ABORTED로 남는다. 필요하면 다시 띄운다.", jobName);
            } catch (RuntimeException e) {
                // MUST NOT: "batch_execution에는 남지 않았을 수 있다"로 되돌리지 않는다.
                // ADR-0078 이후 거짓이다 — 종료 훅과 스위퍼가 열린 행을 반드시 마감한다.
                log.error("관리자가 띄운 잡이 기록 바깥에서 터졌다. jobName={} — "
                        + "실행 행은 마감되지만 이 예외 자체는 여기 말고 어디에도 없다.", jobName, e);
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
