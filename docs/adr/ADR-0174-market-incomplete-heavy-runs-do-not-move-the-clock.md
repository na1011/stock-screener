---
id: ADR-0174
status: 제안됨
date: 2026-10-07
applies_to:
  - "src/main/java/**/batch/MarketCompleteness.java"
  - "src/main/java/**/batch/RetirementEvidence.java"
  - "src/main/java/**/datasource/http/RefetchWaits.java"
  - "src/main/java/**/batch/BatchExecutionRecorder.java"
  - "src/main/java/**/batch/BatchExecutionContext.java"
  - "src/main/java/**/batch/StockTaskExecutor.java"
  - "src/main/java/**/batch/JobFreshnessPolicy.java"
  - "src/main/java/**/batch/JobFreshnessAlerter.java"
  - "src/main/java/**/datasource/DataSourceException.java"
  - "src/main/java/**/datasource/kr/DartResponse.java"
  - "src/main/java/**/datasource/kr/DartCorpCodeCache.java"
  - "src/main/java/**/datasource/us/SecTickerCikCache.java"
  - "src/main/java/**/datasource/us/AlpacaResponse.java"
  - "src/main/java/**/datasource/us/SecEdgarResponse.java"
  - "src/main/java/**/datasource/us/SecFilingSupplement.java"
  - "src/main/java/**/pipeline/scheduler/PipelineScheduler.java"
  - "src/main/resources/db/migration/V65__batch_execution_scope_reason.sql"
---

# ADR-0174. 시장을 못 끝낸 무거운 잡 실행은 신선도 시계에 세지 않는다

## 결정

무거운 잡(FR · FSR · G)은 마감 때 **시장마다** 끝까지 마쳤는지 판정한다(`MarketCompleteness`). 하나라도 미완료면 그 실행을 시계에서 뺀다.

- **잡별 시장 집합**: FR = KOSPI·KOSDAQ·US · FSR = KOSPI·KOSDAQ · G = KOSPI·KOSDAQ·US.
  - FSR에 US가 없는 것은 진짜 한계다. 미국 전체 재무제표 소스(`FullStatementDataSource` 구현)가 없다.
- **시장 m이 미완료** = 아래 넷 중 하나.
  - (가) 시작 때 m의 소스가 등록돼 있지 않다.
  - (나) m에 실패가 있고 성공이 0이다.
  - (다) m의 실패 중 **소스의 답을 못 받은 것**이 하나라도 있다.
  - (라) m의 종목은 성공했지만 미국 공시 보충이 **받지 못한 것**을 신고했다(받기 실패 · 목록 실패 · 옛 판으로 메운 재수신 실패).
- **「답함」 분류는 `MarketCompleteness.sourceAnswered(종류, 표지)` 하나다.** 기본은 「답 못 받음」이다.
  - 답함 = `NOT_FOUND`·`MALFORMED`이면서 던지는 자리가 `DataSourceException.answered(...)`로 표지를 달고, **그 종목에 상장폐지 증거가 있을 때**뿐이다.
  - 상장폐지 증거(`RetirementEvidence`) = 실행 시작 때 현재 활성 편입이 아님 ∨ 그 시장의 최근 5거래일에 봉이 없음. 잡이 시작 때 대상 전체로 한 번 읽는다.
    - 현재 편입이고 봉이 오는 종목의 매핑 부재는 신규 상장의 등록 지연 꼴이라 「다시 물어도 같은 답」이 안 선다 — 답 못 받음이다.
  - 한도·일시·인증·`UNEXPECTED`는 표지와 무관하게 답 못 받음이다. 표지 없는 `NOT_FOUND`·`MALFORMED`도 답 못 받음이다.
  - 재료는 실행 안의 시장별 계수다(`StockTaskExecutor.Outcome`이 종류와 표지를 싣는다). 마감 때 실패 행을 SQL로 다시 읽지 않는다.
- **표지를 다는 자리는 둘이다 — 신선한 매핑 판의 부재.**
  - SEC: `SecEdgarFinancialAdapter`의 CIK 미매핑. 상장폐지 종목은 그 파일에서 빠진다.
  - DART: 재무(`DartFinancialAdapter`)·전체 재무제표(`DartFullStatementAdapter`)의 고유번호 미매핑. 거버넌스·기업행위·개황은 신선한 부재를 지금처럼 빈 결과로 낸다.
  - **신선 = 판의 나이 < 24시간 ∧ 그 판 뒤 다시 받기 실패가 없음** — 한 정의다(`Snapshot.fresh`).
  - 그 밖 `NOT_FOUND`·`MALFORMED`는 표지가 없다. DART `021`·`100`(우리 요청 오류) · status 없음 · SEC 「facts 키 없음」 · Alpaca 봉투 이상이 여기다.
- **매핑 캐시(SEC·DART 같은 구조)**:
  - 24시간이 지나면 다시 받는다. DART 하한은 1,999(2026-10-05·10-06 앱 로그 「상장사 3998건」의 절반), SEC는 5,195다.
  - 받기 실패·하한 미달은 옛 판을 **낡은 판**으로 지킨다. 낡은 판에 없는 종목은 `TRANSIENT`다(「매핑 갱신 실패 판에서 미매핑」).
  - 실패하면 2초 · 10초를 기다리며 두 번 다시 받고(`RefetchWaits` — `SecFilingClient.Backoff`와 같은 값), 그래도 실패면 10분 기억한다(첫 적재 포함). 그동안 종목 조회는 콜 0이다.
  - SEC 매핑 파일도 본문을 문자열로 받아 상태를 먼저 본다(`SecEdgarResponse.requireJson`). JSON으로 받으면 403·429 HTML이 TRANSIENT가 되어 한도의 「다시 묻지 않음」을 비켜 간다.
  - 종목 조회 경로에서 판을 못 얻으면 `AUTH`·`RATE_LIMITED` 말고는 `TRANSIENT`로 감싼다. 명부의 `freshSnapshot`은 원래 종류다.
  - DART 다운로드에 일일 한도 가드를 둔다.
- **전송·본문 분류**:
  - DART 호출부 다섯은 본문을 바이트로 받아 HTTP 상태를 먼저 보고, 2xx면 응답이 선언한 charset으로, 선언이 없으면 Jackson 자동 감지(UTF 계열 · BOM)로 판다(`DartResponse.read`).
    - UTF-8로 못박으면 BOM 본문은 파싱이 터지고, `charset=EUC-KR` 선언은 U+FFFD로 깨져 계정명이 조용히 안 맞는다.
    - status 000인데 `list`가 배열이 아니면 MALFORMED(표지 없음)다. 빈 결과로 읽으면 거버넌스가 「표 없음」(noData) 행을 틀린 사실로 남긴다.
    - 429 → `RATE_LIMITED`. 그 밖 비2xx(404 포함) → `TRANSIENT`. HTTP 404를 `NOT_FOUND`로 매기지 않는다.
    - 2xx인데 본문 없음·JSON 아님 → `TRANSIENT`.
    - 어댑터의 「데이터 없음」 삼킴은 JSON `status 013`·`014`(`DartResponse.NoData`)일 때만이다.
  - Alpaca·SEC: 2xx인데 본문 없음·JSON 아님 → `TRANSIENT`. 408 → `TRANSIENT`. 파싱된 JSON의 모양 이상만 `MALFORMED`다.
  - SEC 받기에서 다시 받아도 덜 온 2xx → `TRANSIENT`.
- **기록(V65)**: `finish`의 같은 UPDATE에서 `FULL`로 시작한 행을 `NARROW`·`scope_reason = MARKET_INCOMPLETE`로 내리고 `incomplete_markets`를 적는다.
  - 한 방향이다. 시작부터 `NARROW`인 행은 사유를 안 바꾸고 `incomplete_markets`만 적는다.
  - status · 성공·실패 수 · note 앞머리는 그대로다. note 꼬리에 사람 말(「시장 미완료 KOSPI 일시 3」 · 「US 보충 일시 1」)을 붙인다.
  - 대상 0건으로 곧장 마감하는 갈래도 같은 판정을 지난다.
- **사유(`scope_reason`)는 잡이 명시적으로 넘긴다**(`DeclaredScope`). 값은 ARGS · RERUN · GAP_FILL · PAST_DATE · NO_SCHEDULED_RANGE · MARKET_INCOMPLETE다.
  - 시작 때 우선순위는 GAP_FILL > PAST_DATE > RERUN > 잡 선언이다. `startRetry`도 기준일을 본다.
  - `corporate-action-judge`는 NO_SCHEDULED_RANGE다.
- **알림**: 정기 범위(`FULL`)로 시작한 실행이 미완료면 `WARNING` 한 통을 보낸다. 키는 `dedupeKey() + ":incomplete"`다.
  - 시작부터 좁힌 실행(꼬리 · `--tickers` 손 실행)에는 안 보낸다. 기록(`incomplete_markets`)은 남긴다.
  - 신선도 알림·배너는 정기 시도가 있으면 「정기 n번 시도 · (시장) 미완료」를 붙인다.
  - 미완료 시장이 있으면 손 실행보다 원인 확인을 먼저 권한다.
- **MALFORMED 다발 경보**: 실패 기록 자리(`BatchExecutionContext.process`)가 MALFORMED 종목 실패를 센다.
  - 표지 단 실패는 안 센다. 처리 수가 20 미만인 실행은 비율 경보를 내지 않는다.
  - 그전에는 `countMalformed()` 호출자가 0이라 이 경보가 한 번도 못 났다.
- **연체 보정 순서**: 연체가 둘 이상이면 아래 순서로 고른다.
  - 키는 그 잡의 마지막 SCHEDULER 행 `started_at`이다(상태·범위 무관). 없으면 가장 오래된 것으로 친다.
  - 키가 같으면 마지막 성공에서 오래된 것부터 고른다.
  - 잡이 실행 행을 만들기 전에 던지면 스케줄러가 FAILED 시도 행을 남긴다(「시작 실패」).
- **판 8**: 위 분류가 SEC 공시 보충 판 범위 파일(`SecEdgarResponse` · `SecFilingClient` · `SecFilingSupplement`)과 그 폐포(`DataSourceException`)를 바꾸므로 `READER_VERSION`을 7에서 8로 올렸다.

## 규칙

- MUST: 「답함」 판정은 `MarketCompleteness.sourceAnswered` 한 곳에 둔다. 시계와 표적 재실행(2부)이 같은 함수를 쓴다.
- MUST: 표지의 기본은 「답 못 받음」이다. `DataSourceException.answered`는 `NOT_FOUND`·`MALFORMED`만 받는다.
- MUST NOT: 「답함」을 종류만으로 정하지 않는다. 같은 종류가 결정적 답일 수도, 끊긴 전송·우리 요청 오류일 수도 있다.
- MUST NOT: 새 자리에 표지를 달 때 「종류가 NOT_FOUND라서」를 근거로 쓰지 않는다. 그 자리의 답이 결정적인지 소스에 물어 잰다.
- MUST NOT: 낡은 매핑 판의 부재를 「모른다」(빈 값)로 내지 않는다. 그것은 소스의 답이 아니다.
- MUST NOT: DART HTTP 비2xx를 `NOT_FOUND`로 매기지 않는다. 어댑터 다섯이 `NOT_FOUND`를 「데이터 없음」으로 삼키던 자리라 경로 변경·점검이 전 종목 빈 성공이 된다.
- MUST NOT: DART 본문을 `toEntity(String)`으로 받지 않는다. 비JSON content-type에 charset이 없으면 ISO-8859-1로 읽혀 한글 계정명이 조용히 깨진다.
- MUST NOT: DART 본문을 UTF-8로 못박지 않는다. 선언 charset이 있으면 그것으로, 없으면 바이트째 Jackson에 넘긴다(BOM · 자동 감지).
- MUST NOT: 매핑 캐시의 재시도를 대기 없이 하지 않는다. 대기 없는 시도는 0.4초 안에 끝나 그보다 긴 끊김을 못 넘고, 실패는 10분 기억된다.
- MUST: 표지 단 실패를 「답함」으로 세기 전에 상장폐지 증거를 본다. 신선한 매핑의 부재만으로는 신규 상장 등록 지연과 갈리지 않는다.
- MUST: 시장 미완료는 `finish`의 **같은 UPDATE**에서 적는다. 상태와 범위 사이에 틈이 있으면 그 사이의 신선도 판정이 미완료 실행을 센다.
- MUST NOT: `NARROW`를 `FULL`로 올리는 길을 만들지 않는다.
- MUST NOT: note 꼬리에 `낱말=` 꼴 토큰을 쓰지 않는다. `ops/apply-window/env.sh` `note_nums()`의 합산 정규식과 공시 보충 칸이 그 꼴이다.
- MUST: 미완료 알림 키를 부분 실패 알림과 가른다. 같은 키면 `AlertSuppressor`가 10분 안의 두 번째를 버린다.
- MUST NOT: `scope_reason`·`incomplete_markets`에 NOT NULL을 걸거나 이력을 UPDATE하지 않는다. 이유는 V63과 같다(옛 jar · ADR-0172 §버린 대안 1).

## 검증

```bash
TEST_DB_URL=jdbc:postgresql://localhost:5432/<전용 시험 DB> ./gradlew test --no-daemon \
  --tests "*MarketCompletenessTest" --tests "*BatchExecutionScopeTest" --tests "*FinancialRefillJobTest" \
  --tests "*PipelineSchedulerChainTest" --tests "*JobFreshnessPolicyTest" --tests "*AdminBatchApiTest" \
  --tests "*WireContractTest" --tests "*DartResponseTest" --tests "*DartCorpCodeCacheTest" \
  --tests "*SecTickerCikCacheTest" --tests "*AlpacaResponseTest" --tests "*SecFilingSupplementTest" \
  --tests "*ReaderVersionPinTest"
cd frontend && npm run test:unit
```

변이체·대조군으로 잰 것(2026-10-07):

| 변이체 | 붉은 시험 |
|---|---|
| 미완료 알림 키를 `dedupeKey()`로 | `MarketCompletenessTest.incompleteAlertIsNotSuppressedByThePartialAlert` |
| 실패 기록 자리의 `countMalformed()` 배선 제거 | `MarketCompletenessTest.malformedSurgeAlertsOnce` |
| `DartResponse.read`가 ISO-8859-1로 판독 | `DartResponseTest.koreanSurvivesNonJsonContentType` |
| `DartResponse.read`가 UTF-8 문자열로 못박아 판독(옛 판) | `DartResponseTest.bomIsTolerated` · `declaredCharsetIsHonoured` |
| 매핑 캐시 재시도 대기 0(대기 없는 재시도) | `SecTickerCikCacheTest.waitsBridgeAShortOutage` · `DartCorpCodeCacheTest.waitsBridgeAShortOutage` |

`PipelineSchedulerChainTest.replayOfTheOperationalClock`은 대조군을 안에 둔다. 운영 시계(G 09-20 16:14:41 · FSR 09-23 00:26:25 · FR 10-03 21:17:41 KST · SCHEDULER 행 0)를 재생하고, G가 매번 미완료로 끝난다고 놓는다.
- 새 키: 10-19 G · 10-20 G · 10-21 FSR · 10-22~29 G · 10-30 FR.
- 대조군(시도를 모르는 키 = 지금까지의 순서): FR이 11-05까지 밀린다.

## 배경

ADR-0172는 좁힌 **손** 실행이 시계를 미는 것을 막았다. 정기 범위로 돈 실행이 한 시장의 소스 답을 못 받고 `SUCCESS`·`PARTIAL_SUCCESS`로 끝나 시계를 미는 것은 남았다.
- 그 사례: 한도 소진 꼬리 · 시장 통째 장애 · 소스 빈 없음 · 낡은 매핑 판.
- 이것은 `docs/reload-gate.md`의 「부분 성공이 시장 통째 실패·한도 꼬리를 안고도 무거운 잡 시계를 되돌린다」 행이다.
- 무거운 잡은 월 1회라, 시계가 한 번 잘못 밀리면 그 시장이 다음 차례까지 한 달 가까이 낡는다.

## 버린 대안

1. **ADR-0172 §버린 대안 2(종목 실패율 임계)를 그대로 따른다** — 그 기각은 「수로 가르는 임계」에 섰다. 임계를 실측으로 못 고르고, 한도 꼬리가 경계 밑이면 반쯤 못 받은 실행이 FULL이 된다.
   - 이 결정의 술어는 수가 아니라 **실패 하나하나의 성질**(소스가 답했는가)이라 그 기각에 안 선다. 그래서 다시 연다.
2. **종류만으로 「답함」을 가른다(`NOT_FOUND`·`MALFORMED` = 답함)** — 같은 종류가 결정적 답일 수도, 끊긴 전송·우리 요청 오류일 수도 있다.
   - 종류로 접으면 못 받은 시장이 시계를 민다. 그래서 던지는 자리의 표지로 가르고 기본을 「답 못 받음」으로 둔다.
3. **SEC 「facts 키 없음」에 표지를 단다** — 운영 7행이 전부 한 실행(#182)이고, 다시 물은 적이 없어 결정성을 재지 않았다.
   - 같은 증거 수준인 companyfacts 404(163종목)에는 표지를 안 달므로 기준도 어긋난다. 현재 편입은 0이라 빼도 잃는 것이 없다.
4. **표지를 예외 밖 형(억제 목록)으로 운반해 판을 안 올린다** — 이 판의 분류 고침이 어차피 판 범위 파일을 바꿔 판을 올린다. 판 하나를 아끼려는 우회가 표지의 형을 묶을 이유가 없다.
5. **SEC 첫 적재 실패를 기억하지 않는다(지금까지)** — 그 사유(「기억하면 10분 동안 미국이 전원 실패」)는 그 실패가 시계를 밀던 분류 위에 섰다.
   - 이제 한 종목 `TRANSIENT`면 미국이 미완료라 시계를 안 민다. 기억하지 않으면 hang일 때 종목마다 다시 받는다(1,000 × 읽기 제한 60초).
6. **매핑 캐시 실패를 즉시(대기 없이) 한 번 다시 받고 기억한다(뼈대 다음 판)** — 0.4초 안에 끝나 그보다 긴 끊김을 못 넘는다. 첫 적재 실패까지 기억하므로 순간 끊김 하나가 새 JVM의 첫 10분 동안 그 시장 전 종목을 실패시켰다(구현 반증).
7. **신선한 매핑의 부재만으로 「답함」으로 센다(뼈대 다음 판)** — 표지의 근거는 상장폐지 종목뿐이었고, 같은 부재에 신규 상장의 등록 지연이 섞인다.
8. **매핑 캐시 실패를 「실행 단위로 한 번」 기억한다** — datasource 층에 실행 문맥이 없고(싱글턴 · 웹 JVM에서 다른 잡과 겹친다) 그 기제를 새로 만들어야 한다. 즉시 1회 재시도 + 10분 기억으로 둔다.
9. **마감 때 실패 행을 SQL로 다시 읽어 판정한다** — `resolveFailures`는 실행 id로 안 좁힌다. 그 사이 다른 실행이 해결로 표시한 행이 빠진다.
10. **기록 계층이 「선언 NARROW = ARGS」로 사유를 추론한다** — 인자가 아닌 이유로 좁은 잡(`corporate-action-judge`)이 거짓 사유를 단다.
11. **연체 순서 키를 마지막 성공으로 둔다(지금까지)** — 매번 미완료로 끝나는 잡이 늘 가장 오래 밀린 것으로 남아 다른 연체 잡의 차례를 빼앗는다(위 재생).

## 결과

- **사정거리**: 하한(실측의 절반)을 넘는 잘 짜인 부분 매핑 파일에서 빠진 종목 중 **상장폐지 증거가 있는 것**은 「답함」으로 접혀 그 시장이 FULL이 될 수 있다. 남는 신호는 그 종목의 부분 실패 알림이다. 현재 편입이고 봉이 오는 종목은 이 접힘에 안 든다. 이 밖은 하한이 잡는다.
- **판 8의 대가**: 배포 뒤 첫 FR이 보충 저장 행(판 7)을 다시 받는다. 판 범위 소스 해시가 바뀌었고, 고정 재료의 출력 해시는 판 7과 같다(분류 고침은 받기 갈래라 고정 재료가 안 지난다).
- **무거운 잡 밖으로도 닿는다.**
  - 기업행위·개황이 낡은 DART 판에서 모르는 종목코드는 이제 `TRANSIENT` 종목 실패다(부분 실패 알림). 웹 JVM의 DART 매핑은 하루 1콜이다.
  - Alpaca 8 어댑터의 200 비JSON은 `TRANSIENT`다. 분사 자식 봉(`SpinOffValuer`)이 그것을 「자식 가격 없음」으로 접지 않아, 모체 종목이 실패로 남고 다음 날 다시 묻는다.
- **시장 판정은 시장별이고 재실행은 잡 전체다.** 미국만 미완료여도 다음 차례가 국내까지 다시 돈다. (잡, 시장) 표적 재시도는 2부의 몫이다. `incomplete_markets`와 같은 분류 함수를 쓴다.
- **시험 컨텍스트에는 미국 재무 소스가 없다.** 정기 범위 FR 시험의 `incomplete_markets`에는 US가 늘 「소스 없음」으로 든다(`FinancialRefillJobTest`).
- **화면**: 실행 이력의 덧말과 툴팁이 사유마다 다르다(「시장 미완료 KOSPI, US」 · 「재실행」 · 「과거 기준일」 …). 상세 시트가 「연체 판정 · 세지 않음」을 보인다.
  - 이력 API가 `scope`·`scopeReason`·`incompleteMarkets`를 싣는다.
- **배너**: 제목이 「신선도가 갱신되지 않았습니다」로 바뀌었다. 정기 시도가 있으면 줄마다 시도 수와 미완료 시장을 붙인다.
- 되돌리려면 `BatchExecutionContext.finish`가 시장 목록을 빈 채로 넘기게 하면 된다. 컬럼은 남아도 무해하다. 분류 고침은 그것과 따로 되돌린다(판이 다시 바뀐다).
