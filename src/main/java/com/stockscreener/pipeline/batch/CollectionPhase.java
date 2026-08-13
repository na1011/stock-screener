package com.stockscreener.pipeline.batch;

/**
 * 종목 하나에 대해 수행하는 수집 단계. {@code batch_execution_failure.failed_phase}의 값이다.
 *
 * <p>단계를 나누는 이유는 재실행 대상을 좁히기 위해서다. 한 종목이 시세는 성공하고 재무만
 * 실패할 수 있는데, 이를 구분하지 못하면 재실행이 이미 성공한 시세를 다시 받아 쿼터를 태운다.
 *
 * <p>MUST: 값을 추가하면 {@code V5__batch_execution.sql}의 {@code ck_failure_phase}도
 * 함께 늘려야 한다. 제약이 먼저 막으므로 조용히 어긋나지는 않는다.
 */
public enum CollectionPhase {

    /** 일별 시세 수집. */
    PRICE,

    /** 재무제표 수집. */
    FINANCIAL,

    /** 지수 구성종목 반영. 종목 단위가 아니라 지수 단위로 실패한다. */
    UNIVERSE,

    /**
     * 시장 지수 시계열 수집(ADR-0053).
     *
     * <p>MUST NOT: 이 단계를 {@link com.stockscreener.pipeline.batch.BatchExecutionContext#process}로
     * 돌리지 않는다. {@code batch_execution_failure.stock_id}가 <b>NOT NULL + FK</b>라 종목 밖
     * 실패에는 넣을 값이 없고, 시도하면 지수 수집 실패 하나가 <b>일일 갱신 전체를 죽인다.</b>
     * {@code UNIVERSE}와 같은 자리이며 집계는 {@code countExternally}, 통지는
     * {@code alertOperator}로 간다.
     *
     * <p>실패 격리의 단위가 종목이라는 원칙이 <b>원리적으로 성립하지 않는 첫 입력</b>이다 —
     * 지수 한 줄이 없으면 그날 전 종목의 {@code EXCESS_RETURN_26W}가 안 쓰인다.
     */
    MARKET_INDEX,

    /** 종목 마스터(이름·섹터·상장일) 수집. */
    SECURITY_MASTER,

    /**
     * 기업 행위 수집과 원장 기록.
     *
     * <p>MUST NOT: 이 단계를 "수정주가 재계산"으로 읽지 않는다. 계수를 다시 쓰는 것은
     * {@code AdjustmentRecalculator.rebuild} 하나뿐이고 <b>이 단계는 그것을 부르지 않는다</b>
     * (ADR-0049). 이 단계가 성공했는데 {@code adj_factor}가 그대로인 것이 정상이다.
     */
    ADJUSTMENT,

    /**
     * 수집한 원자료로부터의 지표 계산.
     *
     * <p>외부 호출이 없는 유일한 단계다. 그래도 같은 자리에 두는 이유는 실패의 성격이 같기
     * 때문이다 — 한 종목의 계산이 터져도 나머지는 저장되어야 하고, 어느 종목이 왜 실패했는지
     * 남아야 관리자가 그 종목만 다시 돌릴 수 있다.
     */
    INDICATOR
}
