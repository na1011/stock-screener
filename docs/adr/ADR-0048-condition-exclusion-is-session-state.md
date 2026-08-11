---
id: ADR-0048
status: 채택됨
date: 2026-08-11
applies_to:
  - "frontend/src/stores/screenDraft.ts"
  - "frontend/src/components/condition/**"
---

# ADR-0048. 조건을 실행에서 빼는 것은 화면만 아는 세션 상태다

## 결정

`DraftCondition.enabled`는 **프론트엔드에만 있다.** 서버·DB·와이어 계약 어디에도 대응하는 필드가 없다. 꺼진 조건은 **실행 요청에서만** 빠지고 **저장에는 그대로 들어간다.** 전략을 다시 불러오면 전부 켜진 상태로 시작한다.

## 규칙

- MUST NOT: `enabled`를 `src/test/resources/contract/wire-contract.json`에 넣지 않는다. 그 픽스처는 **서버 값을 프론트가 거울로 들 때**의 장치다(ADR-0020). 이것은 거울이 아니라 화면 고유의 상태이므로, 거기 등록하면 존재하지 않는 서버 필드를 지키는 계약이 생긴다.
- MUST NOT: `setEnabled()`가 `markDirty()`를 부르지 않는다. 이유는 아래 배경에 있다 — 프리셋이 **저장할 수 없는 편집 상태**로 잠금 해제된다.
- MUST: 실행 요청 형태는 `dirty`가 아니라 `dirty || hasDisabledCondition`으로 가른다. `presetCode`·`screenId`로 보내면 서버는 제외를 모르므로 **꺼 둔 조건까지 평가한다** — 껐는데 결과가 그대로다.
- MUST: 저장(`toConditionGroup`)은 `completeConditions`, 실행(`buildRequest`)은 `runnableConditions`를 쓴다. 둘이 같은 배열을 보게 되면 제외가 전략에서 지우는 것과 구별되지 않는다.
- MUST NOT: `enabled`를 `conditionDefaults()`에 넣지 않는다. 편집기의 [초기화]가 그 객체를 `Object.assign`으로 덮어쓰므로 **값을 비우는 동작이 제외까지 조용히 풀어 버린다.** 반환 타입의 `Omit<DraftCondition, 'key' | 'enabled'>`가 그것을 컴파일 단계에서 막는다.
- MUST NOT: 칩의 비활성 표시에 점선을 쓰지 않는다. 미완성이 이미 점선이고, 둘은 사용자가 해야 할 일이 정반대다(하나는 채우는 것, 하나는 그대로 두면 되는 것).

## 검증

```bash
# 서버 쪽에 enabled가 새지 않았는가 — 아무것도 나오지 않아야 한다
rg -n '"enabled"' src/test/resources/contract/wire-contract.json src/main/java src/main/resources/db

# 제외가 dirty를 올리지 않는가 — 회귀 테스트가 지킨다
cd frontend && npx vitest run src/stores/screenDraft.spec.ts -t '프리셋에서 조건을 빼도 읽기 전용이 유지된다'
```

## 배경

"이 조건 하나만 빼고 돌려보기"를 할 수 없었다. 지우고 다시 만들면 값을 전부 다시 넣어야 하고 `undoRemove`는 마지막 하나만 되살린다. 스윙 프리셋처럼 조건이 열 개를 넘는 전략에서 특히 그렇다.

**`markDirty()`를 부르지 않는 것이 이 결정의 핵심이다.** `readOnly = !editable && !dirty`이므로 프리셋에서 조건을 끄면 `dirty`가 올라 잠금이 풀리고, 칩 바의 `＋ 조건`·휴지통이 나타나며 **"복제해서 편집" 버튼이 사라진다.** 그런데 `canSave`는 서버가 준 `editable`을 보므로 여전히 false다 — **고칠 수는 있는데 저장할 길이 없는 상태**가 된다. 지금까지 이것이 드러나지 않은 이유는 읽기 전용 프리셋에서 `markDirty`에 닿는 경로가 하나도 없었기 때문이고(편집기가 통째로 잠긴다), 제외가 그 첫 번째 구멍이 될 뻔했다. 그리고 "조건 빼고 돌려보기"는 **프리셋에서 가장 쓰고 싶은 기능**이다.

실제로 필요한 것은 `dirty`가 아니라 **즉석 조건 경로를 타는 것**이었다. `hasDisabledCondition`이 그것만 연다.

## 버린 대안

- **백엔드까지 영속화(`is_enabled` 컬럼)** — `V15__condition_required.sql`이 같은 형태의 선례라 마이그레이션 자체는 한 줄이다. 그러나 저장·로드만으로는 부족하다: `dirty`가 아닐 때 서버가 저장된 정의를 그대로 실행하므로 `ConditionTranslator`·`ConditionEvaluator`도 제외를 알아야 하고, 그 순간 "이번 실행에서만 빼는 것"이 전략의 영구 속성이 된다. **용도가 시험이다** — 사용자가 정한 범위도 그것이다.
- **꺼진 조건을 저장에서 제외** — 저장된 전략을 열어 조건 하나를 끄고 다시 저장하면 그 조건이 영영 사라진다. 조용한 데이터 손실이고, 제외와 삭제를 같은 동작으로 만든다.
- **칩 안에 중첩 버튼** — 칩 루트가 `<button>`이라 HTML이 무효다. 루트를 `<span>`으로 바꾸고 안에 버튼 둘을 두되, `data-chip-key`와 `@click`은 **루트에 남겼다** — `.chip__main`으로 옮기려면 `inheritAttrs: false`가 필요하고 그러면 `onClick`까지 딸려가 마커·배지·캐럿 영역을 눌러도 편집기가 열리지 않는다.

## 결과

**얻은 것.** 프리셋을 포함해 어느 전략에서든 조건을 하나씩 빼며 결과를 비교할 수 있고, 그 과정이 저장본을 건드리지 않는다.

**대가.** 제외 상태는 새로고침·전략 전환·저장 후 재로딩에서 사라진다. 그래서 칩 바와 실행 버튼 툴팁, 저장 다이얼로그가 **그 사실을 말한다** — 말하지 않으면 결과가 왜 달라졌는지 알 수 없는 순간이 온다(ADR-0024가 미완성 조건을 조용히 버리지 않기로 한 것과 같은 이유다).

**되돌리려면.** `screenDraft`의 `enabled`·`runnableConditions`·`hasDisabledCondition`·`setEnabled`, `ConditionChip`의 두-버튼 구조, `conditionText`의 `disabledConditionNotice`, 그리고 `SaveScreenDialog`·`ConditionChipBar`·`ScreenerView`의 안내를 함께 걷어야 한다.
