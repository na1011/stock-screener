package com.stockscreener.screening;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Optional;

/**
 * 조건 하나. "지표 - 연산자 - 피연산자"의 3항 구조다.
 *
 * <p>이 객체는 자신이 어느 판정 방식(FILTER/SCORING)에서 쓰이는지 모른다. 가중치와 필수 여부는
 * 조건 자체가 아니라 조건-소속 관계의 속성이고, 판정 방식은 컨테이너의 속성이다. 그래서 하나의
 * 조건 모델이 두 판정을 모두 지탱한다 — {@code backend.md}가 요구한 성질이다.
 *
 * <p>{@code required}와 {@code weight}는 <b>함께 쓸 수 없다.</b> 필수 조건을 통과한 종목은
 * 정의상 그것을 전부 충족하므로 같은 몫을 받고, 그러면 달성률이 언제나 높게 나와 변별이 사라진다.
 * 같은 불변식을 {@code ck_cond_required_weight}가 DB에서도 강제한다.
 *
 * @param indicatorCode 좌변 지표
 * @param operator      비교 연산자
 * @param operand       우변 (리터럴 또는 지표 참조)
 * @param weight        SCORING에서 이 조건이 갖는 가중치. FILTER에서는 무시되며 null일 수 있다.
 * @param required      SCORING에서 이 조건을 충족하지 못한 종목을 결과에서 뺀다.
 *                      FILTER에서는 모든 조건이 이미 필수라 무시된다.
 */
public record IndicatorCondition(
        String indicatorCode,
        ComparisonOperator operator,
        ConditionOperand operand,
        BigDecimal weight,
        boolean required
) {

    public IndicatorCondition {
        if (indicatorCode == null || indicatorCode.isBlank()) {
            throw new IllegalArgumentException("지표 코드는 비어 있을 수 없다");
        }
        if (operator == null) {
            throw new IllegalArgumentException("연산자는 null일 수 없다");
        }
        if (operand == null) {
            throw new IllegalArgumentException("피연산자는 null일 수 없다");
        }
        if (weight != null && weight.signum() <= 0) {
            throw new IllegalArgumentException("가중치는 양수여야 한다: " + weight);
        }
        if (required && weight != null) {
            throw new IllegalArgumentException(
                    "필수 조건은 가중치를 가질 수 없다 — 점수에 참여하지 않는다: " + indicatorCode);
        }
        if (operator.requiresUpperBound()
                && operand instanceof ConditionOperand.Literal literal
                && literal.upperBound() == null) {
            throw new IllegalArgumentException("BETWEEN에는 상한이 필요하다");
        }
    }

    public static IndicatorCondition of(String indicatorCode, ComparisonOperator operator, ConditionOperand operand) {
        return new IndicatorCondition(indicatorCode, operator, operand, null, false);
    }

    public IndicatorCondition withWeight(BigDecimal weight) {
        return new IndicatorCondition(indicatorCode, operator, operand, weight, required);
    }

    /** 이 조건을 필수로 만든다. 가중치는 함께 지울 수밖에 없다 — 위 불변식을 보라. */
    public IndicatorCondition asRequired() {
        return new IndicatorCondition(indicatorCode, operator, operand, null, true);
    }

    /** SCORING에서 쓸 가중치. 지정되지 않았으면 1로 본다. */
    public BigDecimal effectiveWeight() {
        return weight == null ? BigDecimal.ONE : weight;
    }

    /**
     * 이 조건이 참조하는 지표 코드 전부. 좌변과 (지표 참조인 경우) 우변을 포함한다.
     *
     * <p>필요한 지표 값을 한 번에 조회할 때 쓴다.
     */
    public java.util.Set<String> referencedIndicatorCodes() {
        if (operand instanceof ConditionOperand.IndicatorRef ref) {
            return java.util.Set.of(indicatorCode, ref.indicatorCode());
        }
        return java.util.Set.of(indicatorCode);
    }

    /**
     * 이 조건을 <b>그 통화 시장용으로</b> 해석한다.
     *
     * <p>{@link ConditionOperand.MarketLiteral}이면 그 통화의 임계를 골라 평범한
     * {@link ConditionOperand.Literal}로 바꾼다. 그래서 <b>해석 뒤에는 아래
     * {@link #evaluate}도, SQL 번역도, 백테스트 하네스도 이 축을 몰라도 된다.</b>
     *
     * @return 해석된 조건. 그 통화의 임계가 <b>없으면 빈 값</b>이다 — 호출부가 모아서
     *         요청 단위로 거절한다({@link MarketThresholdMissingException}).
     *         <b>다른 통화의 값으로 대신하지 않는다.</b>
     */
    public Optional<IndicatorCondition> resolveFor(String currencyCode) {
        if (!(operand instanceof ConditionOperand.MarketLiteral market)) {
            return Optional.of(this);
        }
        ConditionOperand.Literal chosen = market.forCurrency(currencyCode);
        return chosen == null ? Optional.empty()
                : Optional.of(new IndicatorCondition(
                        indicatorCode, operator, chosen, weight, required));
    }

    /**
     * 주어진 지표 값들에 대해 이 조건을 판정한다.
     *
     * <p>IMPORTANT — 이것이 판정 의미의 정본이다. SQL 번역이 종목을 고른 근거와 화면에 보여주는
     * 선정 이유가 같은 객체에서 나오므로 어긋날 수 없다. Phase 6의 워치리스트 알림 판정도
     * 이 메서드를 그대로 재사용한다.
     *
     * @param values 지표 코드 → 값. 계산되지 않은 지표는 키가 <b>없다</b>(null 값이 아니라 부재).
     */
    public ConditionOutcome evaluate(Map<String, BigDecimal> values) {
        BigDecimal actual = values.get(indicatorCode);
        if (actual == null) {
            // 이력 부족 등으로 지표가 계산되지 않았다. 충족도 불충족도 아니다.
            return ConditionOutcome.notEvaluable(this);
        }

        BigDecimal bound;
        BigDecimal upperBound = null;

        switch (operand) {
            case ConditionOperand.Literal literal -> {
                bound = literal.value();
                upperBound = literal.upperBound();
            }
            case ConditionOperand.IndicatorRef ref -> {
                BigDecimal reference = values.get(ref.indicatorCode());
                if (reference == null) {
                    return ConditionOutcome.notEvaluable(this);
                }
                bound = ref.applyTo(reference);
            }
            // 도달할 수 없다 — 실행 경로는 resolveFor를 먼저 지나고, 그 통화의 임계가 없으면
            // 요청 단위로 거절된다. 판정불가로 접으면 조건이 없는 것처럼 종목이 통과하거나
            // (SQL 번역) 0건의 사유가 사라진다(MarketThresholdMissingException의 javadoc).
            case ConditionOperand.MarketLiteral ignored -> throw new IllegalStateException(
                    "해석되지 않은 시장별 임계로 판정을 시도했다: " + indicatorCode
                            + " — 실행 전에 resolveFor를 거쳐야 한다");
        }

        boolean matched = operator.test(actual, bound, upperBound);
        return ConditionOutcome.evaluated(this, actual, matched);
    }
}
