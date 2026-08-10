package com.stockscreener.screening.web;

import com.stockscreener.screening.ComparisonOperator;
import com.stockscreener.screening.ConditionGroup;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.util.List;

/**
 * 요청·응답에 실리는 조건 트리.
 *
 * <p>도메인 {@link ConditionGroup}을 그대로 직렬화하지 않는 이유는 {@code sealed} 피연산자를
 * Jackson이 다루려면 타입 정보 애너테이션을 도메인에 붙여야 하기 때문이다. 도메인이 와이어
 * 형식을 알기 시작하면 JSON 형태를 바꿀 때마다 판정 로직이 있는 파일을 건드리게 된다.
 *
 * <p>구조는 처음부터 트리다. v1은 {@code childGroups}를 항상 비워 보내고
 * {@code ScreenDefinitionValidator}가 그것을 강제한다 — v2의 OR 도입이 이 형식을 바꾸지 않는다.
 */
public record ConditionGroupPayload(
        ConditionGroup.LogicalOperator logicalOperator,
        @Valid @NotEmpty(message = "조건을 하나 이상 지정해야 한다") List<ConditionPayload> conditions,
        @Valid List<ConditionGroupPayload> childGroups
) {

    public ConditionGroupPayload {
        logicalOperator = logicalOperator == null ? ConditionGroup.LogicalOperator.AND : logicalOperator;
        childGroups = childGroups == null ? List.of() : List.copyOf(childGroups);
    }

    /**
     * 조건 하나.
     *
     * @param weight   SCORING에서만 쓴다. null이면 1로 취급된다.
     * @param required SCORING에서 미충족 종목을 결과에서 뺀다. {@code weight}와 함께 쓸 수 없다.
     *                 생략하면 거짓이라, 이 필드를 모르는 옛 클라이언트의 요청이 종전과 같이 동작한다.
     */
    public record ConditionPayload(
            @NotNull(message = "지표 코드가 필요하다") String indicatorCode,
            @NotNull(message = "연산자가 필요하다") ComparisonOperator operator,
            @Valid @NotNull(message = "피연산자가 필요하다") OperandPayload operand,
            BigDecimal weight,
            Boolean required
    ) {

        /** 생략된 {@code required}를 거짓으로 읽는다. Jackson이 null을 넣는 자리다. */
        public boolean requiredOrFalse() {
            return Boolean.TRUE.equals(required);
        }
    }

    /**
     * 조건의 우변. 합타입을 {@code type} 판별자로 표현한다.
     *
     * <p>Jackson의 다형 역직렬화(@JsonTypeInfo) 대신 평면 record + 판별자를 쓴다. 필드 서넛이면
     * 다형 매핑의 설정 비용이 이득보다 크고, 잘못된 조합({@code type=LITERAL}인데 지표 코드가 온
     * 경우)의 오류 메시지가 훨씬 읽기 쉽다.
     */
    public record OperandPayload(
            @NotNull(message = "피연산자 종류가 필요하다") OperandType type,
            BigDecimal value,
            BigDecimal upperBound,
            String indicatorCode,
            BigDecimal multiplier
    ) {
    }

    public enum OperandType {
        /** 상수와 비교한다. {@code PER < 10}의 10. */
        LITERAL,
        /** 다른 지표와 비교한다. {@code ADJ_CLOSE > SMA_200 × 1.05}. */
        INDICATOR
    }
}
