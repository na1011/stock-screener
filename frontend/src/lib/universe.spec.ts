import { describe, expect, it } from 'vitest'

import type { ScreenType } from '@/api/types'

import { screenTypeLabel } from './screen'
import { allUniversesLabel, universeLabel } from './universe'

describe('universeLabel', () => {
  it('편입 종목 수를 함께 보인다', () => {
    expect(universeLabel({ name: '코스피200', memberCount: 200 })).toBe('코스피200 (200종목)')
  })

  it('종목 수를 모르면 괄호를 붙이지 않는다', () => {
    // `(null종목)`을 보이지 않기 위해서다.
    expect(universeLabel({ name: '코스피200', memberCount: null })).toBe('코스피200')
  })

  it('빌더가 적는 이름과 결과 머리말이 적는 이름이 같다', () => {
    // 둘이 갈리면 사용자는 "내가 고른 게 이게 맞나"를 확인할 방법이 없어진다.
    const universe = { name: '코스닥150', memberCount: 150 }
    expect(universeLabel(universe)).toBe(universeLabel({ ...universe }))
    expect(allUniversesLabel([universe])).not.toBe('')
  })

  it('「전체」의 괄호 안은 활성 목록에서 만든다 — 코드에 박으면 대상이 늘 때 화면만 낡는다', () => {
    // 예전에는 '전체 (코스피200 + 코스닥150)'이 상수였다. 그것은 활성 대상이 국내 둘일
    // 때만 참인 문장이고, 대상이 늘면 화면이 없는 목록을 사실로 말한다.
    expect(allUniversesLabel([{ memberCount: 200 }, { memberCount: 150 }])).toBe('전체 (350종목)')
    expect(allUniversesLabel([{ memberCount: 200 }, { memberCount: 150 }, { memberCount: 1000 }]))
      .toBe('전체 (1350종목)')
  })

  it('목록이 아직 안 왔으면 괄호를 붙이지 않는다 — 「전체 (0종목)」은 빈 대상으로 읽힌다', () => {
    expect(allUniversesLabel([])).toBe('전체')
  })
})

describe('screenTypeLabel', () => {
  it('두 종류가 서로 다른 이름을 갖는다', () => {
    expect(screenTypeLabel('PRESET')).not.toBe(screenTypeLabel('CUSTOM'))
  })

  it('모르는 종류는 던진다 — 목록의 섹션 구성이 함께 바뀌어야 한다', () => {
    expect(() => screenTypeLabel('SHARED' as ScreenType)).toThrow()
  })
})
