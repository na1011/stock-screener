import type { ConditionInfo, OperandPayload } from '@/api/types'

import { formatNumber } from './format'

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
