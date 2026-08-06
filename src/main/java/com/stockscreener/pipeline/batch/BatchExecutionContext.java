package com.stockscreener.pipeline.batch;

import com.stockscreener.notification.AlertEvent;
import com.stockscreener.notification.AlertPort;
import com.stockscreener.notification.AlertSeverity;
import com.stockscreener.pipeline.datasource.DataSourceException;
import com.stockscreener.pipeline.datasource.SecurityRef;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

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

    /**
     * 소스 스펙 변경을 의심할 실패 비율. 이 비율을 넘는 MALFORMED가 나오면 별도로 알린다.
     *
     * <p>한 종목이 MALFORMED면 그 종목의 데이터 문제지만, 대량이면 벤더가 응답 형식을
     * 바꿨다는 신호이며 spec §7이 우려한 바로 그 사건이다.
     */
    private static final double MALFORMED_ALERT_RATIO = 0.2;

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

    public BatchExecutionContext(long executionId, String jobName, LocalDate targetDate,
                                 StockTaskExecutor executor, BatchExecutionRecorder recorder,
                                 AlertPort alertPort) {
        this.executionId = executionId;
        this.jobName = jobName;
        this.targetDate = targetDate;
        this.executor = executor;
        this.recorder = recorder;
        this.alertPort = alertPort;
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
        StockTaskExecutor.Outcome outcome = executor.execute(executionId, security, phase, task);
        if (outcome.succeeded()) {
            successCount.incrementAndGet();
            writtenRowCount.addAndGet(outcome.writtenRows());
            return true;
        }
        failureCount.incrementAndGet();
        return false;
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
     * 정상 종료로 마감하고, 실패가 있으면 <b>요약 한 건</b>을 알린다.
     *
     * <p>이 메서드가 상태 판정과 알림을 함께 하는 것이 핵심이다. 둘을 분리하면 마감은 했는데
     * 알리지 않은 경로가 생기고, 그 배치의 실패는 아무도 모르게 된다.
     */
    public BatchStatus finish() {
        BatchStatus status = recorder.finish(
                executionId, successCount.get(), failureCount.get(), writtenRowCount.get());

        if (status == BatchStatus.PARTIAL_SUCCESS) {
            alertPort.send(AlertEvent.toOperator(
                    AlertSeverity.WARNING,
                    jobName + " 부분 실패",
                    "%d종목 성공, %d종목 실패 (대상일 %s)"
                            .formatted(successCount.get(), failureCount.get(), targetDate),
                    dedupeKey(),
                    context()));
        }
        alertOnSuspectedSpecChange();
        return status;
    }

    /**
     * 복구 불가능한 실패로 마감하고 즉시 알린다.
     *
     * <p>{@code CRITICAL}인 이유는 방치하면 데이터가 계속 갱신되지 않기 때문이다.
     * 부분 실패와 달리 다음 실행이 스스로 회복하지 못한다.
     */
    public void failFatally(DataSourceException cause) {
        recorder.fail(executionId, successCount.get(), failureCount.get(), writtenRowCount.get(),
                cause.kind() + ": " + cause.getMessage());
        alertPort.send(AlertEvent.toOperator(
                AlertSeverity.CRITICAL,
                jobName + " 실패",
                "복구 불가능한 오류로 중단했다: %s (%s) — %s"
                        .formatted(cause.kind(), cause.sourceName(), cause.getMessage()),
                dedupeKey(),
                context()));
    }

    /** 예상하지 못한 오류로 마감한다. 우리 코드의 버그일 가능성이 높다. */
    public void failUnexpectedly(Throwable cause) {
        recorder.fail(executionId, successCount.get(), failureCount.get(), writtenRowCount.get(),
                cause.getClass().getSimpleName() + ": " + cause.getMessage());
        alertPort.send(AlertEvent.toOperator(
                AlertSeverity.ERROR,
                jobName + " 실패",
                "예상하지 못한 오류로 중단했다: %s".formatted(cause),
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
        recorder.abort(executionId, successCount.get(), failureCount.get(), writtenRowCount.get(),
                reason);
        alertPort.send(AlertEvent.toOperator(
                AlertSeverity.WARNING,
                jobName + " 중단",
                "%s — %d종목 처리 후 중단했다. 다음 실행이 이어서 처리한다."
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

    /** 같은 잡·같은 대상일의 알림은 하나로 묶인다. Phase 6의 억제 정책이 이 키를 쓴다. */
    private String dedupeKey() {
        return jobName + ':' + targetDate;
    }

    private Map<String, String> context() {
        Map<String, String> context = new LinkedHashMap<>();
        context.put("executionId", String.valueOf(executionId));
        context.put("jobName", jobName);
        context.put("targetDate", targetDate.toString());
        return context;
    }
}
