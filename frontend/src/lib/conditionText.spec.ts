import { describe, expect, it } from 'vitest'

import type { ConditionInfo, ComparisonOperatorName, OperandPayload } from '@/api/types'

import { describeCondition } from './conditionText'

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
    ...overrides,
  }
}

const name = (code: string): string => NAMES[code] ?? code

describe('describeCondition — 서버가 준 한글 라벨을 그대로 쓴다', () => {
  it('상수에는 단위를 붙인다 — 실제값과 척도가 같은지 알 수 있어야 한다', () => {
    const text = describeCondition(
      condition({ operator: 'LT', operand: { type: 'LITERAL', value: 10 } }),
      name,
    )
    expect(text).toBe('PER 10.00배 미만')
  })

  it('지표 참조에는 단위를 붙이지 않는다', () => {
    // "200일 이동평균 × 0.90원"은 뜻이 없다.
    const text = describeCondition(
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
    )
    expect(text).toBe('수정종가 200일 이동평균 × 0.90 이상')
  })

  it('값이 없으면 단위도 붙지 않는다', () => {
    // "—배"는 없는 값에 단위를 붙인 것이라 거짓이다.
    const text = describeCondition(
      condition({ operator: 'LT', operand: { type: 'LITERAL', value: null } }),
      name,
    )
    expect(text).toBe('PER — 미만')
  })

  it('BETWEEN은 상·하한을 함께 적는다', () => {
    const text = describeCondition(
      condition({
        operator: 'BETWEEN',
        operatorLabel: '사이',
        operand: { type: 'LITERAL', value: 45, upperBound: 70 },
      }),
      name,
    )
    // 단위는 뒤에 한 번만. "45.00배 ~ 70.00배"는 같은 말을 두 번 한다.
    expect(text).toBe('PER 45.00 ~ 70.00배 사이')
  })

  it('지표 참조는 코드가 아니라 이름으로 적는다', () => {
    const text = describeCondition(
      condition({
        operator: 'GT',
        operatorLabel: '초과',
        operand: { type: 'INDICATOR', indicatorCode: 'SMA_200' },
      }),
      name,
    )
    expect(text).toBe('PER 200일 이동평균 초과')
  })

  it('배수가 1이 아니면 함께 적는다', () => {
    const text = describeCondition(
      condition({
        operator: 'GTE',
        operatorLabel: '이상',
        operand: { type: 'INDICATOR', indicatorCode: 'SMA_200', multiplier: 0.9 },
      }),
      name,
    )
    expect(text).toBe('PER 200일 이동평균 × 0.90 이상')
  })

  it('배수 1은 적지 않는다 — 정보를 더하지 않고 문장만 길어진다', () => {
    const text = describeCondition(
      condition({
        operator: 'GT',
        operatorLabel: '초과',
        operand: { type: 'INDICATOR', indicatorCode: 'SMA_200', multiplier: 1 },
      }),
      name,
    )
    expect(text).toBe('PER 200일 이동평균 초과')
  })
})
