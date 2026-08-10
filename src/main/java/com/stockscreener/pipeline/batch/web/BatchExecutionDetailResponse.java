package com.stockscreener.pipeline.batch.web;

import java.util.List;

/**
 * 실행 하나의 상세.
 *
 * <p>화면은 여기 실린 것만 그리고 잡 이름으로 분기하지 않는다. {@code phases}가 비면 단계별
 * 표를 안 그리고 {@code failureSummary}가 비면 요약을 안 그린다 — 그래야 잡이 늘어도 화면을
 * 고치지 않는다. {@code retrySupported}를 프론트가 잡 이름으로 판단하지 않게 서버가 주는 것과
 * 같은 규칙이다.
 *
 * @param retrySupported 이 잡이 부분 재실행을 지원하는가. 화면이 버튼을 그릴지 정한다 —
 *                       판단을 프론트가 잡 이름으로 하게 두면 잡이 늘 때마다 화면을 고쳐야 한다.
 * @param phases         단계별 집계. 합계로는 어느 단계가 비었는지 알 수 없다.
 * @param failureSummary 실패를 종류·소스로 묶은 것. 실패가 없으면 빈 목록이다.
 */
public record BatchExecutionDetailResponse(
        BatchExecutionResponse execution,
        List<BatchPhaseResponse> phases,
        List<BatchFailureGroupResponse> failureSummary,
        List<BatchFailureResponse> failures,
        boolean retrySupported) {
}
