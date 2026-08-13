import { describe, expect, it } from 'vitest'

import { DEFAULT_DECIMAL_SCALE } from '@/api/limits'
import type { ConditionInfo, IndicatorResponse, ScreeningRow } from '@/api/types'

import { outcomeStyle } from './outcome'
import { RESULT_LABELS, indicatorBriefOf, valueOf } from './resultRow'

function row(values: Record<string, number>): ScreeningRow {
  return {
    stockId: 1,
    ticker: '005930',
    name: '삼성전자',
    marketCode: 'KOSPI',
    close: 70000,
    score: null,
    maxScore: null,
    scoreRatio: null,
    matchedCount: 0,
    notEvaluableCount: 0,
    values,
    outcomes: [],
  }
}

const catalogEntry: IndicatorResponse = {
  code: 'PER',
  name: 'PER(카탈로그)',
  description: '',
  interpretation: '카탈로그 해설',
  category: 'FUNDAMENTAL',
  valueType: 'RATIO',
  unit: '배',
  decimalScale: 1,
  higherIsBetter: false,
  defaultColumn: true,
  detailDefault: true,
  sortOrder: 1,
  percentileOf: null,
  percentile: null,
  population: null,
  bands: [],
}

const conditionInfo: ConditionInfo = {
  index: 0,
  indicator: {
    code: 'PER',
    name: 'PER(조건)',
    unit: '배',
    decimalScale: 3,
    higherIsBetter: false,
    interpretation: '조건 해설',
  },
  operator: 'LT',
  operatorLabel: '미만',
  operand: { type: 'LITERAL', value: 10 },
  weight: null,
  required: false,
}

describe('valueOf', () => {
  it('키가 없으면 null이다 — 계산되지 않았다는 뜻이다', () => {
    expect(valueOf(row({}), 'PER')).toBeNull()
  })

  it('값이 0이면 0이다 — 0과 부재를 접지 않는다', () => {
    // IMPORTANT — `?? 0`을 쓰면 이 두 경우가 같아지고, 사용자는 부채비율 0%인 회사를
    // 본 것으로 읽는다. 이 두 줄이 그 금지를 고정한다.
    expect(valueOf(row({ DEBT_RATIO: 0 }), 'DEBT_RATIO')).toBe(0)
  })

  it('값이 있으면 그대로 준다', () => {
    expect(valueOf(row({ PER: 8.1 }), 'PER')).toBe(8.1)
  })
})

describe('indicatorBriefOf', () => {
  const byCode = new Map([['PER', catalogEntry]])

  it('조건이 참조한 지표는 서버가 실어 준 정의를 먼저 쓴다', () => {
    // 서버가 판정에 쓴 것과 같은 정의라야, 카탈로그가 실패해도 조건에 걸린 지표는
    // 제대로 그려진다.
    expect(indicatorBriefOf('PER', [conditionInfo], byCode).name).toBe('PER(조건)')
    expect(indicatorBriefOf('PER', [conditionInfo], byCode).decimalScale).toBe(3)
  })

  it('조건에 없는 컬럼은 카탈로그로 채운다', () => {
    expect(indicatorBriefOf('PER', [], byCode).name).toBe('PER(카탈로그)')
  })

  it('둘 다 없으면 코드를 이름으로 쓰고 기본 자릿수를 쓴다', () => {
    // 저장된 전략이 폐기된 지표를 참조하는 경우다. 서버도 같은 상황에 코드만 돌려준다.
    const brief = indicatorBriefOf('GONE', [], byCode)

    expect(brief.name).toBe('GONE')
    expect(brief.decimalScale).toBe(DEFAULT_DECIMAL_SCALE)
    expect(brief.higherIsBetter).toBeNull()
  })
})

describe('RESULT_LABELS', () => {
  it('판정 문구는 lib/outcome에서 온다', () => {
    // outcome.ts는 자신이 판정 표현의 유일한 지점이라고 적어 두었는데, 표와 카드가
    // 컬럼 머리글을 따로 적어 두어 이미 새고 있었다(데스크톱 '데이터없음' vs
    // 모바일 '데이터 없음'). 이 단언이 그 주장을 실제로 지킨다.
    expect(RESULT_LABELS.matched).toBe(outcomeStyle('MATCHED').label)
    expect(RESULT_LABELS.notEvaluable).toBe(outcomeStyle('NOT_EVALUABLE').label)
  })

  it('모든 문구가 비어 있지 않다', () => {
    for (const value of Object.values(RESULT_LABELS)) {
      expect(value).not.toBe('')
    }
  })
})
