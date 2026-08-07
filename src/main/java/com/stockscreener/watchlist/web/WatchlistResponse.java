package com.stockscreener.watchlist.web;

import com.stockscreener.watchlist.WatchlistStore;

import java.time.Instant;

/**
 * 워치리스트 한 줄.
 *
 * <p>종목 정보를 항목 안에 접어 넣지 않고 평평하게 두는 이유는 결과 표(`ScreeningResponse`)와
 * 같다 — 표의 한 행이 곧 이 record 하나이고, 화면이 중첩을 풀어 쓰지 않아도 된다.
 *
 * @param screenId    알림 조건 화면. {@code null}이면 알림 없이 추적만 한다.
 * @param screenName  같은 이유로 {@code null}일 수 있다. 화면이 "알림 없음"으로 그린다.
 * @param screenType  {@code PRESET} / {@code CUSTOM}. 화면이 프리셋 배지를 그릴 때 쓴다 —
 *                    {@code screenId}로 되짚지 않는다({@code frontend.md}).
 * @param satisfied   직전 배치의 판정 결과. {@code null}이면 <b>아직 판정한 적 없다</b>.
 *                    IMPORTANT — false와 다르다. 담은 직후와 "조건에 안 맞음"이 같아 보이면
 *                    사용자는 배치가 돌았는지조차 알 수 없다.
 * @param notifiedAt  마지막으로 알림이 나간 시각. {@code null}이면 아직 알린 적 없다.
 */
public record WatchlistResponse(
        long id,
        long stockId,
        String ticker,
        String stockName,
        String marketCode,
        Long screenId,
        String screenName,
        String screenType,
        String memo,
        Boolean satisfied,
        Instant notifiedAt,
        Instant createdAt
) {

    public static WatchlistResponse of(WatchlistStore.WatchlistRow row) {
        return new WatchlistResponse(
                row.item().id(),
                row.item().stockId(),
                row.ticker(),
                row.stockName(),
                row.marketCode(),
                row.item().screenId(),
                row.screenName(),
                row.screenType(),
                row.item().memo(),
                row.item().lastSatisfied(),
                row.item().lastNotifiedAt(),
                row.createdAt());
    }
}
