# 진행 상태

세션이 바뀌어도 남아야 하는 "지금 어디까지 왔는가". 세션 시작 시 훅이 이 파일을 주입한다.

- 요구사항은 [`stock-screener-project-spec.md`](./stock-screener-project-spec.md), 설계 방향은 [`design/`](./design/), 되돌리기 어려운 결정은 [`adr/`](./adr/)에 있다. 여기에 그것들을 옮겨 적지 않는다.
- 무엇을 했는지의 서사는 `git log`에 있다. 여기에는 **다음 세션이 모르면 곤란한 것만** 적는다.
- MUST: 상태 주장은 확인 가능한 형태로 쓴다. "Phase 1 완료"가 아니라 "완료 판정: `./gradlew build` 성공 + ADR-0001~0004 존재"처럼 쓴다. 낡은 문서를 에이전트는 그대로 믿기 때문이다.
- 한 페이지를 넘기지 않는다. 주입에 10,000자 상한이 있고, 길어지면 오히려 신호가 묻힌다.

---

## Phase 상태

| Phase | 상태 | 완료 판정 |
|---|---|---|
| 1. 기반 | **완료** | `./gradlew build` 성공 + QueryDSL 쿼리를 실제 실행하는 테스트 통과 + ADR-0001~0004 기록됨 |
| 2. 수집 파이프라인 | **완료 (실적재 검증됨)** | 아래 "Phase 2 판정" 참조 |
| 3. 지표 계산 | **완료 (실데이터 검증됨)** | 아래 "Phase 3 판정" 참조 |
| 4. 스크리닝 엔진 | 미착수 | — |
| 5. 프론트엔드 | 미착수 | — |
| 6. 워치리스트·관리자·운영 | 미착수 | — |

**현재 코드 상태**: 테스트 **218개** 통과(`./gradlew test`, 외부 호출 0건) + **관통테스트 9개**(`./gradlew liveTest`). 있는 것 — 스키마(V1~V9), **지표 16개 + 백분위 6개**, 조건 모델, 인가, 수집 포트 4종·실패 격리·Spring Batch·배치 잡 4종·`BootstrapRunner`, **국내 시세·재무 어댑터**, **시총 유니버스 어댑터**, **지표 계산 엔진**. 없는 것 — **미국 어댑터(v1.1, ADR-0010)**, API 컨트롤러·프론트엔드(Phase 4~5).

**Phase 2 판정** (설계문서에 기준이 없어 새로 작성했다). 전부 통과 — 소스 교체 국소성(상위 계층 벤더 이름 0건), 종목 단위 실패 격리와 실패 식별, 실패한 부분만 재실행, 실패 시 요약 알림 1건, 초기 적재/일일 갱신 분리, 중단 후 이어서 재시작, 멱등성, EXCLUDE 준수, **국내 두 소스 실제 호출 검증**, ADR-0005~0010 기록.

**초기 적재 완료** (2026-08-06 실행). 유니버스 350종목(KOSPI 200 + KOSDAQ 150), 시세 SUCCESS·실패 0·OHLC 위반 0, 상장주식수 100% 채움. 저장값을 API 원본과 대조해 일치 확인(삼성전자 종가·거래량).

**미국은 v1.1로 연기가 spec에 반영됐다**(ADR-0010). v1 수용 기준은 국내뿐이다.

**Phase 3 판정** (설계문서에 기준이 없어 Phase 2와 같은 방식으로 새로 작성했다). 합성 데이터 통합테스트로 전부 통과 — 지표 추가 비용 1파일(`IndicatorExtensibilityTest`), 파생 지표가 부모 시계열을 받아 계산(MACD-V), 워밍업 구간에 행 없음, 기술적 지표 `adj_close`/PER·PBR `close`, 멱등성, 종목 단위 실패 격리, 무효화 2종(`PRICE_ADJUSTED`·`FINANCIAL_REVISED`), 백분위가 유니버스 한정 오름차순, 보존 정리가 원자료를 건드리지 않음, `indicator_value` 컬럼 5개 유지, ADR-0011 기록.

**실데이터 검증 완료** (2026-08-06). `IndicatorBackfillJob` SUCCESS — 8,750건·실패 0·**1,085,755행**, 22종 지표 전부 ~348종목에서 산출됐다.

- **손계산 대조**: 삼성전자 PER 19.86 = 종가 286,000 ÷ (순이익TTM 84.2조 ÷ 58.5억주). 엔진 출력과 일치.
- **PER이 267종목만 나오는 것은 정상이다** — 적자 기업에 값을 내지 않는 규칙이 동작한 결과다.
- **극단값도 정상이었다.** PER 44,787(원익QnC)을 조사하니 4분기 합이 144.2 − 53.9 + 25.8 − 115.9 = 0.2억이라 EPS가 0.71이다. TTM 합산이 맞고 실제 결산이 그런 것이다 — 이상값을 발견하면 먼저 분기 내역을 본다.
- 백분위는 오름차순이 맞다(최저 PER 0.5 → 0.000, 최고 → 1.000).

---

## 다음에 할 일

**Phase 4(스크리닝 엔진).** 조건 모델·번역기·백분위·실데이터가 모두 준비됐고 HTTP 진입점만 없다.

### 배치 실행 방법 (`BootstrapRunner`, `bootstrap` 프로파일 전용)

```bash
./gradlew bootRun --args='--spring.profiles.active=bootstrap --job=universe'
./gradlew bootRun --args='--spring.profiles.active=bootstrap --job=initial-load \
    --price-years=7 --financial-years=7 --reload-existing=true'
./gradlew bootRun --args='--spring.profiles.active=bootstrap --job=backfill --from=2020-01-02'
```

- **`--reload-existing=true`가 없으면 기간을 늘려도 아무 일도 일어나지 않는다.** 기본 대상 선정이 `NOT EXISTS (price_daily)`라 봉이 하나라도 있으면 건너뛰고, 잡은 "적재할 종목이 없다"며 **성공으로 끝난다.**
- **적재 중 코드를 재컴파일하지 않는다.** devtools가 실행 중인 프로세스에 새 클래스를 끼워넣어 스키마와 어긋난다(실제로 3종목이 이 이유로 실패했다).
- 검증: `ck_price_ohlc` 위반 0건, 편입 종목 중 시세 없는 종목 0건, `min(trade_date)`가 요청 구간 시작과 일치(**잘리지 않았다는 증거**), `listed_shares` 빈값 0건.

**적재 후 `clean-on-validation-error` 제거**(`application-test.yaml`) — 데이터가 생긴 시점의 의무다.

호출량: 유니버스 3콜 + 시세 **350콜**(전 기간이어도 종목당 1콜 — `numOfRows=2000`) + 재무 7년 9,800콜(DART 한도 18,000). 하루면 끝난다.

**환경**: JDK 21.0.8, PostgreSQL 16.14, DB 2개 생성됨. Docker 없음.

---

## 확인된 외부 사실 (2026-08-06 실제 키로 측정)

상세 스펙은 [`api-spec/`](./api-spec/)에 있다. 아래는 **추정이 아니라 실측**이다.

**IMPORTANT — 가장 위험한 것**: 공공데이터포털에서 **없는 종목·휴장일·잘못된 파라미터가 응답상 완전히 동일하다**(`resultCode "00"` + `totalCount 0` + HTTP 200). 날짜 형식 오타가 "그날 데이터 없음"으로 조용히 저장된다. `resultCode` 검사로는 못 잡으며 **요청 전 파라미터 검증이 유일한 방어**다(어댑터에 구현됨).

- **두 소스의 실패 표현이 정반대다.** data.go.kr은 인증 실패에 **HTTP 403 + 다른 봉투**(`OpenAPI_ServiceResponse.cmmMsgHeader`), DART는 인증 실패도 **HTTP 200 + `status "010"`**. 공통 처리 불가(ADR-0009).
- **종목별 기간조회가 된다** → 초기 적재 시세가 **350콜**. 일자별 전종목만 됐다면 구조가 달랐다.
- **KRX는 거래대금(`trPrc`)을 실제로 제공한다** → V1의 "합성 금지"를 지키면서 값을 채운다.
- **Q4 유도 가능.** `thstrm_amount`(분기 단독)와 `thstrm_add_amount`(누적)가 함께 온다. 실측: 삼성전자 2025 매출 FY 333.6조 − 3Q누적 239.8조 = Q4 93.8조.
- **계정명이 4개사(삼성전자·KB금융·카카오·NAVER)에서 일관**됐다. 금융지주도 `매출액`이다. 주요계정 API가 정규화하는 것으로 보이나 표본이 4개뿐이라 후보군 매칭을 유지한다.
- **`corpCode.xml`은 3.5MB ZIP**, 118,664법인 중 상장 3,925건. content-type이 `application/x-msdownload`라 타입으로 분기하면 실패한다.
- **`fnlttSinglIndx`는 쓰면 안 된다.** 계산된 비율 반환 + 2023년 3분기 이후만 제공. 초기 메모의 `fnlttCmpnyIndx`는 존재하지 않는 이름이다.
- **Finnhub `/stock/candle` → HTTP 403**(무료 불가). `/stock/financials-reported`는 무료로 동작한다. **미국은 v1.1로 연기**(ADR-0010, spec 반영 완료) — 대안이 전부 ToS나 한도 문제를 갖는다.
- **Stooq는 JS 챌린지로 차단**, Yahoo 비공식은 첫 요청부터 429. SEC EDGAR는 정상이나 재무 전용이다.
- **지수 구성종목을 주는 공식 무료 API가 없다**(ADR-0008). 그러나 **전종목 조회로 우회했다**(ADR-0012) — `basDt`만 주면 그날 상장 전 종목 2,872건이 시가총액·종목명·시장구분과 함께 **1콜**(785KB, 0.73초)에 온다. `mrktCls`로 시장 필터도 되고 과거 시점도 재현된다. 시드 CSV는 영구 폴백으로 남는다.
- **시세 이력은 2020-01-02부터다**(약 6.5년, 이분 탐색으로 경계 확인). 과거는 언제든 소급되므로 "천천히 쌓을" 필요가 없다.
- **IMPORTANT — `totalCount`를 보지 않으면 조용히 잘린다.** 6.5년 요청 시 `totalCount=1618`인데 `numOfRows=1000`이면 1,000행만 오고 앞의 618일이 **경고 없이** 사라진다. `numOfRows`는 2,000까지 허용되지만 문서화된 상한이 아니라 페이징으로 방어한다(ADR-0009 보완).
- **시총 상위 200에 우선주 5·리츠/스팩 2가 섞인다.** 보통주(종목코드 끝자리 `0`)만 걸러야 같은 회사가 두 번 편입되지 않는다.
- **IMPORTANT — DART 주요계정은 발행주식수를 주지 않는다.** 실측상 `shares_outstanding`이 전부 NULL이라 PER·PBR이 하나도 계산되지 않았다. 시세 응답의 `lstgStCnt`를 쓴다(추가 호출 0, 날짜별, 우선주가 별도 종목이라 보통주 수)(ADR-0013).

---

## ADR 대상은 아니지만 알아야 하는 결정

- **phase별 브랜치를 만들지 않는다.** `main`에 직접 쌓는다(2026-08-06 결정). Phase 2 브랜치가 `main`을 phase 1에 4개월 묶어둔 전례가 근거다 — 병렬 작업이 없어 격리할 대상이 없었고(병합 시 `main`이 앞선 커밋 0개, fast-forward), 되돌리기 단위는 이미 커밋이 담당한다. 브랜치는 **버릴 수 있는 탐색**에만 쓴다(예: 접근이 될지 모르는 실험, 스택이 다른 Phase 5).

- **커밋 전 `git add -A`를 쓰지 않는다.** `1873cf3`이 progress.md 한 줄을 고치면서 작업 트리에 있던 macOS 복제본(`AlertEvent 2.java` 형태) 42개를 함께 담아 컴파일이 멈췄다. `./gradlew checkDuplicateFiles`가 이제 그것을 duplicate class 27건 대신 한 줄로 잡는다.

되돌리기는 쉽지만, 모르면 다시 만들거나 어긋나게 되는 것들.

- **테스트 커넥션 풀은 2로 제한한다.** 컨텍스트마다 별도 풀이 생기고 TestContext는 캐시하며 닫지 않아, 기본값 10이면 `max_connections`(100)를 넘어 **무관한 테스트가** "too many clients"로 실패한다. 실제로 Phase 1 테스트 14건이 이 이유로 실패했고, 새 코드의 결함으로 오해하기 쉬웠다.
- **테스트 프로파일에 `out-of-order: true`가 필요하다.** 테스트 전용 V900이 항상 운영 마이그레이션보다 위에 있어 새 운영 버전이 뒤늦게 들어온다. 운영에는 적용하지 않는다.
- **마이그레이션 체크섬이 어긋나면** `clean-on-validation-error`가 항상 구해주지 않는다. 테스트 DB에서 해당 `flyway_schema_history` 행과 객체를 지우고 재적용한다. **초기 적재 이후에는 쓸 수 없다.**
- **`spring.batch.job.enabled=false`, `pipeline.scheduler.enabled` 기본 꺼짐을 유지한다.** 전자를 켜면 재시작마다 초기 적재가 시작되고, 후자를 어댑터 없이 켜면 매일 실패 알림만 쌓인다.
- **`FinancialFigures`에 `eps`·`bps`가 있지만 지표 계산에 쓰면 안 된다.** 소스가 준 값이며, 지표는 언제나 자체 계산이다(spec §7). 두 필드는 수집값과 자체 계산값을 대조할 때만 쓴다.
- **재무 지표의 유량/저량 구분이 계산의 핵심이다.** 매출·이익·현금흐름은 4분기 합(TTM), 자산·부채·자본은 최근 분기 잔액이다. 저량을 합산하면 4배가 되는데 값이 그럴듯해서 드러나지 않는다. `FinancialMath.sumTrailingFourQuarters`는 유량 전용이다.
- **백분위는 항상 오름차순 기준이다.** `PER_PCTL`이 0에 가까우면 PER이 낮다는 뜻이고, "상위 20%"는 지표에 따라 `<= 0.2`이거나 `>= 0.8`이다. 지표마다 방향을 뒤집어 저장하지 않는 이유는 조건을 읽는 사람이 매번 그 지표의 방향을 확인해야 하기 때문이다.
- **`IndicatorDataReader`가 분기 재무에 한 해치 여유를 얹어 읽는다.** PER의 TTM은 정확히 4분기라 여유가 0인데, 공시가 실제로 빠지는 일이 있어 경계에서 값이 조용히 사라진다. 계산기의 `financialYearsNeeded`는 "의미상 필요한 기간"이고 누락 대비 여유는 읽는 쪽 책임이다.
- **테스트는 로컬 PostgreSQL을 쓴다**(H2는 EAV가 의존하는 PG 고유 기능을 못 쓴다). **Zonky는 재검토 불필요.** 격리는 `@Transactional` 롤백이 기본이지만 실패 격리·배치 테스트는 커밋된 트랜잭션을 요구해 `@AfterEach` 정리를 쓴다. `JobRestartTest`는 `@BeforeEach`에서도 정리한다 — 남은 잡 인스턴스가 `JobInstanceAlreadyCompleteException`을 일으켜 재시작성 결함처럼 보인다.
- **패키지 구조**: `indicator`, `screening`(+`.query`), `stock`, `auth`, `notification`, `pipeline`(`.datasource`/`.collector`/`.batch`/`.scheduler`), `probe`. `pipeline` 하위 이름은 `.claude/rules/data-pipeline.md`의 path glob에 **의도적으로 맞춘 것**이다 — 그래야 규칙이 자동 로드된다.

---

## 미룬 것 / 알려진 미완성

- **외부 서비스 발급 8건 중 3건(데이터 소스) 완료, 5건 미진행.** [`external-credentials.md`](./external-credentials.md)가 정본이다. 남은 것은 Google OAuth·도메인·Cloudflare·Sentry·알림 채널이며 전부 Phase 5~6에 필요하다.
- **재무 수집이 일일 배치에 없다.** 분기 공시라 매일 850종목을 조회하면 쿼터만 태운다. 초기 적재에만 있고 공시 시즌 갱신은 수동 실행 전제 — 자동화는 공시 일정을 아는 것이 전제이고 그것 자체가 또 하나의 수집 대상이다.
- **워치리스트 조건 판정이 일일 배치에 없다.** 워치리스트 기능 자체가 Phase 6이다. `AlertPort`가 사용자 대상 알림(`recipientUserId`)을 지원하므로 이음매는 준비되어 있다.
- **배치 잡 4종 모두 HTTP·CLI 진입점이 없다.** 테스트에서만 호출된다. 초기 적재를 실행하려면 일회성 러너든 관리자 API든 진입점이 먼저 필요하다.
- **API 컨트롤러 없음**: Phase 4. 부분 재실행은 `DailyUpdateJob.retryFailures()`까지 있고 HTTP 진입점만 없다.
- **actuator 미도입**: Phase 6. `/actuator/health`가 열려 있지만 404다.

---

## 되돌리지 말 것

상세 근거는 각 ADR에 있다.

공통점: **지워도 컴파일되고 테스트도 통과하는데 나중에 조용히 망가진다.**

- ADR-0001 — QueryDSL OpenFeign 포크(`com.querydsl`로 되돌리면 런타임 `NoSuchMethodError`), `QuerydslProbeTest` 유지.
- ADR-0002 — 기술적 지표 `adj_close`/재무 `close`, `indicator_value` PK 컬럼 순서, `screen_condition.indicator_code`에 FK 없음.
- ADR-0003 — `ConditionOutcome` 3상태 유지, 번역기는 연산자에만 분기.
- ADR-0004 — `allowed_email`↔`app_user` FK 없음, `is_admin` 중복은 의도된 것.
- ADR-0005 — **`JobRepository` 빈 직접 정의 유지.** 지우면 인메모리로 돌아가 아무것도 기록되지 않고 재시작이 불가능해지는데 **아무 증상이 없다.** `BATCH_*`에 `@Entity` 만들지 않기.
- ADR-0006 — 포트 DTO에 비율 필드 넣지 않기(담을 곳이 없는 것이 "자체 계산"의 구조적 보장).
- ADR-0007 — **`rollbackFor = Exception.class` 유지**(Spring 기본은 checked에 커밋한다), **`StockTaskRunner` 분리 유지**(자기 호출은 프록시를 안 거쳐 `REQUIRES_NEW`가 무시된다), Spring Batch `skip` 미사용.
- ADR-0008 — 시드 유니버스 구현 유지, **조용한 폴백 금지.**
- ADR-0009 — data.go.kr **요청 전 파라미터 검증 유지**(빈 결과와 구별 불가), DART **status 파싱 유지**(인증 실패도 200), `@ConditionalOnApiKey` 유지(빈 문자열도 "속성 있음"이라 `@ConditionalOnProperty`로는 안 된다).
- ADR-0010 — 미국 포트·`SP500` 유니버스 행·시드 CSV를 **지우지 않는다.** 어댑터만 없을 뿐 구조는 그대로 두어 v1.1에서 어댑터 하나로 붙인다.
- ADR-0013 — **발행주식수는 시세 쪽(`price_daily.listed_shares`)을 우선한다.** 재무 쪽은 폴백으로 남긴다. PER·PBR이 주식수를 **봉마다** 읽는 것도 유지한다 — 하나로 고정하면 증자·분할 구간의 값이 통째로 틀리는데 그 값은 그럴듯하다.
- ADR-0012 — **시드 유니버스(`KOSPI200` 등)를 비활성으로 내리지 않는다.** 시총 어댑터가 `@ConditionalOnApiKey`라 키 없는 환경에서 등록되지 않아, 시드까지 내리면 활성 유니버스가 하나도 남지 않는다(실제로 테스트 7건이 이 이유로 깨졌다). 반대로 **새 유니버스는 비활성으로 시작**한다 — 활성이면 키 없는 개발·CI에서 매번 실패 알림이 쌓인다. 유니버스 코드에 **지수명을 차용하지 않는다.**
- ADR-0011 — **원자료는 지우지 않는다.** 지표는 원자료에서 재생성되지만 그 역은 불가능하고, 외부 API가 과거 구간을 계속 준다는 보장도 없다(Finnhub가 실제로 그렇게 바뀌었다). `IndicatorRetentionPolicy`가 `indicator_value`만 건드리는 것이 그 비대칭의 이행이다.
