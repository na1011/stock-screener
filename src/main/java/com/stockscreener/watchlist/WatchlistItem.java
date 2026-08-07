package com.stockscreener.watchlist;

import java.time.Instant;

/**
 * 워치리스트 항목 하나.
 *
 * @param screenId       알림 조건으로 쓸 화면. {@code null}이면 알림 없이 추적만 한다 —
 *                       V3가 그 컬럼을 nullable로 둔 의미가 그것이다.
 * @param lastSatisfied  직전 배치의 판정 결과. {@code null}이면 아직 판정한 적 없다.
 *                       엣지 트리거가 이 값을 보고 "거짓→참"을 가른다(ADR-0026).
 * @param lastNotifiedAt 마지막으로 알린 시각. 억제 판정은 {@code lastSatisfied}가 하고
 *                       이 값은 화면 표시용이다.
 */
public record WatchlistItem(
        long id,
        long userId,
        long stockId,
        Long screenId,
        String memo,
        Boolean lastSatisfied,
        Instant lastNotifiedAt
) {

    /** 알림 조건이 걸려 있는가. 걸려 있지 않으면 배치가 판정 대상으로 삼지 않는다. */
    public boolean hasAlertCondition() {
        return screenId != null;
    }

    /**
     * 직전 판정이 참이었는가. {@code null}(미판정)은 거짓으로 읽는다.
     *
     * <p>이 해석이 "방금 담은 종목이 이미 조건을 만족하면 첫 배치에서 알린다"를 만든다.
     * 사용자는 그 종목이 조건에 맞는지 궁금해서 담았으므로 그것이 맞는 동작이다.
     */
    public boolean wasSatisfied() {
        return Boolean.TRUE.equals(lastSatisfied);
    }
}
