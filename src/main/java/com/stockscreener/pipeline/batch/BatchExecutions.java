package com.stockscreener.pipeline.batch;

import com.stockscreener.notification.AlertPort;

import org.springframework.stereotype.Component;

import java.time.LocalDate;

/**
 * 배치 실행을 시작해 {@link BatchExecutionContext}를 만든다.
 *
 * <p>잡이 recorder·executor·alertPort를 각각 주입받아 조립하지 않게 하는 이유는, 조립을
 * 잡마다 반복하면 어느 잡은 알림 포트를 빼먹기 때문이다. 그 잡의 실패는 기록만 되고
 * 통지되지 않는데, 그 사실은 실패가 실제로 일어나기 전까지 드러나지 않는다.
 */
@Component
public class BatchExecutions {

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
        return new BatchExecutionContext(
                executionId, jobName, targetDate, executor, recorder, alertPort);
    }

    /** 실패한 부분만 다시 도는 실행을 시작한다. */
    public BatchExecutionContext startRetry(String jobName, LocalDate targetDate,
                                            int targetCount, long retriedFromId) {
        long executionId = recorder.startRetry(jobName, targetDate, targetCount, retriedFromId);
        return new BatchExecutionContext(
                executionId, jobName, targetDate, executor, recorder, alertPort);
    }

    public BatchExecutionRecorder recorder() {
        return recorder;
    }
}
