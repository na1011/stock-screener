package com.stockscreener.screening.web;

import com.stockscreener.screening.ConditionGroup;
import com.stockscreener.screening.ConditionOperand;
import com.stockscreener.screening.IndicatorCondition;
import com.stockscreener.web.ApiExceptions.ConditionValidationException;

import java.util.ArrayList;
import java.util.List;

/**
 * 요청 페이로드를 도메인 조건 트리로 옮긴다.
 *
 * <p>IMPORTANT — 이 클래스의 존재 이유는 <b>조건 인덱스</b>다. {@code IndicatorCondition}과
 * {@code ConditionOperand}의 생성자가 좋은 불변식을 던지지만 자기가 몇 번째 조건인지 모른다.
 * 그대로 흘려보내면 사용자 입력 문제가 500으로 나가고, 화면은 어느 줄을 강조할지 알 수 없어
 * 사용자가 조건을 하나씩 지워가며 범인을 찾아야 한다.
 *
 * <p>도메인 불변식을 약화시키지 않으면서 그 문제를 푸는 유일한 지점이다 — 인덱스를 아는
 * 루프에서 감싼다.
 */
public final class ConditionPayloadMapper {

    private ConditionPayloadMapper() {
    }

    public static ConditionGroup toDomain(ConditionGroupPayload payload) {
        return toDomain(payload, new int[]{0});
    }

    /**
     * 구조 검증 실패를 HTTP 오류로 옮긴다.
     *
     * <p>{@code ScreenValidationException}은 {@code IllegalArgumentException}이라 그대로 두면
     * 500으로 나간다 — 사용자가 고칠 수 있는 입력 문제가 서버 버그처럼 보인다.
     * 실행 경로와 저장 경로가 <b>같은 변환</b>을 쓰도록 여기 한 곳에 둔다.
     */
    public static ConditionValidationException toApiException(
            com.stockscreener.screening.store.ScreenDefinitionValidator.ScreenValidationException e) {
        return e.conditionIndex() == null
                ? new ConditionValidationException(e.getMessage())
                : new ConditionValidationException(e.conditionIndex(), e.indicatorCode(), e.getMessage());
    }

    /**
     * @param counter 트리 전체를 관통하는 조건 순번. 중첩 그룹이 와도 인덱스가 겹치지 않는다 —
     *                v1은 중첩을 거절하지만, 거절하는 그 오류 메시지조차 정확한 순번을 담아야 한다.
     */
    private static ConditionGroup toDomain(ConditionGroupPayload payload, int[] counter) {
        List<IndicatorCondition> conditions = new ArrayList<>();
        for (ConditionGroupPayload.ConditionPayload condition : payload.conditions()) {
            int index = counter[0]++;
            conditions.add(toCondition(condition, index));
        }

        List<ConditionGroup> children = new ArrayList<>();
        for (ConditionGroupPayload child : payload.childGroups()) {
            children.add(toDomain(child, counter));
        }

        try {
            return new ConditionGroup(payload.logicalOperator(), conditions, children);
        } catch (IllegalArgumentException e) {
            // 조건 0개짜리 그룹 같은 경우. 특정 조건의 문제가 아니라 목록 전체의 문제다.
            throw new ConditionValidationException(e.getMessage());
        }
    }

    private static IndicatorCondition toCondition(ConditionGroupPayload.ConditionPayload payload, int index) {
        try {
            return new IndicatorCondition(
                    payload.indicatorCode(), payload.operator(), toOperand(payload),
                    payload.weight(), payload.requiredOrFalse());
        } catch (IllegalArgumentException e) {
            throw new ConditionValidationException(index, payload.indicatorCode(), e);
        }
    }

    private static ConditionOperand toOperand(ConditionGroupPayload.ConditionPayload payload) {
        ConditionGroupPayload.OperandPayload operand = payload.operand();
        // 들어오는 쪽은 언제나 평범한 리터럴이다 — thresholdCurrency는 읽기 전용이고
        // 저장 경로가 조건에서 다시 유도한다. 사용자가 축을 잘못 고를 경로를 아예 만들지 않는다.
        return switch (operand.type()) {
            case LITERAL -> new ConditionOperand.Literal(operand.value(), operand.upperBound());
            case INDICATOR -> new ConditionOperand.IndicatorRef(
                    operand.indicatorCode(), operand.multiplier());
        };
    }

    // ── 도메인 → 페이로드 (응답에 조건 사전을 실을 때) ───────────────────────

    public static ConditionGroupPayload toPayload(ConditionGroup group) {
        List<ConditionGroupPayload.ConditionPayload> conditions = group.conditions().stream()
                .map(ConditionPayloadMapper::toPayload)
                .toList();
        List<ConditionGroupPayload> children = group.childGroups().stream()
                .map(ConditionPayloadMapper::toPayload)
                .toList();
        return new ConditionGroupPayload(group.logicalOperator(), conditions, children);
    }

    public static ConditionGroupPayload.ConditionPayload toPayload(IndicatorCondition condition) {
        ConditionGroupPayload.OperandPayload operand = switch (condition.operand()) {
            case ConditionOperand.Literal literal -> new ConditionGroupPayload.OperandPayload(
                    ConditionGroupPayload.OperandType.LITERAL,
                    literal.value(), literal.upperBound(), null, null, null);
            case ConditionOperand.IndicatorRef ref -> new ConditionGroupPayload.OperandPayload(
                    ConditionGroupPayload.OperandType.INDICATOR,
                    null, null, ref.indicatorCode(), ref.multiplier(), null);
            // 기준 통화의 값만 싣고 그 통화를 함께 밝힌다. 화면은 이것을 **읽기 전용**으로
            // 보인다 — 안 보이면 사용자는 자기 전략이 왜 어떤 시장에서 안 도는지 알 수 없다.
            //
            // MUST NOT: 통화를 밝히지 않은 채 값만 싣지 않는다. 그러면 미국 실행인데 원화
            // 기준값이 「선정 이유」로 표시되고, 그 거짓은 대조하기 전까지 드러나지 않는다.
            case ConditionOperand.MarketLiteral market -> {
                var first = market.byCurrency().entrySet().iterator().next();
                yield new ConditionGroupPayload.OperandPayload(
                        ConditionGroupPayload.OperandType.LITERAL,
                        first.getValue().value(), first.getValue().upperBound(),
                        null, null, first.getKey());
            }
        };
        return new ConditionGroupPayload.ConditionPayload(
                condition.indicatorCode(), condition.operator(), operand,
                condition.weight(), condition.required());
    }
}
