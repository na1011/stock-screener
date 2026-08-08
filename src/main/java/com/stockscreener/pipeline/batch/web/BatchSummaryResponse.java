package com.stockscreener.pipeline.batch.web;

import java.util.List;

/**
 * 운영 현황 요약 (spec §4의 첫 관리자 요구사항).
 *
 * @param latestByJob             잡별 <b>가장 최근</b> 실행 한 건씩. 목록의 앞부분을 자르지
 *                                않는 이유는, 일일 갱신이 자주 돌면 유니버스 리밸런싱의 마지막
 *                                결과가 밀려나 "그 잡이 언제 마지막으로 성공했는가"를 볼 수
 *                                없게 되기 때문이다.
 * @param unresolvedFailureCount  미해결 에러 건수. 재실행에 성공한 실패는 여기서 빠진다.
 * @param runningJobs             지금 도는 중인 잡 이름.
 */
public record BatchSummaryResponse(
        List<BatchExecutionResponse> latestByJob,
        long unresolvedFailureCount,
        List<String> runningJobs) {
}
