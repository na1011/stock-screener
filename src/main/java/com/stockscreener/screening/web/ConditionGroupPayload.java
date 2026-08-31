package com.stockscreener.screening.web;

import com.stockscreener.screening.ComparisonOperator;
import com.stockscreener.screening.ConditionGroup;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

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
    /**
     * @param thresholdCurrency     {@code value}가 <b>어느 통화의 값인가</b>. {@code null}이면
     *                              시장 중립이다(부호·개수·연수·백분위·상대비교).
     *                              <b>읽기 전용이다</b> — 요청에 담아도 서버가 무시하고 조건에서
     *                              다시 유도한다. 그래서 사용자가 축을 잘못 고를 경로가 없다.
     * @param thresholdsByCurrency  <b>그 조건이 가진 통화별 임계 전부.</b> 시장 축이 없는 조건에는
     *                              {@code null}이다.
     *                              <p><b>MUST: 화면은 자기가 고른 조회 대상의 통화로 여기서
     *                              고른다.</b> {@code value} 하나만 읽으면 <b>다른 시장의 임계를
     *                              자기 시장 단위로 그린다</b> — 실측으로 버핏 프리셋의 조건 칩이
     *                              「시가총액 7,940,000,000원 이상」이 됐고(USD 값 + 원 단위),
     *                              같은 화면의 「선정 이유」는 1조원이라 <b>위아래가 갈렸다.</b>
     *                              <p><b>MUST NOT: 「기준 통화」가 있다고 읽지 않는다.</b> 한때
     *                              {@code toPayload}가 맵의 <i>첫</i> 항목을 실었는데
     *                              {@code MarketLiteral}이 {@code Map.copyOf}로 순서를 지우므로
     *                              <b>어느 통화가 나올지 정해져 있지 않았다.</b> 지금
     *                              {@code value}는 통화 코드 오름차순의 첫 값이고, 그것은
     *                              <b>대상을 아직 모를 때의 표시 기본값일 뿐</b> 기준이 아니다.
     */
    public record OperandPayload(
            @NotNull(message = "피연산자 종류가 필요하다") OperandType type,
            BigDecimal value,
            BigDecimal upperBound,
            String indicatorCode,
            BigDecimal multiplier,
            String thresholdCurrency,
            Map<String, MarketThreshold> thresholdsByCurrency
    ) {
    }

    /** 한 통화에서 잰 임계. {@code upperBound}는 BETWEEN에만 있다. */
    public record MarketThreshold(BigDecimal value, BigDecimal upperBound) {
    }

    public enum OperandType {
        /** 상수와 비교한다. {@code PER < 10}의 10. */
        LITERAL,
        /** 다른 지표와 비교한다. {@code ADJ_CLOSE > SMA_200 × 1.05}. */
        INDICATOR
    }
}
