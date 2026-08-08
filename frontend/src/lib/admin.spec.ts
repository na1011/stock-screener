import { describe, expect, it } from 'vitest'

import { BATCH_STATUSES, INTERACTION_ACTIONS, TRIGGERED_BY } from '@/api/types'
import type { BatchExecution } from '@/api/types'

import {
  batchStatusStyle,
  canRetry,
  elapsedSeconds,
  interactionActionLabel,
  triggeredByLabel,
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
    retriedFromId: null,
    triggeredBy: 'SCHEDULER',
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
