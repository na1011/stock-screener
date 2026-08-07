import { describe, expect, it } from 'vitest'

import { EVALUATION_MODES } from '@/api/types'
import type { EvaluationMode } from '@/api/types'

import { evaluationModeLabels, evaluationModeOptions } from './evaluationMode'

describe('evaluationModeLabels', () => {
  it('두 방식이 모든 변형에서 서로 다르다', () => {
    for (const variant of ['noun', 'short', 'action'] as const) {
      const values = EVALUATION_MODES.map((mode) => evaluationModeLabels(mode)[variant])
      expect(new Set(values).size).toBe(EVALUATION_MODES.length)
    }
  })

  it('변형마다 문구가 다르다 — 자리마다 필요한 품사가 다르기 때문이다', () => {
    // 고르는 버튼에는 동사가, 요약에는 명사가, Tag에는 두 글자가 들어가야 한다.
    // 하나로 합치면 어느 한 자리는 반드시 어색해진다.
    const scoring = evaluationModeLabels('SCORING')
    expect(new Set([scoring.noun, scoring.short, scoring.action]).size).toBe(3)
    expect(scoring.short.length).toBeLessThanOrEqual(3)
  })

  it('모든 변형이 채워져 있다', () => {
    for (const mode of EVALUATION_MODES) {
      const labels = evaluationModeLabels(mode)
      expect(labels.noun).not.toBe('')
      expect(labels.short).not.toBe('')
      expect(labels.action).not.toBe('')
    }
  })

  it('모르는 방식은 던진다 — 라벨이 틀리면 결과 집합 전체를 잘못 설명한다', () => {
    expect(() => evaluationModeLabels('RANKING' as EvaluationMode)).toThrow()
  })
})

describe('evaluationModeOptions', () => {
  it('선택지를 손으로 적지 않고 유니온에서 파생시킨다', () => {
    // 손으로 적은 배열은 판정 방식이 늘어도 그대로 남아, 만들 수 없는 조건이 생기는데
    // 아무 데서도 티가 나지 않는다.
    expect(evaluationModeOptions).toHaveLength(EVALUATION_MODES.length)
    expect(evaluationModeOptions.map((it) => it.value)).toEqual([...EVALUATION_MODES])
    expect(evaluationModeOptions[0]?.label).toBe(evaluationModeLabels('FILTER').action)
  })
})
