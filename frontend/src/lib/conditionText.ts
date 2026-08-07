import type { ConditionInfo, IndicatorResponse, OperandPayload } from '@/api/types'
import type { DraftCondition } from '@/stores/screenDraft'

import { formatEnteredNumber, formatNumber } from './format'

/**
 * 조건 하나를 사람이 읽는 문장으로.
 *
 * 서버가 준 `operatorLabel`(한글)을 그대로 쓴다. 프론트가 연산자별 문구를 따로 들면 연산자가
 * 추가될 때 두 곳을 고쳐야 하고, 한쪽을 잊으면 조건은 만들 수 있는데 설명이 비어 있게 된다.
 *
 * 이 문장이 결과의 "왜 선정되었는가"를 이루므로, 판정에 쓰인 값과 다른 것을 적으면 안 된다.
 */
export function describeCondition(
  condition: ConditionInfo,
  indicatorName: (code: string) => string,
): string {
  const left = condition.indicator.name
  const scale = condition.indicator.decimalScale
  const operand = condition.operand

  if (condition.operator === 'BETWEEN') {
    const lower = formatNumber(operand.value ?? null, scale)
    const upper = formatNumber(operand.upperBound ?? null, scale)
    return `${left} ${lower} ~ ${upper} ${condition.operatorLabel}`
  }

  return `${left} ${describeOperand(operand, scale, indicatorName)} ${condition.operatorLabel}`
}

/**
 * 편집 중인 조건 하나를 **칩에 들어갈 길이로**.
 *
 * {@link describeCondition}과 합치지 않는 이유는 입력이 다르기 때문이다 — 저쪽은 응답의
 * `ConditionInfo`(완성된 것)이고 이쪽은 초안(`DraftCondition`, 작성 중일 수 있는 것)이다.
 * 그리고 문장의 성격도 다르다. 결과의 "왜 선정되었는가"는 서버가 판정에 쓴 값을 그대로
 * 옮겨야 하고, 칩은 사용자가 방금 넣은 것을 되비춰야 한다. `evaluationModeLabels`가
 * `noun`/`short`/`action`으로 변형에 이름을 붙인 것과 같은 방식이다(frontend.md).
 *
 * 연산자 문구는 여기서도 서버가 준 라벨(`operatorLabel`)을 그대로 쓴다. 칩이 짧아야 한다고
 * 부등호 기호를 프론트가 매핑하면, 연산자가 늘 때 고칠 곳이 둘이 된다.
 *
 * @param operatorLabel 서버가 준 한글 라벨. 카탈로그가 아직 안 왔으면 null이고, 그때는
 *                      연산자 자리를 비운다 — 임의의 문구를 지어내지 않는다.
 * @return 지표조차 고르지 않았으면 null. 칩은 그때 "지표 선택"을 스스로 그린다.
 */
export function summarizeDraftCondition(
  condition: DraftCondition,
  indicator: IndicatorResponse | null,
  operandIndicator: IndicatorResponse | null,
  operatorLabel: string | null,
): string | null {
  if (indicator === null) {
    return null
  }
  const scale = indicator.decimalScale
  const unit = indicator.unit ?? ''
  const tail = operatorLabel === null ? '' : ` ${operatorLabel}`

  if (condition.operandType === 'INDICATOR') {
    const name = operandIndicator?.name ?? condition.operandIndicatorCode ?? '?'
    const multiplier = condition.multiplier
    // 배수 1은 적지 않는다. "SMA_200 × 1"은 정보를 더하지 않고 칩만 길어진다.
    const right =
      multiplier === null || multiplier === 1 ? name : `${name} × ${formatEnteredNumber(multiplier, 2)}`
    return `${indicator.name} ${right}${tail}`
  }

  if (condition.operator === 'BETWEEN') {
    const lower = formatEnteredNumber(condition.value, scale)
    const upper = formatEnteredNumber(condition.upperBound, scale)
    return `${indicator.name} ${lower}~${upper}${unit}${tail}`
  }

  return `${indicator.name} ${formatEnteredNumber(condition.value, scale)}${unit}${tail}`
}

function describeOperand(
  operand: OperandPayload,
  decimalScale: number,
  indicatorName: (code: string) => string,
): string {
  if (operand.type === 'INDICATOR') {
    const code = operand.indicatorCode ?? null
    const name = code === null ? '?' : indicatorName(code)
    const multiplier = operand.multiplier
    // 배수 1은 적지 않는다. "SMA_200 × 1"은 정보를 더하지 않고 문장만 길어진다.
    if (multiplier === null || multiplier === undefined || multiplier === 1) {
      return name
    }
    return `${name} × ${formatNumber(multiplier, 2)}`
  }
  return formatNumber(operand.value ?? null, decimalScale)
}
