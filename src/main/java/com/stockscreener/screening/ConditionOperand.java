package com.stockscreener.screening;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 조건의 우변. 리터럴 값이거나 다른 지표에 대한 참조다.
 *
 * <p>합타입을 처음부터 두는 이유는 "종가 &gt; SMA200" 같은 지표 대 지표 조건 때문이다.
 * 이를 표현할 수 없으면 이동평균 돌파류 조건이 전부 하드코딩 분기로 우회되는데,
 * 그것이 {@code backend.md}가 금지한 것이다. 나중에 넣으면 저장된 모든 조건이 깨지는
 * 변경이지만, 지금 넣으면 작은 닫힌 집합에 케이스 하나를 더하는 것으로 끝난다.
 *
 * <p>세 번째 변형({@link MarketLiteral})은 <b>같은 조건이 시장마다 다른 임계를 갖는</b> 경우다.
 * 프리셋을 시장에서 빼는 대신 임계가 시장을 따라가게 하는 것이 그 목적이고, 근거는
 * ADR-0073 §규칙의 <i>"미국 프리셋을 만들 때 이 임계를 복사하지 않고 그 시장 분포로 다시
 * 잰다"</i>이다.
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
     * <b>시장마다 다른 상수</b>와 비교한다. 키는 그 임계를 잰 시장의 <b>통화</b>다.
     *
     * <p>축을 통화로 잡는 이유는 {@code KRW} 하나가 코스피·코스닥 둘을 덮어 <b>시장이 늘 때
     * 목록을 고칠 필요가 없기</b> 때문이다(ADR-0099). 시장 코드로 두면 고치지 않은 조건이
     * 새 시장까지 조용히 판정한다.
     *
     * <p>IMPORTANT — <b>이 변형이 있다는 것은 「이 수가 어느 시장 분포에서 나왔는가」가
     * 조건마다 기록된다는 뜻이다.</b> 시장 중립인 조건({@link Literal})과 일부러 갈라 둔다 —
     * 중립의 근거가 될 수 있는 것은 <b>부호·개수·연수·백분위·상대비교</b>뿐이고
     * 「비율이니까」는 근거가 아니다(P27 축①). 실측이 그것을 뒷받침한다:
     * {@code ATR_PCT_14 &lt;= 5.9}가 국내 39.4% 대 미국 <b>89.7%</b>를 자르는 반면
     * {@code PER_PCTL &lt;= 0.30}은 24.3% 대 24.8%로 거의 같다(2026-08-31 실측).
     *
     * <p>MUST NOT: 없는 통화를 다른 통화의 값으로 대신하지 않는다. 금액은 옮기면 즉시 틀린
     * 것이 드러나지만 <b>무차원은 아무 증상이 없다</b>(ADR-0072). 없으면 실행을 거절한다 —
     * 그 판정은 {@link IndicatorCondition#resolveFor}가 한다.
     *
     * @param byCurrency 통화 코드 → 그 시장 분포에서 잰 임계. 비어 있을 수 없다.
     */
    record MarketLiteral(Map<String, Literal> byCurrency) implements ConditionOperand {

        public MarketLiteral {
            if (byCurrency == null || byCurrency.isEmpty()) {
                throw new IllegalArgumentException(
                        "시장별 임계는 통화 하나 이상을 가져야 한다 — 비면 어느 시장에서도"
                                + " 판정할 수 없고, 그것은 조건을 지운 것과 같다");
            }
            byCurrency = Map.copyOf(byCurrency);
        }

        public static MarketLiteral of(String currencyCode, Literal literal) {
            Map<String, Literal> one = new LinkedHashMap<>();
            one.put(currencyCode, literal);
            return new MarketLiteral(one);
        }

        /** 그 통화의 임계. 없으면 {@code null} — 호출부가 거절로 접는다. */
        public Literal forCurrency(String currencyCode) {
            return byCurrency.get(currencyCode);
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
