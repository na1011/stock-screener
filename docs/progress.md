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
| 2. 수집 파이프라인 | **국내 완료 / 초기 적재 미실행** | 아래 "Phase 2 판정" 참조 |
| 3. 지표 계산 | 미착수 | — |
| 4. 스크리닝 엔진 | 미착수 | — |
| 5. 프론트엔드 | 미착수 | — |
| 6. 워치리스트·관리자·운영 | 미착수 | — |

**현재 코드 상태**: 테스트 **177개** 통과(`./gradlew test`, 외부 호출 0건) + **관통테스트 9개** 통과(`./gradlew liveTest`, 실제 API). 있는 것 — 스키마(V1~V6), 지표 3개, 조건 모델, 인가, 수집 포트 4종·실패 격리·Spring Batch·collector/writer·배치 잡 3종·유니버스 시드, **국내 시세(data.go.kr)·재무(DART) 어댑터**. 없는 것 — **미국 어댑터(v1.1로 연기, ADR-0010)**, 지표 계산 배치(Phase 3), API 컨트롤러·프론트엔드(Phase 4~5).

**Phase 2 판정** (설계문서에 기준이 없어 새로 작성했다). 전부 통과 — 소스 교체 국소성(상위 계층 벤더 이름 0건), 종목 단위 실패 격리와 실패 식별, 실패한 부분만 재실행, 실패 시 요약 알림 1건, 초기 적재/일일 갱신 분리, 중단 후 이어서 재시작, 멱등성, EXCLUDE 준수, **국내 두 소스 실제 호출 검증**, ADR-0005~0010 기록.

**미달 항목 2개**:
- **초기 적재 미실행** — 시드 CSV가 15종목 표본이라 실제 구성종목으로 교체해야 의미가 있다(사용자 작업).
- **미국 시장** — v1.1로 연기. spec §9 수용기준 미달이므로 `spec-change-proposal.md`의 승인이 필요하다.

---

## 다음에 할 일

**초기 적재 실행.** 코드는 준비됐고 남은 것은 데이터다.

1. **시드 CSV를 실제 구성종목으로 교체**(사용자 작업). `src/main/resources/universe/kospi200.csv`·`kosdaq150.csv`가 각 10·5종목 표본이다. 출처는 KRX 정보데이터시스템 → 지수 → 지수구성종목.
2. 환경변수 주입 후 `UniverseRebalanceJob` → `InitialLoadJob` 순으로 실행.
3. 검증: `ck_price_ohlc` 위반 0건, 편입 종목 중 시세 없는 종목 0건, 재무 6개 계정 채움 비율.
4. **적재 후 `clean-on-validation-error` 제거**(`application-test.yaml`) — 데이터가 생긴 시점의 의무다.

호출량 추정: 시세 350콜(종목당 1콜, 기간조회 가능) + 재무 4,200콜(종목당 12콜). 각각 일 한도 안에 들어간다.

**그 다음**: Phase 3(지표 계산). `IndicatorInvalidationPort`가 이미 호출되고 있으므로 구현만 채우면 된다.

**환경**: JDK 21.0.8, PostgreSQL 16.14, `stock_screener`·`stock_screener_test` DB 생성됨. Docker 없음.

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
- **Finnhub `/stock/candle` → HTTP 403**(무료 불가). `/stock/financials-reported`는 무료로 동작한다. **미국은 v1.1로 연기**(ADR-0010) — 대안이 전부 ToS나 한도 문제를 갖는다. `spec-change-proposal.md` 승인 필요.
- **Stooq는 JS 챌린지로 차단**, Yahoo 비공식은 첫 요청부터 429. SEC EDGAR는 정상이나 재무 전용이다.
- **지수 구성종목을 주는 공식 무료 API가 없다**(ADR-0008). 시드 CSV가 기본이자 영구 폴백이며, 현재 **표본 15종목**이라 실제 구성종목으로 채워야 한다.

---

## ADR 대상은 아니지만 알아야 하는 결정

되돌리기는 쉽지만, 모르면 다시 만들거나 어긋나게 되는 것들.

- **테스트 커넥션 풀은 2로 제한한다.** 컨텍스트마다 별도 풀이 생기고 TestContext는 캐시하며 닫지 않아, 기본값 10이면 `max_connections`(100)를 넘어 **무관한 테스트가** "too many clients"로 실패한다. 실제로 Phase 1 테스트 14건이 이 이유로 실패했고, 새 코드의 결함으로 오해하기 쉬웠다.
- **테스트 프로파일에 `out-of-order: true`가 필요하다.** 테스트 전용 V900이 항상 운영 마이그레이션보다 위에 있어 새 운영 버전이 뒤늦게 들어온다. 운영에는 적용하지 않는다.
- **마이그레이션 체크섬이 어긋나면** `clean-on-validation-error`가 항상 구해주지 않는다. 테스트 DB에서 해당 `flyway_schema_history` 행과 객체를 지우고 재적용한다. **초기 적재 이후에는 쓸 수 없다.**
- **`spring.batch.job.enabled=false`, `pipeline.scheduler.enabled` 기본 꺼짐을 유지한다.** 전자를 켜면 재시작마다 초기 적재가 시작되고, 후자를 어댑터 없이 켜면 매일 실패 알림만 쌓인다.
- **`FinancialFigures`(지표 계층)에 `totalLiabilities`·`operatingCashFlow`·`eps`·`bps`·`currencyCode`가 없다.** 스키마와 `RawFinancialReport`에는 있어 수집은 되지만, Phase 3에서 부채비율·ROE를 만들 때 확장해야 한다.
- **테스트는 로컬 PostgreSQL을 쓴다**(H2는 EAV가 의존하는 PG 고유 기능을 못 쓴다). **Zonky는 재검토 불필요**(조사 완료). 격리는 `@Transactional` 롤백이 기본이지만 실패 격리·배치 테스트는 커밋된 트랜잭션을 요구해 `@AfterEach` 정리를 쓴다. `JobRestartTest`는 `@BeforeEach`에서도 정리한다 — 남은 잡 인스턴스가 `JobInstanceAlreadyCompleteException`을 일으키고 그것이 재시작성 결함처럼 보인다.
- **패키지 구조**: `indicator`, `screening`(+`.query`), `stock`, `auth`, `notification`, `pipeline`(`.datasource`/`.collector`/`.batch`/`.scheduler`), `probe`. `pipeline` 하위 이름은 `.claude/rules/data-pipeline.md`의 path glob에 **의도적으로 맞춘 것**이다 — 그래야 규칙이 자동 로드된다.

---

## 미룬 것 / 알려진 미완성

- **외부 서비스 발급 8건 — 전부 미진행.** [`external-credentials.md`](./external-credentials.md). **키 3개가 어댑터 작업의 전제다.**
- **재무 수집이 일일 배치에 없다.** 분기 공시라 매일 850종목을 조회하면 쿼터만 태운다. 초기 적재에만 있고 공시 시즌 갱신은 수동 실행 전제 — 자동화는 공시 일정을 아는 것이 전제이고 그것 자체가 또 하나의 수집 대상이다.
- **워치리스트 조건 판정이 일일 배치에 없다.** 워치리스트 기능 자체가 Phase 6이다. `AlertPort`가 사용자 대상 알림(`recipientUserId`)을 지원하므로 이음매는 준비되어 있다.
- **지표 보존 정책·백분위(`_PCTL`)·지표 간 의존 사용**: Phase 3.
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
