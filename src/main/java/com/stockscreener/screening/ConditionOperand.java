package com.stockscreener.screening;

import java.math.BigDecimal;

/**
 * 조건의 우변. 리터럴 값이거나 다른 지표에 대한 참조다.
 *
 * <p>합타입을 처음부터 두는 이유는 "종가 &gt; SMA200" 같은 지표 대 지표 조건 때문이다.
 * 이를 표현할 수 없으면 이동평균 돌파류 조건이 전부 하드코딩 분기로 우회되는데,
 * 그것이 {@code backend.md}가 금지한 것이다. 나중에 넣으면 저장된 모든 조건이 깨지는
 * 변경이지만, 지금 넣으면 작은 닫힌 집합에 케이스 하나를 더하는 것으로 끝난다.
 *
 * <p>DB의 {@code ck_cond_operand} CHECK 제약과 같은 불변식을 표현한다.
 */
public sealed interface ConditionOperand {

    /**
     * 상수와 비교한다. {@code PER < 10}의 {@code 10}.
     *
     * @param value      비교 기준값. BETWEEN에서는 하한.
     * @param upperBound BETWEEN의 상한. 그 외에는 null.
     */
    record Literal(BigDecimal value, BigDecimal upperBound) implements ConditionOperand {

        public Literal {
            if (value == null) {
                throw new IllegalArgumentException("리터럴 피연산자의 값은 null일 수 없다");
            }
        }

        public static Literal of(BigDecimal value) {
            return new Literal(value, null);
        }

        public static Literal of(String value) {
            return new Literal(new BigDecimal(value), null);
        }

        public static Literal between(BigDecimal lower, BigDecimal upper) {
            if (upper == null) {
                throw new IllegalArgumentException("BETWEEN에는 상한이 필요하다");
            }
            return new Literal(lower, upper);
        }
    }

    /**
     * 다른 지표와 비교한다. {@code CLOSE > SMA_200 × 1.05}의 {@code SMA_200 × 1.05}.
     *
     * @param indicatorCode 비교 대상 지표
     * @param multiplier    계수. null이면 1.0으로 본다. "SMA200보다 5% 위" 같은 여유를 준 돌파 조건에 쓴다.
     */
    record IndicatorRef(String indicatorCode, BigDecimal multiplier) implements ConditionOperand {

        public IndicatorRef {
            if (indicatorCode == null || indicatorCode.isBlank()) {
                throw new IllegalArgumentException("참조 지표 코드는 비어 있을 수 없다");
            }
        }

        public static IndicatorRef of(String indicatorCode) {
            return new IndicatorRef(indicatorCode, null);
        }

        public static IndicatorRef of(String indicatorCode, String multiplier) {
            return new IndicatorRef(indicatorCode, new BigDecimal(multiplier));
        }

        /** 계수를 적용한 실효 기준값. */
        public BigDecimal applyTo(BigDecimal referenceValue) {
            return multiplier == null ? referenceValue : referenceValue.multiply(multiplier);
        }
    }
}
