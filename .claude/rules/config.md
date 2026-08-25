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

- **`.env`를 애플리케이션에 싣는 것은 이 파일이다.** Spring Boot는 `.env`를 읽지 않고 dotenv 의존성도 없다 — `loadDotEnv`/`applyDotEnv`가 직접 읽어 주입한다. **MUST NOT: dotenv 의존성을 추가하지 않는다.** 두 경로가 생기면 어느 쪽이 이겼는지 증상으로 구분되지 않는다.
  - **실제 셸 환경변수가 `.env`를 이긴다.** 일회성 오버라이드를 파일 수정 없이 하기 위해서다.
  - **IDE에서 직접 실행하면 이 주입이 걸리지 않는다.** `./gradlew bootRun`을 쓰거나 IDE 실행 구성에 환경변수를 따로 넣어야 한다. 안 넣어도 애플리케이션은 정상 기동하고 브라우저 로그인만 안 되므로, 증상이 "코드 버그"처럼 보인다.
- **IMPORTANT — Sentry 아티팩트는 이름이 넷이고 고르는 순간 갈린다.** Boot 4용은 **`io.sentry:sentry-spring-boot-4-starter`** 하나다(`-jakarta`는 Boot **3**용, 접미사 없는 것은 Boot 2용). Boot 3용을 고르면 DSN이 실린 채 기동할 때 `!Incompatible Spring Boot Version detected!`를 찍고 `ClassNotFoundException: …RestClientCustomizer`로 컨텍스트가 죽는다 — **Boot 4에서 Sentry가 안 되는 것으로 읽히는 증상이다.** 근거는 ADR-0030.

- **MUST NOT: `.env`가 `test`에 없다는 이유로 "프로퍼티가 있어야 켜지는 자동설정"을 검증 없이 두지 않는다.** `@ConditionalOnProperty`가 걸린 가지는 그 값이 없는 테스트에서 **평가조차 되지 않아**, 435건이 전부 초록인데 앱이 못 뜬다. 처방은 `.env` 주입이 아니라 **형식만 유효한 가짜 값을 `@SpringBootTest(properties = …)`로 주는 것**이다. `SentryAutoConfigurationSmokeTest`(`sentry.dsn`)와 `SchedulerEnabledContextTest`(`pipeline.scheduler.enabled`)가 그 형태이고, 둘 다 실제로 결함을 잡았으므로 지우지 않는다.

- **`frontendBuild`의 `inputs.property`를 지우지 않는다.** 없으면 값만 바뀐 재빌드를 Gradle이 UP-TO-DATE로 판정해 **캐시된 옛 번들을 그대로 싣는다** — DSN을 채웠는데 번들에 아무것도 안 들어가고, 원인이 캐시라는 것은 드러나지 않는다. **MUST NOT: 시크릿을 값으로 선언하지 않는다.** Gradle은 입력 프로퍼티 값을 `.gradle/` 아래 태스크 이력에 평문으로 직렬화한다. `SENTRY_AUTH_TOKEN`은 존재 여부(boolean)만 넣는다.

- **`processResources`의 `exclude '**/*.map'`을 지우지 않는다.** 지우면 소스맵이 배포 jar에 실려 프론트 소스가 통째로 공개된다. Vite 플러그인의 "업로드 후 삭제"는 **업로드가 켜져 있을 때만** 돌고 지금은 꺼져 있다(ADR-0032).
- **`./gradlew build`가 `frontend/`를 함께 빌드한다.** `processResources`가 `frontend/dist`를 `static/`으로 싣고 `check`가 Vitest를 돈다. Node가 없으면 `-PskipFrontend`(번들이 빠져 `SpaFallbackTest`가 실패한다). `npm` 경로는 빌드가 직접 해석한다 — Homebrew 경로가 `/etc/paths`에 없어 IDE 데몬에서 조용히 깨지기 때문이다.

## `src/main/resources/application.yaml`

- **`pipeline.scheduler.enabled` 기본 꺼짐을 유지한다.**(`spring.batch.job.enabled`는 Spring Batch와 함께 걷혔다 — ADR-0075) 켜면 애플리케이션을 띄우는 것만으로 배치가 돌아 외부 API 쿼터를 태운다. 운영 인스턴스는 `ops/run-app.sh`가 실행 인자로 켠다 — **`application-prod.yaml`을 만들지 않는다**(`guard-secrets.sh`가 그 이름을 막고, 실은 "이 프로세스가 운영인가"는 파일이 아니라 실행 방식이 정할 일이다).
- **`management.health.mail.enabled: false`를 지우지 않는다.** `spring-boot-mail`이 SMTP에 실제로 접속하는 인디케이터를 함께 싣는다. 알림 자격증명이 없는 동안 `/actuator/health`가 통째로 503이 되고, 그 503을 배포 스크립트와 재부팅 검증이 "앱이 안 떴다"로 읽는다(ADR-0031).
- **IMPORTANT — `spring.lifecycle.timeout-per-shutdown-phase`(15s)와 `ops/stop.sh`의 앱 대기(45s)는 짝이다.** 한쪽만 고치면 배포가 조용히 깨진다. 앱은 도는 배치가 끝나기를 상한만큼 기다린 뒤에야 빈을 파괴하므로, 상한이 대기보다 길면 `stop.sh`가 정상 종료를 실패로 판정하고 `ops/deploy.sh`의 `set -e`가 재기동에 도달하지 못한다 — 앱은 이미 SIGTERM을 받았으니 **아무것도 안 도는 상태로 끝난다.** 기본값 30초가 옛 대기 30초와 겹쳐 실제로 그랬다(실측 30,061ms, ADR-0078). 반대 방향(`ops/*.sh`를 열 때)은 그 ADR의 `applies_to`가 알린다.
- **MUST NOT: `server.forward-headers-strategy: framework`를 지우지 않는다.** cloudflared가 로컬에 평문 HTTP로 붙어 원래 스킴이 `X-Forwarded-Proto`에만 남는다. 지우면 `redirect_uri`가 `http://`로 나가 도메인 로그인만 깨지는데 **localhost 개발과 테스트는 멀쩡하다** — 헤더가 없어 분기 자체를 안 타기 때문이다.

## `src/test/resources/application-test.yaml`

- **커넥션 풀은 2로 제한한다.** 기본값 10이면 무관한 테스트가 "too many clients"로 깨진다(실제 14건).
- **`out-of-order: true`가 필요하다.** 테스트 전용 V900이 운영 마이그레이션보다 위에 있다.
- **체크섬이 어긋나면 테스트도 함께 죽는다.** `clean-on-validation-error`는 Boot 4.1·Flyway 12.4에 **바인딩되지 않아 아무 일도 하지 않는다**(실측: 여덟을 어긋내니 테스트 컨텍스트가 전부 `FlywayValidateException`으로 죽었다). **MUST NOT: 「테스트는 어차피 낫는다」로 두지 않는다** — 두 DB가 같은 처방을 받고 `UPDATE`도 각각 돌린다. 값을 얻는 가장 싼 길이 `./gradlew build`다.

## `src/main/resources/db/migration/**`

- **적용된 파일을 고치면 기동이 막힌다.** Flyway가 파일 전체의 체크섬을 대조하므로 **주석 한 줄만 바꿔도** `FlywayValidateException`으로 컨텍스트가 죽는다.
- **처방은 편집의 종류가 정한다. 「고치지 않는다」가 걸리는 것은 앞쪽뿐이다.**
  - **스키마를 바꿨으면 고치지 않는다.** 이력 행과 객체를 지우고 재적용하는 것이 유일한 길인데 **실데이터가 들어 있어 재적용이 곧 재적재다.**
  - **주석만 바꿨으면 체크섬만 맞춘다**(`flyway repair`가 하는 일이고 실패 메시지도 그것을 시킨다). 실패가 `Applied to database`와 `Resolved locally`를 둘 다 찍고 **불일치 전부를 한 번에 신고하므로 부팅 한 번이면 값이 다 나온다.** `UPDATE flyway_schema_history SET checksum=<Resolved locally> WHERE version='<N>'` 뒤 `installed_rank`·`installed_on`·`execution_time`이 그대로다 — **재적용이 아니다.** **MUST: 운영 DB에서는 `ops/dump-db.sh`를 먼저 뜨고 같은 창에서 재배포까지 간다** — 배포된 jar가 옛 바이트를 들고 있어 체크섬만 고치면 **다음 재기동이 죽고 증상은 며칠 뒤에 난다.**

## `frontend/package.json`

- **MUST: `primevue`는 4.x에 고정한다**(ADR-0019). 5.0.0은 MIT가 아니라 라이선스 키를 요구하고, 없거나 만료되면 화면에 고지가 뜬다.

## `.env.example`

- **`DB_NAME`은 앱이 안 쓴다.** 앱은 `DB_URL`로 붙고, 이 값은 `ops/`가 `psql`로 도는 배치를 물을 때만 쓴다. 둘이 갈리면 그 경고가 **빈 결과를 내고 그것은 「도는 배치 없음」과 구분되지 않는다.** `DB_URL`의 DB 이름을 바꾸면 함께 바꾼다.
- **MUST NOT: 실제 값을 적지 않는다.** 플레이스홀더만 둔다. `.env` 자체는 `settings.json`의 `deny`와 `guard-secrets.sh`가 막지만, `.env.example`은 **열람이 허용돼 있어** 아무것도 막지 않는다.
