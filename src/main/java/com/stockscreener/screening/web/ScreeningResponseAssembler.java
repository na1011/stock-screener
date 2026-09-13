package com.stockscreener.screening.web;

import com.stockscreener.indicator.IndicatorCatalog;
import com.stockscreener.indicator.IndicatorDescriptor;
import com.stockscreener.indicator.SectorMetricSpec;
import com.stockscreener.screening.ConditionOutcome;
import com.stockscreener.screening.EvaluationMode;
import com.stockscreener.screening.IndicatorCondition;
import com.stockscreener.screening.preset.PresetCatalog;
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

    /**
     * 카탈로그에 없는 지표의 소수 자릿수.
     *
     * <p>프론트도 같은 상황에 같은 값을 쓴다({@code frontend/src/api/limits.ts}). 어긋나면 같은
     * 지표가 표와 서버 응답에서 다른 자릿수로 보인다. 상수로 뽑은 이유는 리터럴이면
     * {@code wire-contract.json}이 지킬 수 없기 때문이다(ADR-0020).
     */
    public static final int FALLBACK_DECIMAL_SCALE = 2;

    private final IndicatorCatalog indicatorCatalog;
    private final StockReader stockReader;
    private final ScreeningRepository screeningRepository;

    /**
     * 업종 상대값 → 그 표본 수 지표.
     *
     * <p>선언을 그대로 받는 이유는 <b>짝을 아는 곳이 거기뿐</b>이기 때문이다. 코드에 접미사
     * 규칙을 적으면 규칙이 두 곳에 생기고, 카탈로그의 {@code source_indicator_code}로는 찾을 수
     * 없다 — 표본 수에는 그 링크를 일부러 걸지 않았다(백분위 안내가 붙어 버린다).
     */
    private final Map<String, String> peerCountByMetric;

    public ScreeningResponseAssembler(IndicatorCatalog indicatorCatalog, StockReader stockReader,
                                      ScreeningRepository screeningRepository,
                                      List<SectorMetricSpec> sectorMetricSpecs) {
        this.indicatorCatalog = indicatorCatalog;
        this.stockReader = stockReader;
        this.screeningRepository = screeningRepository;
        this.peerCountByMetric = sectorMetricSpecs.stream().collect(java.util.stream.Collectors
                .toMap(SectorMetricSpec::code, SectorMetricSpec::peerCountCode));
    }

    /**
     * @param currencyCode 이 결과가 선 시장의 통화. <b>화면의 모든 금액 표기가 이것 하나에 걸린다</b> —
     *                     종가·통화 단위 지표·조건 칩의 임계까지 전부다. 행마다 싣지 않는 이유는
     *                     한 실행이 통화 하나라는 것을 {@code ScreeningService}가 이미 강제하기
     *                     때문이다(둘이면 요청을 거절한다). 행에도 두면 같은 사실이 두 곳에 놓인다.
     */
    ScreeningResponse assemble(ScreeningService.Source source, EvaluationMode mode,
                               String universeCode, String currencyCode,
                               LocalDate asOf, LocalDate asOfRequested,
                               ScreeningRepository.ScreeningOutcome outcome,
                               List<ScreeningResult> results, boolean truncated) {

        List<IndicatorCondition> conditions = source.conditions().flattenConditions();
        List<ScreeningResponse.ConditionTally> tallies = toTallies(outcome, conditions.size());

        // 표에 보일 컬럼 — 카탈로그의 기본 컬럼 ∪ 조건이 참조한 지표.
        // 조건에 쓰인 지표를 반드시 포함하는 이유는 "왜 걸렸는지"를 보려면 그 값이 보여야 하기 때문이다.
        Set<String> columnCodes = new LinkedHashSet<>(source.conditions().referencedIndicatorCodes());
        // 업종 상대값을 조건으로 썼으면 **그 값을 낼 때 비교한 표본 수도 함께** 보인다.
        // 임계(5)는 5 미만만 막으므로 5와 40의 차이가 값에는 남지 않는다 — 그 수가 없으면
        // 사용자가 "업종 상위 50%"를 두 종목 중 하나라는 뜻으로도 읽게 된다(ADR-0044).
        // 기본 컬럼으로 두지 않는 이유는 업종 조건을 쓰지 않는 화면까지 시끄러워지기 때문이다.
        List.copyOf(columnCodes).forEach(code -> {
            String peerCount = peerCountByMetric.get(code);
            if (peerCount != null) {
                columnCodes.add(peerCount);
            }
        });
        // 프리셋이 자기 화면에서만 함께 보여야 할 지표를 더한다. defaultColumn으로 두면 그것은
        // **전역**이라 모든 화면이 시끄러워진다(지금 넷뿐이다). 여기서 union하면 columns가 이미
        // 동적 목록이라 스키마도 wire-contract도 안 건드린다 — 표현 계층 한정 예외다.
        //
        // MUST: 저장된 커스텀 화면에는 걸리지 않는다. codeIfPreset()이 프리셋 코드일 때만
        // 값을 주므로, 사용자가 만든 화면이 프리셋 컬럼을 물려받지 않는다.
        source.codeIfPreset().map(PresetCatalog::extraColumns).ifPresent(columnCodes::addAll);

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
                currencyCode,
                adsSuspectCount(universeCode),
                // 어느 모집단을 썼는지는 서버가 판단해서 준다. 화면이 카탈로그를 따로 받아
                // 판단하면 그 요청만 실패해도 모집단 주의 문구가 조용히 사라진다.
                //
                // 열거 순서로 정렬한다 — 집합이라 순서에 뜻이 없는데 정렬하지 않으면
                // 화면의 문구 순서가 조건 순서에 따라 흔들린다.
                descriptors.values().stream()
                        .map(IndicatorDescriptor::population)
                        .filter(java.util.Objects::nonNull)
                        .distinct()
                        .sorted()
                        .toList(),
                new ScreeningResponse.ScreenInfo(
                        source.id(), source.code(), source.name(), source.type()),
                conditionInfos,
                List.copyOf(columnCodes),
                outcome.populationCount(),
                outcome.results().size(),
                tallies,
                outcome.minimumBlocked(),
                rows.size(),
                truncated,
                rows);
    }

    /**
     * 질의 계층의 귀속을 와이어 모양으로 옮긴다.
     *
     * <p><b>MUST: 가정하지 말고 단언한다.</b> {@code ConditionGroup.flattenConditions()}와
     * {@code ConditionEvaluator.evaluateGroup}가 같은 전위 순회라는 것이 <b>어디에도 단언돼 있지
     * 않다</b> — 두 구현이 우연히 발맞추고 있다. 그 우연이 깨지면 조건 목록과 귀속의 인덱스가
     * 어긋나 <b>다른 조건의 수가 붙는데</b>, 값이 그럴듯해 화면으로는 안 드러난다.
     * 이 클래스는 이미 「여기서 다시 판정하지 않는다」를 지키는 자리이고 이것도 같은 종류의 가드다.
     */
    private static List<ScreeningResponse.ConditionTally> toTallies(
            ScreeningRepository.ScreeningOutcome outcome, int conditionCount) {
        List<ScreeningRepository.ConditionTally> tallies = outcome.tallies();
        if (tallies.isEmpty()) {
            return List.of();
        }
        if (tallies.size() != conditionCount) {
            throw new IllegalStateException("조건 귀속의 길이가 조건 수와 다르다: "
                    + tallies.size() + " 대 " + conditionCount);
        }
        List<ScreeningResponse.ConditionTally> wire = new ArrayList<>(tallies.size());
        for (int i = 0; i < tallies.size(); i++) {
            ScreeningRepository.ConditionTally tally = tallies.get(i);
            if (tally.conditionIndex() != i) {
                throw new IllegalStateException("조건 귀속의 인덱스가 어긋났다: "
                        + tally.conditionIndex() + " 자리에 " + i + "번 조건");
            }
            wire.add(new ScreeningResponse.ConditionTally(i, tally.notMatched(),
                    tally.notEvaluable(), tally.wouldPassWithout()));
        }
        return wire;
    }

    /**
     * 「시총 상위 N」이 사실이 아닐 수 있다는 것을 화면이 말할 수 있게 하는 수.
     *
     * <p>대상을 안 좁혔으면({@code universeCode == null}) 활성 유니버스 전체의 합이다 —
     * 그때 스크리닝이 도는 대상이 정확히 그 집합이다({@code selectableUniverses}가
     * {@code MarketScope}와 같은 술어를 쓴다).
     *
     * <p>근거와 이 축이 못 잡는 것은 {@code StockReader.UniverseRow}의 javadoc에 있다.
     */
    private int adsSuspectCount(String universeCode) {
        return stockReader.selectableUniverses().stream()
                .filter(u -> universeCode == null || u.code().equals(universeCode))
                .mapToInt(StockReader.UniverseRow::adsSuspectCount)
                .sum();
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
                condition.weight(),
                condition.required());
    }

    /**
     * 카탈로그에 없는 지표라도 코드만은 돌려준다.
     *
     * <p>저장된 화면이 폐기된 지표를 참조하는 경우다. 여기서 예외를 던지면 화면 전체가 열리지
     * 않고, null을 주면 프론트가 NPE를 낸다. 코드만 보여주면 사용자가 "이 조건이 문제"임을 안다.
     */
    private static ScreeningResponse.IndicatorBrief brief(String code, IndicatorDescriptor descriptor) {
        if (descriptor == null) {
            return new ScreeningResponse.IndicatorBrief(code, code, null, FALLBACK_DECIMAL_SCALE, null,
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
