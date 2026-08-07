package com.stockscreener.stock.web;

import com.stockscreener.stock.StockReader;

import java.util.List;

/**
 * 검색 결과 한 건.
 *
 * <p>지표 값을 싣지 않는다. 검색은 "어느 종목으로 갈지 고르는" 단계이고, 값이 필요하면 상세로
 * 가면 된다 — 여기에 값을 실으면 한 글자 칠 때마다 지표 조회가 따라붙는다.
 *
 * @param universeCodes 현재 편입되어 있는 유니버스. 같은 이름의 종목을 가르는 단서가 된다.
 */
public record StockSearchResponse(long id, String ticker, String name, String marketCode,
                                  List<String> universeCodes) {

    public static StockSearchResponse of(StockReader.SearchRow row) {
        return new StockSearchResponse(row.id(), row.ticker(), row.name(), row.marketCode(),
                row.universeCodes());
    }
}
