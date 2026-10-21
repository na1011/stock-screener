---
id: ADR-0181
status: 채택됨
date: 2026-10-19
applies_to:
  - "src/main/java/com/stockscreener/pipeline/collector/PriceObservationWindow.java"
  - "src/main/java/com/stockscreener/pipeline/collector/PriceWriter.java"
  - "src/main/java/com/stockscreener/pipeline/collector/PriceRefutation.java"
  - "src/main/java/com/stockscreener/pipeline/collector/CorporateActionSupplement.java"
  - "src/main/java/com/stockscreener/pipeline/batch/PriceBoundaryJob.java"
  - "src/main/java/com/stockscreener/pipeline/batch/SymbolEraSeedJob.java"
  - "src/main/java/com/stockscreener/pipeline/batch/PriceIntegrityAlerter.java"
  - "src/main/resources/price-boundary/**"
  - "src/main/resources/corporate-action/**"
---

# ADR-0181. 미국 행의 시세는 그 증권의 관측 구간 안에서만 쓰고, 원장은 소스가 준 비율 사건을 다 받되 가격으로 한 번 반박받는다

## 결정

미국 행의 저장 봉은 **그 행 증권의 관측 구간** 안이어야 한다 — `max(경계, era_from) ≤ 날짜 < era_to` 이고 머리 평봉이 아님. 경계는 커밋된 입력
(`price-boundary/boundaries.tsv` · 닻 키 · 근거 필수)이다. 원장은 소스가 주는 비율 사건(분할·병합 · 주식배당 · 같은 기호 단위 분할)을 다 받고, 소스가
빠뜨린 사건은 SEC 원문을 근거로 단 입력(`corporate-action/supplement.tsv`)으로 싣는다. 미국 소스 공시는 **적용 직전 가격으로 한 번 반박받는다**
(`PriceRefutation`). 이미 저장된 다른 증권 봉은 손 잡 `price-boundary`가 CSV로 내보낸 뒤 지운다. 정본 계획과 실측은 탐색 브랜치
`explore/s-vintage-coherence`의 `explore/price-holes/BUNDLE1-PLAN-1019.md` v4.1(두 렌즈 반증 「고쳐서 간다」를 재서 닫음) · 재료 `bundle1/`이다.

## 규칙

- **MUST: `price_daily`에 쓰는 자리는 전부 `PriceObservationWindow`를 지난다** — `PriceWriter.write`(모든 적재 경로)와 접기(`StockFold`의 봉 규칙).
  소스 봉을 읽어 값을 내는 과거 날짜 순위(`UsPastRanking`)도 행의 하한(`StoredUsRows.Row.priceFloor`)으로 거른다. 하나라도 빠지면 그 경로가 지운
  봉을 다시 넣는다(관문의 미국 시세 재적재 포함).
- **MUST: 경계 파일의 키는 표지가 아니라 닻(`alpaca_anchor_symbol` · `alpaca_anchor_date`)이다** — 표지는 개명 · 재사용 비우기로 바뀐다. 신원 판정이
  행을 끊으면 새 행은 닻이 달라 키에 안 걸리지만 `era_from`이 그 앞을 막는다(경계는 언제나 그 시대보다 앞이다).
- **MUST: 머리 평봉 규칙은 미국만이다** — 첫 실거래 앞 거래량 0 · 시고저종 같은 봉. 국내 거래정지 기준가는 같은 증권의 공식 값이다(950160 694봉).
  중간 평봉은 이력 안의 관측을 잇는 것이라 둔다.
- **MUST: 저장된 봉을 지우는 것은 `price-boundary --apply=true` 하나이고, 지우기 전에 지울 행을 CSV로 영구 위치에 내보낸다** — 내보내기가 실패하면
  지우지 않는다. ADR-0011 「원자료는 전량 보관」의 사유는 「지우면 소스가 과거를 계속 준다는 보장이 없어 되돌릴 수 없다」이고, 이 봉은 그 행
  증권의 원자료가 아니며(같은 기호 문자열의 다른 증권 · 체결 없는 채움) 소스가 지금도 준다 — CSV와 `ops/dump-db.sh`가 되돌리기 재료다. 그 밖의
  원자료 삭제는 여전히 ADR-0011이 막는다. 편입 구간과 겹치는 봉은 거절한다. 실행은 평일 13:30 전(ADR-0166).
- **MUST: 원장 비율 사건 종류는 종목 조회와 시장 전체 조회(순위 후보 다리)가 같은 목록(`AlpacaCorporateActionAdapter.RATIO_TYPES`)을 쓴다** — 한쪽만
  넓히면 편입과 후보의 주식수 기저가 갈린다.
- **MUST: 원장 보충 줄은 SEC 접수번호와 원문 문장을 든다**(없으면 기동 실패). 코드 갈래가 아니라 원장 입력이다 — 특정 종목을 위한 분기를 만들지
  않는다. 분사 꼴 줄의 `ratio`는 주식수 배수(s)라 주식수가 그대로면 1이어야 한다(`JdbcStoredShareCounts.SHIFT_TO_ASOF`가 APPLIED 비율을 곱한다).
- **MUST: 분사 자식은 그날의 기호로 묻는다**(자식 asof · 없으면 ex 날짜) — asof 없이 물으면 재사용·개명된 자식 기호가 다른 실체를 준다(UHAL.B 첫 봉
  12-19 대 그날 기호 UHALB 11-10).
- **MUST: 미국 소스 공시 중 사람이 안 본 APPROVED · APPLIED 사건은 재구축마다 반박받는다** — k = |ln e| / σ ≥ 5 이고 관측이 1에 더 가까우면, 또는
  앞 마지막 봉이 체결 없는 채움이면 OBSERVED(근거 note · ADR-0055 억제 · 재구축 잡이 알림)로 돌린다. 효력일 뒤 봉이 없으면 APPROVED는 그 판에
  적용하지 않고 두며 APPLIED는 그대로 둔다. 앞 봉이 없으면 소스 정본대로 적용한다. 임계는 두 집단의 간격(참 사건 중 관측이 1에 더 가까운 것의 k 최대
  2.76 · 소스 오류 k 최소 7.10) 안이다. **적용된 행도 보는 이유**: 판·봉은 사건보다 늦게 온다 — 새 사건은 다음 10-Q 전에, 봉 없는 후보의 사건은
  편입 전에 적용된다. 원 종가와 저장 판만 읽어 상태 없이 멱등이다. 「사람이 안 본」 = `judged_at` 없음 ∧ (APPROVED ∨ 노트 없음) — V69_3 앞의
  판정은 ADR-0090 펜스의 정의(APPLIED ∧ note NULL = 사람이 안 본 것)로 가른다. 적용됐던 행을 보류해도 `applied_at`은 이력으로 남는다.
- **MUST: k < 5이고 관측이 1에 더 가까우면 저장 주식수에 묻는다**(탐색 `SHARE-LENS-PLAN-1019.md` v2) — 앞 판(공시일 < 효력일) 대 뒤 판(기말 ∧ 공시일
  ≥ 효력일 — dei 표지일이 기말 뒤 · 공시일 앞이라 공시일만 보면 사건 앞 표지를 집는다)의 비 s가 |ln s| < |ln 비율| / 2면 보류한다. 그 종목의 사건
  없는 2칸 판 쌍이 8개 이상이고 비율과 반대 방향으로 반폭을 넘은 몫이 5% 이하일 때만 말하고, 뒤 판이 없거나 같은 판 키에 주식수가 둘이면 침묵해
  적용한다. **주식수 단독으로는 보류하지 않는다** — 가격 배수가 맞는 참 사건 139 중 5(TT · COGT · AKTX×2 · AMRN — ADS · 계열 불변 꼴)에서 「1」을
  낸다. 실례 GBCI 2021-02-04 「주식배당 1.1」(k 3.43 · SEC 표지 주식수 +0.08%)이 이것 없이 적용됐다(2026-10-19 · 사람 REJECTED 10-20).
- **MUST: 보류는 `held_at`, 사람 판정은 `judged_at` 열이 지킨다 — 노트가 아니다**(V69_3). 수집은 보류 행을 같은 값으로 덮지 않고(소스가 종류·비율·
  가격 배수·신주상장일을 고쳐 보내면 덮고 보류를 풀어 다시 판정한다), 반증은 사람이 상태를 정한 행을 다시 보류하지 않는다. 노트로는 못 가른다 —
  사람 판정이 note를 덮고 분사 행은 수집이 note를 다시 쓴다. 미해석 분사·휴리스틱 후보의 덮기는 그대로다(좁히면 미해석 분사의 승격이 끊긴다).
- **MUST NOT: 반박 적중을 자동 `REJECTED`로 두지 않는다** — QGEN 2024-01-30(k 2.33)은 참 사건인데 관측이 1에 더 가깝다. 숨기고 사람이 판정한다.
- **MUST: 감시(`PriceIntegrityAlerter` · 평일 15:30) 면제는 근거 필수 파일(`price-boundary/exempt.tsv`)이고 키는 (검사, 표지, 구간 시작일)이다.**

## 검증

```bash
# 경계 · 보충 · 면제 파일의 계약과 술어 · 반증 · 감시의 꼴별 대조군
./gradlew test --tests 'com.stockscreener.pipeline.collector.PriceObservationWindowTest' \
  --tests 'com.stockscreener.pipeline.collector.PriceRefutationTest' \
  --tests 'com.stockscreener.pipeline.collector.CorporateActionSupplementTest' \
  --tests 'com.stockscreener.pipeline.batch.PriceBoundaryJobTest' \
  --tests 'com.stockscreener.pipeline.batch.PriceIntegrityAlerterTest'
# price_daily에 쓰는 SQL이 PriceWriter · StockFold · AdjustmentRecalculator(계수 열) 밖에 생기지 않았다
rg -n "INSERT INTO price_daily|UPDATE price_daily|DELETE FROM price_daily|UPDATE %s SET stock_id" src/main/java
```

```premise 미국 보존 창 편입의 저장 봉이 전부 그 행의 관측 구간 안이다(경계 파일 행)
psql -d stock_screener -At -c "WITH b(sym, d) AS (VALUES ('CAI','2025-06-18'),('FLY','2025-08-07'),('NIQ','2025-07-23'),('PPLC','2026-03-04'),('CART','2023-09-19'),('VG','2025-01-24'),('SAIL','2025-02-13'),('FG','2022-12-01'),('LB','2024-06-28'),('CR','2023-04-04'),('BAM','2022-12-12'),('CRC','2020-10-28'),('EXE','2021-02-10'),('VAL','2021-05-03')),
 x AS (SELECT s.ticker, count(p.trade_date) n FROM b JOIN stock s ON s.market_code = 'US' AND s.alpaca_anchor_symbol = b.sym AND s.alpaca_anchor_date = DATE '2026-08-28'
   LEFT JOIN price_daily p ON p.stock_id = s.id AND p.trade_date < b.d::date GROUP BY s.ticker)
 SELECT CASE WHEN count(*) = 0 THEN '잴 것이 없었다 — 경계 파일 행이 DB에 없다'
   WHEN sum(n) = 0 THEN 'OK 경계 앞 봉 0 · 행 ' || count(*)
   ELSE '경계 앞 봉 ' || sum(n) || '(' || string_agg(ticker || ' ' || n, ', ') FILTER (WHERE n > 0) || ') — price-boundary --apply가 아직 안 돌았거나 덤프 복원 · 손 SQL이 되돌렸다' END FROM x"
```

## 배경

미국 행의 닻(ADR-0180)은 지금 실체를 고정하지만 소스는 그 실체의 요청 구간 앞에 같은 기호 문자열의 다른 실체 봉과 체결 없는 채움을 붙여 준다
(VG: 닻 · 문자열 · 오늘 asof 모두 Vonage 실거래 + 평봉 630 + Venture Global). 그 봉이 `RETURN_52W`(FLY +316.72% · CAI −65.85%) ·
`TRADING_DAYS_750` · 1,261봉 코드에 들어갔다. 원장은 소스가 주는 `stock_dividend`(CBSH 5% · SCCO 1%)와 같은 기호 `unit_split`(WOLF 교환
0.008352)을 버렸고, 소스가 빠뜨린 사건(UHAL Series N 9:1 · SPHR 분사 · CORZ 파산 교환)은 받을 길이 없었다. 반대로 소스 레코드가 틀린 것도
있었다(RTX · DRS — 사람 거절 · IR 0.8824 APPLIED · RJF 1.3).

## 버린 대안

- **경계를 `era_from`에 싣기** — 시대는 등록인 시대라 파산 재편(같은 CIK)에 안 서고 `eraCovers`를 읽는 순위·판독기 전부의 뜻이 바뀐다.
  거꾸로 `era_from`을 술어에 넣는 것은 같은 방향이라 선다.
- **경계를 `stock` 열로** — 끊기 · 접기 상속을 위해서였는데 시대 칸과 살아남는 행의 닻이 덮는다. 마이그레이션은 관문 몫(V70)과 번호가 부딪힌다.
- **읽을 때 자르기** — `price_daily`를 읽는 main 13파일이 전부 알아야 한다. **마이그레이션 DELETE** — CLI `bootRun`이 승인 전에 적용한다.
- **격리 테이블** — 마이그레이션이 필요하고 같은 되돌리기를 CSV가 준다.
- **`asof`로 증권을 가르기** — 옛 시절 asof가 옛 실체 끝을 주는 행(8)도 있지만 한 줄로 잇는 행(LB · SN · MBLY · CR · BAM)이 있어 경계의 근거로는 부족하다.
- **자기 계보 `stock_merger` 갈래(CORZ)** — 소스에 옛 증권을 이을 개명이 없어 휴리스틱이 된다. 빠진 것이 하나라 원문 한 줄이 정확하다.
- **UHAL · SPHR을 `UNSUPPORTED`로 미표시** — SEC가 비율과 날짜를 주고 자식이 거래된다. 진짜 한계가 아니다.

## 결과

- 이미 저장된 봉은 이 변경이 안 건드린다 — 교정은 `price-boundary --apply=true`(경계 14행 10,829봉 · 머리 평봉 8행 3,087 · BAM 원장 2행 REJECTED)와
  `symbol-era-seed --apply=true` 뒤 `initial-load --tickers=UHAL.B,SPHR --price-years=7 --skip-financial=true --reload-existing=true`(사용자 승인)다.
- 새 원장 종류는 배포 뒤 첫 14:00 수집이 받는다 — CBSH 6 · SCCO 10 BONUS_ISSUE · RJF(반박 → OBSERVED) · WOLF(자식 가치 못 잼 → OBSERVED) · 보충
  UHAL · SPHR(가치 비율) · CORZ(앞 봉 채움 → OBSERVED). IR 2020-03-02 APPLIED는 이 규칙이 다시 안 본다(이미 적용) — 판정 CLI로 REJECTED(승인).
- 감시의 지금 적중(2026-10-19 운영 질의): 간격 4(CAI · FLY · NIQ · PPLC) · 외딴 평봉 = 경계 행 7 + 면제 24 · 상쇄 배수 = UHAL · CRC(경계 앞) ·
  LUNR(면제) — 교정과 보충 뒤 0이 수용 기준이다.
- **판정(2026-10-21 · 묶음 1 끝)**: 감시 적중 0(10-20 15:30 `PriceIntegrityAlerter` — 면제 24 뺌) · 판정 대기 0(OBSERVED 없음 · 네 판정 행은
  10-21 14:00 사슬 뒤에도 `judged_at` 그대로 · `held_at` 10-21 0행) · GBCI 계수 전 봉 1. **프리셋**(`PresetResultSnapshotTest` · 10-15 · 09-16 · 03-02 ·
  교정 전 10-19 07:08 대 10-21 14:40 · 8,909 → 8,868행): 다른 63행이 전부 귀속된다 — 대상 행 15(SCCO 3 · CBSH 3 · CORZ 2 · CART 2 · CAI 2 · VG 2 · PPLC 1 ·
  RJF는 반박 뒤 REJECTED라 전후 같다) · 10-16 편입 22종목 44행(교정 전 스냅숏에서 10-15가 최신일이라 열린 편입의 하한을 접었다 — ADR-0135 · 데이터 몫 아님) ·
  10-15 봉 잠정값의 확정 4행(AMGN · MKL · DECK 둘 — 10-19 13:30 재수집이 거래량 지표를 고쳐 문턱을 넘음 · `alpaca-bars.md`) · 미설명 0.
- 되돌리려면: 경계 · 보충 파일을 비우고 `PriceRefutation.screen`을 우회한다. 지운 봉은 CSV에서 다시 넣는다.
