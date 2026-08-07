import { apiFetch, READ_RETRY_DELAYS_MS } from './http'
import type {
  CatalogResponse,
  IsoDate,
  MeResponse,
  PriceReference,
  ScreenRequest,
  ScreenResponse,
  ScreeningRequest,
  ScreeningResponse,
  StockDetailResponse,
  UniverseResponse,
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

  catalog: () => apiFetch<CatalogResponse>('/api/indicators'),

  universes: () => apiFetch<UniverseResponse[]>('/api/universes'),

  /** 목록의 `conditions`는 항상 null이다. 조건이 필요하면 {@link screen}을 부른다. */
  screens: () => apiFetch<ScreenResponse[]>('/api/screens'),

  screen: (id: number) => apiFetch<ScreenResponse>(`/api/screens/${id}`),

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
  screening: (body: ScreeningRequest) =>
    apiFetch<ScreeningResponse>('/api/screening', {
      method: 'POST',
      body,
      retryDelaysMs: READ_RETRY_DELAYS_MS,
    }),

  stock: (stockId: number, asOf?: IsoDate | null) =>
    apiFetch<StockDetailResponse>(`/api/stocks/${stockId}`, { query: { asOf } }),

  /** 지지·저항. 종목 상세와 **별도 요청**이다 — 한쪽이 실패해도 다른 쪽은 산다(ADR-0014). */
  priceReference: (stockId: number, asOf?: IsoDate | null) =>
    apiFetch<PriceReference>(`/api/stocks/${stockId}/price-reference`, { query: { asOf } }),
}
