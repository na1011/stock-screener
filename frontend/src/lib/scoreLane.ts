/**
 * 조건 하나가 총점에서 갖는 몫을 막대로 그리기 위한 계산.
 *
 * **트랙 길이가 배점이고, 채운 만큼이 획득이다.** 배점 3점짜리 조건의 트랙은 1점짜리의
 * 3배이고, 못 받았으면 그 긴 트랙이 빈 채로 남는다. 그래서 "무엇이 이 종목을 끌어올렸나"와
 * "무엇을 놓쳤나"가 길이 하나로 읽힌다.
 *
 * IMPORTANT — 버린 인코딩은 **채움 = 획득/총점**이었다. 그 막대는 만점을 받은 3점 조건을
 * 30%만 채워 그렸고, 바로 옆 숫자는 `3.00 / 3.00`(만점)이었다. 한 줄에 붙은 둘이 분모가
 * 달라 **서로를 부정했다.** 지금은 트랙이 배점을 지고 채움이 획득을 지므로 둘이 같은 말을 한다.
 *
 * IMPORTANT — 이 계산은 `.vue` 밖에 있다. 이 저장소의 테스트는 `environment: 'node'`이고
 * jsdom도 `@vue/test-utils`도 없어(ADR-0019가 프론트 의존성을 모아 두는 자리다) 컴포넌트
 * 안의 로직은 어떤 테스트로도 덮이지 않는다. 이번 변경의 새 규칙은 전부 여기 있다.
 *
 * 색은 갖지 않는다 — 이 막대를 그리는 `RatioBar`는 accent 단색이고, 유불리는 말하지 않는다
 * (ADR-0021의 "형태로 채널을 가른다"). 조건 판정은 옆의 `Tag`가 진다.
 */

/** 조건 하나의 레인. `RatioBar`의 `trackRatio`·`value`로 그대로 간다. */
export interface ScoreLane {
  /** 트랙이 차지할 폭(0..1]. 배점이 가장 큰 조건이 1이다. */
  trackRatio: number
  /** 트랙 안에서 채워진 비율(0..1). 서버 계약상 0 또는 1이다. */
  fill: number
}

/**
 * 이 전략에서 레인이 뜻을 갖는가. 가지면 정규화에 쓸 **최대 배점**을 낸다.
 *
 * IMPORTANT — **배점이 전부 같으면 null이다.** 그때 트랙은 전부 같은 길이가 되고 채움은
 * 0 아니면 1이라, 막대가 옆의 판정 `Tag`와 **똑같은 말만** 한다. 뜻이 없는 그래픽을 그리느니
 * 그리지 않는 편이 낫고, 그러면 그 폭을 글자가 쓴다.
 *
 * **이 분기는 죽지 않는다.** 호출부(`ConditionOutcomes`)가 `isScoring` 가드 <b>밖</b>에서
 * 무조건 부르므로 FILTER 전략은 매 렌더마다 여기로 온다. 배점이 평평한 <b>프리셋</b>은
 * `SWING_TREND_KR`(점수 조건 일곱이 전부 1)이 폐기되며 없어졌지만, 사용자가 조건 빌더로
 * 저장한 SCORING 전략이 언제든 그 형태가 될 수 있다.
 *
 * 조건이 하나뿐인 경우도 같은 규칙에 걸린다(값이 하나면 서로 다를 수가 없다). 견줄 대상이
 * 없는 길이는 아무것도 말하지 않는다.
 *
 * @param scoredWeights 점수 조건들의 배점. **필수 조건은 빼고 넘긴다** — 총점에 참여하지
 *   않으므로 최대 배점을 정할 자격도 없다. 거를 때 `weight === null`을 쓰지 않는다.
 *   FILTER 전략은 필수·점수 양쪽 모두 `weight`가 null이라 구분이 서지 않는다(`api/types.ts`).
 * @return 최대 배점. 레인을 그리지 않아야 하면 null.
 */
export function laneScale(scoredWeights: readonly number[]): number | null {
  const distinct = new Set(scoredWeights.filter((weight) => weight > 0))
  if (distinct.size < 2) {
    return null
  }
  return Math.max(...distinct)
}

/**
 * 조건 하나의 트랙 길이와 채움.
 *
 * 총점(`maxScore`)이 아니라 **최대 배점**으로 정규화한다. 총점 대비로 재면 조건이 늘수록
 * 모든 레인이 짧아져 배점 차이가 다시 안 보인다 — 조건 여섯짜리 전략에서 3점 조건이
 * 트랙의 3/14를 차지해도 눈에는 1점 조건과 같아 보인다. 비례 관계는 어느 쪽이든 같으므로
 * 잃는 것 없이 길이만 벌어진다.
 *
 * @param earnedScore 서버가 준 획득 점수. 충족이면 배점 전액, 아니면 0이다.
 *   **`NOT_EVALUABLE`도 0으로 온다** — 미충족과 같은 빈 트랙이 되고, 둘의 구분은 판정
 *   `Tag`가 진다. 막대까지 그 구분을 지려 하면 형태가 두 가지 말을 하게 된다.
 * @return 그릴 것이 없으면 null. 값이 없을 때 0으로 메우지 않는다 — 빈 트랙은 "못 받았다"는
 *   주장이고, 모르는 것을 그렇게 그리면 안 된다(`.claude/rules/frontend.md`).
 */
export function scoreLane(
  weight: number,
  earnedScore: number | null,
  scale: number,
): ScoreLane | null {
  if (earnedScore === null || !(weight > 0) || !(scale > 0)) {
    return null
  }
  return {
    trackRatio: Math.min(1, weight / scale),
    fill: Math.min(1, Math.max(0, earnedScore / weight)),
  }
}
