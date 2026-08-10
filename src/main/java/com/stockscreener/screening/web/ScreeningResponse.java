package com.stockscreener.screening.web;

import com.stockscreener.screening.ConditionOutcome;
import com.stockscreener.screening.EvaluationMode;
import com.stockscreener.screening.store.ScreenType;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * 스크리닝 결과.
 *
 * <p>설계 결정 넷이 이 형태에 담겨 있다.
 * <ol>
 *   <li><b>{@code status}는 3값이고 boolean이 아니다.</b> 충족·불충족·데이터없음을 boolean으로
 *       접으면 ADR-0003이 금지한 병합이 HTTP 계층에서 일어나고, 그 지점은 어떤 백엔드
 *       테스트로도 잡히지 않는다.
 *   <li><b>조건 정의는 사전에 한 번만 싣는다.</b> 행마다 조건 메타데이터를 반복하면
 *       350행 × 8조건에서 페이로드가 10배가 된다. 행은 {@code conditionIndex}로 참조한다.
 *   <li><b>{@code values}는 지표 코드가 키인 맵이다.</b> 지표별 DTO 필드를 만들면 지표를
 *       추가할 때마다 DTO를 고쳐야 하고, 그것이 "지표 코드에 분기하지 않는다"의 HTTP 계층 위반이다.
 *   <li><b>{@code asOf}를 항상 에코한다.</b> 배치가 하루 밀렸을 때 사용자가 어제 데이터를
 *       오늘 것으로 읽는 것을 막는 유일한 장치다.
 * </ol>
 *
 * @param asOfRequested 요청이 지정한 일자. null이면 서버가 정했다는 뜻이다.
 * @param columns       결과 표의 기본 컬럼 — 카탈로그의 기본 컬럼 ∪ 조건이 참조한 지표
 * @param truncated     상한에 걸려 잘렸는가. <b>조용히 자르지 않는다.</b>
 * @param usesPercentile 이 결과가 백분위 지표를 하나라도 쓰는가. 서버가 판단해서 주는 이유는
 *                      아래 {@link #PERCENTILE_POPULATION} 주의 문구를 <b>언제 보여야 하는지</b>가
 *                      이 값에 달려 있기 때문이다. 화면이 카탈로그를 따로 받아 판단하면 그 요청이
 *                      실패했을 때 문구가 조용히 사라지고, 화면은 사실이 아닌 말을 하게 된다.
 */
public record ScreeningResponse(
        LocalDate asOf,
        LocalDate asOfRequested,
        EvaluationMode evaluationMode,
        String sortedBy,
        UniverseInfo universe,
        String percentilePopulation,
        boolean usesPercentile,
        ScreenInfo screen,
        List<ConditionInfo> conditions,
        List<String> columns,
        int evaluatedCount,
        int resultCount,
        boolean truncated,
        List<Row> rows
) {

    /**
     * 백분위의 모집단이 무엇인지.
     *
     * <p>IMPORTANT — 순위는 <b>각 유니버스 안에서</b> 매겨진다(ADR-0036). 유니버스를 지정하지 않은
     * 화면의 "PER 하위 20%"는 코스피·코스닥을 한 줄로 세운 하위 20%가 아니라
     * <b>각 유니버스 하위 20%의 합집합</b>이다. 대상을 좁히는 축과 순위를 매기는 축이 다르며,
     * 순위는 언제나 같은 시장 안에서 매겨지고 화면은 그중 무엇을 보여줄지만 정한다.
     *
     * <p>상수 이름이 {@code ALL_CURRENT_MEMBERS}인 것은 <b>모집단이 아니라 대상</b>을 가리키기
     * 때문이다 — 현재 어느 유니버스든 편입된 종목이 계산 대상이고, 그 안에서 유니버스별로 나뉜다.
     * <b>(2026-08-10 정정 — 원문은 "순위는 그 유니버스 안에서 매겨진 것이 아니다"였다.</b>
     * ADR-0036이 그것을 뒤집었는데 이 주석만 남아 있었다. 값과 동작은 바뀌지 않았다.)
     */
    public static final String PERCENTILE_POPULATION = "ALL_CURRENT_MEMBERS";

    public static final String SORTED_BY_SCORE = "SCORE_DESC";
    public static final String SORTED_BY_STOCK = "STOCK_ID_ASC";

    /** @param memberCount 현재 편입 종목 수. 전체 유니버스면 null. */
    public record UniverseInfo(String code, String name, Integer memberCount) {
    }

    /** @param id 즉석 조건이면 null. @param code 프리셋만 갖는다. */
    public record ScreenInfo(Long id, String code, String name, ScreenType type) {
    }

    /**
     * 조건 하나의 정의와 표시 정보.
     *
     * @param indicator     지표의 의미·단위·유리한 방향. spec §5가 요구하는 "값만 나열하지 않는다".
     * @param operatorLabel 한글 라벨. 정본은 {@code ComparisonOperator.displayName()}이다.
     * @param required      필수 조건인가. 화면이 필수와 점수 조건을 갈라 보여주려면 이것이 필요하다 —
     *                      {@code weight}가 null인 것으로 유도하면 안 된다. FILTER 전략은 둘 다 null이라
     *                      구분이 서지 않고, 그것이 {@code frontend.md}가 금지한 "서버가 준 값을
     *                      다른 필드로 다시 유도하는 것"이다.
     */
    public record ConditionInfo(
            int index,
            IndicatorBrief indicator,
            String operator,
            String operatorLabel,
            ConditionGroupPayload.OperandPayload operand,
            BigDecimal weight,
            boolean required
    ) {
    }

    /** 결과 표가 컬럼 머리글과 값 서식에 쓰는 최소 정보. */
    public record IndicatorBrief(
            String code,
            String name,
            String unit,
            int decimalScale,
            Boolean higherIsBetter,
            String interpretation
    ) {
    }

    /**
     * 종목 한 행.
     *
     * @param values   지표 코드 → 값. <b>계산되지 않은 지표는 키가 없다</b>(null이 아니라 부재).
     * @param close    기준일 원주가 종가. 사용자가 실제로 지불하는 가격이다.
     * @param outcomes 조건별 판정. frontend.md가 요구하는 "왜 이 종목이 선정되었는지"다.
     */
    public record Row(
            long stockId,
            String ticker,
            String name,
            String marketCode,
            BigDecimal close,
            BigDecimal score,
            BigDecimal maxScore,
            BigDecimal scoreRatio,
            long matchedCount,
            long notEvaluableCount,
            Map<String, BigDecimal> values,
            List<Outcome> outcomes
    ) {
    }

    /**
     * 조건 하나에 대한 판정.
     *
     * @param status      {@link ConditionOutcome.Status} 그대로. MATCHED / NOT_MATCHED / NOT_EVALUABLE.
     * @param actualValue 판정 불가면 null.
     */
    public record Outcome(
            int conditionIndex,
            ConditionOutcome.Status status,
            BigDecimal actualValue,
            BigDecimal earnedScore
    ) {
    }
}
