package com.stockscreener.pipeline.batch;

import java.util.List;

/**
 * 실행 하나의 진척 스냅샷. 도는 중에도, 마감할 때도 같은 것을 넘긴다.
 *
 * <p><b>단위는 작업 단위(task)다.</b> 작업 단위 하나 = {@code BatchExecutionContext.process()}
 * 호출 하나이며, 이는 실패 격리의 단위(종목 × 단계)와 같다. 대상 <b>종목</b> 수와 혼동하지
 * 않는다 — 그 혼동이 지표 백필을 화면에 {@code 43400/350}으로 띄웠다. 350종목을 124거래일
 * 돌아 43,400번 처리한 것을 종목 수로 나눈 값이었다.
 *
 * <p>계획 수는 여기 따로 두지 않고 {@link #phases}의 합으로 정한다. 총계를 별도 필드로 두면
 * 단계별 계획과 어긋날 수 있고, 어긋난 쪽이 화면의 분모가 되면 처음의 그 혼동으로 돌아간다.
 *
 * @param stageLabel 지금 어느 구간인가. 잡이 자유 문자열로 신고하고 아무도 해석하지 않는다.
 *                   신고하지 않는 잡은 {@code null}이다.
 * @param phases     단계별 집계. 잡이 계획을 신고하지 않았으면 비어 있고, 그때 화면의 분모는
 *                   {@code —}가 된다. 0으로 채우지 않는다 — "안 신고했다"와 "0건 계획"은 다르다.
 */
public record BatchProgress(
        int successCount,
        int failureCount,
        long writtenRowCount,
        String stageLabel,
        List<PhaseProgress> phases) {

    /** 계획된 작업 단위 수. 신고가 없으면 {@code null}이며 그대로 컬럼에 들어간다. */
    public Integer plannedTaskCount() {
        if (phases.isEmpty()) {
            return null;
        }
        return phases.stream().mapToInt(PhaseProgress::plannedCount).sum();
    }
}
