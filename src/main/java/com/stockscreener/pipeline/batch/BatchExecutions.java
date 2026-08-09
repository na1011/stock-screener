package com.stockscreener.pipeline.batch;

import com.stockscreener.notification.AlertPort;

import jakarta.annotation.PreDestroy;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 배치 실행을 시작해 {@link BatchExecutionContext}를 만든다.
 *
 * <p>잡이 recorder·executor·alertPort를 각각 주입받아 조립하지 않게 하는 이유는, 조립을
 * 잡마다 반복하면 어느 잡은 알림 포트를 빼먹기 때문이다. 그 잡의 실패는 기록만 되고
 * 통지되지 않는데, 그 사실은 실패가 실제로 일어나기 전까지 드러나지 않는다.
 */
@Component
public class BatchExecutions {

    private static final Logger log = LoggerFactory.getLogger(BatchExecutions.class);

    /**
     * 아직 마감되지 않은 실행.
     *
     * <p>IMPORTANT — 이것이 없으면 <b>프로세스가 내려갈 때 열린 행이 영원히 RUNNING으로
     * 남는다.</b> 마감은 {@link BatchExecutionContext}가 살아 있는 JVM 안에서만 하기
     * 때문이고, devtools 재기동·SIGTERM·launchd 재기동이 전부 그 경로다. 실제로 그렇게
     * 쌓인 고아 행이 10건이었다.
     *
     * <p>이 집합은 <b>단일 실행 판정에 쓰지 않는다.</b> 그 정본은 {@link AdminJobLauncher}에
     * 있고, 둘을 겸하면 잡이 죽었을 때 다시 띄우지 못하게 된다(ADR-0025의 단일 인스턴스 전제).
     */
    private final Map<Long, BatchExecutionContext> open = new ConcurrentHashMap<>();

    private final StockTaskExecutor executor;
    private final BatchExecutionRecorder recorder;
    private final AlertPort alertPort;

    public BatchExecutions(StockTaskExecutor executor, BatchExecutionRecorder recorder,
                           AlertPort alertPort) {
        this.executor = executor;
        this.recorder = recorder;
        this.alertPort = alertPort;
    }

    /** 새 실행을 시작한다. */
    public BatchExecutionContext start(String jobName, LocalDate targetDate,
                                       BatchExecutionRecorder.TriggeredBy triggeredBy,
                                       int targetCount) {
        long executionId = recorder.start(jobName, targetDate, triggeredBy, targetCount);
        return track(new BatchExecutionContext(
                executionId, jobName, targetDate, executor, recorder, alertPort, open::remove));
    }

    /** 실패한 부분만 다시 도는 실행을 시작한다. */
    public BatchExecutionContext startRetry(String jobName, LocalDate targetDate,
                                            int targetCount, long retriedFromId) {
        long executionId = recorder.startRetry(jobName, targetDate, targetCount, retriedFromId);
        return track(new BatchExecutionContext(
                executionId, jobName, targetDate, executor, recorder, alertPort, open::remove));
    }

    private BatchExecutionContext track(BatchExecutionContext context) {
        open.put(context.executionId(), context);
        return context;
    }

    /**
     * 내려가기 전에 아직 열려 있는 실행을 {@code ABORTED}로 닫는다.
     *
     * <p>이 빈은 {@code BatchExecutionRecorder} → {@code JdbcTemplate} → {@code DataSource}에
     * 의존하므로 <b>그것들보다 먼저 파괴된다.</b> 그래서 여기서 DB 쓰기가 아직 가능하다.
     * 의존 방향이 바뀌면 이 전제가 깨지고, 깨진 사실은 다음 비정상 종료 때까지 드러나지 않는다.
     *
     * <p>MUST NOT: 여기서 예외가 밖으로 나가게 두지 않는다. 하나가 실패해도 나머지는 닫아야
     * 하고, 종료 경로에서 터지는 예외는 마감 자체를 통째로 잃게 만든다.
     */
    @PreDestroy
    void closeOpenExecutions() {
        for (BatchExecutionContext context : open.values()) {
            try {
                context.abortOnShutdown("프로세스가 종료돼 중단됐다. 남은 구간은 다음 실행이 처리한다.");
                log.info("내려가면서 열린 배치 실행을 마감했다. id={} job={}",
                        context.executionId(), context.jobName());
            } catch (RuntimeException e) {
                log.warn("열린 배치 실행을 마감하지 못했다. id={} — RUNNING으로 남는다. "
                        + "BatchExecutionSweeper가 나중에 정리한다.", context.executionId(), e);
            }
        }
        open.clear();
    }

    public BatchExecutionRecorder recorder() {
        return recorder;
    }
}
