import { describe, expect, it } from 'vitest'

import type { PercentileGuide } from '@/api/types'

import { directionStyle, indicatorDirection, topQuintileCondition } from './direction'

function guide(overrides: Partial<PercentileGuide>): PercentileGuide {
  return {
    ascending: true,
    favorableEnd: 'UNKNOWN',
    topQuintileOperator: null,
    topQuintileThreshold: null,
    label: '',
    ...overrides,
  }
}

describe('indicatorDirection — 서버가 준 값만 본다', () => {
  it('higherIsBetter가 참이면 높을수록 유리하다', () => {
    expect(indicatorDirection({ higherIsBetter: true })).toBe('HIGHER_IS_BETTER')
  })

  it('higherIsBetter가 거짓이면 낮을수록 유리하다', () => {
    expect(indicatorDirection({ higherIsBetter: false })).toBe('LOWER_IS_BETTER')
  })

  it('null이면 방향이 없다 — RSI처럼 유불리가 정해지지 않은 지표다', () => {
    expect(indicatorDirection({ higherIsBetter: null })).toBe('NEUTRAL')
  })

  it('백분위는 favorableEnd를 따른다 — 저장된 higherIsBetter는 항상 null이다', () => {
    // PER_PCTL이 이 경우다. higherIsBetter만 보면 방향 없음이 되어, 낮을수록 좋다는
    // 사실이 화면에서 사라진다.
    expect(indicatorDirection({ higherIsBetter: null, percentile: guide({ favorableEnd: 'LOW' }) }))
      .toBe('LOWER_IS_BETTER')
    expect(indicatorDirection({ higherIsBetter: null, percentile: guide({ favorableEnd: 'HIGH' }) }))
      .toBe('HIGHER_IS_BETTER')
    expect(indicatorDirection({ higherIsBetter: null, percentile: guide({ favorableEnd: 'UNKNOWN' }) }))
      .toBe('NEUTRAL')
  })

  it('세 방향의 표기와 라벨이 서로 다르다', () => {
    const styles = (['HIGHER_IS_BETTER', 'LOWER_IS_BETTER', 'NEUTRAL'] as const).map(directionStyle)
    expect(new Set(styles.map((s) => s.marker)).size).toBe(3)
    expect(new Set(styles.map((s) => s.label)).size).toBe(3)
  })
})

describe('topQuintileCondition — 부등호를 프론트가 정하지 않는다', () => {
  it('낮을수록 좋은 지표의 상위 20%는 서버가 준 LTE 0.2다', () => {
    // PER_PCTL. 뒤집어 쓰면 가장 비싼 종목이 최상위로 올라오는데 값이 그럴듯해 눈에 안 띈다.
    const condition = topQuintileCondition(
      guide({ favorableEnd: 'LOW', topQuintileOperator: 'LTE', topQuintileThreshold: 0.2 }),
    )
    expect(condition).toEqual({ operator: 'LTE', value: 0.2 })
  })

  it('높을수록 좋은 지표의 상위 20%는 서버가 준 GTE 0.8이다', () => {
    const condition = topQuintileCondition(
      guide({ favorableEnd: 'HIGH', topQuintileOperator: 'GTE', topQuintileThreshold: 0.8 }),
    )
    expect(condition).toEqual({ operator: 'GTE', value: 0.8 })
  })

  it('방향을 모르면 null이다 — 임의로 한쪽을 고르지 않는다', () => {
    expect(topQuintileCondition(guide({ favorableEnd: 'UNKNOWN' }))).toBeNull()
  })

  it('백분위 지표가 아니면 null이다', () => {
    expect(topQuintileCondition(null)).toBeNull()
    expect(topQuintileCondition(undefined)).toBeNull()
  })
})
