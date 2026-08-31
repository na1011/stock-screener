import { describe, expect, it } from 'vitest'

import { MISSING } from './format'
import { formatQuoteChange, quoteBasisLabel, quoteTone } from './quote'

describe('quoteTone — 값 없음과 보합을 가른다', () => {
  it('오르면 RISE, 내리면 FALL이다', () => {
    expect(quoteTone(1.7)).toBe('RISE')
    expect(quoteTone(-2.3)).toBe('FALL')
  })

  it('IMPORTANT — 0은 보합이고 null은 모름이다. 템플릿이 `rate > 0`으로 가르면 둘이 붙는다', () => {
    expect(quoteTone(0)).toBe('FLAT')
    expect(quoteTone(null)).toBeNull()
    expect(quoteTone(undefined)).toBeNull()
    expect(quoteTone(Number.NaN)).toBeNull()
  })
})

describe('formatQuoteChange — 금액이 앞, 백분율이 괄호 안', () => {
  it('오른 것에 부호를 붙이고 소수 두 자리로 낸다', () => {
    expect(formatQuoteChange(12500, 4.89, 'KRW')).toBe('+12,500원 (+4.89%)')
  })

  it('내린 것은 부호가 하나씩이다 — 두 번 붙지 않는다', () => {
    expect(formatQuoteChange(-2050, -17.46, 'KRW')).toBe('-2,050원 (-17.46%)')
  })

  it('보합에는 부호가 없다', () => {
    expect(formatQuoteChange(0, 0, 'KRW')).toBe('0원 (0.00%)')
  })

  it('값이 없으면 —다. 0으로 메우지 않는다', () => {
    expect(formatQuoteChange(null, null, 'KRW')).toBe(MISSING)
  })
})

describe('quoteBasisLabel — 「전일」이라 적지 않고 날짜를 말한다', () => {
  it('직전 거래일을 그대로 낸다 — 26거래일 전일 수도 있다', () => {
    expect(quoteBasisLabel('2025-10-29')).toBe('2025. 10. 29. 대비')
  })

  it('없으면 null이다 — 붙일 것이 없다', () => {
    expect(quoteBasisLabel(null)).toBeNull()
  })
})
