---
paths:
  - "build.gradle"
  - "settings.gradle"
  - "src/main/resources/application*.yaml"
  - "src/test/resources/application-test.yaml"
  - "src/main/resources/db/migration/**"
  - "frontend/package.json"
  - "frontend/vite.config.ts"
  - ".env.example"
---

# 설정 파일의 함정

아래 파일들은 "로직이 아니라 돌아가는 시스템의 모양"을 바꾸고, 규칙이 전부 **값이 틀려도 에러가 안 나는** 형태다. 컴파일도 되고 대개 테스트도 통과한 뒤, 무관해 보이는 곳에서 증상이 나온다.

## `build.gradle`

- **MUST NOT: `.env`를 `test` 태스크에 주입하지 않는다.** 실키가 있으면 `@ConditionalOnApiKey` 어댑터가 등록돼 **빈 구성 자체가 바뀐다.** 단위 테스트는 외부 호출 0건이 전제다. `.env`는 `bootRun`·`liveTest`에만 들어가고, 실제 외부 호출은 `@Tag("live")` + `./gradlew liveTest`에만 둔다. 주입 실패는 조용하므로 `./gradlew checkEnv`로 가른다.
- **프론트 번들에 환경변수를 실으려면 `applyDotEnv`를 `frontendBuild`에도 걸어야 한다.** 지금은 `bootRun`·`liveTest`에만 걸려 있고, Vite의 `envDir`이 `frontend/`라 **루트 `.env`를 읽지 않는다** — `VITE_SENTRY_DSN`을 채워도 번들에는 아무것도 안 들어간다. 걸 때 **`inputs.property`로 그 값을 함께 선언한다**: 안 하면 값만 바뀐 재빌드에서 Gradle이 UP-TO-DATE로 판정해 **캐시된 옛 번들을 그대로 싣는다.**
- **`./gradlew build`가 `frontend/`를 함께 빌드한다.** `processResources`가 `frontend/dist`를 `static/`으로 싣고 `check`가 Vitest를 돈다. Node가 없으면 `-PskipFrontend`(번들이 빠져 `SpaFallbackTest`가 실패한다). `npm` 경로는 빌드가 직접 해석한다 — Homebrew 경로가 `/etc/paths`에 없어 IDE 데몬에서 조용히 깨지기 때문이다.

## `src/main/resources/application.yaml`

- **`spring.batch.job.enabled=false`와 `pipeline.scheduler.enabled` 기본 꺼짐을 유지한다.** 켜면 애플리케이션을 띄우는 것만으로 배치가 돌아 외부 API 쿼터를 태운다.
- **MUST NOT: `server.forward-headers-strategy: framework`를 지우지 않는다.** cloudflared가 로컬에 평문 HTTP로 붙어 원래 스킴이 `X-Forwarded-Proto`에만 남는다. 지우면 `redirect_uri`가 `http://`로 나가 도메인 로그인만 깨지는데 **localhost 개발과 테스트는 멀쩡하다** — 헤더가 없어 분기 자체를 안 타기 때문이다.

## `src/test/resources/application-test.yaml`

- **커넥션 풀은 2로 제한한다.** 기본값 10이면 무관한 테스트가 "too many clients"로 깨진다(실제 14건).
- **`out-of-order: true`가 필요하다.** 테스트 전용 V900이 운영 마이그레이션보다 위에 있다.
- `clean-on-validation-error: true`가 아직 남아 있다 — 제거 대상이다.

## `src/main/resources/db/migration/**`

- **마이그레이션 체크섬이 어긋나면** `clean-on-validation-error`가 항상 구해주지 않는다. 해당 `flyway_schema_history` 행과 객체를 지우고 재적용한다.
- **적용된 마이그레이션 파일은 고치지 않는다.** 실데이터가 들어 있어(적재 2026-08-06) 재적용이 곧 재적재다.

## `frontend/package.json`

- **MUST: `primevue`는 4.x에 고정한다**(ADR-0019). 5.0.0은 MIT가 아니라 라이선스 키를 요구하고, 없거나 만료되면 화면에 고지가 뜬다.

## `.env.example`

- **MUST NOT: 실제 값을 적지 않는다.** 플레이스홀더만 둔다. `.env` 자체는 `settings.json`의 `deny`와 `guard-secrets.sh`가 막지만, `.env.example`은 **열람이 허용돼 있어** 아무것도 막지 않는다.
