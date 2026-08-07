import { defineStore } from 'pinia'
import { computed, ref } from 'vue'

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

/** 서버의 `ScreenDefinitionValidator.MAX_CONDITIONS`와 같은 값이다. */
export const MAX_CONDITIONS = 20

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

let nextKey = 1

function emptyCondition(): DraftCondition {
  return {
    key: nextKey++,
    indicatorCode: null,
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
  const conditions = ref<DraftCondition[]>([emptyCondition()])
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
   * 유니버스와 기준일은 dirty에 넣지 않는다. 저장된 정의를 고치는 것이 아니라 실행 시점의
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

  const title = computed(() => (origin.value.kind === 'custom' ? '새 조건' : origin.value.name))
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
   * 손대지 않은 프리셋·저장된 화면은 조건을 다시 보내지 않으므로(서버가 저장된 정의를 쓴다)
   * 여기서 조건을 세지 않는다. 즉석 조건일 때만 완성된 행이 하나 이상 필요하다.
   */
  const canRun = computed(
    () => (!dirty.value && origin.value.kind !== 'custom') || completeConditions.value.length > 0,
  )

  function markDirty(): void {
    dirty.value = true
  }

  function addCondition(): void {
    if (!canAddCondition.value) {
      return
    }
    conditions.value.push(emptyCondition())
    markDirty()
  }

  function removeCondition(key: number): void {
    conditions.value = conditions.value.filter((it) => it.key !== key)
    if (conditions.value.length === 0) {
      conditions.value.push(emptyCondition())
    }
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
    conditions.value = [emptyCondition()]
    evaluationMode.value = 'FILTER'
    editable.value = true
    dirty.value = false
  }

  /** 저장된 화면·프리셋을 편집기로 불러온다. 유니버스·기준일 선택은 유지한다. */
  function loadFrom(screen: ScreenResponse): void {
    // `type`은 **실행 형태**를 정한다 — 프리셋이면 presetCode로, 내 화면이면 screenId로
    // 보내야 서버가 저장된 정의를 그대로 실행한다. 편집 권한과는 다른 질문이다.
    origin.value =
      screen.type === 'PRESET' && screen.code !== null
        ? { kind: 'preset', code: screen.code, name: screen.name }
        : { kind: 'screen', id: screen.id ?? 0, name: screen.name }

    editable.value = screen.editable
    evaluationMode.value = screen.evaluationMode
    universeCode.value = screen.universeCode
    conditions.value = (screen.conditions?.conditions ?? []).map(fromPayload)
    if (conditions.value.length === 0) {
      conditions.value = [emptyCondition()]
    }
    dirty.value = false
  }

  /**
   * 프리셋을 내 것으로 복제한다.
   *
   * 프리셋은 읽기 전용이라 고칠 수 없다(서버가 403을 준다). 복제하면 같은 조건에서 출발해
   * 자기 화면으로 저장할 수 있다 — 그렇지 않으면 사용자는 프리셋을 손으로 다시 옮겨 적는다.
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
    markDirty,
    addCondition,
    removeCondition,
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
