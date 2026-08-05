package com.stockscreener.pipeline.collector;

import java.time.LocalDate;

/**
 * 원자료가 소급 변경되었을 때 그것에서 파생된 계산 결과를 무효화한다.
 *
 * <p>Phase 2가 이 포트를 <b>정의하고 호출</b>하며, Phase 3이 구현한다. 지금은
 * {@link NoOpIndicatorInvalidationPort}가 로그만 남긴다 — {@code indicator_value}에 아직
 * 행이 없어 지울 것이 없기 때문이다.
 *
 * <p>IMPORTANT — 구현이 Phase 3이어도 <b>호출 지점은 Phase 2에 있어야 한다.</b> 이 이음매가
 * 없으면 Phase 3에서 분할 재계산과 지표 재계산이 서로를 모르는 채로 구현되고, 그 어긋남은
 * 액면분할이 실제로 일어나기 전까지 드러나지 않는다. ADR-0002가 경고한 것과 같은 함정이며,
 * 그때는 이미 잘못된 지표로 스크리닝한 뒤다.
 */
public interface IndicatorInvalidationPort {

    /**
     * 해당 종목의 {@code fromDate} 이후 저장된 지표를 무효화한다.
     *
     * <p>Phase 3의 구현은 {@code DELETE FROM indicator_value WHERE stock_id = ? AND
     * trade_date >= ?} + 재계산 큐 등록이 될 것이다. 부분 삭제인 이유는 분할이 영향을 주는
     * 구간이 분할일 이전 전체이고, 그 구간을 워밍업으로 쓰는 지표까지 함께 무효화해야 하기
     * 때문이다.
     */
    void invalidate(long stockId, LocalDate fromDate, InvalidationReason reason);

    enum InvalidationReason {
        /** 액면분할·병합으로 수정주가가 다시 쓰였다. */
        PRICE_ADJUSTED,
        /** 정정공시로 재무 수치가 바뀌었다. */
        FINANCIAL_REVISED
    }
}
