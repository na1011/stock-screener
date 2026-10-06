package com.stockscreener.pipeline.batch;

import com.stockscreener.notification.AlertEvent;
import com.stockscreener.notification.AlertPort;
import com.stockscreener.notification.AlertSeverity;
import com.stockscreener.pipeline.datasource.DataSourceException;
import com.stockscreener.pipeline.datasource.SecurityRef;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;

import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongConsumer;

/**
 * 배치 실행 하나의 진행 상태를 들고 마감까지 책임진다.
 *
 * <p>집계를 각 잡이 손으로 하지 않게 모아둔 이유는, 흩어지면 어떤 잡은 실패 건수를 세지 않고
 * SUCCESS로 마감하게 되기 때문이다. 마감({@link #finish()})이 상태 판정과 알림을 함께
 * 수행하므로, <b>실패가 DB에만 있고 아무도 모르는 상태가 구조적으로 불가능</b>해진다.
 *
 * <p>MUST NOT: 종목마다 알림을 보내지 않는다. 850건 실패에 850통을 보내면 알림 자체가
 * 무의미해진다. 마감 시 요약 한 건만 보낸다.
 *
 * <p>스레드 안전하다. 청크 처리가 병렬로 확장될 여지를 열어두기 위해 원자적 카운터를 쓴다.
 */
public final class BatchExecutionContext {

    private static final Logger log = LoggerFactory.getLogger(BatchExecutionContext.class);

    /**
     * 소스 스펙 변경을 의심할 실패 비율. 이 비율을 넘는 MALFORMED가 나오면 별도로 알린다.
     *
     * <p>한 종목이 MALFORMED면 그 종목의 데이터 문제지만, 대량이면 벤더가 응답 형식을
     * 바꿨다는 신호이며 spec §7이 우려한 바로 그 사건이다.
     */
    private static final double MALFORMED_ALERT_RATIO = 0.2;

    /**
     * 진척을 DB에 남기는 간격.
     *
     * <p>건수가 아니라 <b>시간</b> 기준인 것이 요점이다. 작업 단위 하나의 소요가 잡마다
     * 두 자릿수 배로 다르다 — 지표 백필은 계산만이라 수십 ms이고, 초기 적재는 외부 호출이라
     * 수 초다. 건수로 정하면 한쪽은 초당 수십 번 쓰고 다른 쪽은 몇 분에 한 번 쓴다.
     */
    private static final long FLUSH_INTERVAL_NANOS = Duration.ofSeconds(3).toNanos();

    private final long executionId;
    private final String jobName;
    private final LocalDate targetDate;
    private final StockTaskExecutor executor;
    private final BatchExecutionRecorder recorder;
    private final AlertPort alertPort;

    private final AtomicInteger successCount = new AtomicInteger();
    private final AtomicInteger failureCount = new AtomicInteger();
    private final AtomicInteger malformedCount = new AtomicInteger();
    private final AtomicLong writtenRowCount = new AtomicLong();

    /**
     * 단계별 집계. 합계와 <b>별도로</b> 센다.
     *
     * <p>합계만으로는 "시세가 한 건도 안 들어왔다"를 판정할 수 없다. 시세가 0행이어도 지표
     * 단계가 기존 시계열을 다시 써서 종목당 수천 행을 남기기 때문이다({@code IndicatorRunner}는
     * {@code asOf} 이전 구간을 통째로 재계산한다). 실제로 그 상황이 979,541행짜리 성공으로
     * 기록되어 금요일 종가 결손이 아무에게도 알려지지 않았다.
     *
     * <p>모든 값을 미리 채워 둔다. 늦게 넣으면 이 클래스가 약속한 스레드 안전성이 깨진다.
     */
    private final Map<CollectionPhase, PhaseCounters> byPhase = initPhaseCounters();

    /**
     * 지금 어느 구간인가. 잡이 신고하지 않으면 {@code null}이고 화면은 그 줄을 그리지 않는다.
     *
     * <p>{@code volatile}인 이유는 신고하는 스레드와 진척을 쓰는 스레드가 다를 수 있기
     * 때문이다. 정확성보다 <b>보이는가</b>가 중요한 값이라 락을 걸지 않는다.
     */
    private volatile String stageLabel;

    /** 마지막으로 진척을 쓴 시각. 벽시계가 아니라 단조 시계다 — 시각 보정에 흔들리면 안 된다. */
    private final AtomicLong lastFlushNanos = new AtomicLong(System.nanoTime());

    /**
     * 마감됐음을 알리는 콜백.
     *
     * <p>{@link BatchExecutions}가 <b>아직 마감되지 않은</b> 실행을 들고 있다가 프로세스가
     * 내려갈 때 닫는다. 그러려면 정상 마감된 것을 그 목록에서 빼야 하고, 그 신호가 이것이다.
     * 콜백으로 두는 이유는 컨텍스트가 자기를 만든 쪽을 알지 않게 하기 위해서다.
     */
    private final LongConsumer onTerminal;

    /**
     * 이미 마감했는가.
     *
     * <p>DB에도 같은 가드가 있지만({@link BatchExecutionRecorder}의
     * {@code AND status = 'RUNNING'}) 그것은 <b>쓰기만</b> 막는다. 알림은 그 결과를 보지 않고
     * 나가므로, 종료 훅이 먼저 마감한 실행에 대해 뒤늦게 도착한 워커가 <b>가짜 "부분 실패"를
     * 그대로 쏜다.</b> 그래서 판정이 알림보다 <b>앞</b>에 있어야 한다.
     *
     * <p>MUST NOT: 이것으로 DB 가드를 대신하지 않는다. 이 플래그는 한 JVM 안에서만 유효하고,
     * 다른 프로세스가 남긴 행과의 경합({@link BatchExecutionSweeper})은 여전히 DB가 판정한다.
     */
    private final AtomicBoolean terminal = new AtomicBoolean();

    /**
     * 프로세스가 내려가서 마감됐는가. 잡의 루프를 <b>협조적으로</b> 끊는 신호다.
     *
     * <p>IMPORTANT — 이것은 {@link #terminal}과 다르다. 정상 마감에는 켜지지 않는다.
     * 그리고 <b>{@code claimTerminal}에 성공한 뒤에만</b> 켜지므로 「이 플래그가 참」은
     * 「이 실행은 이미 마감됐다」와 같다. 그 불변식 때문에 잡 열한 개가 자기 루프를
     * 고치지 않아도 되고, 어디서 {@link BatchShutdownException}이 나가든 행이
     * {@code RUNNING}으로 남지 않는다. <b>순서를 바꾸면 그 보장이 사라진다.</b>
     *
     * <p>인터럽트를 쓰지 않는 이유는 ADR-0078에 있다 — 소켓 읽기가 인터럽트를 무시하고,
     * 외부 호출을 아예 안 하는 잡에는 신호가 도달할 입구조차 없다. 여기는 <b>잡과
     * 프레임워크가 만나는 자리</b>라 어느 잡이든 지난다.
     */
    private volatile boolean abandonedOnShutdown;

    /**
     * 마감된 상태. 경합에서 진 경로가 돌려줄 값이다.
     *
     * <p>값이 미리 정해진 경로들은 {@code recorder}를 부르기 <b>전에</b> 채운다. 그래야
     * {@link #finish(String)}이 뒤늦게 도착해 졌을 때 비어 있지 않다 — {@code finish}만
     * {@code recorder}가 상태를 판정하므로 그쪽만 나중에 채운다.
     */
    private volatile BatchStatus recordedStatus;

    /**
     * 이 실행이 <b>시작될 때</b> 미해결이던 (단계 → 종목) 쌍.
     *
     * <p>성공할 때마다 DB를 묻지 않으려고 한 번만 읽는다. 평상시에는 비어 있어 아무 값도
     * 치르지 않는다. 값을 지우면서 쓰므로(첫 성공에서만 {@code remove}가 참) 같은 종목이
     * 두 번 성공해도 UPDATE는 한 번이다.
     */
    private final Map<CollectionPhase, Set<Long>> unresolvedAtStart;

    /**
     * 시장마다 끝까지 마쳤는가를 세는 자리(ADR-0174). 시장 집합을 선언한 잡(FR·FSR·G)만 있고 나머지는 {@code null}이다.
     *
     * <p>판정 재료를 실행 안에서 센다 — 마감 때 실패 행을 SQL로 다시 읽으면 다른 실행이 그 사이 해결로 표시한
     * ({@code resolveFailures}는 실행 id로 안 좁힌다) 행이 빠져 못 받은 시장이 끝난 것으로 읽힌다.
     */
    private final MarketCompleteness completeness;

    public BatchExecutionContext(long executionId, String jobName, LocalDate targetDate,
                                 StockTaskExecutor executor, BatchExecutionRecorder recorder,
                                 AlertPort alertPort, LongConsumer onTerminal) {
        this(executionId, jobName, targetDate, executor, recorder, alertPort, onTerminal, null);
    }

    /** 위와 같되 마감 때 시장 미완료를 판정한다. {@code coverage}가 {@code null}이면 판정하지 않는다. */
    public BatchExecutionContext(long executionId, String jobName, LocalDate targetDate,
                                 StockTaskExecutor executor, BatchExecutionRecorder recorder,
                                 AlertPort alertPort, LongConsumer onTerminal,
                                 MarketCompleteness.Coverage coverage) {
        this.completeness = coverage == null ? null : new MarketCompleteness(coverage);
        this.executionId = executionId;
        this.jobName = jobName;
        this.targetDate = targetDate;
        this.executor = executor;
        this.recorder = recorder;
        this.alertPort = alertPort;
        this.onTerminal = onTerminal;
        this.unresolvedAtStart = recorder.unresolvedStockPhases();
    }

    public long executionId() {
        return executionId;
    }

    /**
     * 종목 하나를 격리해 처리하고 집계에 반영한다.
     *
     * <p>성공 여부를 <b>반환한다.</b> 집계만 하고 버리면 호출부가 "이 종목이 방금 성공했는가"를
     * 알 수 없고, 부분 재실행이 실패한 종목까지 해결로 표시하게 된다.
     *
     * @return 이 종목이 성공했으면 {@code true}
     * @throws DataSourceException 복구 불가능한 실패({@code AUTH})일 때만. 호출부는 이것을
     *                             잡아 {@link #failFatally(DataSourceException)}로 마감해야 한다.
     */
    public boolean process(SecurityRef security, CollectionPhase phase, StockTask task)
            throws DataSourceException {
        abortIfAbandoned();
        try {
            StockTaskExecutor.Outcome outcome =
                    executor.execute(executionId, security, phase, task, abandonedOnShutdown);
            PhaseCounters counters = byPhase.get(phase);

            boolean succeeded = outcome.succeeded();
            if (completeness != null) {
                completeness.record(security.marketCode(), succeeded, outcome.failureKind(),
                        outcome.sourceAnswered());
            }
            if (succeeded) {
                successCount.incrementAndGet();
                writtenRowCount.addAndGet(outcome.writtenRows());
                counters.success.incrementAndGet();
                counters.writtenRows.addAndGet(outcome.writtenRows());
                resolveIfPreviouslyFailed(security.stockId(), phase);
            } else {
                failureCount.incrementAndGet();
                counters.failure.incrementAndGet();
            }
            flushIfDue();
            return succeeded;

        } catch (RuntimeException e) {
            // MUST: 감싸는 범위가 본문 **전체**다. executor 호출 한 줄로 좁히면 그 뒤의
            // flushIfDue()가 빠지는데, 거기서 나오는 CannotCreateTransactionException은
            // DataAccessException이 아니라(→ TransactionException → NestedRuntimeException)
            // flushNow의 좁은 catch를 그대로 통과해 원본 그대로 Sentry까지 간다.
            //
            // DataSourceException은 checked라 여기 안 걸린다 — 이 메서드의 계약은 그대로다.
            throw asShutdownSignalIfAbandoned(e);
        }
    }

    /**
     * 종료가 시작됐으면 여기서 끊는다.
     *
     * <p>잡이 <b>새 작업 단위를 시작하려는 자리</b>에서 부른다. {@link #process}·{@link #stage}·
     * {@link #plan}이 자동으로 부르므로 대개 잡이 직접 부를 일은 없다 — 그 셋 중 아무것도
     * 신고하지 않는 루프만 직접 부른다({@code UniverseRebalanceJob}·{@code MarketIndexJob}).
     *
     * <p>MUST NOT: 이것을 「울타리」로 읽지 않는다. <b>경계</b>다 — 플래그가 한 단계 도중에
     * 켜지면 그 단계는 끝까지 간다. 진행 중인 외부 호출 하나는 이 기제로 못 끊는다.
     */
    public void abortIfAbandoned() {
        if (abandonedOnShutdown) {
            throw new BatchShutdownException(
                    "프로세스가 내려가는 중이라 이 실행을 더 진행하지 않는다. id=" + executionId);
        }
    }

    /**
     * 종료가 시작돼 이 실행이 버려졌는가.
     *
     * <p>끊는 자리에서는 {@link #abortIfAbandoned()}를 쓴다. 이 접근자는 <b>끊을 수 없는
     * 자리</b>를 위한 것이다 — 알림 포트를 직접 부르는 코드가 그렇다. 그쪽은 던지는 대신
     * 로그로 내려야 한다({@link #alertOperator}가 같은 판단을 한다).
     */
    public boolean abandonedOnShutdown() {
        return abandonedOnShutdown;
    }

    /**
     * 종료 중에 난 실패면 신호로 갈아 끼운다. 아니면 원본 그대로 — 진짜 버그는 드러나야 한다.
     *
     * <p>{@link #process} 밖에서 단위를 격리하는 잡(파생층 날짜 루프)도 부른다 — 그 catch가 종료 중의 풀 닫힘을
     * 「그 날짜의 실패」로 삼키면 종료가 남은 단위만큼 늦어진다(ADR-0078 · {@code data-pipeline.md}).
     */
    RuntimeException asShutdownSignalIfAbandoned(RuntimeException cause) {
        if (!abandonedOnShutdown || cause instanceof BatchShutdownException) {
            return cause;
        }
        return new BatchShutdownException(
                "프로세스가 내려가는 중에 실패했다. id=" + executionId, cause);
    }

    /**
     * 이 단계에서 돌 예정인 작업 단위 수를 신고한다. 루프에 들어가기 전에 부른다.
     *
     * <p>IMPORTANT — 이것이 화면의 <b>분모</b>다. 신고하지 않으면 분모가 {@code —}로 뜬다.
     * 대상 <b>종목</b> 수를 분모로 쓰던 시절에 지표 백필이 {@code 43400/350}으로 보였다 —
     * 350종목을 124거래일 돌아 43,400번 처리한 것을 종목 수로 나눈 값이었다. 그러니
     * <b>실제로 {@link #process}를 몇 번 부를 것인가</b>를 신고해야 한다.
     *
     * <p>계획보다 적게 처리하고 끝나는 것은 정상이며 그 자체가 정보다. 일일 갱신은 시세가
     * 실패한 종목의 지표를 건너뛰므로 100%에 못 닿는데, 그 차이가 곧 건너뛴 종목 수다.
     */
    public void plan(CollectionPhase phase, int tasks) {
        abortIfAbandoned();
        PhaseCounters counters = byPhase.get(phase);
        counters.planned.set(tasks);
        counters.declared = true;
        // 곧바로 남긴다. 여기서 안 쓰면 첫 하트비트까지 화면의 분모가 비어 있고, 그 몇 초가
        // 하필 "지금 뭐가 도는지" 보려고 화면을 여는 시점이다.
        flushNow();
    }

    /**
     * 지금 어느 구간인지 신고한다. 형식은 잡이 정하고 아무도 해석하지 않는다.
     *
     * <p>진행률만으로는 부족하기 때문에 있다 — 백필이 40%라는 것보다 "지금 2026-05-12를
     * 돌고 있다"가 실제로 알고 싶은 것이고, 그것을 알아야 남은 시간을 가늠할 수 있다.
     */
    public void stage(String label) {
        // MUST: 구간 신고는 곧 **그 구간을 시작한다**는 선언이다. 그래서 여기가 게이트다 —
        // 종목 루프만 막으면 루프 **뒤**의 백분위·업종 상대값·워치리스트가 안 끊긴다.
        // 새 신고 자리를 만들 때 이 계약이 함께 따라온다는 것이 이 배치의 요점이다.
        abortIfAbandoned();
        this.stageLabel = label;
    }

    /**
     * 종목 격리를 거치지 않고 집계된 결과를 반영한다.
     *
     * <p>처리 단위가 종목이 아닌 잡이 쓴다 — {@link UniverseRebalanceJob}은 지수가,
     * {@link CorporateActionJudgeJob}은 원장 행이 단위다. 그런 잡은 {@link #process}를 쓸 수 없다
     * ({@code batch_execution_failure.stock_id}가 NOT NULL + FK다). 그래도 여기를 지나게 하는 이유는
     * <b>단계별 집계와 진행률이 잡 종류와 무관하게 성립해야</b> 하기 때문이다 — 이 경로가
     * 없으면 그 잡만 화면에서 분모도 단계도 없이 뜬다.
     */
    public void countExternally(CollectionPhase phase, int succeeded, int failed, long writtenRows) {
        successCount.addAndGet(succeeded);
        failureCount.addAndGet(failed);
        writtenRowCount.addAndGet(writtenRows);

        PhaseCounters counters = byPhase.get(phase);
        counters.success.addAndGet(succeeded);
        counters.failure.addAndGet(failed);
        counters.writtenRows.addAndGet(writtenRows);
    }

    /**
     * 실행을 마감하지 않은 채 운영자에게 알린다.
     *
     * <p>마감({@link #finish()})에 실리지 않는 사건이 있다. 워치리스트 판정 실패가 그렇다 —
     * 수집·계산은 끝났으므로 배치는 성공이지만, 그 실패를 아무도 모르면 사용자는 알림이
     * 없는 것을 "조건에 안 맞았나 보다"로 읽는다.
     *
     * <p>{@code alertPort}를 잡에 직접 주입하지 않고 여기를 지나게 하는 이유는 <b>실행
     * 컨텍스트가 함께 실리기 때문</b>이다. executionId 없는 알림은 관리자 화면에서 그 실행을
     * 찾을 수 없다. dedupeKey도 같은 규칙을 따라야 억제가 일관된다.
     */
    public void alertOperator(AlertSeverity severity, String title, String body) {
        if (abandonedOnShutdown) {
            // MUST: 「안 보낸다」가 아니라 「로그로 내린다」이다. 여기 오는 알림 중 여섯은
            // 대응하는 개별 로그가 아예 없어(기업 행위 판정 대기·근접 중복, 대상일 시세 0건,
            // 대상일 지수 없음 등) 통째로 막으면 **그 사실이 어디에도 안 남는다.**
            // 판정 대기 후보가 사라지면 그 종목의 장기창 지표는 ADR-0055로 억제된 채
            // 남는데, 화면에서는 "조건에 안 맞았다"와 구분되지 않는다.
            //
            // 안 보내는 이유는 ADR-0078이다 — CompositeAlertPort.send가 동기이고 웹훅이
            // connect 5초 + read 10초라 종료 예산을 그만큼 먹는다.
            log.warn("종료 중이라 알림을 보내지 않는다 — 이 로그가 유일한 기록이다. "
                            + "id={} job={} 심각도={} 제목={} 본문={}",
                    executionId, jobName, severity, title, body);
            return;
        }
        alertPort.send(AlertEvent.toOperator(
                severity, jobName + " — " + title, body, dedupeKey() + ':' + title, context()));
    }

    /** 응답을 해석할 수 없었던 실패를 별도로 센다. 소스 스펙 변경 판단의 근거다. */
    public void countMalformed() {
        malformedCount.incrementAndGet();
    }

    public int successCount() {
        return successCount.get();
    }

    public int failureCount() {
        return failureCount.get();
    }

    public long writtenRowCount() {
        return writtenRowCount.get();
    }

    /**
     * 한 단계가 저장한 행 수.
     *
     * <p>MUST: "그 단계가 실제로 무언가를 받아왔는가"는 이쪽으로 묻는다. 합계로 물으면 다른
     * 단계가 쓴 행이 0을 덮는다 — {@link PhaseProgress}의 주석에 그 사고가 적혀 있다.
     */
    public long writtenRowCount(CollectionPhase phase) {
        return byPhase.get(phase).writtenRows.get();
    }

    /**
     * 정상 종료로 마감하고, 실패가 있으면 <b>요약 한 건</b>을 알린다.
     *
     * <p>이 메서드가 상태 판정과 알림을 함께 하는 것이 핵심이다. 둘을 분리하면 마감은 했는데
     * 알리지 않은 경로가 생기고, 그 배치의 실패는 아무도 모르게 된다.
     */
    public BatchStatus finish() {
        return finish(null);
    }

    /**
     * 위와 같되 실행 기록에 한 줄을 남긴다.
     *
     * <p>성공한 실행이 무엇을 했는지 나중에 물어야 하는 잡이 쓴다 — 근거는
     * {@link BatchExecutionRecorder#finish(long, BatchProgress, String)}에 있다.
     */
    public BatchStatus finish(String note) {
        if (!claimTerminal("정상 마감")) {
            return recordedStatus;
        }
        List<MarketCompleteness.Incomplete> incomplete =
                completeness == null ? List.of() : completeness.judge();
        String recordedNote = withIncompleteTail(note, incomplete);
        BatchStatus status = recorder.finish(executionId, snapshot(), recordedNote,
                MarketCompleteness.markets(incomplete));
        recordedStatus = status;
        onTerminal.accept(executionId);

        if (status == BatchStatus.PARTIAL_SUCCESS) {
            alertPort.send(AlertEvent.toOperator(
                    AlertSeverity.WARNING,
                    jobName + " 부분 실패",
                    // MUST NOT: 단위를 "종목"이라 쓰지 않는다. 한 종목이 여러 단계·여러 날짜로
                    // 처리되므로 이 수는 종목 수보다 크다. 실제로 350종목짜리 유니버스에서
                    // "84716종목 실패"라고 나간 알림이 있었다.
                    // note를 함께 싣는다 — 무엇이 실패했는지(파생층의 실패 거래일 등)가 note에만 있으면
                    // 알림을 받은 사람이 관리 화면을 열기 전에는 모른다. 잘라서 싣는다 — 웹훅이 본문 뒤를 2,000자에서
                    // 자르는데 실행 id가 본문 뒤에 그려지고, 무효화 종목 목록을 통째로 싣는 note(재무 refill)가 있다.
                    "%d건 성공, %d건 실패 (대상일 %s)%s"
                            .formatted(successCount.get(), failureCount.get(), targetDate,
                                    noteForAlert(recordedNote)),
                    dedupeKey(),
                    context()));
        }
        alertOnSuspectedSpecChange();
        alertOnIncompleteMarkets(incomplete);
        return status;
    }

    /** note 앞머리는 그대로 두고 꼬리에 미완료 시장을 붙인다. 앞머리 형식은 잡이 정하고 운영 정규식이 읽는다. */
    private static String withIncompleteTail(String note, List<MarketCompleteness.Incomplete> incomplete) {
        String tail = MarketCompleteness.noteTail(incomplete);
        if (tail.isEmpty()) {
            return note;
        }
        return note == null || note.isBlank() ? tail : note + " " + tail;
    }

    /**
     * 시장 미완료를 <b>따로 한 통</b> 알린다(ADR-0174).
     *
     * <p>부분 실패 알림에 기대지 않는 이유가 둘이다. 그 알림은 note를 300자에서 자르는데 무효화 종목 목록을 싣는 note는 꼬리가
     * 잘린다. 그리고 실패가 0인 미완료(시작 때 소스 없음)는 부분 실패 알림이 아예 안 난다.
     *
     * <p>MUST: 키를 {@code dedupeKey() + ":incomplete"}로 가른다 — 같은 키면 {@code AlertSuppressor}가 10분 안의 두 번째를
     * 버려, 부분 실패 알림 바로 뒤의 이 알림이 사라진다. {@code :malformed}와 같은 자리다. 심각도는 {@code WARNING}이다 —
     * 웹훅의 최소 심각도가 그것이라 그 아래면 채널이 받지 않는다.
     */
    private void alertOnIncompleteMarkets(List<MarketCompleteness.Incomplete> incomplete) {
        if (incomplete.isEmpty()) {
            return;
        }
        alertPort.send(AlertEvent.toOperator(
                AlertSeverity.WARNING,
                jobName + " 시장 미완료",
                ("%s (대상일 %s · 실행 id=%d) — 이 실행은 신선도 시계에 세지 않는다. 정기 실행이면 그 잡이 연체로 남아 "
                        + "다음 무거운 잡 차례에 다시 돈다 — 같은 사유가 되풀이되면 원인(소스 장애·한도·우리 코드)을 먼저 본다.")
                        .formatted(MarketCompleteness.noteTail(incomplete), targetDate, executionId),
                dedupeKey() + ":incomplete",
                context()));
    }

    /** 알림 본문에 실을 note — 길면 앞만 싣고 전체는 실행 기록에 있다고 적는다. */
    static String noteForAlert(String note) {
        if (note == null || note.isBlank()) {
            return "";
        }
        int limit = 300;
        return note.length() <= limit ? " — " + note : " — " + note.substring(0, limit) + "…(전체는 실행 기록 note)";
    }

    /**
     * 마감하되 요약 알림을 보내지 않는다.
     *
     * <p>{@link UniverseRebalanceJob} 전용이다. 그 잡은 이미 <b>지수마다</b> 실패를 알렸으므로
     * 요약을 한 건 더 보내면 같은 사실이 두 번 온다.
     *
     * <p>MUST: 그래도 여기를 지나야 한다. {@code recorder}를 직접 부르면 {@code onTerminal}이
     * 불리지 않아 이 컨텍스트가 {@link BatchExecutions}의 열린 목록에 남고, <b>종료 훅이
     * 성공한 실행을 ABORTED로 덮는다.</b> 실제로 그 경로에 있었다.
     */
    public BatchStatus finishQuietly() {
        if (!claimTerminal("정상 마감(무알림)")) {
            return recordedStatus;
        }
        BatchStatus status = recorder.finish(executionId, snapshot());
        recordedStatus = status;
        onTerminal.accept(executionId);
        return status;
    }

    /**
     * 복구 불가능한 실패로 마감하고 즉시 알린다.
     *
     * <p>{@code CRITICAL}인 이유는 방치하면 데이터가 계속 갱신되지 않기 때문이다.
     * 부분 실패와 달리 다음 실행이 스스로 회복하지 못한다.
     */
    public void failFatally(DataSourceException cause) {
        if (!claimTerminal("복구 불가능한 실패")) {
            return;
        }
        recordedStatus = BatchStatus.FAILED;
        recorder.fail(executionId, snapshot(), cause.kind() + ": " + cause.getMessage());
        onTerminal.accept(executionId);
        alertPort.send(AlertEvent.toOperator(
                AlertSeverity.CRITICAL,
                jobName + " 실패",
                "복구 불가능한 오류로 중단했다: %s (%s) — %s"
                        .formatted(cause.kind(), cause.sourceName(), cause.getMessage()),
                dedupeKey(),
                context()));
    }

    /**
     * 예상하지 못한 오류로 마감한다. 우리 코드의 버그일 가능성이 높다.
     *
     * <p>{@link BatchShutdownException}은 <b>여기서 되돌아 나간다.</b> 그것은 설계된 종료
     * 신호이지 버그가 아니고, 잡 열한 개가 이 메서드를 일반 {@code catch (RuntimeException)}
     * 에서 부르므로 걸러내지 않으면 <b>종료마다</b> {@code claimTerminal}의 "이미 마감된 …"
     * 경고가 확정적으로 난다. 그러면 그 경고가 진짜 마감 경합을 가리키는 신호이기를
     * 그만두는데, 그 구분이 가능해야 한다는 것이 {@link #claimTerminal}의 존재 이유다.
     */
    public void failUnexpectedly(Throwable cause) {
        failUnexpectedly(null, cause);
    }

    /**
     * 위와 같되 <b>어디서</b> 실패했는지를 앞에 붙인다. 스케줄러가 잡이 실행 행을 만들기 전에 던진 것을 「시작 실패」로
     * 남길 때 쓴다 — 그 행이 없으면 연체 보정 순서의 키(마지막 시도)가 그 잡에서 멈춘다({@code PipelineScheduler}).
     *
     * @param where {@code null}이면 붙이지 않는다
     */
    public void failUnexpectedly(String where, Throwable cause) {
        if (cause instanceof BatchShutdownException) {
            return;
        }
        if (!claimTerminal("예상하지 못한 실패")) {
            return;
        }
        String prefix = where == null ? "" : where + " — ";
        recordedStatus = BatchStatus.FAILED;
        recorder.fail(executionId, snapshot(),
                prefix + cause.getClass().getSimpleName() + ": " + cause.getMessage());
        onTerminal.accept(executionId);
        alertPort.send(AlertEvent.toOperator(
                AlertSeverity.ERROR,
                jobName + " 실패",
                "%s예상하지 못한 오류로 중단했다: %s".formatted(prefix, cause),
                dedupeKey(),
                context()));
    }

    /**
     * 외부 요인으로 중단했음을 기록한다. 일일 쿼터 소진이 대표적이다.
     *
     * <p>실패가 아니라 정상적인 일시정지이므로 {@code WARNING}이며, 다음 실행이 이어서
     * 처리한다는 사실을 본문에 남긴다.
     */
    public void abort(String reason) {
        if (!claimTerminal("외부 요인으로 중단")) {
            return;
        }
        recordedStatus = BatchStatus.ABORTED;
        recorder.abort(executionId, snapshot(), reason);
        onTerminal.accept(executionId);
        alertPort.send(AlertEvent.toOperator(
                AlertSeverity.WARNING,
                jobName + " 중단",
                // 단위가 종목이 아니라 작업 단위다 — finish()의 알림과 같은 이유로 "건"이다.
                "%s — %d건 처리 후 중단했다. 다음 실행이 이어서 처리한다."
                        .formatted(reason, successCount.get()),
                dedupeKey(),
                context()));
    }

    /**
     * MALFORMED가 임계 비율을 넘으면 소스 스펙 변경을 의심해 알린다.
     *
     * <p>개별 종목 실패와 별개의 알림인 이유는 대응이 다르기 때문이다. 부분 실패는 재실행으로
     * 해결되지만, 스펙 변경은 어댑터를 고쳐야 한다.
     */
    private void alertOnSuspectedSpecChange() {
        int malformed = malformedCount.get();
        int processed = successCount.get() + failureCount.get();
        if (malformed > 0 && processed > 0 && (double) malformed / processed >= MALFORMED_ALERT_RATIO) {
            alertPort.send(AlertEvent.toOperator(
                    AlertSeverity.ERROR,
                    jobName + " 소스 응답 해석 실패 다발",
                    "%d/%d 종목의 응답을 해석하지 못했다. 외부 소스의 응답 형식 변경을 의심한다."
                            .formatted(malformed, processed),
                    dedupeKey() + ":malformed",
                    context()));
        }
    }

    /**
     * 프로세스가 내려가서 중단됐음을 기록한다. <b>알리지 않는다.</b>
     *
     * <p>{@link BatchExecutions}의 종료 훅 전용이다. 알림을 보내지 않는 이유는 둘이다 —
     * {@code CompositeAlertPort.send}가 <b>동기</b>이고 웹훅이 connect 5초 + read 10초라
     * 종료 예산을 그만큼 먹고, 종료 경로에서 나는 예외는 마감 자체를 통째로 잃게 만든다.
     * 기록은 남고 로그도 남는다.
     *
     * <p>MUST NOT: "그 시점에 알림 채널의 빈이 이미 파괴됐을 수 있다"를 이유로 되돌리지
     * 않는다. 그것은 {@code @PreDestroy} 시절의 사유이고 지금은 <b>거짓</b>이다 —
     * {@link BatchExecutions}의 리스너는 빈이 살아 있는 것이 프레임워크 계약으로 보장되는
     * 자리에서 돈다.
     */
    void abortOnShutdown(String reason) {
        if (!claimTerminal("종료로 중단")) {
            return;
        }
        // MUST: recorder.abort **앞**이다. 그것은 REQUIRES_NEW DB 왕복이라 종료 중엔 수 초가
        // 될 수 있고, 그 사이 워커는 다음 종목의 외부 호출을 새로 시작한다. 그리고
        // claimTerminal **뒤**여야 한다 — 그 순서가 이 플래그의 불변식이다.
        abandonedOnShutdown = true;
        recordedStatus = BatchStatus.ABORTED;
        recorder.abort(executionId, snapshot(), reason);
        onTerminal.accept(executionId);
    }

    /**
     * 마감할 권리를 한 번만 준다.
     *
     * <p>MUST NOT: 조용히 넘어가지 않는다. 두 번째 마감은 대개 종료 경합이지만 버그일 수도
     * 있고, 그 구분은 로그가 남아야 가능하다. {@code error}가 아니라 {@code warn}인 이유는
     * {@code sentry.logging.minimum-event-level}이 {@code error}라 정상 종료마다 이벤트가
     * 하나씩 쌓이기 때문이다.
     */
    private boolean claimTerminal(String attempted) {
        if (terminal.compareAndSet(false, true)) {
            return true;
        }
        log.warn("이미 마감된 배치 실행을 다시 마감하려 했다. 기록도 알림도 건너뛴다. "
                + "id={} 이미={} 시도={}", executionId, recordedStatus, attempted);
        return false;
    }

    String jobName() {
        return jobName;
    }

    /** 같은 잡·같은 대상일의 알림은 하나로 묶인다. Phase 6의 억제 정책이 이 키를 쓴다. */
    private String dedupeKey() {
        return jobName + ':' + targetDate;
    }

    /**
     * 지금까지의 집계. 진척 기록과 마감이 <b>같은 것</b>을 넘긴다.
     *
     * <p>둘이 다른 것을 넘기면 마감 직후의 화면이 마감 직전과 어긋나고, 그 어긋남은 배치가
     * 끝난 뒤에야 보여서 원인을 되짚기 어렵다.
     *
     * <p>계획을 신고하지 않은 단계는 <b>뺀다.</b> 안 도는 단계까지 0으로 채우면 화면의 단계별
     * 표에 시세·재무·유니버스가 전부 0으로 늘어서고, 그러면 "이 잡은 이 단계를 돌지 않는다"와
     * "돌았는데 아무것도 못 받았다"가 같은 모양이 된다.
     */
    private BatchProgress snapshot() {
        List<PhaseProgress> phases = new ArrayList<>();
        for (Map.Entry<CollectionPhase, PhaseCounters> entry : byPhase.entrySet()) {
            PhaseCounters counters = entry.getValue();
            if (counters.isUntouched()) {
                continue;
            }
            phases.add(new PhaseProgress(entry.getKey(), counters.planned.get(),
                    counters.success.get(), counters.failure.get(), counters.writtenRows.get()));
        }
        return new BatchProgress(successCount.get(), failureCount.get(), writtenRowCount.get(),
                stageLabel, List.copyOf(phases));
    }

    /**
     * 마지막 기록으로부터 {@link #FLUSH_INTERVAL_NANOS}가 지났으면 진척을 남긴다.
     *
     * <p>CAS로 자리를 차지한 스레드만 쓴다. 락 없이 병렬 처리를 견디기 위한 것이고, 진 쪽이
     * 그냥 건너뛰어도 되는 이유는 <b>다음 작업 단위에서 다시 기회가 오기 때문</b>이다.
     */
    private void flushIfDue() {
        long now = System.nanoTime();
        long last = lastFlushNanos.get();
        if (now - last < FLUSH_INTERVAL_NANOS || !lastFlushNanos.compareAndSet(last, now)) {
            return;
        }
        flushNow();
    }

    /**
     * 진척을 지금 남긴다.
     *
     * <p>MUST NOT: 실패를 삼키지 않되, <b>배치를 죽이지도 않는다.</b> 진척 기록은 화면을 위한
     * 부수적인 쓰기이고, 여기서 예외가 올라가면 수집·계산이 통째로 실패한 것으로 기록된다 —
     * 보존 정리를 격리한 것과 같은 판단이다({@code DailyUpdateJob}). {@link DataAccessException}만
     * 잡는 이유는 그 밖의 예외는 우리 코드의 버그이고, 버그는 드러나야 하기 때문이다.
     */
    private void flushNow() {
        if (abandonedOnShutdown) {
            // 마감된 행에는 어차피 안 닿는다(progress의 UPDATE가 `AND status = 'RUNNING'`).
            // 그런데 종료 중에는 이 호출이 CannotCreateTransactionException을 낼 수 있고,
            // 그것은 DataAccessException이 **아니라서** 아래 catch를 그대로 통과한다.
            // 넓히는 대신 부르지 않는다 — 그 catch의 좁음은 "그 밖은 우리 버그다"라는 의도다.
            return;
        }
        lastFlushNanos.set(System.nanoTime());
        try {
            recorder.progress(executionId, snapshot());
        } catch (DataAccessException e) {
            log.warn("배치 진척을 기록하지 못했다. 집계는 메모리에 남아 있고 마감 때 다시 쓴다. id={}",
                    executionId, e);
        }
    }

    /**
     * 이 종목·이 단계에 남아 있던 실패를 해결로 표시한다.
     *
     * <p>어느 잡이든 지나는 자리라 잡마다 흩뿌리지 않는다. 이전에는 {@code daily-update}의
     * 시세 단계 하나에만 붙어 있었고, 그래서 나머지 여섯 단계의 실패는 데이터가 채워진 뒤에도
     * 영영 미해결로 남았다 — spec §4의 "미해결 에러 건수"가 그만큼 거짓이었다.
     *
     * <p>집합에서 빠지지 않으면 아무 일도 하지 않는다. <b>평상시에 UPDATE가 나가지 않는 것이
     * 이 가드의 목적이다.</b>
     */
    private void resolveIfPreviouslyFailed(long stockId, CollectionPhase phase) {
        Set<Long> pending = unresolvedAtStart.get(phase);
        if (pending == null || !pending.remove(stockId)) {
            return;
        }
        int resolved = recorder.resolveFailures(stockId, phase);
        if (resolved > 0) {
            log.debug("이전 실패를 해결로 표시했다. stockId={} phase={} 건수={}",
                    stockId, phase, resolved);
        }
    }

    private static Map<CollectionPhase, PhaseCounters> initPhaseCounters() {
        Map<CollectionPhase, PhaseCounters> counters = new EnumMap<>(CollectionPhase.class);
        for (CollectionPhase phase : CollectionPhase.values()) {
            counters.put(phase, new PhaseCounters());
        }
        return counters;
    }

    /** 한 단계의 카운터. 모든 값을 미리 만들어 두므로 늦은 삽입으로 경합할 일이 없다. */
    private static final class PhaseCounters {
        private final AtomicInteger planned = new AtomicInteger();
        private final AtomicInteger success = new AtomicInteger();
        private final AtomicInteger failure = new AtomicInteger();
        private final AtomicLong writtenRows = new AtomicLong();

        /**
         * 계획을 신고받았는가.
         *
         * <p>{@code planned == 0}으로 대신하지 않는 이유는 <b>0건 계획이 실재하기 때문</b>이다 —
         * 재실행할 실패가 하나도 없는 부분 재실행이 그렇다. 그것을 "신고 안 함"과 묶으면
         * 화면이 분모를 {@code —}로 그려, 계획대로 0건 처리하고 끝난 실행이 계량이 없는
         * 옛 실행과 같은 모양이 된다.
         */
        private volatile boolean declared;

        /** 이 잡이 이 단계를 아예 쓰지 않았는가. */
        private boolean isUntouched() {
            return !declared && success.get() == 0 && failure.get() == 0 && writtenRows.get() == 0;
        }
    }

    private Map<String, String> context() {
        Map<String, String> context = new LinkedHashMap<>();
        context.put("executionId", String.valueOf(executionId));
        context.put("jobName", jobName);
        context.put("targetDate", targetDate.toString());
        return context;
    }
}
