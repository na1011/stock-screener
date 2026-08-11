---
id: ADR-0047
status: 채택됨
date: 2026-08-11
applies_to:
  - "frontend/src/layouts/AppLayout.vue"
  - "frontend/src/views/**"
---

# ADR-0047. 넓은 폭에서는 앱 셸이 스크롤 컨테이너다

## 결정

**900px 초과**에서 문서(window)는 스크롤하지 않는다. `.app-layout`이 `height: 100dvh; overflow: hidden`으로 뷰포트를 채우고, `.app-layout__body`만 `overflow: hidden auto`로 스크롤한다. **900px 이하는 그대로 문서가 스크롤한다.**

## 규칙

- MUST: `.app-layout:not(.app-layout--narrow)`에 `grid-template-rows: minmax(0, 1fr)`를 함께 준다. 없으면 암묵 행이 `auto`라 내용 높이로 자라 `100dvh`를 넘긴 채 잘리고, **스크롤바 없이 아래가 사라진다.**
- MUST: 그 아래 `.app-layout__main`에 `min-height: 0`을 준다. flex 자식의 자동 최소 크기를 풀지 않으면 본문이 실제로 스크롤하지 않는다.
- MUST NOT: 뷰의 루트에 `min-height: 100dvh`를 쓰지 않는다. 본문 상자의 높이가 이미 정확히 `100dvh`라 여유가 0이고, 오류 배너 한 줄이나 서브픽셀 반올림만으로 두 번째 스크롤바가 생긴다. `100%`를 쓴다.
- MUST NOT: `.app-layout__body`의 넓은 폭 규칙을 `clip auto`로 적지 않는다. 브라우저가 `hidden auto`로 계산하므로(CSS Overflow 3 — 한 축이 `visible`/`clip`이 아니면 다른 축의 `clip`은 `hidden`이 된다) 코드가 거짓이 된다.
- MUST: 새 뷰가 sticky 요소를 두면 그 기준이 **뷰포트가 아니라 이 본문 상자**임을 전제한다.
- MUST NOT: 폭 경계를 CSS에 리터럴로 적지 않는다. `.app-layout--narrow` 클래스가 `BREAKPOINTS.BUILDER`를 이미 반영한다(`lib/breakpoints.ts`).

## 검증

자동 검증이 불가능한 결정이다 — CSS 레이아웃은 렌더해야 드러난다. 아래는 사람이 브라우저에서 확인하는 절차다.

```bash
# clip이 되살아나지 않았는가 (넓은 폭 규칙은 `hidden auto`여야 한다)
rg -n 'overflow' frontend/src/layouts/AppLayout.vue
```

**1440×900, 조건 한 줄·안내 없음·결과 로드 상태**에서:

1. 휠을 굴려도 주소창 아래에 문서 스크롤바가 없다.
2. `.app-layout__body`에도 스크롤바가 없다(그보다 크롬이 높은 상태에서 생기는 것은 허용한다 — `--app-result-scroll-height`의 주석 참조).
3. 레일의 종목 검색 드롭다운을 열고 본문을 스크롤하면 **드롭다운이 검색창에 붙어 있다.**
4. 900px 이하로 줄이면 문서가 다시 스크롤하고, 모바일에서 주소창이 정상적으로 접힌다.

## 배경

좌측 검색 드롭다운이 스크롤을 따라 흘러가 검색창에서 떨어졌다. `node_modules`를 열어 확인한 원인은 PrimeVue 오버레이의 두 성질이 겹친 것이다. `@primeuix/utils`의 `absolutePosition`이 오버레이를 body에 붙이고 `getBoundingClientRect().top + pageYOffset`, 즉 **문서 좌표**에 `position: absolute`로 박는다. 그리고 스크롤을 듣는 `ConnectedOverlayScrollHandler`가 쓰는 `getScrollableParents`는 `nodeType !== 9`로 **document를 배제**하므로 window 스크롤은 듣지도 못한다. 그래서 sticky 앵커(레일 검색창, 조건 칩 바)에 붙은 오버레이는 앵커에서 떨어져 나가면서 닫히지도 않았다. 조건 편집 팝오버도 같은 원인이다.

window가 움직이지 않으면 문서 좌표도 움직이지 않는다.

`AppLayout`의 주석이 정반대 근거를 명문화해 두고 있었다 — `overflow-x: clip`을 고른 이유가 *"hidden이 스크롤 컨테이너를 만들어 화면 안의 sticky가 이 상자를 기준으로 붙어 버리기 때문"*이었다. 넓은 폭에서는 그것이 이제 **원하는 동작**이다. 그 주석을 함께 고쳤다.

## 버린 대안

- **`appendTo="self"`** — 최상위 팝오버 셋에는 한 줄이면 되지만 **팝오버 *안*의 `Select`·`DatePicker`에 닿지 않는다.** 사용자가 실제로 지적한 자리가 거기다(화면 설정 팝오버의 조회 대상·기준일). 게다가 `relativePosition`은 조상의 `overflow`에 클리핑된다.
- **window scroll 리스너로 오버레이 닫기** — PrimeVue가 "열린 오버레이 목록"을 공개하지 않는다. 중첩된 `Select`는 imperative ref가 위로 노출되지도 않아, 여기서도 문제의 자리를 못 고친다.
- **좁은 폭까지 셸로 전환** — `height: 100dvh`로 문서를 묶으면 모바일 주소창 자동 접힘이 영영 일어나지 않아 720px 화면에서 ~60px을 상시 잃는다. `.claude/rules/frontend.md`가 모바일을 "빠르게 훑어보는 조회"로 규정한 것과 충돌한다. **그리고 그쪽에는 애초에 이 버그가 없다** — 900px 이하에서 조건 바는 `static`이고 `AppHeader`에는 오버레이 앵커가 하나도 없다. 유일하게 남았던 768~900px 구간은 조건 바의 `static` 경계를 767에서 900으로 올려 닫았다.
- **DataTable의 `scroll-height="flex"`** — 사슬 전체에 확정 높이가 필요한데 그 중간에 `AsyncPanel`의 블록 래퍼가 있다. 별도 결정으로 남긴다.

## 결과

**얻은 것.** 모든 오버레이가 한 번에 고쳐진다 — 검색 드롭다운, 조건 편집기, 지표 목록, 화면 설정. 본문이 scrollable parent가 되므로 팝오버가 스크롤에 정상적으로 닫히기도 한다. 모달을 열 때 스크롤바 폭 보정으로 생기던 가로 점프도 사라진다.

**대가.** `.screener__bar`는 sticky라 스크롤해도 칩이 **움직이지 않는데도** 팝오버가 닫힌다 — `getScrollableParents`가 본문 상자를 잡기 때문이다. 즉 **조건 편집기·지표 목록·화면 설정을 열어 둔 채 결과 표를 스크롤할 수 없다.** 이득이 아니라 받아들인 비용이다.

**되돌리려면.** `AppLayout`의 `:not(.app-layout--narrow)` 블록 셋, `ScreenerView`의 `.screener { min-height }`와 조건 바 경계(900→767), `main.css`의 `--app-result-scroll-height`를 함께 되돌려야 한다. **그중 하나만 되돌리면 이중 스크롤바나 잘린 화면이 남는다.**
