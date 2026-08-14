import { MISSING, formatDate, formatNumber, formatPrice } from './format'

/**
 * 시세 등락의 방향과 그것을 말로 푼 문장.
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
 * 무엇에 견준 값인지를 말로 푼다. 툴팁과 낭독기가 함께 쓴다.
 *
 * IMPORTANT — 화면의 등락 옆에 **「전일 대비」라고 적지 않는다.** 거래정지 구간은 봉 자체가
 * 없어(`PriceWriter`가 OHLC 0 행을 버린다) 직전 거래일이 수 주 전일 수 있고, 그때 그 문구는
 * 그냥 거짓이다. 프론트에는 거래일 달력이 없어 인접 여부를 판정할 수단도 없다. 그래서
 * **날짜를 그대로 말한다** — 짧은 인라인 표기는 숫자만 두고, 기준은 이 문장이 진다.
 */
export function quoteDescription(
  previousTradeDate: string | null | undefined,
  change: number | null | undefined,
  rate: number | null | undefined,
): string | null {
  const tone = quoteTone(rate)
  if (tone === null || !previousTradeDate) {
    return null
  }
  const on = formatDate(previousTradeDate)
  if (tone === 'FLAT') {
    return `${on} 종가와 같습니다.`
  }
  // 부호는 「올랐다/내렸다」가 이미 말한다. 숫자는 크기만 낸다.
  const amount = formatPrice(change === null || change === undefined ? null : Math.abs(change))
  const percent =
    rate === null || rate === undefined ? MISSING : `${formatNumber(Math.abs(rate), 1)}%`
  const verb = tone === 'RISE' ? '올랐습니다' : '내렸습니다'
  return `${on} 종가보다 ${amount}(${percent}) ${verb}.`
}
