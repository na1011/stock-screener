import type { IndicatorBand, PercentileGuide } from '@/api/types'

import { indicatorDirection, type Direction } from './direction'
import { formatRatioAsPercent } from './format'

/**
 * "이 값이 어느 구간인가"와 "그 구간이 유리한 쪽인가".
 *
 * IMPORTANT — `lib/direction`과 같은 방식으로 **지표 코드를 인자로 받지 않는다.** 받지 않는
 * 것이 규칙의 강제 수단이다(`.claude/rules/frontend.md`). 판단 재료는 서버가 준 구간 목록과
 * 유불리 방향뿐이며, 그래서 지표가 추가돼도 이 파일은 고치지 않는다.
 *
 * IMPORTANT — 부등호를 여기서 유도하지 않는다. `band.from`/`band.to`는 프론트에 오지도
 * 않는다 — 서버가 `operator`·`value`·`upperBound`로 번역해 내려준다(ADR-0023). 여기가 하는
 * 것은 그 조건을 **적용**하는 것뿐이다.
 *
 * 색을 어떻게 쓰는지는 ADR-0021의 "형태로 가른다" 절이 정한다 — 판정 3상태는 `Tag` 알약이고
 * 값 구간은 미터 막대와 숫자 색조다. 같은 색을 쓰되 형태가 다르다.
 */
export type BandTone = 'FAVORABLE' | 'NEUTRAL' | 'UNFAVORABLE'

/**
 * 값이 드는 구간의 인덱스. 어디에도 들지 않으면 null.
 *
 * IMPORTANT — 목록 순서 그대로(큰 값 → 작은 값) 훑어 **첫 매치**를 취한다. 임의의 선택이
 * 아니라 경계값을 도메인과 맞추기 위한 것이다: 도메인의 구간은 `[from, to)`로 위쪽이
 * 열려 있는데 `BETWEEN`은 양끝을 포함하므로(ADR-0023이 스스로 지적한 지점), 경계값이
 * 아래 구간의 `BETWEEN`과 위 구간의 `GTE`에 동시에 걸린다. 위에서부터 훑으면 위 구간이
 * 이기고, 그것이 `[from, to)`가 뜻하는 바다.
 *
 * null이 나오면 화면은 구간을 **말하지 않는다.** 없는 구간을 지어내면 그 거짓이 라벨로
 * 나가고, 사용자는 "매우 낮음"을 보고 판단하게 된다.
 */
export function matchBandIndex(bands: readonly IndicatorBand[], value: number): number | null {
  const found = bands.findIndex((band) => satisfies(band, value))
  return found < 0 ? null : found
}

function satisfies(band: IndicatorBand, value: number): boolean {
  switch (band.operator) {
    case 'GT':
      return value > band.value
    case 'GTE':
      return value >= band.value
    case 'LT':
      return value < band.value
    case 'LTE':
      return value <= band.value
    case 'EQ':
      return value === band.value
    case 'NEQ':
      return value !== band.value
    case 'BETWEEN':
      // 상한이 없는 BETWEEN은 조건이 아니다. 서버가 그런 것을 보내면 맞다고 답하는 쪽이
      // 더 나쁘다 — 값이 아무 데나 들어가고 그 구간의 라벨이 붙는다.
      return band.upperBound !== null && value >= band.value && value <= band.upperBound
    default:
      // 유니온이 늘면 여기서 컴파일이 멈춘다. 런타임까지 가면 그 연산자를 쓴 구간이
      // 조용히 "맞지 않음"이 되고, 값이 엉뚱한 구간의 라벨을 달게 된다.
      return assertNever(band.operator)
  }
}

/**
 * 값의 위치가 유리한 쪽인가.
 *
 * @param position 값 축에서의 자리. 0이 가장 낮은 값, 1이 가장 높은 값이다.
 *
 * 삼등분이다 — 아래 1/3과 위 1/3만 색을 얻고 가운데는 중립이다. 이 구분이 거친 것은 알고
 * 있고, 그래서 **정확한 뜻은 색이 아니라 라벨이 진다**("우수", "과매수"). 색은 훑을 때
 * 눈이 먼저 잡는 신호일 뿐이다. 더 잘게 나누면 초록 다섯 단계가 되고, 다크 배경에서
 * 그 단계들은 서로 구별되지 않는다.
 */
export function toneForPosition(position: number, direction: Direction): BandTone {
  if (direction === 'NEUTRAL' || Number.isNaN(position)) {
    return 'NEUTRAL'
  }
  const clamped = Math.min(1, Math.max(0, position))
  const high = clamped >= 2 / 3
  const low = clamped <= 1 / 3
  const favorableAtHigh = direction === 'HIGHER_IS_BETTER'
  if (high) {
    return favorableAtHigh ? 'FAVORABLE' : 'UNFAVORABLE'
  }
  if (low) {
    return favorableAtHigh ? 'UNFAVORABLE' : 'FAVORABLE'
  }
  return 'NEUTRAL'
}

/**
 * 구간 인덱스를 값 축의 위치로.
 *
 * 목록은 **큰 값이 앞**이므로(ADR-0023이 강제한다) 인덱스가 클수록 낮은 값이다. 뒤집어
 * 쓰면 유불리가 정반대로 나오는데, 값도 라벨도 그럴듯해서 눈으로는 드러나지 않는다.
 *
 * 구간이 하나뿐이면 위치를 말할 수 없다 — 가운데로 둔다.
 */
export function bandPosition(index: number, count: number): number {
  if (count <= 1) {
    return 0.5
  }
  return (count - 1 - index) / (count - 1)
}

/**
 * 구간을 읽는 데 필요한 최소 정보. **지표 코드가 없다는 것이 이 타입의 요점이다**
 * (`DirectionSource`와 같은 이유).
 */
export interface BandSource {
  bands: readonly IndicatorBand[]
  higherIsBetter: boolean | null
  percentile?: PercentileGuide | null
}

/** 값 하나를 읽은 결과. 미터와 숫자 색조가 **같은 이것**을 본다. */
export interface BandReading {
  /** 값 축에서의 자리(0..1). 미터의 채움도 이것이다. */
  position: number
  tone: BandTone
  /** 구간 모드에서만 있다. 백분위는 자연어 라벨을 갖지 않는다(ADR-0023). */
  label: string | null
  /** 구간 개수. 0이면 눈금 없는 연속 막대다. */
  segments: number
  /** 채워질 눈금의 인덱스(왼쪽=낮은 값 기준). 연속 막대면 null. */
  activeSegment: number | null
}

/**
 * 값이 어느 구간인지 한 번에 읽는다.
 *
 * IMPORTANT — 미터와 숫자 색조가 **각자 계산하지 않게** 하려고 하나로 모았다. 두 번 계산하면
 * 언젠가 한쪽만 고쳐지고, 그때 화면은 초록 숫자 아래 빨간 막대를 그리게 된다.
 *
 * 두 모드가 있다.
 * - **구간 모드**: 서버가 구간을 준 지표 10개. 눈금이 구간 개수만큼 있고 라벨이 붙는다.
 * - **백분위 모드**: 값 자체가 0..1 척도인 `*_PCTL`. ADR-0023이 백분위에 **구간 라벨**을
 *   붙이지 말라고 한 것은 `PercentileGuide`와 두 체계가 되기 때문이고, 여기서 하는 것은
 *   라벨이 아니라 **값의 위치 표시**라 그 금지에 걸리지 않는다.
 *
 * @return 그릴 것이 없으면 null. 주가 스케일 지표(SMA·ATR·MACD·종가)가 그렇다 — 구간을
 *   지어내면 "3만원 이상 = 높음"이 되어 종목마다 뜻이 달라진다.
 */
export function readBand(source: BandSource, value: number | null | undefined): BandReading | null {
  if (value === null || value === undefined || Number.isNaN(value)) {
    return null
  }
  const direction = indicatorDirection(source)

  if (source.bands.length > 0) {
    const index = matchBandIndex(source.bands, value)
    const matched = index === null ? undefined : source.bands[index]
    if (index === null || matched === undefined) {
      return null
    }
    const position = bandPosition(index, source.bands.length)
    return {
      position,
      tone: toneForPosition(position, direction),
      label: matched.label,
      segments: source.bands.length,
      // 눈금은 왼쪽이 낮은 값이다. 목록은 큰 값이 앞이므로 뒤집는다.
      activeSegment: source.bands.length - 1 - index,
    }
  }

  if (source.percentile) {
    const position = Math.min(1, Math.max(0, value))
    return {
      position,
      tone: toneForPosition(position, direction),
      label: percentileRankLabel(position, direction),
      segments: 0,
      activeSegment: null,
    }
  }

  return null
}

/**
 * 백분위 값을 "상위 16%"로.
 *
 * IMPORTANT — ADR-0023이 백분위에 금지한 것은 **구간 라벨**("20% 이상 = 매우 높음")이고,
 * 이유는 `PercentileGuide`와 두 체계가 되기 때문이다. 순위 표현은 그 체계를 **그대로 쓰는
 * 것**이라 걸리지 않는다 — 서버가 "상위 20%" 버튼을 `<= 0.2`(낮은 쪽 유리)나 `>= 0.8`(높은
 * 쪽 유리)로 만드는 규칙과 같은 식이다.
 *
 * 방향은 `lib/direction`이 서버의 `favorableEnd`에서 이미 뽑아 둔 것을 쓴다. 유불리 방향을
 * 판단하는 자리가 둘이 되면 어긋나는데, 어긋나도 백분율이 그럴듯해 드러나지 않는다.
 *
 * 유리한 방향이 없으면(RSI 백분위 같은 것) 순위도 뜻이 없으므로 말하지 않는다.
 */
function percentileRankLabel(position: number, direction: Direction): string | null {
  if (direction === 'NEUTRAL') {
    return null
  }
  const rank = direction === 'LOWER_IS_BETTER' ? position : 1 - position
  // 0%는 "순위가 없다"로 읽힌다. 가장 좋은 값도 최소 상위 1%로 적는다.
  return `상위 ${formatRatioAsPercent(Math.max(rank, 0.01), 0)}`
}

function assertNever(value: never): never {
  throw new Error(`처리하지 않은 비교 연산자다: ${String(value)}`)
}
