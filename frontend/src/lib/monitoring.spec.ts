import { afterEach, describe, expect, it, vi } from 'vitest'
import type { App } from 'vue'

const { initSpy } = vi.hoisted(() => ({ initSpy: vi.fn() }))
vi.mock('@sentry/vue', () => ({ init: initSpy }))

import { initMonitoring, shouldEnableMonitoring } from './monitoring'

/** `Sentry.init`은 app을 쓰지 않는 경로에서 형태만 요구한다. */
const app = {} as App

/** 마지막 호출에 넘어간 옵션. 호출이 없었으면 그 사실을 단정으로 드러낸다. */
function initOptions(): Record<string, unknown> {
  const call = initSpy.mock.calls.at(-1)
  if (call === undefined) {
    throw new Error('Sentry.init이 호출되지 않았다')
  }
  return call[0] as Record<string, unknown>
}

afterEach(() => {
  initSpy.mockClear()
  vi.unstubAllEnvs()
})

describe('shouldEnableMonitoring', () => {
  it.each([
    ['빈 문자열', ''],
    ['공백만', '   '],
    ['미설정', undefined],
  ])('%s이면 켜지 않는다', (_label, dsn) => {
    expect(shouldEnableMonitoring(dsn)).toBe(false)
  })

  it('DSN이 있으면 켠다', () => {
    expect(shouldEnableMonitoring('https://key@o0.ingest.sentry.io/1')).toBe(true)
  })
})

describe('initMonitoring', () => {
  it('DSN이 없으면 Sentry를 초기화하지 않는다 — 키 없이도 화면은 떠야 한다', () => {
    vi.stubEnv('VITE_SENTRY_DSN', '')

    expect(initMonitoring(app)).toBe(false)
    expect(initSpy).not.toHaveBeenCalled()
  })

  it('DSN이 있으면 초기화하고 켰다고 알린다', () => {
    vi.stubEnv('VITE_SENTRY_DSN', 'https://key@o0.ingest.sentry.io/1')

    expect(initMonitoring(app)).toBe(true)
    expect(initSpy).toHaveBeenCalledTimes(1)
    expect(initOptions()).toMatchObject({ dsn: 'https://key@o0.ingest.sentry.io/1' })
  })

  it('PII를 보내지 않는다 — 켜지면 로그인 이메일이 Sentry로 나간다', () => {
    vi.stubEnv('VITE_SENTRY_DSN', 'https://key@o0.ingest.sentry.io/1')

    initMonitoring(app)

    expect(initOptions().sendDefaultPii).toBe(false)
  })

  it('트레이싱·리플레이를 켜지 않는다 — 무료 티어의 제약은 월 이벤트 총량이다', () => {
    vi.stubEnv('VITE_SENTRY_DSN', 'https://key@o0.ingest.sentry.io/1')

    initMonitoring(app)

    const options = initOptions()
    expect(options.tracesSampleRate).toBeUndefined()
    expect(options.replaysSessionSampleRate).toBeUndefined()
    // integrations를 지정하지 않아야 기본 집합(오류 관련)만 붙는다.
    expect(options.integrations).toBeUndefined()
  })

  it('release를 손으로 적지 않는다 — 번들과 소스맵이 같은 값을 써야 매핑된다', () => {
    vi.stubEnv('VITE_SENTRY_DSN', 'https://key@o0.ingest.sentry.io/1')

    initMonitoring(app)

    expect(initOptions().release).toBeUndefined()
  })
})
