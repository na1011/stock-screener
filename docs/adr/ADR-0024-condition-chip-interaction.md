---
id: ADR-0024
status: 채택됨
date: 2026-08-08
applies_to:
  - "frontend/src/components/condition/**"
  - "frontend/src/components/AppRail.vue"
  - "frontend/src/layouts/AppLayout.vue"
---

# ADR-0024. 조건은 칩으로 읽고 팝오버로 고친다

## 결정

조건 하나를 **칩 하나**로 그리고, 편집은 그 칩에 붙는 **팝오버**에서 한다. 팝오버는 값 구간
프리셋을 먼저 보이고 직접 입력을 접어 둔다. 화면 골격은 좌측 레일 + 작업 영역 두 열로 바꾸고
화면 목록을 레일에 상주시킨다.

## 규칙

- MUST: 조건의 상태 모델은 `stores/screenDraft`가 유일한 정본이다. 칩·팝오버는 그리기만 한다.
  `DraftCondition`의 평면 구조와 `toRequest()`는 이 결정과 무관하게 유지된다.
- MUST: **미완성 조건을 조용히 버리지 않는다.** 칩이 점선 + "값 필요"로 표시하고, 칩 바 아래
  한 줄과 실행 버튼 툴팁이 개수를 말한다. `completeConditions`가 걸러내는 것은 그대로다 —
  바뀐 것은 그 사실을 화면이 말한다는 것뿐이다.
- MUST: 조건 삭제는 되돌릴 수 있어야 한다(`screenDraft.lastRemoved` + `undoRemove`). 삭제 하나에
  확인 대화상자를 두지 않는다 — 흔한 동작이라 매번 막으면 확인 자체가 읽히지 않게 된다.
- MUST: 전체를 지우는 것(`reset`)은 잃을 것이 있을 때만 확인을 받는다.
- MUST: 서버가 `invalid-condition`·`unknown-indicator`에 싣는 `conditionIndex`를 해당 칩에 표시한다.
- MUST NOT: **자동 실행하지 않는다.** `/api/screening`은 최대 2000행을 통째로 주는 POST다. 대신
  결과가 조건과 어긋나면 화면이 그것을 자백한다(실행 버튼 라벨 + 요약 배지). 라이브 카운트가
  필요하면 경량 엔드포인트를 먼저 만든다.
- MUST NOT: 열려 있는 팝오버를 닫고 **같은 틱에** 다시 열지 않는다. PrimeVue의 나가는 트랜지션이
  컨테이너를 걷어내는 중이라 `offsetHeight`를 null에서 읽고 죽는다. `@hide`를 기다린다.
- MUST: 팝오버를 열 때 기준 요소를 **동기적으로** 붙잡아 `show(event, anchor)`로 넘긴다.
  브라우저는 디스패치가 끝나면 `event.currentTarget`을 null로 되돌리므로, `nextTick` 뒤에 PrimeVue가
  그것을 읽으면 같은 자리에서 죽는다.
- MUST: 화면 폭 경계는 `lib/breakpoints`의 셋(640/767/900)만 쓴다. 레일이 서랍으로 바뀌는 경계는
  `BUILDER`(900)를 재사용한다 — 레일 240px을 뗀 뒤 작업 영역이 좁아지는 지점과 같다.

### 레일의 층위 (2026-08-08 추가)

레일에 목록을 상주시킨 것만으로는 부족했다. **nav와 목록이 같은 것을 가리키고 같은 표시를 써서
전체가 평평하게 읽혔다.** 아래 셋이 그 상태로 되돌아가는 것을 막는다.

- MUST NOT: 주 nav에 `/screens` 행을 다시 넣지 않는다. 바로 아래 목록에서 모든 항목에 도달할 수
  있어 **인접한 두 레벨이 같은 것을 가리키게 된다.** `/screens`로 가는 길은 "내 전략" 구획
  제목의 관리 링크 하나이고, 그 링크는 `/screens`에서 활성으로 켜져야 한다 — 안 켜지면 그
  페이지에서 레일에 활성이 하나도 없다.
- MUST: 활성 표시를 두 층으로 가른다. **장소**(주 nav)는 `--current`(면만), **내용**(전략
  목록·새 전략)은 `--open`(accent 면 + 코발트 막대)이다. 같은 표시를 두 층에 쓰면 위 MUST NOT을
  지켜도 다시 평평해진다.
- MUST: 구획 제목("프리셋"·"내 전략")은 **제목의 크기와 무게**를 갖는다(본문 크기·700·본문 색).
  위계는 **항목을 들여쓰고 톤을 낮춰서** 만들지, 제목을 작고 흐리게 해서 만들지 않는다. 후자를
  먼저 시도했는데 제목이 분류로 읽히지 않고 항목 위에 붙은 꼬리표가 됐다 — 이 목록에서 "프리셋인가
  내 것인가"는 훑을 때 가장 먼저 답해져야 하는 질문이다. 들여쓰기는 다크에서도, 대비를 못 올리는
  자리에서도, 한국어에 대문자가 없어도 성립하는 유일한 위계 신호다.
- MUST: 활성 배경을 `:hover`·`:focus-visible`에도 **명시적으로 다시 적는다.** `main.css`의
  `.app-list-button:hover`와 scoped 규칙은 특정도가 같아(둘 다 0,2,0) 승자가 주입 순서로 정해진다.
  빠뜨리면 열린 항목에 마우스를 올리는 순간 틴트가 회색으로 되돌아간다.
- MUST NOT: 열려 있는 전략 행을 다시 눌러 스크리너로 돌아오게 만들지 않는다. `openByKey`가 서버에서
  정의를 다시 받아 **저장하지 않은 편집을 조용히 날린다.** 편집을 보존한 채 결과로 돌아가는 길은
  주 nav의 스크리너 행뿐이라, 그 행을 지우면 안 된다.
- MUST: 전체를 지우는 동작은 `composables/useFreshDraft`를 쓴다. 레일과 칩 바가 각자 구현했을 때
  한쪽은 확인만, 다른 쪽은 이동만 갖고 있었다.

## 검증

```bash
# 1. 조건 편집기가 지표 코드로 분기하지 않는가 (frontend.md의 MUST NOT)
rg -n "'(PER|PBR|ROE|RSI_14|ADJ_CLOSE|SMA_)" frontend/src/components/condition/
#    → 아무것도 나오지 않아야 한다

# 2. 되돌리기와 미완성 표시가 살아 있는가
rg -n 'undoRemove|lastRemoved' frontend/src/stores/screenDraft.ts frontend/src/layouts/AppLayout.vue
rg -n 'incompleteCount' frontend/src/components/condition/ConditionChipBar.vue frontend/src/views/ScreenerView.vue

# 3. 서버가 준 조건 인덱스를 쓰는가
rg -n 'invalidIndex' frontend/src/stores/screening.ts frontend/src/views/ScreenerView.vue

# 4. 레일 주 nav에 /screens가 없는가 (2026-08-08)
rg -n 'to="/screens"' frontend/src/components/AppRail.vue
#    → 구획 제목의 관리 링크(rail__manage) 한 줄만 나와야 한다. rail__nav 안에 있으면 위반이다.

# 5. 전체 지우기가 한 곳에서만 나오는가
rg -n 'draft.reset\(\)' frontend/src/components frontend/src/composables
#    → useFreshDraft.ts만 나와야 한다
```

`screenDraft.spec.ts` 11건이 그대로 통과하는 것이 "모델을 건드리지 않았다"의 검증이다. 하나라도
깨지면 그리기만 바꾼 것이 아니다.

## 배경

조건 하나가 세로 90px에 컨트롤 여섯 개를 가로로 폈다. 460px짜리 `Select`에 "ROE" 세 글자가
들어갔고 조건 다섯 개면 화면 절반이 편집기였다. 그보다 나쁜 것은 배려의 부재였다 — 미완성
조건이 아무 표시 없이 실행에서 빠졌고, 삭제에 되돌리기가 없었고, 서버가 알려준 문제 조건의
자리를 화면이 쓰지 않았고, 무엇보다 **어떤 값이 좋은 값인지 알 방법이 없었다.**
마지막 항목은 `.claude/rules/frontend.md`가 MUST NOT으로 금지한 "지표를 값만 나열"에
조건 빌더가 정확히 해당한 것이다.

## 버린 대안

**세로 행 유지 + 개선.** 지표 피커와 값 프리셋만 붙이는 안. 배려 문제는 풀리지만 높이 문제가
남고, 조건이 늘수록 결과가 밀려나는 구조 자체는 그대로다. 결과가 화면의 대부분을 차지해야
한다는 것이 이 앱의 전제다.

**자동 실행(디바운스).** TradingView처럼 조건을 고치면 바로 반영하는 안. `/api/screening`이
전량 응답이라 타이핑마다 부르면 무겁고, 경량 카운트 엔드포인트는 Phase 6 범위다. "어긋남을
말한다"가 그 대안이며, 자백하는 화면은 자동 실행보다 정직하기까지 하다.

**OR·조건 그룹 중첩까지 함께.** 백엔드는 준비되어 있지만(`ScreenDefinitionValidator`의 검사 두 줄)
칩 바는 AND 평면을 전제한다. 트리를 칩으로 그리는 것은 별개의 설계 문제다.

**상단 가로 헤더 유지.** 브랜드·검색·사용자 메뉴가 작업 영역의 세로 공간을 상시로 먹는다.
이 앱에서 세로는 결과 행 수다.

## 결과

**쉬워지는 것.** 조건이 스무 개여도 바가 두 줄을 넘지 않는다. 화면 전환이 레일에서 1클릭이 되어
`/screens` 왕복이 사라졌다. 값 프리셋 덕분에 숫자를 모르는 사용자도 조건을 만들 수 있다.

**어려워지는 것.** 팝오버가 셋(편집기·지표 목록·설정)이라 서로의 열림/닫힘 순서를 신경 써야
한다. 위의 두 MUST NOT이 그 대가이고, 둘 다 실제로 화면을 죽였던 것이다.

**되돌리려면.** `components/condition/`의 다섯 파일과 `AppRail`을 걷어내고 `ConditionBuilder`·
`ConditionRow`를 되살린다(`16366f7` 이전). 스토어·API·저장된 화면은 영향받지 않는다 —
바뀐 것은 그리는 방식뿐이다.
