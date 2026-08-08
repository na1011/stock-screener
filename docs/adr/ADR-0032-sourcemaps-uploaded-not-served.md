---
id: ADR-0032
status: 채택됨
date: 2026-08-09
applies_to:
  - "frontend/vite.config.ts"
  - "frontend/src/lib/monitoring.ts"
---

# ADR-0032. 소스맵은 Sentry에만 올리고 배포물에는 싣지 않는다

## 결정

프론트 번들의 소스맵을 `hidden`으로 만들어 Sentry에만 업로드하고, **배포 jar에는 넣지 않는다.** 업로드는 org·project·토큰 셋이 다 있을 때만 켜지며, 켠 뒤 실패하면 빌드를 죽인다.

## 규칙

- **MUST: `build.sourcemap`을 `'hidden'`으로 둔다.** `true`로 두면 번들 끝에 `//# sourceMappingURL` 주석이 남고, 브라우저가 그것을 따라 `/assets/index-a3f9.js.map`을 요청한다. 서빙되면 프론트 소스가 통째로 공개된다. `false`로 두면 올릴 것이 없어 스택트레이스가 `index-a3f9.js:1:28471`로만 남는다.
- **MUST: `processResources`의 `exclude '**/*.map'`을 유지한다.** 플러그인의 `filesToDeleteAfterUpload`는 **업로드가 켜져 있을 때만** 돈다. 지금처럼 슬러그가 없어 업로드가 꺼져 있으면 맵이 `dist`에 그대로 남는다(실측: 21개). 즉 그 삭제만으로는 **지금 상태에서 아무것도 막지 못한다.** 마지막 방어선은 이 `exclude`다.
- **MUST: 업로드 플러그인을 `command === 'build'`에서만 켠다.** 이 config는 개발 서버와 **Vitest**도 읽는다. 조건 없이 넣으면 단위 테스트를 돌릴 때마다 Sentry에 릴리스가 만들어진다.
- **MUST: `errorHandler`로 업로드 실패를 다시 던진다.** 기본 동작은 에러만 찍고 **빌드를 통과시킨다**(실측). 그러면 슬러그 오타나 회수된 토큰이 초록 빌드 뒤에 숨고, 그 사실은 진짜 오류가 나서 이슈를 열었을 때에야 드러난다.
- **MUST: 값이 없어 건너뛸 때 경고를 남긴다.** 조용히 건너뛰는 것이 이 배선의 유일한 실패 방식이다.
- **MUST NOT: `Sentry.init`에 `release`를 손으로 적지 않는다.** 플러그인이 번들에 주입한 값을 SDK가 읽고 소스맵도 같은 값으로 올라간다. 손으로 적으면 둘이 어긋나 **매핑만 조용히 안 된다.**
- **MUST NOT: `SENTRY_AUTH_TOKEN`에 `VITE_` 접두어를 붙이지 않는다.** 붙이는 순간 번들에 실려 공개된다. DSN은 반대다 — 쓰기 전용 공개 식별자라 번들에 박히는 것이 정상이다.

## 검증

```bash
./gradlew clean build

# ① 배포물에 소스맵이 없다 (0)
unzip -l build/libs/*.jar | grep -c '\.map'

# ② 그런데 dist에는 남아 있다 — ①이 우연이 아니라 exclude 덕분임을 보인다 (지금은 21)
ls frontend/dist/assets/*.map | wc -l

# ③ 번들에 sourceMappingURL 주석이 없다 (hidden)
grep -c sourceMappingURL frontend/dist/assets/*.js   # 전부 0

# ④ DSN은 번들에 실렸다. 호스트가 ingest.us.sentry.io일 수 있으므로 좁게 grep하지 않는다
unzip -p build/libs/*.jar 'BOOT-INF/classes/static/assets/*.js' | grep -c 'sentry\.io'

# ⑤ 시크릿은 실리지 않았다 (0)
unzip -p build/libs/*.jar 'BOOT-INF/classes/static/assets/*.js' | grep -c 'sntrys_'

# ⑥ 배선 판정
cd frontend && npm run test:unit -- monitoring
```

**①②③④⑤ 모두 2026-08-09 실측으로 확인했다.** ④에서 처음에 `ingest.sentry.io`로 grep해 0이 나왔는데, 실제 호스트는 `o…​.ingest.us.sentry.io`였다 — 확인 명령이 좁으면 멀쩡한 배선을 고장으로 읽는다.

## 배경

Phase 6의 판정 하나가 "프론트 스택트레이스가 읽힌다"이다. 그러려면 소스맵이 Sentry에 있어야 하는데, **같은 파일이 배포물에 실리면 소스가 공개된다.** 이 둘은 같은 산출물의 두 목적지 문제이지 만들지 말지의 문제가 아니다.

두 겹으로 막는 이유는 **각각 혼자서는 지금 상태에서 안 통하기 때문**이다. 플러그인의 삭제는 업로드가 켜져야 돌고, 지금은 org·project 슬러그가 없어 꺼져 있다. `exclude`만 있으면 업로드가 켜졌을 때 `dist`에 맵이 계속 쌓인다. 둘은 같은 규칙의 중복이 아니라 **서로 다른 구간을 막는다.**

## 버린 대안

- **`sourcemap: false`** — 소스가 안 새지만 스택트레이스도 못 읽는다. Phase 6 판정을 포기하는 것이다.
- **`sourcemap: true` + 서버에서 `.map` 경로 차단** — `WebMvcConfig`에 규칙이 하나 더 늘고, 그 규칙이 `/assets/**` 핸들러보다 먼저 걸리는지에 결과가 달린다. 파일을 아예 안 싣는 편이 실패할 여지가 없다.
- **슬러그를 `frontend/.env.production`에 적는다** — 그 파일명은 `guard-secrets.sh`가 막고, 실제로 시크릿(토큰)이 같은 파일에 모이기 쉽다. Vite가 프로세스 환경변수의 `VITE_` 접두 키를 `.env` 파일보다 우선해 싣는 것이 확인됐으므로(`loadEnv` 실측), 주입 지점은 Gradle 태스크 환경으로 충분하다.
- **업로드 실패를 경고로 두고 빌드는 통과시킨다**(플러그인 기본값) — 값을 채우는 것은 명시적 선택이므로, 채운 뒤 실패했다면 그것은 알아야 할 사건이다. 경고는 빌드 로그에 묻힌다.

## 결과

- **쉬워지는 것**: 슬러그 두 개만 `.env`에 채우면 코드 변경 없이 스택트레이스가 읽힌다. 소스가 새지 않는 것이 업로드 설정과 무관하게 성립한다.
- **어려워지는 것**: 슬러그가 채워진 뒤에는 Sentry가 죽어 있으면 `./gradlew build`가 실패한다. 끄려면 환경변수 하나를 비우면 된다.
- **되돌리려면**: `vite.config.ts`의 `sourcemap`·플러그인과 `build.gradle`의 `exclude`를 함께 건드려야 한다. 한쪽만 되돌리면 소스가 샌다.
