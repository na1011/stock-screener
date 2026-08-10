import { defineStore } from 'pinia'
import { computed, ref } from 'vue'

import { MAX_CONDITIONS } from '@/api/limits'
import type {
  ComparisonOperatorName,
  ConditionGroupPayload,
  ConditionPayload,
  EvaluationMode,
  IsoDate,
  OperandType,
  ScreenResponse,
  ScreeningRequest,
} from '@/api/types'

/**
 * 편집 중인 조건 하나.
 *
 * 서버의 `ConditionPayload`를 그대로 쓰지 않는 이유는 **작성 중인 상태**를 담아야 하기
 * 때문이다. 지표를 아직 고르지 않았거나 값을 비운 행이 존재할 수 있고, 그것을 그대로
 * 보내면 400이 난다. 보내기 직전에 {@link toPayload}가 완성된 것만 옮긴다.
 */
export interface DraftCondition {
  /** v-for 키. 지표 코드를 키로 쓰면 같은 지표를 두 번 쓸 때 행이 뒤섞인다. */
  key: number
  indicatorCode: string | null
  operator: ComparisonOperatorName
  operandType: OperandType
  value: number | null
  upperBound: number | null
  operandIndicatorCode: string | null
  multiplier: number | null
  weight: number | null
}

/** 지금 편집 중인 것이 어디서 왔는가. 실행 요청의 형태가 여기서 갈린다. */
export type DraftOrigin =
  | { kind: 'custom' }
  | { kind: 'preset'; code: string; name: string }
  | { kind: 'screen'; id: number; name: string }

/**
 * 아무 전략도 열지 않은 상태를 부르는 말.
 *
 * 여기가 정본인 이유는 {@link useScreenDraftStore}의 `title`이 이미 이 말을 쓰고 있기
 * 때문이다. 그런데 레일과 칩 바가 **같은 상태로 되돌리는 버튼**에 각자 문구를 적어 두어,
 * 버튼과 그 버튼이 만드는 제목이 어긋날 수 있었다.
 *
 * IMPORTANT — "새 조건"이었던 것을 "새 전략"으로 바꿨다(ADR-0027). **"조건"은 칩 하나를
 * 가리키는 말로 이미 쓰이고 있어서**(칩 바의 `＋ 조건`, `MAX_CONDITIONS`) 같은 낱말이 두
 * 층위를 가리켰고, 사용자가 이 버튼을 "프리셋을 추가하는 것"으로 읽었다. 되돌리지 않는다.
 */
export const FRESH_DRAFT_TITLE = '새 전략'

let nextKey = 1

function emptyCondition(indicatorCode: string | null = null): DraftCondition {
  return {
    key: nextKey++,
    indicatorCode,
    operator: 'GTE',
    operandType: 'LITERAL',
    value: null,
    upperBound: null,
    operandIndicatorCode: null,
    multiplier: null,
    weight: null,
  }
}

export const useScreenDraftStore = defineStore('screenDraft', () => {
  const origin = ref<DraftOrigin>({ kind: 'custom' })
  /**
   * 빈 목록으로 시작한다.
   *
   * 예전에는 빈 조건 하나를 미리 넣어 두었다. 세로 행 편집기에서는 "여기에 채우세요"라는
   * 안내였지만, 칩 바에서는 지표조차 없는 칩이 하나 떠 있는 것이 되어 무엇을 눌러야 할지
   * 오히려 헷갈린다. 비어 있으면 `+ 조건` 하나만 보이고 그것이 유일한 다음 동작이다.
   */
  const conditions = ref<DraftCondition[]>([])
  const evaluationMode = ref<EvaluationMode>('FILTER')
  const universeCode = ref<string | null>(null)
  const asOf = ref<IsoDate | null>(null)

  /**
   * 불러온 정의에 손을 댔는가.
   *
   * IMPORTANT — 실행 요청이 이것으로 갈린다. 손대지 않았으면 `screenId`·`presetCode`로 보내
   * 서버가 저장된 정의를 그대로 실행하게 하고(그래야 Phase 6의 인터랙션 로깅이 "무엇을
   * 실행했는지"를 안다), 손댔으면 `conditions`를 즉석 조건으로 보낸다. 셋 중 정확히 하나만
   * 보낼 수 있으므로 둘을 함께 보낼 수는 없다.
   *
   * 조회 대상과 기준일은 dirty에 넣지 않는다. 저장된 정의를 고치는 것이 아니라 실행 시점의
   * 덮어쓰기라서 서버가 별도 필드로 받는다.
   */
  const dirty = ref(false)

  /**
   * 서버가 준 편집 가능 여부.
   *
   * IMPORTANT — 이것을 `origin.kind === 'preset'`으로 대신하지 않는다. `ScreenResponse.editable`은
   * 서버가 인가를 알고 계산해 주는 값이고, 규칙이 바뀌면(예: 관리자는 프리셋을 고칠 수 있게)
   * 서버만 고치면 되도록 만들어 둔 것이다. 프론트가 출처(`type`)로 권한을 다시 유도하면 그
   * 설계가 무의미해지고, 두 판단이 갈리는 날 화면이 서버와 모순된 상태가 된다.
   *
   * 새로 만든 조건과 복제본은 언제나 내 것이므로 true다.
   */
  const editable = ref(true)

  const title = computed(() => (origin.value.kind === 'custom' ? FRESH_DRAFT_TITLE : origin.value.name))
  /** 출처가 프리셋인가. **표시용**이다 — 권한 판단은 {@link editable}이 한다. */
  const isPreset = computed(() => origin.value.kind === 'preset')
  /** 편집기를 잠글 것인가. 손을 대면(dirty) 복제본을 만드는 중이므로 잠금이 풀린다. */
  const readOnly = computed(() => !editable.value && !dirty.value)
  const canAddCondition = computed(() => conditions.value.length < MAX_CONDITIONS)

  /** 완성된 조건만 센다. 작성 중인 빈 행은 실행 대상이 아니다. */
  const completeConditions = computed(() => conditions.value.filter(isComplete))

  /**
   * 실행할 수 있는가.
   *
   * 손대지 않은 프리셋·저장된 전략은 조건을 다시 보내지 않으므로(서버가 저장된 정의를 쓴다)
   * 여기서 조건을 세지 않는다. 즉석 조건일 때만 완성된 행이 하나 이상 필요하다.
   */
  const canRun = computed(
    () => (!dirty.value && origin.value.kind !== 'custom') || completeConditions.value.length > 0,
  )

  function markDirty(): void {
    dirty.value = true
  }

  /**
   * @param indicatorCode 지표를 이미 고른 채로 추가한다. 칩 바는 지표 선택이 먼저이므로
   *                      빈 조건을 만든 뒤 곧바로 지표를 넣는 왕복이 필요 없다.
   */
  function addCondition(indicatorCode: string | null = null): void {
    if (!canAddCondition.value) {
      return
    }
    conditions.value.push(emptyCondition(indicatorCode))
    markDirty()
  }

  /**
   * 방금 지운 조건과 그것이 있던 자리.
   *
   * 삭제에 되돌리기가 없었다. 조건 하나를 잘못 지우면 지표부터 값까지 다시 넣어야 했고,
   * 그것이 확인 대화상자를 매번 띄우는 것보다 나쁜 이유는 없다 — 그래서 묻지 않고
   * 되돌릴 수 있게 한다. 자리(index)까지 기억하는 이유는 되돌린 조건이 맨 뒤로 가면
   * 사용자가 만든 순서가 조용히 바뀌기 때문이다.
   */
  const lastRemoved = ref<{ condition: DraftCondition; index: number } | null>(null)

  function removeCondition(key: number): void {
    const index = conditions.value.findIndex((it) => it.key === key)
    if (index < 0) {
      return
    }
    lastRemoved.value = { condition: conditions.value[index] as DraftCondition, index }
    conditions.value.splice(index, 1)
    markDirty()
  }

  /** 되돌릴 것이 없으면 아무 일도 하지 않는다. 버퍼는 한 번만 쓴다. */
  function undoRemove(): void {
    const removed = lastRemoved.value
    if (removed === null) {
      return
    }
    conditions.value.splice(Math.min(removed.index, conditions.value.length), 0, removed.condition)
    lastRemoved.value = null
    markDirty()
  }

  function setEvaluationMode(mode: EvaluationMode): void {
    if (evaluationMode.value === mode) {
      return
    }
    evaluationMode.value = mode
    // 판정 방식이 바뀌면 저장된 정의와 달라진다. dirty로 두지 않으면 사용자가 SCORING으로
    // 바꿔도 서버는 저장된 FILTER로 실행하고, 화면과 결과가 어긋난다.
    markDirty()
  }

  /** 처음부터 새로 만든다. */
  function reset(): void {
    origin.value = { kind: 'custom' }
    conditions.value = []
    evaluationMode.value = 'FILTER'
    editable.value = true
    dirty.value = false
    // 새로 시작하면 이전 전략에서 지운 조건을 되돌릴 수 있어서는 안 된다. 지금 편집 중인
    // 것과 무관한 조건이 튀어나온다.
    lastRemoved.value = null
  }

  /** 저장된 전략·프리셋을 편집기로 불러온다. 조회 대상·기준일 선택은 유지한다. */
  function loadFrom(screen: ScreenResponse): void {
    // `type`은 **실행 형태**를 정한다 — 프리셋이면 presetCode로, 내 전략이면 screenId로
    // 보내야 서버가 저장된 정의를 그대로 실행한다. 편집 권한과는 다른 질문이다.
    origin.value =
      screen.type === 'PRESET' && screen.code !== null
        ? { kind: 'preset', code: screen.code, name: screen.name }
        : { kind: 'screen', id: screen.id ?? 0, name: screen.name }

    editable.value = screen.editable
    evaluationMode.value = screen.evaluationMode
    universeCode.value = screen.universeCode
    conditions.value = (screen.conditions?.conditions ?? []).map(fromPayload)
    dirty.value = false
    lastRemoved.value = null
  }

  /**
   * 프리셋을 내 것으로 복제한다.
   *
   * 프리셋은 읽기 전용이라 고칠 수 없다(서버가 403을 준다). 복제하면 같은 조건에서 출발해
   * 자기 전략으로 저장할 수 있다 — 그렇지 않으면 사용자는 프리셋을 손으로 다시 옮겨 적는다.
   */
  function forkAsCustom(): void {
    origin.value = { kind: 'custom' }
    editable.value = true
    dirty.value = true
  }

  function toConditionGroup(): ConditionGroupPayload {
    return {
      logicalOperator: 'AND',
      conditions: completeConditions.value.map(toPayload),
      childGroups: [],
    }
  }

  /**
   * 실행 요청을 만든다.
   *
   * @return 조건이 하나도 완성되지 않은 즉석 조건이면 null. 그때는 실행하지 않는다 —
   *         빈 조건을 보내면 서버가 400을 주고, 그 오류는 사용자 잘못이 아니다.
   */
  function toRequest(): ScreeningRequest | null {
    const base = { universeCode: universeCode.value, asOf: asOf.value }

    if (!dirty.value && origin.value.kind === 'preset') {
      return { ...base, presetCode: origin.value.code }
    }
    if (!dirty.value && origin.value.kind === 'screen') {
      return { ...base, screenId: origin.value.id }
    }
    if (completeConditions.value.length === 0) {
      return null
    }
    return { ...base, conditions: toConditionGroup(), evaluationMode: evaluationMode.value }
  }

  return {
    origin,
    conditions,
    evaluationMode,
    universeCode,
    asOf,
    dirty,
    editable,
    title,
    isPreset,
    readOnly,
    canAddCondition,
    completeConditions,
    canRun,
    lastRemoved,
    markDirty,
    addCondition,
    removeCondition,
    undoRemove,
    setEvaluationMode,
    reset,
    loadFrom,
    forkAsCustom,
    toConditionGroup,
    toRequest,
  }
})

/** 실행·저장에 쓸 수 있을 만큼 채워졌는가. */
export function isComplete(condition: DraftCondition): boolean {
  if (condition.indicatorCode === null) {
    return false
  }
  if (condition.operandType === 'INDICATOR') {
    return condition.operandIndicatorCode !== null && condition.operator !== 'BETWEEN'
  }
  if (condition.value === null) {
    return false
  }
  return condition.operator !== 'BETWEEN' || condition.upperBound !== null
}

function toPayload(condition: DraftCondition): ConditionPayload {
  const operand =
    condition.operandType === 'INDICATOR'
      ? {
          type: 'INDICATOR' as const,
          indicatorCode: condition.operandIndicatorCode,
          multiplier: condition.multiplier,
        }
      : {
          type: 'LITERAL' as const,
          value: condition.value,
          upperBound: condition.operator === 'BETWEEN' ? condition.upperBound : null,
        }

  return {
    // isComplete를 통과한 것만 여기 온다.
    indicatorCode: condition.indicatorCode as string,
    operator: condition.operator,
    operand,
    weight: condition.weight,
  }
}

function fromPayload(payload: ConditionPayload): DraftCondition {
  return {
    key: nextKey++,
    indicatorCode: payload.indicatorCode,
    operator: payload.operator,
    operandType: payload.operand.type,
    value: payload.operand.value ?? null,
    upperBound: payload.operand.upperBound ?? null,
    operandIndicatorCode: payload.operand.indicatorCode ?? null,
    multiplier: payload.operand.multiplier ?? null,
    weight: payload.weight ?? null,
  }
}
