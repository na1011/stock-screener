---
id: ADR-0025
status: 채택됨
date: 2026-08-08
applies_to:
  - "src/main/java/com/stockscreener/notification/**"
  - "src/main/java/com/stockscreener/auth/AppUserAlertRecipients.java"
---

# ADR-0025. 알림은 하나의 포트 뒤에서 채널로 팬아웃하고, 채널 활성 여부는 빈 등록 조건으로 표현하지 않는다

## 결정

`AlertPort`(소비자가 보는 단일 진입점)와 `AlertChannel`(팬아웃 대상)을 **다른 타입으로 나눈다.** 채널은 메신저 웹훅과 이메일 둘이다. **채널 빈은 자격증명 유무와 무관하게 항상 등록**하고, 활성 여부는 `AlertChannel.isConfigured()`가 판정한다. 활성 채널이 하나도 없으면 `AlertConfig.alertPort()`가 **같은 메서드 안에서** `LoggingAlertPort`를 돌려준다.

메신저 페이로드는 **Slack 형식 하나**만 만든다. Discord는 웹훅 URL 끝에 `/slack`을 붙여 같은 형식을 받는다.

새 의존성: `org.springframework.boot:spring-boot-starter-mail`.

## 규칙

- MUST: 소비자(배치·워치리스트·관리자)는 `AlertPort`만 부른다. `AlertChannel`을 직접 주입받지 않는다 — 받는 순간 "배치는 메신저로, 워치리스트는 메일로" 같은 분기가 호출부로 흩어지고, 그것이 spec §8이 금지한 통지 체계의 중복이다.
- MUST: `AlertChannel.deliver()`는 실패 시 **던진다.** 삼키면 `CompositeAlertPort`가 "아무 채널도 받지 못했다"를 알 수 없고, 마지막 방어선인 로그 폴백이 동작하지 않는다.
- MUST: `CompositeAlertPort.send()`는 **어떤 경우에도 던지지 않는다.** 알림은 부수 효과이며 웹훅이 죽었다고 배치가 실패해서는 안 된다.
- MUST: 전달된 채널이 0개면 폴백(`LoggingAlertPort`)이 받는다. **알림이 갈 곳 없이 끝나는 경로가 존재해서는 안 된다.**
- MUST NOT: 채널 등록을 `@Conditional`로 걸지 않는다. 걸면 "활성 채널이 하나도 없는 상태"를 판정하는 자리가 사라지고, `AlertPort`가 무엇이 될지가 여러 조건의 우연한 조합이 된다.
- MUST NOT: 메신저 채널에 사용자 대상 알림(`recipientUserId != null`)을 보내지 않는다. 메신저 채널은 운영자 채널 하나뿐이라 지인의 관심종목이 운영자 채널에 뜬다.
- MUST NOT: 사용자 대상 알림에 심각도 임계를 적용하지 않는다. 워치리스트 알림은 `INFO`이고 운영자 기본 임계는 `ERROR`라, 적용하면 **워치리스트 알림이 통째로 사라진다.**
- MUST NOT: 웹훅 URL·메일 자격증명을 yaml·로그·커밋에 넣지 않는다. **웹훅 URL은 그 자체가 자격증명이다** — 아는 사람은 누구나 그 채널에 글을 쓸 수 있다.
- MUST NOT: `notification`이 `auth`를 직접 부르지 않는다. 수신자 조회는 `AlertRecipientDirectory` 인터페이스를 지나고 구현은 `auth`에 둔다. 반대 방향이면 패키지 의존이 순환한다.
- MUST: `AlertSeverity`에 값을 더할 때 채널 임계 기본값(`AlertProperties`)이 여전히 맞는지 함께 본다. `atLeast`가 **선언 순서를 강도 순서로 읽으므로**, 중간에 끼워 넣으면 모든 임계가 조용히 밀린다.

## 검증

```bash
./gradlew test --tests "com.stockscreener.notification.*"
```

`AlertConfigTest`가 핵심이다 — **자격증명이 없을 때 `AlertPort`가 `LoggingAlertPort`이고, 채널 값을 채우면 `CompositeAlertPort`가 된다.** 단위 테스트로는 잡히지 않는 결함이다. 각 클래스는 저마다 올바르게 동작하고 틀린 것은 "어느 빈이 `AlertPort`가 되는가"뿐이기 때문이다.

소비자가 채널을 직접 잡고 있지 않은지:

```bash
rg "AlertChannel" src/main/java --files-with-matches | rg -v "notification/"
```
아무것도 반환하지 않아야 한다.

실채널 검증은 자격증명 발급 이후다(`TODO.md` §3 ⑤). 페이로드 형태와 실패 격리는 `mockwebserver`로 이미 검증되므로, 남는 것은 "그 URL이 정말 그 채널인가"뿐이다.

## 배경

`AlertPort`·`AlertEvent`·`AlertSeverity` 이음매는 Phase 2에 이미 있었고 배치가 그것을 부르고 있었지만, 등록된 구현이 `LoggingAlertPort` 하나라 **알림이 로그로만 떨어졌다.** spec §9의 "갱신 실패 시 담당자가 이메일/메신저로 즉시 인지"가 그래서 성립하지 않았다.

설계에서 가장 신경 쓴 것은 **알림이 조용히 사라지는 경로를 없애는 것**이다. 알림 체계의 고장은 원래 알리려던 사건보다 늦게 발견된다 — 아무 일도 일어나지 않은 것과 구분되지 않기 때문이다.

## 버린 대안

**채널을 `@ConditionalOnProperty`로 등록.** `stockscreener.alert.webhook.url: ${ALERT_WEBHOOK_URL:}`처럼 기본값이 빈 문자열이면 **속성은 존재하므로 조건이 참이 된다** — `ConditionalOnApiKey`가 같은 함정 때문에 만들어졌다. 그 어노테이션을 일반화해 재사용하는 것도 검토했으나, 조건부 등록 자체가 더 큰 문제를 낳는다: 채널이 하나라도 등록되면 `LoggingAlertPort`의 `@ConditionalOnMissingBean`이 물러나는데, **그 채널이 실제로 보낼 수 있는지는 별개**다. 웹훅 URL만 있고 메일이 없는 상태에서 웹훅이 죽으면 알림이 통째로 사라지고, 그 구성은 아무 테스트도 깨뜨리지 않는다. 활성 판정을 `isConfigured()`로 옮기면 등록 순서와 무관하게 한 메서드가 전부 결정한다.

**채널마다 Slack/Discord 페이로드 분기.** 형식이 실제로 다르다(`text` vs `content`). 그러나 Discord가 `/slack` 접미사로 Slack 형식을 받아주므로 분기 없이 끝난다. 분기를 두면 "지금 어느 메신저를 쓰는가"가 설정과 코드 양쪽에 나타나고, 채널을 바꿀 때 둘을 함께 고쳐야 한다.

**`AlertPort` 하나에 채널 목록을 넣기(타입 분리 없이).** 소비자가 `List<AlertPort>`를 받게 되어 "어느 것으로 보낼까"가 호출부의 질문이 된다. 타입을 나눈 실질적 이득은 **예외 계약이 갈린다**는 것이다 — 포트는 던지지 않고 채널은 던진다. 하나로 합치면 채널마다 try/catch를 반복하게 되고, 언젠가 하나가 그것을 빠뜨려 배치를 죽인다.

**억제 상태를 DB에 저장.** 재시작해도 억제가 유지된다는 이점이 있으나, 알림 경로가 DB에 의존하게 된다 — **DB 장애를 알리려는 알림이 DB 때문에 막힌다.** 재시작으로 억제가 풀리는 것은 오히려 맞는 동작이다: 재시작은 대개 배포나 장애 대응이고, 그 직후의 알림은 보고 싶은 것이다.

## 결과

**쉬워지는 것.** 채널 추가가 `AlertChannel` 구현 하나 + `AlertConfig`에 `@Bean` 한 줄이다. 소비자는 한 줄도 바뀌지 않는다. 자격증명 없이 CI·개발에서 전체 경로가 돌고, 알림은 로그로 떨어진다.

**어려워지는 것.** 채널이 늘어나면 `AlertConfig.alertPort()`의 인자가 늘어난다(`List<AlertChannel>` 주입이라 실제로는 늘지 않지만, 빈 정의는 늘어난다). 억제가 인스턴스 메모리라 애플리케이션을 여러 개 띄우면 중복 통지가 난다 — 이 프로젝트는 단일 인스턴스이므로 문제가 아니고, 문제가 되는 날은 인스턴스가 늘어나는 날이다.

**되돌리려면.** `AlertChannel`·`CompositeAlertPort`·`AlertConfig`·`AlertSuppressor`·`AlertRecipientDirectory`와 두 채널 구현을 지우고 `LoggingAlertPort`에 `@ConditionalOnMissingBean` `@Bean`을 되돌린다. `spring-boot-starter-mail`과 `spring.mail.*`·`stockscreener.alert.*` 설정도 함께 제거한다.
