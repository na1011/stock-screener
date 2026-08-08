/**
 * HTTP 계약을 지키는 한 곳.
 *
 * 여기에 모으는 이유는 셋 다 빠뜨리기 쉽고 빠뜨렸을 때의 증상이 원인을 가리지 않기 때문이다.
 * - **CSRF 토큰** — 없으면 403이 나오는데 증상이 인증 버그처럼 보인다.
 * - **재시도 판별** — 상태 코드로 해야 한다. 바디의 `retryable`에만 기대면 바디 없는 응답에서
 *   판별이 깨진다(ADR-0015).
 * - **problem+json 파싱** — 분기 대상은 `type`(URN)이지 한글 메시지가 아니다.
 */

/** RFC 7807 본문. 타입별 추가 속성이 붙으므로 인덱스 시그니처를 연다. */
export interface ApiProblem {
  type: string
  title?: string
  status?: number
  detail?: string
  retryable?: boolean
  [key: string]: unknown
}

/** 오류 종류의 안정적 식별자. 백엔드 `ApiProblemType`과 1:1이다. */
export const ProblemType = {
  INVALID_CONDITION: 'urn:stockscreener:invalid-condition',
  UNKNOWN_INDICATOR: 'urn:stockscreener:unknown-indicator',
  INVALID_REQUEST: 'urn:stockscreener:invalid-request',
  AMBIGUOUS_SCREENING_INPUT: 'urn:stockscreener:ambiguous-screening-input',
  INDICATOR_HISTORY_UNAVAILABLE: 'urn:stockscreener:indicator-history-unavailable',
  INDICATOR_DATA_NOT_READY: 'urn:stockscreener:indicator-data-not-ready',
  SCREEN_NOT_FOUND: 'urn:stockscreener:screen-not-found',
  STOCK_NOT_FOUND: 'urn:stockscreener:stock-not-found',
  UNIVERSE_NOT_FOUND: 'urn:stockscreener:universe-not-found',
  PRESET_READ_ONLY: 'urn:stockscreener:preset-is-read-only',
  DUPLICATE_SCREEN_NAME: 'urn:stockscreener:duplicate-screen-name',
  SCREEN_IN_USE: 'urn:stockscreener:screen-in-use',
  WATCHLIST_ITEM_NOT_FOUND: 'urn:stockscreener:watchlist-item-not-found',
  ALREADY_WATCHED: 'urn:stockscreener:already-watched',
  CONFLICT: 'urn:stockscreener:conflict',
} as const

/** 네트워크 자체가 실패해 상태 코드가 없는 경우. */
export const NETWORK_ERROR_STATUS = 0

/**
 * 그때 사용자에게 보이는 말. **여기가 정본이다.**
 *
 * `LoginView`가 같은 문장을 따로 적어 두고 그 아래에 `error.userMessage`를 detail로 깔고
 * 있었다 — 네트워크 오류에서는 **같은 문장이 두 줄로** 나왔다. 거울을 없애고, 아래 줄은
 * 이것과 다를 때만 쓰게 한다.
 */
export const NETWORK_ERROR_MESSAGE = '서버에 연결하지 못했습니다.'

/**
 * 보존 기간 밖 오류가 함께 싣는 구간.
 *
 * IMPORTANT — 이 두 필드 이름은 **양쪽 컴파일러 모두에게 보이지 않는다.** 서버에서는
 * `ApiExceptions.IndicatorHistoryUnavailableException`이 `with("retentionFrom", …)`으로 문자열
 * 키를 얹고, 프론트에서는 `ApiProblem`의 인덱스 시그니처(`[key: string]: unknown`)로 꺼낸다.
 * 한쪽에서 이름을 바꾸면 날짜 선택기의 범위가 **아무 오류 없이** 사라지고, 사용자는 같은
 * 오류를 만나며 날짜를 하나씩 다시 찍는다 — 그 필드가 막으려던 바로 그 상황이다.
 *
 * 그래서 URN 옆, grep 되는 한 곳에 둔다. 이름의 정본은 서버이고 `wire-contract.json`이 둘을 묶는다.
 */
export interface RetentionWindow {
  from: string
  to: string
}

const RETENTION_FROM = 'retentionFrom'
const RETENTION_TO = 'retentionTo'

/** @return 구간을 알 수 없으면 null. 그때는 날짜 선택기를 좁히지 않는다. */
export function retentionWindow(problem: ApiProblem | null): RetentionWindow | null {
  const from = problem?.[RETENTION_FROM]
  const to = problem?.[RETENTION_TO]
  return typeof from === 'string' && typeof to === 'string' ? { from, to } : null
}

/**
 * 조건 검증 오류가 함께 싣는, **몇 번째 조건이 문제인가**.
 *
 * IMPORTANT — 위 `retentionFrom`/`retentionTo`와 같은 종류의 위험이다. 서버는
 * `ApiExceptions.ConditionValidationException`이 `with("conditionIndex", …)`로 문자열 키를
 * 얹고 여기서는 인덱스 시그니처로 꺼낸다. 어긋나면 오류는 그대로 나되 **어느 조건이 문제인지만
 * 조용히 사라진다** — 사용자는 조건 스무 개 중 무엇을 고쳐야 하는지 알 수 없게 된다.
 *
 * 이름의 정본은 서버이고 `wire-contract.json`이 둘을 묶는다.
 */
const CONDITION_INDEX = 'conditionIndex'

/** @return 인덱스를 알 수 없으면 null. 그때는 어떤 칩도 붉게 칠하지 않는다. */
export function conditionIndex(problem: ApiProblem | null): number | null {
  const index = problem?.[CONDITION_INDEX]
  return typeof index === 'number' && Number.isInteger(index) && index >= 0 ? index : null
}

export class ApiError extends Error {
  readonly status: number
  readonly problem: ApiProblem | null
  readonly retryable: boolean

  constructor(status: number, problem: ApiProblem | null, message: string) {
    super(message)
    this.name = 'ApiError'
    this.status = status
    this.problem = problem
    this.retryable = isRetryable(status, problem)
  }

  /** 오류 종류 판별. 한글 메시지가 아니라 이 값으로 분기한다. */
  is(type: string): boolean {
    return this.problem?.type === type
  }

  /** 화면에 보여줄 문구. 서버가 준 설명이 있으면 그것을 쓴다. */
  get userMessage(): string {
    return this.problem?.detail ?? this.problem?.title ?? this.message
  }
}

/**
 * 자동 재시도 대상인가.
 *
 * IMPORTANT — 상태 코드가 1차 판별이고 바디는 있을 때만 덮어쓴다. 순서를 뒤집으면 바디 없는
 * 5xx(프록시가 끊었을 때가 그렇다)에서 재시도가 통째로 사라진다.
 *
 * 401은 어떤 바디가 와도 재시도하지 않는다 — 재인증이 필요한 상태라 같은 요청을 반복하면
 * 같은 401만 쌓인다.
 */
export function isRetryable(status: number, problem: ApiProblem | null): boolean {
  if (status === 401) {
    return false
  }
  if (problem && typeof problem.retryable === 'boolean') {
    return problem.retryable
  }
  return isRetryableStatus(status)
}

export function isRetryableStatus(status: number): boolean {
  return status === NETWORK_ERROR_STATUS || status >= 500 || status === 408 || status === 429
}

/** 쿠키 하나를 읽는다. `XSRF-TOKEN`은 HttpOnly가 아니어서 읽을 수 있다. */
export function readCookie(name: string): string | null {
  if (typeof document === 'undefined') {
    return null
  }
  const prefix = `${name}=`
  for (const raw of document.cookie.split(';')) {
    const part = raw.trim()
    if (part.startsWith(prefix)) {
      return decodeURIComponent(part.slice(prefix.length))
    }
  }
  return null
}

export type HttpMethod = 'GET' | 'POST' | 'PUT' | 'DELETE'

export interface ApiRequest {
  method?: HttpMethod
  body?: unknown
  query?: Record<string, string | number | null | undefined>
  signal?: AbortSignal
  /**
   * 재시도 간격(ms). 길이가 곧 재시도 횟수다.
   *
   * IMPORTANT — 기본값이 GET에만 붙는다. 생성·수정 요청을 자동 재시도하면 서버가 이미 처리한
   * 뒤 응답만 유실된 경우 같은 전략이 두 개 만들어진다. 조회인데 POST인 요청
   * (`/api/screening`)은 호출부가 명시적으로 켠다.
   */
  retryDelaysMs?: number[]
  /** 401일 때 전역 처리기를 부를지. 세션 프로브(`/api/me`)는 끈다 — 그것이 정상 경로다. */
  notifyUnauthorized?: boolean
}

const DEFAULT_RETRY_DELAYS_MS = [300, 900]

/** 조회성 POST가 재시도를 켤 때 쓴다. */
export const READ_RETRY_DELAYS_MS = DEFAULT_RETRY_DELAYS_MS

let unauthorizedHandler: (() => void) | null = null

/**
 * 세션이 끊겼을 때 불릴 처리기. 인증 스토어가 등록한다.
 *
 * 전역 하나로 두는 이유는 401이 어느 요청에서든 나올 수 있고, 그때마다 호출부가 로그인
 * 화면으로 보내는 코드를 반복하면 한 곳을 빠뜨렸을 때 그 화면만 빈 채로 남기 때문이다.
 */
export function setUnauthorizedHandler(handler: (() => void) | null): void {
  unauthorizedHandler = handler
}

export async function apiFetch<T>(path: string, request: ApiRequest = {}): Promise<T> {
  const method = request.method ?? 'GET'
  const delays = request.retryDelaysMs ?? (method === 'GET' ? DEFAULT_RETRY_DELAYS_MS : [])
  const url = buildUrl(path, request.query)

  let attempt = 0
  for (;;) {
    try {
      return await send<T>(url, method, request)
    } catch (error) {
      const apiError = error instanceof ApiError ? error : rethrowUnexpected(error)
      const delay = delays[attempt]
      if (!apiError.retryable || delay === undefined) {
        throw apiError
      }
      attempt += 1
      await sleep(jitter(delay))
    }
  }
}

function rethrowUnexpected(error: unknown): never {
  // AbortError처럼 우리가 만들지 않은 예외는 그대로 올린다. 여기서 ApiError로 감싸면
  // 취소가 "재시도 가능한 오류"로 둔갑해 이미 떠난 화면의 요청이 되살아난다.
  throw error
}

async function send<T>(url: string, method: HttpMethod, request: ApiRequest): Promise<T> {
  const headers: Record<string, string> = { Accept: 'application/json' }

  if (request.body !== undefined) {
    headers['Content-Type'] = 'application/json'
  }
  if (method !== 'GET') {
    const token = readCookie('XSRF-TOKEN')
    if (token !== null) {
      headers['X-XSRF-TOKEN'] = token
    }
  }

  let response: Response
  try {
    response = await fetch(url, {
      method,
      headers,
      // 세션 쿠키를 실어야 한다. 같은 오리진 전제이므로 include가 아니라 same-origin이다.
      credentials: 'same-origin',
      body: request.body === undefined ? undefined : JSON.stringify(request.body),
      signal: request.signal,
    })
  } catch (error) {
    if (error instanceof DOMException && error.name === 'AbortError') {
      throw error
    }
    throw new ApiError(NETWORK_ERROR_STATUS, null, NETWORK_ERROR_MESSAGE)
  }

  if (!response.ok) {
    const problem = await readProblem(response)
    if (response.status === 401 && (request.notifyUnauthorized ?? true)) {
      unauthorizedHandler?.()
    }
    throw new ApiError(response.status, problem, problem?.detail ?? `요청이 실패했습니다 (${response.status}).`)
  }

  if (response.status === 204) {
    return undefined as T
  }
  return (await response.json()) as T
}

async function readProblem(response: Response): Promise<ApiProblem | null> {
  const contentType = response.headers.get('content-type') ?? ''
  if (!contentType.includes('json')) {
    return null
  }
  try {
    const parsed: unknown = await response.json()
    if (parsed !== null && typeof parsed === 'object' && 'type' in parsed) {
      return parsed as ApiProblem
    }
    return null
  } catch {
    // 본문이 JSON이라고 했는데 파싱되지 않으면 그 사실만 남기고 상태 코드로 판별한다.
    // 여기서 예외를 삼키지 않으면 원래 오류(상태 코드)가 파싱 오류에 가려진다.
    return null
  }
}

function buildUrl(path: string, query?: Record<string, string | number | null | undefined>): string {
  if (!query) {
    return path
  }
  const params = new URLSearchParams()
  for (const [key, value] of Object.entries(query)) {
    if (value !== null && value !== undefined && value !== '') {
      params.set(key, String(value))
    }
  }
  const queryString = params.toString()
  return queryString.length === 0 ? path : `${path}?${queryString}`
}

/** 여러 화면이 동시에 실패했을 때 재시도가 한꺼번에 몰리지 않게 흩는다. */
function jitter(delayMs: number): number {
  if (delayMs <= 0) {
    return 0
  }
  return Math.round(delayMs * (0.7 + Math.random() * 0.6))
}

function sleep(ms: number): Promise<void> {
  if (ms <= 0) {
    return Promise.resolve()
  }
  return new Promise((resolve) => setTimeout(resolve, ms))
}
