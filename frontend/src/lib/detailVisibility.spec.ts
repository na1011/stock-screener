import { afterEach, describe, expect, it, vi } from 'vitest'

import type { IndicatorResponse, StockIndicatorValue } from '@/api/types'

import {
  hiddenCount,
  isVisibleByDefault,
  missingCount,
  readShowAllIndicators,
  writeShowAllIndicators,
} from './detailVisibility'

function value(code: string, detailDefault: boolean): StockIndicatorValue {
  return {
    code,
    name: code,
    value: 1,
    unit: null,
    decimalScale: 2,
    category: 'OSCILLATOR',
    higherIsBetter: null,
    description: '',
    interpretation: '',
    detailDefault,
    unavailableReason: null,
  }
}

function catalogEntry(code: string, detailDefault: boolean): IndicatorResponse {
  return {
    code,
    name: code,
    description: '',
    interpretation: '',
    category: 'OSCILLATOR',
    valueType: 'RATIO',
    unit: null,
    decimalScale: 2,
    higherIsBetter: null,
    defaultColumn: false,
    detailDefault,
    sortOrder: 1,
    percentileOf: null,
    percentile: null,
    population: null,
    bands: [],
  }
}

describe('isVisibleByDefault', () => {
  it('서버가 실어 준 값을 그대로 쓴다', () => {
    expect(isVisibleByDefault(value('MACD', true))).toBe(true)
    expect(isVisibleByDefault(value('ICHI_MOD_A', false))).toBe(false)
  })
})

describe('hiddenCount', () => {
  it('응답에 실렸는데 감춘 것만 센다', () => {
    const indicators = [value('MACD', true), value('ICHI_MOD_A', false), value('ICHI_MOD_B', false)]

    expect(hiddenCount(indicators)).toBe(2)
  })

  it('응답에 없는 지표는 감춘 것이 아니다 — 애초에 값이 없던 것이다', () => {
    // 카탈로그에서 세면 종목과 무관하게 언제나 같은 수가 나온다. 그러면 값이 없어 빠진
    // 것까지 "감췄다"고 말하게 되고, 사용자는 조건에 넣으면 볼 수 있다고 믿는다.
    expect(hiddenCount([value('MACD', true)])).toBe(0)
  })
})

describe('missingCount', () => {
  it('분모를 기본 노출 대상으로 좁힌다', () => {
    // 카탈로그 4개 중 하나는 감춤 대상. 기본 노출은 셋이고 둘을 그렸으면 하나가 빠진 것이다.
    const catalog = [
      catalogEntry('A', true),
      catalogEntry('B', true),
      catalogEntry('C', true),
      catalogEntry('HIDDEN', false),
    ]

    expect(missingCount(catalog, 2)).toBe(1)
  })

  it('감춘 지표를 값 없음으로 세지 않는다', () => {
    // 예전 식(전체 − 그린 수)이면 2가 나온다. 감춤은 값의 유무와 무관한 사실이다.
    const catalog = [catalogEntry('A', true), catalogEntry('HIDDEN', false)]

    expect(missingCount(catalog, 1)).toBe(0)
  })

  it('카탈로그가 없으면 0이다 — 분모를 모르는 채로 수를 지어내지 않는다', () => {
    expect(missingCount([], 5)).toBe(0)
  })
})

/**
 * 「전체 지표 보기」의 기억.
 *
 * 환경이 `node`라 `window`가 없다 — 그것이 오히려 이 시험의 요점이다. **저장소를 못 쓰는
 * 환경에서 던지면 지표 구획이 통째로 죽는데**, 그것은 토글 하나가 치를 대가가 아니다.
 */
describe('전체 지표 보기 — 기억이 화면을 죽이지 않는다', () => {
  afterEach(() => {
    vi.unstubAllGlobals()
  })

  it('저장소가 없으면 기본값으로 돌아간다. 던지지 않는다', () => {
    vi.stubGlobal('window', undefined)

    expect(() => readShowAllIndicators()).not.toThrow()
    expect(readShowAllIndicators()).toBe(false)
    expect(() => writeShowAllIndicators(true)).not.toThrow()
  })

  it('저장소가 막혀 있어도 던지지 않는다 — 비공개 모드가 그 자리다', () => {
    vi.stubGlobal('window', {
      localStorage: {
        getItem() {
          throw new Error('접근이 막혔다')
        },
        setItem() {
          throw new Error('접근이 막혔다')
        },
      },
    })

    expect(readShowAllIndicators()).toBe(false)
    expect(() => writeShowAllIndicators(true)).not.toThrow()
  })

  it('쓴 값을 그대로 읽는다', () => {
    const store = new Map<string, string>()
    vi.stubGlobal('window', {
      localStorage: {
        getItem: (key: string) => store.get(key) ?? null,
        setItem: (key: string, value: string) => void store.set(key, value),
      },
    })

    writeShowAllIndicators(true)
    expect(readShowAllIndicators()).toBe(true)

    writeShowAllIndicators(false)
    expect(readShowAllIndicators()).toBe(false)
  })
})
