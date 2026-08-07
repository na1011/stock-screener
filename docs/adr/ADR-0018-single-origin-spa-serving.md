---
id: ADR-0018
status: 채택됨
date: 2026-08-07
applies_to:
  - "build.gradle"
  - "frontend/vite.config.ts"
  - "src/main/java/**/web/WebMvcConfig.java"
  - "src/main/java/**/auth/SecurityConfig.java"
---

# ADR-0018. 프론트엔드를 같은 오리진에서 서빙하고 번들을 Gradle이 빌드한다

## 결정

프론트엔드는 저장소 루트 `frontend/`에 두고, Gradle의 `processResources`가 `frontend/dist`를 classpath의 `static/`으로 복사해 **`bootJar` 하나에 담는다.** 별도 배포하지 않는다. 깊은 링크는 `WebMvcConfig`의 폴백이 `index.html`로 돌려주고, 보안 매처는 `/api/**`만 인증을 요구하고 나머지(정적 셸)는 익명 접근을 허용하는 방향으로 뒤집는다.

## 규칙

- MUST: 프론트와 API는 같은 오리진에서 서빙한다. CORS 설정을 추가하지 않는다.
- MUST: `processResources`가 번들을 싣는다. `bootJar`에만 걸지 않는다 — `bootRun`이 낡은 번들을 서빙하면 그 차이는 "고쳤는데 왜 안 바뀌지"로만 드러난다.
- MUST: SPA 폴백은 `api/`·`actuator/`로 시작하는 경로에 적용하지 않는다. 적용하면 없는 API 경로가 404 대신 `index.html`을 200으로 돌려주고, 프론트는 그것을 JSON으로 파싱하다 엉뚱한 오류를 낸다.
- MUST: 보안 매처는 `/api/**`를 `authenticated`, `/api/admin/**`과 `/actuator/**`를 `ROLE_ADMIN`으로 명시한 뒤 `anyRequest().permitAll()`로 닫는다. `permitAll`이 마지막이므로 **새 보호 대상은 반드시 그 앞에 명시해야 한다.**
- MUST NOT: 번들(`frontend/dist`)과 `node_modules`를 커밋하지 않는다.
- MUST NOT: 개발 서버 프록시에 `changeOrigin`을 켜지 않는다. Host가 바뀌면 Spring이 만드는 OAuth `redirect_uri`가 :8080이 되어 인증 후 개발 서버를 벗어난다.

## 검증

```bash
# CORS 설정이 없어야 한다 (같은 오리진 전제의 실질 검증)
rg -n "CorsConfigurationSource|addCorsMappings|@CrossOrigin|\.cors\(" src/main/java   # 결과 없음

# 번들이 리소스에 실린다
./gradlew processResources && test -f build/resources/main/static/index.html

# SPA 폴백이 API를 삼키지 않는다 — SecurityConfigTest/WebMvcConfig 테스트가 단언한다
./gradlew test --tests "*SecurityConfigTest*" --tests "*SpaFallback*"
```

## 배경

`SecurityConfig`가 세션 쿠키(`JSESSIONID`) + `XSRF-TOKEN` 쿠키 기반이고 CORS 설정이 전혀 없다. 프론트를 다른 오리진에 두면 CORS preflight, `allowCredentials`(와일드카드 오리진 금지), `SameSite=None; Secure` 쿠키를 전부 떠안아야 하는데, 호스팅이 맥북 온프레미스 + Cloudflare Tunnel **단일 진입점**(spec §6)이라 오리진을 나눠서 얻는 것이 없다.

그리고 기존 매처가 `anyRequest().authenticated()` + `HttpStatusEntryPoint(401)`였다. 그대로 두면 로그인하지 않은 사용자의 **첫 페이지 로드 자체가 401**이라 SPA가 뜨질 못한다 — 로그인 버튼을 그릴 화면조차 없다. 서버 렌더링 로그인 페이지를 두지 않기로 한 이상(ADR-0004의 401 방침) 정적 셸은 익명에게 열려야 한다.

## 버린 대안

- **프론트 분리 배포(Cloudflare Pages 등)** — 오리진이 갈려 위의 쿠키·CORS 문제를 전부 떠안는다. 정적 호스팅 비용은 0이지만 백엔드는 어차피 온프레미스라 진입점이 둘이 되고, Tunnel 설정도 둘이 된다.
- **`src/main/resources/static`에 번들을 직접 커밋** — 빌드 도구가 없어도 되지만 소스와 번들이 어긋난 채 커밋되고, 어느 쪽이 정본인지 알 수 없게 된다.
- **`bootJar`에만 프론트 빌드를 건다** — `./gradlew test`가 빨라지지만 `bootRun`이 낡은 번들을 서빙한다. Gradle의 up-to-date 판정이 걸리므로 `processResources`에 걸어도 두 번째 빌드부터는 비용이 0이다.
- **SPA 라우트를 permitAll 목록에 하나씩 나열** — 라우트를 추가할 때마다 보안 설정을 함께 고쳐야 하고, 잊으면 그 화면만 401이 된다. 데이터는 전부 `/api/**` 뒤에 있으므로 정적 셸을 여는 편이 안전하고 단순하다. 대신 `/actuator/**`를 미리 잠가 Phase 6에서 actuator가 들어올 때 `permitAll`이 구멍이 되지 않게 한다.

## 결과

배포 산출물이 jar 하나로 유지되고 인증 흐름에 특별한 처리가 필요 없다. 대신 `./gradlew build`가 Node를 요구한다 — 없으면 `-PskipFrontend`로 API만 띄울 수 있고, `npm`을 찾지 못하면 빌드가 명시적으로 실패한다(Homebrew 경로가 `/etc/paths`에 없어 IDE 실행에서 조용히 깨지던 문제를 앞당겨 드러낸다).

되돌리려면 `build.gradle`의 프론트 태스크, `WebMvcConfig`의 폴백, `SecurityConfig`의 매처 순서, `vite.config.ts`의 프록시를 함께 고쳐야 하고, 오리진이 갈리는 순간 CORS 설정과 쿠키 속성이 새로 필요해진다.
