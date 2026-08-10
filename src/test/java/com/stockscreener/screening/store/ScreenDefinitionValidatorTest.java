package com.stockscreener.screening.store;

import com.stockscreener.screening.ComparisonOperator;
import com.stockscreener.screening.ConditionGroup;
import com.stockscreener.screening.ConditionOperand;
import com.stockscreener.screening.IndicatorCondition;
import com.stockscreener.screening.store.ScreenDefinitionValidator.ScreenValidationException;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * v1 조건 구조 제약. 스키마가 일부러 막지 않는 것을 여기서 막는다.
 *
 * <p>실패가 <b>어느 조건인지</b>를 들고 있어야 조건 빌더가 그 줄을 강조할 수 있다.
 * 인덱스 없이 거절만 하면 사용자는 조건을 하나씩 지워가며 범인을 찾게 된다.
 */
class ScreenDefinitionValidatorTest {

    private static final Set<String> KNOWN = Set.of("PER", "PBR", "RSI_14", "ADJ_CLOSE", "SMA_200");

    @Test
    @DisplayName("깊이 1의 AND 목록은 통과한다")
    void acceptsFlatAndGroup() {
        assertThatCode(() -> ScreenDefinitionValidator.validate(
                ConditionGroup.and(List.of(
                        literal("PER", ComparisonOperator.LT, "10"),
                        literal("PBR", ComparisonOperator.LT, "1"))), KNOWN))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("중첩 그룹은 거절한다 — 로더가 조용히 누락시키는 형태다")
    void rejectsNestedGroups() {
        ConditionGroup nested = new ConditionGroup(
                ConditionGroup.LogicalOperator.AND,
                List.of(literal("PER", ComparisonOperator.LT, "10")),
                List.of(ConditionGroup.or(List.of(literal("PBR", ComparisonOperator.LT, "1")))));

        assertThatThrownBy(() -> ScreenDefinitionValidator.validate(nested, KNOWN))
                .isInstanceOf(ScreenValidationException.class)
                .hasMessageContaining("중첩");
    }

    @Test
    @DisplayName("OR 결합은 거절한다 — v2의 범위다")
    void rejectsOrGroups() {
        assertThatThrownBy(() -> ScreenDefinitionValidator.validate(
                ConditionGroup.or(List.of(literal("PER", ComparisonOperator.LT, "10"))), KNOWN))
                .isInstanceOf(ScreenValidationException.class)
                .hasMessageContaining("AND");
    }

    @Test
    @DisplayName("조건 개수 상한을 넘으면 거절한다")
    void rejectsTooManyConditions() {
        List<IndicatorCondition> many = new ArrayList<>();
        for (int i = 0; i <= ScreenDefinitionValidator.MAX_CONDITIONS; i++) {
            many.add(literal("PER", ComparisonOperator.GT, String.valueOf(i)));
        }

        assertThatThrownBy(() -> ScreenDefinitionValidator.validate(ConditionGroup.and(many), KNOWN))
                .isInstanceOf(ScreenValidationException.class)
                .hasMessageContaining(String.valueOf(ScreenDefinitionValidator.MAX_CONDITIONS));
    }

    @Test
    @DisplayName("IMPORTANT — 폐기된 지표를 참조하면 조건 인덱스와 함께 거절한다")
    void rejectsUnknownIndicatorWithIndex() {
        ConditionGroup group = ConditionGroup.and(List.of(
                literal("PER", ComparisonOperator.LT, "10"),
                literal("RETIRED_INDICATOR", ComparisonOperator.LT, "5")));

        assertThatThrownBy(() -> ScreenDefinitionValidator.validate(group, KNOWN))
                .isInstanceOfSatisfying(ScreenValidationException.class, e -> {
                    // FK는 존재만 보장하고 is_active=false를 막지 못한다. 그 화면은 저장은 되지만
                    // 영원히 '판정 불가'만 낸다.
                    assertThat(e.conditionIndex()).isEqualTo(1);
                    assertThat(e.indicatorCode()).isEqualTo("RETIRED_INDICATOR");
                });
    }

    @Test
    @DisplayName("우변 지표도 검사한다 — 좌변만 보면 참조가 뚫린다")
    void validatesRightHandIndicatorToo() {
        ConditionGroup group = ConditionGroup.and(IndicatorCondition.of(
                "ADJ_CLOSE", ComparisonOperator.GT,
                ConditionOperand.IndicatorRef.of("SMA_999")));

        assertThatThrownBy(() -> ScreenDefinitionValidator.validate(group, KNOWN))
                .isInstanceOfSatisfying(ScreenValidationException.class,
                        e -> assertThat(e.indicatorCode()).isEqualTo("SMA_999"));
    }

    @Test
    @DisplayName("BETWEEN + 지표 참조는 저장 시점에 막는다 — 실행할 때만 깨지는 화면을 만들지 않는다")
    void rejectsBetweenWithIndicatorReference() {
        // 번역기가 이 조합에 IllegalArgumentException을 던진다. 여기서 막지 않으면
        // 저장은 성공하고 실행 버튼을 누를 때 500이 난다.
        ConditionGroup group = ConditionGroup.and(IndicatorCondition.of(
                "PER", ComparisonOperator.BETWEEN, ConditionOperand.IndicatorRef.of("PBR")));

        assertThatThrownBy(() -> ScreenDefinitionValidator.validate(group, KNOWN))
                .isInstanceOf(ScreenValidationException.class)
                .hasMessageContaining("BETWEEN");
    }

    @Test
    @DisplayName("지표 참조 조건도 정상이면 통과한다")
    void acceptsValidIndicatorReference() {
        assertThatCode(() -> ScreenDefinitionValidator.validate(
                ConditionGroup.and(IndicatorCondition.of("ADJ_CLOSE", ComparisonOperator.GT,
                        ConditionOperand.IndicatorRef.of("SMA_200", "1.05"))
                        .withWeight(new BigDecimal("2"))), KNOWN))
                .doesNotThrowAnyException();
    }

    private static IndicatorCondition literal(String code, ComparisonOperator operator, String value) {
        return IndicatorCondition.of(code, operator, ConditionOperand.Literal.of(value));
    }
}
