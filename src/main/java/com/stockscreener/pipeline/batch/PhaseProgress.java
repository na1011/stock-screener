package com.stockscreener.pipeline.batch;

/**
 * 한 단계가 지금까지 처리한 것. {@code batch_execution_phase} 한 행에 대응한다.
 *
 * <p>합계와 <b>별도로</b> 세는 이유는 합계로는 "그 단계가 실제로 무언가를 받아왔는가"를
 * 판정할 수 없기 때문이다. 시세가 0행이어도 지표 단계가 기존 시계열을 다시 써서 종목당 수천
 * 행을 남긴다 — 실제로 그 상황이 979,541행짜리 성공으로 기록되어 금요일 종가 결손이
 * 아무에게도 알려지지 않았다.
 *
 * @param plannedCount    이 단계에서 돌 예정인 작업 단위 수
 * @param writtenRowCount 저장한 행 수. <b>단계마다 뜻이 다르다</b> — {@code PRICE}는 저장한
 *                        행이고 {@code INDICATOR}는 <b>값이 달라진</b> 행이다(지표 쓰기가 같은
 *                        값을 다시 쓰지 않는다). 재계산만 한 실행이 지표 0행인 것은
 *                        "계산하지 않았다"가 아니라 "바뀐 것이 없다"는 뜻이다.
 */
public record PhaseProgress(
        CollectionPhase phase,
        int plannedCount,
        int successCount,
        int failureCount,
        long writtenRowCount) {
}
