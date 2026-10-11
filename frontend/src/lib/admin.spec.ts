import { describe, expect, it } from 'vitest'

import {
  BATCH_STATUSES,
  EXECUTION_SCOPE_REASONS,
  INTERACTION_ACTIONS,
  TRIGGERED_BY,
} from '@/api/types'
import type { BatchExecution } from '@/api/types'

import {
  batchStatusStyle,
  canRetry,
  elapsedSeconds,
  interactionActionLabel,
  triggeredByLabel,
  scopeNote,
} from './admin'

function execution(overrides: Partial<BatchExecution> = {}): BatchExecution {
  return {
    id: 1,
    jobName: 'daily-update',
    targetDate: '2026-08-05',
    status: 'PARTIAL_SUCCESS',
    startedAt: '2026-08-06T04:00:00Z',
    finishedAt: '2026-08-06T04:03:20Z',
    targetCount: 350,
    successCount: 348,
    failureCount: 2,
    writtenRowCount: 348,
    plannedTaskCount: 700,
    progressAt: '2026-08-06T04:03:20Z',
    stageLabel: null,
    retriedFromId: null,
    triggeredBy: 'SCHEDULER',
    scope: null,
    scopeReason: null,
    incompleteMarkets: [],
    note: null,
    unresolvedFailureCount: 2,
    ...overrides,
  }
}

describe('배치 상태 표현', () => {
  it.each(BATCH_STATUSES)('%s에 라벨과 severity가 있다', (status) => {
    // 하나라도 빠지면 그 상태의 실행이 화면에서 어떻게 보일지 정해지지 않는다.
    const style = batchStatusStyle(status)
    expect(style.label).not.toBe('')
    expect(style.tooltip).not.toBe('')
  })

  it('IMPORTANT — 부분 실패가 실패와 다른 색이다', () => {
    // 848종목 성공한 배치가 빨간불로 뜨면 매일 빨간불이고, 곧 아무도 보지 않게 된다.
    expect(batchStatusStyle('PARTIAL_SUCCESS').severity).not.toBe(
      batchStatusStyle('FAILED').severity,
    )
  })

  it('중단은 실패가 아니다 — 쿼터 소진은 정상적인 일시정지다', () => {
    expect(batchStatusStyle('ABORTED').severity).not.toBe('danger')
  })
})

describe('트리거 표현', () => {
  it('범위 덧말은 NARROW에만 붙는다 — 표지 전(null)을 「모름」으로 덮지 않는다', () => {
    expect(scopeNote(execution({ scope: 'FULL' }))).toBeNull()
    expect(scopeNote(execution({ scope: null }))).toBeNull()
    // 사유 기록 전(V65 전) NARROW는 예전 낱말이다 — ARGS로 접지 않고 툴팁이 그 사실을 말한다.
    expect(scopeNote(execution({ scope: 'NARROW' }))?.label).toBe('좁힘')
    expect(scopeNote(execution({ scope: 'NARROW' }))?.title).toContain('사유 기록 전')
  })

  it.each(EXECUTION_SCOPE_REASONS)('NARROW 사유 %s에 덧말과 툴팁이 있다', (reason) => {
    const note = scopeNote(execution({ scope: 'NARROW', scopeReason: reason }))
    expect(note?.label).toBeTruthy()
    expect(note?.title).toContain('연체 판정에 세지 않습니다')
  })

  it('IMPORTANT — 시장 미완료는 「좁힘」이 아니다 — 정기 범위로 돈 실행을 손으로 좁힌 실행으로 읽으면 원인을 엉뚱한 데서 찾는다', () => {
    const note = scopeNote(
      execution({ scope: 'NARROW', scopeReason: 'MARKET_INCOMPLETE', incompleteMarkets: ['KOSPI', 'US'] }),
    )
    expect(note?.label).toBe('시장 미완료 KOSPI, US')
    expect(note?.title).not.toContain('범위가 달라')
  })

  it('처음부터 좁힌 실행이 시장까지 못 끝냈으면 둘 다 말한다 — 사유는 처음 것 그대로다', () => {
    const note = scopeNote(
      execution({ scope: 'NARROW', scopeReason: 'ARGS', incompleteMarkets: ['US'] }),
    )
    expect(note?.label).toBe('좁힘 · 시장 미완료 US')
  })

  it('모르는 사유는 던진다 — 틀린 덧말은 실행을 잘못 설명한다', () => {
    expect(() =>
      scopeNote(execution({ scope: 'NARROW', scopeReason: 'GUESS' as never })),
    ).toThrow()
  })

  it.each(TRIGGERED_BY)('%s에 한글 라벨이 있다', (triggeredBy) => {
    expect(triggeredByLabel(triggeredBy)).not.toBe(triggeredBy)
  })
})

describe('인터랙션 행위 이름', () => {
  it.each(INTERACTION_ACTIONS)('%s에 한글 이름이 있다', (action) => {
    expect(interactionActionLabel(action)).not.toBe('')
  })

  it('모르는 값은 던지지 않고 그대로 보인다 — 로그 화면이 통째로 죽으면 안 된다', () => {
    // 서버가 행위를 하나 더했는데 프론트가 낡은 경우다. 낯선 이름 한 줄이 섞이는 것이
    // 화면이 사라지는 것보다 낫고, 그 이름 자체가 "거울이 낡았다"는 신호다.
    expect(interactionActionLabel('SOMETHING_NEW' as never)).toBe('SOMETHING_NEW')
  })
})

describe('소요 시간', () => {
  it('끝난 실행은 초로 계산된다', () => {
    expect(elapsedSeconds(execution())).toBe(200)
  })

  it('IMPORTANT — 아직 안 끝났으면 null이다. 0이면 즉시 끝난 것으로 읽힌다', () => {
    expect(elapsedSeconds(execution({ finishedAt: null }))).toBeNull()
  })
})

describe('재실행 가능 판정', () => {
  it('미해결 실패가 있고 잡이 지원하면 켠다', () => {
    expect(canRetry(execution(), true)).toBe(true)
  })

  it('미해결 실패가 없으면 끈다 — 누르면 대상 0건짜리 실행만 남는다', () => {
    expect(canRetry(execution({ unresolvedFailureCount: 0 }), true)).toBe(false)
  })

  it('IMPORTANT — 지원 여부는 서버가 준 값이다. 잡 이름으로 유도하지 않는다', () => {
    // 유도하면 잡이 늘 때마다 화면을 고쳐야 하고, 고치지 않은 사실은 버튼이 없는 것으로만
    // 보인다. daily-update여도 서버가 아니라면 끈다.
    expect(canRetry(execution({ jobName: 'daily-update' }), false)).toBe(false)
  })
})
