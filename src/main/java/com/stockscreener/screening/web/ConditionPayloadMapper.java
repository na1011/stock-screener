package com.stockscreener.screening.web;

import com.stockscreener.screening.ConditionGroup;
import com.stockscreener.screening.ConditionOperand;
import com.stockscreener.screening.IndicatorCondition;
import com.stockscreener.web.ApiExceptions.ConditionValidationException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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

    /**
     * 요청의 피연산자를 도메인으로 옮긴다.
     *
     * <p><b>{@code thresholdsByCurrency}가 오면 시장별 임계로 되살린다.</b> 안 되살리면 그 조건이
     * <b>시장 중립</b>이 되어 <b>한 통화의 값이 다른 시장에 그대로 걸린다</b> — 실측으로 버핏
     * 프리셋을 복제해 미국에서 돌리면 원본 43종목이 <b>4종목</b>이 됐다(원화 시총 1조를 달러에
     * 걸어 시총 $1조 이상만 남았다). <b>화면은 USD 값을 그리고 있어 아무 단서가 없다.</b>
     *
     * <p>MUST NOT: 이것을 「사용자가 축을 고른다」로 읽지 않는다(ADR-0106 §결정 4는 그대로다).
     * 화면에는 고르는 자리가 없고 이 맵은 <b>서버가 준 것을 그대로 되돌려 준 것</b>이다.
     * <b>사용자가 값을 손대면 화면이 맵을 안 보낸다</b> — 그때는 평범한 리터럴이 되고 축은
     * 저장 경로가 지표의 {@code value_type}에서 다시 유도한다.
     *
     * <p>맵이 비면 {@code MarketLiteral}이 던진다. 통화 코드가 우리가 모르는 것이면 그 조건은
     * 어느 조회 대상에서도 안 풀려 <b>요청 단위 거절</b>로 드러난다(ADR-0106 §규칙) —
     * 조용히 다른 값으로 판정되는 경로가 없다.
     */
    private static ConditionOperand toOperand(ConditionGroupPayload.ConditionPayload payload) {
        ConditionGroupPayload.OperandPayload operand = payload.operand();
        return switch (operand.type()) {
            case LITERAL -> toLiteral(operand);
            case INDICATOR -> new ConditionOperand.IndicatorRef(
                    operand.indicatorCode(), operand.multiplier());
        };
    }

    private static ConditionOperand toLiteral(ConditionGroupPayload.OperandPayload operand) {
        Map<String, ConditionGroupPayload.MarketThreshold> byCurrency = operand.thresholdsByCurrency();
        if (byCurrency == null || byCurrency.isEmpty()) {
            return new ConditionOperand.Literal(operand.value(), operand.upperBound());
        }
        Map<String, ConditionOperand.Literal> literals = new LinkedHashMap<>();
        byCurrency.forEach((currency, threshold) -> literals.put(currency,
                new ConditionOperand.Literal(threshold.value(), threshold.upperBound())));
        return new ConditionOperand.MarketLiteral(literals);
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
                    literal.value(), literal.upperBound(), null, null, null, null);
            case ConditionOperand.IndicatorRef ref -> new ConditionGroupPayload.OperandPayload(
                    ConditionGroupPayload.OperandType.INDICATOR,
                    null, null, ref.indicatorCode(), ref.multiplier(), null, null);
            // MUST: 통화별 임계를 **전부** 싣는다. 화면이 자기가 고른 조회 대상의 통화로
            // 고르기 때문이다. 하나만 실으면 그 값이 다른 시장의 단위로 그려진다 — 실측으로
            // 버핏 프리셋의 조건 칩이 「시가총액 7,940,000,000원 이상」(USD 값 + 원 단위)이
            // 됐고 같은 화면의 「선정 이유」는 1조원이라 위아래가 갈렸다.
            //
            // MUST NOT: 맵의 **첫** 항목을 「기준 통화」로 삼지 않는다. MarketLiteral이
            // Map.copyOf로 순서를 지우므로 어느 통화가 나올지 정해져 있지 않다 — 그것이
            // 위 결함의 원인이었다. 아래 정렬은 그 비결정성을 없애기 위한 것이고,
            // value는 **대상을 아직 모를 때의 표시 기본값**이지 기준이 아니다.
            case ConditionOperand.MarketLiteral market -> {
                Map<String, ConditionGroupPayload.MarketThreshold> byCurrency = new LinkedHashMap<>();
                market.byCurrency().entrySet().stream()
                        .sorted(Map.Entry.comparingByKey())
                        .forEach(entry -> byCurrency.put(entry.getKey(),
                                new ConditionGroupPayload.MarketThreshold(
                                        entry.getValue().value(), entry.getValue().upperBound())));
                var fallback = byCurrency.entrySet().iterator().next();
                yield new ConditionGroupPayload.OperandPayload(
                        ConditionGroupPayload.OperandType.LITERAL,
                        fallback.getValue().value(), fallback.getValue().upperBound(),
                        null, null, fallback.getKey(), Map.copyOf(byCurrency));
            }
        };
        return new ConditionGroupPayload.ConditionPayload(
                condition.indicatorCode(), condition.operator(), operand,
                condition.weight(), condition.required());
    }
}
