package com.stockscreener.stock.web;

import com.stockscreener.stock.StockReader;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 스크리닝 대상으로 고를 수 있는 유니버스.
 *
 * <p>편입 종목이 하나도 없는 유니버스는 나오지 않는다({@code StockReader.selectableUniverses}).
 * 그것을 선택지로 주면 사용자는 고르고 나서야 결과가 빈 이유를 묻게 된다.
 *
 * <p><b>술어는 편입이지 시장이 아니다.</b> 미국은 {@code US_TOP1000}으로 목록에 뜨고
 * ({@code SP500}은 편입 0건이라 빠진다), <b>{@code MarketScope}가 「전체」의 시장·통화를
 * 정할 때와 같은 술어</b>다 — 갈리면 화면이 못 고르는 대상이 기준일과 통화를 정한다.
 */
@RestController
@RequestMapping("/api/universes")
public class UniverseController {

    private final StockReader stockReader;

    public UniverseController(StockReader stockReader) {
        this.stockReader = stockReader;
    }

    @GetMapping
    public List<UniverseResponse> list() {
        return stockReader.selectableUniverses().stream()
                .map(u -> new UniverseResponse(u.code(), u.name(), u.marketCode(),
                        u.currencyCode(), u.memberCount()))
                .toList();
    }

    public record UniverseResponse(String code, String name, String marketCode,
                                   String currencyCode, int memberCount) {
    }
}
