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
 * @param staleJobs               예산을 넘겨 <b>안 돈</b> 잡. 오래 밀린 것부터.
 *                                <b>{@code latestByJob}으로는 이것을 못 낸다</b> —
 *                                그 목록은 <b>돈 것</b>만 담고, 예산은 서버에만 있다.
 * @param approvedWithoutCoefficient 원장이 승인됐는데 계수가 안 들어간 행 수.
 *                                <b>사슬이 끊겼다는 것을 재는 유일한 술어다</b>
 *                                ({@code JobFreshnessPolicy}의 javadoc).
 */
public record BatchSummaryResponse(
        List<BatchExecutionResponse> latestByJob,
        long unresolvedFailureCount,
        List<String> runningJobs,
        List<StaleJobResponse> staleJobs,
        long approvedWithoutCoefficient) {
}
