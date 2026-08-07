import { describe, expect, it } from 'vitest'

import type { OutcomeStatus } from '@/api/types'

import { outcomeStyle } from './outcome'

describe('outcomeStyle — 세 상태가 서로 다르게 그려진다', () => {
  const statuses: OutcomeStatus[] = ['MATCHED', 'NOT_MATCHED', 'NOT_EVALUABLE']

  it('라벨·아이콘·색이 셋 다 서로 다르다', () => {
    // 둘로 접히면 "데이터가 없어 판정할 수 없었다"가 "조건에 맞지 않았다"와 같아 보이고,
    // 사용자는 종목이 왜 빠졌는지 알 수 없다.
    const labels = new Set(statuses.map((s) => outcomeStyle(s).label))
    const icons = new Set(statuses.map((s) => outcomeStyle(s).icon))
    const severities = new Set(statuses.map((s) => outcomeStyle(s).severity))

    expect(labels.size).toBe(3)
    expect(icons.size).toBe(3)
    expect(severities.size).toBe(3)
  })

  it('NOT_EVALUABLE은 불충족과 다른 뜻임을 문구로 밝힌다', () => {
    const style = outcomeStyle('NOT_EVALUABLE')
    expect(style.label).toBe('데이터 없음')
    expect(style.tooltip).toContain('판정하지 못했다')
  })

  it('모든 상태에 문구가 채워져 있다', () => {
    for (const status of statuses) {
      const style = outcomeStyle(status)
      expect(style.label).not.toBe('')
      expect(style.tooltip).not.toBe('')
    }
  })

  it('모르는 상태는 조용히 넘어가지 않고 던진다', () => {
    // 서버가 상태를 늘렸는데 화면이 모르면, 그 종목의 판정이 화면에서 사라진다.
    expect(() => outcomeStyle('WHATEVER' as OutcomeStatus)).toThrow()
  })
})
