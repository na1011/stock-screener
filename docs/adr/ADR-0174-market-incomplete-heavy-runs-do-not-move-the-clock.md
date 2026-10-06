---
id: ADR-0174
status: 제안됨
date: 2026-10-07
applies_to:
  - "src/main/java/**/batch/MarketCompleteness.java"
  - "src/main/java/**/batch/BatchExecutionRecorder.java"
  - "src/main/java/**/batch/BatchExecutionContext.java"
  - "src/main/java/**/batch/StockTaskExecutor.java"
  - "src/main/java/**/batch/JobFreshnessPolicy.java"
  - "src/main/java/**/batch/JobFreshnessAlerter.java"
  - "src/main/java/**/datasource/SourceAnswer.java"
  - "src/main/java/**/pipeline/scheduler/PipelineScheduler.java"
  - "src/main/resources/db/migration/V65__batch_execution_scope_reason.sql"
---

# ADR-0174. 시장을 못 끝낸 무거운 잡 실행은 신선도 시계에 세지 않는다

## 결정

무거운 잡(FR · FSR · G)은 마감 때 **시장마다** 끝까지 마쳤는지 판정한다(`MarketCompleteness`). 하나라도 미완료면 그 실행을 시계에서 뺀다.

- **잡별 시장 집합**: FR = KOSPI·KOSDAQ·US · FSR = KOSPI·KOSDAQ · G = KOSPI·KOSDAQ·US.
  - FSR에 US가 없는 것은 진짜 한계다. 미국 전체 재무제표 소스(`FullStatementDataSource` 구현)가 없다.
- **시장 m이 미완료** = 아래 셋 중 하나.
  - (가) 시작 때 m의 소스가 등록돼 있지 않다.
  - (나) m에 실패가 있고 성공이 0이다.
  - (다) m의 실패 중 **소스의 답을 못 받은 것**이 하나라도 있다.
- **「답함」 분류는 `MarketCompleteness.sourceAnswered(kind, 표지)` 하나다.**
  - 답함 = `NOT_FOUND`·`MALFORMED`이면서 던지는 자리가 `SourceAnswer.answered(...)`·`mark(...)`로 표지를 단 것뿐이다.
  - 그 밖은 전부 「답 못 받음」이다. 한도·일시·인증·`UNEXPECTED`는 표지와 무관하게, 표지 없는 `NOT_FOUND`·`MALFORMED`도.
  - 재료는 실행 안의 시장별 계수다(`StockTaskExecutor.Outcome`이 종류와 표지를 싣는다). 마감 때 실패 행을 SQL로 다시 읽지 않는다.
- **기록(V65)**: `finish`의 같은 UPDATE에서 `FULL`로 시작한 행을 `NARROW`·`scope_reason = MARKET_INCOMPLETE`로 내리고 `incomplete_markets`를 적는다.
  - 한 방향이다. 시작부터 `NARROW`인 행은 사유를 안 바꾸고 `incomplete_markets`만 적는다.
  - status · 성공·실패 수 · note 앞머리는 그대로다. note 꼬리에 사람 말(「시장 미완료 KOSPI 일시 3」)을 붙인다.
  - 대상 0건으로 곧장 마감하는 갈래도 같은 판정을 지난다.
- **사유(`scope_reason`)는 잡이 명시적으로 넘긴다**(`DeclaredScope`). 값은 ARGS · RERUN · GAP_FILL · PAST_DATE · NO_SCHEDULED_RANGE · MARKET_INCOMPLETE다.
  - 시작 때 우선순위는 GAP_FILL > PAST_DATE > RERUN > 잡 선언이다. `startRetry`도 기준일을 본다.
  - `corporate-action-judge`는 NO_SCHEDULED_RANGE다.
- **알림**: 미완료면 `WARNING` 한 통을 보낸다. 키는 `dedupeKey() + ":incomplete"`다.
  - 신선도 알림·배너는 정기 시도가 있으면 「정기 n번 시도 · (시장) 미완료」를 붙인다.
  - 미완료 시장이 있으면 손 실행보다 원인 확인을 먼저 권한다.
- **연체 보정 순서**: 연체가 둘 이상이면 아래 순서로 고른다.
  - 키는 그 잡의 마지막 SCHEDULER 행 `started_at`이다(상태·범위 무관). 없으면 가장 오래된 것으로 친다.
  - 키가 같으면 마지막 성공에서 오래된 것부터 고른다.
  - 잡이 실행 행을 만들기 전에 던지면 스케줄러가 FAILED 시도 행을 남긴다(「시작 실패」).
- **곁가지(같은 판)**: `BatchExecutionContext`가 MALFORMED 종목 실패를 스스로 센다.
  - 그전에는 `countMalformed()` 호출자가 0이라 「응답 해석 실패 다발」 알림이 한 번도 못 났다.

## 규칙

- MUST: 「답함」 판정은 `MarketCompleteness.sourceAnswered` 한 곳에 둔다. 시계와 표적 재실행(2부)이 같은 함수를 쓴다.
- MUST: 표지의 기본은 「답 못 받음」이다. `SourceAnswer`는 `NOT_FOUND`·`MALFORMED`에만 표지를 단다.
- MUST NOT: 표지를 `DataSourceException`의 필드·하위형으로 옮기지 않는다. 그 클래스는 SEC 공시 보충 판 고정 범위의 폐포에 들어 있다(범위 소스가 생성자를 부른다).
  - 필드 하나만 더해도 `ReaderVersionPinTest`의 소스 해시가 바뀐다(2026-10-07 실측). 그래서 표지는 예외의 억제 목록에 붙는 별도 형이다.
- MUST NOT: 「답함」을 종류만으로 정하지 않는다. 같은 종류가 결정적 답일 수도, 끊긴 전송·우리 인자 오류일 수도 있다.
- MUST: 시장 미완료는 `finish`의 **같은 UPDATE**에서 적는다. 상태와 범위 사이에 틈이 있으면 그 사이의 신선도 판정이 미완료 실행을 센다.
- MUST NOT: `NARROW`를 `FULL`로 올리는 길을 만들지 않는다.
- MUST NOT: note 꼬리에 `낱말=` 꼴 토큰을 쓰지 않는다. `07-judge.sh`의 합산 정규식과 공시 보충 칸이 그 꼴이다.
- MUST: 미완료 알림 키를 부분 실패 알림과 가른다. 같은 키면 `AlertSuppressor`가 10분 안의 두 번째를 버린다.
- MUST NOT: `scope_reason`·`incomplete_markets`에 NOT NULL을 걸거나 이력을 UPDATE하지 않는다. 이유는 V63과 같다(옛 jar · ADR-0172 §버린 대안 1).

## 검증

```bash
TEST_DB_URL=jdbc:postgresql://localhost:5432/<전용 시험 DB> ./gradlew test --no-daemon \
  --tests "*MarketCompletenessTest" --tests "*BatchExecutionScopeTest" --tests "*FinancialRefillJobTest" \
  --tests "*PipelineSchedulerChainTest" --tests "*JobFreshnessPolicyTest" --tests "*AdminBatchApiTest" \
  --tests "*WireContractTest"
cd frontend && npm run test:unit
```

변이체로 잰 것(2026-10-07):

| 변이체 | 붉은 시험 |
|---|---|
| 미완료 알림 키를 `dedupeKey()`로 | `MarketCompletenessTest.incompleteAlertIsNotSuppressedByThePartialAlert` |
| 실패 기록 자리의 `countMalformed()` 배선 제거 | `MarketCompletenessTest.malformedSurgeAlertsOnce` |

`PipelineSchedulerChainTest.replayOfTheOperationalClock`은 대조군을 안에 둔다. 운영 시계(G 09-20 16:14:41 · FSR 09-23 00:26:25 · FR 10-03 21:17:41 KST · SCHEDULER 행 0)를 재생하고, G가 매번 미완료로 끝난다고 놓는다.
- 새 키: 10-19 G · 10-20 G · 10-21 FSR · 10-22~29 G · 10-30 FR.
- 대조군(시도를 모르는 키 = 지금까지의 순서): FR이 11-05까지 밀린다.

## 배경

ADR-0172는 좁힌 **손** 실행이 시계를 미는 것을 막았다. 정기 범위로 돈 실행이 한 시장의 소스 답을 못 받고 `SUCCESS`·`PARTIAL_SUCCESS`로 끝나 시계를 미는 것은 남았다.
- 그 사례: 한도 소진 꼬리 · 시장 통째 장애 · 소스 빈 없음.
- 이것은 `docs/reload-gate.md`의 「부분 성공이 시장 통째 실패·한도 꼬리를 안고도 무거운 잡 시계를 되돌린다」 행이다.
- 무거운 잡은 월 1회라, 시계가 한 번 잘못 밀리면 그 시장이 다음 차례까지 한 달 가까이 낡는다.

## 버린 대안

1. **ADR-0172 §버린 대안 2(종목 실패율 임계)를 그대로 따른다** — 그 기각은 「수로 가르는 임계」에 섰다. 임계를 실측으로 못 고르고, 한도 꼬리가 경계 밑이면 반쯤 못 받은 실행이 FULL이 된다.
   - 이 결정의 술어는 수가 아니라 **실패 하나하나의 성질**(소스가 답했는가)이라 그 기각에 안 선다. 그래서 다시 연다.
2. **종류만으로 「답함」을 가른다(`NOT_FOUND`·`MALFORMED` = 답함)** — 같은 종류가 결정적 답일 수도, 끊긴 전송·우리 인자 오류일 수도 있다(시계 고침 v7 계획 안 반증).
   - 종류로 접으면 못 받은 시장이 시계를 민다. 그래서 던지는 자리의 표지로 가르고 기본을 「답 못 받음」으로 둔다.
3. **마감 때 실패 행을 SQL로 다시 읽어 판정한다** — `resolveFailures`는 실행 id로 안 좁힌다. 그 사이 다른 실행이 해결로 표시한 행이 빠진다.
4. **기록 계층이 「선언 NARROW = ARGS」로 사유를 추론한다** — 인자가 아닌 이유로 좁은 잡(`corporate-action-judge`)이 거짓 사유를 단다.
5. **연체 순서 키를 마지막 성공으로 둔다(지금까지)** — 매번 미완료로 끝나는 잡이 늘 가장 오래 밀린 것으로 남아 다른 연체 잡의 차례를 빼앗는다(위 재생).

## 결과

- **이 판은 뼈대다.** 어느 던지는 자리에 「답함」 표지를 달지(사이트별 판정표)는 다음 판의 몫이다. 어댑터 분류 고침(5xx·빈 본문 → `TRANSIENT` · 낡은 매핑)과 미국 공시 보충 층의 미완료(`UNREADABLE`이 아닌 보충 실패 · 목록 실패 · staleReused)도 다음 판이다.
  - **그 전까지는 모든 `NOT_FOUND`·`MALFORMED`가 「답 못 받음」이다.** 운영 이력(2026-10-07 SELECT · 최근 60일)에서 FR·FSR·G의 SUCCESS·PARTIAL_SUCCESS 148실행 중 26실행이 그 종류의 실패를 가졌다.
  - 그런 실행은 이 판에서 `MARKET_INCOMPLETE`로 내려간다. 이 판만 배포하면 그런 정기 실행이 시계를 못 밀고, 그 잡이 연체로 남아 다음 평일에 다시 돈다. **배포는 표지 판정표와 함께다.**
- **시장 판정은 시장별이고 재실행은 잡 전체다.** 미국만 미완료여도 다음 차례가 국내까지 다시 돈다. (잡, 시장) 표적 재시도는 2부의 몫이다. `incomplete_markets`와 같은 분류 함수를 쓴다.
- **시험 컨텍스트에는 미국 재무 소스가 없다.** 정기 범위 FR 시험의 `incomplete_markets`에는 US가 늘 「소스 없음」으로 든다(`FinancialRefillJobTest`).
- **화면**: 실행 이력의 덧말과 툴팁이 사유마다 다르다(「시장 미완료 KOSPI, US」 · 「재실행」 · 「과거 기준일」 …). 상세 시트가 「연체 판정 · 세지 않음」을 보인다.
  - 이력 API가 `scope`·`scopeReason`·`incompleteMarkets`를 싣는다.
- **배너**: 제목이 「신선도가 갱신되지 않았습니다」로 바뀌었다. 정기 시도가 있으면 줄마다 시도 수와 미완료 시장을 붙인다.
- 되돌리려면 `BatchExecutionContext.finish`가 시장 목록을 빈 채로 넘기게 하면 된다. 컬럼은 남아도 무해하다.
