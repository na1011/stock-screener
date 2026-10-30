package com.stockscreener.screening.web;

import com.stockscreener.indicator.IndicatorPopulation;
import com.stockscreener.screening.ConditionOutcome;
import com.stockscreener.screening.EvaluationMode;
import com.stockscreener.screening.store.ScreenType;
import com.stockscreener.stock.FinancialBasisReader;

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
 * @param relativePopulations 이 결과가 실제로 쓴 상대 비교의 모집단. 비어 있으면 상대 지표를
 *                      하나도 쓰지 않았다는 뜻이다. 서버가 판단해서 주는 이유는 모집단 주의
 *                      문구를 <b>언제 보여야 하는지</b>가 이 값에 달려 있기 때문이다. 화면이
 *                      카탈로그를 따로 받아 판단하면 그 요청이 실패했을 때 문구가 조용히
 *                      사라지고, 화면은 사실이 아닌 말을 하게 된다.
 * @param populationCount 그 기준일에 <b>판정 대상</b>이던 종목 수(ADR-0142). 조건을 안 건 수이고
 *                      <b>행이 0개여도 산다</b> — 0건 화면이 분모를 말할 수 있는 유일한 자리다.
 *                      <p>{@code universe.memberCount}와 <b>다른 것을 센다.</b> 저쪽은
 *                      「지금 편입」이고 대상을 안 좁히면 {@code universe} 자체가 null이다.
 *                      이 수는 <b>그날의 모집단</b>이라 과거 기준일 조회에서 갈린다(ADR-0135).
 *                      백테스트 하네스의 {@code PointInTimeScreener.Outcome.evaluated}와 같은 개념이다.
 * @param includedCount 조건에 맞은 종목 수. <b>상한({@code MAX_ROWS}) 절단 전</b>이라
 *                      {@code truncated}일 때만 {@code resultCount}와 다르다.
 * @param conditionTallies 조건 순서와 <b>같은 길이</b>. 귀속을 참으로 만들 수 없으면 비어 있다 —
 *                      FILTER가 0건이 아닐 때가 그렇다(통과분만 보고 세면 「0종목이 걸렸다」는
 *                      거짓이 나온다).
 * @param minimumBlocked 통과가 하나도 없을 때 <b>가장 적게 걸린 종목이 못 넘긴 관문 수</b>.
 *                      2 이상이면 <b>한 조건만 풀어서는 안 열린다.</b> 통과가 있으면 null이다.
 */
public record ScreeningResponse(
        LocalDate asOf,
        LocalDate asOfRequested,
        EvaluationMode evaluationMode,
        String sortedBy,
        UniverseInfo universe,
        String currencyCode,
        List<IndicatorPopulation> relativePopulations,
        ScreenInfo screen,
        List<ConditionInfo> conditions,
        List<String> columns,
        int populationCount,
        int includedCount,
        List<ConditionTally> conditionTallies,
        Integer minimumBlocked,
        int resultCount,
        boolean truncated,
        List<Row> rows
) {

    /**
     * 상대 비교의 모집단이 <b>둘</b>이 되었다.
     *
     * <p>예전에는 백분위 하나뿐이라 모집단이 상수였고 문자열 하나로 실렸다. 업종 축이 생기면서
     * <b>같은 응답 안에 모집단이 다른 조건이 함께 놓인다</b> — 사용자가 보는 "상위 50%"의 뜻이
     * 조건마다 다른데 값은 둘 다 0~1이라 화면으로는 구분되지 않는다(ADR-0044). 그래서 상수가
     * 아니라 <b>실제로 쓰인 모집단의 집합</b>을 낸다.
     *
     * <p>각 모집단이 무엇인지는 {@link IndicatorPopulation}에 있다. 여기 열거하지 않는 이유는
     * 그 뜻이 지표 계층의 사실이고, 두 곳에 적으면 갈리기 때문이다.
     */
    public static final String SORTED_BY_SCORE = "SCORE_DESC";
    public static final String SORTED_BY_STOCK = "STOCK_ID_ASC";

    /**
     * 조건 하나가 <b>무엇을 얼마나 잘랐는가</b>(ADR-0142). 행이 0개여도 사는 자리다.
     *
     * @param conditionIndex   {@code conditions}의 자리. {@code Row.outcomes}의 것과 같은 인덱스다
     * @param notMatched       조건을 못 넘긴 종목 수
     * @param notEvaluable     <b>값이 없어</b> 판정하지 못한 종목 수. 위와 갈라 세는 것이 요점이다 —
     *                         합치면 사용자가 데이터 문제를 전략 문제로 읽는다
     * @param wouldPassWithout 이 조건 하나만 뺐을 때의 통과 수. 관문이 아니면 null이다.
     *                         <b>이것은 펜스 축이 아니다</b> — ADR-0070 §검증이 그 용도를 기각했다
     */
    public record ConditionTally(int conditionIndex, int notMatched, int notEvaluable,
                                 Integer wouldPassWithout) {
    }

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
     * @param financialBasis 지표가 쓴 재무 기준과 그보다 새로운 실적 발표 여부(ADR-0195). 재무가 하나도 안 보이면 null이다.
     *                 화면은 PENDING일 때만 결과 행에 표시를 단다 — 판정은 서버가 했다
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
            List<Outcome> outcomes,
            FinancialBasisReader.Basis financialBasis
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
