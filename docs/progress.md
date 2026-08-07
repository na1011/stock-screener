# 진행 상태

세션이 바뀌어도 남아야 하는 "지금 어디까지 왔는가". 세션 시작 시 훅이 이 파일을 주입한다.

- 요구사항은 [`spec`](./stock-screener-project-spec.md), 설계와 **phase 완료 판정**은 [`design/v1-architecture.md`](./design/v1-architecture.md), 외부 API 실측은 [`api-spec/`](./api-spec/), 되돌리기 어려운 결정은 [`adr/`](./adr/)에 있다. **여기에 옮겨 적지 않는다.**
- 무엇을 했는지는 `git log`에 있다. 여기에는 **다음 세션이 모르면 곤란한 것만** 적는다.
- MUST: 상태 주장은 확인 가능한 형태로 쓴다. "Phase 1 완료"가 아니라 "`./gradlew build` 성공 + ADR-0001~0004 존재"처럼 쓴다. 낡은 문서를 에이전트는 그대로 믿는다.
- **주입 상한은 8,000자다**(`inject-progress.sh`). 넘기면 뒷부분이 조용히 잘린다. **파일별 함정은 `.claude/rules/*.md`로 옮긴다** — glob으로 그 파일을 열 때만 로드되므로 여기 예산을 쓰지 않는다. 여기 남길 것은 **어떤 파일을 열든 알아야 하는 것**뿐이다.

---

## Phase 상태

| Phase | 상태 | 완료 판정 |
|---|---|---|
| 1. 기반 | **완료** | design §5 기준 통과 + ADR-0001~0004 |
| 2. 수집 파이프라인 | **완료 (실적재 검증됨)** | design §5 기준 통과 + ADR-0005~0010 |
| 3. 지표 계산 | **완료 (실데이터 검증됨)** | design §5 기준 통과 + ADR-0011 |
| 4. 스크리닝 엔진 | **완료 (실데이터 검증됨)** | design §5 기준 통과 + ADR-0014~0017 |
| 5. 프론트엔드 | **구현 완료 · 구조 보강 완료 · 수동 관통 미검증** | 아래 참조 + ADR-0018~0020 |
| 6. 워치리스트·관리자·운영 | 미착수 | 착수 전 design §5에 판정을 먼저 적는다 |

**현재 코드 상태**: `./gradlew build` 성공 — 백엔드 **348개** + 프론트 Vitest **144개** 통과(외부 호출 0건), 관통테스트 9개(`./gradlew liveTest`). 있는 것 — 스키마 V1~V10, 지표 17개 + 백분위 6개, 조건 모델·번역기, 인가, 수집 파이프라인·배치 잡 4종·`BootstrapRunner`, 지표 계산 엔진, HTTP API 전체, 프리셋 3종, **Vue3 프론트엔드 전체**(로그인·조건 빌더·결과 표/카드·화면 저장·종목 상세·지지저항·종목 검색). 없는 것 — 미국 어댑터(v1.1, ADR-0010), 관리자 API·워치리스트(Phase 6).

**실데이터가 들어 있다** (2026-08-06). 유니버스 350종목, `indicator_value` **2.81M행**, 최신일 **2026-08-05**. 보존 구간 180거래일이 23종에서 180/180이라 과거 일자 스크리닝도 동작한다. 실측 `MOMENTUM_KR` 27종목.

- **PER이 267종목만 나오는 것은 정상이다** — 적자 기업에 값을 내지 않는 규칙의 결과다.
- **극단값은 먼저 분기 내역을 본다.** PER 44,787(원익QnC)은 4분기 합이 0.2억이라 실제 결산이 그렇다.

**환경**: JDK 21.0.8, PostgreSQL 16.14, Node 24. Docker 없음.

---

## 다음에 할 일

**Phase 5의 수동 관통이 남았다.** 구현·자동 검증은 끝났지만 design §5 Phase 5의 12개 판정은 대부분 UX 수준이라 **브라우저와 실제 Google 로그인이 있어야 판정된다.** 헤드리스로 확인된 것은 서버 쪽뿐이다(`SpaFallbackTest`·`SecurityConfigTest`가 단언한다). 관통 절차는 [`TODO.md`](../TODO.md) §1의 체크리스트 13개에 있다.

- **개발 서버(:5173) 로그인은 Console에 URI를 하나 더 등록해야 된다**(절차는 `TODO.md` §2). Vite 프록시가 Host를 바꾸지 않아 Spring이 요청 Host로 `redirect_uri`를 만든다(실측). **:8080은 그대로 된다.**
- 배치(`BootstrapRunner`) 실행은 `batch-load` skill이 스스로 라우팅한다. 여기서 가리킬 필요가 없다.

---

## 어떤 파일을 열든 알아야 하는 것

파일별 함정은 아래 규칙 파일이 glob으로 자동 로드한다. **여기 옮겨 적지 않는다.**

| 파일 | glob | 무엇이 있나 |
|---|---|---|
| `.claude/rules/backend.md` | `src/main/java/**` | 조건 모델 설계, Jackson 3.x, CSRF, 보안 매처 순서, SPA 폴백 |
| `.claude/rules/data-pipeline.md` | `**/batch/**`·`**/indicator/**` 등 | 외부 소스 함정, 유량/저량, 백분위 방향, 배치 실행 |
| `.claude/rules/test.md` | `src/test/**` | 로컬 PG, 커넥션 풀 2, 마이그레이션, 픽스처 |
| `.claude/rules/frontend.md` | `**/*.vue`·`**/*.ts` 등 | 지표 코드 분기 금지, 문구 배치, 빌드 연동, primevue 고정 |

- **phase별 브랜치를 만들지 않는다.** `main`에 직접 쌓는다(2026-08-06). Phase 2 브랜치가 `main`을 4개월 묶어둔 전례가 근거다. 브랜치는 **버릴 수 있는 탐색**에만 쓴다.
- **`git add -A`를 쓰지 않는다.** `1873cf3`이 macOS 복제본 42개를 담아 컴파일이 멈췄다.
- **IMPORTANT — iCloud가 `build/` 산출물도 복제한다.** 증상은 테스트의 `Could not execute test class 'X 2' (wrong name: X)`와 **수 분간의 멈춤**(Gradle이 파일 해싱에서 대기한다). 처방은 `./gradlew clean`이고, `checkDuplicateFiles`가 `src`(삭제)와 `build`(clean)를 나눠 먼저 잡는다. **원격 편집 파일이 즉시 안 보이는 것도 같은 원인이다 — 없다고 단정하기 전에 몇 분 뒤 다시 본다.**
- **`.env`는 `build.gradle`이 읽어 `bootRun`·`liveTest`에만 주입한다.** `test`에는 넣지 않는다 — 실키가 있으면 `@ConditionalOnApiKey` 어댑터가 등록돼 빈 구성이 바뀐다. 주입 실패는 조용하므로 `./gradlew checkEnv`로 가른다.

---

## 미룬 것 / 알려진 미완성

- **`clean-on-validation-error: true`가 `application-test.yaml`에 남아 있다**(제거 대상).
- **외부 서비스 발급 8건 중 4건 완료.** 정본은 [`external-credentials.md`](./external-credentials.md). 도메인은 **`na1011.net`, 앱 주소는 `screener.na1011.net`으로 확정**됐고 구매만 남았다(2026-08-08).
- **재무 수집이 일일 배치에 없다.** 분기 공시라 매일 조회하면 쿼터만 태운다. 공시 시즌 갱신은 수동 실행 전제다.
- **워치리스트가 통째로 Phase 6이다.** `AlertPort`의 `recipientUserId`와 `ComparisonOperator.test()`로 이음매는 준비되어 있다.
- **배치 잡의 HTTP 진입점이 없다 — Phase 6.** `SecurityConfigTest`가 `/api/admin/batches`의 404를 단언하므로 그때 함께 고친다.
- **actuator 미도입**: Phase 6. 매처는 이미 `ROLE_ADMIN`으로 잠가 두었다.
- **IMPORTANT — Phase 6의 에이전트 코드 작업 둘.** 사람이 하는 발급 절차에 섞여 있어 놓치기 쉽다.
  1. **도메인을 붙이면 `server.forward-headers-strategy: framework`가 필요하다.** `server:` 블록이 아예 없고 cloudflared는 평문 HTTP로 붙는다. 없으면 `redirect_uri`가 `http://`로 나가 로그인이 깨지는데 **호스트는 맞아서 원인이 안 보인다.**
  2. **프론트 Sentry용 `VITE_SENTRY_DSN`을 신설해야 한다.** Vite는 `VITE_` 접두어만 번들에 노출한다. 지금 프론트 오류는 `console.error`에만 남는다.
- **`formatPrice`가 `'원'`을 고정한다.** `market`에 `currency`(KRW/USD)가 있지만 어떤 DTO도 싣지 않는다. ADR-0010의 미국 시장은 응답 계약부터 고쳐야 열린다.
- **프리셋에 `editable: true`를 줘도 저장까지 되지는 않는다.** 빌더 잠금만 풀리고 `SaveScreenDialog`는 `origin.kind === 'screen'`만 갱신한다.

---

## 되돌리지 말 것

**공통점: 지워도 컴파일되고 테스트도 통과하는데 나중에 조용히 망가진다.** 근거는 각 ADR에 있고 `applies_to`로 자동 로드된다. 아래 셋만 남기는 이유는 **그 파일을 건드리지 않아도 무너질 수 있어** 조건부 로드가 걸리지 않기 때문이다.

- **ADR-0005 — `JobRepository` 빈 직접 정의를 지우지 않는다.** 지우면 인메모리로 돌아가 아무것도 기록되지 않고 재시작이 불가능해지는데 **아무 증상이 없다.**
- **ADR-0007 — `rollbackFor = Exception.class`와 `StockTaskRunner` 분리를 유지한다.** Spring 기본은 checked 예외에 커밋하고, 자기 호출은 프록시를 안 거쳐 `REQUIRES_NEW`가 무시된다.
- **ADR-0012 — 시드 유니버스(`KOSPI200` 등)를 비활성으로 내리지 않는다.** 시총 어댑터가 `@ConditionalOnApiKey`라 키 없는 환경에서 활성 유니버스가 하나도 남지 않는다(실제로 테스트 7건이 깨졌다).
