package com.stockscreener.pipeline.batch.web;

import java.util.List;

/**
 * 실행 하나의 상세.
 *
 * @param retrySupported 이 잡이 부분 재실행을 지원하는가. 화면이 버튼을 그릴지 정한다 —
 *                       판단을 프론트가 잡 이름으로 하게 두면 잡이 늘 때마다 화면을 고쳐야 한다.
 */
public record BatchExecutionDetailResponse(
        BatchExecutionResponse execution,
        List<BatchFailureResponse> failures,
        boolean retrySupported) {
}
