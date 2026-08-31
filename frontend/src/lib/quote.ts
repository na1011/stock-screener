import { MISSING, formatDate, formatNumber, formatSignedPrice } from './format'

/**
 * 시세 등락의 방향·표기·기준.
 *
 * IMPORTANT — **방향 판정이 템플릿에 있으면 안 된다.** `rate > 0`을 화면이 직접 쓰면
 * `null`(값 없음)과 `0`(보합)이 같은 분기로 떨어지고, 그때 색이 붙지 않은 채 `—`만 남는다.
 * `lib/direction.ts`가 지표 유불리에 대해 같은 일을 하는 것과 같은 이유다.
 *
 * IMPORTANT — 등락에 **`▲`/`▼`를 쓰지 않는다.** 두 가지가 동시에 막는다.
 *   1. ADR-0021이 `MUST NOT: 유불리 방향 표식(▲/▼)에 색을 주지 않는다`를 못 박았다.
 *   2. 같은 화면(`IndicatorCard`)이 이미 그 글리프를 쓰는데 **극성이 반대다** — `direction.ts`의
 *      `▼`는 「낮을수록 유리」, 즉 PER 카드의 ▼는 *좋다*는 뜻이다. 헤더의 ▼가 하락이면 한
 *      화면에서 같은 기호가 반대를 말한다.
 * 방향은 부호와 색이 진다.
 */
export type QuoteTone = 'RISE' | 'FALL' | 'FLAT'

/** 값이 없으면 null이다 — `0`(보합)과 갈라야 한다. */
export function quoteTone(rate: number | null | undefined): QuoteTone | null {
  if (rate === null || rate === undefined || Number.isNaN(rate)) {
    return null
  }
  if (rate > 0) {
    return 'RISE'
  }
  return rate < 0 ? 'FALL' : 'FLAT'
}

/**
 * 등락 한 줄. `+12,500원 (+4.89%)`.
 *
 * 금액이 앞, 백분율이 괄호 안이다 — 국내외 시세 화면이 공유하는 관례이고, 가운뎃점으로
 * 나열하면 둘이 대등해 보여 무엇이 주된 값인지 흐려진다.
 *
 * 소수 **두 자리**다. {@link formatSignedPercent}(한 자리)를 쓰지 않는 이유는 그쪽이 재무
 * 증감률용이기 때문이다 — 등락률은 두 자리가 관례이고 서버도 두 자리로 반올림해 보낸다.
 *
 * MUST NOT: 여기서 다시 계산하지 않는다. 서버는 **수정종가의 비**로 재고 미보정 기업 행위를
 * 지나면 아예 내지 않는데(ADR-0055), 화면은 둘 다 알 수 없다.
 */
export function formatQuoteChange(
  change: number | null | undefined,
  rate: number | null | undefined,
  currency: string | null | undefined,
): string {
  if (quoteTone(rate) === null) {
    return MISSING
  }
  const sign = (rate as number) > 0 ? '+' : ''
  return `${formatSignedPrice(change, currency)} (${sign}${formatNumber(rate, 2)}%)`
}

/**
 * 무엇에 견준 값인가. 기준일 캡션 뒤에 붙는다.
 *
 * IMPORTANT — 「전일 대비」라고 적지 않는다. 거래정지 구간은 봉 자체가 없어(`PriceWriter`가
 * OHLC 0 행을 버린다) 직전 거래일이 수 주 전일 수 있고 — 실제로 207940은 26거래일 전이다 —
 * 프론트에는 거래일 달력이 없어 인접 여부를 판정할 수단도 없다. **날짜를 그대로 말한다.**
 */
export function quoteBasisLabel(previousTradeDate: string | null | undefined): string | null {
  return previousTradeDate ? `${formatDate(previousTradeDate)} 대비` : null
}
