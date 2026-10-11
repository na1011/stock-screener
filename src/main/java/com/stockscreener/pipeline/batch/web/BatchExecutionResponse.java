package com.stockscreener.pipeline.batch.web;

import com.stockscreener.pipeline.batch.BatchExecutionReader;
import com.stockscreener.pipeline.batch.BatchStatus;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * 배치 실행 한 건.
 *
 * <p>{@code targetDate}와 {@code startedAt}을 <b>둘 다</b> 싣는다. 새벽에 도는 배치의 대상은
 * 전일이고, 재실행하면 실행 시각은 바뀌지만 대상 일자는 그대로다 — 하나만 주면 화면이
 * "언제 것을 언제 돌렸는가"를 말할 수 없다.
 *
 * <p>{@code targetCount}와 {@code plannedTaskCount}를 <b>둘 다</b> 싣는다. 앞엣것은 대상
 * <b>종목</b> 수이고 뒤엣것은 <b>작업 단위</b> 수다 — 한 종목이 여러 단계·여러 날짜로
 * 처리되므로 둘은 배수만큼 다르다. 하나만 주면 화면이 종목 수를 진행률의 분모로 쓰게 되고,
 * 그것이 지표 백필을 {@code 43400/350}으로 그렸다(ADR-0037).
 *
 * @param unresolvedFailureCount 아직 해결되지 않은 실패. 0이면 재실행 버튼을 켜지 않는다.
 * @param retriedFromId          어느 실행을 다시 돌린 것인가. 없으면 최초 실행이다.
 * @param plannedTaskCount       진행률의 분모. 계량이 없는 실행은 {@code null}이며 0으로
 *                               메우지 않는다 — 0이면 화면이 분모 0의 진행률을 그린다.
 * @param progressAt             마지막 진척 반영 시각. 화면이 "돌고 있는가 멎었는가"를 이걸로 가른다.
 * @param stageLabel             지금 어느 구간인가. 서버가 준 문자열을 화면이 해석 없이 보인다.
 * @param scope                  신선도 시계에 세는가(V63 · V65). 표지 전 실행은 {@code null}이다 — 「모름」이고 FULL로 접지 않는다.
 *                               MUST NOT: 빼지 않는다 — 화면의 {@code scopeNote}가 모르는 값(undefined 포함)에 던져 이력 표가
 *                               통째로 깨진다.
 * @param scopeReason            {@code NARROW}인 이유(V65 · ADR-0174). 화면이 이것으로 덧말을 고른다 — note를 파싱하지 않는다.
 * @param incompleteMarkets      마감 때 미완료로 판정한 시장. 없으면 빈 목록이다.
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
        Integer plannedTaskCount,
        Instant progressAt,
        String stageLabel,
        Long retriedFromId,
        String triggeredBy,
        String scope,
        String scopeReason,
        List<String> incompleteMarkets,
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
                execution.plannedTaskCount(),
                execution.progressAt(),
                execution.stageLabel(),
                execution.retriedFromId(),
                execution.triggeredBy(),
                execution.scope(),
                execution.scopeReason(),
                execution.incompleteMarkets(),
                execution.note(),
                execution.unresolvedFailureCount());
    }
}
