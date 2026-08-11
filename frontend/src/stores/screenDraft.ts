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
  /**
   * SCORING에서 미충족 종목을 결과에서 뺀다.
   *
   * `weight`와 배타다 — 서버의 `ck_cond_required_weight`가 같은 불변식을 강제하므로,
   * 한쪽을 켜면 다른 쪽을 비워야 400을 맞지 않는다. 그 처리는 편집기가 한다.
   */
  required: boolean
  /**
   * 이번 실행에 쓸 것인가. **화면에서만 아는 상태다**(ADR-0048).
   *
   * "이 조건 하나만 빼고 돌려보기"를 위한 것이다. 지우고 다시 만들면 값을 전부 다시 넣어야
   * 하고, `undoRemove`는 마지막 하나만 되살린다.
   *
   * IMPORTANT — 서버는 이 개념을 모른다. 저장할 때는 **꺼진 조건도 함께 보낸다** — 실행에서
   * 빼는 것과 전략에서 지우는 것은 다른 일이고, 후자는 휴지통이 한다. 그래서 저장한 뒤 다시
   * 열면 전부 켜져 있다(`fromPayload`). 그것이 "세션 한정"의 뜻이다.
   */
  enabled: boolean
}

/** 지금 편집 중인 것이 어디서 왔는가. 실행 요청의 형태가 여기서 갈린다. */
export type DraftOrigin =
  | { kind: 'custom' }
  | { kind: 'preset'; code: string; name: string }
  | { kind: 'screen'; id: number; name: string }

/**
 * 서버에 **실제로 보낸 것**.
 *
 * IMPORTANT — 이것이 없어서 화면이 두 가지 거짓말을 하고 있었다.
 *
 * 1. `toConditionGroup()`이 미완성 조건을 걸러 보내는데 칩 바는 `draft.conditions`의 자리로
 *    `conditionIndex`를 되짚었다. `[A(미완성), B, C]`에서 C가 거부되면 **B가 붉게** 칠해졌다.
 * 2. `dirty`를 내리는 곳이 `reset`·`loadFrom`뿐이고 실행은 그것을 건드리지 않아, 즉석 조건으로
 *    **성공한 직후에도** "아래는 이전 실행 결과입니다"가 방금 받은 결과 위에 떴다.
 *
 * 둘의 뿌리가 같다 — "무엇을 실행했는가"를 기록하지 않고 "지금 상태"로 되짚은 것이다.
 */
export interface SentSnapshot {
  request: ScreeningRequest
  /**
   * 보낸 순서대로의 조건 `key`. 서버의 `conditionIndex`가 이 배열의 자리를 가리킨다.
   *
   * 저장된 정의를 실행할 때(`presetCode`·`screenId`)는 서버가 `ORDER BY sort_order, id`로
   * 읽으므로 지금 편집기에 있는 순서와 같다 — 그때는 전부를 순서대로 담는다.
   */
  keys: number[]
}

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

/**
 * 조건 하나의 **내용** 기본값. 신원(`key`)과 표시 상태(`enabled`)는 여기 없다.
 *
 * IMPORTANT — 반환 타입의 `Omit`이 장식이 아니다. 이 객체는 편집기의 [초기화]가
 * `Object.assign`으로 기존 조건에 **덮어쓰는** 데 쓰이므로, 여기 들어간 것은 무엇이든
 * 초기화 때 함께 되돌아간다. `key`가 바뀌면 v-for가 재마운트되어 팝오버가 앵커를 잃고,
 * `enabled`가 딸려 오면 값을 비우는 동작이 **제외까지 조용히 풀어 버린다.** "값을 비우기"이지
 * 신원을 바꾸거나 표시를 되돌리는 것이 아니다 — 둘을 여기 더하면 컴파일이 막는다.
 */
function conditionDefaults(
  indicatorCode: string | null = null,
): Omit<DraftCondition, 'key' | 'enabled'> {
  return {
    indicatorCode,
    operator: 'GTE',
    operandType: 'LITERAL',
    value: null,
    upperBound: null,
    operandIndicatorCode: null,
    multiplier: null,
    weight: null,
    required: false,
  }
}

/**
 * `key`와 `enabled`가 `conditionDefaults` **밖**에 있는 것은 의도다. 편집기의 [초기화]가
 * defaults를 `Object.assign`으로 덮어쓰므로, 여기 있는 둘은 초기화가 건드리지 않는다 —
 * "값을 비우기"이지 신원을 바꾸거나 제외를 푸는 것이 아니다.
 */
function emptyCondition(indicatorCode: string | null = null): DraftCondition {
  return { key: nextKey++, enabled: true, ...conditionDefaults(indicatorCode) }
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

  /**
   * 완성된 조건만 센다. 작성 중인 빈 행은 실행 대상이 아니다.
   *
   * **제외한 조건도 여기 들어간다.** 이것이 저장의 단위이기 때문이다 — 제외는 이번 실행에서만
   * 빼는 것이지 전략에서 지우는 것이 아니다.
   */
  const completeConditions = computed(() => conditions.value.filter(isComplete))

  /** 실제로 서버에 보낼 조건. 완성됐고 **켜져 있는** 것. */
  const runnableConditions = computed(() => completeConditions.value.filter((it) => it.enabled))

  /**
   * 실행에서 뺀 조건이 있는가.
   *
   * IMPORTANT — 이것이 `dirty`와 **별개**인 것이 이 기능의 핵심이다. 제외에 `markDirty()`를
   * 부르면 `readOnly = !editable && !dirty`가 풀려 프리셋의 칩 바 잠금이 열리고 "복제해서
   * 편집" 버튼이 사라지는데, `canSave`는 `editable`을 보므로 여전히 false다 — **고칠 수는
   * 있는데 저장할 길이 없는 상태**가 된다. 그리고 "이 조건 빼고 돌려보기"는 프리셋에서 가장
   * 쓰고 싶은 기능이다.
   *
   * 실제로 필요한 것은 dirty가 아니라 **즉석 조건 경로를 타는 것**이다. 그것만 여기서 연다.
   */
  const hasDisabledCondition = computed(
    () => completeConditions.value.length !== runnableConditions.value.length,
  )

  /**
   * 실행할 수 있는가.
   *
   * 손대지 않은 프리셋·저장된 전략은 조건을 다시 보내지 않으므로(서버가 저장된 정의를 쓴다)
   * 여기서 조건을 세지 않는다. 즉석 조건일 때만 켜진 행이 하나 이상 필요하다.
   */
  const canRun = computed(
    () =>
      (!dirty.value && !hasDisabledCondition.value && origin.value.kind !== 'custom') ||
      runnableConditions.value.length > 0,
  )

  /**
   * 조건 하나를 실행에서 빼거나 되돌린다.
   *
   * `markDirty()`를 **부르지 않는다** — 이유는 {@link hasDisabledCondition}에 있다.
   */
  function setEnabled(key: number, enabled: boolean): void {
    const target = conditions.value.find((it) => it.key === key)
    if (target !== undefined) {
      target.enabled = enabled
    }
  }

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

  /**
   * 지표만 남기고 값을 비운다. 편집기의 [초기화]다.
   *
   * `key`를 보존하는 것이 요점이다. 새 조건으로 갈아끼우면 v-for가 다시 그리고, 그러면
   * **지금 열려 있는 팝오버가 자기 앵커를 잃는다** — 사용자는 초기화를 누른 순간 팝오버가
   * 사라지는 것을 본다.
   */
  function resetCondition(key: number): void {
    const target = conditions.value.find((it) => it.key === key)
    if (target === undefined) {
      return
    }
    Object.assign(target, conditionDefaults(target.indicatorCode))
    // 없으면 `toRequest()`가 screenId·presetCode만 보내 초기화가 실행에 반영되지 않는다.
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

  /**
   * @param list 무엇을 담을지. 기본은 완성된 조건 전부다 — 저장이 그 형태다.
   *   인자를 받는 이유는 **보내는 것과 저장하는 것이 같지 않을 수 있어서**이고, 지금 그 차이는
   *   {@link buildRequest}가 정한다. 두 곳이 각자 배열을 만들면 조건 자리가 어긋난다.
   */
  function toConditionGroup(
    list: DraftCondition[] = completeConditions.value,
  ): ConditionGroupPayload {
    return {
      logicalOperator: 'AND',
      conditions: list.map(toPayload),
      childGroups: [],
    }
  }

  /**
   * 지금 실행하면 **무엇을 보내는가**. 부작용이 없다 — 기록은 {@link toRequest}가 한다.
   *
   * 요청과 조건 자리를 **함께** 만드는 것이 요점이다. 둘을 따로 유도하면 한쪽만 필터가
   * 바뀌었을 때 자리가 밀리고, 그 어긋남은 서버가 조건을 거부할 때에만 드러난다.
   */
  function buildRequest(): SentSnapshot | null {
    const base = { universeCode: universeCode.value, asOf: asOf.value }

    // 저장된 정의를 그대로 실행하려면 **뺀 조건이 없어야 한다.** 서버는 제외를 모르므로
    // presetCode·screenId로 보내면 꺼 둔 조건까지 함께 평가된다 — 껐는데 결과가 그대로다.
    const savedAsIs = !dirty.value && !hasDisabledCondition.value
    if (savedAsIs && origin.value.kind === 'preset') {
      return { request: { ...base, presetCode: origin.value.code }, keys: allKeys() }
    }
    if (savedAsIs && origin.value.kind === 'screen') {
      return { request: { ...base, screenId: origin.value.id }, keys: allKeys() }
    }
    const sending = runnableConditions.value
    if (sending.length === 0) {
      return null
    }
    return {
      request: { ...base, conditions: toConditionGroup(sending), evaluationMode: evaluationMode.value },
      keys: sending.map((it) => it.key),
    }
  }

  function allKeys(): number[] {
    return conditions.value.map((it) => it.key)
  }

  /** 마지막으로 실행한 것. 결과가 지금 조건과 어긋났는지 판정하는 기준이다. */
  const lastSent = ref<SentSnapshot | null>(null)

  /**
   * 지금 실행하면 지난번과 **같은 것**을 보내는가. 같으면 눈앞의 결과는 낡지 않았다.
   *
   * IMPORTANT — 이 판정을 `dirty`로 하지 않는다. `dirty`는 "저장본과 다른가"이고 실행으로
   * 내려가지 않으므로, 즉석 조건은 실행에 성공해도 영원히 true다. 두 질문을 한 깃발이
   * 겸하던 동안 화면은 방금 받은 결과를 "이전 실행 결과"라고 불렀다.
   */
  const matchesLastSent = computed(() => {
    const sent = lastSent.value
    if (sent === null) {
      return false
    }
    const now = buildRequest()
    return now !== null && JSON.stringify(now.request) === JSON.stringify(sent.request)
  })

  /**
   * 실행 요청을 만들고 **보낸 것으로 기록한다.**
   *
   * @return 조건이 하나도 완성되지 않은 즉석 조건이면 null. 그때는 실행하지 않는다 —
   *         빈 조건을 보내면 서버가 400을 주고, 그 오류는 사용자 잘못이 아니다.
   *         보내지 않았으므로 기록도 남기지 않는다 — 덮으면 낡은 결과 위의 자백이 사라진다.
   */
  function toRequest(): ScreeningRequest | null {
    const snapshot = buildRequest()
    if (snapshot === null) {
      return null
    }
    lastSent.value = snapshot
    return snapshot.request
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
    runnableConditions,
    hasDisabledCondition,
    canRun,
    lastRemoved,
    lastSent,
    matchesLastSent,
    markDirty,
    addCondition,
    removeCondition,
    resetCondition,
    setEnabled,
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
    // 필수면 가중치를 보내지 않는다. 둘을 함께 보내면 서버가 400으로 거절한다.
    weight: condition.required ? null : condition.weight,
    required: condition.required,
  }
}

function fromPayload(payload: ConditionPayload): DraftCondition {
  return {
    key: nextKey++,
    // 서버는 제외를 모른다. 불러오면 언제나 전부 켜진 상태에서 시작한다(ADR-0048).
    enabled: true,
    indicatorCode: payload.indicatorCode,
    operator: payload.operator,
    operandType: payload.operand.type,
    value: payload.operand.value ?? null,
    upperBound: payload.operand.upperBound ?? null,
    operandIndicatorCode: payload.operand.indicatorCode ?? null,
    multiplier: payload.operand.multiplier ?? null,
    weight: payload.weight ?? null,
    required: payload.required ?? false,
  }
}
