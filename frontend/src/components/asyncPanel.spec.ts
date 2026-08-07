import { describe, expect, it } from 'vitest'

import { ASYNC_PANELS, panelSubject } from './asyncPanel'
import type { AsyncPanelName } from './asyncPanel'

const names = Object.keys(ASYNC_PANELS) as AsyncPanelName[]

/**
 * 한글 음절에 받침이 있는가.
 *
 * IMPORTANT — 이 계산이 **런타임이 아니라 테스트에 있는 것**이 요점이다. 순한글에서만 맞고
 * 'S&P500' 같은 이름에서는 조용히 틀리기 때문에 화면에서 쓰지 않는다. 대신 여기서 데이터로
 * 적어 둔 조사가 맞는지 검사한다 — 틀리면 빌드가 멈추므로 조용히 틀릴 수가 없다.
 */
function hasFinalConsonant(syllable: string): boolean | null {
  const code = syllable.codePointAt(0)
  if (code === undefined || code < 0xac00 || code > 0xd7a3) {
    return null
  }
  return (code - 0xac00) % 28 !== 0
}

describe('ASYNC_PANELS', () => {
  it('모든 영역에 이름과 조사가 있다', () => {
    for (const name of names) {
      expect(ASYNC_PANELS[name].subject).not.toBe('')
      expect(['을', '를']).toContain(ASYNC_PANELS[name].particle)
    }
  })

  it('조사가 이름의 받침과 맞는다', () => {
    for (const name of names) {
      const { subject, particle } = ASYNC_PANELS[name]
      const batchim = hasFinalConsonant(subject.slice(-1))
      if (batchim === null) {
        // 한글로 끝나지 않는 이름이면 계산으로 판단할 수 없다. 그래서 값을 손으로 적는다.
        continue
      }
      expect(particle, `${subject}의 조사`).toBe(batchim ? '을' : '를')
    }
  })

  it('영역 이름이 서로 다르다 — 두 패널이 같은 이름을 쓰면 어느 쪽이 실패했는지 모른다', () => {
    expect(new Set(names.map(panelSubject)).size).toBe(names.length)
  })
})
