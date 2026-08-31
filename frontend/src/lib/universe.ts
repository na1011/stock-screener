/**
 * 조회 대상의 표시 이름.
 *
 * MUST: 한국어 표시어는 **"조회 대상"**이고 영문 식별자는 `universe`다. 의도적으로 어긋나 있다
 * — "유니버스"로 직역하면 세계관처럼 읽히고, 실제로 이 값이 정하는 것은 **스크리닝이 훑을
 * 종목의 범위**뿐이다. "대상 시장"도 쓰지 않는다: 선택지가 `코스피200`이라 시장이라 부르면
 * 코스피 전체(~800종목)로 읽히는데, 이 앱에서 200이냐 800이냐는 곧 결과의 차이다.
 * `lib/terminology.spec.ts`가 되돌아가는 것을 막는다.
 *
 * 조건 빌더는 고를 대상을 이 형식으로 적고, 결과 머리말은 "무엇을 대상으로 실행했는가"를
 * 같은 형식으로 다시 적는다. 두 곳이 각자 만들고 있었기 때문에, 한쪽 문구를 고치면 사용자가
 * 고른 이름과 결과가 말하는 이름이 달라진다 — 그러면 "내가 고른 게 이게 맞나"를 확인할
 * 방법이 없어진다.
 */

/**
 * 대상을 좁히지 않았을 때. 서버는 이 경우 `universe`를 null로 준다.
 *
 * MUST NOT: 괄호 안을 코드에 박지 않는다. 예전에는 `'전체 (코스피200 + 코스닥150)'`이었는데
 * 그것은 **활성 대상이 국내 둘뿐일 때만 참인 문장**이고, 대상이 늘면 화면이 없는 목록을
 * 사실로 말한다 — 서버는 맞고 라벨만 낡는다.
 */
export function allUniversesLabel(
  universes: ReadonlyArray<{ memberCount: number | null }>,
): string {
  // 이름을 나열하지 않는 것은 길이 때문이다 — 실제 이름이 「코스피 시총 상위 200」이라
  // 둘만 이어도 칩을 넘긴다. 종목 수는 형제 선택지(`universeLabel`)와 같은 형식이라
  // 「전체 (350종목)」과 「코스피 시총 상위 200 (200종목)」이 한 목록에서 나란히 읽힌다.
  const total = universes.reduce((sum, u) => sum + (u.memberCount ?? 0), 0)
  return total === 0 ? '전체' : `전체 (${total}종목)`
}

/** 편입 종목 수를 모르면(서버가 null) 괄호를 붙이지 않는다. `(null종목)`을 보이지 않기 위해서다. */
export function universeLabel(universe: { name: string; memberCount: number | null }): string {
  return universe.memberCount === null
    ? universe.name
    : `${universe.name} (${universe.memberCount}종목)`
}
