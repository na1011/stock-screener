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
| 1. 기반 | **미착수** | `./gradlew build` 성공 + QueryDSL 쿼리를 실제 실행하는 테스트 통과 + ADR 기록됨 |
| 2. 수집 파이프라인 | 미착수 | — |
| 3. 지표 계산 | 미착수 | — |
| 4. 스크리닝 엔진 | 미착수 | — |
| 5. 프론트엔드 | 미착수 | — |
| 6. 워치리스트·관리자·운영 | 미착수 | — |

각 phase의 범위와 판정 기준은 [`design/v1-architecture.md`](./design/v1-architecture.md) §4·§5에 있다.

**현재 코드 상태**: Spring Boot 골격만 있다. `StockScreenerApplication` 1개, 빈 `application.yaml`, webmvc 스타터. 도메인 코드 없음, 프론트엔드 디렉터리 없음.

---

## 다음에 할 일

Phase 1 착수. 시작 전에 [`design/v1-architecture.md`](./design/v1-architecture.md) §1(검증된 외부 사실)을 반드시 읽는다 — QueryDSL mainline을 쓰면 **컴파일은 통과하고 런타임에** `NoSuchMethodError`가 난다.

**선결 조건** (미해결):
- JDK 21 필요. 시스템 JDK는 17이다.
- 로컬 PostgreSQL 16에 데이터베이스·롤 생성 필요. (설치는 되어 있음)
- Docker 없음 → 통합 테스트 방식을 로컬 인스턴스 전제로 정해야 한다.

---

## ADR 대상은 아니지만 알아야 하는 결정

되돌리기는 쉽지만, 모르면 다시 만들거나 어긋나게 되는 것들. ADR에 쓰기엔 사소하고 코드만 봐서는 의도를 알기 어려운 판단을 여기 남긴다.

*(아직 없음 — Phase 1에서 패키지 구조·조건 모델의 형태 등이 정해지면 여기 기록한다)*

---

## 미룬 것 / 알려진 미완성

*(아직 없음)*

---

## 되돌리지 말 것

이전 세션이 근거를 갖고 내린 판단 중, 모르고 보면 되돌리기 쉬운 것.

- **QueryDSL은 OpenFeign 포크(`io.github.openfeign.querydsl`)를 쓴다.** 표준으로 보이는 `com.querydsl`로 되돌리면 Hibernate 7에서 런타임에 깨진다. Boot 4.1이 아직 `querydsl.version=5.1.0`을 고정하고 있어 더 헷갈린다. 근거는 [`design/v1-architecture.md`](./design/v1-architecture.md) §1. Phase 1에서 ADR로도 남긴다.
