package com.stockscreener.screening;

import java.math.BigDecimal;

/**
 * 조건 하나의 판정 결과.
 *
 * <p>세 가지 상태를 구분한다. 이 구분이 FILTER와 SCORING이 같은 조건 집합에서 다르게
 * 동작하는 근거다.
 *
 * <ul>
 *   <li><b>충족</b> — 값이 있고 조건을 만족한다.
 *   <li><b>불충족</b> — 값이 있고 조건을 만족하지 않는다.
 *   <li><b>판정 불가</b> — 지표가 계산되지 않았다(이력 부족, 적자로 PER 계산 불가 등).
 * </ul>
 *
 * <p>IMPORTANT — "판정 불가"를 "불충족"과 합치면 안 된다. FILTER에서는 둘 다 종목을 제외하지만,
 * SCORING에서는 다르다 — 판정 불가인 조건은 0점을 주되 종목 자체는 결과에 남아야 한다.
 * 5개 조건 중 4개를 만족한 종목이 나머지 하나의 지표가 없다는 이유로 사라지면 안 되기 때문이다.
 * 그리고 화면에는 "조건 불충족"과 "데이터 없음"이 다르게 보여야 사용자가 이유를 이해한다.
 *
 * @param condition   판정 대상 조건
 * @param actualValue 실제 지표 값. 판정 불가면 null.
 * @param matched     충족 여부. 판정 불가면 false지만 {@link #evaluable}로 구분해야 한다.
 * @param evaluable   판정할 수 있었는지
 */
public record ConditionOutcome(
        IndicatorCondition condition,
        BigDecimal actualValue,
        boolean matched,
        boolean evaluable
) {

    static ConditionOutcome evaluated(IndicatorCondition condition, BigDecimal actualValue, boolean matched) {
        return new ConditionOutcome(condition, actualValue, matched, true);
    }

    static ConditionOutcome notEvaluable(IndicatorCondition condition) {
        return new ConditionOutcome(condition, null, false, false);
    }

    /** FILTER에서 이 조건이 종목을 통과시키는가. 판정 불가는 통과시키지 않는다. */
    public boolean passesFilter() {
        return evaluable && matched;
    }

    /** SCORING에서 이 조건이 획득한 점수. 충족했을 때만 가중치를 얻는다. */
    public BigDecimal earnedScore() {
        return passesFilter() ? condition.effectiveWeight() : BigDecimal.ZERO;
    }

    /** 화면에 표시할 상태. frontend.md가 요구하는 "선정 이유" 노출에 쓴다. */
    public Status status() {
        if (!evaluable) {
            return Status.NOT_EVALUABLE;
        }
        return matched ? Status.MATCHED : Status.NOT_MATCHED;
    }

    public enum Status {
        /** 조건 충족 */
        MATCHED,
        /** 조건 불충족 */
        NOT_MATCHED,
        /** 지표가 계산되지 않아 판정할 수 없음 — 불충족과 구분해서 보여준다 */
        NOT_EVALUABLE
    }
}
