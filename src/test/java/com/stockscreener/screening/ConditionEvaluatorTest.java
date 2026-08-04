package com.stockscreener.screening;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ConditionEvaluatorTest {

    private final ConditionEvaluator evaluator = new ConditionEvaluator();

    private static final IndicatorCondition PER_UNDER_10 =
            IndicatorCondition.of("PER", ComparisonOperator.LT, ConditionOperand.Literal.of("10"))
                    .withWeight(new BigDecimal("3"));
    private static final IndicatorCondition PBR_UNDER_1 =
            IndicatorCondition.of("PBR", ComparisonOperator.LT, ConditionOperand.Literal.of("1"))
                    .withWeight(new BigDecimal("2"));

    @Nested
    @DisplayName("하나의 조건 집합이 두 판정 방식을 모두 지탱한다")
    class SharedModel {

        @Test
        @DisplayName("FILTER — 모든 조건을 만족해야 통과한다")
        void filterRequiresAll() {
            ConditionGroup group = ConditionGroup.and(PER_UNDER_10, PBR_UNDER_1);

            ScreeningResult both = evaluator.evaluate(1L, group, EvaluationMode.FILTER,
                    Map.of("PER", new BigDecimal("8"), "PBR", new BigDecimal("0.8")));
            ScreeningResult onlyOne = evaluator.evaluate(2L, group, EvaluationMode.FILTER,
                    Map.of("PER", new BigDecimal("8"), "PBR", new BigDecimal("2.0")));

            assertThat(both.included()).isTrue();
            assertThat(onlyOne.included()).isFalse();
        }

        @Test
        @DisplayName("SCORING — 같은 조건 집합으로 가중 점수를 낸다")
        void scoringUsesSameGroup() {
            ConditionGroup group = ConditionGroup.and(PER_UNDER_10, PBR_UNDER_1);

            ScreeningResult both = evaluator.evaluate(1L, group, EvaluationMode.SCORING,
                    Map.of("PER", new BigDecimal("8"), "PBR", new BigDecimal("0.8")));
            ScreeningResult onlyOne = evaluator.evaluate(2L, group, EvaluationMode.SCORING,
                    Map.of("PER", new BigDecimal("8"), "PBR", new BigDecimal("2.0")));

            assertThat(both.score()).isEqualByComparingTo("5");   // 3 + 2
            assertThat(onlyOne.score()).isEqualByComparingTo("3"); // PER만
            assertThat(both.maxScore()).isEqualByComparingTo("5");
        }

        @Test
        @DisplayName("SCORING은 일부만 만족해도 결과에 남는다 — 순위를 매기는 것이 목적이기 때문")
        void scoringKeepsPartialMatches() {
            ConditionGroup group = ConditionGroup.and(PER_UNDER_10, PBR_UNDER_1);

            ScreeningResult noneMatched = evaluator.evaluate(1L, group, EvaluationMode.SCORING,
                    Map.of("PER", new BigDecimal("50"), "PBR", new BigDecimal("5")));

            assertThat(noneMatched.included()).isTrue();
            assertThat(noneMatched.score()).isEqualByComparingTo("0");
        }
    }

    @Nested
    @DisplayName("IMPORTANT — 지표 값이 없는 종목은 두 모드에서 다르게 동작해야 한다")
    class MissingIndicatorBehaviour {

        @Test
        @DisplayName("FILTER — 값이 없으면 제외된다")
        void filterExcludesWhenValueMissing() {
            ConditionGroup group = ConditionGroup.and(PER_UNDER_10);

            // PER이 계산되지 않았다(이력 부족, 적자 등). 키 자체가 없다.
            ScreeningResult result = evaluator.evaluate(1L, group, EvaluationMode.FILTER, Map.of());

            assertThat(result.included()).isFalse();
        }

        @Test
        @DisplayName("SCORING — 값이 없어도 종목은 남고 그 조건만 0점이다")
        void scoringKeepsStockWithZeroForMissing() {
            ConditionGroup group = ConditionGroup.and(PER_UNDER_10, PBR_UNDER_1);

            // PBR만 있고 PER은 없다.
            ScreeningResult result = evaluator.evaluate(1L, group, EvaluationMode.SCORING,
                    Map.of("PBR", new BigDecimal("0.8")));

            assertThat(result.included()).isTrue();
            assertThat(result.score()).isEqualByComparingTo("2"); // PBR만 득점
            assertThat(result.notEvaluableCount()).isEqualTo(1);
        }

        @Test
        @DisplayName("'데이터 없음'과 '조건 불충족'은 구분된다 — 화면에 다르게 보여야 한다")
        void distinguishesNotEvaluableFromNotMatched() {
            ConditionGroup group = ConditionGroup.and(PER_UNDER_10, PBR_UNDER_1);

            ScreeningResult result = evaluator.evaluate(1L, group, EvaluationMode.SCORING,
                    Map.of("PBR", new BigDecimal("5")));  // PBR은 있지만 불충족, PER은 없음

            Map<String, ConditionOutcome.Status> byCode = result.outcomes().stream()
                    .collect(java.util.stream.Collectors.toMap(
                            o -> o.condition().indicatorCode(), ConditionOutcome::status));

            assertThat(byCode.get("PER")).isEqualTo(ConditionOutcome.Status.NOT_EVALUABLE);
            assertThat(byCode.get("PBR")).isEqualTo(ConditionOutcome.Status.NOT_MATCHED);
        }
    }

    @Nested
    @DisplayName("선정 이유")
    class Explanation {

        @Test
        @DisplayName("조건별 실제 값과 획득 점수가 함께 나온다")
        void exposesPerConditionBreakdown() {
            ConditionGroup group = ConditionGroup.and(PER_UNDER_10, PBR_UNDER_1);

            ScreeningResult result = evaluator.evaluate(1L, group, EvaluationMode.SCORING,
                    Map.of("PER", new BigDecimal("8.5"), "PBR", new BigDecimal("2.0")));

            ConditionOutcome per = result.outcomes().get(0);
            assertThat(per.actualValue()).isEqualByComparingTo("8.5");
            assertThat(per.earnedScore()).isEqualByComparingTo("3");

            ConditionOutcome pbr = result.outcomes().get(1);
            assertThat(pbr.actualValue()).isEqualByComparingTo("2.0");
            assertThat(pbr.earnedScore()).isEqualByComparingTo("0");
        }
    }

    @Nested
    @DisplayName("지표 대 지표 비교")
    class IndicatorReference {

        @Test
        @DisplayName("다른 지표를 기준으로 비교한다 — 종가 > SMA200")
        void comparesAgainstAnotherIndicator() {
            IndicatorCondition aboveSma = IndicatorCondition.of(
                    "CLOSE", ComparisonOperator.GT, ConditionOperand.IndicatorRef.of("SMA_200"));
            ConditionGroup group = ConditionGroup.and(aboveSma);

            ScreeningResult above = evaluator.evaluate(1L, group, EvaluationMode.FILTER,
                    Map.of("CLOSE", new BigDecimal("70000"), "SMA_200", new BigDecimal("65000")));
            ScreeningResult below = evaluator.evaluate(2L, group, EvaluationMode.FILTER,
                    Map.of("CLOSE", new BigDecimal("60000"), "SMA_200", new BigDecimal("65000")));

            assertThat(above.included()).isTrue();
            assertThat(below.included()).isFalse();
        }

        @Test
        @DisplayName("계수를 적용한다 — 종가 > SMA200 × 1.05 (여유를 둔 돌파)")
        void appliesMultiplier() {
            IndicatorCondition breakout = IndicatorCondition.of(
                    "CLOSE", ComparisonOperator.GT, ConditionOperand.IndicatorRef.of("SMA_200", "1.05"));
            ConditionGroup group = ConditionGroup.and(breakout);

            // 66,000은 SMA200(65,000)보다 위지만 5% 여유(68,250)에는 못 미친다.
            ScreeningResult narrow = evaluator.evaluate(1L, group, EvaluationMode.FILTER,
                    Map.of("CLOSE", new BigDecimal("66000"), "SMA_200", new BigDecimal("65000")));
            ScreeningResult wide = evaluator.evaluate(2L, group, EvaluationMode.FILTER,
                    Map.of("CLOSE", new BigDecimal("70000"), "SMA_200", new BigDecimal("65000")));

            assertThat(narrow.included()).isFalse();
            assertThat(wide.included()).isTrue();
        }

        @Test
        @DisplayName("참조 지표가 없으면 판정 불가다")
        void notEvaluableWhenReferenceMissing() {
            IndicatorCondition aboveSma = IndicatorCondition.of(
                    "CLOSE", ComparisonOperator.GT, ConditionOperand.IndicatorRef.of("SMA_200"));

            ConditionOutcome outcome = aboveSma.evaluate(Map.of("CLOSE", new BigDecimal("70000")));

            assertThat(outcome.evaluable()).isFalse();
        }
    }

    @Nested
    @DisplayName("트리 구조 — v1은 만들지 않지만 판정은 이미 지원한다")
    class TreeSupport {

        @Test
        @DisplayName("OR 그룹을 판정한다")
        void evaluatesOrGroup() {
            ConditionGroup orGroup = ConditionGroup.or(java.util.List.of(PER_UNDER_10, PBR_UNDER_1));

            ScreeningResult onlyPer = evaluator.evaluate(1L, orGroup, EvaluationMode.FILTER,
                    Map.of("PER", new BigDecimal("8"), "PBR", new BigDecimal("5")));
            ScreeningResult neither = evaluator.evaluate(2L, orGroup, EvaluationMode.FILTER,
                    Map.of("PER", new BigDecimal("50"), "PBR", new BigDecimal("5")));

            assertThat(onlyPer.included()).isTrue();
            assertThat(neither.included()).isFalse();
        }

        @Test
        @DisplayName("중첩 그룹을 재귀적으로 판정한다 — (A AND (B OR C))")
        void evaluatesNestedGroup() {
            IndicatorCondition roeOver15 = IndicatorCondition.of(
                    "ROE", ComparisonOperator.GT, ConditionOperand.Literal.of("15"));
            ConditionGroup inner = ConditionGroup.or(java.util.List.of(PBR_UNDER_1, roeOver15));
            ConditionGroup outer = new ConditionGroup(
                    ConditionGroup.LogicalOperator.AND,
                    java.util.List.of(PER_UNDER_10),
                    java.util.List.of(inner));

            // PER 충족 + (PBR 불충족 OR ROE 충족) → 통과
            ScreeningResult result = evaluator.evaluate(1L, outer, EvaluationMode.FILTER,
                    Map.of("PER", new BigDecimal("8"),
                           "PBR", new BigDecimal("5"),
                           "ROE", new BigDecimal("20")));

            assertThat(result.included()).isTrue();
            assertThat(result.outcomes()).hasSize(3);
        }
    }
}
