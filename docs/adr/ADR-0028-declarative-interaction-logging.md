---
id: ADR-0028
status: 채택됨
date: 2026-08-08
applies_to:
  - "src/main/java/com/stockscreener/interaction/**"
  - "src/main/java/com/stockscreener/web/InteractionInterceptor.java"
  - "src/main/resources/db/migration/V13__interaction_log.sql"
---

# ADR-0028. 인터랙션은 애너테이션으로 표시하고 인터셉터 하나가 수집한다

## 결정

기록 대상 행위를 `@RecordInteraction(InteractionAction.…)`으로 **핸들러 메서드에 표시**하고, 수집은
`InteractionInterceptor` 하나가 `afterCompletion`에서 한다. 저장은 `interaction_log` 한 테이블이다.

기록 실패는 **요청을 깨뜨리지 않되 ERROR 로그로 남긴다** — `InteractionRecorder`가
`DataAccessException`만 좁게 잡는다.

**요청 본문은 저장하지 않는다.** `detail`에는 URI 템플릿 변수와 애너테이션이 지정한 쿼리
파라미터만 담는다.

로그인만 예외적으로 이벤트(`InteractiveAuthenticationSuccessEvent`)로 받는다. 필터가 처리하는
흐름이라 애너테이션이 붙을 핸들러가 없다.

## 규칙

- MUST: 새 엔드포인트의 기록은 `@RecordInteraction` 한 줄로 끝낸다.
- MUST NOT: 컨트롤러 본문에서 `InteractionRecorder`를 직접 부르지 않는다. 손으로 넣는 방식은
  반드시 누락이 생기고, **누락된 사실은 그 행위를 조회하려 할 때에야 드러난다.**
- MUST NOT: 단순 조회(목록·상세·검색)에 표시를 붙이지 않는다. 화면을 여는 것만으로 발생해
  로그를 채우고, 그러면 정작 의도가 드러나는 행위가 그 사이에 묻힌다.
- MUST: `InteractionAction`에 값을 더하면 `src/test/resources/contract/wire-contract.json`과
  프론트의 `INTERACTION_ACTIONS`에 함께 적는다(ADR-0020). 어긋나면 새 행위가 관리자 화면의
  **필터 선택지에서 조용히 빠지고**, 그 행위의 로그는 조회할 방법이 없어진다.
- MUST: `InteractionRecorder`는 던지지 않되 침묵하지도 않는다. 잡는 예외를 넓히지 않는다 —
  넓히면 우리 코드의 버그까지 삼켜 **기록이 안 되는 상태가 정상처럼 보인다.**
- MUST NOT: 요청 본문을 `detail`에 담지 않는다.
- MUST NOT: `postHandle`에서 기록하지 않는다. 예외가 나면 호출되지 않아 **실패한 시도가 통째로
  빠진다.**

## 검증

```bash
./gradlew test --tests "com.stockscreener.interaction.*" --tests "*WireContractTest*"
```

컨트롤러에 로깅 코드가 새어 들어가지 않았는지:

```bash
rg "InteractionRecorder" src/main/java --files-with-matches | rg -v "interaction/|web/InteractionInterceptor"
```
아무것도 반환하지 않아야 한다.

## 배경

spec §8의 첫 NFR이 요구하는 것은 둘이다 — "기록할 가치가 있는 행위만 선별적으로" 남길 것과,
"매 기능을 개발할 때마다 로깅 코드를 별도로 작성해야 하는 부담이 없을" 것. 이 둘은 같은 방향이
아니다. 전부 남기면 선별이 없고, 손으로 남기면 부담이 있다. 애너테이션은 **선별의 정본을
코드에 두면서 수집 비용을 한 곳으로 모은다.**

`.claude/rules/backend.md`가 같은 것을 MUST NOT으로 이미 적어 두었다 — "기능마다 로깅 코드를
손으로 넣는 방식으로 구현하지 않는다."

## 버린 대안

**AOP `@Around` 어드바이스.** 메서드 인자에 접근할 수 있어 "어느 전략을 돌렸는가"까지 담을 수
있다는 이점이 실재한다. 그러나 셋을 잃는다. (1) 어드바이스는 예외가 **어떤 상태 코드로
번역될지 모른다** — `ApiExceptionHandler`가 그 뒤에 돌기 때문이다. 그래서 실패한 시도의 상태를
남길 수 없다. (2) URI 템플릿 변수를 다시 파싱해야 한다. 요청 속성에 이미 들어 있는 것을 두고
경로 문자열을 쪼개게 된다. (3) 프록시 경계가 하나 더 생긴다 — ADR-0007이 자기 호출로 겪은 그
경계이고, 그 함정은 조용하다. 무엇보다 인자 해석은 액션마다 갈라지므로, 결국 "기능마다 손으로
넣는 것"과 같은 모양이 된다.

**요청 본문을 캐싱해 `detail`에 담기.** `ContentCachingRequestWrapper`를 필터로 걸면 가능하다.
그러나 모든 요청의 본문이 메모리에 한 벌 더 생기고, 저장되는 것은 사용자가 만든 조건식 전문과
메모다. spec이 요구하는 것은 "누가 언제 어떤 주요 행위를 했는지"이고 그것은 액션과 대상 id로
답해진다. **얻는 것에 비해 저장하는 개인정보가 많다.**

**성공(2xx)만 기록.** 로그가 깨끗해지지만 사용자가 겪은 마찰이 통째로 안 보인다. "저장을
눌렀는데 400이 났다"는 운영자가 알아야 할 사실이고, 그것이 안 보이면 같은 실패가 반복돼도
아무도 모른다. `status_code`를 남기면 필터는 조회 시점에 할 수 있다.

**`AuthenticationSuccessEvent`로 로그인 잡기.** 세션이 살아 있는 매 요청의 재인증에서도
발화해 **로그인 한 번이 수백 건으로 늘어난다.** `InteractiveAuthenticationSuccessEvent`는
실제로 로그인 흐름을 마쳤을 때만 발화한다.

**`action`에 CHECK 제약 걸기.** 값을 더할 때마다 마이그레이션이 하나씩 늘어난다. 이 컬럼에
잘못된 값을 쓸 수 있는 경로는 애플리케이션 하나뿐이고, 값의 정본은 enum이다.

## 결과

**쉬워지는 것.** 새 엔드포인트의 기록이 한 줄이다. 어느 행위가 기록 대상인지 `InteractionAction`
하나만 보면 알 수 있고, 그것이 곧 관리자 화면의 필터 선택지가 된다.

**어려워지는 것.** 액션별 상세를 담을 수 없다. "PER 12 이하로 스크리닝했다"가 아니라
"스크리닝을 실행했다"까지만 남는다. 필요해지면 애너테이션에 표현식을 더하는 방향이 아니라,
그 행위 전용 컬럼을 두는 방향으로 간다 — 표현식은 컴파일러가 못 보고 조용히 틀린다.

**되돌리려면.** `interaction` 패키지와 `InteractionInterceptor`, `WebMvcConfig`의 등록,
V13 마이그레이션, 컨트롤러 6곳의 애너테이션, 그리고 계약 픽스처의 `InteractionAction` 항목을
함께 걷어낸다.
