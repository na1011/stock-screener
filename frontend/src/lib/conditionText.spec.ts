import { describe, expect, it } from 'vitest'

import type { ConditionInfo, ComparisonOperatorName, OperandPayload } from '@/api/types'

import {
  disabledConditionNotice,
  incompleteConditionNotice,
  splitCondition,
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
    )
    expect(text).toBe('수정종가 200일 이동평균 × 0.90 이상')
  })

  it('값이 없으면 단위도 붙지 않는다', () => {
    // "—배"는 없는 값에 단위를 붙인 것이라 거짓이다.
    const text = sentence(
      condition({ operator: 'LT', operand: { type: 'LITERAL', value: null } }),
      name,
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
    )
    expect(text).toBe('PER 200일 이동평균 초과')
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
