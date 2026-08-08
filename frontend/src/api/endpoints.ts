import { apiFetch, READ_RETRY_DELAYS_MS } from './http'
import type {
  AdminJob,
  AllowedEmail,
  AllowedEmailRequest,
  AllowedEmailUpdateRequest,
  BatchExecution,
  BatchExecutionDetail,
  BatchStatus,
  BatchSummary,
  CatalogResponse,
  InteractionAction,
  InteractionActor,
  InteractionLogEntry,
  IsoDate,
  JobLaunchResponse,
  JobRunRequest,
  MeResponse,
  PriceReference,
  ScreenRequest,
  ScreenResponse,
  ScreeningRequest,
  ScreeningResponse,
  StockDetailResponse,
  StockSearchResult,
  UniverseResponse,
  WatchlistItem,
  WatchlistRequest,
} from './types'

/**
 * 엔드포인트별 얇은 함수. 여기서만 경로 문자열을 안다.
 *
 * 재시도 정책이 함수마다 다른 것이 의도다 — 조회는 자동 재시도하고 생성·수정은 하지 않는다.
 * 자세한 이유는 {@link ApiRequest.retryDelaysMs}에 있다.
 */
export const api = {
  /** 세션 프로브. 익명이면 401이고 그것이 정상 경로이므로 전역 401 처리기를 부르지 않는다. */
  me: () => apiFetch<MeResponse>('/api/me', { notifyUnauthorized: false }),

  catalog: (signal?: AbortSignal) => apiFetch<CatalogResponse>('/api/indicators', { signal }),

  universes: (signal?: AbortSignal) => apiFetch<UniverseResponse[]>('/api/universes', { signal }),

  /** 목록의 `conditions`는 항상 null이다. 조건이 필요하면 {@link screen}을 부른다. */
  screens: (signal?: AbortSignal) => apiFetch<ScreenResponse[]>('/api/screens', { signal }),

  screen: (id: number, signal?: AbortSignal) =>
    apiFetch<ScreenResponse>(`/api/screens/${id}`, { signal }),

  createScreen: (body: ScreenRequest) =>
    apiFetch<ScreenResponse>('/api/screens', { method: 'POST', body }),

  /** 조건을 통째로 교체한다. 부분 패치는 없다. */
  updateScreen: (id: number, body: ScreenRequest) =>
    apiFetch<ScreenResponse>(`/api/screens/${id}`, { method: 'PUT', body }),

  deleteScreen: (id: number) => apiFetch<void>(`/api/screens/${id}`, { method: 'DELETE' }),

  /**
   * 스크리닝 실행.
   *
   * GET이 아니라 POST인 이유는 조건 트리를 쿼리스트링에 담을 수 없어서다 — 부수 효과가 없는
   * 조회이므로 자동 재시도를 켠다. CSRF 토큰은 {@code apiFetch}가 붙인다.
   */
  screening: (body: ScreeningRequest, signal?: AbortSignal) =>
    apiFetch<ScreeningResponse>('/api/screening', {
      method: 'POST',
      body,
      signal,
      retryDelaysMs: READ_RETRY_DELAYS_MS,
    }),

  /** 종목명·티커 검색. 일치가 없으면 빈 배열이다(404가 아니다). */
  searchStocks: (query: string, signal?: AbortSignal) =>
    apiFetch<StockSearchResult[]>('/api/stocks', { query: { q: query }, signal }),

  stock: (stockId: number, asOf?: IsoDate | null, signal?: AbortSignal) =>
    apiFetch<StockDetailResponse>(`/api/stocks/${stockId}`, { query: { asOf }, signal }),

  /** 지지·저항. 종목 상세와 **별도 요청**이다 — 한쪽이 실패해도 다른 쪽은 산다(ADR-0014). */
  priceReference: (stockId: number, asOf?: IsoDate | null, signal?: AbortSignal) =>
    apiFetch<PriceReference>(`/api/stocks/${stockId}/price-reference`, { query: { asOf }, signal }),

  watchlist: (signal?: AbortSignal) => apiFetch<WatchlistItem[]>('/api/watchlist', { signal }),

  /**
   * 담기·수정·해제는 자동 재시도하지 않는다.
   *
   * 생성을 재시도하면 응답만 유실된 경우 같은 항목을 두 번 만들려 하고, 그때 서버는
   * 409(ALREADY_WATCHED)를 준다 — 사용자는 담기지 않은 줄 알게 된다.
   */
  addToWatchlist: (body: WatchlistRequest) =>
    apiFetch<WatchlistItem>('/api/watchlist', { method: 'POST', body }),

  updateWatchlistItem: (id: number, body: WatchlistRequest) =>
    apiFetch<WatchlistItem>(`/api/watchlist/${id}`, { method: 'PUT', body }),

  removeFromWatchlist: (id: number) => apiFetch<void>(`/api/watchlist/${id}`, { method: 'DELETE' }),

  // ── 관리자 ──────────────────────────────────────────────────────
  //
  // 권한은 서버가 강제한다(`/api/admin/**`는 ROLE_ADMIN). 화면이 항목을 감추는 것은 존재를
  // 드러내지 않기 위해서이지 그것이 인가 수단이어서가 아니다.

  adminBatches: (
    query: { from?: IsoDate | null; to?: IsoDate | null; jobName?: string | null; status?: BatchStatus | null },
    signal?: AbortSignal,
  ) => apiFetch<BatchExecution[]>('/api/admin/batches', { query, signal }),

  adminBatchSummary: (signal?: AbortSignal) =>
    apiFetch<BatchSummary>('/api/admin/batches/summary', { signal }),

  adminBatch: (id: number, signal?: AbortSignal) =>
    apiFetch<BatchExecutionDetail>(`/api/admin/batches/${id}`, { signal }),

  /**
   * 실패분 재실행. 자동 재시도를 켜지 않는다 — 응답만 유실된 경우 같은 재실행이 두 번 뜬다.
   */
  retryBatch: (id: number) =>
    apiFetch<JobLaunchResponse>(`/api/admin/batches/${id}/retry`, { method: 'POST' }),

  adminJobs: (signal?: AbortSignal) => apiFetch<AdminJob[]>('/api/admin/jobs', { signal }),

  /** 202로 즉시 돌아온다. 새 실행은 목록을 새로 고쳐 확인한다(ADR-0029). */
  runJob: (jobName: string, body: JobRunRequest) =>
    apiFetch<JobLaunchResponse>(`/api/admin/jobs/${jobName}/run`, { method: 'POST', body }),

  allowedEmails: (signal?: AbortSignal) =>
    apiFetch<AllowedEmail[]>('/api/admin/allowed-emails', { signal }),

  addAllowedEmail: (body: AllowedEmailRequest) =>
    apiFetch<AllowedEmail>('/api/admin/allowed-emails', { method: 'POST', body }),

  updateAllowedEmail: (id: number, body: AllowedEmailUpdateRequest) =>
    apiFetch<AllowedEmail>(`/api/admin/allowed-emails/${id}`, { method: 'PUT', body }),

  removeAllowedEmail: (id: number) =>
    apiFetch<void>(`/api/admin/allowed-emails/${id}`, { method: 'DELETE' }),

  adminInteractions: (
    query: {
      from?: IsoDate | null
      to?: IsoDate | null
      userId?: number | null
      action?: InteractionAction | null
    },
    signal?: AbortSignal,
  ) => apiFetch<InteractionLogEntry[]>('/api/admin/interactions', { query, signal }),

  adminInteractionActors: (signal?: AbortSignal) =>
    apiFetch<InteractionActor[]>('/api/admin/interactions/actors', { signal }),
}
