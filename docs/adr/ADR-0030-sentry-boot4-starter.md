---
id: ADR-0030
status: 채택됨
date: 2026-08-09
applies_to:
  - "src/main/java/**/observability/**"
  - "src/test/java/**/observability/**"
---

# ADR-0030. Sentry는 Boot 4 전용 스타터로 붙이고, 예상된 거절은 올리지 않는다

## 결정

백엔드 에러 트래킹을 `io.sentry:sentry-spring-boot-4-starter:8.52.0`으로 붙인다. DSN이 비어 있으면 수집만 꺼지고 애플리케이션은 그대로 뜬다. 4xx `ApiException`은 `beforeSend`에서 버린다. **DSN이 실린 경로를 `./gradlew test`가 밟도록 가짜 DSN을 쓰는 컨텍스트 테스트를 둔다.**

## 규칙

- **MUST: `io.sentry:sentry-spring-boot-4-starter`를 쓴다.** 이름이 비슷한 것이 셋 더 있고 고르는 순간 갈린다 — `sentry-spring-boot-starter`(Boot 2/javax), `sentry-spring-boot-starter-jakarta`(Boot **3**), `sentry-spring-boot-4-starter`(Boot **4**).
- **MUST NOT: `SentryAutoConfigurationSmokeTest`를 지우거나 그 `sentry.dsn` 프로퍼티를 빼지 않는다.** 그 순간 이 결정의 검증이 `bootRun`으로만 가능해지는데, **그 사실을 아무도 알려주지 않는다.**
- **MUST NOT: `Sentry.OptionsConfiguration` 빈을 `sentryOptionsConfiguration`이라는 이름으로 정의하지 않는다.** Sentry의 자동 구성이 그 이름에 `@ConditionalOnMissingBean(names = "sentryOptionsConfiguration")`을 걸고 있어, 같은 이름을 쓰면 자기 구성을 통째로 물러나게 한다 — 이벤트 프로세서·통합·전송 계층·in-app 패키지 판정이 전부 사라지는데 **아무 에러도 나지 않는다.** 확장은 `SentryOptions.BeforeSendCallback` 빈으로 한다.
- **MUST: `sentry.dsn`의 기본값을 빈 문자열로 둔다**(`${SENTRY_DSN:}`). 키가 없어도 프로퍼티는 "존재"해 자동 구성이 켜지고, SDK가 빈 DSN을 비활성으로 다뤄 수집만 꺼진다. 경로가 하나뿐이라 "키 있을 때만 도는 코드"가 생기지 않는다.
- **MUST: `send-default-pii: false`를 명시한다.** 기본값이지만 켜지면 로그인 이메일이 Sentry로 나간다. 기본값에 기대면 SDK 업그레이드가 조용히 뒤집을 수 있다.
- **MUST NOT: `sentry.logging`을 끄지 않는다.** `log.error`가 이벤트가 되는 그 경로가 **알림 코드 자체가 죽은 경우**를 잡는 유일한 수단이다 — `CompositeAlertPort`의 채널 실패는 로그로만 남는다.
- **MUST NOT: `sentry.traces-sample-rate`를 켜지 않는다.** 무료 티어의 월 이벤트 총량을 태우고, 여기서 필요한 것은 예상 못 한 예외이지 성능 프로파일이 아니다.
- **MUST: 4xx `ApiException`은 버리고 5xx는 올린다.** Sentry의 `sentryExceptionResolver`가 `DispatcherServlet`의 예외 경로를 **전부** 잡으므로, 거르지 않으면 `SCREEN_NOT_FOUND` 같은 예상된 거절이 이슈의 대부분을 차지하고 그때부터 진짜 예외도 함께 묻힌다.

## 검증

```bash
# ① DSN이 실린 채로 컨텍스트가 뜬다 — 빌드가 판정한다
./gradlew test --tests "com.stockscreener.observability.SentryAutoConfigurationSmokeTest"

# ② 4xx 필터가 산다
./gradlew test --tests "com.stockscreener.observability.SentryConfigTest"

# ③ DSN 없는 경로도 그대로 뜬다
./gradlew test --tests "com.stockscreener.StockScreenerApplicationTests"

# ④ 실제 의존성 선언이 Boot 4판인가 (주석의 언급은 세지 않는다)
grep -E "^\s*implementation 'io\.sentry:" build.gradle   # sentry-spring-boot-4-starter 한 줄만 나와야 한다

# ⑤ DSN이 진짜 살아 있는 프로젝트를 가리키는가 (실 API 호출, 이벤트 1건)
./gradlew liveTest --tests "*SentryLiveTest*"
```

**②③은 실측으로 확인했다(2026-08-09).** ①은 카나리로도 확인했다 — 의존성을 Boot 3판(`sentry-spring-boot-starter-jakarta:8.52.0`)으로 바꾸자 두 테스트가 `ClassNotFoundException`으로 **깨졌고**, Boot 4판으로 되돌리자 통과했다. 즉 이 테스트는 정말로 그 결함을 잡는다.

`bootRun` 실측(2026-08-09, 실 DSN): `--debug`의 조건 평가 보고서에 `SentryAutoConfiguration matched: @ConditionalOnProperty (sentry.dsn) matched`가 있고 `!Incompatible Spring Boot Version detected!`는 없으며 앱은 2.07초에 기동했다.

## 배경

2026-08-08 세션이 Sentry를 붙이려다 애플리케이션 기동에 실패했고, `.claude/rules/config.md`에 "Sentry의 Spring Boot 스타터는 Boot 4에서 애플리케이션을 죽인다"로 남겼다. **그 진단은 한 단계 넓었다.** 2026-08-09 Maven Central 실측 결과:

- `sentry-spring-boot-4-starter:8.52.0`의 POM이 `org.springframework.boot:spring-boot-starter:4.1.0`을 **직접 의존**한다.
- 내부 모듈 `sentry-spring-boot-4`가 `org.springframework.boot.restclient.RestClientCustomizer`(**Boot 4** 패키지)를 구현한다. 그때 없다고 나온 `org.springframework.boot.web.client.RestClientCustomizer`는 Boot **3** 패키지이고, 그것을 참조하는 것은 Boot 3판이다.
- `SentrySpringVersionChecker`가 `SpringBootVersion.getVersion().startsWith("4")`가 **아닐 때** 경고한다 — Boot 3판이 그 경고를 찍은 것 자체가 잘못된 아티팩트라는 신호였다.
- RestClient/RestTemplate/WebClient 가지가 전부 `@ConditionalOnClass`를 단 wrapper 안에 격리돼 있어, `spring-boot-restclient`가 클래스패스에 없어도(실제로 없다) 그 가지만 건너뛴다.

**즉 Boot 4에서 Sentry 스타터를 못 쓰는 것이 아니라 Boot 3용을 고른 것이 원인이었다.**

더 나쁜 부분은 진단이 아니라 **판정 수단**이었다. `.env`를 `test`에 주입하지 않는다는 규칙 때문에 테스트에는 `SENTRY_DSN`이 없고, 그러면 `@ConditionalOnProperty("sentry.dsn")` 가지가 **평가되지 않는다.** 435건이 전부 초록인데 앱은 못 떴다. 이 ADR의 절반은 그 상태를 없애는 것이다.

## 버린 대안

- **코어 SDK만 쓰고 자동 구성을 직접 배선한다.** 2026-08-08에 실제로 기동까지 확인된 경로다. 하지만 `sentryExceptionResolver`·`SentrySpringFilter`·사용자 컨텍스트·로그백 어펜더를 전부 손으로 다시 만들어야 하고, 그 코드는 SDK 업그레이드마다 어긋난다. 스타터가 정상 동작하는 것이 확인된 이상 유지 비용만 남는다.
- **`.env`를 `test` 태스크에 주입해 실 DSN으로 검증한다.** 규칙 위반이고, 그 규칙에는 별도 근거가 있다(`@ConditionalOnApiKey` 어댑터가 등록되어 빈 구성 자체가 바뀐다). 가짜 DSN 프로퍼티가 같은 목적을 달성하면서 그 부작용이 없다.
- **`sentry.ignored-exceptions-for-type`으로 `ApiException`을 통째로 무시한다.** 5xx까지 함께 사라진다 — 그것은 우리 쪽 문제라 반드시 봐야 한다.
- **`sentry.exception-resolver-order`를 낮춰 처리된 예외를 안 보게 한다.** 리졸버 순서에 의존하는 규칙이라 Boot이 순서를 바꾸면 조용히 뒤집히고, 로그백 경로로 들어오는 이벤트에는 애초에 적용되지 않는다. `beforeSend`는 두 경로 모두를 덮는다.

## 결과

- **쉬워지는 것**: 예상 못 한 예외가 사람 개입 없이 수집된다. 알림 코드가 죽어도 그 사실이 `log.error` 경로로 Sentry에 남는다. Sentry 관련 회귀가 `./gradlew build`에서 잡힌다.
- **어려워지는 것**: `@SpringBootTest` 컨텍스트가 하나 늘어 테스트 시간과 커넥션 풀 사용이 조금 증가한다(테스트 풀 상한이 2인 것과 함께 본다).
- **되돌리려면**: `build.gradle`의 의존성, `application.yaml`의 `sentry:` 블록, `observability` 패키지, 그리고 위 세 테스트를 함께 건드려야 한다.
