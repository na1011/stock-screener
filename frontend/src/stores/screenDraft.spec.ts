import { createPinia, setActivePinia } from 'pinia'
import { beforeEach, describe, expect, it } from 'vitest'

import type { ScreenResponse } from '@/api/types'

import { isComplete, useScreenDraftStore } from './screenDraft'
import type { DraftCondition } from './screenDraft'

/**
 * 편집기 상태의 규칙.
 *
 * Pinia는 DOM이 필요 없어 `environment: 'node'`에서 그대로 돈다 — 이 파일은 새 의존성을
 * 들이지 않는다(ADR-0019가 그은 선이 바로 그것이다).
 */
function screen(overrides: Partial<ScreenResponse> = {}): ScreenResponse {
  return {
    id: 7,
    code: null,
    name: '내 전략',
    description: null,
    type: 'CUSTOM',
    evaluationMode: 'FILTER',
    universeCode: null,
    active: true,
    editable: true,
    conditions: {
      conditions: [
        {
          indicatorCode: 'PER',
          operator: 'LT',
          operand: { type: 'LITERAL', value: 10 },
        },
      ],
    },
    ...overrides,
  }
}

const readOnlyPreset = () =>
  screen({ id: 1, code: 'VALUE_KR', name: '가치주', type: 'PRESET', editable: false })

function condition(overrides: Partial<DraftCondition> = {}): DraftCondition {
  return {
    key: 1,
    indicatorCode: 'PER',
    operator: 'LT',
    operandType: 'LITERAL',
    value: 10,
    upperBound: null,
    operandIndicatorCode: null,
    multiplier: null,
    weight: null,
    ...overrides,
  }
}

beforeEach(() => {
  setActivePinia(createPinia())
})

describe('편집 가능 여부는 서버 판단을 그대로 쓴다', () => {
  it('서버가 편집 불가라고 한 전략은 잠긴다', () => {
    const draft = useScreenDraftStore()

    draft.loadFrom(readOnlyPreset())

    expect(draft.editable).toBe(false)
    expect(draft.readOnly).toBe(true)
  })

  it('서버가 편집 가능이라고 하면 프리셋이어도 잠그지 않는다', () => {
    // IMPORTANT — 이것이 이 테스트 파일의 핵심이다. 예전에는 `origin.kind === 'preset'`으로
    // 잠갔기 때문에 서버가 규칙을 바꿔도(관리자는 프리셋을 고칠 수 있게) 화면이 따라오지
    // 못하고 서버와 모순된 상태가 됐다. ScreenResponse.editable은 바로 그것을 막으려고
    // 서버가 계산해 주는 값이다.
    const draft = useScreenDraftStore()

    draft.loadFrom(screen({ code: 'VALUE_KR', type: 'PRESET', editable: true }))

    expect(draft.readOnly).toBe(false)
    // 출처는 여전히 프리셋이다 — 표시와 실행 형태는 그쪽을 따른다.
    expect(draft.isPreset).toBe(true)
  })

  it('복제하면 내 것이 되어 잠금이 풀린다', () => {
    const draft = useScreenDraftStore()
    draft.loadFrom(readOnlyPreset())

    draft.forkAsCustom()

    expect(draft.editable).toBe(true)
    expect(draft.readOnly).toBe(false)
    expect(draft.origin.kind).toBe('custom')
  })

  it('새로 시작하면 언제나 편집할 수 있다', () => {
    const draft = useScreenDraftStore()
    draft.loadFrom(readOnlyPreset())

    draft.reset()

    expect(draft.editable).toBe(true)
    expect(draft.readOnly).toBe(false)
  })
})

describe('실행 요청의 형태는 출처와 dirty가 정한다', () => {
  it('손대지 않은 프리셋은 presetCode로 보낸다', () => {
    // 저장된 정의를 서버가 그대로 실행해야 Phase 6의 인터랙션 로깅이 "무엇을 실행했는지"를 안다.
    const draft = useScreenDraftStore()
    draft.loadFrom(readOnlyPreset())

    const request = draft.toRequest()

    expect(request?.presetCode).toBe('VALUE_KR')
    expect(request?.conditions).toBeUndefined()
  })

  it('손대지 않은 내 전략은 screenId로 보낸다', () => {
    const draft = useScreenDraftStore()
    draft.loadFrom(screen())

    expect(draft.toRequest()?.screenId).toBe(7)
  })

  it('손대면 즉석 조건으로 보낸다', () => {
    const draft = useScreenDraftStore()
    draft.loadFrom(screen())

    draft.markDirty()
    const request = draft.toRequest()

    expect(request?.screenId).toBeUndefined()
    expect(request?.conditions?.conditions).toHaveLength(1)
    expect(request?.evaluationMode).toBe('FILTER')
  })

  it('완성된 조건이 없는 즉석 조건은 실행 요청을 만들지 않는다', () => {
    // 빈 조건을 보내면 서버가 400을 주는데, 그 오류는 사용자 잘못이 아니다.
    const draft = useScreenDraftStore()

    expect(draft.toRequest()).toBeNull()
  })
})

describe('isComplete', () => {
  it('지표를 고르지 않은 행은 실행 대상이 아니다', () => {
    expect(isComplete(condition({ indicatorCode: null }))).toBe(false)
  })

  it('BETWEEN은 상한이 있어야 완성이다', () => {
    expect(isComplete(condition({ operator: 'BETWEEN' }))).toBe(false)
    expect(isComplete(condition({ operator: 'BETWEEN', upperBound: 20 }))).toBe(true)
  })

  it('지표를 비교 대상으로 쓰면 BETWEEN은 성립하지 않는다', () => {
    // 지표 하나로는 구간의 양 끝을 만들 수 없다.
    expect(
      isComplete(
        condition({ operandType: 'INDICATOR', operandIndicatorCode: 'SMA_20', operator: 'BETWEEN' }),
      ),
    ).toBe(false)
    expect(
      isComplete(
        condition({ operandType: 'INDICATOR', operandIndicatorCode: 'SMA_20', operator: 'GT' }),
      ),
    ).toBe(true)
  })
})
