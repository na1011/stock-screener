---
id: ADR-0031
status: 채택됨
date: 2026-08-09
applies_to:
  - "src/main/java/**/auth/SecurityConfig.java"
  - "src/test/java/**/auth/SecurityConfigTest.java"
  - "ops/**"
---

# ADR-0031. actuator는 `health` 하나만 열고, 그것을 익명 공개로 둔다

## 결정

`spring-boot-starter-actuator`를 도입하되 웹으로 노출하는 엔드포인트는 `health` **하나**다. 그 하나는 익명에게 열고 상세는 감춘다(`show-details: never`). 메일 health 인디케이터는 끈다.

## 규칙

- **MUST: `management.endpoints.web.exposure.include`를 `health`로 유지한다.** 늘리려면 그 엔드포인트가 무엇을 노출하는지와 왜 필요한지를 먼저 답한다. `SecurityConfig`가 `/actuator/**`를 관리자로 잠그지만, 노출을 좁혀 두면 그 매처가 잘못돼도 새어 나갈 것이 없다 — 계층이 둘이어야 하나가 무너져도 남는다.
- **MUST NOT: `management.health.mail.enabled: false`를 지우지 않는다.** `spring-boot-mail`이 `MailHealthContributorAutoConfiguration`을 함께 싣고, 그 인디케이터는 SMTP에 **실제로 접속을 시도한다.** 실패하면 `/actuator/health`가 통째로 503이 되고, 아래 워치독과 재부팅 검증이 그것을 "앱이 안 떴다"로 읽는다. 자격증명이 채워진 뒤에도 켜지 않는다 — **메일 서버가 잠깐 느린 것과 앱이 죽은 것은 다른 사건**이고, 여기서 섞으면 후자를 놓친다.
- **MUST NOT: `show-details`를 `always`나 `when-authorized`로 올리지 않는다.** 익명에게 열려 있으므로 상세가 곧 DB 주소·디스크 용량의 공개다.
- **MUST: `/actuator/health`의 익명 200을 지키는 테스트를 유지한다**(`SecurityConfigTest.healthIsPubliclyReadable`). 인가 규칙과 노출 설정 **둘 다** 성립해야 200이므로, 한쪽만 무너져도 이 테스트가 걸린다.
- **MUST NOT: `WebMvcConfig.BACKEND_PREFIXES`에서 `actuator/`를 빼지 않는다.** 빼면 없는 actuator 경로가 404 대신 `index.html`을 200으로 돌려주고, 워치독은 그것을 "살아 있다"로 읽는다 — 앱이 반쯤 죽어도 초록이 된다.

## 검증

```bash
# 실행 중인 앱에 대해 (2026-08-09 실측값을 주석에 적었다)
curl -s  http://localhost:8080/actuator/health   # {"groups":["liveness","readiness"],"status":"UP"}
curl -so /dev/null -w '%{http_code}\n' http://localhost:8080/actuator/env         # 401
curl -so /dev/null -w '%{http_code}\n' http://localhost:8080/actuator/beans       # 401 (노출도 안 됨)
curl -so /dev/null -w '%{http_code}\n' http://localhost:8080/actuator/nonexistent # 401 — SPA 폴백이 안 먹는다

# 빌드가 판정하는 부분
./gradlew test --tests "com.stockscreener.auth.SecurityConfigTest"
```

## 배경

Phase 6의 남은 판정 하나가 "재부팅 후 사람 개입 없이 앱과 터널이 다시 뜬다"이다. 그것을 확인하려면 **밖에서 물을 곳**이 필요한데, 지금은 포트가 열렸는지밖에 볼 수 없다. 포트는 DB가 죽은 채 뜬 앱에서도 열리고, `/`는 정적 셸이라 백엔드가 반쯤 죽어도 200을 준다. 둘 다 "살아 있다"의 근거로 쓸 수 없다.

`/actuator/health`를 익명에 여는 것은 그 대가다. 대신 상세를 감춰 나가는 것은 상태 한 글자와 그룹 이름뿐이다(`{"groups":["liveness","readiness"],"status":"UP"}` — 실측). 이 교환을 택한 이유는 **판정 한 줄이 밖에서 되는 것**의 가치가 크기 때문이다 — `curl https://screener.na1011.net/actuator/health` 하나가 앱·터널·절전 셋을 동시에 판정한다(502면 앱, 무응답이면 터널이나 절전).

## 버린 대안

- **`/actuator/health`도 관리자 전용으로 잠근다.** 그러면 워치독이 OAuth 세션을 들고 있어야 하고, 재부팅 직후 로그인 없이 확인할 수단이 사라진다. 판정하려고 만든 것이 판정을 못 하게 된다.
- **health 대신 `/api/…` 중 하나를 프로브로 쓴다.** 전부 인증 뒤에 있어 익명은 401이다. 401을 "살아 있다"로 읽는 프로브는 앱이 죽어 프록시가 401을 대신 낼 때 구분되지 않는다.
- **actuator 없이 TCP 포트만 본다.** DB가 죽은 채 뜬 앱과 구분되지 않는다. 실제로 이 앱은 Flyway·JPA가 기동 시 DB를 요구하므로 대개 못 뜨지만, "대개"에 기대는 프로브는 프로브가 아니다.
- **메일 인디케이터를 켜 둔 채 health 그룹에서만 뺀다.** 설정이 두 곳으로 갈리고, SMTP 접속 시도 자체는 계속 일어난다.

## 결과

- **쉬워지는 것**: 재부팅·배포 후 확인이 `curl` 한 줄이 된다. launchd `KeepAlive`가 "떴다"고 말하는 것과 앱이 실제로 동작하는 것을 구분할 수 있다.
- **어려워지는 것**: `{"status":"UP"}`이 외부에 공개된다. 이 서비스가 존재한다는 사실 이상은 나가지 않지만, 엔드포인트 하나가 인증 밖에 있다는 것은 사실이다.
- **되돌리려면**: `build.gradle`의 의존성, `application.yaml`의 `management:` 블록, `SecurityConfig`의 두 매처, `SecurityConfigTest`의 두 테스트, 그리고 `ops/`의 프로브를 함께 건드려야 한다.
