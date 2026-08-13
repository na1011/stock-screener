import { describe, expect, it } from 'vitest'

import {
  BUILDER_CATEGORY_RANK,
  DETAIL_CATEGORY_RANK,
  categoryLabel,
  groupByCategory,
} from './indicatorCategory'

interface Item {
  code: string
  category: string
}

function item(code: string, category: string): Item {
  return { code, category }
}

const categoryOf = (it: Item) => it.category

describe('categoryLabel', () => {
  it('알려진 분류는 한글 이름을 준다', () => {
    expect(categoryLabel('FUNDAMENTAL')).toBe('재무 지표')
    expect(categoryLabel('OSCILLATOR')).toBe('오실레이터')
    // ADR-0057에서 뜻이 바뀌었다 — 백분위가 아니라 지표 둘 이상의 교차·위치 신호다.
    expect(categoryLabel('DERIVED')).toBe('파생 지표')
  })

  it('모르는 분류는 코드를 그대로 보인다', () => {
    // 빈 문자열로 두면 제목 없는 묶음이 생겨 사용자가 그것이 무엇인지 알 방법이 없다.
    expect(categoryLabel('SENTIMENT')).toBe('SENTIMENT')
  })
})

describe('groupByCategory', () => {
  it('랭크 순서대로 묶음을 낸다', () => {
    const items = [
      item('PER', 'FUNDAMENTAL'),
      item('CLOSE', 'PRICE'),
      item('RSI', 'TECHNICAL'),
      item('MACD', 'OSCILLATOR'),
      item('GOLDEN_CROSS_AGE', 'DERIVED'),
    ]

    expect(groupByCategory(items, categoryOf, BUILDER_CATEGORY_RANK).map((g) => g.category)).toEqual([
      'PRICE',
      'TECHNICAL',
      'OSCILLATOR',
      'DERIVED',
      'FUNDAMENTAL',
    ])
    // 상세는 재무부터 읽고 가격은 맨 뒤다. 같은 입력에서 배치가 달라야 한다.
    expect(groupByCategory(items, categoryOf, DETAIL_CATEGORY_RANK).map((g) => g.category)).toEqual([
      'FUNDAMENTAL',
      'TECHNICAL',
      'OSCILLATOR',
      'DERIVED',
      'PRICE',
    ])
  })

  it('모르는 분류를 버리지 않고 맨 뒤에 붙인다', () => {
    // IMPORTANT — 예전에는 걸러냈다. 서버가 분류를 하나 추가하면 그 지표 전체가 조건
    // 빌더에서 선택 불가능해지는데 아무 신호가 없다. 이 테스트가 그 회귀를 막는다.
    const items = [item('SENT', 'SENTIMENT'), item('RSI', 'TECHNICAL')]

    const groups = groupByCategory(items, categoryOf, BUILDER_CATEGORY_RANK)

    expect(groups.map((g) => g.category)).toEqual(['TECHNICAL', 'SENTIMENT'])
    expect(groups[1]?.label).toBe('SENTIMENT')
    expect(groups[1]?.items).toHaveLength(1)
  })

  it('모르는 분류가 여럿이면 서버가 준 순서를 유지한다', () => {
    const items = [item('B', 'BETA'), item('A', 'ALPHA'), item('RSI', 'TECHNICAL')]

    expect(groupByCategory(items, categoryOf, BUILDER_CATEGORY_RANK).map((g) => g.category)).toEqual([
      'TECHNICAL',
      'BETA',
      'ALPHA',
    ])
  })

  it('값이 없는 분류는 빈 묶음으로 남기지 않는다', () => {
    const groups = groupByCategory([item('RSI', 'TECHNICAL')], categoryOf, BUILDER_CATEGORY_RANK)

    expect(groups).toHaveLength(1)
  })

  it('묶음 안의 순서는 입력 순서 그대로다', () => {
    // 카탈로그 스토어가 서버의 sortOrder로 미리 정렬해 넘긴다. 여기서 흐트러지면
    // 카탈로그가 의도한 배치가 사라진다.
    const items = [item('RSI', 'TECHNICAL'), item('SMA_20', 'TECHNICAL'), item('MACD', 'TECHNICAL')]

    expect(
      groupByCategory(items, categoryOf, BUILDER_CATEGORY_RANK)[0]?.items.map((it) => it.code),
    ).toEqual(['RSI', 'SMA_20', 'MACD'])
  })

  it('빈 입력은 빈 배열이다', () => {
    expect(groupByCategory([], categoryOf, BUILDER_CATEGORY_RANK)).toEqual([])
  })
})
