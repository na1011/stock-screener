package com.stockscreener.screening;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 조건 그룹을 지표 값에 대해 판정한다.
 *
 * <p>FILTER와 SCORING이 하나의 조건 모델을 공유한다는 것을 실제로 구현하는 곳이다.
 * 두 방식의 차이는 조건별 결과({@link ConditionOutcome})를 <b>어떻게 접느냐</b>뿐이다.
 * <ul>
 *   <li>FILTER — 결합 연산자에 따라 AND/OR로 접어 통과 여부를 낸다.
 *   <li>SCORING — 충족한 조건의 가중치를 합한다.
 * </ul>
 * 조건 자체는 자신이 어느 쪽에서 쓰이는지 모른다.
 *
 * <p>재귀로 작성한다. v1은 깊이 1만 만들지만, 이렇게 두면 v2의 OR 트리 도입 시
 * 이 클래스를 고치지 않는다.
 */
@Component
public class ConditionEvaluator {

    /**
     * 한 종목을 판정한다.
     *
     * @param group  조건 그룹
     * @param mode   판정 방식
     * @param values 지표 코드 → 값. 계산되지 않은 지표는 키가 없다.
     */
    public ScreeningResult evaluate(long stockId, ConditionGroup group, EvaluationMode mode,
                                    Map<String, BigDecimal> values) {
        List<ConditionOutcome> outcomes = new ArrayList<>();
        boolean passes = evaluateGroup(group, values, outcomes);

        BigDecimal score = outcomes.stream()
                .map(ConditionOutcome::earnedScore)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal maxScore = outcomes.stream()
                .map(o -> o.condition().effectiveWeight())
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        // SCORING은 좁히지 않는다. 일부 조건만 만족한 종목도 그만큼의 점수로 남아야
        // 순위를 매길 수 있다.
        boolean included = mode == EvaluationMode.SCORING || passes;

        return new ScreeningResult(stockId, included, score, maxScore, outcomes);
    }

    /**
     * 그룹을 재귀적으로 판정하고, 지나간 조건의 결과를 {@code collected}에 모은다.
     *
     * @return 이 그룹이 통과하는가 (FILTER 관점)
     */
    private boolean evaluateGroup(ConditionGroup group, Map<String, BigDecimal> values,
                                  List<ConditionOutcome> collected) {
        ConditionGroup.LogicalOperator operator = group.logicalOperator();
        boolean result = operator == ConditionGroup.LogicalOperator.AND;

        for (IndicatorCondition condition : group.conditions()) {
            ConditionOutcome outcome = condition.evaluate(values);
            collected.add(outcome);
            result = combine(operator, result, outcome.passesFilter());
        }

        // v1에서는 비어 있다. v2의 OR 트리가 여기로 들어온다.
        for (ConditionGroup child : group.childGroups()) {
            boolean childResult = evaluateGroup(child, values, collected);
            result = combine(operator, result, childResult);
        }

        return result;
    }

    private static boolean combine(ConditionGroup.LogicalOperator operator, boolean accumulated, boolean next) {
        return operator == ConditionGroup.LogicalOperator.AND
                ? accumulated && next
                : accumulated || next;
    }
}
