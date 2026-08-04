package com.stockscreener.screening;

import java.util.ArrayList;
import java.util.List;

/**
 * 조건들의 묶음. 구조적으로 트리이며, v1은 깊이 1로만 만든다.
 *
 * <p>{@code childGroups}와 {@code logicalOperator}를 처음부터 두는 이유는 spec §10의
 * "지금의 단순 조건 목록 구조가 트리 구조의 최상위 계층으로 자연스럽게 흡수될 수 있어야 한다"를
 * 마이그레이션 없이 만족시키기 위해서다.
 *
 * <p>v1의 제약(그룹 하나, AND만)은 <b>애플리케이션이 그렇게 만들지 않는 것</b>으로 지킨다.
 * 깊이를 막는 검증을 넣지 않는 이유는 나중에 제거해야 할 것이기 때문이다. 대신 판정과 번역을
 * 처음부터 재귀로 작성하면, v2의 OR 도입은 UI 변경만으로 끝나고 이 코드는 손대지 않는다.
 *
 * @param logicalOperator 자식들을 결합하는 방식
 * @param conditions      이 그룹에 직접 속한 조건들
 * @param childGroups     하위 그룹들. v1에서는 항상 비어 있다.
 */
public record ConditionGroup(
        LogicalOperator logicalOperator,
        List<IndicatorCondition> conditions,
        List<ConditionGroup> childGroups
) {

    public ConditionGroup {
        if (logicalOperator == null) {
            throw new IllegalArgumentException("결합 연산자는 null일 수 없다");
        }
        conditions = conditions == null ? List.of() : List.copyOf(conditions);
        childGroups = childGroups == null ? List.of() : List.copyOf(childGroups);
        if (conditions.isEmpty() && childGroups.isEmpty()) {
            throw new IllegalArgumentException("조건이 하나도 없는 그룹은 만들 수 없다");
        }
    }

    /** v1이 쓰는 형태 — 조건 목록 하나를 AND로 묶는다. */
    public static ConditionGroup and(IndicatorCondition... conditions) {
        return new ConditionGroup(LogicalOperator.AND, List.of(conditions), List.of());
    }

    public static ConditionGroup and(List<IndicatorCondition> conditions) {
        return new ConditionGroup(LogicalOperator.AND, conditions, List.of());
    }

    /** v2를 위한 형태. v1에서는 만들지 않지만 판정·번역은 이미 지원한다. */
    public static ConditionGroup or(List<IndicatorCondition> conditions) {
        return new ConditionGroup(LogicalOperator.OR, conditions, List.of());
    }

    /** 이 그룹과 모든 하위 그룹의 조건을 평면화한다. 선정 이유 표시와 지표 조회에 쓴다. */
    public List<IndicatorCondition> flattenConditions() {
        List<IndicatorCondition> all = new ArrayList<>(conditions);
        for (ConditionGroup child : childGroups) {
            all.addAll(child.flattenConditions());
        }
        return all;
    }

    /** 이 그룹이 참조하는 모든 지표 코드. 필요한 값을 한 번에 조회할 때 쓴다. */
    public java.util.Set<String> referencedIndicatorCodes() {
        java.util.Set<String> codes = new java.util.LinkedHashSet<>();
        for (IndicatorCondition condition : flattenConditions()) {
            codes.addAll(condition.referencedIndicatorCodes());
        }
        return codes;
    }

    public enum LogicalOperator {
        AND,
        OR
    }
}
