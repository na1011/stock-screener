import { describe, expect, it } from 'vitest'

import type { WatchlistItem } from '@/api/types'
import { ALERT_STATE_PRESENTATION, alertStateOf, type AlertState } from './watchlist'

function item(overrides: Partial<WatchlistItem> = {}): WatchlistItem {
  return {
    id: 1,
    stockId: 10,
    ticker: '005930',
    stockName: '삼성전자',
    marketCode: 'KOSPI',
    screenId: null,
    screenName: null,
    screenType: null,
    memo: null,
    satisfied: null,
    notifiedAt: null,
    createdAt: '2026-08-08T00:00:00Z',
    ...overrides,
  }
}

describe('alertStateOf', () => {
  it('알림 조건이 없으면 NONE이다', () => {
    expect(alertStateOf(item({ screenId: null, satisfied: null }))).toBe('NONE')
  })

  it('조건은 있는데 아직 판정 전이면 PENDING이다', () => {
    expect(alertStateOf(item({ screenId: 3, satisfied: null }))).toBe('PENDING')
  })

  it('IMPORTANT — 판정 전(null)과 불충족(false)이 서로 다른 상태다', () => {
    // 접으면 사용자는 배치가 돌았는지조차 알 수 없다. 서버가 세 값을 주는 이유가 이것이다.
    expect(alertStateOf(item({ screenId: 3, satisfied: null }))).not.toBe(
      alertStateOf(item({ screenId: 3, satisfied: false })),
    )
  })

  it('불충족은 UNMET, 충족은 MET이다', () => {
    expect(alertStateOf(item({ screenId: 3, satisfied: false }))).toBe('UNMET')
    expect(alertStateOf(item({ screenId: 3, satisfied: true }))).toBe('MET')
  })

  it('조건이 없으면 satisfied 값과 무관하게 NONE이다', () => {
    // 서버는 이 조합을 만들지 않지만, 조건을 뗀 직후의 응답을 화면이 잘못 읽지 않게 못박는다.
    expect(alertStateOf(item({ screenId: null, satisfied: true }))).toBe('NONE')
  })
})

describe('ALERT_STATE_PRESENTATION', () => {
  const states: AlertState[] = ['NONE', 'PENDING', 'UNMET', 'MET']

  it('모든 상태에 표시가 있다', () => {
    // Record<AlertState, …>라 컴파일이 먼저 잡지만, 상태를 늘리고 여기 배열을 안 고치면
    // 그 상태만 검증 없이 지나간다. 배열을 키에서 파생시켜 그것도 막는다.
    expect(Object.keys(ALERT_STATE_PRESENTATION).sort()).toEqual([...states].sort())
  })

  it('라벨이 서로 다르다 — 같으면 화면에서 상태가 접힌다', () => {
    const labels = states.map((state) => ALERT_STATE_PRESENTATION[state].label)
    expect(new Set(labels).size).toBe(states.length)
  })

  it('각 상태가 왜 그런지 설명한다', () => {
    states.forEach((state) => {
      expect(ALERT_STATE_PRESENTATION[state].hint.length).toBeGreaterThan(0)
    })
  })
})
