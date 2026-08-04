package com.stockscreener.indicator;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;

/**
 * 지표 계산기에 전달되는 한 종목의 원자료.
 *
 * <p>러너가 {@link DataRequirement}의 선언을 보고 채워서 넘긴다. 계산기는 여기 담긴 것만 쓰고
 * 직접 저장소를 조회하지 않는다 — 그래야 계산 로직이 순수해져 테스트가 쉽고,
 * 러너가 종목당 한 번만 읽어 N+1을 피할 수 있다.
 */
public final class IndicatorContext {

    private final long stockId;
    private final List<PriceBar> priceBars;
    private final List<FinancialFigures> annualFigures;
    private final List<FinancialFigures> quarterlyFigures;
    private final Map<String, NavigableMap<LocalDate, BigDecimal>> dependencies;

    public IndicatorContext(
            long stockId,
            List<PriceBar> priceBars,
            List<FinancialFigures> annualFigures,
            List<FinancialFigures> quarterlyFigures,
            Map<String, NavigableMap<LocalDate, BigDecimal>> dependencies) {
        this.stockId = stockId;
        this.priceBars = priceBars == null ? List.of() : List.copyOf(priceBars);
        this.annualFigures = annualFigures == null ? List.of() : List.copyOf(annualFigures);
        this.quarterlyFigures = quarterlyFigures == null ? List.of() : List.copyOf(quarterlyFigures);
        this.dependencies = dependencies == null ? Map.of() : Map.copyOf(dependencies);
    }

    public long stockId() {
        return stockId;
    }

    /** 날짜 오름차순 시세. 워밍업 구간을 포함해 {@link DataRequirement}가 요구한 만큼 담긴다. */
    public List<PriceBar> priceBars() {
        return priceBars;
    }

    /** {@code periodEnd} 내림차순(최신 우선) 연간 재무. */
    public List<FinancialFigures> annualFigures() {
        return annualFigures;
    }

    /** {@code periodEnd} 내림차순(최신 우선) 분기 재무. TTM 합산에 쓴다. */
    public List<FinancialFigures> quarterlyFigures() {
        return quarterlyFigures;
    }

    /**
     * 의존 지표의 이미 계산된 시계열. {@link DataRequirement#dependsOn()}에 선언한 코드로 찾는다.
     *
     * <p>파생 지표가 부모의 계산을 다시 구현하지 않게 하는 통로다.
     */
    public NavigableMap<LocalDate, BigDecimal> dependency(String indicatorCode) {
        NavigableMap<LocalDate, BigDecimal> series = dependencies.get(indicatorCode);
        if (series == null) {
            throw new IllegalStateException(
                    "선언하지 않은 의존 지표를 요청했다: " + indicatorCode
                            + " — DataRequirement.dependsOn에 추가하라.");
        }
        return Collections.unmodifiableNavigableMap(series);
    }

    /** 계산 대상 날짜들(시세 기준, 오름차순). */
    public List<LocalDate> tradeDates() {
        return priceBars.stream().map(PriceBar::tradeDate).toList();
    }

    /** 빈 컨텍스트. 테스트와 원자료가 없는 종목에 쓴다. */
    public static IndicatorContext empty(long stockId) {
        return new IndicatorContext(stockId, List.of(), List.of(), List.of(), new TreeMap<>());
    }
}
