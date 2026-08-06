package com.stockscreener.screening.web;

import com.stockscreener.indicator.IndicatorCatalog;
import com.stockscreener.indicator.IndicatorDescriptor;
import com.stockscreener.screening.ConditionOutcome;
import com.stockscreener.screening.EvaluationMode;
import com.stockscreener.screening.IndicatorCondition;
import com.stockscreener.screening.ScreeningResult;
import com.stockscreener.screening.query.ScreeningRepository;
import com.stockscreener.stock.StockReader;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 판정 결과를 응답으로 옮긴다.
 *
 * <p>IMPORTANT — 여기서 <b>다시 판정하지 않는다.</b> 점수도 상태도 {@link ConditionOutcome}이
 * 이미 계산한 것을 그대로 옮긴다. 응답 조립이 자체 계산을 시작하는 순간 화면이 보여주는
 * 선정 이유가 실제로 종목을 고른 근거와 갈라질 수 있고, 그 차이는 손으로 대조하기 전까지
 * 드러나지 않는다(ADR-0003).
 */
@Component
public class ScreeningResponseAssembler {

    private final IndicatorCatalog indicatorCatalog;
    private final StockReader stockReader;
    private final ScreeningRepository screeningRepository;

    public ScreeningResponseAssembler(IndicatorCatalog indicatorCatalog, StockReader stockReader,
                                      ScreeningRepository screeningRepository) {
        this.indicatorCatalog = indicatorCatalog;
        this.stockReader = stockReader;
        this.screeningRepository = screeningRepository;
    }

    ScreeningResponse assemble(ScreeningService.Source source, EvaluationMode mode,
                               String universeCode, LocalDate asOf, LocalDate asOfRequested,
                               int totalCount, List<ScreeningResult> results, boolean truncated) {

        List<IndicatorCondition> conditions = source.conditions().flattenConditions();

        // 표에 보일 컬럼 — 카탈로그의 기본 컬럼 ∪ 조건이 참조한 지표.
        // 조건에 쓰인 지표를 반드시 포함하는 이유는 "왜 걸렸는지"를 보려면 그 값이 보여야 하기 때문이다.
        Set<String> columnCodes = new LinkedHashSet<>(source.conditions().referencedIndicatorCodes());
        List<IndicatorDescriptor> catalog = indicatorCatalog.activeIndicators();
        catalog.stream().filter(IndicatorDescriptor::defaultColumn)
                .map(IndicatorDescriptor::code).forEach(columnCodes::add);

        Map<String, IndicatorDescriptor> descriptors = indicatorCatalog.describe(columnCodes);

        List<Long> stockIds = results.stream().map(ScreeningResult::stockId).toList();
        Map<Long, StockReader.StockRow> stocks = stockReader.findRows(stockIds, asOf);
        // 표시용 값. 조건에 쓰이지 않은 기본 컬럼도 채워야 표가 비지 않는다.
        // 판정에 쓴 것과 같은 조회 경로·같은 일자라 두 값이 어긋날 수 없다.
        Map<Long, Map<String, BigDecimal>> valuesByStock =
                screeningRepository.loadIndicatorValues(stockIds, columnCodes, asOf);

        List<ScreeningResponse.ConditionInfo> conditionInfos = new ArrayList<>(conditions.size());
        for (int i = 0; i < conditions.size(); i++) {
            conditionInfos.add(toConditionInfo(i, conditions.get(i), descriptors));
        }

        List<ScreeningResponse.Row> rows = results.stream()
                .map(result -> toRow(result, stocks.get(result.stockId()),
                        valuesByStock.getOrDefault(result.stockId(), Map.of())))
                .toList();

        return new ScreeningResponse(
                asOf,
                asOfRequested,
                mode,
                mode == EvaluationMode.SCORING
                        ? ScreeningResponse.SORTED_BY_SCORE
                        : ScreeningResponse.SORTED_BY_STOCK,
                universeInfo(universeCode),
                ScreeningResponse.PERCENTILE_POPULATION,
                new ScreeningResponse.ScreenInfo(
                        source.id(), source.code(), source.name(), source.type()),
                conditionInfos,
                List.copyOf(columnCodes),
                totalCount,
                rows.size(),
                truncated,
                rows);
    }

    private ScreeningResponse.UniverseInfo universeInfo(String universeCode) {
        if (universeCode == null) {
            return null;
        }
        return stockReader.selectableUniverses().stream()
                .filter(u -> u.code().equals(universeCode))
                .findFirst()
                .map(u -> new ScreeningResponse.UniverseInfo(u.code(), u.name(), u.memberCount()))
                .orElse(new ScreeningResponse.UniverseInfo(universeCode, universeCode, null));
    }

    private ScreeningResponse.ConditionInfo toConditionInfo(
            int index, IndicatorCondition condition, Map<String, IndicatorDescriptor> descriptors) {

        IndicatorDescriptor descriptor = descriptors.get(condition.indicatorCode());
        return new ScreeningResponse.ConditionInfo(
                index,
                brief(condition.indicatorCode(), descriptor),
                condition.operator().name(),
                // 한글 라벨의 정본은 ComparisonOperator다. 프론트가 목록을 복제하면
                // 연산자가 추가될 때 두 곳을 고쳐야 한다.
                condition.operator().displayName(),
                ConditionPayloadMapper.toPayload(condition).operand(),
                condition.weight());
    }

    /**
     * 카탈로그에 없는 지표라도 코드만은 돌려준다.
     *
     * <p>저장된 화면이 폐기된 지표를 참조하는 경우다. 여기서 예외를 던지면 화면 전체가 열리지
     * 않고, null을 주면 프론트가 NPE를 낸다. 코드만 보여주면 사용자가 "이 조건이 문제"임을 안다.
     */
    private static ScreeningResponse.IndicatorBrief brief(String code, IndicatorDescriptor descriptor) {
        if (descriptor == null) {
            return new ScreeningResponse.IndicatorBrief(code, code, null, 2, null,
                    "더 이상 제공되지 않는 지표다.");
        }
        return new ScreeningResponse.IndicatorBrief(
                descriptor.code(), descriptor.name(), descriptor.unit(),
                descriptor.decimalScale(), descriptor.higherIsBetter(), descriptor.interpretation());
    }

    private static ScreeningResponse.Row toRow(ScreeningResult result, StockReader.StockRow stock,
                                               Map<String, BigDecimal> values) {
        List<ScreeningResponse.Outcome> outcomes = new ArrayList<>(result.outcomes().size());
        for (int i = 0; i < result.outcomes().size(); i++) {
            ConditionOutcome outcome = result.outcomes().get(i);
            outcomes.add(new ScreeningResponse.Outcome(
                    i, outcome.status(), outcome.actualValue(), outcome.earnedScore()));
        }

        return new ScreeningResponse.Row(
                result.stockId(),
                stock == null ? null : stock.ticker(),
                stock == null ? null : stock.name(),
                stock == null ? null : stock.marketCode(),
                stock == null ? null : stock.close(),
                result.score(),
                result.maxScore(),
                result.scoreRatio(),
                result.matchedCount(),
                result.notEvaluableCount(),
                values,
                outcomes);
    }
}
