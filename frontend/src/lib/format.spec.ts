import { describe, expect, it } from 'vitest'

import {
  MISSING,
  MONEY_UNIT,
  formatAmount,
  formatCount,
  formatDate,
  formatIndicatorValue,
  formatNumber,
  formatPrice,
  formatRatioAsPercent,
  formatSignedPrice,
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
    expect(formatIndicatorValue(value, '%', 2, 'KRW')).toBe(MISSING)
    expect(formatPrice(value, 'KRW')).toBe(MISSING)
    expect(formatCount(value)).toBe(MISSING)
    expect(formatRatioAsPercent(value)).toBe(MISSING)
  })

  it('0은 값이 있는 것이다', () => {
    expect(formatNumber(0, 2)).toBe('0.00')
    expect(formatPrice(0, 'KRW')).toBe('0원')
  })
})

describe('formatIndicatorValue — 단위는 서버가 준 문자열을 붙인다', () => {
  it('배', () => {
    expect(formatIndicatorValue(8.1, '배', 2, 'KRW')).toBe('8.10배')
  })

  it('퍼센트', () => {
    expect(formatIndicatorValue(12.345, '%', 2, 'USD')).toBe('12.35%')
  })

  it('단위가 없으면 숫자만 남는다', () => {
    expect(formatIndicatorValue(70123.5, null, 2, 'KRW')).toBe('70,123.50')
  })
})

describe('formatPrice — 원주가라 소수점을 보이지 않는다', () => {
  it('천 단위를 끊고 원을 붙인다', () => {
    expect(formatPrice(70100, 'KRW')).toBe('70,100원')
  })
})

describe('formatSignedPrice — 오른 것에만 부호를 더한다', () => {
  it('양수에 +를 붙인다', () => {
    expect(formatSignedPrice(1200, 'KRW')).toBe('+1,200원')
  })

  it('음수는 formatPrice가 이미 붙인 -를 그대로 쓴다 — 두 번 붙이지 않는다', () => {
    expect(formatSignedPrice(-2300, 'KRW')).toBe('-2,300원')
  })

  it('보합에는 부호가 없다', () => {
    expect(formatSignedPrice(0, 'KRW')).toBe('0원')
  })

  it('없으면 —다. 0으로 메우지 않는다', () => {
    expect(formatSignedPrice(null, 'KRW')).toBe(MISSING)
  })
})

/**
 * 통화 축. **이 묶음이 없으면 미국 종목의 금액이 조용히 「원」으로 그려진다** —
 * 값도 자릿수도 맞아 보이므로 화면 어디에서도 틀렸다는 신호가 나지 않는다.
 *
 * 접는 자리가 통화마다 다른 것이 요점이다. 원화는 만진법(조·억)이고 달러는 천진법(T·B·M)이라
 * 한쪽 규칙을 다른 쪽에 쓰면 `$3.5조`처럼 읽을 수 없는 값이 나온다.
 */
describe('금액은 시장의 통화를 따라간다', () => {
  it('원화는 접미 「원」이다', () => {
    expect(formatPrice(70100, 'KRW')).toBe('70,100원')
  })

  it('달러는 접두 「$」이고 센트까지 보인다 — 원주가라 반올림하면 실제 지불액과 어긋난다', () => {
    expect(formatPrice(150.25, 'USD')).toBe('$150.25')
  })

  it('음수 부호가 통화 기호보다 앞이다 — `$-150`은 음수로 읽히지 않는다', () => {
    expect(formatPrice(-150.25, 'USD')).toBe('-$150.25')
  })

  it('원화는 만진법으로 접는다', () => {
    expect(formatAmount(1.234e12, 'KRW')).toBe('1.23조원')
    expect(formatAmount(3.5e8, 'KRW')).toBe('3.50억원')
  })

  it('달러는 천진법으로 접는다 — 만진법을 쓰면 `$3.5조`가 된다', () => {
    expect(formatAmount(3.5e12, 'USD')).toBe('$3.50T')
    expect(formatAmount(7.94e9, 'USD')).toBe('$7.94B')
    expect(formatAmount(4.2e6, 'USD')).toBe('$4.20M')
  })

  it('금액 지표의 단위 「원」이 그 시장의 통화로 갈린다', () => {
    // 서버 카탈로그는 시장 중립이라 MARKET_CAP 단위를 늘 '원'으로 준다.
    expect(formatIndicatorValue(1e12, MONEY_UNIT, 0, 'KRW')).toBe('1,000,000,000,000원')
    expect(formatIndicatorValue(7.94e9, MONEY_UNIT, 0, 'USD')).toBe('$7,940,000,000')
  })

  it('금액이 아닌 단위는 통화를 타지 않는다 — %와 배는 시장 중립이다', () => {
    expect(formatIndicatorValue(12.3, '%', 1, 'USD')).toBe('12.3%')
    expect(formatIndicatorValue(8.1, '배', 2, 'USD')).toBe('8.10배')
  })

  it('모르는 통화는 원화로 떨어지지 않고 ISO 코드를 붙인다', () => {
    // 틀린 통화로 그려 놓고 조용한 것이 못 그리는 것보다 나쁘다.
    expect(formatPrice(1234, 'JPY')).toBe('1,234.00 JPY')
  })

  it('통화를 모르면 숫자만 낸다 — 기본값으로 원화를 그리지 않는다', () => {
    expect(formatPrice(1234, null)).toBe('1,234')
    expect(formatIndicatorValue(1e12, MONEY_UNIT, 0, null)).toBe('1,000,000,000,000')
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
