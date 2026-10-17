---
id: ADR-0180
status: 채택됨
date: 2026-10-17
applies_to:
  - "src/main/resources/db/migration/V69_1__us_identity.sql"
  - "src/main/java/com/stockscreener/pipeline/collector/identity/**"
  - "src/main/java/com/stockscreener/pipeline/datasource/IdentitySource.java"
  - "src/main/java/com/stockscreener/pipeline/datasource/SecurityRef.java"
  - "src/main/java/com/stockscreener/pipeline/datasource/us/AlpacaSecIdentityAdapter.java"
  - "src/main/java/com/stockscreener/pipeline/batch/StockFoldJob.java"
  - "src/main/java/com/stockscreener/pipeline/batch/SecCikBindJob.java"
---

# ADR-0180. 미국 종목 행은 증권 실체 하나 × 등록인 시대 하나다

## 결정

**미국 `stock` 행 하나는 Alpaca 증권 실체 하나(닻)와 SEC 등록인 하나(`sec_cik`)다.** 행에 그 신원을 저장하고, 소스는 표지(`ticker`)가 아니라
그 신원으로 묻는다. 실체가 등록인을 바꾸면 **행을 끊는다**(앞 행 `era_to` · 뒤 행 `era_from`). 개명은 같은 행의 표지를 바꾸고 옛 기호를
`stock_symbol_era`에 남긴다. 기호 재사용은 옛 행의 표지를 `<기호>~<마지막 거래일>`로 비운다.

판정은 토요일 리밸런싱의 미국 첫 지수 앞에서 한 번 한다(`UsIdentityTriage`). 판정과 근거는 `identity_triage`에 남는다. 설계와 실측의 정본은
탐색 브랜치 `explore/s-vintage-coherence`의 `explore/financial-sync/US-IDENTITY-PLAN-1016.md` v3.1이다(§0 ★1~★16 · 두 렌즈 반증 세 바퀴).

## 규칙

- **MUST: 미국 소스 호출은 `SecurityRef.identity()`로 한다.** 봉은 `symbols=<닻 기호>&asof=<닻 날짜>`(`barSymbol`·`barAsOf`) — 기본 asof는
  「오늘 그 기호의 실체」라 개명·재사용 뒤 다른 실체를 준다(`HLX` 기본 = 09-01에 끝 · `HLX@08-28` = 지금 HOS 봉). 재무·업종은 `sec_cik`가
  있으면 그것으로, 없을 때만 오늘 SEC 티커 파일로 푼다.
- **MUST: 닻 날짜는 행마다다** — 행이 생긴 미국 동부 날짜. 상수로 두면 그 뒤에 생긴 행이 다른 실체를 집는다.
- **MUST: `sec_cik`는 포함 검사(`RegistrantContainment`)로 검증된 것만 넣는다** — 그 행의 저장 접수번호가 전부 그 CIK의 companyfacts ∪ 같은
  CIK 보충 표에 든다. 재무 행이 0인 행은 첫 FR이 물은 CIK로 묶는다(섞일 판이 없다). 묶는 자리는 FR(`FinancialCollector` · 추가 콜 0)과 손 잡
  `sec-cik-bind`다.
- **MUST: 미결합 행의 FR은 쓰기 전에 포함 검사를 한다.** 하나도 안 들면(`NONE` — 오늘 파일이 그 기호에 다른 등록인을 준다) 쓰지 않고 소스가 답한
  실패로 남긴다. 일부만 들면(`PARTIAL`) 지금처럼 쓰되 묶지 않는다 — companyfacts가 실었던 공시를 빼고 그 보충마저 실패하면 같은 등록인도 이렇게
  나온다.
- **MUST: 공시 장부(`stocksByCik`)는 묶인 등록인을 먼저 쓴다** — 오늘 파일로 풀면 교체 기호의 새 등록인 공시가 옛 행에 붙는다(★16).
- **MUST: 같은 실체 판정은 봉 (종가 · 거래량 · 체결 수)가 겹친 날 전부 같은가로 한다.** 다른 기호끼리는 겹침 `IdentityJudge.MIN_OVERLAP`(20)
  이상이어야 한다 — 겹침 0의 「전부 같음」은 공허한 참이다. 같은 기호의 닻 대 오늘은 겹침 하나면 된다(거래 드문 증권).
- **MUST: 행 끊기는 전제 셋이 다 서야 한다**(`SplitEvidence`) — 저장 접수번호가 새 CIK에 하나도 없음 · 날짜 박힌 끝의 증거(Alpaca CUSIP 개명 ·
  재편 · 인수 합병 / SEC `25-NSE`·`15-12*`·`8-K12B`) · 교체일. 하나라도 빠지면 그 기호만 `AMBIGUOUS`로 멈춘다 — 리밸런싱 전체는 돈다.
- **MUST: 판정 재료를 못 받으면 아무것도 안 바꾸고 그 실행에서 새 미국 기호를 등록하지 않는다.** 판정 없이 등록하면 개명된 실체가 새 행이 된다.
- **MUST: 기업 행위와 배당은 저장된 기호 시대도 묻는다**(원장 걷기와 합집합). 시대는 양 끝 날짜로 자른다. 개명 아닌 행은 시대가 0줄이라 요청도
  값도 지금과 같다.
- **MUST: 그날까지 등록인 시대가 끝난 행은 순위 후보가 아니다**(`JdbcStoredShareCounts` · `era_to` 제외).
- **MUST NOT: 행을 지워 중복을 없애지 않는다.** 다시 받을 수 없는 자식이 넷이다(관측 편입 · 관심 종목 · 실패 기록 · 공시 소비). 접기 잡
  `stock-fold`가 표마다 옮기거나 같은 사건이면 버리고, 값이 다르면 그 짝을 멈춘다. 자식 표는 실행 때 `pg_constraint`로 세고 규칙이 없는 표가
  있으면 쓰지 않는다.
- **MUST NOT: 판정에 기업 행위 원장을 정본으로 쓰지 않는다** — 원장에 없는 개명이 있다(CYCN→KRSA · ★4). 원장은 끝의 증거와 개명일 대조로만 쓴다.

## 검증

- `rg -n "asof" src/main/java/com/stockscreener/pipeline/datasource/us/AlpacaPriceAdapter.java` — 닻 asof를 보낸다.
- 단위·통합 시험: `IdentityJudgeTest` · `SplitEvidenceTest` · `RegistrantContainmentTest` · `UsIdentityTriageTest` · `StockFoldTest` ·
  `StockIdentityRefTest` · `AlpacaPriceAdapterTest`(닻) · `AlpacaCorporateActionAdapterTest`(저장 시대 · 신원 사건) · `AlpacaDividendEraTest` ·
  `SecFilingListSourceTest`(묶인 등록인) · `JdbcStoredShareCountsTest`(끝난 시대) · `SecFilingSupplementWriteContractTest`(FR 포함 검사).
- 탐침(실호출 · 운영 읽기만): `UsIdentityProbeLiveTest`(판정 함수를 운영 모집단에) · `AnchorNeutralityProbeLiveTest`(닻 봉 = 저장 봉).

```premise 미국 신원 판정의 마지막 실행에 보류(AMBIGUOUS)가 0이다
psql -d stock_screener -At -c "WITH last AS (SELECT max(execution_id) AS e FROM identity_triage WHERE verdict = 'RUN')
SELECT CASE
  WHEN (SELECT e FROM last) IS NULL THEN '잴 것이 없었다 — 신원 판정이 아직 한 번도 안 돌았다(배포 뒤 첫 토요일 리밸런싱 전)'
  WHEN count(*) FILTER (WHERE t.verdict = 'AMBIGUOUS') = 0 THEN 'OK 마지막 실행 ' || (SELECT e FROM last) || ' 보류 0'
  ELSE '마지막 신원 판정(실행 ' || (SELECT e FROM last) || ')에 보류 ' || count(*) FILTER (WHERE t.verdict = 'AMBIGUOUS')
       || '건 — 기호마다 원장에 물어 판정한다(증거는 identity_triage.evidence): '
       || string_agg(t.symbol, ',' ORDER BY t.symbol) FILTER (WHERE t.verdict = 'AMBIGUOUS') END
FROM identity_triage t WHERE t.execution_id = (SELECT e FROM last)"
```

```premise 미국 행에 실체 닻이 다 있다
psql -d stock_screener -At -c "SELECT CASE
  WHEN count(*) = 0 THEN '잴 것이 없었다 — 미국 행이 없다'
  WHEN count(*) FILTER (WHERE alpaca_anchor_symbol IS NULL) = 0 THEN 'OK 미국 ' || count(*) || '행 닻 있음'
  ELSE '닻 없는 미국 행 ' || count(*) FILTER (WHERE alpaca_anchor_symbol IS NULL) || ' — 등록 경로 밖에서 생긴 행이다. 신원 판정이 그 행을 못 본다: '
       || string_agg(ticker, ',' ORDER BY ticker) FILTER (WHERE alpaca_anchor_symbol IS NULL) END
FROM stock WHERE market_code = 'US'"
```

「같은 실체 · 같은 등록인 · 열린 시대 두 행」은 DB 펜스로 못 센다 — 같은 실체인지는 봉이 답하고, 등록인을 나눠 쓰는 행은 클래스가 여럿인
발행사(GOOGL·GOOG)에서 정상이다. 접기 잡의 계획 모드(`--job=stock-fold --apply=false`)가 그 수를 낸다.

## 배경

미국 행은 `ticker` 문자열로만 식별되고 실행마다 그때의 목록(Alpaca 활성 목록 · SEC 티커 파일 · 봉의 기본 asof=오늘)으로 다시 풀렸다. 행이
생긴 08-29 뒤 일어난 신원 사건마다 그 풀이가 다른 실체를 가리키거나 아무것도 안 가리켰다 — 개명 49 · 합병 폐지 25 · 장외 이전 9 · 등록인 교체
8 등(★5·★13). 결과는 재무 끊김 · 과거 명부의 생존 편향 · 개명된 실체의 토요일마다 새 행(10-17 밤 34) · 등록인이 바뀐 행에 새 등록인 재무가 붙는
길(★16)이었다. 원장은 실체의 연속(Alpaca `asof`)과 등록인(companyfacts 접수번호)을 정직하게 준다 — 가짜 한계였다.

## 버린 대안

- **오늘 파일로 CIK 일괄 고정(v1)** — 포함 검사 실패 3행이 다른 등록인을 받는다(★1).
- **CIK 계보를 한 행에서 합치기(v2)** — 판 라벨이 `(stock_id, 연도, 분기)`이고 Q4 유도가 한 트리의 회계연도 창을 전제한다. SPAC 신탁 재무와
  운영회사 재무가 한 라벨 공간에 든다. 합치면 `completeVintageHistory`를 꺼야 해서 이른 판·보충 판까지 꺼진다.
- **등록인 교체 행 제외** — 원장이 뒤집히는 것이 아니다(두 원장이 같은 날짜를 일관되게 준다). 빼면 재편·de-SPAC 뒤 운영회사가 영영 빠진다.
- **기업 행위 원장을 개명의 정본으로** — CYCN→KRSA가 원장에 없다 · 목록 이탈 46이 원장으로 설명 안 된다(★4·★5).
- **`sec-cik-bind`로만 묶기(계획 v3.1)** — 재무 있는 5,128행마다 companyfacts를 다시 받는다(평균 약 1.3MB · 약 6.7GB · 전체 FR 한 판). 정기 FR이
  같은 트리를 이미 받으므로 FR 자리에서 잰다(추가 콜 0). 손 잡은 한 번에 묶어야 할 때(관문 전)로 남긴다.
- **끊긴 새 행이 앞 행의 주식수를 이어 쓴다(순위 공백을 메우려고)** — 1:1 지주사 재편에는 맞지만 de-SPAC은 합병 뒤 주식수가 크게 바뀐다. 공백은
  진짜 한계로 두고 미표시한다(DIS 재편 2019-03-20 → 1744489 첫 주식수 사실 2019-05-08 · 49일).
- **판정을 관문 설정 뒤에** — 꺼진 상태가 토요일마다 중복 행을 만들고 그때마다 승인 접기를 부른다. 개명 아닌 행은 판정 전후 값이 같다(R1).

## 결과

- 신원 판정·닻·등록인 묶기·기호 시대 합집합·끝난 시대의 순위 제외가 설정 밖으로 `main`에 들어간다. 클래스 축의 공시일 기호 맞추기와 과거 날짜
  상장 판정(D4~D6)은 관문 설정 뒤 몫이고 아직 없다.
- 복원 경로(`UniverseRestoreJob`)는 신원 판정을 안 탄다 — 미국 복원 명부는 주식수가 있는 행의 순위라 새 기호를 원리적으로 안 낸다.
- 배당의 원장 개명 걷기(이 표가 생기기 전 개명 · FLG←NYCB)는 안 넣었다 — 개명 아닌 행의 값도 바꾸므로 관문 설정 뒤 몫이다.
- 순위의 CIK 대표 고르기(`dropCikDuplicates`)는 오늘 파일 CIK 그대로다 — 묶인 등록인과 갈리는 행은 끊기 전의 교체 행뿐이고 그 행들은 비편입이다.
- 되돌리려면: 어댑터의 `identity()` 읽기를 표지로 되돌리고 `UniverseRebalanceJob`의 판정 호출을 걷는다. 마이그레이션의 열·표는 남아도 읽는 쪽이
  없으면 값에 안 닿는다.
- **운영 접기(2026-10-17 21시 · 실행 834 · 판단은 사용자가 위임 — 원문 「claude.md의 판단기준과 드라이런 프로세스 근거하여 너가 판단해」)**:
  짝 35 = 접기 29 · 시대 닫기 6 · 실패 0. 미국 행 8,664 → 8,635 · 시대 닫힘 6행. 계획(읽기 전용 탐침 `FoldPlanProbeLiveTest`)은 재무 옮김
  220 · 같은 접수번호 버림 489(전부 추출 판 차이) · 부재 표 옮김 594 · 갱신 표지 27 · `US_LISTED` 편입 29를 옛 행으로 잇기 · 가격·지표·공시
  소비·실패 기록·관심 종목 0이었다. 접은 행에 최신 표지·판 순서 위반 0. 버린 판의 값을 다음 FR이 채우는지는 아직 안 쟀다(첫 FR 뒤에 잰다).
- **R0'(2026-10-17 · `UsIdentityProbeLiveTest` · 운영 읽기만 · 10-17 리밸런싱 전 상태 재현)**: 행 8,504 · 목록 8,528 · 같은 실체 8,361 ·
  봉 없음 6 · 「한쪽만 빔」 보류 0 · 재사용 0. 개명 짝 **35**(계획 34 + WKEY→WQEY — 지주사 재편 `stock_merger` 10-05 · `8-K12B` 10-02라
  계획의 짝 후보 생성에 없었다) = 재무 0행 개명 12 + 미결합 23(포함 검사 ALL 17 · NONE 6). 같은 등록인 29 · 등록인 교체 6은 6짝 모두 끝의 증거와
  교체일이 섰다(COLA 10-02 · IPEX 09-28 · JFB 09-04 · NHIC·NHICW 09-22 · WKEY 10-05).
- **R1(같은 날 · `AnchorNeutralityProbeLiveTest` · 표본 65 = 의도 15 + 무작위 편입 50)**: 닻 봉 = 기본 봉 64/65. 다른 하나는 TEVA로, 기본은
  저장 봉 1,502일을 잃고 닻은 저장 봉과 같다(ADR-0167의 CUSIP 전환). 행마다 「값 다름 1」은 닻·기본이 같다 — 마지막 저장일(10-15) 거래량을 소스가
  그 뒤 고쳤다(저장 36,777,721 → 지금 39,920,276 · 닻과 무관).
- **R2(같은 날 · 작은 DB `stock_screener_gate_id1017` · 표본 16)**: `V69_1` 적용 0.035초 · 접기 2짝(HLX/HOS · FGNXP/FGCPP) 반영 뒤 접수번호 집합 =
  접기 전 합집합(51 · 88) · 라벨마다 최신 하나 · 번호 0..n · `US_LISTED` 이음 하나 · 시대 닫기 2짝(COLA/SAIQ · JFB/XTND) · FR 포함 검사가
  REAX·XRPN을 쓰기 전에 거절(NONE)하고 HOS를 묶었다. 같은 접수번호의 값 차이(39 · 51행)는 전부 추출 판 차이였다 — T가 빈 칸(08-30 뒤 생긴
  컬럼)과 그 사이 바뀐 순이익 규칙. 그래서 재무 표는 같은 접수번호면 T를 남긴다(`StockFold` javadoc).
