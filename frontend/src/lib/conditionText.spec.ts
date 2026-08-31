import { describe, expect, it } from 'vitest'

import type {
  ComparisonOperatorName,
  ConditionInfo,
  IndicatorResponse,
  OperandPayload,
} from '@/api/types'
import { MONEY_UNIT } from '@/lib/format'
import type { DraftCondition } from '@/stores/screenDraft'

import {
  disabledConditionNotice,
  incompleteConditionNotice,
  splitCondition,
  summarizeDraftCondition,
} from './conditionText'

const NAMES: Record<string, string> = {
  SMA_200: '200일 이동평균',
  PER: 'PER',
}

function condition(
  overrides: Partial<ConditionInfo> & { operator: ComparisonOperatorName; operand: OperandPayload },
): ConditionInfo {
  return {
    index: 0,
    indicator: {
      code: 'PER',
      name: 'PER',
      unit: '배',
      decimalScale: 2,
      higherIsBetter: false,
      interpretation: '',
    },
    operatorLabel: '미만',
    weight: null,
    required: false,
    ...overrides,
  }
}

const name = (code: string): string => NAMES[code] ?? code

/**
 * 두 조각을 이어 붙인 문장.
 *
 * 아래 단정들이 조각이 아니라 이 문장을 보는 이유는, 화면이 둘을 **다른 칸에** 그려도
 * 사용자가 읽는 말은 하나이기 때문이다. 조각의 경계가 어디든 이어 붙인 결과가 달라지면
 * 그것은 문구가 바뀐 것이다.
 */
function sentence(...args: Parameters<typeof splitCondition>): string {
  const parts = splitCondition(...args)
  return `${parts.subject} ${parts.criterion}`
}

describe('splitCondition — 서버가 준 한글 라벨을 그대로 쓴다', () => {
  it('상수에는 단위를 붙인다 — 실제값과 척도가 같은지 알 수 있어야 한다', () => {
    const text = sentence(
      condition({ operator: 'LT', operand: { type: 'LITERAL', value: 10 } }),
      name,
      'KRW',
    )
    expect(text).toBe('PER 10.00배 미만')
  })

  it('지표 참조에는 단위를 붙이지 않는다', () => {
    // "200일 이동평균 × 0.90원"은 뜻이 없다.
    const text = sentence(
      condition({
        operator: 'GTE',
        operatorLabel: '이상',
        indicator: {
          code: 'ADJ_CLOSE',
          name: '수정종가',
          unit: '원',
          decimalScale: 2,
          higherIsBetter: null,
          interpretation: '',
        },
        operand: { type: 'INDICATOR', indicatorCode: 'SMA_200', multiplier: 0.9 },
      }),
      name,
      'KRW',
    )
    expect(text).toBe('수정종가 200일 이동평균 × 0.90 이상')
  })

  it('값이 없으면 단위도 붙지 않는다', () => {
    // "—배"는 없는 값에 단위를 붙인 것이라 거짓이다.
    const text = sentence(
      condition({ operator: 'LT', operand: { type: 'LITERAL', value: null } }),
      name,
      'KRW',
    )
    expect(text).toBe('PER — 미만')
  })

  it('BETWEEN은 상·하한을 함께 적는다', () => {
    const text = sentence(
      condition({
        operator: 'BETWEEN',
        operatorLabel: '사이',
        operand: { type: 'LITERAL', value: 45, upperBound: 70 },
      }),
      name,
      'KRW',
    )
    // 단위는 뒤에 한 번만. "45.00배 ~ 70.00배"는 같은 말을 두 번 한다.
    expect(text).toBe('PER 45.00 ~ 70.00배 사이')
  })

  it('지표 참조는 코드가 아니라 이름으로 적는다', () => {
    const text = sentence(
      condition({
        operator: 'GT',
        operatorLabel: '초과',
        operand: { type: 'INDICATOR', indicatorCode: 'SMA_200' },
      }),
      name,
      'KRW',
    )
    expect(text).toBe('PER 200일 이동평균 초과')
  })

  it('배수가 1이 아니면 함께 적는다', () => {
    const text = sentence(
      condition({
        operator: 'GTE',
        operatorLabel: '이상',
        operand: { type: 'INDICATOR', indicatorCode: 'SMA_200', multiplier: 0.9 },
      }),
      name,
      'KRW',
    )
    expect(text).toBe('PER 200일 이동평균 × 0.90 이상')
  })

  it('배수 1은 적지 않는다 — 정보를 더하지 않고 문장만 길어진다', () => {
    const text = sentence(
      condition({
        operator: 'GT',
        operatorLabel: '초과',
        operand: { type: 'INDICATOR', indicatorCode: 'SMA_200', multiplier: 1 },
      }),
      name,
      'KRW',
    )
    expect(text).toBe('PER 200일 이동평균 초과')
  })
})

describe('splitCondition — 금액은 그 시장의 통화로 적는다', () => {
  const marketCap = (operand: OperandPayload, operator: ComparisonOperatorName, label: string) =>
    condition({
      operator,
      operand,
      operatorLabel: label,
      indicator: {
        code: 'MARKET_CAP',
        name: '시가총액',
        // 카탈로그는 시장 중립이라 금액을 이 문자열 하나로만 말한다. 화면이 그것을
        // 행의 통화로 갈아끼우지 않으면 미국 임계가 「원」으로 뜬다.
        unit: '원',
        decimalScale: 0,
        higherIsBetter: true,
        interpretation: '',
      },
    })

  it('달러 시장이면 접두 기호를 붙이고 「원」을 안 쓴다', () => {
    const text = sentence(
      marketCap({ type: 'LITERAL', value: 7940000000 }, 'GTE', '이상'),
      name,
      'USD',
    )
    expect(text).toBe('시가총액 $7,940,000,000 이상')
  })

  it('원화 시장이면 접미가 「원」이다 — 같은 조건이 시장에 따라 다르게 적힌다', () => {
    const text = sentence(
      marketCap({ type: 'LITERAL', value: 1000000000000 }, 'GTE', '이상'),
      name,
      'KRW',
    )
    expect(text).toBe('시가총액 1,000,000,000,000원 이상')
  })

  it('IMPORTANT — BETWEEN은 접두를 양쪽에 붙인다. 접미만 생각하면 하한이 통화를 잃는다', () => {
    // `summarizeDraftCondition`(조건 칩)은 양쪽에 붙인다. 여기만 빠지면 같은 임계가
    // 칩에서는 `$10,000,000,000`, 표에서는 `10,000,000,000`이 되어 두 곳이 갈린다.
    const text = sentence(
      marketCap({ type: 'LITERAL', value: 1e10, upperBound: 2e10 }, 'BETWEEN', '사이'),
      name,
      'USD',
    )
    expect(text).toBe('시가총액 $10,000,000,000 ~ $20,000,000,000 사이')
  })

  it('접미 통화의 BETWEEN은 뒤에 한 번만 붙는다 — 같은 말을 두 번 하지 않는다', () => {
    const text = sentence(
      marketCap({ type: 'LITERAL', value: 1e12, upperBound: 2e12 }, 'BETWEEN', '사이'),
      name,
      'KRW',
    )
    expect(text).toBe('시가총액 1,000,000,000,000 ~ 2,000,000,000,000원 사이')
  })

  it('값이 없으면 통화 기호를 안 붙인다 — `$—`는 없는 값에 통화를 붙인 것이라 거짓이다', () => {
    const text = sentence(
      marketCap({ type: 'LITERAL', value: null, upperBound: 2e10 }, 'BETWEEN', '사이'),
      name,
      'USD',
    )
    expect(text).toBe('시가총액 — ~ $20,000,000,000 사이')
  })
})

describe('summarizeDraftCondition — 조건 칩은 고른 대상의 임계를 보인다', () => {
  const marketCap = (over: Partial<DraftCondition> = {}): DraftCondition => ({
    key: 1,
    indicatorCode: 'MARKET_CAP',
    operator: 'GTE',
    operandType: 'LITERAL',
    value: 1e12,
    upperBound: null,
    thresholdCurrency: 'KRW',
    thresholdsByCurrency: {
      KRW: { value: 1e12, upperBound: null },
      USD: { value: 7.94e9, upperBound: null },
    },
    operandIndicatorCode: null,
    multiplier: null,
    weight: null,
    required: true,
    enabled: true,
    ...over,
  })

  const indicator = {
    code: 'MARKET_CAP',
    name: '시가총액',
    unit: MONEY_UNIT,
    decimalScale: 0,
  } as IndicatorResponse

  const chip = (condition: DraftCondition, currency: string | null): string | null =>
    summarizeDraftCondition(condition, indicator, null, '이상', currency)

  it('IMPORTANT — 미국을 고르면 미국 임계다. 원화 값에 「$」만 붙이지 않는다', () => {
    // 실측으로 이 자리가 「시가총액 7,940,000,000원 이상」이었다 — USD 값에 원 단위가
    // 붙은 것이고, 같은 화면의 「선정 이유」는 1조원이라 위아래가 갈렸다.
    expect(chip(marketCap(), 'USD')).toBe('시가총액 $7,940,000,000 이상')
  })

  it('국내를 고르면 국내 임계다', () => {
    expect(chip(marketCap(), 'KRW')).toBe('시가총액 1,000,000,000,000원 이상')
  })

  it('대상 통화에 임계가 없으면 **그 값의 원래 통화로** 그린다 — 숫자와 단위를 어긋내지 않는다', () => {
    // 사용자가 만든 원화 전략을 미국 대상에서 볼 때다. 그 전략은 어차피 실행이 거절되므로
    // 「$1,000,000,000,000」로 그리는 것보다 원화 임계임을 그대로 보이는 편이 참이다.
    const krwOnly = marketCap({ thresholdsByCurrency: { KRW: { value: 1e12, upperBound: null } } })
    expect(chip(krwOnly, 'USD')).toBe('시가총액 1,000,000,000,000원 이상')
  })

  it('사용자가 값을 고치면 맵을 안 쓴다 — 편집기가 value를 직접 쓰기 때문이다', () => {
    // 맵은 그대로인데 값만 바뀐 상태. 여기서 맵을 쓰면 사용자가 넣은 수가 화면에서 사라진다.
    expect(chip(marketCap({ value: 5e11 }), 'KRW')).toBe('시가총액 500,000,000,000원 이상')
    expect(chip(marketCap({ value: 5e11 }), 'USD')).toBe('시가총액 $500,000,000,000 이상')
  })

  it('시장 축이 없는 조건은 고른 대상의 통화로 그대로 그린다', () => {
    const neutral = marketCap({ thresholdCurrency: null, thresholdsByCurrency: null, value: 10 })
    expect(chip(neutral, 'USD')).toBe('시가총액 $10 이상')
  })
})

describe('splitCondition — 경계는 지표 이름 뒤다', () => {
  it('이름에 공백이 있어도 subject가 통째로 남는다', () => {
    // 문장을 공백으로 쪼개 얻으면 여기서만 어긋난다 — subject가 "200일"이 되고
    // criterion이 "이동평균 …"이 된다. 화면은 그래도 그럴듯해 보인다.
    const parts = splitCondition(
      condition({
        operator: 'GTE',
        operatorLabel: '이상',
        indicator: {
          code: 'SMA_200',
          name: '200일 이동평균',
          unit: '원',
          decimalScale: 2,
          higherIsBetter: null,
          interpretation: '',
        },
        operand: { type: 'LITERAL', value: 20100 },
      }),
      name,
      'KRW',
    )
    expect(parts.subject).toBe('200일 이동평균')
    expect(parts.criterion).toBe('20,100.00원 이상')
  })

  it('criterion은 임계값이 아니라 비교 대상이다 — 다른 지표가 올 수 있다', () => {
    const parts = splitCondition(
      condition({
        operator: 'GTE',
        operatorLabel: '이상',
        operand: { type: 'INDICATOR', indicatorCode: 'SMA_200', multiplier: 1.25 },
      }),
      name,
      'KRW',
    )
    expect(parts.subject).toBe('PER')
    expect(parts.criterion).toBe('200일 이동평균 × 1.25 이상')
  })
})

describe('실행에서 빠지는 이유 둘을 갈라 말한다', () => {
  it('없으면 아무 말도 하지 않는다', () => {
    expect(incompleteConditionNotice(0)).toBeNull()
    expect(disabledConditionNotice(0)).toBeNull()
  })

  it('개수를 말한다', () => {
    expect(disabledConditionNotice(2)).toContain('2')
  })

  it('덜 만든 것과 일부러 뺀 것이 서로 다른 말을 한다', () => {
    // 합치면 실수와 의도가 같은 숫자에 섞인다 — 하나는 채워야 하고 하나는 그대로 두면 된다.
    expect(disabledConditionNotice(1)).not.toBe(incompleteConditionNotice(1))
  })
})
