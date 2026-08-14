import { describe, expect, it } from 'vitest'

import { quoteDescription, quoteTone } from './quote'

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

describe('quoteDescription — 무엇에 견준 값인지 날짜로 말한다', () => {
  it('「전일」이라 적지 않는다 — 직전 거래일이 수 주 전일 수 있다', () => {
    expect(quoteDescription('2026-07-20', 1200, 1.74)).toBe(
      '2026. 07. 20. 종가보다 1,200원(1.7%) 올랐습니다.',
    )
  })

  it('내린 것은 부호가 아니라 낱말이 말한다 — 숫자는 크기만 낸다', () => {
    expect(quoteDescription('2026-08-13', -2300, -2.31)).toBe(
      '2026. 08. 13. 종가보다 2,300원(2.3%) 내렸습니다.',
    )
  })

  it('보합은 크기를 말하지 않는다', () => {
    expect(quoteDescription('2026-08-13', 0, 0)).toBe('2026. 08. 13. 종가와 같습니다.')
  })

  it('낼 것이 없으면 null이다 — 문장을 지어내지 않는다', () => {
    expect(quoteDescription('2026-08-13', null, null)).toBeNull()
    expect(quoteDescription(null, 1200, 1.74)).toBeNull()
  })
})
