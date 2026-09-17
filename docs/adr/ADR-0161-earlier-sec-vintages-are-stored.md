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
- **MUST: 재번호도 켰을 때만 돈다.** 끈 채로 돌면 회수가 지운 라벨의 번호가 비는 지금의 동작이 남는다(실측 미국 33라벨).

## 검증

```premise 미국 현재 편입의 원 공시 판이 빠지지 않았다
psql -d stock_screener -At -c "WITH firsts AS (SELECT f.stock_id, f.fiscal_year, f.fiscal_period, min(f.published_at) first_pub FROM financial_statement f JOIN stock s ON s.id = f.stock_id WHERE s.market_code NOT IN ('KOSPI','KOSDAQ') AND EXISTS (SELECT 1 FROM universe_membership um WHERE um.stock_id = f.stock_id AND um.universe_code = 'US_TOP1000' AND um.effective_to IS NULL) GROUP BY 1,2,3), flagged AS (SELECT a.stock_id FROM firsts a JOIN firsts b ON b.stock_id = a.stock_id AND b.fiscal_period = a.fiscal_period AND b.fiscal_year = a.fiscal_year + 1 WHERE a.first_pub >= b.first_pub) SELECT CASE WHEN (SELECT count(*) FROM firsts) = 0 THEN '잴 것이 없었다 — 미국 현재 편입 재무가 0행이다' WHEN count(*) = 0 THEN 'OK 원 공시 판이 빠진 라벨 0' ELSE count(*) || '라벨·' || count(DISTINCT stock_id) || '종목의 첫 저장 판이 이듬해 같은 기간 첫 판보다 늦다 — 원 공시 판이 없다(재현율 약 30%의 하한). financial-refill --insert-earlier-vintages=true --financial-years=11(현재 편입 --universe=US_TOP1000 · 과거 편입 --tickers)과 그 종목 백필이 닫는다(ADR-0161)' END FROM flagged"
```

**시험**: `FinancialWriterTest$EarlierVintages` 7건 — 삽입·무효화 기점·재번호 셋이 **결함을 되살린 코드(`insertEarlier = false`)에서 붉었다**
(2026-09-17 대조군).

## 결과

- **코드는 들어갔고 배포도 값 반영도 아직이다(2026-09-17).** 위 펜스가 612라벨·326종목으로 붉고 **묶음 적용 전까지 붉은 것이 정상이다** — 반영은 E·L과 **한 창**에서 한다
  (progress.md ①). Q4V만 먼저 반영하면 E와 겹치는 종목을 두 번 무효화·백필한다.
- **크기(캐시 1,214종목 스윕 대 DB)**: 빠진 판 **2,596·683종목**(유도 1,378 · 소스 1,218). 보존 창 안까지 비는 판 290·133종목 ·
  편입일 12,894(거의 복원 평면). **오늘 저장값에도 닿는다** — `ValuationHistory.median`이 봉마다 그때 보이던 판으로 배수를
  내 1,260봉 중앙값을 만들므로 `PER/PBR/PSR_MEDIAN_5Y`·`*_DISCOUNT_5Y`가 바뀐다. 활성 화면 중 읽는 것은 프리셋 `린치 고속성장주`
  (`PER < PER_MEDIAN_5Y`)와 사용자 화면 v13~v16(`PER/PBR_DISCOUNT_5Y` · 가중치 조건)이다. 바뀌는 크기는 아직 안 쟀다(드라이런이 잰다).
- **도달**: `financial-refill`의 기본 창이 8년이라 11년을 명시한다 · `--universe`는 현재 편입만이라 과거 편입은 `--tickers`다 ·
  `US_TOP1000` 이력 없는 8종목은 쓰기는 닿지만 백필이 안 닿아 넣지 않는다.
- **순위는 안 움직인다(반증자 모의 · 재현하지 않았다)** — `JdbcStoredShareCounts` 규칙을 격자 54점에 모의해 주식수 1% 넘게 바뀐 칸 0
  (유도 Q4가 같은 기말·공시일의 FY와 주식수가 같다). 적용 창에서 `universe-restore --dates` 전후 한 쌍으로 확인한다.
- **어려워지는 것**: 「정정」 배지가 `revision_no > 0`이라 재번호 뒤 재게재에도 붙는다 — 배지의 뜻은 따로 고친다.
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
