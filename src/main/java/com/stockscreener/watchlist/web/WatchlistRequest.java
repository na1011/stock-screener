package com.stockscreener.watchlist.web;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/**
 * 워치리스트 담기·수정 요청.
 *
 * @param stockId  담을 종목. 수정에서는 무시된다 — 종목을 바꾸는 것은 다른 항목을 담는 것이다.
 * @param screenId 알림 조건으로 쓸 화면. {@code null}이면 알림 없이 추적만 한다.
 *                 프리셋이거나 본인 화면이어야 한다 — 남의 화면을 조건으로 걸 수 없다.
 * @param memo     왜 담았는지. 나중에 자기가 왜 담았는지 기억하지 못하는 것이 이 필드의 이유다.
 */
public record WatchlistRequest(
        @NotNull(message = "종목을 지정해야 한다")
        @Positive(message = "종목 id가 올바르지 않다")
        Long stockId,

        Long screenId,

        @Size(max = 500, message = "메모는 500자를 넘을 수 없다")
        String memo
) {
}
