---
id: ADR-0037
status: 채택됨
date: 2026-08-10
applies_to:
  - "src/main/java/**/pipeline/batch/**"
  - "src/main/resources/db/migration/V14__batch_execution_progress.sql"
---

# ADR-0037. 배치 진행률의 단위를 작업 단위로 두고, 단계별 집계를 별도 테이블에 남긴다

## 결정

배치의 진행률을 **작업 단위(task)** 로 잰다. 작업 단위 하나 = `BatchExecutionContext.process()`
호출 하나이며, 이는 실패 격리의 단위(종목 × 단계)와 같다. 잡이 `plan(phase, tasks)`로 계획을
신고하고, 그 합이 `batch_execution.planned_task_count`가 되어 화면의 분모가 된다.

단계별 집계는 `batch_execution_phase`에 행으로 남긴다. 진척은 마감을 기다리지 않고 3초마다
기록한다.

## 규칙

- MUST: 잡은 루프에 들어가기 전에 `execution.plan(phase, tasks)`로 **실제로 `process()`를 부를
  횟수**를 신고한다. 대상 종목 수가 아니다 — 종목 하나를 여러 단계·여러 날짜로 처리하는 잡이
  이미 셋이다.
- MUST NOT: `target_count`를 분모로 쓰지 않는다. 그 컬럼의 뜻은 **대상 종목 수**이고 그대로
  둔다. 뜻을 갈아타면 마이그레이션 이전 행이 조용히 다른 것을 의미하게 된다.
- MUST NOT: 처리 건수를 "종목"이라 부르지 않는다. 알림 문구도 마찬가지다.
- MUST NOT: 계획을 마감 시점의 실측으로 덮어쓰지 않는다. 처리 < 계획은 정상이며 **그 차이가
  곧 건너뛴 작업 수**다(일일 갱신은 시세가 실패한 종목의 지표를 건너뛴다). 덮어쓰면 그
  신호가 사라진다.
- MUST NOT: 계획을 신고하지 않은 단계를 0으로 채워 저장하지 않는다. "이 잡은 이 단계를 돌지
  않는다"와 "돌았는데 아무것도 못 받았다"가 같은 모양이 된다.
- MUST NOT: `batch_execution_phase.phase`에 CHECK 제약을 걸지 않는다. 값의 정본은
  `CollectionPhase` enum이고, 같은 사본인 `ck_failure_phase`가 V7에서 `INDICATOR` 때문에
  패치돼야 했다. 실패 기록과 달리 이 집계는 **정상 경로**에 있어, 제약이 낡으면 새 단계를 쓰는
  배치가 통째로 죽는다.
- MUST: 종단 상태 전이는 `status = 'RUNNING'`인 행에만 적용한다. 두 번 마감하면 먼저 쓴 결과가
  조용히 덮인다.
- MUST: 진척 기록의 실패는 로그로 남기되 배치를 죽이지 않는다(`DataAccessException`만 잡는다).
  화면을 위한 부수적인 쓰기가 그날의 수집·계산을 실패로 만들면 안 된다.

## 검증

계획을 신고하지 않는 잡이 생기면 화면의 분모가 `—`가 되므로, 잡마다 신고가 있는지는 코드로 본다.

```bash
# process()를 부르는 잡은 plan()도 불러야 한다. 아래 두 목록이 같아야 한다.
rg -l "execution\.process\(|countExternally\(" src/main/java/com/stockscreener/pipeline/batch
rg -l "execution\.plan\(" src/main/java/com/stockscreener/pipeline/batch
```

분자가 분모를 넘으면 계량이 다시 어긋난 것이다. 넘을 수 있는 경로는 잡이 계획보다 많이 도는
것뿐이고, 그것은 곧 `plan()`의 식이 루프와 어긋났다는 뜻이다.

```premise 배치 진행률의 분자가 분모를 넘지 않는다
psql -d stock_screener -At -c "SELECT CASE
  WHEN count(*) = 0 THEN '잴 것이 없었다 — planned_task_count가 기록된 실행이 하나도 없다'
  WHEN count(*) FILTER (WHERE success_count + failure_count > planned_task_count) = 0
    THEN 'OK ' || count(*) || '건 모두 처리 <= 계획'
  ELSE count(*) FILTER (WHERE success_count + failure_count > planned_task_count)
       || '건이 계획보다 많이 처리됐다 — plan()의 식이 루프와 어긋났다'
  END FROM batch_execution WHERE planned_task_count IS NOT NULL"
```

## 배경

관리자 화면의 "처리" 칸이 지표 백필을 `43400/350`으로 그렸다. 분모 `target_count`는 잡이 넘긴
**대상 종목 수 350**이었고, 분자 `success_count`는 `process()` 성공 **횟수**였다. 백필의 루프는
`for(거래일) for(종목)`이라 350종목 × 124거래일 = 43,400이 됐다. 값은 둘 다 정확했고 **단위만
달랐다.** 같은 어긋남이 일일 갱신(`700/350`)과 초기 적재에도 있었고, 부분 실패 알림은 350종목
유니버스에서 "84716종목 실패"라고 나갔다.

두 번째로, 카운터가 마감할 때 한 번만 기록되어 56분짜리 백필이 내내 `0/350`으로 보였다. 그
화면으로는 "돌고 있다"와 "멎었다"를 구분할 수 없다.

세 번째로, `BatchExecutionContext`가 단계별 저장 행 수를 **메모리에서만** 세고 DB에 남기지
않았다. 그 카운터가 존재하는 이유 자체가 실제 사고다 — 시세 0행인 날 지표 단계가 979,541행을
써서 합계로는 성공으로 보였고, 금요일 종가 결손이 아무에게도 알려지지 않았다. 합계로는 판정할
수 없는 것을 위해 만든 값인데 정작 화면에는 합계만 있었다.

## 버린 대안

- **`target_count`의 뜻을 작업 단위로 바꾼다.** 컬럼이 하나로 끝나 단순하지만, V5 주석이 그
  뜻을 "대상 종목 수"로 못박았고 실데이터가 그 뜻으로 들어 있다. 뜻을 갈아타면 과거 행이
  아무 흔적 없이 다른 것을 의미하게 되고, 그것이 이 결정이 없애려던 종류의 혼동이다.
- **분자를 종목 단위로 줄인다.** 분모를 그대로 두는 대신 성공을 종목별로 중복 제거하는 안.
  버린 이유는 한 종목이 시세는 성공하고 재무만 실패할 수 있어 "그 종목이 성공했는가"가
  단일한 답을 갖지 않기 때문이다. 실패 격리의 단위가 종목 × 단계인 것과 같은 이유다.
- **마감 시 계획을 실측으로 덮어쓴다.** 끝난 실행이 항상 100%가 되어 보기에는 깔끔하지만,
  건너뛴 작업이 몇 개였는지가 사라진다. 일일 갱신에서 그 차이가 곧 시세 실패로 지표를 건너뛴
  종목 수다.
- **단계를 컬럼으로 편다**(`price_written_rows`, `indicator_written_rows`, …). 조인이 없어 읽기는
  싸지만 단계를 하나 더할 때마다 마이그레이션이 필요하다. 지표 저장을 스키마 변경 없이 늘리기로
  한 것과 같은 판단으로 행으로 둔다.
- **Spring Batch의 `StepExecution.readCount`/`writeCount`를 쓴다.** V5가 이미 기각했다 — 도메인
  의미가 없어 "시세 몇 행, 재무 몇 건"을 구분하지 못한다. 게다가 이 잡들은 `Job`/`Step`을 쓰지
  않아 `BATCH_STEP_EXECUTION`이 0행이다. **(2026-08-20 — 여기 적힌 0행이 3년 뒤가 아니라 열흘
  뒤에 Spring Batch를 걷는 근거가 됐다. 그 테이블은 이제 없다 — ADR-0075.)**

## 결과

- 잡을 하나 더할 때 `plan()` 한 줄이 늘어난다. 빠뜨리면 화면의 분모가 `—`로 뜨고, 그것은
  틀린 숫자가 아니라 없는 숫자라 오해를 만들지 않는다.
- 관리자 화면이 잡 이름을 몰라도 진행률·단계별 집계를 그릴 수 있다. 서버가 신고한 것만
  그리므로 잡이 늘어도 화면을 고치지 않는다.
- 진척 기록이 3초마다 `UPDATE` 한 번 + 단계 수만큼 `UPSERT`를 더한다. 개인용 규모에서 무시할
  수 있고, 늘리려면 `BatchExecutionContext.FLUSH_INTERVAL_NANOS` 하나만 만진다.
- 되돌리려면 V14를 되돌리는 마이그레이션과 `plan()`·`stage()` 호출 제거가 필요하다. 다만
  `43400/350`이 다시 뜬다.
