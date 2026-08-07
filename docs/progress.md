# 진행 상태

세션이 바뀌어도 남아야 하는 "지금 어디까지 왔는가". 세션 시작 시 훅이 이 파일을 주입한다.

- 요구사항은 [`spec`](./stock-screener-project-spec.md), 설계와 **phase 완료 판정**은 [`design/v1-architecture.md`](./design/v1-architecture.md), 외부 API 실측은 [`api-spec/`](./api-spec/), 되돌리기 어려운 결정은 [`adr/`](./adr/)에 있다. **여기에 옮겨 적지 않는다.**
- 무엇을 했는지는 `git log`에 있다. 여기에는 **다음 세션이 모르면 곤란한 것만** 적는다.
- MUST: 상태 주장은 확인 가능한 형태로 쓴다. "Phase 1 완료"가 아니라 "`./gradlew build` 성공 + ADR-0001~0004 존재"처럼 쓴다. 낡은 문서를 에이전트는 그대로 믿는다.
- **주입 상한은 8,000자다**(`inject-progress.sh`). 넘기면 뒷부분이 조용히 잘린다.

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

---

## 다음에 할 일

**Phase 5의 수동 관통이 남았다.** 구현·자동 검증은 끝났지만 design §5 Phase 5의 12개 판정은 대부분 UX 수준이라 **브라우저와 실제 Google 로그인이 있어야 판정된다.** 헤드리스로 확인된 것은 서버 쪽뿐이다(`SpaFallbackTest`·`SecurityConfigTest`가 단언한다).

관통 절차는 [`TODO.md`](../TODO.md) §1의 체크리스트 13개에 있다.

- **개발 서버(:5173) 로그인은 Console에 URI를 하나 더 등록해야 된다**(절차는 `TODO.md` §2). Vite 프록시가 Host를 바꾸지 않아 Spring이 요청 Host로 `redirect_uri`를 만든다(실측). **:8080은 그대로 된다.**

### 배치 실행 방법 (`BootstrapRunner`, `bootstrap` 프로파일 전용)

```bash
# 공통 접두: ./gradlew bootRun --args='--spring.profiles.active=bootstrap <아래>'
--job=universe
--job=initial-load --price-years=7 --financial-years=7 --reload-existing=true
--job=backfill --from=2025-11-11 --as-of=2026-08-05   # 구간 전체를 돈다
```

- **`--reload-existing=true`가 없으면 기간을 늘려도 아무 일도 일어나지 않는다.** 대상 선정이 `NOT EXISTS (price_daily)`라 봉이 하나라도 있으면 건너뛰고, 잡은 **성공으로 끝난다.**
- **적재 중 코드를 재컴파일하지 않는다.** devtools가 실행 중 프로세스에 새 클래스를 끼워넣어 카탈로그·스키마와 어긋난다(실제 2회 발생).
- 검증: `ck_price_ohlc` 위반 0, 편입 종목 중 시세 없는 종목 0, `min(trade_date)`가 요청 구간 시작과 일치, `listed_shares` 빈값 0.

**환경**: JDK 21.0.8, PostgreSQL 16.14, Node 24. Docker 없음.

---

## 외부 소스에서 가장 위험한 것

정본은 [`api-spec/`](./api-spec/)이다. 아래 둘만 남기는 이유는 **api-spec이 자동 로드되지 않고, 둘 다 아무 에러 없이 틀린 데이터를 저장하기 때문**이다. 어댑터의 방어를 지우지 마라.

- **IMPORTANT — 공공데이터포털은 없는 종목·휴장일·파라미터 오타의 응답이 동일하다**(`resultCode "00"` + `totalCount 0` + HTTP 200). 날짜 형식 오타가 "그날 데이터 없음"으로 저장된다. **요청 전 파라미터 검증이 유일한 방어**다.
- **IMPORTANT — `totalCount`를 보지 않으면 조용히 잘린다.** `totalCount=1618`인데 `numOfRows=1000`이면 618일이 **경고 없이** 사라진다.

---

## ADR 대상은 아니지만 알아야 하는 결정

되돌리기는 쉽지만, 모르면 다시 만들거나 어긋나게 되는 것들.

- **phase별 브랜치를 만들지 않는다.** `main`에 직접 쌓는다(2026-08-06). Phase 2 브랜치가 `main`을 4개월 묶어둔 전례가 근거다. 브랜치는 **버릴 수 있는 탐색**에만 쓴다.
- **`git add -A`를 쓰지 않는다.** `1873cf3`이 macOS 복제본 42개를 담아 컴파일이 멈췄다.
- **IMPORTANT — iCloud가 `build/` 산출물도 복제한다.** 증상은 테스트의 `Could not execute test class 'X 2' (wrong name: X)`와 **수 분간의 멈춤**(Gradle이 파일 해싱에서 대기한다). 처방은 `./gradlew clean`이고, `checkDuplicateFiles`가 `src`(삭제)와 `build`(clean)를 나눠 먼저 잡는다. **원격 편집 파일이 즉시 안 보이는 것도 같은 원인이다 — 없다고 단정하기 전에 몇 분 뒤 다시 본다.**
- **테스트 커넥션 풀은 2로 제한한다.** 기본값 10이면 무관한 테스트가 "too many clients"로 깨진다(실제 14건).
- **테스트 프로파일에 `out-of-order: true`가 필요하다.** 테스트 전용 V900이 운영 마이그레이션보다 위에 있다.
- **마이그레이션 체크섬이 어긋나면** `clean-on-validation-error`가 항상 구해주지 않는다. 해당 `flyway_schema_history` 행과 객체를 지우고 재적용한다.
- **`spring.batch.job.enabled=false`, `pipeline.scheduler.enabled` 기본 꺼짐을 유지한다.**
- **`FinancialFigures`의 `eps`·`bps`를 지표 계산에 쓰면 안 된다.** 지표는 언제나 자체 계산이다(spec §7). 두 필드는 대조용이다.
- **재무 지표의 유량/저량 구분이 핵심이다.** 매출·이익·현금흐름은 4분기 합(TTM), 자산·부채·자본은 최근 분기 잔액이다. 저량을 합산하면 4배가 되는데 값이 그럴듯해 드러나지 않는다.
- **백분위는 항상 오름차순이다.** `PER_PCTL`이 0에 가까우면 PER이 낮다는 뜻이고, "상위 20%"는 지표에 따라 `<= 0.2`이거나 `>= 0.8`이다. 방향의 정본은 원본의 `higher_is_better`이고 카탈로그 API가 `favorableEnd`로 내려준다.
- **`IndicatorDataReader`가 분기 재무에 한 해치 여유를 얹어 읽는다.** PER의 TTM은 여유가 0이라 공시가 빠지면 경계에서 값이 조용히 사라진다.
- **테스트는 로컬 PostgreSQL을 쓴다**(H2는 EAV가 의존하는 PG 기능을 못 쓴다). **Zonky는 재검토 불필요.** 격리는 `@Transactional` 롤백이 기본이고, 커밋을 요구하는 테스트만 `@AfterEach` 정리를 쓴다.
- **`.env`는 `build.gradle`이 읽어 `bootRun`·`liveTest`에만 주입한다.** `test`에는 넣지 않는다 — 실키가 있으면 `@ConditionalOnApiKey` 어댑터가 등록돼 빈 구성이 바뀐다. 주입 실패는 조용하므로 `./gradlew checkEnv`로 가른다.
- **Jackson은 3.x이고 패키지가 `tools.jackson.databind`다.** Boot 3 예제를 복사하면 컴파일이 안 된다.
- **CSRF 토큰의 지연 로딩을 껐다**(`setCsrfRequestAttributeName(null)`). 켜두면 SPA의 첫 POST가 403이 되고 인증 버그처럼 보인다. MockMvc도 `with(csrf())`가 없으면 403이다.
- **테스트 DB와 운영 DB의 활성 유니버스가 정반대다.** 운영은 `KOSPI_TOP200`·`KOSDAQ_TOP150`이 활성이고 시드는 0건, 테스트 DB는 시드가 활성·전부 0건이다. 유니버스 테스트는 픽스처를 직접 심는다.
- **`ADJ_CLOSE`는 봉 하나로 값이 나오는 첫 지표다.** `DELETE FROM stock` 앞에 `indicator_value` 정리가 없으면 FK로 정리가 중단되고 **무관해 보이는 다른 테스트**가 깨진다.
- **백필 한 번은 워밍업이 긴 지표를 다 채우지 못한다.** 러너가 `maxEffectiveLookback` 봉만 읽어 SMA_200은 실행당 ~25거래일치만 나온다. 구간 전체를 돌아야 한다(180일=1시간 51분).

### 프론트엔드 (Phase 5에서 생긴 것)

- **`./gradlew build`가 `frontend/`를 함께 빌드한다.** `processResources`가 `frontend/dist`를 `static/`으로 싣고, `check`가 Vitest를 돈다. Node가 없으면 `-PskipFrontend`(번들이 빠져 `SpaFallbackTest`가 실패한다). `npm` 경로는 빌드가 직접 해석한다 — Homebrew 경로가 `/etc/paths`에 없어 IDE 데몬에서 조용히 깨지기 때문이다.
- **보안 매처의 마지막이 `permitAll`이다**(ADR-0018). 정적 셸을 익명에게 열어야 로그인 화면이 뜬다. **새 보호 대상은 반드시 그 앞에 명시해야 한다.**
- **SPA 폴백은 `api/`·`actuator/`·`/assets/**`에 적용하지 않는다.** 적용하면 없는 경로가 404 대신 HTML 200을 주고 프론트가 JSON 파싱에서 죽는다.
- **`primevue`는 4.x에 고정한다**(ADR-0019). 5.0.0은 MIT가 아니라 라이선스 키를 요구하고, 없거나 만료되면 화면에 고지가 뜬다.
- **코드 규칙은 `.claude/rules/frontend.md`에 있다**(`**/*.vue`·`**/*.ts` glob으로 자동 로드). 지표 코드 분기 금지, `?? 0` 금지, 모르는 값에 던질지 보일지, 문자열 배치 기준이 거기 있다. 여기 옮겨 적지 않는다.
- **백엔드 enum·상수의 거울은 `wire-contract.json`이 지킨다**(ADR-0020). 값을 하나 더하면 픽스처에도 적어야 양쪽 테스트가 통과한다.

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
