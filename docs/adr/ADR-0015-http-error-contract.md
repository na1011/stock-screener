---
id: ADR-0015
status: 채택됨
date: 2026-08-06
applies_to:
  - "src/main/java/com/stockscreener/web/**"
  - "src/main/java/com/stockscreener/**/web/**"
---

# ADR-0015. HTTP 오류는 ProblemDetail로 내고 catch-all 핸들러를 두지 않으며 재시도 가능성은 상태 코드에서 유도한다

## 결정

모든 API 오류를 RFC 7807 `application/problem+json`으로 낸다. `spring.mvc.problemdetails.enabled: true`를 켜고, `ApiExceptionHandler extends ResponseEntityExceptionHandler`가 **아는 예외만** 추가로 처리한다. 자동 재시도 가능 여부는 **1차로 상태 코드**에서 유도하고, 바디의 `retryable`은 서버가 더 잘 아는 경우에만 그것을 덮는다.

## 규칙

- MUST NOT: `@ExceptionHandler(Exception.class)` 또는 `(Throwable.class)`를 만들지 않는다. 알려지지 않은 예외는 잡지 않고 흘려보낸다.
- MUST: 오류 종류를 `ApiProblemType`의 `urn:stockscreener:*` 값으로 식별한다. 프론트는 한글 메시지가 아니라 이 값으로 분기한다.
- MUST: 조건 검증 실패는 **조건 인덱스**를 응답에 담는다. 도메인 record의 `IllegalArgumentException`을 그대로 흘리지 않는다.
- MUST: 프론트의 재시도 판별이 바디 없이도 성립해야 한다 — `5xx`·`408`·`429`는 재시도 대상, 그 외 `4xx`는 사용자 조치, `401`은 재인증.
- MUST NOT: 오류 바디에 스택트레이스를 담지 않는다.
- MUST: 사용자 입력 문제는 4xx로 낸다. 5xx로 나가면 그것은 매핑을 빠뜨린 것이다.

## 검증

```bash
grep -rnE '^\s*@ExceptionHandler\((Exception|Throwable)\.class\)' src/main/java/
```
아무것도 반환하지 않아야 한다. (javadoc의 언급은 걸리지 않도록 행 시작을 고정한다.)

```bash
./gradlew test --tests "*ReadApiTest*" --tests "*ScreeningApiContractTest*"
```
잘못된 JSON이 `problem+json` 400으로 나가고 `retryable`이 실리며 스택트레이스가 없는 것, 보존 밖 일자가 400 + 보존 구간을 담는 것, 없는 지표 참조가 `conditionIndex`를 담는 것을 확인한다.

```bash
grep -n 'problemdetails' src/main/resources/application.yaml
```
`enabled: true`가 있어야 한다. 끄면 프레임워크 예외만 다른 형식으로 나간다.

## 배경

이 저장소에는 Phase 4 이전까지 **HTTP 계층이 아예 없었고** 오류 규약도 없었다. `docs/`와 `.claude/rules/`를 통틀어 REST·오류 형식을 정한 문서가 없다. 그래서 이 결정이 규약을 처음 세운다.

catch-all을 금지하는 이유는 전역 CLAUDE.md의 규칙이기도 하지만, 더 실질적으로는 **Phase 6의 Sentry** 때문이다. catch-all이 있으면 무엇이 수집되고 무엇이 삼켜지는지가 핸들러 등록 순서에 좌우되는 상태가 되고, 그것은 사후에 확인하기 어렵다.

재시도 판별을 상태 코드에서 유도하는 이유는 **누락에 견디게** 하기 위해서다. "서버가 `retryable`을 채운다"는 규칙은 핸들러 하나를 빠뜨린 응답이 프론트의 재시도 로직을 깨뜨린다. 상태 코드는 프레임워크가 항상 채우므로 Boot 기본 오류 바디로 떨어져도 프론트가 계속 동작한다. spec §8과 `frontend.md`가 요구하는 "일시적 오류는 자동 짧은 재시도, 안 되면 수동 재시도"가 여기 걸린다.

## 버린 대안

- **예외마다 `@ExceptionHandler` 하나씩** — 어느 예외가 어떤 상태로 나가는지가 여러 곳에 흩어지고, 새 예외를 추가하며 핸들러를 빠뜨리면 500으로 나간다. 상태와 종류를 예외 자신이 들고 다니는 `ApiException` 계층으로 대체했다.
- **`retryable`만으로 판별** — 표현이 명시적이지만 서버가 기억해야 성립한다. 위 배경 참조.
- **자체 오류 봉투(`{code, message, data}`)** — 익숙하지만 표준을 버리는 대가가 있다. `ProblemDetail`은 Spring 6부터 내장이고 프레임워크 예외가 이미 그 형식으로 나온다. 자체 형식을 쓰면 그것들을 전부 다시 감싸야 하고, 감싸지 못한 경로가 남는다.
- **`type`에 `https://` URL** — RFC 7807은 URI를 요구하되 역참조 가능할 필요는 없다고 명시한다. `https://` 주소를 쓰면 그 문서가 실제로 존재해야 할 것 같은 인상을 주고, 존재하지 않으면 그 자체가 부채가 된다.
- **`DataIntegrityViolationException`을 일괄 409로 매핑** — 편하지만 우리 코드의 NOT NULL 위반 같은 **진짜 버그**를 사용자 입력 충돌로 위장한다. 저장소가 어느 제약인지 아는 지점에서 타입 있는 예외로 바꾼다.

## 결과

- 쉬워지는 것: 새 엔드포인트가 `ApiException` 하위 타입을 던지기만 하면 규약을 따른다. 프론트는 오류 처리 코드를 한 벌만 쓴다.
- 어려워지는 것: 새 오류 종류마다 `ApiProblemType`에 상수를 더해야 한다. 그것이 의도다 — 오류 종류가 늘어나는 것을 눈에 보이게 한다.
- 되돌리려면: `com.stockscreener.web` 패키지 전체와 프론트의 오류 처리를 함께 고쳐야 한다.
