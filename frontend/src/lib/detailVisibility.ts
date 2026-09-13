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

/**
 * 「전체 지표 보기」를 켠 채로 두는 자리.
 *
 * **`localStorage`를 고른 이유**는 이 토글이 한 번의 조작이 아니라 **지속 성향**이기
 * 때문이다 — "나는 전체를 보는 사람이다"는 탭을 닫아도 유지되는 것이 맞다.
 * `sessionStorage`는 탭마다 풀리고(그래서 `auth.ts`의 복귀 경로가 그것을 쓴다),
 * URL 쿼리는 링크에 실려 **받는 사람의 화면까지 바꾼다.**
 *
 * **MUST NOT: 이 값으로 서버 요청을 가르지 않는다.** 서버는 언제나 전량을 주고 각 행이
 * 자기 노출 정책을 들고 온다(ADR-0058 §규칙) — 거르는 것은 화면이다. 요청을 가르면
 * 「내 조건」 구획이 감춘 지표의 값을 못 받는다.
 *
 * **저장소를 못 쓰는 환경에서 던지지 않는다.** 사파리 비공개 모드처럼 접근이 막히면
 * 기본값으로 돌아가면 되는 일이고, 지표 화면이 통째로 죽을 이유가 아니다.
 */
const SHOW_ALL_KEY = 'stock-screener.detail.show-all-indicators'

export function readShowAllIndicators(): boolean {
  try {
    return window.localStorage.getItem(SHOW_ALL_KEY) === 'true'
  } catch {
    return false
  }
}

export function writeShowAllIndicators(showAll: boolean): void {
  try {
    window.localStorage.setItem(SHOW_ALL_KEY, String(showAll))
  } catch {
    // 저장만 못 할 뿐 이번 세션의 토글은 동작한다. 화면을 죽이지 않는다.
  }
}

/**
 * 0건 진단을 접어 둔 자리(ADR-0142).
 *
 * **기본은 접힘이다**(2026-09-14 사용자 결정). 조건이 많으면 목록만으로 화면이 길어진다.
 *
 * **그래서 접힌 상태가 무엇을 말하는지가 계약이 된다** — 제목·판정 대상 수·요약 한 줄이
 * 남고 버튼이 `조건 N개 펼치기`로 갯수를 밝힌다. P27 축④가 요구하는 것은 「0건일 때 사유에
 * **닿을 수 있다**」이므로 클릭 하나 뒤에 있는 것은 충족이지만, **접힌 화면이 아무 말도 안 하면
 * 그때는 못 닿는 것**이 된다. 그 경계를 `EmptyDiagnosis.spec.ts`가 잠근다.
 *
 * 선택은 **지속 성향**이라 위 `SHOW_ALL_KEY`와 같은 이유로 `localStorage`에 둔다.
 * **저장소를 못 쓰는 환경에서 던지지 않는다** — 기본값으로 돌아가면 되는 일이다.
 */
const DIAGNOSIS_COLLAPSED_KEY = 'stock-screener.result.diagnosis-collapsed'

export function readDiagnosisCollapsed(): boolean {
  try {
    // 저장된 값이 없으면 접힘이다. `=== 'false'`로 물어야 「미설정」이 기본값을 타고,
    // `!== 'true'`로 물으면 같은 결과지만 기본이 무엇인지가 코드에서 안 읽힌다.
    return window.localStorage.getItem(DIAGNOSIS_COLLAPSED_KEY) !== 'false'
  } catch {
    return true
  }
}

export function writeDiagnosisCollapsed(collapsed: boolean): void {
  try {
    window.localStorage.setItem(DIAGNOSIS_COLLAPSED_KEY, String(collapsed))
  } catch {
    // 저장만 못 할 뿐 이번 세션의 토글은 동작한다.
  }
}
