package com.stockscreener.pipeline.batch.web;

import com.stockscreener.pipeline.batch.BatchExecutionReader;
import com.stockscreener.pipeline.batch.BatchStatus;

import java.time.Instant;
import java.time.LocalDate;

/**
 * 배치 실행 한 건.
 *
 * <p>{@code targetDate}와 {@code startedAt}을 <b>둘 다</b> 싣는다. 새벽에 도는 배치의 대상은
 * 전일이고, 재실행하면 실행 시각은 바뀌지만 대상 일자는 그대로다 — 하나만 주면 화면이
 * "언제 것을 언제 돌렸는가"를 말할 수 없다.
 *
 * @param unresolvedFailureCount 아직 해결되지 않은 실패. 0이면 재실행 버튼을 켜지 않는다.
 * @param retriedFromId          어느 실행을 다시 돌린 것인가. 없으면 최초 실행이다.
 */
public record BatchExecutionResponse(
        long id,
        String jobName,
        LocalDate targetDate,
        BatchStatus status,
        Instant startedAt,
        Instant finishedAt,
        int targetCount,
        int successCount,
        int failureCount,
        long writtenRowCount,
        Long retriedFromId,
        String triggeredBy,
        String note,
        int unresolvedFailureCount) {

    public static BatchExecutionResponse of(BatchExecutionReader.Execution execution) {
        return new BatchExecutionResponse(
                execution.id(),
                execution.jobName(),
                execution.targetDate(),
                execution.status(),
                execution.startedAt(),
                execution.finishedAt(),
                execution.targetCount(),
                execution.successCount(),
                execution.failureCount(),
                execution.writtenRowCount(),
                execution.retriedFromId(),
                execution.triggeredBy(),
                execution.note(),
                execution.unresolvedFailureCount());
    }
}
