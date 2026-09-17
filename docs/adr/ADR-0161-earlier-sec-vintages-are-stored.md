---
id: ADR-0161
status: 채택됨
date: 2026-09-17
applies_to:
  - "src/main/java/com/stockscreener/pipeline/collector/FinancialWriter.java"
  - "src/main/java/com/stockscreener/pipeline/collector/FinancialCollector.java"
  - "src/main/java/com/stockscreener/pipeline/batch/FinancialRefillJob.java"
  - "src/main/java/com/stockscreener/pipeline/batch/BootstrapRunner.java"
---

# ADR-0161. 저장본보다 이른 SEC 판도 저장한다 — 손으로 켤 때만

## 결정

판 이력을 전부 내는 소스(`FinancialFetch.completeVintageHistory` — SEC)에서 **저장된 최신본보다 이른 공시본이
오고 그 공시일의 행이 없으면 새 행으로 넣는다.** 넣는 행은 `is_latest=false`이고, **넣은 즉시 그 라벨을**, 호출 끝에는
공시일 순서와 `revision_no`가 어긋난 라벨 전부를 `(published_at, revision_no, id)` 순으로 0..n 다시 매긴다. 지표 무효화 기점은
**넣은 판의 공시일(P1)**이다. 이 갈래는 `financial-refill --insert-earlier-vintages=true`로만 켜진다.

## 규칙

- **MUST: 스케줄러 경로는 거짓을 넘긴다**(`FinancialRefillJob.runAndReport`의 5-인자 판). 참으로 돌면 정기
  `financial-refill`이 수백 종목의 지표를 지우는데 `AUTO_BACKFILL_FINANCIAL_STOCK_LIMIT`(100)을 넘어 **지운 채
  남는다.** 스케줄러를 켜는 것은 그 상한이 종목 수가 아니라 예상 소요로 바뀐 뒤다(progress.md A35).
- **MUST: 켠 refill 뒤에는 무효화된 종목의 백필이 짝이다.** 백필 대상은 **실행 전에 DB에서 정해 파일로 남긴다** —
  잡이 모으는 목록은 메모리에만 있어, 중단되면 다시 돌려도 이미 넣은 판이라 목록에 안 뜬다.
- **MUST NOT: DART(`completeVintageHistory=false`)에 이식하지 않는다.** DART는 `(corp_code, 연도, 보고서코드)`마다
  판을 하나만 내므로 「이른 판」이 오지 않고, 국내 `revision_no`는 ADR-0071·ADR-0098의 premise가 읽는다.
- **MUST NOT: 이른 판을 `is_latest=true`로 넣지 않는다.** 더 늦은 공시본이 이미 최신이고 `uq_fs_latest`가 종목
  트랜잭션을 통째로 롤백한다.
- **MUST: 넣은 즉시 그 라벨을 재번호한다 — 호출 끝으로 미루지 않는다.** 소스가 라벨 안에서 공시일 오름차순으로 주므로
  같은 호출에서 뒤따르는 새 정정판이 `최신 번호 + 1`을 쓰는데, 그 번호가 임시 번호와 겹쳐 `uq_fs_revision` 위반으로 그 종목
  쓰기가 통째로 롤백된다(시험 `acceptsANewerRevisionInTheSameCallAfterAnEarlierInsert`가 수정 전에 붉었다).
- **MUST: 이른 판의 주식수는 같은 라벨에 저장된 값을 잇는다.** SEC 주식수는 라벨 앵커의 표지라 소스가 같은 라벨의
  모든 판에 같은 값을 주고, 저장값이 다르면 손 수리(ADR-0126)다 — 기존 행은 `Amount.overwritable()`이 지키지만 **새 행
  삽입은 그 보호를 안 거친다**. 사본 재생에서 `RPAY` 2023 Q4가 90,048,638,000으로 되살아날 뻔했다(삽입 2,565판 중 그 1칸).
  저장값이 둘 이상으로 갈리면 소스 값을 쓰고 경고를 남긴다.
- **MUST: 펜스 예외는 증거와 두 공시일까지 고정한다.** `PACS` 2024 Q3는 SEC 제출 목록에 2024-11-15 `NT 10-Q` 뒤
  2025-11-19 10-K 1·10-Q 4를 몰아 낸 것이라 「첫 판 = 이듬해 첫 판」이 소스 사실이다. 판정식 `>=`를 `>`로 바꾸면 결함의
  주된 모양(원 공시 판이 빠져 첫 저장 판이 이듬해 비교 열인 것)을 함께 놓친다. 예외가 더는 안 걸리면 펜스가 「낡았다」를 낸다.
- **MUST: 재번호도 켰을 때만 돈다.** 끈 채로 돌면 회수가 지운 라벨의 번호가 비는 지금의 동작이 남는다(실측 미국 33라벨).

## 검증

```premise 미국 현재 편입의 원 공시 판이 빠지지 않았다
psql -d stock_screener -At -c "WITH firsts AS (SELECT f.stock_id, f.fiscal_year, f.fiscal_period, min(f.published_at) first_pub FROM financial_statement f JOIN stock s ON s.id = f.stock_id WHERE s.market_code NOT IN ('KOSPI','KOSDAQ') AND EXISTS (SELECT 1 FROM universe_membership um WHERE um.stock_id = f.stock_id AND um.universe_code = 'US_TOP1000' AND um.effective_to IS NULL) GROUP BY 1,2,3), flagged AS (SELECT s.ticker::text AS ticker, a.fiscal_year, a.fiscal_period::text AS fiscal_period, a.first_pub, b.first_pub AS next_first FROM firsts a JOIN firsts b ON b.stock_id = a.stock_id AND b.fiscal_period = a.fiscal_period AND b.fiscal_year = a.fiscal_year + 1 JOIN stock s ON s.id = a.stock_id WHERE a.first_pub >= b.first_pub), exc(ticker, fiscal_year, fiscal_period, first_pub, next_first) AS (VALUES ('PACS'::text, 2024, 'Q3'::text, DATE '2025-11-19', DATE '2025-11-19')), unexplained AS (SELECT * FROM flagged EXCEPT SELECT * FROM exc), stale AS (SELECT * FROM exc EXCEPT SELECT * FROM flagged) SELECT CASE WHEN (SELECT count(*) FROM firsts) = 0 THEN '잴 것이 없었다 — 미국 현재 편입 재무가 0행이다' WHEN (SELECT count(*) FROM unexplained) = 0 AND (SELECT count(*) FROM stale) = 0 THEN 'OK 원 공시 판이 빠진 라벨 0 (증거 예외 ' || (SELECT count(*) FROM exc) || ')' WHEN (SELECT count(*) FROM unexplained) > 0 THEN (SELECT count(*) FROM unexplained) || '라벨·' || (SELECT count(DISTINCT ticker) FROM unexplained) || '종목의 첫 저장 판이 이듬해 같은 기간 첫 판보다 늦다 — 원 공시 판이 없다(재현율 약 30%의 하한). financial-refill --insert-earlier-vintages=true --financial-years=11(현재 편입 --universe=US_TOP1000 · 과거 편입 --tickers)과 그 종목 백필이 닫는다(ADR-0161)' ELSE '예외가 낡았다 — ' || (SELECT string_agg(ticker || ' ' || fiscal_year || fiscal_period, ', ') FROM stale) || '가 더는 안 걸린다. ADR-0161 펜스의 예외에서 지운다' END"
```

**시험**: `FinancialWriterTest$EarlierVintages` 9건 — 삽입·무효화 기점·재번호 셋이 **결함을 되살린 코드(`insertEarlier = false`)에서 붉었다**
(2026-09-17 대조군).

## 결과

- **코드는 들어갔고 배포도 값 반영도 아직이다(2026-09-17).** 운영 펜스는 611라벨·325종목으로 붉고(PACS 예외 뺀 수)
  적용 전까지 붉은 것이 정상이다.
- **반영은 Q4V 단독으로 한다 — E·L과 묶지 않는다.** 묶던 근거가 「겹치는 종목을 두 번 무효화·백필한다」라는 비용이었다.
  Q4V는 읽기 코드 변경이 주석뿐이라 옛 jar의 13:30과 경쟁하지 않고, 순위 영향은 반증 둘이 따로 0이다(격자 36,960칸 · 54점).
- **드라이런(사본 DB · 2026-09-17 22시 덤프)** — 재생 하네스 `EarlierVintageReplayHarnessTest`:
  도달 675종목 삽입 2,565 = 행 증가 · 회수·정정·교정 0 · US `is_latest`·국내 행 불변 · 공시일–번호 역전 0 ·
  live refill 6종목이 재생과 공시일·번호·주식수까지 같다. 같은 백필을 돈 대조 사본과의 지표 대조: 공시일 구간 계열 128,107행이
  전부 [P1, P2) 안 · 5년 중앙값·할인 337,800행이 전부 [P1, P2+1,260봉] 안 · 675종목 밖과 국내 0. 화면(전 프리셋·저장 화면 21기준일):
  편입 여부 18/624칸 · 전 열 46/624칸 — 전부 미국 · 최신일 0. EMAT 시총 계단 05-22 → **02-20**.
- **적용 절차를 사본에서 전 구간 리허설했다(2026-09-18 01:27~03:44)** — 운영 역할을 사본에 주고 덤프·복원·배포 없는 refill·백필·판정·게이트까지.
  live refill이 재생과 같은 수를 냈다(현재 편입 2,257 · 과거 편입 310 = 재생 2,565 + 캐시 없는 `EBC` 2). 구간 규칙 밖 0 · 같은 판 값 차이 0 · 국내 불변 ·
  `ADJ_CLOSE` 감소 0. **스케줄러와 같은 플래그 거짓 refill을 Q4V 사본과 대조 사본에 돌린 결과가 같다** — 넣은 행을 다음 정기 refill이 되돌리지 않는다.
  소요는 refill 1,000종목 19분 · 과거 207 3분 · 원본층 4분 · 파생층 5분이다.
- **순위 재료 판정은 「대조군 대비」다.** 「적용 전 대 후」는 Q4V 밖 원인을 잡는다 — 리허설에서 `EXE`의 2026 Q2 판이 아예 없다가 이번 refill이 새 라벨로 넣어
  순위가 읽는 기가 Q1 → Q2로 바뀌었다. 운영 후 대 대조군 후는 전 격자에서 0줄이다.
- **사라지는 원본 지표 2,667행을 열었다**: 원 공시값으로 계산 규칙이 값을 안 냄 1,734행·23종목 · 5년 창 표본 287행·4종목 ·
  유도 Q4 기준 섞임 의심 646행(`APLD`·`UWMC` — 재작성된 FY에서 옛 기준 1~3분기를 뺐다). 뒤의 것은 **기존 저장 유도 Q4에도
  801판 중 81판(66종목)이 같은 모양**이라 이 결정이 만든 결함이 아니다 — 유도 축에서 따로 연다.
- **ADR-0151 펜스는 179 → 97이고 적용 뒤에도 임계(60) 위다.** 남는 97행은 전부 소스가 그 공시일에 Q4를 직접 주고 FY는 안 주는
  행이다(캐시 대조) — 그 펜스는 DB에 유도 여부가 없어 둘을 못 가른다.
- **크기(캐시 1,214종목 스윕 대 DB)**: 빠진 판 **2,596·683종목**(유도 1,378 · 소스 1,218). 보존 창 안까지 비는 판 290·133종목 ·
  편입일 12,894(거의 복원 평면). **오늘 저장값에도 닿는다** — `ValuationHistory.median`이 봉마다 그때 보이던 판으로 배수를
  내 1,260봉 중앙값을 만들므로 `PER/PBR/PSR_MEDIAN_5Y`·`*_DISCOUNT_5Y`가 바뀐다. 활성 화면 중 읽는 것은 프리셋 `린치 고속성장주`
  (`PER < PER_MEDIAN_5Y`)와 사용자 화면 v13~v16(`PER/PBR_DISCOUNT_5Y` · 가중치 조건)이다. 바뀌는 크기는 위 드라이런이 쟀다.
- **도달**: `financial-refill`의 기본 창이 8년이라 11년을 명시한다 · `--universe`는 현재 편입만이라 과거 편입은 `--tickers`다 ·
  `US_TOP1000` 이력 없는 8종목은 쓰기는 닿지만 백필이 안 닿아 넣지 않는다.
- **순위는 안 움직인다(반증자 모의 둘 · 재현하지 않았다)** — `JdbcStoredShareCounts` 규칙을 격자에 모의해 주식수 1% 넘게 바뀐 칸 0
  (유도 Q4가 같은 기말·공시일의 FY와 주식수가 같다). 적용 창에서는 외부 콜 없는 주식수 선택 질의를 전후로 대조한다 —
  `universe-restore --dates`는 과거 날짜에도 오늘의 상장 목록을 써 판정 재료가 흔들린다.
- **배지**: 「정정」 = `revision_no > 0`은 「공시일이 그 라벨의 첫 판보다 늦다」와 같은 뜻이라(표시행 3,207 = 3,207 — 반증 B 5차)
  재번호 뒤에도 공시일 칸과 짝이 맞는다. 832라벨에 새로 붙는 것은 그 행의 공시일이 재게재일이기 때문이다. 뜻을 「숫자가 바뀌었다」로
  바꾸는 안은 통화가 다른 판·유도 Q4·국내 회수 판에서 틀려 받지 않았고, 화면의 뜻이라 따로 정한다.
- **어려워지는 것**:
  `guard-rebalance-on-dirty-rank.sh`와 ADR-0125 premise가 공시일 없이 `revision_no`로 행을 고른다(동률 결함 자체) — E와 함께 고친다.

## 배경

`writeOne`은 소스가 최신본보다 이른 공시본을 주고 그 공시일의 행이 없으면 **건너뛰었다**(「revision_no 배정과 is_latest 관리가
따라붙는 별개 축」). 읽기는 봉마다 `published_at <= 봉`인 판으로 접으므로(ADR-0128) 빠진 판의 공시일부터 다음 저장 판의 공시일까지
**그 기가 안 보이거나 옛 판 값을 쓴다.** 대부분 10-K와 함께 나온 유도 Q4·FY의 첫 판이 빠지고 이듬해 비교 판만 저장된 모양이라
평균 1년 넘게 빈다. EMAT의 2025 Q4 유도 판(02-20)도 빠진 판 중 하나다 — 시총 계단이 05-22로 밀린 것(ADR-0126)에 이 결손과
`sharesOf`가 분기 행만 보는 것 중 어느 쪽이 얼마나 기여하는지는 **돌려 보지 않았다**(드라이런이 가른다).
ADR-0152는 같은 뿌리의 반대쪽(옛 규칙이 만든 판을 회수)을 닫았다 — 이 결정이 건너뛰던 쪽을 닫는다.

## 버린 대안

- **무효화 기점을 회계기간 시작일로(정정 갈래와 같은 규약)** — 그 판은 공시일 전의 봉에 원리적으로 안 보인다(읽기 경로 전부
  `published_at <= 봉`). 넓힐 이유가 없고, 실측으로 P1이 보존 창 안인 판이 적어 대부분은 어느 기점이든 창 전체가 지워진다.
- **플래그 없이 `completeVintageHistory`만으로 켠다** — 다음 정기 refill이 수백 종목을 무효화하고 백필 상한에 걸려 지표가 빈 채 남는다.
  배포가 값을 바꾸면 「무해한 선행 배포」가 성립하지 않는다.
- **재번호 없이 넣는다** — 공시일–번호 순서가 깨지면 `JdbcStoredShareCounts`의 동률 정렬과 가드 훅이 옛 판을 고른다.
