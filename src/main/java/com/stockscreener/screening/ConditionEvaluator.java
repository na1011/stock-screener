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

        // 필수 조건은 점수에 참여하지 않는다. 통과한 종목은 정의상 그것을 전부 충족하므로
        // 같은 몫을 받고, 그러면 달성률이 언제나 높게 나와 변별이 사라진다.
        BigDecimal score = outcomes.stream()
                .filter(o -> !o.condition().required())
                .map(ConditionOutcome::earnedScore)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal maxScore = outcomes.stream()
                .filter(o -> !o.condition().required())
                .map(o -> o.condition().effectiveWeight())
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        // SCORING은 좁히지 않는다. 일부 조건만 만족한 종목도 그만큼의 점수로 남아야
        // 순위를 매길 수 있다. 필수 조건이 그 유일한 예외다 — 게이트로 거른 뒤 남은 것에
        // 점수를 매기는 전략은 이것 없이 표현할 수 없고, 가중치로 흉내내면 미충족 종목이
        // 낮은 점수로 결과에 남는다.
        boolean included = mode == EvaluationMode.SCORING
                ? satisfiesRequired(outcomes)
                : passes;

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

    /**
     * 필수 조건을 전부 충족했는가.
     *
     * <p>IMPORTANT — 판정불가({@code NOT_EVALUABLE})는 충족이 아니다. {@code passesFilter()}가
     * 그 의미를 이미 갖고 있으므로 여기서 다시 정의하지 않는다. 통과로 접으면 <b>데이터가 없는
     * 종목일수록 게이트를 잘 통과하게 되는데</b>, 그것은 게이트의 목적과 정확히 반대다.
     *
     * <p>필수 조건이 하나도 없으면 참이다 — 기존 SCORING 전략의 동작이 그대로 유지된다.
     */
    private static boolean satisfiesRequired(List<ConditionOutcome> outcomes) {
        return outcomes.stream()
                .filter(o -> o.condition().required())
                .allMatch(ConditionOutcome::passesFilter);
    }

    private static boolean combine(ConditionGroup.LogicalOperator operator, boolean accumulated, boolean next) {
        return operator == ConditionGroup.LogicalOperator.AND
                ? accumulated && next
                : accumulated || next;
    }
}
