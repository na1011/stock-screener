/**
 * 유니버스의 표시 이름.
 *
 * 조건 빌더는 고를 유니버스를 이 형식으로 적고, 결과 머리말은 "무엇을 대상으로 실행했는가"를
 * 같은 형식으로 다시 적는다. 두 곳이 각자 만들고 있었기 때문에, 한쪽 문구를 고치면 사용자가
 * 고른 이름과 결과가 말하는 이름이 달라진다 — 그러면 "내가 고른 게 이게 맞나"를 확인할
 * 방법이 없어진다.
 */

/** 유니버스를 고르지 않았을 때. 서버는 이 경우 `universe`를 null로 준다. */
export const ALL_UNIVERSES_LABEL = '전체 (코스피200 + 코스닥150)'

/** 편입 종목 수를 모르면(서버가 null) 괄호를 붙이지 않는다. `(null종목)`을 보이지 않기 위해서다. */
export function universeLabel(universe: { name: string; memberCount: number | null }): string {
  return universe.memberCount === null
    ? universe.name
    : `${universe.name} (${universe.memberCount}종목)`
}
