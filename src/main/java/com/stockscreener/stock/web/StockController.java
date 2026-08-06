package com.stockscreener.stock.web;

import com.stockscreener.indicator.IndicatorCatalog;
import com.stockscreener.indicator.IndicatorDataReader;
import com.stockscreener.indicator.IndicatorDescriptor;
import com.stockscreener.screening.query.ScreeningRepository;
import com.stockscreener.stock.PriceReference;
import com.stockscreener.stock.StockReader;
import com.stockscreener.stock.SupportResistanceAnalyzer;
import com.stockscreener.web.ApiExceptions;
import com.stockscreener.web.AsOfResolver;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 종목 상세와 지지·저항 참고정보.
 *
 * <p>둘을 <b>다른 엔드포인트</b>로 나눈 이유는 {@link StockDetailResponse}의 주석에 있다.
 * 요약하면 오류 격리와 "지표가 아님"의 구조적 표현이다.
 */
@RestController
@RequestMapping("/api/stocks")
public class StockController {

    private final StockReader stockReader;
    private final IndicatorCatalog indicatorCatalog;
    private final ScreeningRepository screeningRepository;
    private final IndicatorDataReader priceReader;
    private final SupportResistanceAnalyzer analyzer;
    private final AsOfResolver asOfResolver;

    public StockController(StockReader stockReader, IndicatorCatalog indicatorCatalog,
                           ScreeningRepository screeningRepository, IndicatorDataReader priceReader,
                           SupportResistanceAnalyzer analyzer, AsOfResolver asOfResolver) {
        this.stockReader = stockReader;
        this.indicatorCatalog = indicatorCatalog;
        this.screeningRepository = screeningRepository;
        this.priceReader = priceReader;
        this.analyzer = analyzer;
        this.asOfResolver = asOfResolver;
    }

    @GetMapping("/{stockId}")
    public StockDetailResponse detail(
            @PathVariable long stockId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asOf) {

        LocalDate resolved = asOfResolver.resolve(asOf);
        StockReader.StockRow row = stockReader.findById(stockId, resolved)
                .orElseThrow(() -> new ApiExceptions.StockNotFoundException(stockId));

        List<IndicatorDescriptor> catalog = indicatorCatalog.activeIndicators();
        Map<String, BigDecimal> values = screeningRepository
                .loadIndicatorValues(List.of(stockId),
                        catalog.stream().map(IndicatorDescriptor::code)
                                .collect(java.util.stream.Collectors.toCollection(java.util.LinkedHashSet::new)),
                        resolved)
                .getOrDefault(stockId, Map.of());

        // 값이 없는 지표는 행 자체를 내보내지 않는다. null 값을 주면 화면이 "0"이나 "-"로
        // 그리는데, "계산되지 않았다"와 "값이 0이다"는 다른 사실이다.
        List<StockDetailResponse.IndicatorValue> indicators = new ArrayList<>();
        for (IndicatorDescriptor descriptor : catalog) {
            BigDecimal value = values.get(descriptor.code());
            if (value == null) {
                continue;
            }
            indicators.add(new StockDetailResponse.IndicatorValue(
                    descriptor.code(), descriptor.name(), value, descriptor.unit(),
                    descriptor.decimalScale(), descriptor.category(), descriptor.higherIsBetter(),
                    descriptor.description(), descriptor.interpretation()));
        }

        StockDetailResponse.Price price = stockReader.priceOn(stockId, resolved)
                .map(p -> new StockDetailResponse.Price(p.tradeDate(), p.open(), p.high(), p.low(),
                        p.close(), p.volume(), p.tradeValue(), p.listedShares()))
                .orElse(null);

        return new StockDetailResponse(resolved,
                StockDetailResponse.Stock.of(row, stockReader.currentUniversesOf(stockId)),
                price, indicators);
    }

    /**
     * 지지·저항 참고정보.
     *
     * <p>IMPORTANT — 응답의 {@code kind}·{@code method}·{@code disclaimer}가 필수 필드다.
     * 프론트가 한계 표시를 빠뜨릴 수 없게 하는 가장 싼 방법이다. 진입가·손절가에 해당하는
     * 단일 값은 어떤 형태로도 내지 않는다(spec §10).
     */
    @GetMapping("/{stockId}/price-reference")
    public PriceReference priceReference(
            @PathVariable long stockId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asOf) {

        LocalDate resolved = asOfResolver.resolve(asOf);
        stockReader.findById(stockId, resolved)
                .orElseThrow(() -> new ApiExceptions.StockNotFoundException(stockId));

        // 검증된 읽기 경로를 재사용한다 — "오름차순 LIMIT은 가장 오래된 N봉을 준다"는 함정을
        // 다시 밟지 않기 위해서다.
        return analyzer.analyze(priceReader.readPriceBars(
                stockId, resolved, SupportResistanceAnalyzer.WINDOW_BARS));
    }
}
