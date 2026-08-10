package com.stockscreener.pipeline.batch.web;

import com.stockscreener.pipeline.batch.BatchExecutionReader;

/**
 * 실행 하나의 단계별 집계 한 줄.
 *
 * <p>합계로는 답할 수 없는 질문을 위해 있다 — "시세가 한 건도 안 들어왔는가"는 합계로 물으면
 * 지표 단계가 쓴 행이 0을 덮는다. 실제로 시세 0행짜리 실행이 979,541행 성공으로 기록됐다.
 *
 * <p>{@code phase}가 문자열인 것은 의도다. 서버가 단계를 하나 더했는데 화면이 낡았을 때, 단계
 * 표가 통째로 죽는 것보다 낯선 이름 한 줄이 섞이는 편이 낫다({@code frontend.md}).
 *
 * @param plannedCount    이 단계에서 돌 예정이던 작업 단위 수
 * @param writtenRowCount 저장한 행 수. <b>단계마다 뜻이 다르다</b> — 시세는 저장한 행이고
 *                        지표는 값이 달라진 행이다. 재계산만 한 실행의 지표 0행은 정상이다.
 */
public record BatchPhaseResponse(
        String phase,
        int plannedCount,
        int successCount,
        int failureCount,
        long writtenRowCount) {

    public static BatchPhaseResponse of(BatchExecutionReader.Phase phase) {
        return new BatchPhaseResponse(
                phase.phase(),
                phase.plannedCount(),
                phase.successCount(),
                phase.failureCount(),
                phase.writtenRowCount());
    }
}
