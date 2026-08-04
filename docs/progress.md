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
| 1. 기반 | **진행 중** (Step 4/7 완료) | `./gradlew build` 성공 + QueryDSL 쿼리를 실제 실행하는 테스트 통과 + ADR 기록됨 |
| 2. 수집 파이프라인 | 미착수 | — |
| 3. 지표 계산 | 미착수 | — |
| 4. 스크리닝 엔진 | 미착수 | — |
| 5. 프론트엔드 | 미착수 | — |
| 6. 워치리스트·관리자·운영 | 미착수 | — |

각 phase의 범위와 판정 기준은 [`design/v1-architecture.md`](./design/v1-architecture.md) §4·§5에 있다.

**현재 코드 상태**: Phase 1의 Step 1~4 완료. 의존성·스키마(V1~V4)·지표 확장 지점·지표 3개(SMA_200, PER, PBR)가 있다. 조건 모델 번역과 인가 로직은 아직 없다. 프론트엔드 디렉터리 없음.

**Step 4 확장 비용 측정 결과 (설계문서 §5 기준 2)**: **1개**. PBR을 추가하면서 계산기 클래스 파일 하나만 만들고 다른 파일은 하나도 고치지 않았다. 레지스트리·enum·스키마·번역기에 지표 코드 하드코딩이 0건임을 확인했다. 런타임에 카탈로그가 `[PBR, PER, SMA_200]`로 동기화되는 것도 확인했다.

---

## 다음에 할 일

Phase 1 Step 5 — 조건 모델과 번역(FILTER/SCORING 두 판정 방식). 그 다음 Step 6(인가 로직), Step 7(ADR 정리).

**선결 조건은 모두 해결됨**: JDK 21.0.8 설치되어 있고 Gradle toolchain이 해결한다(시스템 기본은 17이지만 무관). PostgreSQL 16.14 구동 중이고 `stock_screener_test` DB 생성됨. Docker는 없으며 로컬 PG로 통합 테스트한다.

---

## ADR 대상은 아니지만 알아야 하는 결정

되돌리기는 쉽지만, 모르면 다시 만들거나 어긋나게 되는 것들. ADR에 쓰기엔 사소하고 코드만 봐서는 의도를 알기 어려운 판단을 여기 남긴다.

- **테스트는 로컬 PostgreSQL(`stock_screener_test`)을 쓴다.** H2를 쓰지 않는 이유는 EAV 설계가 PostgreSQL 고유 기능(INCLUDE 인덱스·`PERCENT_RANK`·`ON CONFLICT`·EXCLUDE 제약)에 의존하기 때문이다. H2 스위트는 초록불인 채로 실제 쿼리 플랜을 검증하지 못한다. **Zonky embedded-postgres는 재검토하지 않아도 된다** — 조사 결과 Boot 4 hang 버그는 2.8.0에서 수정되어 실제로 쓸 수 있지만, README 지원 상한이 Boot 4.0.x인데 이 프로젝트는 4.1이고 로컬 PG로 충분해 의존성을 늘리지 않기로 했다. Docker가 생기면 Testcontainers로 가는 것이 맞고, 접속 정보가 `application-test.yaml`에만 있어 설정 변경만으로 전환된다.
- **테스트 격리는 `@Transactional` 롤백이 기본이다.** 다만 Phase 2의 종목 단위 실패 격리는 커밋된 트랜잭션을 요구하므로(`REQUIRES_NEW`), 롤백을 모든 테스트의 전제로 삼지 않는다. 그 경우엔 `@AfterEach` 명시적 정리를 쓴다.
- **`JpaSliceTest` 애너테이션으로 JPA 슬라이스 테스트 설정을 묶었다.** `@DataJpaTest`가 Flyway 자동설정을 제외하고 임베디드 DB로 교체하려 하기 때문에, 매 테스트에 세 애너테이션을 반복해 붙이면 하나 빠뜨린 테스트가 다른 스키마를 보게 된다.
- **컴포넌트 스캔을 검증하는 테스트는 슬라이스가 아니라 `@SpringBootTest`를 쓴다.** JPA 슬라이스는 `@Component`를 스캔하지 않아 지표 확장성 테스트가 성립하지 않는다.
- **Phase 1 동안 마이그레이션 V1~V4를 자유롭게 수정한다.** 데이터가 없어 DB를 비우고 다시 적용하면 그만이다. 교정용 V5·V6를 쌓지 않는다. **MUST: Phase 2에서 초기 적재를 실행한 이후로는 수정 대신 새 버전을 추가한다.** 그때 `application-test.yaml`의 `clean-on-validation-error`도 제거한다.
- **`querydsl_probe`는 테스트 전용 마이그레이션(`db/testmigration/V900`)에 있다.** 도메인 테이블이 아니라 운영 스키마에 넣지 않았다.
- **지표 계산 규칙**: 기술적 지표는 `adj_close`(수정주가), PER·PBR·시가총액·표시가는 `close`(원주가)를 쓴다. 이유는 ADR-0002에 있다. 이 규칙을 어긴 지표는 분할이 일어나기 전까지 그럴듯한 값을 내므로 아무도 눈치채지 못한다.

---

## 미룬 것 / 알려진 미완성

- **Google Cloud Console에서 OAuth 클라이언트 발급** (~10분, 사용자 수동 작업). Phase 1은 인가 로직(허용목록 판정·권한 분리)까지만 하고 실제 핸드쉐이크는 하지 않는다. 설계문서 §5의 기준이 "허가되지 않은 이메일이 차단된다"이지 "브라우저 로그인이 된다"가 아니기 때문이다. 실제 마감은 Phase 6(Cloudflare Tunnel + 고정 도메인)이다.
- **지표 값 보존 정책**: 결정하지 않았다. 데이터가 없어 판단 근거가 없다. `indicator_value`가 날짜 기준으로 분할 가능한 형태이므로 Phase 3에서 마이그레이션으로 도입할 수 있다.
- **백분위(`_PCTL`) 계산**: Phase 3. 저장 구조는 이미 수용 가능함을 확인했다(파생 지표 코드로 평범한 행이 되며 `indicator_value`의 컬럼은 5개 그대로다).
- **지표 간 의존(MACD-V 등)**: 인터페이스(`DataRequirement.dependsOn`)와 레지스트리의 위상 정렬·워밍업 유도는 구현·테스트되어 있으나, 실제로 의존을 쓰는 지표는 아직 없다.
- **배치 실행 이력·인터랙션 로그·지지저항 테이블**: 각각 Phase 2·6·4. 형태가 그 시점에 결정되어야 해서 미뤘다.

---

## 되돌리지 말 것

이전 세션이 근거를 갖고 내린 판단 중, 모르고 보면 되돌리기 쉬운 것.

- **QueryDSL은 OpenFeign 포크(`io.github.openfeign.querydsl`)를 쓴다.** 표준으로 보이는 `com.querydsl`로 되돌리면 Hibernate 7에서 런타임에 깨진다. Boot 4.1이 아직 `querydsl.version=5.1.0`을 고정하고 있어 더 헷갈린다. 근거는 [`design/v1-architecture.md`](./design/v1-architecture.md) §1. Phase 1에서 ADR로도 남긴다.
