import { describe, expect, it } from 'vitest'

import type { StockIndicatorValue } from '@/api/types'

import { MISSING, splitIndicatorValue } from './format'
import { readBand } from './band'

/**
 * 값이 없는 지표를 카드로 남기는 경로(ADR-0060).
 *
 * 이 파일이 있는 이유는 **기존 프론트 테스트가 이 변경을 하나도 검증하지 못하기 때문**이다 —
 * `detailVisibility.spec.ts`는 `detailDefault`와 개수만 보고 `value`를 읽지 않아, 값이
 * null이 되어도 초록이다.
 */
function indicator(value: number | null, unavailableReason: string | null): StockIndicatorValue {
  return {
    code: 'PER',
    name: 'PER',
    value,
    unit: '배',
    decimalScale: 2,
    category: 'FUNDAMENTAL',
    higherIsBetter: false,
    description: '',
    interpretation: '낮을수록 이익 대비 주가가 싸다.',
    detailDefault: true,
    unavailableReason,
  }
}

describe('값이 없는 지표의 표시', () => {
  it('값이 null이면 —로 그리고 단위를 붙이지 않는다', () => {
    const row = indicator(null, '적자라 PER을 내지 않습니다.')

    const parts = splitIndicatorValue(row.value, row.unit, row.decimalScale, 'KRW')

    expect(parts.number).toBe(MISSING)
    // `—배`는 없는 값에 단위를 붙인 것이라 거짓이다.
    expect(parts.unit).toBe('')
  })

  it('MUST NOT: 값이 없을 때 0으로 메우지 않는다', () => {
    const parts = splitIndicatorValue(indicator(null, '사유').value, '배', 2, 'KRW')

    expect(parts.number).not.toBe('0')
    expect(parts.number).not.toBe('0.00')
  })

  it('값이 없으면 구간 미터를 그리지 않는다 — 없는 값에 위치를 지어내지 않는다', () => {
    const source = {
      higherIsBetter: false,
      bands: [
        { label: '높음', operator: 'GTE' as const, value: 20, upperBound: null },
        { label: '낮음', operator: 'LT' as const, value: 20, upperBound: null },
      ],
    }

    expect(readBand(source, indicator(null, '사유').value)).toBeNull()
    expect(readBand(source, 10)).not.toBeNull()
  })

  it('사유가 있으면 해석문 대신 그것을 보인다 — 둘을 함께 그리면 계산된 값처럼 읽힌다', () => {
    // 카드가 고르는 규칙을 여기서 못박는다. IndicatorCard의 v-if/v-else-if와 같은 판정이다.
    const withReason = indicator(null, '최근 4개 분기가 적자라 PER을 내지 않습니다.')
    const withValue = indicator(12.3, null)

    expect(shown(withReason)).toBe(withReason.unavailableReason)
    expect(shown(withValue)).toBe(withValue.interpretation)
  })
})

/** `IndicatorCard`가 그리는 아래쪽 한 줄. 사유가 해석문을 대체한다. */
function shown(row: StockIndicatorValue): string | null {
  return row.unavailableReason ?? row.interpretation
}
