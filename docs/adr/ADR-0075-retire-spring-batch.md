---
id: ADR-0075
status: 채택됨
date: 2026-08-20
applies_to:
  - "build.gradle"
  - "src/main/java/**/pipeline/batch/**"
  - "src/main/resources/db/migration/**"
---

# ADR-0075. Spring Batch를 걷는다 — 2계층 설계가 미완이었고 청크 모델이 애초에 막혀 있었다

**ADR-0005를 대체한다.**

## 결정

`spring-boot-starter-batch`와 `spring-batch-test` 의존성, `BatchInfrastructureConfig`,
`BatchExecutionRecorder.linkJobExecution()`, `JobRestartTest`를 걷는다. V6가 만든 메타데이터
6테이블과 시퀀스 셋, `batch_execution.job_execution_id` 컬럼을 V31로 DROP한다.

**도는 배치는 한 줄도 안 건드린다.** 잡 아홉, 실행 이력, 종목 단위 격리, task 단위 진행률,
관리자 재실행, 셧다운 마감이 전부 그대로다.

**딸려 나온 것이 하나 있다 — `spring-retry`의 버전이다.** build.gradle의 주석이 *「spring-batch-core가
전이로 끌어오지만 직접 선언한다 — 전이 의존에 기대면 Batch 업그레이드가 조용히 걷어갈 수 있다」*고
적어 두었는데, **직접 선언된 것은 아티팩트뿐이고 버전은 여전히 Batch의 BOM이 주고 있었다.**
걷자마자 `Could not find org.springframework.retry:spring-retry:.`로 컴파일이 멈췄다. 걷기 직전에
해석되던 `2.0.13`을 박았다. **그 주석이 막으려던 사고가 정확히 그 주석 아래에서 일어났다** —
「직접 선언했다」가 「버전까지 직접 정했다」를 뜻하지 않는다.

## 왜 걷는가

**① 2계층 설계가 미완이었다.** ADR-0005+0007이 설계한 것은 둘로 나눈 구조다 — Spring Batch가
재시작을 쥐고, `batch_execution`이 종목 단위 실패를 쥐고, `job_execution_id`로 잇는다.
**그 링크가 한 번도 안 꽂혔다**: 컬럼이 99행 전부 NULL이고 채우는 `linkJobExecution()`에
호출자가 0이다. 메타 6테이블은 운영·테스트 DB 양쪽에서 0행이다.

**이것은 새 발견이 아니다.** ADR-0037이 2026-08-10에 *「이 잡들은 `Job`/`Step`을 쓰지 않아
`BATCH_STEP_EXECUTION`이 0행이다」*라고 이미 기록했다. 즉 여기 있던 것은 「Spring Batch +
원장」이 아니라 **「원장 + 안 꽂힌 의존성」**이었다.

**② 청크 모델이 이 워크로드에서 막혀 있다.** ADR-0007이 `faultTolerant().skip()`을 거절한
이유는 성능이 아니라 **쿼터**다 — skip은 청크를 롤백하고 아이템을 하나씩 다시 처리하므로
**실패한 종목 하나 때문에 같은 청크의 성공 종목이 API를 다시 호출한다.** 일일 한도가 있는
소스에서 그것은 그대로 낭비다. 청크 기반 내결함성을 못 쓰면 남는 가치는 `ExecutionContext`
재시작 하나로 줄어든다.

**③ 그 재시작조차 Spring Batch가 준 적이 없고, 지금도 다른 것이 준다.** 재개는 **잡마다 자기
대상 선정 쿼리**가 하고 그 정밀도는 잡마다 다르다. `batch_execution_phase`는 **체크포인트가
아니다** — PK가 `(execution_id, phase)`라 재실행이 자기 행을 새로 만들고, 읽는 곳은 관리자
조회뿐이다(ADR-0037의 진행률 투영). **재개가 실제로 얹혀 있는 것은 멱등 upsert이며,
`InitialLoadResumeTest`가 이번에 그것을 처음으로 잰다.**

**④ 자체 계층은 「저품질 `JobRepository`」가 아니다.** ADR-0005가 *「직접 만들면 200줄이
600줄이 되고 그 600줄이 저품질 `JobRepository`다」*라고 경고했고 지금 실행추적 계층은
1,336줄이다. **그러나 그 예측은 절반만 맞았다** — `BatchExecutionReader` 300줄은 전부 관리자
조회이고, ADR-0007이 *「Spring Batch는 실패한 아이템을 구조화해 저장하지 않고(`EXIT_MESSAGE`에
문자열일 뿐)… 관리자의 부분 재실행이 문자열 파싱에 의존할 수는 없다」*고 이미 못박았다.
spec이 요구하는 *「실패한 부분만 골라 수동 재실행」*을 위해 **Spring Batch를 제대로 썼어도 이
계층은 따로 만들어야 했다.**

**⑤ 기획 정본을 어기지 않는다.** spec은 학습 가치(한국 백엔드 채용시장 스택)를 기술 선택
기준으로 명시하지만 **Spring Batch를 채택 기술로 지정하지 않았다** — `docs/adr/README.md`가
열거하는 「기획 정본에 근거와 함께 기록된 채택 기술」 목록에 없다. 따라서 이것은 ADR 층위
결정이다. **꽂히지 않은 의존성은 그 축에서 값이 0이고**, 실질적 배치 경험(스케줄링·종목 단위
격리·task 단위 진행률·관리자 재실행·셧다운 처리)은 이미 만들어져 그대로 남는다.

## 규칙

- **MUST NOT: 「안 꽂혀 있다」를 그대로 되돌리지 않는다.** 같은 전제에서 「그러니 꽂자」도
  나온다. 이 결정이 그쪽을 안 고른 이유는 **청크 모델이 쿼터에 막혀 있어 꽂아도 얻는 것이
  좁기 때문**이다. Tasklet `Step`으로 감싸는 길은 그 논거가 닿지 않아 열려 있으나(§버린 대안),
  그때는 **재개 정밀도라는 실제 결함을 고치는 것이 목적이어야 한다.**
- **MUST: 재개를 말할 때 무엇이 그것을 주는지 정확히 쓴다.** 주는 것은 **잡별 대상 선정
  쿼리와 멱등 upsert**다. `batch_execution_phase`는 진행률 투영이다. 이 둘을 섞어 적으면
  다음 세션이 없는 체크포인트를 있다고 믿는다.
- **MUST NOT: `batch_execution`·`batch_execution_phase`·`batch_execution_failure`를 걷지
  않는다.** 이번에 지운 것은 **안 쓰이던 쪽**이고, 이 셋은 관리자 화면과 부분 재실행의 정본이다.
- **MUST NOT: V31이 `batch_execution_id_seq`·`batch_execution_failure_id_seq`를 건드리지
  않는다.** 이름이 `batch`로 시작하지만 **살아남는 테이블의** identity 시퀀스다.

## 검증

프로덕션·테스트 어디에도 Spring Batch를 부르는 코드가 없어야 한다 — **아무것도 반환하지
않아야 한다.** 선언과 import만 본다. 산문은 일부러 안 잡는다 — ADR-0007의 쿼터 논거와
`StockTaskExecutor`의 javadoc이 *「Spring Batch의 skip을 왜 안 쓰는가」*를 계속 설명해야 하고,
V6 마이그레이션도 자기가 무엇이었는지 말해야 한다:

```bash
rg -n "^\s*(implementation|testImplementation).*spring-batch|^import org\.springframework\.batch" \
  build.gradle src/
```

```premise Spring Batch 메타데이터가 DB에 남아 있지 않다
psql -d stock_screener -At -c "WITH t AS (SELECT count(*) n FROM pg_tables WHERE schemaname='public' AND (tablename LIKE 'batch_job%' OR tablename LIKE 'batch_step%')), q AS (SELECT count(*) n FROM pg_sequences WHERE schemaname='public' AND (sequencename LIKE 'batch_job%' OR sequencename LIKE 'batch_step%')), keep AS (SELECT count(*) n FROM pg_sequences WHERE schemaname='public' AND sequencename IN ('batch_execution_id_seq','batch_execution_failure_id_seq')) SELECT CASE WHEN (SELECT n FROM keep) < 2 THEN '실행 이력의 identity 시퀀스가 ' || (SELECT n FROM keep) || '개다(2여야 한다) — V31이 이름만 보고 살아남을 것까지 지웠다. batch_execution INSERT가 죽는다' WHEN (SELECT n FROM t) > 0 OR (SELECT n FROM q) > 0 THEN 'Spring Batch 메타가 남아 있다: 테이블 ' || (SELECT n FROM t) || ' / 시퀀스 ' || (SELECT n FROM q) || ' — V31이 적용되지 않았다(앱을 띄우면 Flyway가 돌린다)' ELSE 'OK 메타 0 / 실행 이력 시퀀스 ' || (SELECT n FROM keep) || '개 보존' END"
```

재개가 여전히 멱등한지는 `InitialLoadResumeTest`가 잡는다:

```bash
./gradlew test --tests "*InitialLoadResumeTest*"
```

## 버린 대안

- **Tasklet `Step`으로 제대로 꽂는다** — ADR-0007의 쿼터 논거는 `faultTolerant().skip()`
  하나만 죽이고 Tasklet에는 닿지 않는다. 잡을 `Job` + Tasklet `Step` 여럿으로 감싸면 단계
  경계마다 `ExecutionContext` 체크포인트가 생기고, 지금 없는 **재개 정밀도**를 실제로 메운다.
  채용시장 축에서도 「썼다」가 참이 된다. **버린 이유는 그 결함이 Spring Batch를 필요로 하지
  않기 때문**이다 — `InitialLoadJob`의 재개 구멍은 대상 선정 쿼리가 `price_daily`만 보는
  것이고, 그 쿼리를 고치는 것이 훨씬 싸다. 사용자가 이 안을 함께 놓고 「걷는다」를 골랐다.
- **걷되 스키마는 두고 온다** — 0행 테이블 여섯의 유지비가 0이고 되돌리기가 한 줄이다.
  버린 이유는 **다음 세션이 운영 DB에서 그 테이블을 보고 무엇인지 다시 추적하게 되기 때문**
  이다. `tmp_` 테이블 하나를 그렇게 남겨 두었다가 같은 비용을 치른 전례가 있다.
- **그대로 둔다** — 의존성 하나와 빈 테이블 여섯의 비용이 작고 증상도 없다. 버린 이유는
  **증상이 없다는 것이 이 항목이 반복해서 판단 대기 목록에 오른 이유**이기 때문이다. 걷지
  않으면 다음 세션이 같은 것을 다시 발견하고 다시 묻는다.
- **인메모리 `JobRepository`로 되돌린다** — ADR-0005가 이미 버렸다. `JobRepository` 빈 정의를
  지우면 아무것도 기록되지 않는데 증상이 없다는 것이 그 근거였다. **이번에는 기록할 것 자체가
  없으므로 그 위험이 성립하지 않는다** — 그래서 되돌리는 대신 걷는다.
