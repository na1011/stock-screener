import { describe, expect, it } from 'vitest'

import type { IndicatorBand } from '@/api/types'

import { bandPosition, matchBandIndex, readBand, toneForPosition } from './band'

function band(overrides: Partial<IndicatorBand>): IndicatorBand {
  return { label: '', operator: 'GTE', value: 0, upperBound: null, ...overrides }
}

/**
 * ROE의 실제 구간(`RoeCalculator`). 서버가 `[from, to)`를 GTE/BETWEEN/LT로 번역해 내린 모습
 * 그대로다 — 이 픽스처가 서버와 어긋나면 아래 경계 검사가 뜻을 잃는다.
 */
const ROE_BANDS: IndicatorBand[] = [
  band({ label: '매우 높음', operator: 'GTE', value: 20 }),
  band({ label: '우수', operator: 'BETWEEN', value: 15, upperBound: 20 }),
  band({ label: '양호', operator: 'BETWEEN', value: 10, upperBound: 15 }),
  band({ label: '낮음', operator: 'BETWEEN', value: 5, upperBound: 10 }),
  band({ label: '매우 낮음', operator: 'LT', value: 5 }),
]

function labelAt(value: number): string | null {
  const index = matchBandIndex(ROE_BANDS, value)
  return index === null ? null : (ROE_BANDS[index]?.label ?? null)
}

describe('matchBandIndex — 서버가 준 부등호를 적용하기만 한다', () => {
  it('구간 안의 값은 그 구간의 라벨을 얻는다', () => {
    expect(labelAt(24)).toBe('매우 높음')
    expect(labelAt(17)).toBe('우수')
    expect(labelAt(11.34)).toBe('양호')
    expect(labelAt(7)).toBe('낮음')
    expect(labelAt(2)).toBe('매우 낮음')
  })

  it('경계값은 위쪽 구간이 가져간다 — 도메인의 [from, to)와 같다', () => {
    // BETWEEN은 양끝을 포함하므로 20은 '우수'(15~20)에도 걸린다. 목록 순서대로 훑어
    // 첫 매치를 취하는 것이 그 중복을 도메인 쪽으로 푼다. 반대로 풀면 '20% 이상 = 매우
    // 높음'이라고 적어 둔 계산기와 화면이 어긋난다.
    expect(labelAt(20)).toBe('매우 높음')
    expect(labelAt(15)).toBe('우수')
    expect(labelAt(5)).toBe('낮음')
  })

  it('음수와 0도 가장 아래 구간에 든다', () => {
    expect(labelAt(0)).toBe('매우 낮음')
    expect(labelAt(-28.33)).toBe('매우 낮음')
  })

  it('구간이 없으면 null이다 — 주가 스케일 지표가 그렇다', () => {
    expect(matchBandIndex([], 137805)).toBeNull()
  })

  it('상한 없는 BETWEEN은 맞다고 답하지 않는다', () => {
    // 맞다고 하면 값이 아무 데나 들어가고 그 구간의 라벨이 붙는다. 조건이 아닌 것을
    // 조건으로 읽는 것보다 "말하지 않는다"가 낫다.
    expect(matchBandIndex([band({ operator: 'BETWEEN', value: 10, upperBound: null })], 12)).toBeNull()
  })

  it('나머지 부등호도 그대로 적용한다', () => {
    expect(matchBandIndex([band({ operator: 'GT', value: 10 })], 10)).toBeNull()
    expect(matchBandIndex([band({ operator: 'LTE', value: 10 })], 10)).toBe(0)
    expect(matchBandIndex([band({ operator: 'EQ', value: 10 })], 10)).toBe(0)
    expect(matchBandIndex([band({ operator: 'NEQ', value: 10 })], 10)).toBeNull()
  })
})

describe('bandPosition — 목록은 큰 값이 앞이다', () => {
  it('첫 구간이 값 축의 꼭대기다', () => {
    expect(bandPosition(0, 5)).toBe(1)
    expect(bandPosition(4, 5)).toBe(0)
    expect(bandPosition(2, 5)).toBe(0.5)
  })

  it('구간이 하나뿐이면 위치를 말하지 않는다', () => {
    expect(bandPosition(0, 1)).toBe(0.5)
  })
})

describe('toneForPosition — 유불리는 서버가 준 방향만 본다', () => {
  it('높을수록 좋은 지표는 위쪽이 유리하다', () => {
    expect(toneForPosition(1, 'HIGHER_IS_BETTER')).toBe('FAVORABLE')
    expect(toneForPosition(0, 'HIGHER_IS_BETTER')).toBe('UNFAVORABLE')
  })

  it('낮을수록 좋은 지표는 정반대다', () => {
    // 뒤집어 쓰면 PER이 가장 비싼 구간에서 초록이 된다. 값도 라벨도 그럴듯해 눈으로는
    // 드러나지 않는다.
    expect(toneForPosition(1, 'LOWER_IS_BETTER')).toBe('UNFAVORABLE')
    expect(toneForPosition(0, 'LOWER_IS_BETTER')).toBe('FAVORABLE')
  })

  it('방향이 없으면 색을 주지 않는다 — RSI의 과매수는 나쁜 것이 아니다', () => {
    expect(toneForPosition(1, 'NEUTRAL')).toBe('NEUTRAL')
    expect(toneForPosition(0, 'NEUTRAL')).toBe('NEUTRAL')
  })

  it('가운데 삼분의 일은 중립이다', () => {
    expect(toneForPosition(0.5, 'HIGHER_IS_BETTER')).toBe('NEUTRAL')
    expect(toneForPosition(0.5, 'LOWER_IS_BETTER')).toBe('NEUTRAL')
  })

  it('구간 밖의 위치도 안전하게 다룬다', () => {
    expect(toneForPosition(1.4, 'HIGHER_IS_BETTER')).toBe('FAVORABLE')
    expect(toneForPosition(-0.2, 'HIGHER_IS_BETTER')).toBe('UNFAVORABLE')
    expect(toneForPosition(Number.NaN, 'HIGHER_IS_BETTER')).toBe('NEUTRAL')
  })

  it('ROE 11.34%는 양호 구간이고 색을 얻지 않는다', () => {
    // 실제 화면(기아)의 값이다. 다섯 구간의 한가운데라 초록도 빨강도 아니어야 한다 —
    // 뜻은 색이 아니라 '양호'라는 라벨이 진다.
    const index = matchBandIndex(ROE_BANDS, 11.34)
    expect(index).not.toBeNull()
    expect(toneForPosition(bandPosition(index ?? 0, ROE_BANDS.length), 'HIGHER_IS_BETTER')).toBe(
      'NEUTRAL',
    )
  })
})

describe('readBand — 미터와 숫자 색조가 같은 것을 본다', () => {
  const roe = { bands: ROE_BANDS, higherIsBetter: true, percentile: null }

  it('구간 모드는 라벨과 눈금 자리를 함께 준다', () => {
    const reading = readBand(roe, 24)
    expect(reading).toEqual({
      position: 1,
      tone: 'FAVORABLE',
      label: '매우 높음',
      segments: 5,
      // 목록은 큰 값이 앞이지만 눈금은 왼쪽이 낮은 값이다. 뒤집지 않으면 유리한 값이
      // 막대 왼쪽 끝에서 차오른다.
      activeSegment: 4,
    })
  })

  it('낮을수록 좋은 지표는 같은 자리에서 반대 색조가 된다', () => {
    const per = { bands: ROE_BANDS, higherIsBetter: false, percentile: null }
    expect(readBand(per, 24)?.tone).toBe('UNFAVORABLE')
  })

  it('백분위는 값 자체가 위치다 — 구간 라벨은 붙이지 않는다', () => {
    const pctl = {
      bands: [],
      higherIsBetter: null,
      percentile: {
        ascending: true,
        favorableEnd: 'HIGH' as const,
        topQuintileOperator: null,
        topQuintileThreshold: null,
        label: '',
      },
    }
    expect(readBand(pctl, 0.6568)).toEqual({
      position: 0.6568,
      tone: 'NEUTRAL',
      label: null,
      segments: 0,
      activeSegment: null,
    })
    expect(readBand(pctl, 0.9)?.tone).toBe('FAVORABLE')
    expect(readBand(pctl, 0.1)?.tone).toBe('UNFAVORABLE')
  })

  it('구간도 백분위도 없으면 그리지 않는다 — 주가 스케일 지표다', () => {
    // ATR(14) 8480.47에 "높음"을 붙이면 종목마다 뜻이 달라진다(ADR-0023).
    expect(readBand({ bands: [], higherIsBetter: null, percentile: null }, 8480.47)).toBeNull()
  })

  it('값이 없으면 그리지 않는다 — 0으로 메우지 않는다', () => {
    expect(readBand(roe, null)).toBeNull()
    expect(readBand(roe, undefined)).toBeNull()
    expect(readBand(roe, Number.NaN)).toBeNull()
  })
})
