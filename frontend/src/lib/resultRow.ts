import { DEFAULT_DECIMAL_SCALE } from '@/api/limits'
import type { ConditionInfo, IndicatorBrief, IndicatorResponse, ScreeningRow } from '@/api/types'

import { outcomeStyle } from './outcome'

/**
 * 결과 표(데스크톱)와 결과 카드(모바일)가 함께 쓰는 규칙과 문구.
 *
 * 둘은 **같은 결과의 다른 표현**이지 서로 다른 화면이 아니다. 그런데 각자 구현되면서 같은
 * 것이 다르게 그려지고 있었다 — 판정 문구가 `데이터없음`(표)과 `데이터 없음`(카드)으로
 * 갈렸고, 지표 이름을 찾는 방식이 달라 카탈로그가 실패하면 데스크톱은 "PER"을, 모바일은
 * 원본 코드를 보였다. 폭을 줄였을 뿐인데 내용이 달라지는 것은 사용자가 예상할 수 없는
 * 차이다.
 */

/**
 * 행의 지표 값.
 *
 * IMPORTANT — 값이 없는 지표는 **키 자체가 없다**(null이 아니다). `?? 0`을 쓰면 "계산되지
 * 않았다"가 0으로 둔갑하고, 사용자는 부채비율 0%인 회사를 본 것으로 읽는다.
 */
export function valueOf(row: ScreeningRow, code: string): number | null {
  return Object.hasOwn(row.values, code) ? (row.values[code] ?? null) : null
}

/**
 * 컬럼 하나의 표시 정보.
 *
 * 조건이 참조한 지표는 **서버가 응답에 실어 준 brief를 먼저 쓴다.** 서버가 판정에 쓴 것과
 * 같은 정의라서, 카탈로그 요청이 실패해도 조건에 걸린 지표는 제대로 그려진다. 카탈로그는
 * 조건에 없는 기본 컬럼을 채우는 보조 수단이다.
 */
export function indicatorBriefOf(
  code: string,
  conditions: readonly ConditionInfo[],
  byCode: ReadonlyMap<string, IndicatorResponse>,
): IndicatorBrief {
  const fromCondition = conditions.find((it) => it.indicator.code === code)
  if (fromCondition) {
    return fromCondition.indicator
  }
  const meta = byCode.get(code)
  return {
    code,
    name: meta?.name ?? code,
    unit: meta?.unit ?? null,
    // 서버가 폐기된 지표에 쓰는 폴백과 같은 값이어야 자릿수가 화면마다 달라지지 않는다.
    decimalScale: meta?.decimalScale ?? DEFAULT_DECIMAL_SCALE,
    higherIsBetter: meta?.higherIsBetter ?? null,
    interpretation: meta?.interpretation ?? '',
  }
}

/**
 * 점수의 소수 자릿수.
 *
 * 서버가 정한 값이 아니라 **화면의 판단**이다(그래서 `api/limits`가 아니라 여기 있다).
 * 표·카드·조건별 내역에 리터럴 2로 여섯 번 적혀 있어서, 정밀도를 바꾸려면 여섯 곳을 찾아야
 * 했고 하나를 빠뜨리면 같은 점수가 화면마다 다른 자릿수로 보인다.
 */
export const SCORE_DECIMAL_SCALE = 2

/**
 * 표와 카드가 함께 쓰는 문구.
 *
 * 판정 관련 문구를 `outcomeStyle`에 묶은 것은 의도다. `matchedCount / conditions.length`는
 * "몇 개 조건이 충족인가"이므로 조건 행의 판정과 **같은 사실**이고, 그 표현의 유일한 지점이
 * `lib/outcome`이라는 주장을 여기서 실제로 지킨다. 예전에는 컬럼 머리글이 따로 적혀 있어
 * 그 주장이 이미 새고 있었다.
 */
export const RESULT_LABELS = {
  search: '종목명 · 티커 검색',
  empty: '조건에 맞는 종목이 없습니다.',
  /** 서버가 이름을 주지 않은 종목. 빈칸으로 두면 클릭할 것이 없어진다. */
  unnamed: '이름 없음',
  score: '점수',
  scoreRatio: '달성률',
  matched: outcomeStyle('MATCHED').label,
  notEvaluable: outcomeStyle('NOT_EVALUABLE').label,
  /** 값이 없는 칸에 붙이는 설명. `—`만으로는 0과 구별은 되어도 이유가 보이지 않는다. */
  notCalculated: '계산되지 않음',
} as const
