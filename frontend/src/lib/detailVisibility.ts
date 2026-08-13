import type { IndicatorResponse, StockIndicatorValue } from '@/api/types'

/**
 * 종목 상세의 분류 구획에 무엇을 보일 것인가(ADR-0058).
 *
 * 판정은 **서버가 실어 준 `detailDefault`** 하나로 한다. 화면이 카탈로그에서 되짚지 않는
 * 이유가 셋이다 — 카탈로그와 상세 응답의 도착 순서가 보장되지 않아 감출 카드가 그려졌다가
 * 접히고, 카탈로그가 실패하면 감춤이 통째로 무효가 되는데 그 사실이 화면에 남지 않으며,
 * `.claude/rules/frontend.md`가 "서버가 이미 아는 사실을 화면이 다른 요청으로 재계산하지
 * 않는다"를 금한다.
 *
 * **감추는 것은 분류 구획뿐이다.** 「내 조건」 구획은 이 판정을 타지 않는다 — 사용자가 조건에
 * 넣은 지표는 값이 없어도 보여야 하고, 그것이 "왜 이 종목이 결과에 없는가"의 답이다.
 */
export function isVisibleByDefault(indicator: StockIndicatorValue): boolean {
  return indicator.detailDefault
}

/**
 * 이 종목에서 **실제로 감춘** 지표 수.
 *
 * MUST NOT: 카탈로그에서 세지 않는다. 그러면 종목과 무관하게 언제나 같은 수가 나오는데,
 * 그중 이 종목에 값이 없는 것은 "감춘" 것이 아니라 애초에 없던 것이다. 응답에 실렸는데
 * 구획에 안 그린 것만이 감춘 것이다.
 */
export function hiddenCount(indicators: readonly StockIndicatorValue[]): number {
  return indicators.filter((indicator) => !isVisibleByDefault(indicator)).length
}

/**
 * 값이 없어 목록에 오르지 못한 지표 수.
 *
 * 분모를 **기본 노출 대상으로 좁힌다.** 전체 활성 지표에서 빼면 감춘 것까지 "값이 없다"로
 * 세는데, 감춤은 값의 유무와 무관한 사실이다.
 *
 * 카탈로그가 없으면 0을 낸다 — 분모를 모르는 채로 수를 지어내지 않는다.
 */
export function missingCount(
  catalogIndicators: readonly IndicatorResponse[],
  shownCount: number,
): number {
  if (catalogIndicators.length === 0) {
    return 0
  }
  const expected = catalogIndicators.filter((indicator) => indicator.detailDefault).length
  return Math.max(0, expected - shownCount)
}
