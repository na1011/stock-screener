import { createPinia, setActivePinia } from 'pinia'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import { apiFetch } from '@/api/http'

import { installUnauthorizedHandler, useAuthStore } from './auth'

/**
 * 세션 상태의 규칙.
 *
 * 이 파일이 없어서 결함이 드러나지 않았다 — 401이 로그인 안내로 이어지는 사슬을 검증하는
 * 테스트가 프론트·백 어디에도 없었고, 그 사슬은 **마지막 고리가 끊긴 채** 있었다.
 * `markSignedOut()`이 상태만 바꾸고 "이동은 라우터 가드가 한다"고 주석에 적혀 있었는데,
 * 그 가드는 라우트가 바뀔 때만 돈다. 같은 화면에서의 API 호출에는 아무도 반응하지 않았다.
 */
const ME = { userId: 1, email: 'someone@example.com', name: '홍길동', admin: false }

/** 서버의 401은 `HttpStatusEntryPoint`가 상태만 세운 것이라 **본문이 없다.** */
function unauthorized(): Response {
  return new Response(null, { status: 401 })
}

function ok(body: unknown): Response {
  return new Response(JSON.stringify(body), {
    status: 200,
    headers: { 'content-type': 'application/json' },
  })
}

function mockFetch(...responses: Response[]): void {
  const fetchMock = vi.fn()
  for (const response of responses) {
    fetchMock.mockResolvedValueOnce(response)
  }
  vi.stubGlobal('fetch', fetchMock)
}

beforeEach(() => {
  setActivePinia(createPinia())
})

afterEach(() => {
  vi.unstubAllGlobals()
  // 전역 하나를 여러 테스트가 공유한다. 남겨 두면 다음 파일의 401이 죽은 스토어를 건드린다.
  installUnauthorizedHandler()
})

describe('만료와 "처음부터 익명"을 가른다', () => {
  it('부팅 프로브가 401이어도 만료라고 하지 않는다', async () => {
    // IMPORTANT — 이 구분이 없으면 로그인하지 않은 사용자가 로그인 화면 위에서 "로그인이
    // 만료되었습니다"를 보게 된다. 만료된 적이 없는데도.
    mockFetch(unauthorized())
    const auth = useAuthStore()

    await auth.probe()

    expect(auth.status).toBe('anonymous')
    expect(auth.sessionExpired).toBe(false)
  })

  it('쓰던 중 어느 요청에서든 401이 나면 만료로 알린다', async () => {
    mockFetch(ok(ME), unauthorized())
    const auth = useAuthStore()
    installUnauthorizedHandler()
    await auth.probe()
    expect(auth.isAuthenticated).toBe(true)

    // 라우트를 바꾸지 않는 평범한 요청. 예전에는 여기서 토스트만 떴다.
    await expect(apiFetch('/api/screens')).rejects.toThrow()

    expect(auth.sessionExpired).toBe(true)
    expect(auth.isAuthenticated).toBe(false)
  })

  it('세션 프로브의 401은 전역 처리기를 거치지 않는다', async () => {
    // `/api/me`가 `notifyUnauthorized: false`인 것이 위 두 경우를 가르는 유일한 장치다.
    // 그 플래그가 사라지면 부팅 때마다 만료 안내가 뜬다.
    mockFetch(unauthorized())
    const auth = useAuthStore()
    installUnauthorizedHandler()

    await auth.probe()

    expect(auth.sessionExpired).toBe(false)
  })
})

describe('revalidate — 탭으로 돌아왔을 때', () => {
  it('로그인해 있던 세션이 끊겼으면 만료다', async () => {
    mockFetch(ok(ME), unauthorized())
    const auth = useAuthStore()
    await auth.probe()

    await auth.revalidate()

    expect(auth.sessionExpired).toBe(true)
  })

  it('처음부터 익명이었으면 만료가 아니다', async () => {
    mockFetch(unauthorized(), unauthorized())
    const auth = useAuthStore()
    await auth.probe()

    await auth.revalidate()

    expect(auth.sessionExpired).toBe(false)
  })

  it('다른 탭에서 다시 로그인했으면 안내를 거둔다', async () => {
    // 안내가 남아 있으면 이미 살아난 세션을 두고 사용자가 로그인을 반복한다.
    mockFetch(ok(ME), unauthorized(), ok(ME))
    const auth = useAuthStore()
    await auth.probe()
    await auth.revalidate()
    expect(auth.sessionExpired).toBe(true)

    await auth.revalidate()

    expect(auth.sessionExpired).toBe(false)
    expect(auth.isAuthenticated).toBe(true)
  })

  it('401이 아닌 실패는 만료가 아니다', async () => {
    // `anonymous`와 `unavailable`을 합치지 않는 이유와 같다 — 서버가 답하지 못했을 때
    // "로그인하세요"를 띄우면 사용자는 원인이 자기 계정에 있다고 읽는다.
    // 5xx가 아니라 400을 쓰는 것은 그쪽이 자동 재시도 대상이라 mock을 세 번 태우기 때문이다.
    mockFetch(ok(ME), new Response(null, { status: 400 }))
    const auth = useAuthStore()
    await auth.probe()

    await auth.revalidate()

    expect(auth.status).toBe('unavailable')
    expect(auth.sessionExpired).toBe(false)
  })
})
