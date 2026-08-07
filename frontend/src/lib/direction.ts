import type { ComparisonOperatorName, FavorableEnd, PercentileGuide } from '@/api/types'

/**
 * 지표의 유불리 방향.
 *
 * IMPORTANT — 이 모듈의 함수들은 **지표 코드를 인자로 받지 않는다.** 받지 않는 것이 규칙의
 * 강제 수단이다. 코드로 분기하는 순간 지표가 추가될 때마다 화면을 고쳐야 하고, 그것이
 * 백엔드에서 없앤 하드코딩 분기가 프론트로 옮겨온 것이다.
 *
 * 판단 근거는 서버가 주는 두 가지뿐이다 — 일반 지표는 `higherIsBetter`, 백분위 지표는
 * `percentile.favorableEnd`.
 */
export type Direction = 'HIGHER_IS_BETTER' | 'LOWER_IS_BETTER' | 'NEUTRAL'

/** 방향 판단에 필요한 최소 정보. 지표 코드가 없다는 것이 이 타입의 요점이다. */
export interface DirectionSource {
  higherIsBetter: boolean | null
  percentile?: PercentileGuide | null
}

export function indicatorDirection(source: DirectionSource): Direction {
  // 백분위는 저장된 higherIsBetter가 항상 null이다(값의 위치일 뿐 그 자체에 유불리가 없다).
  // 유불리는 원본 지표가 들고 있고 서버가 favorableEnd로 옮겨 준다.
  if (source.percentile) {
    return fromFavorableEnd(source.percentile.favorableEnd)
  }
  if (source.higherIsBetter === true) {
    return 'HIGHER_IS_BETTER'
  }
  if (source.higherIsBetter === false) {
    return 'LOWER_IS_BETTER'
  }
  return 'NEUTRAL'
}

function fromFavorableEnd(end: FavorableEnd): Direction {
  switch (end) {
    case 'HIGH':
      return 'HIGHER_IS_BETTER'
    case 'LOW':
      return 'LOWER_IS_BETTER'
    case 'UNKNOWN':
      return 'NEUTRAL'
    default:
      return assertNever(end)
  }
}

export interface DirectionStyle {
  /** 값 옆에 붙는 기호. 범례와 함께 쓴다 — 기호만으로는 뜻이 전달되지 않는다. */
  marker: string
  label: string
}

export function directionStyle(direction: Direction): DirectionStyle {
  switch (direction) {
    case 'HIGHER_IS_BETTER':
      return { marker: '▲', label: '높을수록 유리' }
    case 'LOWER_IS_BETTER':
      return { marker: '▼', label: '낮을수록 유리' }
    case 'NEUTRAL':
      return { marker: '–', label: '유리한 방향이 정해져 있지 않음' }
    default:
      return assertNever(direction)
  }
}

/**
 * "상위 20%" 조건을 만든다.
 *
 * IMPORTANT — 부등호를 프론트가 정하지 않는다. 백분위는 언제나 오름차순이므로 PER처럼 낮은
 * 쪽이 좋은 지표는 `<= 0.2`, ROE처럼 높은 쪽이 좋은 지표는 `>= 0.8`이다. 뒤집어 쓰면
 * 정반대 종목이 최상위로 올라오는데 값도 결과도 그럴듯해서 눈으로는 드러나지 않는다.
 *
 * @return 방향을 모르면 null. 그때는 버튼을 그리지 않는다 — 임의로 한쪽을 고르면 틀린다.
 */
export function topQuintileCondition(
  percentile: PercentileGuide | null | undefined,
): { operator: ComparisonOperatorName; value: number } | null {
  if (!percentile || percentile.topQuintileOperator === null || percentile.topQuintileThreshold === null) {
    return null
  }
  return { operator: percentile.topQuintileOperator, value: percentile.topQuintileThreshold }
}

function assertNever(value: never): never {
  throw new Error(`처리하지 않은 방향이다: ${String(value)}`)
}
