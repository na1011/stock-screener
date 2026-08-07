/**
 * 화면 폭 경계.
 *
 * CSS와 JS가 이 값을 **직접 공유할 방법이 없다.** 미디어 쿼리는 `var()`를 받지 않고
 * (`@media (max-width: var(--x))`는 명세상 동작하지 않는다), `@custom-media`나 sass는 전부 새
 * 의존성이라 ADR-0019의 기준을 넘지 못한다. 그래서 **정본은 여기 두고, CSS는 리터럴 px을
 * 유지하되 `breakpoints.spec.ts`가 토큰 밖의 값을 잡는다.**
 *
 * 셋을 하나로 합치지 않는 이유는 서로 다른 질문이기 때문이다. 문제는 개수가 아니라 셋 다
 * 이름 없는 숫자여서 **어느 것이 JS와 CSS에 걸쳐 있는지 알 수 없었다**는 것이다.
 */
export const BREAKPOINTS = {
  /** 헤더·목록이 글자 라벨을 접는 폭. 컴포넌트를 갈아 끼우지는 않는다. */
  COMPACT: 640,
  /**
   * 표 ↔ 카드 **컴포넌트 교체** 경계.
   *
   * IMPORTANT — JS(`useBreakpoint`)와 CSS가 반드시 같아야 한다. 어긋나면 그 사이 폭에서
   * 데스크톱 DataTable이 행 확장 안에 모바일 grid-areas 레이아웃을 그린다. 좁은 구간에서만
   * 재현되어 눈에 띄지 않는다.
   */
  MOBILE: 767,
  /** 조건 빌더가 한 열로 무너지는 폭. 빌더는 표보다 넓은 폭을 요구한다. */
  BUILDER: 900,
} as const

export function maxWidthQuery(px: number): string {
  return `(max-width: ${px}px)`
}
