/**
 * PrimeVue 오버레이 안에서 시작한 클릭이 `document`로 새어 나가는 것을 막는다.
 *
 * <h2>없으면 무슨 일이 나는가</h2>
 * 조건 편집 팝오버 안에서 `Select`의 옵션을 고르면 **팝오버가 통째로 닫힌다.** 실측한 순서는
 * 이렇다(배포본 크롬, 옵션을 44ms 누르고 뗀 클릭).
 *
 * ```
 * mousedown  LI.p-select-option    오버레이 안, pointer-events: auto
 * mouseup    FOOTER.editor__foot   오버레이는 아직 DOM에 있지만 pointer-events: none  (+44ms)
 * click      BODY                  ← 두 타깃의 공통 조상으로 재계산된다
 * ```
 *
 * 마지막 줄이 핵심이다. `primevue/select`의 `onOverlayLeave`가 걷히는 오버레이에
 * `pointerEvents = 'none'`을 박는데(소스 확인), leave 애니메이션이 300ms라 **오버레이는
 * 화면에 남은 채 hit-test에서만 빠진다.** 그러면 mouseup이 밑에 깔린 팝오버로 떨어지고,
 * click은 둘의 공통 조상인 `body`에 디스패치된다. 옵션 `li`에 걸린
 * `onClick: withModifiers(noop, ['stop'])`이 **실행될 기회 자체가 없어져** 그 클릭이
 * `document`까지 올라가고, `Popover`의 outside-click 리스너가 그것을 "바깥 클릭"으로 받는다.
 *
 * 사람의 클릭은 보통 이 구간에 들어간다. **자동화된 클릭(6ms)으로는 재현되지 않는다** —
 * 그래서 이 결함을 브라우저 도구로 확인하려면 반드시 누르고 있는 시간을 늘려야 한다.
 *
 * <h2>왜 mousedown을 보는가</h2>
 * mousedown 시점에는 오버레이가 제자리에 원래 크기로, `pointer-events: auto`로 있다. 그래서
 * "이 누름이 오버레이에서 시작했는가"는 애니메이션 타이밍과 무관하게 언제나 참이다. click
 * 타깃만 보면 이미 늦는다.
 *
 * <h2>대가</h2>
 * 오버레이에서 누르고 **바깥에서 뗀** 클릭은 이제 팝오버를 닫지 않는다. 드래그로 시작한
 * 클릭이므로 닫지 않는 편이 맞지만, 동작이 바뀐 것은 사실이다(ADR-0064 §검증).
 *
 * MUST NOT: 아래 선택자를 지우거나 좁히지 않는다. **PrimeVue를 올려 클래스 이름이 바뀌면
 * 아무 에러 없이 증상만 되돌아온다** — 테스트도 컴파일도 통과한다.
 */
const OVERLAY_SELECTOR = [
  '.p-select-overlay',
  '.p-multiselect-overlay',
  '.p-autocomplete-overlay',
  '.p-cascadeselect-overlay',
  '.p-treeselect-overlay',
  '.p-datepicker-panel',
].join(',')

function inOverlay(target: EventTarget | null): boolean {
  return target instanceof Element && target.closest(OVERLAY_SELECTOR) !== null
}

/**
 * `main.ts`에서 한 번 부른다.
 *
 * 컴포넌트가 아니라 전역인 이유는 **닫히는 팝오버가 넷이기 때문**이다(조건 편집기·지표
 * 목록·화면 설정·지표 도움말). 넷 다 같은 경로로 닫히고, 앞으로 추가될 것도 그렇다.
 */
export function installOverlayClickShield(): void {
  let pressedInOverlay = false

  document.addEventListener(
    'mousedown',
    (event) => {
      pressedInOverlay = inOverlay(event.target)
    },
    true,
  )

  document.addEventListener(
    'click',
    (event) => {
      const pressed = pressedInOverlay
      /*
       * IMPORTANT — 조건과 무관하게 먼저 내린다. `ConditionChipBar`가 칩을 **합성 클릭**으로
       * 다시 여는데(`element.click()`), 그 클릭에는 선행 mousedown이 없다. 조건부로만
       * 내리면 직전 누름의 깃발이 남아 그 경로를 삼킨다 — 이 앱에서 가장 자주 도는 길이다.
       */
      pressedInOverlay = false
      if (!pressed || inOverlay(event.target)) {
        // 오버레이 밖에서 시작한 진짜 바깥 클릭은 그대로 통과한다 — 팝오버는 닫혀야 한다.
        // 오버레이 안에서 끝난 클릭은 PrimeVue가 스스로 처리한다.
        return
      }
      event.stopPropagation()
    },
    true,
  )
}
