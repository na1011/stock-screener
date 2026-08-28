package com.stockscreener.pipeline.datasource;

import java.time.LocalDate;

/**
 * 소스가 준 종목 마스터 정보. {@code stock} 행을 만들거나 갱신하는 데 쓴다.
 *
 * <p>유니버스에 새로 편입된 종목은 아직 {@code stock} 행이 없다. 리밸런싱 배치가 티커만 알고
 * 있으므로, 이름·섹터·상장일을 채우려면 이 포트가 필요하다.
 *
 * <p>{@code marketCode}를 소스가 정하는 이유는 티커만으로는 시장을 알 수 없기 때문이다 —
 * 코스피와 코스닥은 같은 6자리 숫자 체계를 쓴다.
 *
 * <p>{@code listingExchange}는 <b>시장 안에서 거래소를 가른다.</b> 미국은 {@code market_code}가
 * {@code US} 하나이고 NYSE·NASDAQ이 그 안에 있어, 마켓 캘린더를 거래소마다 따로 조회하려면
 * 이 값이 필요하다. 국내는 시장이 곧 거래소라 {@code null}이다.
 */
public record SecurityDescriptor(
        String marketCode,
        String ticker,
        String name,
        String nameEn,
        String sector,
        LocalDate listedOn,
        LocalDate delistedOn,
        String listingExchange
) {

    public SecurityDescriptor {
        if (marketCode == null || marketCode.isBlank()) {
            throw new IllegalArgumentException("marketCode가 비어 있다. ticker=" + ticker);
        }
        if (ticker == null || ticker.isBlank()) {
            throw new IllegalArgumentException("ticker가 비어 있다.");
        }
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("name이 비어 있다. ticker=" + ticker);
        }
    }
}
