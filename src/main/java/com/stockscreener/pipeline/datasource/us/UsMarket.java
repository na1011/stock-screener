package com.stockscreener.pipeline.datasource.us;

/**
 * 미국 시장의 코드. <b>벤더가 정하지 않는다.</b>
 *
 * <p>미국은 시장이 하나다 — 거래소(NYSE·NASDAQ)는 {@code stock.listing_exchange}가 가르고,
 * 그 둘을 시장으로 갈랐으면 백분위 모집단이 상장 장소로 나뉜다(ADR-0036·0086).
 *
 * <p>이 상수가 어댑터 밖에 있는 이유는 <b>축마다 벤더가 다르기 때문</b>이다. 시세·유니버스는
 * Alpaca, 재무는 SEC EDGAR다. 어느 한 어댑터의 상수를 나머지가 참조하면 「미국의 시장 코드를
 * 그 벤더가 정한다」가 되고, 그 벤더를 바꾸는 날 무관한 어댑터가 함께 흔들린다(ADR-0006).
 */
public final class UsMarket {

    public static final String CODE = "US";

    private UsMarket() {
    }
}
