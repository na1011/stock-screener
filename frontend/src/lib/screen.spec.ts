import { describe, expect, it } from 'vitest'

import type { DraftOrigin } from '@/stores/screenDraft'

import {
  matchesOrigin,
  screenTypeLabel,
  staleNotice,
  staleReasonOf,
  type ScreenRef,
} from './screen'

const preset: ScreenRef = { id: 7, code: 'UNDERVALUED', type: 'PRESET' }
const mine: ScreenRef = { id: 42, code: null, type: 'CUSTOM' }

const presetOrigin: DraftOrigin = { kind: 'preset', code: 'UNDERVALUED', name: '저평가 가치주' }
const mineOrigin: DraftOrigin = { kind: 'screen', id: 42, name: '내 전략' }
const freshOrigin: DraftOrigin = { kind: 'custom' }

describe('matchesOrigin — 레일 표시와 결과 신선도가 같은 규칙을 쓴다', () => {
  it('프리셋은 code로 대조한다', () => {
    expect(matchesOrigin(preset, presetOrigin)).toBe(true)
    expect(matchesOrigin(preset, { kind: 'preset', code: 'MOMENTUM', name: '추세 상승' })).toBe(false)
  })

  it('내 전략은 id로 대조한다', () => {
    expect(matchesOrigin(mine, mineOrigin)).toBe(true)
    expect(matchesOrigin(mine, { kind: 'screen', id: 43, name: '다른 전략' })).toBe(false)
  })

  it('종류가 다르면 id·code가 같아도 다른 전략이다', () => {
    // 프리셋 id 7과 내 전략 id 7은 서로 다른 것이다. type을 보지 않으면 레일이 엉뚱한
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

describe('staleReasonOf — 자백의 기준은 "무엇을 보냈는가"다', () => {
  const base = { hasResult: true, matchesLastSent: false, canRun: true }

  it('보낸 것과 지금 보낼 것이 같으면 결과는 낡지 않았다', () => {
    // IMPORTANT — 이 한 줄이 이 함수가 생긴 이유다. 예전에는 `dirty`로 판정했는데 그것을
    // 내리는 곳이 reset·loadFrom뿐이라, 즉석 조건으로 **성공한 직후에도** 방금 받은 결과
    // 위에 "아래는 이전 실행 결과입니다"가 떴다.
    expect(
      staleReasonOf({ ...base, matchesLastSent: true, resultScreen: mine, origin: freshOrigin }),
    ).toBeNull()
  })

  it('결과가 없으면 낡고 자시고 할 것이 없다', () => {
    expect(
      staleReasonOf({ ...base, hasResult: false, resultScreen: null, origin: freshOrigin }),
    ).toBeNull()
  })

  it('즉석 조건을 고치면 EDITED다 — 다른 전략이라고 하지 않는다', () => {
    // matchesOrigin이 custom에 언제나 false를 주므로, 이 갈래가 없으면 즉석 조건을 고칠
    // 때마다 "지금 조건과 다른 전략의 결과입니다"라고 말하게 된다. 열린 전략이 없는데도.
    expect(staleReasonOf({ ...base, resultScreen: mine, origin: freshOrigin })).toBe('EDITED')
  })

  it('조건을 비우면 SCREEN이다 — 실행할 것이 없으면 열린 전략도 없다', () => {
    expect(
      staleReasonOf({ ...base, canRun: false, resultScreen: preset, origin: freshOrigin }),
    ).toBe('SCREEN')
  })

  it('같은 전략을 고친 것과 다른 전략을 연 것이 갈린다', () => {
    // 결과를 만든 전략(resultScreen)이 지금 열린 것과 같으면 사용자가 고친 것이고,
    // 다르면 갈아탄 것이다. 되돌리는 방법이 서로 달라 문구도 달라야 한다.
    expect(staleReasonOf({ ...base, resultScreen: preset, origin: presetOrigin })).toBe('EDITED')
    expect(staleReasonOf({ ...base, resultScreen: preset, origin: mineOrigin })).toBe('SCREEN')
  })
})

describe('staleNotice — 어긋난 이유를 접지 않는다', () => {
  it('조건 편집과 전략 전환이 서로 다른 말을 한다', () => {
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
