import { describe, expect, it } from 'vitest'

import { laneScale, scoreLane } from './scoreLane'

describe('laneScale — 레인이 뜻을 갖는 전략에서만 척도를 낸다', () => {
  it('배점이 갈리면 최대 배점을 낸다', () => {
    // VALUE_KR의 실제 배점이다. 3점 조건의 트랙이 1점 조건의 3배가 된다.
    expect(laneScale([3, 2, 2, 2, 1])).toBe(3)
    expect(laneScale([3, 2, 3, 3, 2, 1])).toBe(3)
  })

  it('배점이 전부 같으면 null이다 — 막대가 판정 Tag와 같은 말만 하게 된다', () => {
    // 폐기된 `SWING_TREND_KR`의 점수 조건이 이랬다. 트랙이 전부 같은 길이가 되고
    // 채움은 0 아니면 1이라, 옆의 `✓충족`이 이미 말한 것을 반복할 뿐이다.
    expect(laneScale([1, 1, 1, 1])).toBeNull()
    expect(laneScale([2, 2])).toBeNull()
  })

  it('점수 조건이 하나뿐이어도 null이다 — 견줄 대상이 없는 길이는 말하지 않는다', () => {
    expect(laneScale([3])).toBeNull()
  })

  it('점수 조건이 없으면 null이다', () => {
    expect(laneScale([])).toBeNull()
  })

  it('0 이하 배점은 척도에 넣지 않는다', () => {
    // 서버가 막고 있지만(`IndicatorCondition`이 던진다) 여기서 나누는 쪽이라
    // 들어오면 0으로 나누게 된다. 걸러 낸 뒤 남은 것이 하나면 그리지 않는다.
    expect(laneScale([0, 2])).toBeNull()
    expect(laneScale([0, 2, 1])).toBe(2)
  })
})

describe('scoreLane — 트랙은 배점, 채움은 획득', () => {
  const SCALE = 3

  it('배점이 클수록 트랙이 길다', () => {
    expect(scoreLane(3, 3, SCALE)?.trackRatio).toBe(1)
    expect(scoreLane(1, 1, SCALE)?.trackRatio).toBeCloseTo(1 / 3)
  })

  it('충족은 트랙을 가득 채운다 — 배점이 달라도 채움은 1이다', () => {
    expect(scoreLane(3, 3, SCALE)?.fill).toBe(1)
    expect(scoreLane(1, 1, SCALE)?.fill).toBe(1)
  })

  it('미충족은 긴 트랙이 빈 채로 남는다 — 큰 것을 놓쳤음이 길이로 보인다', () => {
    const lane = scoreLane(3, 0, SCALE)
    expect(lane?.trackRatio).toBe(1)
    expect(lane?.fill).toBe(0)
  })

  it('판정 불가도 빈 트랙이다 — 서버가 0을 준다. 구분은 판정 Tag가 진다', () => {
    expect(scoreLane(2, 0, SCALE)?.fill).toBe(0)
  })

  it('획득 점수를 모르면 그리지 않는다 — 빈 트랙은 "못 받았다"는 주장이다', () => {
    expect(scoreLane(3, null, SCALE)).toBeNull()
  })

  it('0으로 나누지 않는다', () => {
    expect(scoreLane(0, 0, SCALE)).toBeNull()
    expect(scoreLane(3, 3, 0)).toBeNull()
  })

  it('척도를 넘는 배점도 트랙을 벗어나지 않는다', () => {
    // 척도가 최대 배점이므로 정상 경로에서는 일어나지 않는다. 일어나면 막대가
    // 옆 칸을 침범하는 대신 가득 찬 것으로 그린다.
    expect(scoreLane(5, 5, SCALE)?.trackRatio).toBe(1)
  })
})
