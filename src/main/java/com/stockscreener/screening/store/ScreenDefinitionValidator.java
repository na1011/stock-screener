package com.stockscreener.screening.store;

import com.stockscreener.screening.ConditionGroup;
import com.stockscreener.screening.ConditionOperand;
import com.stockscreener.screening.IndicatorCondition;

import java.util.Set;

/**
 * v1의 조건 구조 제약을 강제한다. <b>스키마가 아니라 여기서</b> 막는다.
 *
 * <p>V3는 일부러 깊이를 제한하는 제약을 두지 않았다 — 나중에 제거해야 할 것을 DB에 넣지
 * 않기 위해서다. 그 대가로 "v1은 깊이 1·AND만"이라는 규칙이 애플리케이션 계층에 있어야 하고,
 * 그것이 이 클래스다. v2에서 OR 트리를 열 때는 아래 두 검사만 지우면 되며, 번역기와 판정기는
 * 이미 재귀라 손대지 않는다(ADR-0003).
 *
 * <p>Spring 빈이 아닌 순수 클래스로 둔다 — 상태가 없고, 프리셋 시더와 API가 <b>같은</b>
 * 검증을 통과해야 하기 때문이다. 우회 경로를 하나라도 두면 UI가 열지 못하는 화면이 DB에 생긴다.
 */
public final class ScreenDefinitionValidator {

    /**
     * 조건 개수 상한.
     *
     * <p>조건 하나가 상관 EXISTS 서브쿼리 하나이므로 개수가 곧 쿼리 복잡도다. 상한이 없으면
     * 요청 하나로 수백 개의 서브쿼리를 만들 수 있고, 응답 페이로드도 종목 수 × 조건 수로 늘어난다.
     * 20은 실제 전략이 쓰는 조건 수(최대 6~8)보다 넉넉하다.
     */
    public static final int MAX_CONDITIONS = 20;

    private ScreenDefinitionValidator() {
    }

    /**
     * 조건 그룹이 v1이 다룰 수 있는 형태인지 검사한다.
     *
     * @param knownActiveIndicatorCodes 현재 활성인 지표 코드. 비활성·미등록 지표 참조를 막는다.
     * @throws ScreenValidationException 위반 시. 어느 조건인지 인덱스를 함께 담는다.
     */
    public static void validate(ConditionGroup group, Set<String> knownActiveIndicatorCodes) {
        if (!group.childGroups().isEmpty()) {
            throw new ScreenValidationException(
                    "v1은 중첩 조건 그룹을 지원하지 않는다. 조건을 하나의 AND 목록으로 표현하라.");
        }
        if (group.logicalOperator() != ConditionGroup.LogicalOperator.AND) {
            throw new ScreenValidationException(
                    "v1은 AND 결합만 지원한다. 조건 그룹 간 OR는 이후 버전의 범위다.");
        }

        var conditions = group.conditions();
        if (conditions.size() > MAX_CONDITIONS) {
            throw new ScreenValidationException(
                    "조건은 최대 %d개까지 지정할 수 있다. 현재 %d개다."
                            .formatted(MAX_CONDITIONS, conditions.size()));
        }

        for (int i = 0; i < conditions.size(); i++) {
            IndicatorCondition condition = conditions.get(i);
            requireKnownIndicator(condition.indicatorCode(), i, knownActiveIndicatorCodes);

            if (condition.operand() instanceof ConditionOperand.IndicatorRef ref) {
                requireKnownIndicator(ref.indicatorCode(), i, knownActiveIndicatorCodes);

                // 번역기가 이 조합에서 IllegalArgumentException을 던진다. 저장 시점에 막지 않으면
                // 저장은 되고 실행할 때만 깨지는 화면이 생긴다.
                if (condition.operator().requiresUpperBound()) {
                    throw new ScreenValidationException(i, condition.indicatorCode(),
                            "BETWEEN은 지표 참조를 우변으로 쓸 수 없다. 상·하한을 각각 조건으로 나누어라.");
                }
            }
        }
    }

    private static void requireKnownIndicator(String code, int index, Set<String> known) {
        // FK는 지표의 존재만 보장하고 is_active=false를 막지 못한다. 폐기된 지표로 만든 화면은
        // 저장은 되지만 영원히 판정 불가만 낸다 — 그 상태를 만들지 않는다.
        if (!known.contains(code)) {
            throw new ScreenValidationException(index, code,
                    "사용할 수 없는 지표다: " + code + " (없거나 폐기된 지표)");
        }
    }

    /**
     * 검증 실패. HTTP 계층이 이것을 400으로 옮긴다.
     *
     * <p>{@code web} 패키지의 예외를 직접 던지지 않는 이유는 프리셋 시더도 이 검증기를 쓰기
     * 때문이다 — 기동 시점의 실패가 HTTP 응답 타입으로 표현되면 읽는 사람이 혼란스럽다.
     */
    public static class ScreenValidationException extends IllegalArgumentException {

        private final Integer conditionIndex;
        private final String indicatorCode;

        public ScreenValidationException(String message) {
            super(message);
            this.conditionIndex = null;
            this.indicatorCode = null;
        }

        public ScreenValidationException(int conditionIndex, String indicatorCode, String message) {
            super(message);
            this.conditionIndex = conditionIndex;
            this.indicatorCode = indicatorCode;
        }

        /** 위반한 조건의 순번. 목록 전체의 문제면 null이다. */
        public Integer conditionIndex() {
            return conditionIndex;
        }

        public String indicatorCode() {
            return indicatorCode;
        }
    }
}
