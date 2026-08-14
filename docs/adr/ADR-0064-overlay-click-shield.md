---
id: ADR-0064
status: 채택됨
date: 2026-08-15
applies_to:
  - "frontend/src/lib/overlayClickShield.ts"
  - "frontend/src/main.ts"
  - "frontend/src/components/condition/ConditionChipBar.vue"
---

# ADR-0064. 오버레이에서 시작한 클릭이 document로 새어 나가는 것을 전역에서 막는다

## 결정

`document`에 캡처 단계 리스너 한 쌍을 걸어, **PrimeVue 오버레이 안에서 시작했는데 오버레이
밖에서 끝난 클릭**의 전파를 끊는다(`frontend/src/lib/overlayClickShield.ts`, `main.ts`에서 설치).
함께 `ConditionChipBar.openEditor`가 Popover에 살아 있는 앵커를 넘기도록 고친다.

## 규칙

- MUST: `installOverlayClickShield()`를 `main.ts`에서 **PrimeVue 등록보다 먼저** 부른다.
- MUST: 판정은 **`mousedown`** 타깃으로 한다. `click` 타깃만 보면 이미 늦다 — 아래 §배경의
  실측이 그 이유다.
- MUST: 캡처 `click` 핸들러는 **조건과 무관하게** 깃발을 내린다. `ConditionChipBar`가 칩을
  합성 클릭(`element.click()`)으로 다시 여는데 그 클릭에는 선행 `mousedown`이 없어, 조건부로
  내리면 직전 누름의 깃발이 그 경로를 삼킨다.
- MUST NOT: `OVERLAY_SELECTOR`를 좁히거나 지우지 않는다. PrimeVue를 올려 클래스 이름이 바뀌면
  **컴파일도 테스트도 통과한 채 증상만 되돌아온다.**
- MUST NOT: `Popover.show()`에 `nextTick` 뒤의 원본 이벤트를 넘기지 않는다. `show()`는
  위치 기준은 둘째 인자에서 받지만 앵커 판정용 `eventTarget`은 `event.currentTarget`에서
  읽는데, 그 시점에는 null이라 `isTargetClicked()`가 영구히 false가 된다.

## 검증

이 결정은 **자동 검증 대상이 아니다.** 재현에 `mousedown → 애니메이션 → mouseup`의 실제
타이밍이 필요해 jsdom으로는 잡히지 않고(ADR-0019가 그 런타임을 두지 않기로 했다), 통과하는
테스트를 두면 오히려 "검증됐다"고 믿게 만든다. 대신 배선이 살아 있는지와 수동 재현 절차를 둔다.

```bash
# 1. 설치가 살아 있는가 (지워도 컴파일·테스트가 전부 통과하는 부류다)
rg -n 'installOverlayClickShield\(\)' frontend/src/main.ts
#    → 잡혀야 한다

# 2. 앵커가 살아 있는가
rg -n 'currentTarget: anchor' frontend/src/components/condition/ConditionChipBar.vue
#    → 잡혀야 한다
```

**수동 재현** — `./gradlew bootRun` + `cd frontend && npm run dev`. 스크리너에서 `＋ 조건`으로
조건을 하나 만들고 「직접 입력」을 펼친 뒤 `조건` 콤보를 연다.

1. 옵션을 **45ms 이상 누르고 있다가** 뗀다. 시트가 열린 채여야 하고 선택이 반영돼야 한다.
   **자동화 도구의 기본 클릭(약 6ms)으로는 재현되지 않는다** — 반드시 누르는 시간을 늘린다.
2. 팝오버 **바깥**을 그냥 클릭하면 여전히 닫혀야 한다. 이것이 shield가 과잉 차단하지 않았다는
   증거다.
3. 설정 칩의 `Select`·`DatePicker`로도 1·2를 반복한다.

**바뀐 동작** — 오버레이에서 누르고 **바깥에서 뗀** 클릭은 이제 팝오버를 닫지 않는다.
드래그로 시작한 클릭이므로 닫지 않는 편이 맞지만, 전과 다르다.

## 배경

조건 편집 팝오버에서 `Select`의 옵션을 고르면 팝오버가 통째로 닫혔다. 배포본 크롬에서 잰
순서가 원인을 그대로 말한다.

| t(ms) | 이벤트 | 타깃 | 오버레이 상태 |
|---|---|---|---|
| 169317 | `mousedown` | `LI.p-select-option` | DOM에 있음 · opacity 1 · `pointer-events: auto` |
| 169361 | `mouseup` | `FOOTER.editor__foot` | DOM에 있음 · opacity 0.59 · **`pointer-events: none`** |
| 169361 | `click` | **`BODY`** | 〃 |

`primevue/select/index.mjs`의 `onOverlayLeave(el)`가 걷히는 오버레이에 `pointerEvents = 'none'`을
박는데, leave 애니메이션이 300ms(`p-anchored-overlay`, `scale(0.93)`)라 **오버레이는 화면에
남은 채 hit-test에서만 빠진다.** 그러면 mouseup이 밑에 깔린 팝오버로 떨어지고, `click`은 두
타깃의 공통 조상인 `body`에 디스패치된다. 옵션 `li`에 걸린 `onClick: withModifiers(noop, ['stop'])`이
**실행될 기회 자체가 없어져** 그 클릭이 `document`까지 올라간다.

거기서 `Popover`의 outside-click 리스너가 그것을 바깥 클릭으로 받는다. 방어 둘이 동시에 비어
있기 때문이다 — 오버레이가 `document.body`로 포탈되어 `selfClick`이 서지 못하고,
`isTargetClicked()`는 `eventTarget`이 null이라(위 §규칙의 마지막 항목) 언제나 false다.

## 버린 대안

- **`Select`에 `appendTo="self"`** — 오버레이가 팝오버 안에 그려지면 클릭이 언제나
  `onContentClick`을 타 전역 리스너가 필요 없다. 버린 이유는 `.editor { overflow-y: auto }`가
  드롭다운을 자르고, `Select` 자신의 스크롤 핸들러가 그 스크롤에 반응해 드롭다운을 닫기
  때문이다 — 87개짜리 지표 목록을 볼 수단이 사라진다.
- **`Popover`에 `:dismissable="false"`** — 확실하지만 바깥 클릭으로 닫는 동작을 통째로 잃는다.
  `dismissable`은 `onEnter`에서만 읽히므로 열린 뒤 토글할 수도 없다.
- **`Select`의 `@before-hide`에서 Popover 인스턴스의 `selfClick`을 세운다** — 팝오버 셋에
  각각 배선이 필요하고, 키보드로 고르면 뒤따르는 click이 없어 깃발이 남는다.

## 결과

닫히던 팝오버 넷이 모두 산다 — 조건 편집기·지표 목록·화면 설정·지표 도움말. 앞으로 추가되는
것도 배선 없이 함께 보호된다.

대가는 **전역 리스너 하나가 이벤트 흐름에 끼어든다**는 것이다. `document` 캡처 단계의
`stopPropagation()`은 그 클릭에 대한 document 버블 리스너 전부를 끊으므로, 오버레이에서
누르고 바깥에서 뗀 클릭에는 앱의 어떤 문서 리스너도 반응하지 않는다. 지금 그런 리스너는
PrimeVue 오버레이들의 outside-click뿐이다(앱 자체의 document 리스너는 `AppLayout`의
`visibilitychange` 하나이고 이벤트 종류가 다르다).

되돌리려면 `main.ts`의 한 줄과 `lib/overlayClickShield.ts`를 지운다. **증상은 조용히 돌아온다.**
