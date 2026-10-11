import { describe, expect, it } from 'vitest'

import type { BatchExecution } from '@/api/types'
import {
  formatDuration,
  isStalled,
  processedTasks,
  progressRatio,
  remainingSeconds,
  runningSeconds,
  throughputPerSecond,
} from '@/lib/batchProgress'

const NOW = Date.parse('2026-08-10T12:10:00Z')

function execution(overrides: Partial<BatchExecution> = {}): BatchExecution {
  return {
    id: 1,
    jobName: 'indicator-backfill',
    targetDate: '2026-08-06',
    status: 'RUNNING',
    startedAt: '2026-08-10T12:00:00Z',
    finishedAt: null,
    targetCount: 350,
    successCount: 0,
    failureCount: 0,
    writtenRowCount: 0,
    plannedTaskCount: null,
    progressAt: null,
    stageLabel: null,
    retriedFromId: null,
    triggeredBy: 'MANUAL',
    scope: null,
    scopeReason: null,
    incompleteMarkets: [],
    note: null,
    unresolvedFailureCount: 0,
    ...overrides,
  }
}

describe('progressRatio', () => {
  it('IMPORTANT — 계획이 없으면 null이다. 0이면 분모 0의 진행률이 된다', () => {
    // V14 이전 실행 25건이 이 상태다. 0으로 메우면 화면이 "0% 진행"으로 그리는데,
    // 그것은 다 끝난 실행에도 붙는다.
    expect(progressRatio(execution({ plannedTaskCount: null, successCount: 43400 }))).toBeNull()
  })

  it('분자와 분모가 같은 단위다 — 종목 수가 아니라 작업 단위다', () => {
    // 350종목 × 124거래일. 분모를 targetCount(350)로 쓰면 124가 나오고, 그것이
    // 화면에 43400/350으로 뜨던 값이다.
    const backfill = execution({ targetCount: 350, plannedTaskCount: 43400, successCount: 43400 })

    expect(progressRatio(backfill)).toBe(1)
  })

  it('실패도 진행에 센다 — 실패한 종목도 돌긴 돌았다', () => {
    expect(processedTasks(execution({ successCount: 8, failureCount: 2 }))).toBe(10)
    expect(progressRatio(execution({ successCount: 8, failureCount: 2, plannedTaskCount: 20 })))
      .toBe(0.5)
  })

  it('계획에 못 미치고 끝나는 것은 정상이다 — 그 차이가 건너뛴 작업 수다', () => {
    // 일일 갱신은 시세가 실패한 종목의 지표를 건너뛴다. 100%가 아니어야 그 사실이 보인다.
    const daily = execution({ plannedTaskCount: 700, successCount: 698, failureCount: 1 })

    expect(progressRatio(daily)).toBeCloseTo(0.999, 3)
  })

  it('1을 넘지 않는다 — 넘는 것은 서버 쪽 결함이고 막대로 알릴 일이 아니다', () => {
    expect(progressRatio(execution({ plannedTaskCount: 10, successCount: 99 }))).toBe(1)
  })
})

describe('runningSeconds', () => {
  it('도는 중에도 답한다 — 속도를 재려면 끝나기를 기다릴 수 없다', () => {
    expect(runningSeconds(execution(), NOW)).toBe(600)
  })

  it('끝났으면 종료 시각까지다', () => {
    expect(runningSeconds(execution({ finishedAt: '2026-08-10T12:05:00Z' }), NOW)).toBe(300)
  })
})

describe('throughputPerSecond', () => {
  it('아직 아무것도 처리하지 않았으면 null이다. 0이면 "느리다"로 읽힌다', () => {
    expect(throughputPerSecond(execution(), NOW)).toBeNull()
  })

  it('처리한 작업 단위를 경과 시간으로 나눈다', () => {
    expect(throughputPerSecond(execution({ successCount: 6000 }), NOW)).toBe(10)
  })
})

describe('remainingSeconds', () => {
  it('끝난 실행에는 답하지 않는다', () => {
    const done = execution({
      finishedAt: '2026-08-10T12:05:00Z',
      plannedTaskCount: 100,
      successCount: 100,
    })

    expect(remainingSeconds(done, NOW)).toBeNull()
  })

  it('계획이 없으면 답하지 않는다 — 근거가 얇으면 숫자를 내지 않는다', () => {
    expect(remainingSeconds(execution({ successCount: 6000 }), NOW)).toBeNull()
  })

  it('지금까지의 평균 속도로 남은 작업을 나눈다', () => {
    // 600초에 6,000건 → 초당 10건. 남은 6,000건은 600초.
    const running = execution({ plannedTaskCount: 12000, successCount: 6000 })

    expect(remainingSeconds(running, NOW)).toBe(600)
  })
})

describe('isStalled', () => {
  it('IMPORTANT — RUNNING만으로는 도는 것과 죽은 것이 구분되지 않는다', () => {
    const dead = execution({ progressAt: '2026-08-10T12:00:30Z' })

    expect(isStalled(dead, NOW)).toBe(true)
  })

  it('방금 진척이 있었으면 멎지 않았다', () => {
    expect(isStalled(execution({ progressAt: '2026-08-10T12:09:58Z' }), NOW)).toBe(false)
  })

  it('진척 시각이 없으면 멎었다고 하지 않는다 — 옛 실행이 전부 경고가 되면 곧 배경이 된다', () => {
    expect(isStalled(execution({ progressAt: null }), NOW)).toBe(false)
  })

  it('끝난 실행은 멎은 것이 아니다', () => {
    const done = execution({
      status: 'SUCCESS',
      finishedAt: '2026-08-10T12:05:00Z',
      progressAt: '2026-08-10T12:05:00Z',
    })

    expect(isStalled(done, NOW)).toBe(false)
  })
})

describe('formatDuration', () => {
  it.each([
    [null, '—'],
    [3, '3초'],
    [59, '59초'],
    [90, '1분 30초'],
    [3340, '55분 40초'],
    [7500, '2시간 5분'],
  ])('%s초는 %s로 읽힌다', (seconds, expected) => {
    expect(formatDuration(seconds)).toBe(expected)
  })
})
