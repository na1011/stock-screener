---
id: ADR-0005
status: 대체됨(→ ADR-0075)
date: 2026-08-05
applies_to:
  - "src/main/java/com/stockscreener/pipeline/batch/**"
  - "src/main/resources/db/migration/V6__spring_batch_metadata.sql"
  - "build.gradle"
---

# ADR-0005. Spring Batch를 도입하고 메타데이터 스키마를 Flyway로 관리한다

> **2026-08-20 — 대체됐다(ADR-0075). Spring Batch를 걷었다.**
> 이 결정이 세운 2계층(Batch가 재시작, `batch_execution`이 종목 실패, `job_execution_id`로 연결)의
> **링크가 한 번도 안 꽂혔다** — 컬럼 99행 전부 NULL, 메타 6테이블 0행. 아래 §배경이 예측한
> *「직접 만들면 600줄이 저품질 JobRepository가 된다」*는 **절반만 맞았다**: 실제로 1,336줄이 됐지만
> 그중 관리자 조회 300줄은 Spring Batch가 줄 수 없는 것이었다(ADR-0007이 그 이유를 적었다).
> **이 문서는 그 판단의 기록으로 남긴다.** 지금 무엇이 재시작을 주는지는 ADR-0075가 답한다.

## 결정

Spring Batch 6.0.4를 도입한다. `JobRepository`를 JDBC 구현으로 **명시적으로 정의**하고, `BATCH_*` 메타데이터 스키마는 Boot의 자동 초기화가 아니라 Flyway 마이그레이션(V6)이 관리한다.

## 규칙

- MUST: `JobRepository` 빈을 `JdbcJobRepositoryFactoryBean`으로 직접 정의한다. Boot 4.1은 이것을 자동 구성하지 않으며 기본값이 인메모리다.
- MUST: `BATCH_*` DDL은 `spring-batch-core` jar의 `org/springframework/batch/core/schema-postgresql.sql`에서 꺼내 그대로 쓴다. 손으로 옮겨 적지 않는다.
- MUST: 버전을 올릴 때 같은 방식으로 다시 꺼내 **새 마이그레이션으로 추가**한다. V6를 수정하지 않는다.
- MUST: `spring.batch.job.enabled=false`를 유지한다. 초기 적재가 애플리케이션 재시작마다 시작되면 안 된다.
- MUST NOT: `BATCH_*` 테이블에 대응하는 `@Entity`를 만들지 않는다. 만드는 순간 `ddl-auto: validate`의 검사 대상이 되어 Spring Batch 버전 업그레이드가 기동 실패로 이어진다.
- MUST NOT: `spring.batch.jdbc.*` 속성을 쓰지 않는다. Boot 4.1에 존재하지 않아 조용히 무시된다.

## 검증

**이 결정은 대체됐다. 지켜지는지 잴 것이 없다** — 검증의 정본은 ADR-0075다.

여기 남기는 것은 **되돌아왔는지**를 재는 것 하나다. 아무것도 반환하지 않아야 한다:

```bash
rg -n "^\s*(implementation|testImplementation).*spring-batch" build.gradle
```

나오면 Spring Batch가 다시 들어온 것이고, 그때는 ADR-0075의 §버린 대안(특히 Tasklet `Step`)을
먼저 읽는다 — **꽂지 않은 채 의존성만 되돌리는 것이 정확히 이 문서가 남긴 상태였다.**

## 배경

`progress.md`가 "Spring Batch 도입 여부를 Phase 2에서 판단한다"고 예고했다. 도입한 이유는 **초기 적재의 재시작성이 실제로 필요**하기 때문이다. 850종목 × 2년 시세를 일일 호출 한도가 있는 소스에서 받으므로 한 번에 끝나지 않는 것이 정상 시나리오이고, "어디까지 했는가"를 직접 만들면 그것이 곧 저품질 `JobRepository`가 된다. 배치 경험이 spec §2가 명시한 학습 목표라는 점도 같은 방향을 가리킨다(QueryDSL을 jOOQ 대신 고른 spec §6의 판단과 같은 결).

IMPORTANT — Boot 4.1은 JDBC `JobRepository`를 **자동 구성하지 않는다.** 기본값은 `ResourcelessJobRepository`(인메모리)이고, 그대로 두면 잡은 정상 실행되고 로그도 깨끗하며 `BATCH_*` 테이블도 존재하지만 **아무것도 기록되지 않아 재시작이 불가능하다.** 도입 근거가 통째로 사라지는데 아무 증상이 없다. `BatchProperties`에 `job` 항목만 있어 `spring.batch.jdbc.initialize-schema` 같은 Boot 3 속성은 존재하지도 않는다.

6.x에서 바뀐 것이 둘 더 있다. `BATCH_JOB_SEQ`가 `BATCH_JOB_INSTANCE_SEQ`로 이름이 바뀌었고, 패키지가 `org.springframework.batch.item`에서 `org.springframework.batch.infrastructure.item`으로 옮겨졌다. Boot 3 예제를 복사하면 둘 다 틀린다.

`ddl-auto: validate`와는 충돌하지 않는다. Hibernate의 validate는 **매핑된 엔티티에 대응하는 테이블만** 검사하며, 테이블이 있는데 엔티티가 없는 방향은 문제 삼지 않는다. `querydsl_probe`에서 겪은 함정의 반대 케이스다.

## 버린 대안

- **`@Scheduled` + 직접 만든 실행 루프** — 850종목 규모에는 충분하다. 그러나 재개 지점·실행 이력·부분 재실행을 손으로 만들면 200줄이 600줄이 되고, 그 600줄이 저품질 `JobRepository`다. 학습 목표에서도 배치 경험이 통째로 빠진다.
- **Boot의 스키마 자동 초기화** — Flyway와 실행 순서 경합이 있고, "스키마의 정본은 Flyway"라는 규칙을 깬다. Boot 4.1에는 그 속성 자체가 없다.
- **인메모리 `JobRepository` 유지** — 도입 이유(재시작성)가 사라진다. 아무 증상이 없어 더 위험하다.
- **`BATCH_*` 전용 DataSource 분리** — 도메인 쓰기와 배치 상태 갱신이 같은 트랜잭션에서 커밋되어야 재시작 지점이 실제 저장 결과와 어긋나지 않는다.
- **Spring Batch의 `faultTolerant().skip()`으로 종목 실패 격리** — ADR-0007에서 따로 다룬다.

## 결과

- 쉬워지는 것: 쿼터 소진으로 중단된 초기 적재가 다음 실행에서 이어진다. 잡 파라미터로 부분 재실행을 표현할 수 있다.
- 어려워지는 것: 메타데이터 테이블 6개가 늘고, Spring Batch 버전을 올릴 때 마이그레이션을 직접 꺼내 추가해야 한다.
- 되돌리려면: `build.gradle`의 의존성, `BatchInfrastructureConfig`, V6 마이그레이션, `pipeline/batch`의 잡들을 함께 고쳐야 한다.
