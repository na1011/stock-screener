import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import {
  ApiError,
  NETWORK_ERROR_STATUS,
  ProblemType,
  apiFetch,
  isRetryable,
  readCookie,
  retentionWindow,
  setUnauthorizedHandler,
} from './http'

/** 재시도 지연을 0으로 눌러 테스트가 실제로 기다리지 않게 한다. */
const NO_WAIT = [0, 0]

function jsonResponse(status: number, body: unknown, contentType = 'application/problem+json'): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'content-type': contentType },
  })
}

interface FetchCall {
  url: string
  init: { headers: Record<string, string>; credentials?: string; body?: string }
}

/** n번째 fetch 호출. 호출되지 않았으면 인덱스 오류가 아니라 이유를 남기고 실패한다. */
function fetchCall(mock: ReturnType<typeof vi.fn>, index = 0): FetchCall {
  const call = mock.mock.calls[index]
  if (call === undefined) {
    throw new Error(`fetch가 ${index + 1}번 호출되지 않았다 (실제 ${mock.mock.calls.length}번)`)
  }
  return { url: call[0] as string, init: call[1] as FetchCall['init'] }
}

describe('isRetryable — 상태 코드가 1차 판별이다', () => {
  it.each([500, 502, 503, 408, 429, NETWORK_ERROR_STATUS])('%i은 재시도 대상이다', (status) => {
    expect(isRetryable(status, null)).toBe(true)
  })

  it.each([400, 403, 404, 409, 422])('%i은 사용자 조치라 재시도하지 않는다', (status) => {
    expect(isRetryable(status, null)).toBe(false)
  })

  it('401은 재인증이 필요하므로 재시도하지 않는다', () => {
    expect(isRetryable(401, null)).toBe(false)
  })

  it('401은 바디가 retryable을 참으로 줘도 재시도하지 않는다', () => {
    // 반복해도 같은 401만 쌓인다.
    expect(isRetryable(401, { type: 'urn:x', retryable: true })).toBe(false)
  })

  it('바디의 retryable이 있으면 상태 코드를 덮어쓴다', () => {
    // 503 + INDICATOR_DATA_NOT_READY가 실제 사례다(배치 전이라 아직 준비되지 않았다).
    expect(isRetryable(503, { type: ProblemType.INDICATOR_DATA_NOT_READY, retryable: true })).toBe(true)
    expect(isRetryable(500, { type: 'urn:x', retryable: false })).toBe(false)
  })

  it('바디가 없어도 판별이 성립한다', () => {
    // 프록시가 끊으면 본문 없는 5xx가 온다. 바디에만 기대면 여기서 재시도가 사라진다.
    expect(isRetryable(503, null)).toBe(true)
  })
})

describe('readCookie', () => {
  const original = globalThis.document

  afterEach(() => {
    if (original === undefined) {
      Reflect.deleteProperty(globalThis, 'document')
    } else {
      Object.defineProperty(globalThis, 'document', { value: original, configurable: true })
    }
  })

  function setCookie(value: string): void {
    Object.defineProperty(globalThis, 'document', {
      value: { cookie: value },
      configurable: true,
    })
  }

  it('첫 번째 쿠키를 읽는다', () => {
    setCookie('XSRF-TOKEN=abc123; JSESSIONID=zzz')
    expect(readCookie('XSRF-TOKEN')).toBe('abc123')
  })

  it('중간에 있는 쿠키를 읽는다', () => {
    setCookie('JSESSIONID=zzz; XSRF-TOKEN=abc123')
    expect(readCookie('XSRF-TOKEN')).toBe('abc123')
  })

  it('접두가 겹치는 다른 쿠키를 잘못 읽지 않는다', () => {
    setCookie('XSRF-TOKEN-OLD=wrong; XSRF-TOKEN=right')
    expect(readCookie('XSRF-TOKEN')).toBe('right')
  })

  it('없으면 null이다', () => {
    setCookie('JSESSIONID=zzz')
    expect(readCookie('XSRF-TOKEN')).toBeNull()
  })
})

describe('apiFetch', () => {
  let fetchMock: ReturnType<typeof vi.fn>

  beforeEach(() => {
    fetchMock = vi.fn()
    vi.stubGlobal('fetch', fetchMock)
    Object.defineProperty(globalThis, 'document', {
      value: { cookie: 'XSRF-TOKEN=token-value' },
      configurable: true,
    })
    setUnauthorizedHandler(null)
  })

  afterEach(() => {
    vi.unstubAllGlobals()
    Reflect.deleteProperty(globalThis, 'document')
    setUnauthorizedHandler(null)
  })

  it('GET에는 CSRF 헤더를 붙이지 않는다', async () => {
    fetchMock.mockResolvedValue(jsonResponse(200, { ok: true }, 'application/json'))

    await apiFetch('/api/indicators')

    const { init } = fetchCall(fetchMock)
    expect(init.headers['X-XSRF-TOKEN']).toBeUndefined()
    expect(init.credentials).toBe('same-origin')
  })

  it('POST에는 쿠키에서 읽은 CSRF 토큰을 헤더로 싣는다', async () => {
    // 없으면 403이 나오는데 증상이 인증 버그처럼 보인다.
    fetchMock.mockResolvedValue(jsonResponse(200, { ok: true }, 'application/json'))

    await apiFetch('/api/screening', { method: 'POST', body: { presetCode: 'VALUE' } })

    const { init } = fetchCall(fetchMock)
    expect(init.headers['X-XSRF-TOKEN']).toBe('token-value')
    expect(init.headers['Content-Type']).toBe('application/json')
    expect(init.body).toBe('{"presetCode":"VALUE"}')
  })

  it('problem+json을 파싱해 type으로 분기할 수 있게 한다', async () => {
    fetchMock.mockResolvedValue(
      jsonResponse(400, {
        type: ProblemType.INDICATOR_HISTORY_UNAVAILABLE,
        detail: '보존 기간 밖이다',
        retentionFrom: '2025-11-11',
        retentionTo: '2026-08-05',
      }),
    )

    const error = await apiFetch('/api/screening', { method: 'POST', body: {} }).catch((e: unknown) => e)

    expect(error).toBeInstanceOf(ApiError)
    const apiError = error as ApiError
    expect(apiError.is(ProblemType.INDICATOR_HISTORY_UNAVAILABLE)).toBe(true)
    expect(apiError.problem?.retentionFrom).toBe('2025-11-11')
    expect(apiError.userMessage).toBe('보존 기간 밖이다')
    expect(apiError.retryable).toBe(false)
  })

  it('재시도 대상이면 지정한 횟수만큼 다시 보낸다', async () => {
    fetchMock
      .mockResolvedValueOnce(jsonResponse(503, { type: 'urn:x', retryable: true }))
      .mockResolvedValueOnce(jsonResponse(503, { type: 'urn:x', retryable: true }))
      .mockResolvedValueOnce(jsonResponse(200, { ok: true }, 'application/json'))

    const result = await apiFetch<{ ok: boolean }>('/api/indicators', { retryDelaysMs: NO_WAIT })

    expect(result.ok).toBe(true)
    expect(fetchMock).toHaveBeenCalledTimes(3)
  })

  it('재시도 대상이 아니면 즉시 포기한다', async () => {
    fetchMock.mockResolvedValue(jsonResponse(400, { type: ProblemType.INVALID_CONDITION }))

    await expect(apiFetch('/api/indicators', { retryDelaysMs: NO_WAIT })).rejects.toBeInstanceOf(ApiError)
    expect(fetchMock).toHaveBeenCalledTimes(1)
  })

  it('기본값은 GET만 재시도한다 — 생성 요청을 자동 재시도하면 전략이 두 개 만들어진다', async () => {
    fetchMock.mockResolvedValue(jsonResponse(503, null))

    await expect(apiFetch('/api/screens', { method: 'POST', body: {} })).rejects.toBeInstanceOf(ApiError)
    expect(fetchMock).toHaveBeenCalledTimes(1)
  })

  it('네트워크 자체가 실패하면 재시도한다', async () => {
    fetchMock
      .mockRejectedValueOnce(new TypeError('Failed to fetch'))
      .mockResolvedValueOnce(jsonResponse(200, { ok: true }, 'application/json'))

    const result = await apiFetch<{ ok: boolean }>('/api/indicators', { retryDelaysMs: NO_WAIT })

    expect(result.ok).toBe(true)
    expect(fetchMock).toHaveBeenCalledTimes(2)
  })

  it('401이면 전역 처리기를 부른다', async () => {
    const handler = vi.fn()
    setUnauthorizedHandler(handler)
    fetchMock.mockResolvedValue(new Response(null, { status: 401 }))

    await expect(apiFetch('/api/screens')).rejects.toBeInstanceOf(ApiError)

    expect(handler).toHaveBeenCalledTimes(1)
  })

  it('세션 프로브의 401은 전역 처리기를 부르지 않는다', async () => {
    // /api/me의 401은 "아직 로그인하지 않았다"는 정상 경로다. 여기서 처리기를 부르면
    // 로그인 화면을 그리는 도중에 다시 로그인 화면으로 보내는 순환이 생긴다.
    const handler = vi.fn()
    setUnauthorizedHandler(handler)
    fetchMock.mockResolvedValue(new Response(null, { status: 401 }))

    await expect(apiFetch('/api/me', { notifyUnauthorized: false })).rejects.toBeInstanceOf(ApiError)

    expect(handler).not.toHaveBeenCalled()
  })

  it('204는 본문 없이 끝난다', async () => {
    fetchMock.mockResolvedValue(new Response(null, { status: 204 }))

    await expect(apiFetch('/api/screens/1', { method: 'DELETE' })).resolves.toBeUndefined()
  })

  it('빈 값인 쿼리 파라미터는 보내지 않는다', async () => {
    fetchMock.mockResolvedValue(jsonResponse(200, {}, 'application/json'))

    await apiFetch('/api/stocks/1', { query: { asOf: null } })

    expect(fetchCall(fetchMock).url).toBe('/api/stocks/1')
  })

  it('쿼리 파라미터가 있으면 붙인다', async () => {
    fetchMock.mockResolvedValue(jsonResponse(200, {}, 'application/json'))

    await apiFetch('/api/stocks/1', { query: { asOf: '2026-08-05' } })

    expect(fetchCall(fetchMock).url).toBe('/api/stocks/1?asOf=2026-08-05')
  })

  it('취소는 오류가 아니라 취소로 올라간다', async () => {
    // ApiError로 감싸면 취소가 "재시도 가능한 오류"가 되어 이미 떠난 화면의 요청이 되살아난다.
    fetchMock.mockRejectedValue(new DOMException('aborted', 'AbortError'))

    const error = await apiFetch('/api/indicators', { retryDelaysMs: NO_WAIT }).catch((e: unknown) => e)

    expect(error).toBeInstanceOf(DOMException)
    expect(fetchMock).toHaveBeenCalledTimes(1)
  })
})

describe('retentionWindow — 보존 구간 필드명이 여기 한 곳에만 있다', () => {
  const problem = {
    type: ProblemType.INDICATOR_HISTORY_UNAVAILABLE,
    detail: '보존 구간 밖이다',
    requestedAsOf: '2025-01-02',
    retentionFrom: '2025-11-11',
    retentionTo: '2026-08-05',
  }

  it('서버가 실은 구간을 꺼낸다', () => {
    expect(retentionWindow(problem)).toEqual({ from: '2025-11-11', to: '2026-08-05' })
  })

  it('필드가 없으면 null이다 — 없는 범위를 지어내지 않는다', () => {
    // 서버가 필드 이름을 바꾸면 여기로 온다. 그때 날짜 선택기는 좁혀지지 않을 뿐
    // 잘못된 범위로 잠기지는 않는다. 이름이 어긋났다는 사실 자체는
    // wire-contract 픽스처가 잡는다 — 이 함수만으로는 조용하다.
    expect(retentionWindow({ type: ProblemType.INDICATOR_HISTORY_UNAVAILABLE })).toBeNull()
    expect(retentionWindow({ ...problem, retentionTo: 42 })).toBeNull()
    expect(retentionWindow(null)).toBeNull()
  })
})
