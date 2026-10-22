---
id: ADR-0176
status: 채택됨
date: 2026-10-13
applies_to:
  - "src/main/resources/db/migration/V67__filing_listing_ledger.sql"
  - "src/main/java/**/datasource/FilingListSource.java"
  - "src/main/java/**/datasource/us/SecDailyIndexClient.java"
  - "src/main/java/**/datasource/us/SecFilingListSource.java"
  - "src/main/java/**/datasource/kr/DartFilingListClient.java"
  - "src/main/java/**/datasource/kr/DartFilingListSource.java"
  - "src/main/java/**/datasource/kr/DartReportKey.java"
  - "src/main/java/**/collector/FilingLedgerStore.java"
  - "src/main/java/**/batch/FilingListingJob.java"
  - "src/main/java/**/datasource/kr/DartDailyQuota.java"
  - "src/main/java/**/datasource/kr/DartResponse.java"
  - "src/main/java/**/datasource/http/DataSourceHttpConfig.java"
  - "src/main/resources/db/migration/V68__filing_consumption_ground.sql"
  - "src/main/java/**/batch/FilingConsumptionJob.java"
  - "src/main/java/**/datasource/us/SecFilingConsumption.java"
  - "src/main/java/**/datasource/FilingTrace.java"
---

# ADR-0176. 원장의 정기 공시 목록을 매 평일 받아 공시마다 소비 장부에 쌓는다

## 결정

평일 14:16 크론이 원장의 그날 정기 공시 목록을 받는다 — 미국은 EDGAR 일일 색인(`daily-index`), 국내는 DART 공시검색(`list.json`).
우리 종목에 붙는 공시마다 `filing_consumption`에 한 행을 쌓고, 시장마다 「끝까지 받아 닫은 날」을 `filing_listing_day`에 남겨 커서로 쓴다.
함께 DART 리미터의 일일 총량을 **URL 경로마다** 센다(분당 600은 키 합 그대로).

**2차(소비 · `FilingConsumptionJob`)**: 같은 크론이 목록 뒤에 열린 장부 행을 **지금 활성 편입** 종목의 좁힌 FR(국내는 FSR까지)로
먹이고, **그 종목을 쓴 트랜잭션 안에서** 소비됨을 표시한다. 근거는 그 FR·FSR이 받은 응답이고(`FilingTrace`) 행마다 `fr_ground`로 남긴다(V68).

## 규칙

- MUST: 한 날을 닫는 것은 그날 목록을 끝까지 받은 뒤뿐이다. 받다 실패하면 그날과 그 뒤 날을 닫지 않는다 — 커서는 닫은 마지막 날이고,
  닫지 않은 날을 건너뛰면 그날 공시가 장부에 영영 없다. 하루치 장부 행과 그날을 닫는 행은 한 트랜잭션이다(`FilingLedgerStore.recordDay`).
- MUST: 미국의 「색인 없는 날」은 분기 목록(`index.json`)이 정한다 — 목록의 마지막 날보다 앞인데 목록에 없는 날만 닫고 뒤는 기다린다.
  MUST NOT: 날짜마다 폼 색인을 물어 403을 「없는 날」로 읽지 않는다(없는 날도 막힌 날도 403이다 · `sec-edgar-daily-index.md`).
- MUST NOT: 미국 색인 줄의 제출일이 색인 날짜와 같기를 요구하지 않는다 — 늦게 배포된 공시는 원래 제출일로 뒷날 색인에만 실린다.
- MUST: 국내는 KST 오늘까지 묻고 어제까지만 닫는다. `013`인 날은 공시 없는 날로 닫는다.
- MUST: CIK → 종목은 갱신에 성공한 운영 매핑(`SecTickerCikCache.freshSnapshot`)으로만 붙인다 — 낡은 판의 「없음」으로 공시를 버리지 않는다.
- MUST NOT: 목록에서 편입 여부로 거르지 않는다. 장부는 우리 `stock` 표의 모든 종목에 붙인다 — 재편입 종목의 공시를 잃지 않게. 편입은
  소비 시점에 고른다.
- MUST NOT: 장부 행을 손으로 고치지 않는다 — 상태(소비 칸)는 소비 단계만 바꾼다.
- MUST NOT: 판 범위 일곱 파일(`ReaderVersionPinTest.SOURCE_SCOPE`)을 이 연동 때문에 고치지 않는다 — 한 토큰이 보충 판을 올려 저장분을 다시
  받는다. 새 클라이언트는 그 파일들의 받기(`SecFilingClient.fetch`)·판정(`SecEdgarResponse`)을 부르기만 한다.
- MUST: DART 어댑터는 콜마다 `DartResponse.acquire(limiter, quota, 경로, …)`를 지난다 — 일일 총량은 `DartDailyQuota`가 경로마다,
  속도(분당 600)는 `dartRateLimiter`가 키 합으로 센다.
- MUST NOT: 일일 계수를 `RateLimiter` 계층(구현 클래스 포함)에 넣지 않는다 — `ReaderVersionPinTest`의 해시가 SEC 판 범위 파일이 쓰는
  `RateLimiter`의 구현 전부를 싣는다. 처음 판이 `TokenBucketRateLimiter`에 넣어 SEC 판 해시를 바꿨고(전체 빌드가 잡음), DART만의 변경이
  보충 판을 올려 저장분을 다시 받게 할 뻔했다.

- MUST: 소비됨 표시는 FR·FSR의 종목 훅(`runForConsumption`) 안에서만 한다 — 실행이 끝난 뒤 「그 종목 실패 없음」으로 판정하면
  쓰기와 표시 사이에 간극(중단 · 부분 실패)이 생긴다. 훅이 던지면 그 종목의 쓰기도 되돌아간다.
- MUST: 근거는 쓴 응답이다. 미국 ① companyfacts(**보충 전** 트리)의 us-gaap 접수번호 ② 보충 표 OK·NO_XBRL·COVERED ③ submissions의
  `isXBRL=0`(보충은 XBRL 있는 공시만 다뤄 행이 안 생긴다). 국내는 그 (사업연도, 보고서 코드) 응답 접수번호 ≥ 목록 접수번호.
- MUST: 소비 FR·FSR은 티커로만 부른다 — 비면 전량이 된다. 정기 경로는 `GAP_FILL`(시계 셋이 안 센다).
- 국내 소비는 창을 대상 행 사업연도부터로 좁힌다 — DART 경로는 회수가 없고 두 어댑터가 해마다 닫혀 있다(FSR 비지배 게이트는 창 안
  최댓값이라 아래를 잘라도 남은 해의 판정이 같다 · 시험 둘). MUST NOT: 미국 창을 좁히지 않는다 — SEC 경로는 회수가 있다.

## 검증

- `FilingListingJobTest` · `SecFilingListSourceTest` · `DartFilingListSourceTest` · `DartDailyQuotaTest` — 닫는 규칙 · 실패에서
  멈춤 · 멱등 · 소스 없는 시장 · 커서 지연 알림 · 경로별 계수(대조군: 같은 경로는 한도에서 막힌다). `ReaderVersionPinTest`가 초록이다.
- `FilingListingReplayTest`(환경변수 `FILING_SAMPLES_DIR` · 0콜) — 저장한 색인 7일 × 편입 1,000이 따로 뽑은 21건과 같다.
- 2차: `FilingConsumptionJobTest`(국내 FR → FSR · 미반영은 「원장 아직」 · 편입 밖은 잠듦 · 대상 0이면 FR 안 부름 · **대조군: 표시 뒤
  훅이 던지면 재무 쓰기와 표시가 함께 되돌아감** · 미국 근거 셋 · 5평일 경보) · `SecFilingConsumptionTest` ·
  `DartFinancialAdapterTest`·`DartFullStatementGateTest`(창 아래를 잘라도 남은 해가 같다) · `PipelineSchedulerChainTest`(목록 뒤 소비 ·
  종료 신호면 소비 안 함).
- `CronsDoneTableTest` — `ops/crons-done.sh` 표에 14:16이 있다. `rg "rateLimiter\.(acquire|hasDailyQuota)\(" src/main/java/com/stockscreener/pipeline/datasource/kr/Dart*`가 `DartResponse` 한 곳뿐이다.

```premise 원장 공시 목록의 커서가 평일 이틀 넘게 늦지 않다
psql -X -d stock_screener -At -c "WITH m(market) AS (VALUES ('US'), ('KR')),
c AS (SELECT m.market, max(d.listing_date) AS cursor FROM m LEFT JOIN filing_listing_day d ON d.market = m.market GROUP BY m.market),
lag AS (SELECT market, cursor, (SELECT count(*) FROM generate_series(cursor + 1, current_date - 1, interval '1 day') g
                                 WHERE extract(isodow FROM g) < 6) AS weekdays FROM c WHERE cursor IS NOT NULL)
SELECT CASE
  WHEN (SELECT count(*) FROM c WHERE cursor IS NULL) = 2 THEN '잴 것이 없었다 — 아직 한 날도 닫지 않았다(1차 배포 전)'
  WHEN (SELECT count(*) FROM lag WHERE weekdays > 2) = 0 THEN 'OK ' || (SELECT string_agg(market || ' ' || cursor, ' · ') FROM lag)
  ELSE '커서가 늦다: ' || (SELECT string_agg(market || ' ' || cursor || ' 평일 ' || weekdays || '일', ' · ') FROM lag WHERE weekdays > 2)
       || ' — batch_execution의 filing-listing note가 마지막 실패를 말한다' END"
```

## 배경

새 정기 공시는 FR(목)·FSR(수, 국내)·새 편입 꼬리로만 들어왔다. 정기 주기가 「마지막 성공에서 25일 초과」라, 원장에 있는 공시가 DB에 오기까지
3분기 모형으로 미국 중앙값 15평일 · 국내 9평일이 걸렸다(미국 편입의 10-Q/10-K 14건 중 13건이 원장에 있는데 DB에 없던 단면 · 재무 동기
2부 계획 P7·P9). 결손은 지연 기간의 현재 화면·워치리스트 값이다 — 늦게 온 공시는 들어오는 날 과거 날짜까지 다시 지어진다.
「그 제출일 행이 있는가」로는 소비를 판정할 수 없다 — 미국 판은 같은 날 판을 접고 값이 같은 뒤 판을 안 낸다(`SecEdgarFacts`). 그래서
공시마다 한 행인 장부가 필요하다.

## 버린 대안

- **날짜마다 폼 색인을 물어 상태코드로 휴무일을 가른다** — 휴무일이 404가 아니라 403이어서(실측) 막힘과 못 가른다. 막힌 날을 넘기면 공시를 잃는다.
- **회사마다 submissions를 매일 묻는다** — 미국 편입 1,000종목 × 매일이 일일 색인 1~2콜과 같은 답을 준다. 색인이 회사 수와 무관하다.
- **목록을 편입 종목으로 거른다** — 재편입이 주 11~16건이라(반증 실측) 그 사이 공시가 장부에서 빠진다.
- **DART 일일 한도를 키 합 18,000으로 둔다** — 같은 키로 하루 약 28,400콜(두 경로)에 실패 0인 운영 기록이 「키 합 2만」의 반례다.
  우리가 먼저 걸면 소스가 막지 않는 날의 소비를 우리가 끊는다. 경로 단위 한도의 존재는 재지 않았다 — 넘는 날 DART가 `020`을 내면
  `RATE_LIMITED`로 남고 다음 날 다시 돈다(값은 안 틀린다).
- **(2차) 국내 반영을 탐침 1콜로 먼저 묻고 FR을 기본 창으로 돈다**(2부 계획 v1012 §2-C) — 탐침은 FR과 다른 시각의 다른 응답이라
  근거가 쓴 것과 갈릴 수 있고, 창을 좁히면 FR 자체가 종목당 4~8콜이라 아낄 것이 없다. 같은 이유로 「12월 결산은 DB 행으로 FR 몫을 닫는
  지름길」과 「수요일엔 FSR 몫을 다음 평일로」도 걷었다 — 지름길은 근거가 쓴 응답이 아니다.
- **(2차) 실행이 끝난 뒤 「ABORTED 아님 + 그 종목 실패 행 없음」으로 표시한다** — 쓰기와 표시가 다른 트랜잭션이라 갈릴 수 있다.
- **목록·소비를 14:15 무거운 잡 크론에 넣는다** — 그 잡은 실패하면 예외를 다시 던져 그날 목록이 함께 빠진다. 풀이 1이라 따로 둬도
  순서(14:15 → 14:16 → 14:18)는 그대로다.

## 결과

- 쉬워지는 것: 원장에 있는데 DB에 없는 공시를 장부가 공시 단위로 센다(소비 안 끝난 행). 2차 소비는 그 행만 좁혀 먹인다.
- 2차 원장 대조(2026-10-13 · SEC 42콜): 편입 표본 21건 중 10-Q/10-K 14건은 companyfacts us-gaap에, 6-K 7건은 submissions `isXBRL=0`에
  있었다 — 근거 ①·③으로 21/21이 닫힌다(②의 실물은 표본에 없다).
- 2차 드라이런 D4(2026-10-13 · 작은 DB · 표본 34종목 · 운영 행 복사 · 목록 → 소비): 장부 35행 전부 소비 — 미국 COMPANYFACTS 15 ·
  NO_XBRL 8, 국내 FR·FSR 12/12(DART_RESPONSE). 새 재무 행 34는 전부 장부 공시의 접수일 판이고, 원본 행(rev0) 값은 하나도 안 바뀌었다
  (`is_latest`만 — 국내 교정 5는 정정된 사업보고서의 4분기 유도 행을 새 공시일로 다시 지은 것으로 종목별 회수 수와 1:1). 두 번째 실행은
  두 시장 「대상 0」으로 FR을 부르지 않았다. 국내 FR·FSR 실행은 창 2023~2026(정정된 옛 사업보고서 때문) · FSR 실제 162콜.
- **1차 수용(2026-10-14 · 첫 정기 실행 #794 · 14:16:05~14:16:29)**: 커서가 전제대로 미국 09-24 · 국내 09-21부터 10-13까지 닫았다(미국 20일 · 국내 23일).
  장부와 따로 받은 원장 집합 X(EDGAR `form.{d}.idx` 13일 · DART `list.json` A001~A003 23일 · 정기 폼 × 우리 `stock` 종목)와 맞대어
  **미국 X 1,467 = 장부 1,467 · 국내 X 19 = 장부 19 · 양방향 차 0**. `NO_INDEX` 7일 중 평일 10-12는 색인이 없다(대조: 같은 요청으로 10-13·10-09 200 ·
  10-12 403 · QTR4 디렉터리 목록에 10-12 파일 없음). 미국 장부 1,467 중 활성 편입은 40 — 나머지 1,427은 `US_LISTED` 비편입(6-K 1,263)으로 2차 소비가 잠재운다.
- **2차 수용(2026-10-15 · 첫 정기 소비 #802 · 14:16:17~14:17:40 · 좁힌 FR #803·#804 · FSR #805 · 전부 GAP_FILL·NARROW)**: 실행 전에 찍은
  편입 열린 행 X 54(미국 40 · 국내 14 · 운영 SELECT)가 **전부 소비** — 미국 COMPANYFACTS 25(제출일 판 25/25 있음) · NO_XBRL 15(6-K
  `isXBRL=0` — 판이 없는 것이 원장 대조의 답) · 국내 DART_RESPONSE 14(판 14/14 · FR·FSR 다 표시). 소비 안 끝난 행 0. 그 종목들의 최신
  거래일 지표가 14:20 화해(#807·#808)에서 소비 뒤 시각으로 다시 지어졌다 — 54행 전부(정정으로 지워졌던 `CCL`·`APLD`·`055550` 등 포함).
  같은 날 14:15 정기 FR은 「마지막 성공 12일 전(10-03) < 25일」로 건너뛰었다 — 소비 실행이 NARROW라 시계를 안 밀었다(설계대로). 재는 법:
  `~/Library/Application Support/stock-screener/p2-accept/accept2.py open-before-1015.tsv`(X는 실행 전에 찍어야 한다 — 실행 뒤에는 다시 못 만든다).
  리미터 2주 계수(§5)는 10-28까지 센다.
- **남긴 수의 단위가 제약과 모순이었다(2026-10-20 · 21 실행 869 · 887 FAILED · 탐색 `FILING-LISTING-KEPT-PLAN-1022.md`)**: V67 `kept_rows`는
  「종목 행 수」였고 제약은 `kept_rows <= source_rows`(원본은 정기 공시 **줄** 수)였다. 한 공시가 발행사의 워런트 · 유닛 티커 행까지 붙는 CIK가
  날마다 섞여 처음부터 넘을 수 있었다(장부 행 · 서로 다른 공시: 10-08 98 · 89 · 10-09 92 · 79(원본 96 — 여유 4) · 10-13 169 · 146) — 10-19에
  넘었다(원본 90 · 92행). 그 예외가 시장 루프 밖에서 실행을 세워 **국내 목록까지 10-18에 멈췄다.** 고침: 남긴 수 = 장부에 붙은 서로 다른 공시 수
  (남긴 공시마다 그것을 낸 원본 줄이 있으므로 제약이 구조로 선다 · 공동 등록인 공시는 줄이 여럿이어도 공시 하나) · 장부 쓰기의
  `DataAccessException`은 그 시장의 실패 · 받기 실패와 쓰기 실패는 그 시장을 미완료로 적어(ADR-0174) 실행이 시계를 안 민다(부분 성공이
  신선도 예산을 날마다 덮던 구멍 — 받기 실패도 같았다). 드라이런(SEC 색인 2 + 티커 파일 1 · 잡의 CIK 묶음 그대로): 10-19 옛 계수 92(운영과 같다) →
  새 86 · 10-20 97 → 91. **V69_4 배포 전 행의 `kept_rows`는 옛 단위(종목 행 수)다** — 읽는 코드는 없다(`grep kept_rows`).
  배포 뒤 첫 정기(10-22 14:16 #899): 두 시장 커서 10-21 · 미국 10-19 86 · 10-20 91(둘 다 드라이런과 같다) · 10-21 132 중 105 · 이어진 소비 #900 33 · #903 264.
- 비용: 평일마다 SEC 1~2콜(분기 목록 + 그날 색인) · DART 6콜 + 쪽수. 첫 실행은 커서부터 밀린 날만큼(미국 09-24~ · 국내 09-21~).
- 재지 않은 것: 색인이 날짜 순서를 어기고 늦게 올라오는 경우(앞날을 「없는 날」로 닫는다 — 7일 표본은 순서대로였다) · 새 분기 디렉터리가
  첫 색인 전에 403인지(그날은 앞 분기 끝까지만 닫고 하루 늦는다) · DART 경로 단위 한도의 존재 · (2차) 국내 원본 정기보고서의 재무 API
  반영 지연 · 수요일 정기 FSR과 겹친 날 `/fnlttSinglAcntAll` 우리 한도 18,000 도달(넘으면 남은 종목이 RATE_LIMITED로 하루 늦는다).
- 되돌리기: 크론과 잡을 걷고 V67 두 표를 남겨도 아무것도 안 읽는다. 소비만 걷으려면 14:16 크론의 `filingConsumptionJob.run` 한 줄을
  지운다 — V68 칸과 운반(`FilingTrace`)은 출력에 닿지 않는다. 일일 계수는 `dartRateLimiter`의 일일 한도를 다시 `dart.daily-quota`로 주고 `DartDailyQuota`를 걷으면 키 합이다.
