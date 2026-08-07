---
id: ADR-0020
status: 채택됨
date: 2026-08-07
applies_to:
  - "src/test/resources/contract/**"
  - "frontend/src/api/**"
  - "src/main/java/**/web/**"
---

# ADR-0020. 백엔드-프론트 계약을 체크인된 픽스처로 고정한다

## 결정

백엔드 enum·상수·problem 확장 속성의 **와이어 값**을 `src/test/resources/contract/wire-contract.json` 한 파일에 적고, 백엔드 JUnit(`WireContractTest`)과 프론트 Vitest(`api/contract.spec.ts`)가 **각자 그 파일을 읽어 자기 쪽과 대조**한다. 어느 쪽이 값을 바꾸든 반대쪽 테스트가 실패한다.

IMPORTANT — 이 파일은 **세 번째 정본이 아니라 트립와이어**다. 값의 정본은 언제나 백엔드 코드이고, 픽스처를 고치는 순간이 "반대편도 봐야 한다"고 강제되는 순간이다.

## 규칙

- MUST: 프론트가 서버 enum을 거울로 들 때는 `as const` 배열에서 타입을 파생시킨다(`export const X = [...] as const; export type X = typeof X[number]`). 유니온만 있으면 런타임에 대조할 것이 없다.
- MUST: 서버 값을 프론트에 적을 때(`api/types.ts`의 유니온, `api/limits.ts`의 숫자, `api/http.ts`의 `ProblemType` URN) **같은 값을 픽스처에도 적는다.**
- MUST: 픽스처는 `src/test/resources/contract/` 아래에 둔다. 그래야 Gradle `test`가 입력으로 인식해 픽스처만 고쳐도 재실행한다(실측 확인).
- MUST: `frontendTest`에 `outputs`를 선언하지 않는다. **선언하지 않은 덕분에 Gradle이 이 태스크를 항상 실행하고**(`--info`가 "Task has not declared any outputs despite executing actions"로 알려준다), 그래서 프론트 쪽 대조가 건너뛰어지지 않는다. 캐시를 켜려고 `outputs`를 붙이는 순간 `inputs`가 비로소 load-bearing이 되므로, 그때는 픽스처가 `inputs`에 들어 있는지부터 확인한다 — 그 줄은 미리 넣어 두었다.
- MUST: enum은 **집합으로** 단언한다. 순서는 계약이 아니라 프론트의 표시 판단이다(`lib/indicatorCategory`의 랭크가 그 예다).
- MUST NOT: 픽스처를 실행 코드에서 import하지 않는다. 브라우저 번들로 나가서는 안 되고, 값의 정본이 되어서도 안 된다.
- MUST NOT: 프론트가 나열하지 않는 enum을 픽스처에 넣지 않는다. `ComparisonOperator`가 그렇다 — 목록의 정본은 서버이고 `/api/indicators`가 라벨과 함께 내려준다. 픽스처에 넣으면 프론트가 그것을 나열하고 싶어진다.

## 검증

```bash
./gradlew test --tests "*WireContractTest" && ./gradlew frontendTest
```

장치가 실제로 붙어 있는지는 **일부러 깨뜨려 확인한다**(통과가 곧 "검사하지 않음"일 수 있다).

```bash
# 1. 백엔드가 값을 늘리면 백엔드 테스트가 잡는다
#    IndicatorCategory.java에 더미 상수 추가 → WireContractTest 실패 → 되돌린다
# 2. problem 확장 속성 이름이 바뀌면 잡는다
#    ApiExceptions의 with("retentionFrom", …)을 오타 → WireContractTest 실패 → 되돌린다
# 3. 프론트 유니온이 낡으면 프론트 테스트가 잡는다
#    api/types.ts의 OUTCOME_STATUSES에서 하나 제거 → contract.spec.ts 실패 → 되돌린다
```

셋 다 2026-08-07에 실제로 깨뜨려 실패하는 것을 확인했다.

`frontendTest`가 정말 매번 도는지는 이렇게 확인한다 — 아래가 `UP-TO-DATE`를 출력하기 시작하면
위 규칙이 깨진 것이고, 픽스처가 `inputs`에 들어 있는지 확인해야 한다.

```bash
./gradlew frontendTest --info | grep -A2 "Task ':frontendTest' is not up-to-date"
```

## 배경

프론트는 백엔드 enum 전부를 손으로 적은 TS 유니온으로 들고 있고, 코드 생성도 대조 테스트도 없었다. `ScreeningApiContractTest`는 `"MATCHED"` 같은 문자열을 인라인으로 단언하므로 **이름이 바뀌어도 그대로 통과한다** — 백엔드와 함께 바뀌기 때문이다.

이 패스에서 실제로 발견된 것 둘이 위험의 크기를 보여준다. 하나는 `IndicatorCategory`로, 값을 하나 늘리면 그 분류의 지표 전체가 조건 빌더에서 **선택 불가능해지는데 아무 신호가 없었다**. 다른 하나는 `retentionFrom`·`retentionTo`인데, 이 이름은 **Java 컴파일러와 TS 컴파일러 양쪽 모두에게 보이지 않는다** — 서버는 `with("retentionFrom", …)`로 문자열 키를 얹고 프론트는 `[key: string]: unknown`으로 꺼낸다. 이름이 어긋나면 날짜 선택기의 범위가 조용히 사라지고, 사용자는 같은 보존 오류를 만나며 날짜를 하나씩 다시 찍는다. 그 필드가 존재하는 이유가 정확히 그것을 막는 것이었다.

혼자 하는 저장소에서 양쪽을 같은 커밋으로 고치니 불필요해 보인다는 반론이 있다. 그러나 이 장치가 잡는 것은 "다른 팀이 바꿨다"가 아니라 **"Phase 6 관리자 화면 때문에 enum에 값을 하나 더하고 프론트를 열지 않았다"**이다. Phase 6은 배치 상태·알림 채널·트리거 종류 같은 새 enum이 실제로 생기는 phase다.

## 버린 대안

- **OpenAPI codegen(`springdoc` + `openapi-typescript`)** — 새 의존성 + 빌드 단계 + 생성 파일 리뷰 부담을 얹어, 170줄로 되는 일을 대체한다. 게다가 `retentionFrom` 같은 **동적 problem 확장 속성은 스키마에 나타나지 않아** 가장 위험한 항목을 못 잡는다. API가 Phase 6에서 배가되면 별도 ADR로 재검토한다.
- **런타임 응답 검증(zod류)** — 의존성이 늘고 매 응답마다 비용이 든다. 그리고 실패 시점이 **사용자 화면**이다. 이 문제는 빌드에서 잡아야 하는 것이지 운영에서 잡을 것이 아니다.
- **"그냥 조심한다"** — 지금까지 그렇게 해왔고, 이 패스에서 위 두 결함이 나왔다.
- **픽스처 없이 `as const` 배열 + degrade만** — 런타임 방어로는 유용해서 **함께 채택했지만**(`lib/indicatorCategory`가 모르는 분류를 뒤에 붙이는 것이 그것이다) 이것만으로는 조용하다. 값이 늘어난 사실을 아무도 모른 채 낯선 코드가 화면에 뜰 뿐이다.

## 결과

백엔드에서 enum·상수·problem 속성을 바꾸면 반대쪽을 고치기 전까지 빌드가 통과하지 않는다. 대신 값을 하나 더할 때 고칠 곳이 셋이 된다(코드·픽스처·프론트) — 그 셋째가 이 결정이 사는 비용이고, 픽스처를 고치는 순간이 프론트를 봐야 한다는 신호다.

되돌리려면 `WireContractTest.java`, `frontend/src/api/contract.spec.ts`, 픽스처 JSON, `build.gradle`의 `inputs.file` 한 줄을 지운다. 지워도 컴파일되고 테스트도 통과한다 — 그래서 여기 기록한다.
