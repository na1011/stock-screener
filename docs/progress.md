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
| 2. 수집 파이프라인 | 미착수 | — |
| 3. 지표 계산 | 미착수 | — |
| 4. 스크리닝 엔진 | 미착수 | — |
| 5. 프론트엔드 | 미착수 | — |
| 6. 워치리스트·관리자·운영 | 미착수 | — |

각 phase의 범위와 판정 기준은 [`design/v1-architecture.md`](./design/v1-architecture.md) §4·§5에 있다.

**현재 코드 상태**: Phase 1 완료. 있는 것 — 의존성, 스키마(V1~V4), 지표 확장 지점과 지표 3개(SMA_200/PER/PBR), 조건 모델과 FILTER/SCORING 번역, 허용목록 인가와 권한 분리. 없는 것 — 데이터 수집(Phase 2), 실제 지표 계산 배치(Phase 3), API 컨트롤러와 프론트엔드(Phase 4~5). 테스트 70개 통과.

**설계문서 §5 완료 기준 검증 결과**:

| 기준 | 결과 |
|---|---|
| QueryDSL이 런타임에 동작 | 통과. 실제 PostgreSQL에서 `.fetch()` 실행, 상관 EXISTS 생성 확인 |
| 지표 추가 비용 | **1개**. PBR을 추가하며 계산기 파일 하나만 만들고 다른 파일 0개 수정. 인프라에 지표 코드 하드코딩 0건 |
| 하나의 조건 집합이 두 판정 방식 지탱 | 통과. 같은 `ConditionGroup`이 FILTER/SCORING 양쪽에서 각각 올바르게 동작 |
| 조건 N개 → 예상 쿼리 형태 | 통과. EXISTS 정확히 N개, JOIN·DISTINCT 없음, 지표 코드는 바인드 파라미터 |
| 허가되지 않은 이메일 차단 | 통과. Google 자격증명 없이 합성 `OidcUser`로 검증. 미인증 401, 비관리자 403 |
| ADR 기록 | ADR-0001~0004, 모두 `applies_to`·`## 검증` 채움. 검증 명령 실제 실행해 통과 확인 |

---

## 다음에 할 일

**Phase 2 — 수집 파이프라인.** 착수 전에 [`design/v1-architecture.md`](./design/v1-architecture.md) §4의 Phase 2 항목과 `.claude/rules/data-pipeline.md`를 읽는다.

다룰 것: 공공데이터포털·DART·Finnhub 어댑터, 초기 적재(시세 2년·재무 3년), 일일 갱신 배치, 유니버스 리밸런싱 배치.

Phase 2에서 특히 걸리는 것:
- **초기 적재와 일일 갱신은 분리된 잡이다.** 성격이 다른 1회성 작업이다.
- **실패 격리 단위는 종목이다.** 실패한 종목을 식별할 수 있어야 관리자의 부분 재실행이 성립한다. `batch_execution`·`batch_execution_failure` 테이블이 이 시점에 필요해진다(형태는 배치의 실제 실패 단위가 정해진 뒤 결정하려고 미뤄뒀다).
- **Spring Batch 도입 여부를 여기서 판단한다.** 6.0.4가 Boot 4.1과 호환되는 것은 확인했다. 재시작성·청킹이 필요해지는 시점이 초기 적재다.
- **액면분할 시 과거 시세 전체를 재계산해야 한다.** `adj_close`와 `adj_factor`를 다시 쓰고 해당 종목의 저장된 기술적 지표를 무효화해야 한다. 스키마는 이를 지원하지만 배치가 그것을 알아야 한다.
- **MUST: 이 시점부터 마이그레이션을 수정하지 않는다.** 아래 "ADR 대상은 아니지만 알아야 하는 결정" 참조.

**환경**: JDK 21.0.8(Gradle toolchain이 해결), PostgreSQL 16.14 구동 중, `stock_screener`(운영)·`stock_screener_test`(테스트) DB 생성됨. Docker 없음.

---

## ADR 대상은 아니지만 알아야 하는 결정

되돌리기는 쉽지만, 모르면 다시 만들거나 어긋나게 되는 것들. ADR에 쓰기엔 사소하고 코드만 봐서는 의도를 알기 어려운 판단을 여기 남긴다.

- **테스트는 로컬 PostgreSQL(`stock_screener_test`)을 쓴다.** H2를 쓰지 않는 이유는 EAV 설계가 PostgreSQL 고유 기능(INCLUDE 인덱스·`PERCENT_RANK`·`ON CONFLICT`·EXCLUDE 제약)에 의존하기 때문이다. H2 스위트는 초록불인 채로 실제 쿼리 플랜을 검증하지 못한다. **Zonky embedded-postgres는 재검토하지 않아도 된다** — 조사 결과 Boot 4 hang 버그는 2.8.0에서 수정되어 실제로 쓸 수 있지만, README 지원 상한이 Boot 4.0.x인데 이 프로젝트는 4.1이고 로컬 PG로 충분해 의존성을 늘리지 않기로 했다. Docker가 생기면 Testcontainers로 가는 것이 맞고, 접속 정보가 `application-test.yaml`에만 있어 설정 변경만으로 전환된다.
- **테스트 격리는 `@Transactional` 롤백이 기본이다.** 다만 Phase 2의 종목 단위 실패 격리는 커밋된 트랜잭션을 요구하므로(`REQUIRES_NEW`), 롤백을 모든 테스트의 전제로 삼지 않는다. 그 경우엔 `@AfterEach` 명시적 정리를 쓴다.
- **`JpaSliceTest` 애너테이션으로 JPA 슬라이스 테스트 설정을 묶었다.** `@DataJpaTest`가 Flyway 자동설정을 제외하고 임베디드 DB로 교체하려 하기 때문에, 매 테스트에 세 애너테이션을 반복해 붙이면 하나 빠뜨린 테스트가 다른 스키마를 보게 된다.
- **컴포넌트 스캔을 검증하는 테스트는 슬라이스가 아니라 `@SpringBootTest`를 쓴다.** JPA 슬라이스는 `@Component`를 스캔하지 않아 지표 확장성 테스트가 성립하지 않는다.
- **Phase 1 동안 마이그레이션 V1~V4를 자유롭게 수정한다.** 데이터가 없어 DB를 비우고 다시 적용하면 그만이다. 교정용 V5·V6를 쌓지 않는다. **MUST: Phase 2에서 초기 적재를 실행한 이후로는 수정 대신 새 버전을 추가한다.** 그때 `application-test.yaml`의 `clean-on-validation-error`도 제거한다.
- **`querydsl_probe`는 테스트 전용이다.** 엔티티는 `src/test/java`에, 테이블은 `db/testmigration/V900`에 있다. 운영 소스에 두었더니 `ddl-auto: validate`가 테스트 전용 테이블을 요구해 애플리케이션이 뜨지 못했다. Q클래스도 `testAnnotationProcessor`로 생성한다.
- **OAuth 자격증명의 기본값이 빈 문자열이 아니라 `not-configured` 자리표시자다.** Spring Security의 `OAuth2ClientWebMvcSecurityConfiguration`이 `@EnableWebSecurity` 시점에 생성되며 `ClientRegistration`을 요구하기 때문이다. 빈 값이면 컨텍스트 기동이 실패하고, **필터 체인에서 조건부로 우회할 수 없다**(그보다 먼저 생성된다). 이걸 모르면 "자격증명 없이 기동되게" 만들려다 같은 실패를 반복한다.
- **테스트 프로파일에도 OAuth 자리표시자가 있다.** 같은 이유이며 실제 Google 호출은 하지 않는다 — 인가는 합성 `OidcUser`로 검증한다.
- **패키지 구조**: `indicator`(계산·레지스트리·카탈로그), `screening`(조건 모델·판정) + `screening.query`(QueryDSL 번역·실행), `stock`(JPA 엔티티), `auth`(인가·보안), `probe`(테스트 전용). 레이어가 아니라 도메인 기준으로 나눴다.
- **지표 계산 규칙**: 기술적 지표는 `adj_close`(수정주가), PER·PBR·시가총액·표시가는 `close`(원주가)를 쓴다. 이유는 ADR-0002에 있다. 이 규칙을 어긴 지표는 분할이 일어나기 전까지 그럴듯한 값을 내므로 아무도 눈치채지 못한다.

---

## 미룬 것 / 알려진 미완성

- **Google Cloud Console에서 OAuth 클라이언트 발급** (~10분, 사용자 수동 작업). Phase 1은 인가 로직(허용목록 판정·권한 분리)까지만 하고 실제 핸드쉐이크는 하지 않는다. 설계문서 §5의 기준이 "허가되지 않은 이메일이 차단된다"이지 "브라우저 로그인이 된다"가 아니기 때문이다. 실제 마감은 Phase 6(Cloudflare Tunnel + 고정 도메인)이다.
- **지표 값 보존 정책**: 결정하지 않았다. 데이터가 없어 판단 근거가 없다. `indicator_value`가 날짜 기준으로 분할 가능한 형태이므로 Phase 3에서 마이그레이션으로 도입할 수 있다.
- **백분위(`_PCTL`) 계산**: Phase 3. 저장 구조는 이미 수용 가능함을 확인했다(파생 지표 코드로 평범한 행이 되며 `indicator_value`의 컬럼은 5개 그대로다).
- **지표 간 의존(MACD-V 등)**: 인터페이스(`DataRequirement.dependsOn`)와 레지스트리의 위상 정렬·워밍업 유도는 구현·테스트되어 있으나, 실제로 의존을 쓰는 지표는 아직 없다.
- **배치 실행 이력·인터랙션 로그·지지저항 테이블**: 각각 Phase 2·6·4. 형태가 그 시점에 결정되어야 해서 미뤘다.
- **API 컨트롤러가 없다.** 스크리닝 실행은 `ScreeningRepository`까지만 있고 HTTP 진입점이 없다. Phase 4에서 만든다. `SecurityConfig`가 `/api/**`를 보호하도록 이미 설정되어 있다.
- **지표 계산 배치가 없다.** 계산기는 있지만 원자료를 읽어 `indicator_value`에 쓰는 러너가 없다. Phase 3이며, 종목당 원자료를 한 번만 읽고 위상 정렬 순서로 계산기를 돌리는 형태를 전제로 인터페이스를 설계해뒀다.
- **actuator를 넣지 않았다.** `SecurityConfig`가 `/actuator/health`를 공개로 열어뒀지만 의존성이 없어 404다. 운영 준비(Phase 6) 시점에 추가한다.

---

## 되돌리지 말 것

이전 세션이 근거를 갖고 내린 판단 중, 모르고 보면 되돌리기 쉬운 것. 상세 근거는 각 ADR에 있다.

- **QueryDSL은 OpenFeign 포크를 쓴다** (ADR-0001). 표준으로 보이는 `com.querydsl`로 되돌리면 **컴파일은 통과하고 런타임에** `NoSuchMethodError`가 난다. Boot 4.1이 아직 `querydsl.version=5.1.0`을 고정해 더 헷갈린다. classifier 규칙도 역전되어 있다(`querydsl-jpa`는 빼고 `querydsl-apt`는 붙인다).
- **`QuerydslProbeTest`와 프로브 엔티티를 지우지 않는다** (ADR-0001). "쓰이지 않는 코드"로 보이지만 위 회귀를 잡는 유일한 수단이다.
- **기술적 지표는 `adj_close`, 재무 지표는 `close`를 쓴다** (ADR-0002). 이 규칙을 어긴 지표는 액면분할이 일어나기 전까지 그럴듯한 값을 내므로 아무도 눈치채지 못한다.
- **`indicator_value` PK는 `(trade_date, indicator_code, stock_id)` 순이다** (ADR-0002). `stock_id`를 앞에 두는 "자연스러운" 순서로 바꾸면 스크리닝이 하루치 850행을 인덱스 전체에서 긁는다.
- **`ConditionOutcome`의 3상태를 2상태로 합치지 않는다** (ADR-0003). "지표 없음"을 "불충족"으로 합치면 SCORING에서 일부만 만족한 종목이 사라져 순위 매기기가 무너진다.
- **번역기·판정기는 연산자에만 분기한다** (ADR-0003). 지표 코드에 `switch`가 생기는 순간 확장 비용이 1을 넘는다.
- **`screen_condition.indicator_code`에 FK를 걸지 않는다** (ADR-0002). "빠진 제약"으로 보이지만, 폐기된 지표를 참조하는 저장된 화면이 깨지지 않게 하는 의도적 누락이다.
- **`allowed_email`과 `app_user` 사이에 FK를 걸지 않는다** (ADR-0004). 권한 회수가 사용자의 저장된 화면을 지워서는 안 된다.
- **`is_admin`이 두 테이블에 있는 것은 의도된 중복이다** (ADR-0004). `allowed_email`이 근거, `app_user`는 로그인 시 갱신되는 사본이다. 중복으로 보고 한쪽을 지우면 "권한 회수는 다음 로그인부터"라는 성질이 사라진다.
