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
  screen({ id: 1, code: 'VALUE', name: '가치주', type: 'PRESET', editable: false })

function condition(overrides: Partial<DraftCondition> = {}): DraftCondition {
  return {
    key: 1,
    indicatorCode: 'PER',
    operator: 'LT',
    operandType: 'LITERAL',
    value: 10,
    upperBound: null,
    thresholdCurrency: null,
    thresholdsByCurrency: null,
    operandIndicatorCode: null,
    multiplier: null,
    weight: null,
    required: false,
    enabled: true,
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

    draft.loadFrom(screen({ code: 'VALUE', type: 'PRESET', editable: true }))

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

    expect(request?.presetCode).toBe('VALUE')
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

describe('보낸 것을 기록한다', () => {
  it('미완성 조건이 앞에 있어도 자리가 밀리지 않는다', () => {
    // IMPORTANT — 이것이 이 스냅샷의 존재 이유다. 요청에는 완성된 조건만 실리는데
    // (`toRequest`) 칩 바는 미완성까지 그리므로, 서버가 "두 번째 조건이 잘못됐다"고 하면
    // 화면은 **미완성 칩을 포함한 두 번째**를 붉게 칠했다. 멀쩡한 조건을 고치라고 한 것이다.
    const draft = useScreenDraftStore()
    draft.addCondition('PER') // 미완성 — 값이 없다
    draft.addCondition('ROE')
    draft.addCondition('PBR')
    const incomplete = draft.conditions[0] as DraftCondition
    const roe = draft.conditions[1] as DraftCondition
    const pbr = draft.conditions[2] as DraftCondition
    roe.value = 10
    pbr.value = 1

    draft.toRequest()

    // 보낸 것은 둘뿐이고, 서버가 말하는 자리 1은 PBR이다.
    expect(draft.lastSent?.keys).toEqual([roe.key, pbr.key])
    expect(draft.lastSent?.keys).not.toContain(incomplete.key)
  })

  it('저장된 정의를 그대로 실행하면 전부를 순서대로 담는다', () => {
    // 서버가 `ORDER BY sort_order, id`로 읽으므로 자리는 편집기의 순서와 같다.
    const draft = useScreenDraftStore()
    draft.loadFrom(readOnlyPreset())

    draft.toRequest()

    expect(draft.lastSent?.request.presetCode).toBe('VALUE')
    expect(draft.lastSent?.keys).toEqual(draft.conditions.map((it) => it.key))
  })

  it('실행 직후에는 결과가 낡지 않았다', () => {
    // `dirty`로 판정하던 동안 즉석 조건은 실행에 성공해도 영원히 "고쳐졌다"였다.
    const draft = useScreenDraftStore()
    draft.addCondition('PER')
    ;(draft.conditions[0] as DraftCondition).value = 10

    draft.toRequest()

    expect(draft.matchesLastSent).toBe(true)
  })

  it('실행한 뒤 값을 고치면 어긋난 것으로 잡힌다', () => {
    const draft = useScreenDraftStore()
    draft.addCondition('PER')
    const per = draft.conditions[0] as DraftCondition
    per.value = 10
    draft.toRequest()

    per.value = 20

    expect(draft.matchesLastSent).toBe(false)
  })

  it('보낼 것이 없으면 기록을 덮지 않는다', () => {
    // 조건을 다 지웠다고 기록까지 지우면, 낡은 결과 위에 있어야 할 자백이 사라진다.
    const draft = useScreenDraftStore()
    draft.addCondition('PER')
    const per = draft.conditions[0] as DraftCondition
    per.value = 10
    draft.toRequest()
    const sent = draft.lastSent

    draft.removeCondition(per.key)

    expect(draft.toRequest()).toBeNull()
    expect(draft.lastSent).toBe(sent)
    expect(draft.matchesLastSent).toBe(false)
  })
})

describe('조건을 실행에서 뺀다', () => {
  /** 완성된 조건 둘을 가진 즉석 초안. */
  function twoConditions() {
    const draft = useScreenDraftStore()
    draft.addCondition('PER')
    draft.addCondition('ROE')
    const per = draft.conditions[0] as DraftCondition
    const roe = draft.conditions[1] as DraftCondition
    per.value = 10
    roe.value = 15
    return { draft, per, roe }
  }

  it('뺀 조건은 실행에서 빠지고 저장에는 남는다', () => {
    // 실행에서 빼는 것과 전략에서 지우는 것은 다른 일이다. 후자는 휴지통이 한다.
    const { draft, per } = twoConditions()

    draft.setEnabled(per.key, false)

    expect(draft.runnableConditions).toHaveLength(1)
    expect(draft.completeConditions).toHaveLength(2)
    expect(draft.toConditionGroup().conditions).toHaveLength(2)
    expect(draft.toRequest()?.conditions?.conditions).toHaveLength(1)
  })

  it('프리셋에서 조건을 빼도 읽기 전용이 유지된다', () => {
    // IMPORTANT — 여기가 이 기능의 함정이다. setEnabled가 markDirty를 부르면
    // readOnly(= !editable && !dirty)가 풀려 칩 바 잠금이 열리고 "복제해서 편집" 버튼이
    // 사라지는데, canSave는 editable을 보므로 여전히 false다. 고칠 수는 있는데 저장할
    // 길이 없는 상태가 된다.
    const draft = useScreenDraftStore()
    draft.loadFrom(readOnlyPreset())

    draft.setEnabled((draft.conditions[0] as DraftCondition).key, false)

    expect(draft.dirty).toBe(false)
    expect(draft.readOnly).toBe(true)
    expect(draft.hasDisabledCondition).toBe(true)
  })

  it('뺀 것이 있으면 저장된 정의가 아니라 즉석 조건으로 보낸다', () => {
    // presetCode로 보내면 서버는 제외를 모르므로 꺼 둔 조건까지 평가한다 —
    // 껐는데 결과가 그대로다.
    const draft = useScreenDraftStore()
    draft.loadFrom(readOnlyPreset())
    expect(draft.toRequest()?.presetCode).toBe('VALUE')

    draft.setEnabled((draft.conditions[0] as DraftCondition).key, false)

    const request = draft.toRequest()
    expect(request).toBeNull() // 하나뿐인 조건을 뺐으니 보낼 것이 없다
    expect(draft.canRun).toBe(false)
  })

  it('전부 빼면 실행할 수 없다', () => {
    const { draft, per, roe } = twoConditions()

    draft.setEnabled(per.key, false)
    draft.setEnabled(roe.key, false)

    expect(draft.canRun).toBe(false)
    expect(draft.toRequest()).toBeNull()
  })

  it('실행에서 뺀 것도 "보낸 것과 다르다"로 잡힌다', () => {
    const { draft, per } = twoConditions()
    draft.toRequest()
    expect(draft.matchesLastSent).toBe(true)

    draft.setEnabled(per.key, false)

    expect(draft.matchesLastSent).toBe(false)
  })

  it('저장한 뒤 다시 열면 전부 켜져 있다', () => {
    // 제외는 세션 한정 상태다. 서버는 이 개념을 모르므로 불러오면 초기화된다(ADR-0048).
    const { draft, per } = twoConditions()
    draft.setEnabled(per.key, false)

    draft.loadFrom(screen())

    expect(draft.hasDisabledCondition).toBe(false)
    expect(draft.conditions.every((it) => it.enabled)).toBe(true)
  })

  it('초기화는 제외 상태를 건드리지 않는다', () => {
    // conditionDefaults에 enabled가 들어가면 Object.assign이 그것을 덮어써,
    // 값을 비우는 동작이 제외까지 조용히 풀어 버린다.
    const { draft, per } = twoConditions()
    draft.setEnabled(per.key, false)

    draft.resetCondition(per.key)

    expect(per.enabled).toBe(false)
    expect(per.value).toBeNull()
  })
})

describe('resetCondition — 값만 비운다', () => {
  it('지표는 남기고 나머지를 처음 상태로 되돌린다', () => {
    const draft = useScreenDraftStore()
    draft.addCondition('PER')
    const per = draft.conditions[0] as DraftCondition
    per.operator = 'BETWEEN'
    per.value = 5
    per.upperBound = 15
    per.weight = 3

    draft.resetCondition(per.key)

    expect(per.indicatorCode).toBe('PER')
    expect(per.operator).toBe('GTE')
    expect(per.value).toBeNull()
    expect(per.upperBound).toBeNull()
    expect(per.weight).toBeNull()
    expect(isComplete(per)).toBe(false)
  })

  it('key를 바꾸지 않는다', () => {
    // 바꾸면 v-for가 다시 그리고, 그 순간 열려 있던 팝오버가 앵커를 잃는다 —
    // 사용자는 초기화를 누르자마자 편집기가 사라지는 것을 본다.
    const draft = useScreenDraftStore()
    draft.addCondition('PER')
    const key = (draft.conditions[0] as DraftCondition).key

    draft.resetCondition(key)

    expect((draft.conditions[0] as DraftCondition).key).toBe(key)
  })

  it('초기화도 실행에 반영되어야 하므로 손댄 것으로 친다', () => {
    // dirty가 오르지 않으면 toRequest가 저장된 정의를 그대로 보내고, 비운 값이 무시된다.
    const draft = useScreenDraftStore()
    draft.loadFrom(screen())
    expect(draft.dirty).toBe(false)

    draft.resetCondition((draft.conditions[0] as DraftCondition).key)

    expect(draft.dirty).toBe(true)
  })
})

describe('setIndicator — 지표를 바꾸면 값은 비우고 신원·역할은 남긴다', () => {
  it('값·연산자·피연산자를 비운다 — 단위와 구간이 지표마다 다르다', () => {
    // 부채비율 100(%)을 PER로 끌고 가면 "PER 100배 이하"가 된다. 값도 결과도 그럴듯해
    // 눈으로는 드러나지 않는다.
    const draft = useScreenDraftStore()
    draft.addCondition('DEBT_RATIO')
    const target = draft.conditions[0] as DraftCondition
    target.operator = 'LTE'
    target.value = 100
    target.upperBound = 200
    target.operandType = 'INDICATOR'
    target.operandIndicatorCode = 'PBR'
    target.multiplier = 2

    draft.setIndicator(target.key, 'PER')

    expect(target.indicatorCode).toBe('PER')
    expect(target.operator).toBe('GTE')
    expect(target.value).toBeNull()
    expect(target.upperBound).toBeNull()
    expect(target.operandType).toBe('LITERAL')
    expect(target.operandIndicatorCode).toBeNull()
    expect(target.multiplier).toBeNull()
    expect(isComplete(target)).toBe(false)
  })

  it('IMPORTANT — 역할과 가중치는 남긴다. 지표의 성질이 아니라 전략의 구조다', () => {
    // 되돌리면 SCORING에서 필수로 지정해 둔 조건이 지표만 바꿨는데 조용히
    // "가중치 없는 점수 조건"이 된다.
    const draft = useScreenDraftStore()
    draft.addCondition('DEBT_RATIO')
    const scored = draft.conditions[0] as DraftCondition
    scored.weight = 3
    draft.addCondition('ROE')
    const required = draft.conditions[1] as DraftCondition
    required.required = true

    draft.setIndicator(scored.key, 'PER')
    draft.setIndicator(required.key, 'PBR')

    expect(scored.weight).toBe(3)
    expect(required.required).toBe(true)
  })

  it('key와 제외 상태는 건드리지 않는다', () => {
    // key가 바뀌면 v-for가 다시 그려 열려 있던 팝오버가 앵커를 잃는다. enabled가 딸려 오면
    // 실행에서 빼 둔 조건이 조용히 되살아난다.
    const draft = useScreenDraftStore()
    draft.addCondition('DEBT_RATIO')
    const target = draft.conditions[0] as DraftCondition
    draft.setEnabled(target.key, false)

    draft.setIndicator(target.key, 'PER')

    expect((draft.conditions[0] as DraftCondition).key).toBe(target.key)
    expect(target.enabled).toBe(false)
  })

  it('실행에 반영되어야 하므로 손댄 것으로 친다', () => {
    const draft = useScreenDraftStore()
    draft.loadFrom(screen())
    expect(draft.dirty).toBe(false)

    draft.setIndicator((draft.conditions[0] as DraftCondition).key, 'PBR')

    expect(draft.dirty).toBe(true)
  })

  it('읽기 전용 전략에서는 아무 일도 하지 않는다', () => {
    // 막지 않으면 markDirty가 readOnly를 풀어 "고칠 수는 있는데 저장할 길이 없는 상태"가 된다.
    const draft = useScreenDraftStore()
    draft.loadFrom(screen({ type: 'PRESET', code: 'VALUE', editable: false }))
    const target = draft.conditions[0] as DraftCondition

    draft.setIndicator(target.key, 'PBR')

    expect(target.indicatorCode).toBe('PER')
    expect(draft.dirty).toBe(false)
    expect(draft.readOnly).toBe(true)
  })

  it('같은 지표를 다시 고르면 값을 날리지 않는다', () => {
    const draft = useScreenDraftStore()
    draft.addCondition('PER')
    const target = draft.conditions[0] as DraftCondition
    target.value = 10

    draft.setIndicator(target.key, 'PER')

    expect(target.value).toBe(10)
  })

  it('없는 key는 무시한다', () => {
    const draft = useScreenDraftStore()
    draft.addCondition('PER')

    expect(() => draft.setIndicator(999, 'PBR')).not.toThrow()
    expect((draft.conditions[0] as DraftCondition).indicatorCode).toBe('PER')
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

describe('시장별 임계는 왕복해도 살아 있어야 한다', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
  })

  function marketScoped(): ScreenResponse {
    return screen({
      type: 'PRESET',
      conditions: {
        logicalOperator: 'AND',
        conditions: [
          {
            indicatorCode: 'MARKET_CAP',
            operator: 'GTE',
            operand: {
              type: 'LITERAL',
              value: 1000000000000,
              upperBound: null,
              thresholdCurrency: 'KRW',
              thresholdsByCurrency: {
                KRW: { value: 1000000000000, upperBound: null },
                USD: { value: 6500000000, upperBound: null },
              },
            },
            required: true,
            weight: null,
          },
        ],
        childGroups: [],
      },
    })
  }

  it('복제한 조건을 보낼 때 통화별 임계를 그대로 되돌려 보낸다', () => {
    const draft = useScreenDraftStore()
    draft.loadFrom(marketScoped())
    draft.forkAsCustom()

    const operand = draft.toConditionGroup().conditions[0]!.operand

    // 안 보내면 서버가 시장 중립으로 읽고 원화 값을 미국에 그대로 건다 —
    // 실측으로 버핏 프리셋 복제본이 미국에서 43종목 대신 4종목을 냈다.
    expect(operand.thresholdsByCurrency).toEqual({
      KRW: { value: 1000000000000, upperBound: null },
      USD: { value: 6500000000, upperBound: null },
    })
    expect(operand.thresholdCurrency).toBe('KRW')
  })

  it('값을 손대면 통화별 임계를 안 보낸다 — 그 축은 저장 시점에 서버가 유도한다', () => {
    const draft = useScreenDraftStore()
    draft.loadFrom(marketScoped())
    draft.forkAsCustom()
    draft.conditions[0]!.value = 2000000000000

    const operand = draft.toConditionGroup().conditions[0]!.operand

    expect(operand.thresholdsByCurrency ?? null).toBeNull()
    expect(operand.value).toBe(2000000000000)
  })
})
