import { describe, expect, it } from 'vitest'

import type { DraftOrigin } from '@/stores/screenDraft'

import { matchesOrigin, screenTypeLabel, staleNotice, type ScreenRef } from './screen'

const preset: ScreenRef = { id: 7, code: 'UNDERVALUED', type: 'PRESET' }
const mine: ScreenRef = { id: 42, code: null, type: 'CUSTOM' }

const presetOrigin: DraftOrigin = { kind: 'preset', code: 'UNDERVALUED', name: '저평가 가치주' }
const mineOrigin: DraftOrigin = { kind: 'screen', id: 42, name: '내 화면' }
const freshOrigin: DraftOrigin = { kind: 'custom' }

describe('matchesOrigin — 레일 표시와 결과 신선도가 같은 규칙을 쓴다', () => {
  it('프리셋은 code로 대조한다', () => {
    expect(matchesOrigin(preset, presetOrigin)).toBe(true)
    expect(matchesOrigin(preset, { kind: 'preset', code: 'MOMENTUM', name: '추세 상승' })).toBe(false)
  })

  it('내 화면은 id로 대조한다', () => {
    expect(matchesOrigin(mine, mineOrigin)).toBe(true)
    expect(matchesOrigin(mine, { kind: 'screen', id: 43, name: '다른 화면' })).toBe(false)
  })

  it('종류가 다르면 id·code가 같아도 다른 화면이다', () => {
    // 프리셋 id 7과 내 화면 id 7은 서로 다른 것이다. type을 보지 않으면 레일이 엉뚱한
    // 항목을 활성으로 표시하고, 결과가 낡았는데 낡지 않았다고 말하게 된다.
    expect(matchesOrigin({ id: 42, code: null, type: 'PRESET' }, mineOrigin)).toBe(false)
    expect(matchesOrigin({ id: 7, code: 'UNDERVALUED', type: 'CUSTOM' }, presetOrigin)).toBe(false)
  })

  it('즉석 조건은 무엇과도 같지 않다', () => {
    // 저장되지 않은 초안에는 가리킬 이름이 없다. 조건을 하나만 고쳐도 다른 것이 된다.
    expect(matchesOrigin(preset, freshOrigin)).toBe(false)
    expect(matchesOrigin(mine, freshOrigin)).toBe(false)
  })
})

describe('staleNotice — 어긋난 이유를 접지 않는다', () => {
  it('조건 편집과 화면 전환이 서로 다른 말을 한다', () => {
    const edited = staleNotice('EDITED')
    const screen = staleNotice('SCREEN')
    expect(edited).not.toBeNull()
    expect(screen).not.toBeNull()
    // 접으면 사용자는 무엇을 되돌려야 하는지 알 수 없다.
    expect(edited).not.toBe(screen)
  })

  it('어긋나지 않았으면 말하지 않는다', () => {
    expect(staleNotice(null)).toBeNull()
  })
})

describe('screenTypeLabel', () => {
  it('두 종류가 서로 다른 낱말이다', () => {
    expect(screenTypeLabel('PRESET')).not.toBe(screenTypeLabel('CUSTOM'))
  })
})
