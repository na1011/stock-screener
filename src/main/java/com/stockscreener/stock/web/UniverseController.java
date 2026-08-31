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
 * 지금 DB에는 시드 유니버스가 0건으로 남아 있고 미국({@code SP500})은 데이터가 없다 —
 * 그것들을 선택지로 주면 사용자는 고르고 나서야 결과가 빈 이유를 묻게 된다.
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
