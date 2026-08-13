import { describe, expect, it } from 'vitest'

import { conditionIndicatorCodes } from './draftIndicators'

function condition(indicatorCode: string | null, operandIndicatorCode: string | null = null) {
  return { indicatorCode, operandIndicatorCode }
}

describe('conditionIndicatorCodes', () => {
  it('좌변과 우변을 나온 순서대로 모은다', () => {
    const codes = conditionIndicatorCodes([
      condition('PER'),
      condition('ADJ_CLOSE', 'SMA_200'),
      condition('ROE'),
    ])

    // 조건을 세운 순서가 곧 사용자가 생각한 순서다.
    expect(codes).toEqual(['PER', 'ADJ_CLOSE', 'SMA_200', 'ROE'])
  })

  it('같은 지표를 두 번 써도 한 번만 센다', () => {
    // 「PER >= 5」와 「PER <= 15」처럼 한 지표에 구간을 거는 것이 흔하다.
    expect(conditionIndicatorCodes([condition('PER'), condition('PER')])).toEqual(['PER'])
  })

  it('아직 지표를 고르지 않은 조건은 건너뛴다', () => {
    // 칩을 만들고 지표를 고르기 전 상태다. null이 코드 목록에 들어가면 화면이 빈 카드를 그린다.
    expect(conditionIndicatorCodes([condition(null), condition('PBR')])).toEqual(['PBR'])
  })

  it('조건이 없으면 빈 목록이다 — 구획 자체가 안 그려진다', () => {
    expect(conditionIndicatorCodes([])).toEqual([])
  })

  it('카탈로그에 없을 법한 코드도 거르지 않는다', () => {
    // 폐기된 지표를 조건에 들고 있는 전략이 있을 수 있다. 여기서 걸러내면 그 전략이 왜
    // 실행되지 않는지 화면 어디에도 흔적이 없다.
    expect(conditionIndicatorCodes([condition('ICHI_TIER')])).toEqual(['ICHI_TIER'])
  })
})
