import type { ConditionInfo, IndicatorResponse, OperandPayload } from '@/api/types'
import type { DraftCondition } from '@/stores/screenDraft'

import { formatEnteredNumber, formatIndicatorValue, formatNumber } from './format'

/**
 * 조건 하나를 사람이 읽는 문장으로.
 *
 * 서버가 준 `operatorLabel`(한글)을 그대로 쓴다. 프론트가 연산자별 문구를 따로 들면 연산자가
 * 추가될 때 두 곳을 고쳐야 하고, 한쪽을 잊으면 조건은 만들 수 있는데 설명이 비어 있게 된다.
 *
 * 이 문장이 결과의 "왜 선정되었는가"를 이루므로, 판정에 쓰인 값과 다른 것을 적으면 안 된다.
 *
 * IMPORTANT — 상수에는 **단위를 붙인다.** 붙이지 않으면 선정 이유가 "ROE 8.00 이상 / 실제
 * 11.34%"로 좌우 단위가 어긋나 기준과 실제값이 같은 척도인지 알 수 없다. 칩용
 * {@link summarizeDraftCondition}은 처음부터 붙이고 있었고 이쪽만 빠져 있었다.
 * 지표 참조에는 붙이지 않는다 — "200일 이동평균 × 0.90원"은 뜻이 없다.
 */
export function describeCondition(
  condition: ConditionInfo,
  indicatorName: (code: string) => string,
): string {
  const left = condition.indicator.name
  const scale = condition.indicator.decimalScale
  const unit = condition.indicator.unit
  const operand = condition.operand

  if (condition.operator === 'BETWEEN') {
    // 단위는 뒤에 한 번만. "45.00% ~ 70.00%"는 같은 말을 두 번 한다.
    const lower = formatNumber(operand.value ?? null, scale)
    const upper = formatIndicatorValue(operand.upperBound ?? null, unit, scale)
    return `${left} ${lower} ~ ${upper} ${condition.operatorLabel}`
  }

  return `${left} ${describeOperand(operand, scale, unit, indicatorName)} ${condition.operatorLabel}`
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
    if (condition.operandIndicatorCode === null) {
      return indicator.name
    }
    const name = operandIndicator?.name ?? condition.operandIndicatorCode
    const multiplier = condition.multiplier
    // 배수 1은 적지 않는다. "SMA_200 × 1"은 정보를 더하지 않고 칩만 길어진다.
    const right =
      multiplier === null || multiplier === 1 ? name : `${name} × ${formatEnteredNumber(multiplier, 2)}`
    return `${indicator.name} ${right}${tail}`
  }

  // 값을 아직 안 넣었으면 **지표 이름만** 낸다. `MISSING`(—)을 그대로 끼우면
  // "RSI(14) — 이상"이 되어 마치 조건이 다 갖춰진 문장처럼 읽힌다. 무엇이 비었는지는
  // 칩의 "값 필요" 배지가 말한다.
  if (condition.value === null) {
    return indicator.name
  }

  if (condition.operator === 'BETWEEN') {
    const lower = formatEnteredNumber(condition.value, scale)
    // 상한만 비어 있으면 하한까지는 보인다. 어디까지 넣었는지가 드러나야 한다.
    const upper = condition.upperBound === null ? '?' : formatEnteredNumber(condition.upperBound, scale)
    return `${indicator.name} ${lower}~${upper}${unit}${tail}`
  }

  return `${indicator.name} ${formatEnteredNumber(condition.value, scale)}${unit}${tail}`
}

/**
 * 지표를 아직 고르지 않았을 때 그 자리에 적는 말.
 *
 * 칩의 라벨 폴백({@link summarizeDraftCondition}이 null을 낼 때)과 편집기의 지표 Select
 * placeholder가 **같은 상태를 가리킨다.** 두 곳에 따로 적혀 있어서, 한쪽만 고치면 같은
 * "아직 안 골랐다"가 화면 안에서 두 가지 말로 나온다.
 */
export const PICK_INDICATOR = '지표 선택'

/** 지표 검색 입력의 안내. 편집기의 Select 필터와 지표 피커가 같은 것을 한다. */
export const SEARCH_INDICATOR = '지표 검색'

/**
 * 조건이 전략 안에서 하는 일. **점수 방식에서만 갈린다** — 거르기에서는 모든 조건이 이미 필수다.
 *
 * 서버의 `required` 불리언을 화면에서는 두 값의 선택으로 다룬다. 필수와 가중치가 배타이기
 * 때문이다(`ck_cond_required_weight`) — 체크박스와 가중치 입력칸을 나란히 두면 사용자가 둘 다
 * 채워 놓고 400을 맞는 조합을 만들 수 있고, 그 오류는 저장 버튼을 누를 때에야 나온다.
 *
 * 편집기·칩·결과의 조건 내역이 같은 말을 그리므로 여기 모은다(frontend.md의 (b) 조건).
 */
export type ConditionRole = 'SCORED' | 'REQUIRED'

export const conditionRoleLabels: Record<ConditionRole, string> = {
  SCORED: '점수',
  REQUIRED: '필수',
}

export const conditionRoleHints: Record<ConditionRole, string> = {
  SCORED: '충족하면 가중치만큼 점수를 얻습니다. 못 채워도 결과에는 남습니다.',
  REQUIRED: '충족하지 못한 종목은 결과에서 빠집니다. 점수에는 참여하지 않습니다.',
}

/** 고르는 순서. 손으로 적은 배열은 값을 하나 빠뜨려도 드러나지 않는다(frontend.md). */
export const CONDITION_ROLES: { label: string; value: ConditionRole }[] = (
  ['SCORED', 'REQUIRED'] as const
).map((value) => ({ label: conditionRoleLabels[value], value }))

/** 서버가 준 `required`를 화면의 두 값으로 옮긴다. 반대 방향은 `role === 'REQUIRED'`다. */
export function conditionRoleOf(required: boolean): ConditionRole {
  return required ? 'REQUIRED' : 'SCORED'
}

/**
 * 값을 채우지 않은 조건이 실행에서 빠진다는 안내.
 *
 * IMPORTANT — 칩 바 아래 한 줄과 실행 버튼 툴팁이 짝이다(ADR-0024). 짝인데 문구가 두 곳에
 * 따로 적혀 있었고 **이미 어긋나 있었다** — "실행에서 제외됩니다"와 "제외하고 실행합니다".
 * 변형을 두지 않고 한 문장으로 합친다. 두 자리가 요구하는 것이 같기 때문이다.
 *
 * @return 빠질 것이 없으면 null. 호출부가 그대로 `v-if`·툴팁 바인딩에 쓴다.
 */
export function incompleteConditionNotice(count: number): string | null {
  return count > 0 ? `값을 채우지 않은 조건 ${count}개는 실행에서 제외됩니다.` : null
}

/**
 * 사용자가 **일부러** 뺀 조건이 있다는 안내.
 *
 * {@link incompleteConditionNotice}와 짝을 이루지만 합치지 않는다. 저쪽은 "덜 만든 것"이고
 * 이쪽은 "일부러 뺀 것"이라 사용자가 해야 할 일이 정반대다 — 하나는 채우는 것, 하나는
 * 아무것도 안 해도 되는 것. 개수를 합쳐 세면 실수와 의도가 같은 숫자에 섞인다.
 *
 * 그래도 말하기는 해야 한다. 제외 상태는 저장되지 않고 화면을 새로 고치면 사라지므로,
 * 말하지 않으면 결과가 왜 달라졌는지 알 수 없는 순간이 온다(ADR-0024가 미완성 조건을
 * 조용히 버리지 않기로 한 것과 같은 이유다).
 *
 * @return 뺀 것이 없으면 null. 호출부가 그대로 `v-if`·툴팁 바인딩에 쓴다.
 */
export function disabledConditionNotice(count: number): string | null {
  return count > 0 ? `실행에서 뺀 조건이 ${count}개 있습니다.` : null
}

function describeOperand(
  operand: OperandPayload,
  decimalScale: number,
  unit: string | null,
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
  // 값이 없으면 단위도 붙지 않는다 — "—%"는 없는 값에 단위를 붙인 것이라 거짓이다.
  return formatIndicatorValue(operand.value ?? null, unit, decimalScale)
}
