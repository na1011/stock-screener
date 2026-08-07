import { describe, expect, it } from 'vitest'

import {
  MISSING,
  formatCount,
  formatDate,
  formatIndicatorValue,
  formatNumber,
  formatPrice,
  formatRatioAsPercent,
  parseIsoDate,
  toIsoDate,
} from './format'

describe('formatNumber — 서버가 정한 소수 자릿수를 그대로 쓴다', () => {
  it('자릿수를 채운다', () => {
    expect(formatNumber(8.1, 2)).toBe('8.10')
    expect(formatNumber(0.8, 4)).toBe('0.8000')
  })

  it('자릿수를 넘으면 반올림한다', () => {
    expect(formatNumber(8.126, 2)).toBe('8.13')
  })

  it('천 단위를 끊는다', () => {
    expect(formatNumber(44787.12, 2)).toBe('44,787.12')
  })

  it('음수도 그대로 보인다 — 적자 기업의 성장률이 그렇다', () => {
    expect(formatNumber(-12.5, 2)).toBe('-12.50')
  })
})

describe('값 없음은 0이 아니다', () => {
  it.each([null, undefined, Number.NaN])('%p는 —로 그린다', (value) => {
    // 0으로 그리면 "계산되지 않았다"가 "값이 0이다"로 읽힌다. 부채비율 0%인 회사를
    // 본 것으로 오해하게 된다.
    expect(formatNumber(value, 2)).toBe(MISSING)
    expect(formatIndicatorValue(value, '%', 2)).toBe(MISSING)
    expect(formatPrice(value)).toBe(MISSING)
    expect(formatCount(value)).toBe(MISSING)
    expect(formatRatioAsPercent(value)).toBe(MISSING)
  })

  it('0은 값이 있는 것이다', () => {
    expect(formatNumber(0, 2)).toBe('0.00')
    expect(formatPrice(0)).toBe('0원')
  })
})

describe('formatIndicatorValue — 단위는 서버가 준 문자열을 붙인다', () => {
  it('배', () => {
    expect(formatIndicatorValue(8.1, '배', 2)).toBe('8.10배')
  })

  it('퍼센트', () => {
    expect(formatIndicatorValue(12.345, '%', 2)).toBe('12.35%')
  })

  it('단위가 없으면 숫자만 남는다', () => {
    expect(formatIndicatorValue(70123.5, null, 2)).toBe('70,123.50')
  })
})

describe('formatPrice — 원주가라 소수점을 보이지 않는다', () => {
  it('천 단위를 끊고 원을 붙인다', () => {
    expect(formatPrice(70100)).toBe('70,100원')
  })
})

describe('formatRatioAsPercent', () => {
  it('0..1을 백분율로 옮긴다', () => {
    expect(formatRatioAsPercent(0.2)).toBe('20.0%')
    expect(formatRatioAsPercent(0.8333, 1)).toBe('83.3%')
  })
})

describe('parseIsoDate / toIsoDate — UTC로 밀리지 않는다', () => {
  it('왕복해도 같은 날짜다', () => {
    // new Date('2026-08-05')는 UTC 자정이라 KST에서는 같은 날 09시가 된다. 그 값을
    // toISOString()으로 되돌리면 날짜가 하루 밀리는 조합이 생긴다.
    expect(toIsoDate(parseIsoDate('2026-08-05'))).toBe('2026-08-05')
    expect(toIsoDate(parseIsoDate('2026-01-01'))).toBe('2026-01-01')
    expect(toIsoDate(parseIsoDate('2025-12-31'))).toBe('2025-12-31')
  })

  it('지역 시간의 연·월·일로 만든다', () => {
    const date = parseIsoDate('2026-08-05')
    expect(date?.getFullYear()).toBe(2026)
    expect(date?.getMonth()).toBe(7)
    expect(date?.getDate()).toBe(5)
  })

  it('형식이 다르거나 없으면 null이다', () => {
    expect(parseIsoDate('2026/08/05')).toBeNull()
    expect(parseIsoDate(null)).toBeNull()
    expect(toIsoDate(null)).toBeNull()
  })
})

describe('formatDate', () => {
  it('ISO 날짜를 화면 표기로 옮긴다', () => {
    expect(formatDate('2026-08-05')).toBe('2026. 08. 05.')
  })

  it('형식이 다르면 원문을 그대로 보인다 — 조용히 감추지 않는다', () => {
    expect(formatDate('언젠가')).toBe('언젠가')
  })

  it('없으면 —다', () => {
    expect(formatDate(null)).toBe(MISSING)
  })
})
