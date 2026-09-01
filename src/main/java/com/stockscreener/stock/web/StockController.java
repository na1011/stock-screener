package com.stockscreener.stock.web;

import com.stockscreener.indicator.IndicatorCatalog;
import com.stockscreener.indicator.IndicatorDataReader;
import com.stockscreener.indicator.IndicatorDescriptor;
import com.stockscreener.indicator.UnavailabilityRule;
import com.stockscreener.screening.query.ScreeningRepository;
import com.stockscreener.stock.PriceReference;
import com.stockscreener.stock.StockReader;
import com.stockscreener.stock.SupportResistanceAnalyzer;
import com.stockscreener.web.ApiExceptions;
import com.stockscreener.web.AsOfResolver;
import com.stockscreener.web.MarketScope;

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
    private final MarketScope marketScope;
    private final StockFinancialsAssembler financialsAssembler;
    private final List<UnavailabilityRule> unavailabilityRules;

    public StockController(StockReader stockReader, IndicatorCatalog indicatorCatalog,
                           ScreeningRepository screeningRepository, IndicatorDataReader priceReader,
                           SupportResistanceAnalyzer analyzer, AsOfResolver asOfResolver,
                           MarketScope marketScope,
                           StockFinancialsAssembler financialsAssembler,
                           List<UnavailabilityRule> unavailabilityRules) {
        this.stockReader = stockReader;
        this.indicatorCatalog = indicatorCatalog;
        this.screeningRepository = screeningRepository;
        this.priceReader = priceReader;
        this.analyzer = analyzer;
        this.asOfResolver = asOfResolver;
        this.marketScope = marketScope;
        this.financialsAssembler = financialsAssembler;
        this.unavailabilityRules = List.copyOf(unavailabilityRules);
    }

    /** 한 번에 돌려주는 최대 건수. 검색은 고르기 위한 것이지 훑어보기 위한 것이 아니다. */
    private static final int DEFAULT_SEARCH_LIMIT = 20;
    private static final int MAX_SEARCH_LIMIT = 50;
    private static final int MAX_QUERY_LENGTH = 50;

    /**
     * 실적 표가 한 번에 주는 기(期) 수.
     *
     * <p>연간 3 + 분기 12 = 15에 여유를 둔 값이다. 상한이 필요한 이유는 실측으로 종목당 평균
     * <b>30.9행·최대 37행</b>이기 때문이다 — 모바일 패널에 37행을 그리지 않는다.
     */
    private static final int FINANCIAL_PERIODS = 20;

    /**
     * 종목명·티커 검색.
     *
     * <p>일치가 없으면 404가 아니라 빈 배열이다 — "검색 결과 없음"은 오류가 아니고, 404로
     * 주면 프론트가 그것을 재시도 불가 오류로 다루게 된다.
     *
     * <p>검증 실패를 스프링 기본 예외로 흘리지 않고 {@code INVALID_REQUEST}로 던지는 이유는
     * ADR-0015에 있다 — 프론트는 {@code type}(URN)으로 분기하는데, 기본 예외는 그 값이
     * {@code about:blank}라 분기가 성립하지 않는다.
     */
    @GetMapping
    public List<StockSearchResponse> search(
            @RequestParam(name = "q") String query,
            @RequestParam(required = false) Integer limit) {

        String trimmed = query == null ? "" : query.trim();
        if (trimmed.isEmpty()) {
            throw ApiExceptions.invalidField("q", "검색어가 필요하다");
        }
        if (trimmed.length() > MAX_QUERY_LENGTH) {
            throw ApiExceptions.invalidField("q", "검색어는 " + MAX_QUERY_LENGTH + "자를 넘을 수 없다");
        }

        int effectiveLimit = limit == null ? DEFAULT_SEARCH_LIMIT : limit;
        if (effectiveLimit < 1 || effectiveLimit > MAX_SEARCH_LIMIT) {
            throw ApiExceptions.invalidField("limit", "1 이상 " + MAX_SEARCH_LIMIT + " 이하여야 한다");
        }

        return stockReader.search(trimmed, effectiveLimit).stream()
                .map(StockSearchResponse::of)
                .toList();
    }

    @GetMapping("/{stockId}")
    public StockDetailResponse detail(
            @PathVariable long stockId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asOf) {

        LocalDate resolved = asOfResolver.resolve(asOf, marketScope.forStock(stockId));
        StockReader.StockRow row = stockReader.findById(stockId, resolved)
                .orElseThrow(() -> new ApiExceptions.StockNotFoundException(stockId));

        List<IndicatorDescriptor> catalog = indicatorCatalog.activeIndicators();
        Map<String, BigDecimal> values = screeningRepository
                .loadIndicatorValues(List.of(stockId),
                        catalog.stream().map(IndicatorDescriptor::code)
                                .collect(java.util.stream.Collectors.toCollection(java.util.LinkedHashSet::new)),
                        resolved)
                .getOrDefault(stockId, Map.of());

        // 값이 없는 지표는 원칙적으로 행째 빠진다 — null을 주면 화면이 "0"으로 그릴 위험이
        // 있고 "계산되지 않았다"와 "값이 0이다"는 다른 사실이다.
        //
        // 다만 **왜 없는지 말할 수 있는 것**은 사유와 함께 남긴다. 걸러내고 아무 말도 하지
        // 않는 것이 던지는 것보다 나쁘다(frontend.md). 사유를 모르는 나머지는 그대로 빠지고
        // 화면의 "값이 없어 표시하지 못한 지표 N개"가 그것을 센다.
        UnavailabilityRule.Context ruleContext = new UnavailabilityRule.Context(
                values, stockReader.industryDivisionKeyOf(stockId).orElse(null), row.marketCode());

        List<StockDetailResponse.IndicatorValue> indicators = new ArrayList<>();
        for (IndicatorDescriptor descriptor : catalog) {
            BigDecimal value = values.get(descriptor.code());
            String reason = value == null ? reasonFor(descriptor.code(), ruleContext) : null;
            if (value == null && reason == null) {
                continue;
            }
            indicators.add(new StockDetailResponse.IndicatorValue(
                    descriptor.code(), descriptor.name(), value, descriptor.unit(),
                    descriptor.decimalScale(), descriptor.category(), descriptor.higherIsBetter(),
                    descriptor.description(), descriptor.interpretation(),
                    // 거르지 않고 표시만 한다. 거르면 「내 조건」 구획이 감춘 지표의 값을
                    // 못 받는다 — 서버는 사용자의 초안 조건을 모른다(화면에만 있는 상태다).
                    descriptor.detailDefault(),
                    reason));
        }

        // 미보정 기업 행위를 두 번 쓴다 — 아래 응답의 안내 문구와, 전일 대비를 낼 수 있는지의
        // 판정이다. 후자가 없으면 계수가 없는 종목에서 등락이 원주가 뺄셈이 되어 007340이
        // −81.2%로 나온다(ADR-0055).
        List<LocalDate> unadjustedActions = stockReader.unadjustedActionsOf(stockId);

        StockDetailResponse.Price price = stockReader.priceOn(stockId, resolved)
                .map(p -> StockDetailResponse.Price.of(
                        p, stockReader.previousPrice(stockId, p.tradeDate()).orElse(null),
                        unadjustedActions))
                .orElse(null);

        return new StockDetailResponse(resolved,
                StockDetailResponse.Stock.of(row, stockReader.currentUniversesOf(stockId)),
                price, indicators,
                // 지표가 왜 비었는지는 여기서만 말할 수 있다(ADR-0055). 억제된 종목은 FILTER
                // 결과에 아예 없고, SCORING이라도 필수 조건이면 통째로 빠져 조건별 "데이터
                // 없음"이 이유를 말해 주지 못한다.
                StockDetailResponse.UnadjustedActions.of(unadjustedActions),
                // 업종 카드가 왜 없는지도 여기서만 말할 수 있다 — 표본이 임계에 못 미치면
                // 상대값뿐 아니라 표본 수 행까지 쓰이지 않아 화면에 흔적이 0이다.
                StockDetailResponse.SectorContext.of(
                        stockReader.sectorPeersOf(stockId).orElse(null)));
    }

    /**
     * 이 지표가 이 종목에서 값이 없는 이유. 말할 수 있는 것이 없으면 null이다.
     *
     * <p>규칙은 {@code IndicatorUnavailabilityConfig}에 데이터로 선언되어 있다 —
     * 여기서 지표 코드로 분기하지 않는다.
     */
    private String reasonFor(String code, UnavailabilityRule.Context context) {
        for (UnavailabilityRule rule : unavailabilityRules) {
            if (rule.code().equals(code) && rule.when().test(context)) {
                return rule.reason();
            }
        }
        return null;
    }

    /**
     * 실적 원수치. <b>종목 상세와 별도 요청이다</b> — 이유는 {@link StockFinancialsResponse}에 있다.
     *
     * <p>{@code asOf}를 받지 않는다. 지표는 거래일마다 값이 다르지만 재무제표는 <b>공시된 기(期)</b>
     * 단위라 기준일이 뜻을 갖지 않는다. 받으면 "그날 기준 재무"라는 없는 개념이 생긴다.
     */
    @GetMapping("/{stockId}/financials")
    public StockFinancialsResponse financials(@PathVariable long stockId) {
        stockReader.findById(stockId, asOfResolver.resolve(null, marketScope.forStock(stockId)))
                .orElseThrow(() -> new ApiExceptions.StockNotFoundException(stockId));

        return financialsAssembler.assemble(
                stockReader.financialsOf(stockId, FINANCIAL_PERIODS));
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

        LocalDate resolved = asOfResolver.resolve(asOf, marketScope.forStock(stockId));
        stockReader.findById(stockId, resolved)
                .orElseThrow(() -> new ApiExceptions.StockNotFoundException(stockId));

        // 검증된 읽기 경로를 재사용한다 — "오름차순 LIMIT은 가장 오래된 N봉을 준다"는 함정을
        // 다시 밟지 않기 위해서다.
        return analyzer.analyze(priceReader.readPriceBars(
                stockId, resolved, SupportResistanceAnalyzer.WINDOW_BARS));
    }
}
