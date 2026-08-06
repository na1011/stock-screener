# 진행 상태

세션이 바뀌어도 남아야 하는 "지금 어디까지 왔는가". 세션 시작 시 훅이 이 파일을 주입한다.

- 요구사항은 [`spec`](./stock-screener-project-spec.md), 설계와 **phase 완료 판정**은 [`design/v1-architecture.md`](./design/v1-architecture.md), 외부 API 실측은 [`api-spec/`](./api-spec/), 되돌리기 어려운 결정은 [`adr/`](./adr/)에 있다. **여기에 옮겨 적지 않는다.**
- 무엇을 했는지는 `git log`에 있다. 여기에는 **다음 세션이 모르면 곤란한 것만** 적는다.
- MUST: 상태 주장은 확인 가능한 형태로 쓴다. "Phase 1 완료"가 아니라 "`./gradlew build` 성공 + ADR-0001~0004 존재"처럼 쓴다. 낡은 문서를 에이전트는 그대로 믿는다.
- **주입 상한은 8,000자다**(`inject-progress.sh`의 `limit`). 넘기면 뒷부분이 조용히 잘린다 — `check-doc-staleness.sh`가 세션 시작에 경고한다.

---

## Phase 상태

| Phase | 상태 | 완료 판정 |
|---|---|---|
| 1. 기반 | **완료** | design §5 Phase 1 기준 전부 통과 + ADR-0001~0004 |
| 2. 수집 파이프라인 | **완료 (실적재 검증됨)** | design §5 Phase 2 기준 전부 통과 + ADR-0005~0010 |
| 3. 지표 계산 | **완료 (실데이터 검증됨)** | design §5 Phase 3 기준 전부 통과 + ADR-0011 |
| 4. 스크리닝 엔진 | **완료 (실데이터 검증됨)** | design §5 Phase 4 기준 전부 통과 + ADR-0014~0017 |
| 5. 프론트엔드 | 미착수 | — |
| 6. 워치리스트·관리자·운영 | 미착수 | — |

**현재 코드 상태**: 테스트 **316개** 통과(`./gradlew build`, 외부 호출 0건) + 관통테스트 9개(`./gradlew liveTest`). 있는 것 — 스키마 V1~V10, 지표 17개 + 백분위 6개, 조건 모델·번역기, 인가, 수집 파이프라인·배치 잡 4종·`BootstrapRunner`, 지표 계산 엔진, **HTTP API 전체**, 프리셋 3종. 없는 것 — 미국 어댑터(v1.1, ADR-0010), 프론트엔드(Phase 5), 관리자 API(Phase 6).

**실데이터가 들어 있다** (2026-08-06). 유니버스 350종목(KOSPI 200 + KOSDAQ 150), 시세 실패 0·OHLC 위반 0·상장주식수 100%. `indicator_value` **2.81M행**, 최신일은 **2026-08-05**(~348종목). 삼성전자 PER를 손계산과 대조해 확인. **보존 구간 180거래일에 23종이 전부 180/180**이라 과거 일자 스크리닝도 동작한다(2026-08-07 백필). 실측 `MOMENTUM_KR` 27종목, `VALUE_KR` 만점 10점에 기아·SK·KT.

- **PER이 267종목만 나오는 것은 정상이다** — 적자 기업에 값을 내지 않는 규칙이 동작한 결과다.
- **극단값은 먼저 분기 내역을 본다.** PER 44,787(원익QnC)은 4분기 합이 0.2억이라 EPS가 0.71이었다 — 계산이 아니라 실제 결산이 그렇다.

**미국은 v1.1로 연기가 spec에 반영됐다**(ADR-0010). v1 수용 기준은 국내뿐이다.

---

## 다음에 할 일

**Phase 5(프론트엔드).** 백엔드 API가 전부 있다. 착수 전 design §5에 Phase 5 완료 판정을 먼저 적는다.

- **브라우저 확인에는 Google OAuth 자격증명이 필요하다** — `/api/**`가 전부 인증을 요구하고 로그인 경로가 Google뿐이다. 허용목록에는 `zerus3934@gmail.com`(admin)이 등록돼 있다. 발급 전에는 MockMvc가 유일한 HTTP 검증 수단이다.
- 응답 계약은 `ScreeningResponse`·`IndicatorResponse`·`PriceReference`의 javadoc에 있다.

### 배치 실행 방법 (`BootstrapRunner`, `bootstrap` 프로파일 전용)

```bash
./gradlew bootRun --args='--spring.profiles.active=bootstrap --job=universe'
./gradlew bootRun --args='--spring.profiles.active=bootstrap --job=initial-load \
    --price-years=7 --financial-years=7 --reload-existing=true'
# 백필은 구간 전체를 돈다 — 아래 "백필 한 번은…" 참조.
./gradlew bootRun --args='--spring.profiles.active=bootstrap --job=backfill \
    --from=2025-11-11 --as-of=2026-08-05'
```

- **`--reload-existing=true`가 없으면 기간을 늘려도 아무 일도 일어나지 않는다.** 대상 선정이 `NOT EXISTS (price_daily)`라 봉이 하나라도 있으면 건너뛰고, 잡은 **성공으로 끝난다.**
- **적재 중 코드를 재컴파일하지 않는다.** devtools가 실행 중 프로세스에 새 클래스를 끼워넣어 카탈로그·스키마와 어긋난다. 실제로 두 번 발생했다(V9 때 3종목, `ADJ_CLOSE` 때 84,716건 FK 위반).
- 검증: `ck_price_ohlc` 위반 0, 편입 종목 중 시세 없는 종목 0, `min(trade_date)`가 요청 구간 시작과 일치(**잘리지 않았다는 증거**), `listed_shares` 빈값 0.

**환경**: JDK 21.0.8, PostgreSQL 16.14, DB 2개 생성됨. Docker 없음.

---

## 외부 소스에서 가장 위험한 것

정본은 [`api-spec/`](./api-spec/)이다. 아래 둘만 남기는 이유는 **api-spec이 자동 로드되지 않고, 둘 다 아무 에러 없이 틀린 데이터를 저장하기 때문**이다. 어댑터의 방어를 지우지 마라.

- **IMPORTANT — 공공데이터포털은 없는 종목·휴장일·파라미터 오타의 응답이 동일하다**(`resultCode "00"` + `totalCount 0` + HTTP 200). 날짜 형식 오타가 "그날 데이터 없음"으로 저장된다. **요청 전 파라미터 검증이 유일한 방어**다.
- **IMPORTANT — `totalCount`를 보지 않으면 조용히 잘린다.** `totalCount=1618`인데 `numOfRows=1000`이면 618일이 **경고 없이** 사라진다. 페이징으로 방어한다.

두 소스의 실패 표현이 정반대인 것(ADR-0009), 시세가 2020-01-02부터인 것, 발행주식수를 시세에서 얻는 것(ADR-0013)은 api-spec과 해당 ADR에 있다.

---

## ADR 대상은 아니지만 알아야 하는 결정

되돌리기는 쉽지만, 모르면 다시 만들거나 어긋나게 되는 것들.

- **phase별 브랜치를 만들지 않는다.** `main`에 직접 쌓는다(2026-08-06 결정). Phase 2 브랜치가 `main`을 phase 1에 4개월 묶어둔 전례가 근거다 — 병렬 작업이 없어 격리할 대상이 없었고, 되돌리기 단위는 이미 커밋이 담당한다. 브랜치는 **버릴 수 있는 탐색**에만 쓴다.
- **커밋 전 `git add -A`를 쓰지 않는다.** `1873cf3`이 macOS 복제본(`AlertEvent 2.java`) 42개를 함께 담아 컴파일이 멈췄다. `./gradlew checkDuplicateFiles`가 이제 한 줄로 잡는다.
- **테스트 커넥션 풀은 2로 제한한다.** 컨텍스트마다 풀이 생기고 TestContext는 캐시하며 닫지 않아, 기본값 10이면 **무관한 테스트가** "too many clients"로 실패한다(실제 14건).
- **테스트 프로파일에 `out-of-order: true`가 필요하다.** 테스트 전용 V900이 항상 운영 마이그레이션보다 위에 있어 새 운영 버전이 뒤늦게 들어온다. 운영에는 적용하지 않는다.
- **마이그레이션 체크섬이 어긋나면** `clean-on-validation-error`가 항상 구해주지 않는다. 테스트 DB에서 해당 `flyway_schema_history` 행과 객체를 지우고 재적용한다.
- **`spring.batch.job.enabled=false`, `pipeline.scheduler.enabled` 기본 꺼짐을 유지한다.** 전자를 켜면 재시작마다 초기 적재가 시작되고, 후자를 어댑터 없이 켜면 매일 실패 알림만 쌓인다.
- **`FinancialFigures`의 `eps`·`bps`를 지표 계산에 쓰면 안 된다.** 소스가 준 값이고 지표는 언제나 자체 계산이다(spec §7). 두 필드는 대조용이다.
- **재무 지표의 유량/저량 구분이 핵심이다.** 매출·이익·현금흐름은 4분기 합(TTM), 자산·부채·자본은 최근 분기 잔액이다. 저량을 합산하면 4배가 되는데 값이 그럴듯해 드러나지 않는다. `FinancialMath.sumTrailingFourQuarters`는 유량 전용이다.
- **백분위는 항상 오름차순이다.** `PER_PCTL`이 0에 가까우면 PER이 낮다는 뜻이고, "상위 20%"는 지표에 따라 `<= 0.2`이거나 `>= 0.8`이다. 방향 판단은 원본의 `higher_is_better`가 들고 있고 카탈로그 API가 `favorableEnd`로 내려준다.
- **`IndicatorDataReader`가 분기 재무에 한 해치 여유를 얹어 읽는다.** PER의 TTM은 정확히 4분기라 여유가 0인데 공시가 빠지면 경계에서 값이 조용히 사라진다. `financialYearsNeeded`는 "의미상 필요한 기간"이고 누락 대비는 읽는 쪽 책임이다.
- **테스트는 로컬 PostgreSQL을 쓴다**(H2는 EAV가 의존하는 PG 고유 기능을 못 쓴다). **Zonky는 재검토 불필요.** 격리는 `@Transactional` 롤백이 기본이고, 커밋을 요구하는 실패격리·배치 테스트만 `@AfterEach` 정리를 쓴다. `JobRestartTest`는 `@BeforeEach`에서도 정리한다 — 남은 잡 인스턴스가 재시작성 결함처럼 보인다.
- **패키지 구조**: 컨트롤러는 도메인별 `…/web`에 두고, 최상위 `web`은 HTTP 인프라 전용이라 컨트롤러를 두지 않는다. `pipeline` 하위 이름(`.datasource`/`.collector`/`.batch`/`.scheduler`)은 `.claude/rules/data-pipeline.md`의 path glob에 **의도적으로 맞춘 것**이다.
- **`.env`는 `build.gradle`이 읽어 `bootRun`·`liveTest`에만 주입한다**(Boot도 이 빌드도 dotenv를 쓰지 않는다). `test`에는 넣지 않는다 — 실키가 있으면 `@ConditionalOnApiKey` 어댑터가 등록돼 빈 구성이 바뀐다. 주입 실패는 자리표시자 기본값 탓에 조용하므로 `./gradlew checkEnv`로 가른다(값은 출력하지 않는다).
- **Jackson은 3.x이고 패키지가 `tools.jackson.databind`다.** `com.fasterxml.jackson.databind`는 없다(애너테이션만 2.x). Boot 3 예제를 복사하면 컴파일이 안 된다.
- **CSRF 토큰의 지연 로딩을 껐다**(`setCsrfRequestAttributeName(null)`). 우리 API는 대부분 토큰을 읽지 않는 조회라 기본 동작으로는 쿠키가 안 내려가고, SPA의 첫 POST가 403으로 실패한다 — 인증 버그처럼 보인다. MockMvc도 `with(csrf())`가 없으면 403이다.
- **테스트 DB와 운영 DB의 활성 유니버스가 정반대다.** 운영은 `KOSPI_TOP200`(200)·`KOSDAQ_TOP150`(150)이 활성이고 시드는 0건, 테스트 DB는 시드가 활성·전부 0건이다. 유니버스 테스트는 픽스처를 직접 심는다.
- **`ADJ_CLOSE`는 봉 하나로 값이 나오는 첫 지표다.** 그래서 짧은 픽스처를 쓰는 배치 테스트에서도 `indicator_value`에 행이 생긴다. `DELETE FROM stock` 앞에 `indicator_value` 정리가 없으면 FK로 정리가 중단되고, 남은 종목이 **무관해 보이는 다른 테스트**를 깨뜨린다(실제로 `PipelineEndToEndTest`가 그랬다).
- **백필 한 번은 워밍업이 긴 지표를 다 채우지 못한다.** 러너가 `maxEffectiveLookback` 봉만 읽어 SMA_200은 실행당 ~25거래일치만 나온다. 최신 하루만 돌리면 최신일은 맞지만 **과거 일자 조회가 빈 결과**가 된다(API가 보존 구간 내 `asOf`를 허용한다). 구간 전체를 돌아야 한다 — 180일에 1시간 51분.

---

## 미룬 것 / 알려진 미완성

- **`clean-on-validation-error: true`가 `application-test.yaml`에 남아 있다**(제거 대상). 테스트 DB만 비우지만 프로파일을 잘못 지정하면 되돌릴 수 없다.
- **외부 서비스 발급 8건 중 3건(데이터 소스)만 완료.** 정본은 [`external-credentials.md`](./external-credentials.md). 남은 Google OAuth·도메인·Cloudflare·Sentry·알림 채널은 전부 Phase 5~6에 필요하다.
- **재무 수집이 일일 배치에 없다.** 분기 공시라 매일 조회하면 쿼터만 태운다. 공시 시즌 갱신은 수동 실행 전제이고, 자동화하려면 공시 일정부터 수집 대상이 된다.
- **워치리스트 조건 판정이 일일 배치에 없다**(기능 자체가 Phase 6). `AlertPort`의 `recipientUserId`와 `ComparisonOperator.test()`로 이음매는 준비되어 있다.
- **배치 잡의 HTTP 진입점이 없다 — Phase 6으로 옮겼다**(2026-08-06). `run()`이 장시간 동기 작업이라 `@Async`+폴링이 통째로 따라오고, 관리자 화면과 함께 만드는 편이 낫다. `BootstrapRunner`에 `daily-update`는 없다. `SecurityConfigTest`가 `/api/admin/batches`의 404를 단언하므로 그때 함께 고친다.
- **actuator 미도입**: Phase 6. `/actuator/health`가 열려 있지만 404다.

---

## 되돌리지 말 것

**공통점: 지워도 컴파일되고 테스트도 통과하는데 나중에 조용히 망가진다.** 근거는 각 ADR에 있고 `applies_to`로 자동 로드된다. 아래 셋만 남기는 이유는 **그 파일을 건드리지 않아도 무너질 수 있어** 조건부 로드가 걸리지 않기 때문이다.

- **ADR-0005 — `JobRepository` 빈 직접 정의를 지우지 않는다.** 지우면 인메모리로 돌아가 아무것도 기록되지 않고 재시작이 불가능해지는데 **아무 증상이 없다.** `BATCH_*`에 `@Entity`를 만들지 않는다.
- **ADR-0007 — `rollbackFor = Exception.class`와 `StockTaskRunner` 분리를 유지한다.** Spring 기본은 checked 예외에 커밋하고, 자기 호출은 프록시를 안 거쳐 `REQUIRES_NEW`가 무시된다. 둘 다 실패 격리를 소리 없이 무력화한다.
- **ADR-0012 — 시드 유니버스(`KOSPI200` 등)를 비활성으로 내리지 않는다.** 시총 어댑터가 `@ConditionalOnApiKey`라 키 없는 환경에서 등록되지 않아, 시드까지 내리면 활성 유니버스가 하나도 남지 않는다(실제로 테스트 7건이 이 이유로 깨졌다).
