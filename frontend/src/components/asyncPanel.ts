/**
 * 로딩·실패 문구가 붙는 영역의 이름.
 *
 * `AsyncPanel`은 `label`을 받아 문장을 조립한다("{label}을(를) 불러오지 못했습니다"). 그래서
 * 이 값은 라벨이 아니라 **문장의 주어**이고, 일곱 호출부에 흩어진 자유 문자열이었다. 오타는
 * 그대로 화면에 나가고, 같은 영역을 두 화면이 다르게 부르는 것도 막을 수 없었다.
 *
 * IMPORTANT — **조사를 계산하지 않고 데이터로 적는다.** 종성 판정(`(코드 - 0xAC00) % 28`)은
 * 순한글에서만 맞고 'S&P500'·'KOSPI200' 같은 이름이 들어오는 순간 조용히 틀린다. 여기 올
 * 이름은 닫힌 집합이므로 계산할 이유가 없다 — 조용히 틀리는 헬퍼는 이 패스가 없애려는 것
 * 그 자체다.
 */
export const ASYNC_PANELS = {
  catalog: { subject: '지표 카탈로그', particle: '를' },
  universes: { subject: '유니버스 목록', particle: '을' },
  screening: { subject: '스크리닝 결과', particle: '를' },
  screens: { subject: '화면 목록', particle: '을' },
  stock: { subject: '종목 정보', particle: '를' },
  priceReference: { subject: '지지·저항 참고정보', particle: '를' },
  watchlist: { subject: '관심종목', particle: '을' },
} as const

export type AsyncPanelName = keyof typeof ASYNC_PANELS

/** 영역 이름만 필요할 때. 토스트 요약이 패널과 같은 낱말을 쓰게 한다. */
export function panelSubject(name: AsyncPanelName): string {
  return ASYNC_PANELS[name].subject
}
