# 진행 상태

세션이 바뀌어도 남아야 하는 "지금 어디까지 왔는가". 세션 시작 시 훅이 이 파일을 주입한다.

- 요구사항은 [`stock-screener-project-spec.md`](./stock-screener-project-spec.md), 설계 방향과 **phase 완료 판정 기준**은 [`design/v1-architecture.md`](./design/v1-architecture.md), 외부 API 실측 스펙은 [`api-spec/`](./api-spec/), 되돌리기 어려운 결정은 [`adr/`](./adr/)에 있다. **여기에 그것들을 옮겨 적지 않는다.**
- 무엇을 했는지의 서사는 `git log`에 있다. 여기에는 **다음 세션이 모르면 곤란한 것만** 적는다.
- MUST: 상태 주장은 확인 가능한 형태로 쓴다. "Phase 1 완료"가 아니라 "완료 판정: `./gradlew build` 성공 + ADR-0001~0004 존재"처럼 쓴다. 낡은 문서를 에이전트는 그대로 믿기 때문이다.
- **주입 상한은 8,000자다**(`inject-progress.sh`의 `limit`). 넘기면 뒷부분이 조용히 잘린다 — `check-doc-staleness.sh`가 세션 시작에 경고한다.

---

## Phase 상태

| Phase | 상태 | 완료 판정 |
|---|---|---|
| 1. 기반 | **완료** | design §5 Phase 1 기준 전부 통과 + ADR-0001~0004 |
| 2. 수집 파이프라인 | **완료 (실적재 검증됨)** | design §5 Phase 2 기준 전부 통과 + ADR-0005~0010 |
| 3. 지표 계산 | **완료 (실데이터 검증됨)** | design §5 Phase 3 기준 전부 통과 + ADR-0011 |
| 4. 스크리닝 엔진 | 미착수 | — |
| 5. 프론트엔드 | 미착수 | — |
| 6. 워치리스트·관리자·운영 | 미착수 | — |

**현재 코드 상태**: 테스트 **218개** 통과(`./gradlew test`, 외부 호출 0건) + 관통테스트 9개(`./gradlew liveTest`). 있는 것 — 스키마 V1~V9, 지표 16개 + 백분위 6개, 조건 모델, 인가, 수집 포트 4종·실패 격리·Spring Batch·배치 잡 4종·`BootstrapRunner`, 국내 시세·재무 어댑터, 시총 유니버스 어댑터, 지표 계산 엔진. 없는 것 — 미국 어댑터(v1.1, ADR-0010), API 컨트롤러·프론트엔드(Phase 4~5).

**실데이터가 들어 있다** (2026-08-06). 유니버스 350종목(KOSPI 200 + KOSDAQ 150), 시세 실패 0·OHLC 위반 0·상장주식수 100%. `IndicatorBackfillJob` SUCCESS — **1,085,755행**, 22종 지표 전부 ~348종목에서 산출. 삼성전자 PER를 손계산과 대조해 일치 확인.

- **PER이 267종목만 나오는 것은 정상이다** — 적자 기업에 값을 내지 않는 규칙이 동작한 결과다.
- **극단값을 발견하면 먼저 분기 내역을 본다.** PER 44,787(원익QnC)을 조사하니 4분기 합이 0.2억이라 EPS가 0.71이었다. TTM 합산이 맞고 실제 결산이 그런 것이다.

**미국은 v1.1로 연기가 spec에 반영됐다**(ADR-0010). v1 수용 기준은 국내뿐이다.

---

## 다음에 할 일

**Phase 4(스크리닝 엔진).** 조건 모델·번역기·백분위·실데이터가 모두 준비됐고 HTTP 진입점만 없다. 착수 전 design §5에 Phase 4 완료 판정을 먼저 적는다.

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

**환경**: JDK 21.0.8, PostgreSQL 16.14, DB 2개 생성됨. Docker 없음.

---

## 외부 소스에서 가장 위험한 것

실측 스펙 전체는 [`api-spec/`](./api-spec/)에 있고 그것이 정본이다. 아래 둘만 여기 남기는 이유는 **api-spec이 자동 로드되지 않아 모르고 지나칠 수 있고, 둘 다 아무 에러 없이 틀린 데이터를 저장하기 때문**이다. 어댑터에 방어가 구현되어 있으니 지우지 마라.

- **IMPORTANT — 공공데이터포털은 없는 종목·휴장일·파라미터 오타의 응답이 완전히 동일하다**(`resultCode "00"` + `totalCount 0` + HTTP 200). 날짜 형식 오타가 "그날 데이터 없음"으로 조용히 저장된다. `resultCode` 검사로는 못 잡으며 **요청 전 파라미터 검증이 유일한 방어**다.
- **IMPORTANT — `totalCount`를 보지 않으면 조용히 잘린다.** 6.5년 요청 시 `totalCount=1618`인데 `numOfRows=1000`이면 앞의 618일이 **경고 없이** 사라진다. 페이징으로 방어한다.

두 소스의 실패 표현이 정반대라 공통 처리가 불가능하다는 것(ADR-0009), 시세 이력이 2020-01-02부터라는 것, 발행주식수를 시세 응답에서 얻는다는 것(ADR-0013)도 api-spec과 해당 ADR에 있다.

---

## ADR 대상은 아니지만 알아야 하는 결정

되돌리기는 쉽지만, 모르면 다시 만들거나 어긋나게 되는 것들.

- **phase별 브랜치를 만들지 않는다.** `main`에 직접 쌓는다(2026-08-06 결정). Phase 2 브랜치가 `main`을 phase 1에 4개월 묶어둔 전례가 근거다 — 병렬 작업이 없어 격리할 대상이 없었고, 되돌리기 단위는 이미 커밋이 담당한다. 브랜치는 **버릴 수 있는 탐색**에만 쓴다.
- **커밋 전 `git add -A`를 쓰지 않는다.** `1873cf3`이 문서 한 줄을 고치면서 macOS 복제본(`AlertEvent 2.java` 형태) 42개를 함께 담아 컴파일이 멈췄다. `./gradlew checkDuplicateFiles`가 이제 그것을 duplicate class 27건 대신 한 줄로 잡는다.
- **테스트 커넥션 풀은 2로 제한한다.** 컨텍스트마다 별도 풀이 생기고 TestContext는 캐시하며 닫지 않아, 기본값 10이면 `max_connections`를 넘어 **무관한 테스트가** "too many clients"로 실패한다. 실제로 14건이 이렇게 깨졌고 새 코드의 결함으로 오해하기 쉬웠다.
- **테스트 프로파일에 `out-of-order: true`가 필요하다.** 테스트 전용 V900이 항상 운영 마이그레이션보다 위에 있어 새 운영 버전이 뒤늦게 들어온다. 운영에는 적용하지 않는다.
- **마이그레이션 체크섬이 어긋나면** `clean-on-validation-error`가 항상 구해주지 않는다. 테스트 DB에서 해당 `flyway_schema_history` 행과 객체를 지우고 재적용한다.
- **`spring.batch.job.enabled=false`, `pipeline.scheduler.enabled` 기본 꺼짐을 유지한다.** 전자를 켜면 재시작마다 초기 적재가 시작되고, 후자를 어댑터 없이 켜면 매일 실패 알림만 쌓인다.
- **`FinancialFigures`의 `eps`·`bps`를 지표 계산에 쓰면 안 된다.** 소스가 준 값이며 지표는 언제나 자체 계산이다(spec §7). 두 필드는 대조용이다.
- **재무 지표의 유량/저량 구분이 계산의 핵심이다.** 매출·이익·현금흐름은 4분기 합(TTM), 자산·부채·자본은 최근 분기 잔액이다. 저량을 합산하면 4배가 되는데 값이 그럴듯해서 드러나지 않는다. `FinancialMath.sumTrailingFourQuarters`는 유량 전용이다.
- **백분위는 항상 오름차순 기준이다.** `PER_PCTL`이 0에 가까우면 PER이 낮다는 뜻이고, "상위 20%"는 지표에 따라 `<= 0.2`이거나 `>= 0.8`이다. 지표마다 방향을 뒤집어 저장하지 않는 이유는 조건을 읽는 사람이 매번 그 지표의 방향을 확인해야 하기 때문이다.
- **`IndicatorDataReader`가 분기 재무에 한 해치 여유를 얹어 읽는다.** PER의 TTM은 정확히 4분기라 여유가 0인데 공시가 실제로 빠지는 일이 있어 경계에서 값이 조용히 사라진다. 계산기의 `financialYearsNeeded`는 "의미상 필요한 기간"이고 누락 대비 여유는 읽는 쪽 책임이다.
- **테스트는 로컬 PostgreSQL을 쓴다**(H2는 EAV가 의존하는 PG 고유 기능을 못 쓴다). **Zonky는 재검토 불필요.** 격리는 `@Transactional` 롤백이 기본이지만 실패 격리·배치 테스트는 커밋된 트랜잭션을 요구해 `@AfterEach` 정리를 쓴다. `JobRestartTest`는 `@BeforeEach`에서도 정리한다 — 남은 잡 인스턴스가 `JobInstanceAlreadyCompleteException`을 일으켜 재시작성 결함처럼 보인다.
- **패키지 구조**: `indicator`, `screening`(+`.query`), `stock`, `auth`, `notification`, `pipeline`(`.datasource`/`.collector`/`.batch`/`.scheduler`), `probe`. `pipeline` 하위 이름은 `.claude/rules/data-pipeline.md`의 path glob에 **의도적으로 맞춘 것**이다 — 그래야 규칙이 자동 로드된다.

---

## 미룬 것 / 알려진 미완성

- **`clean-on-validation-error: true`가 `application-test.yaml`에 아직 남아 있다.** 데이터가 생겼으므로 제거 대상이다. 테스트 DB만 비우지만, 운영 DB로 프로파일을 잘못 지정하면 되돌릴 수 없다.
- **외부 서비스 발급 8건 중 3건(데이터 소스) 완료, 5건 미진행.** [`external-credentials.md`](./external-credentials.md)가 정본이다. 남은 것은 Google OAuth·도메인·Cloudflare·Sentry·알림 채널이며 전부 Phase 5~6에 필요하다.
- **재무 수집이 일일 배치에 없다.** 분기 공시라 매일 조회하면 쿼터만 태운다. 초기 적재에만 있고 공시 시즌 갱신은 수동 실행 전제 — 자동화는 공시 일정을 아는 것이 전제이고 그것 자체가 또 하나의 수집 대상이다.
- **워치리스트 조건 판정이 일일 배치에 없다.** 워치리스트 기능 자체가 Phase 6이다. `AlertPort`가 사용자 대상 알림(`recipientUserId`)을 지원하므로 이음매는 준비되어 있다.
- **배치 잡 4종에 HTTP 진입점이 없다.** CLI는 `BootstrapRunner`가 있다(위 실행 방법). 부분 재실행은 `DailyUpdateJob.retryFailures()`까지 있고 HTTP만 없다 — Phase 4.
- **actuator 미도입**: Phase 6. `/actuator/health`가 열려 있지만 404다.

---

## 되돌리지 말 것

**공통점: 지워도 컴파일되고 테스트도 통과하는데 나중에 조용히 망가진다.** 개별 근거는 각 ADR에 있고 `applies_to`로 해당 파일을 건드릴 때 자동 로드된다. 아래 셋만 여기 남기는 이유는 **그 파일을 건드리지 않아도 무너질 수 있어서** 조건부 로드가 걸리지 않기 때문이다.

- **ADR-0005 — `JobRepository` 빈 직접 정의를 지우지 않는다.** 지우면 인메모리로 돌아가 아무것도 기록되지 않고 재시작이 불가능해지는데 **아무 증상이 없다.** `BATCH_*`에 `@Entity`를 만들지 않는다.
- **ADR-0007 — `rollbackFor = Exception.class`와 `StockTaskRunner` 분리를 유지한다.** Spring 기본은 checked 예외에 커밋하고, 자기 호출은 프록시를 안 거쳐 `REQUIRES_NEW`가 무시된다. 둘 다 실패 격리를 소리 없이 무력화한다.
- **ADR-0012 — 시드 유니버스(`KOSPI200` 등)를 비활성으로 내리지 않는다.** 시총 어댑터가 `@ConditionalOnApiKey`라 키 없는 환경에서 등록되지 않아, 시드까지 내리면 활성 유니버스가 하나도 남지 않는다(실제로 테스트 7건이 이 이유로 깨졌다).
