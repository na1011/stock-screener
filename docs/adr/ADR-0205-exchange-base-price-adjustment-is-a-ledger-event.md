---
id: ADR-0205
status: 채택됨
date: 2026-11-01
applies_to:
  - "src/main/java/**/pipeline/collector/KrBasePriceEvents.java"
  - "src/main/java/**/pipeline/datasource/kr/KrSpinOffResolver.java"
  - "src/main/java/**/pipeline/collector/PriceRefutation.java"
  - "src/main/java/**/pipeline/collector/PriceWriter.java"
  - "src/main/java/**/pipeline/datasource/kr/DataGoKrPriceAdapter.java"
  - "src/test/resources/db/testmigration/V70_13__base_price.sql"
---

# ADR-0205. 거래소가 기준가를 조정한 날은 원장의 사건이다

## 결정

국내 관문 켬(`stockscreener.gate.kr`)에서: 봉의 거래소 기준가(공공데이터포털 `clpr − vs` · V70_13 `price_daily.base_price`)가 직전 거래일
종가와 다르면 그날이 기업행위의 효력일이고 가격 배수 m = 직전 종가 ÷ 기준가다. 그 사건을 **원장(`corporate_action`)에 소스 공시로 적는다**
(`KrBasePriceEvents` · `source_form = '기준가'`) — 재구축 · 주식수 보정 · 억제 · 화면이 원장 하나를 본다. 원장 행이 거래소와 모순되면(그날
거래소가 조정하지 않았다) 그 행을 보류한다(`PriceRefutation` 국내 갈래 · 미국 ADR-0181과 같은 판정 대기).

- **사건이 아닌 날**(기준가가 배수의 증거가 아니다): 시장 구분(V70_13 `market_category`)이 바뀐 날(코넥스 → 코스닥 이전상장) · 직전 봉이
  정지 봉이고 기준가 = 시가(재개 · 재상장의 경매가) · 직전 거래일 봉이 없다(봉 구멍) · `vs = 0`(기준가 = 종가)인데 뒤 150일 안 주식수 계단이
  m × 0.995 이상으로 확인하지 않는다.
- **종류**: m &lt; 1은 병합(비율 m · 상장일 그날 — 같은 날 전환 신주가 섞여 주식수 배수는 병합 비율이 아니다) · 그날 주식수가 m과 0.5% 안으로
  5% 넘게 늘었으면 분할(비율 = 주식수 배수 · 상장일 그날) · 나머지 m &gt; 1은 무상 요소 `BONUS_ISSUE`(무상증자 · 주식배당 · 유상증자의
  IAS 33 무상 요소 — 비율 m).
- **신주 상장일은 저장하지 않는다** — 판독기가 짓는다(`IndicatorDataReader.derivedListings`): 권리락 다음 날 ~ 150일 안 주식수가 비율 ×
  0.995 이상으로 오른 첫 계단 · 계단 하나에 사건 하나(상장일을 아는 행의 계단을 먼저 비우고 권리락 순) · 아직 없으면 상장 전(마지막 봉
  다음 날) · 창이 끝나도 없으면 효력일로 접는다.
- **인적분할**(정지 뒤 기준가 = 시가 · 그날 주식수가 바뀜): 존속회사 DART `cmpDvDecsn`(365일 앞 ~ 그날) 중 신주 상장 예정일이 그날이고
  신설회사가 재상장하는 행 하나 → 분할기일 · 그날 새로 상장된 종목(그날 시장 목록에 있고 전 거래일 목록에 없음) 중 DART 기업개황 설립일
  (`est_dt`)이 분할기일 ~ 7일 뒤인 회사 하나가 신설회사 → `SPIN_OFF`(s = 존속 주식수 배수 · v = 재상장일 존속 ÷ 두 회사 시가총액 ·
  ADR-0168 국내 규칙). 하나가 아니면 종류 미상(판정 대기 · ADR-0055 억제). 그날 원장 행이 이미 판정 · 적용됐으면 소스에 다시 묻지 않는다.
- 시세 쓰기기는 관문 켬에서만 두 칸을 쓴다(UPSERT가 덮는다) · 관문 꺼짐 출력은 이 결정 전과 같다.

## 규칙

- MUST: 거래소 기준가 사건을 같은 실행의 DART · 휴리스틱 기록보다 먼저 적는다 — 날짜의 정본이라 같은 날 뒤 기록은 덮지 못하고(IGNORED)
  며칠 어긋난 기록은 근접 중복(판정된 행과의 충돌과 같은 취급)으로 빠진다.
- MUST NOT: 신설회사를 이름으로 고르지 않는다 — 결정의 신설회사명과 고유번호 목록 법인명이 13 중 10에서 달랐다(「엘엑스홀딩스」 대
  「LX홀딩스」 · 「에스케이스퀘어」 대 「SK스퀘어」).
- MUST NOT: 기계가 원장 행을 거절(REJECTED)하지 않는다 — 반박은 보류(OBSERVED)다. 증거 쪽이 진짜 사건에서도 틀릴 수 있다(진짜 사건인데
  `vs = 0`인 날 7).
- MUST: 관문 날 국내 시세 재적재는 봉이 있는 국내 종목 전부(`--tickers`)로 돈다 — 티커 없는 재적재는 지금 편입만 받아 보존 창 과거 편입의
  기준가가 비고, 그 종목의 사건이 안 보인다.

## 버린 대안

| 대안 | 근거(실측 2026-11-01) |
|---|---|
| 계수를 원장 밖에서 기준가로 짓는다(v2) | 원장을 직접 읽는 소비자(주식수 보정 · 거버넌스 유도 · 공통 이익 유도 · 화면 · ADR-0055)가 파생 사건을 못 본다 · 코넥스 이전상장 5 · 정지 뒤 경매가 · 인적분할 재상장 7을 못 가른다(반증 두 렌즈) |
| 주식수 계단으로 사건을 「확인」될 때만 넣는다(v3) | 식별력이 없다 — 아무 날이나 250일 안 31%에 계단이 있다 · 확인 안 된 13 중 12가 진짜 사건 · 실시간 약 41거래일 늦다 |
| 상장일을 원장에 적는다(v4 첫 판) | 평일에 권리락을 처음 볼 때는 계단이 없고, 같은 날 원장 행은 다시 안 쓰여 상장일이 영영 빈다(반증 두 렌즈 · 지금 국내 무상증자 상장일 NULL 0) |
| 반박을 자동 거절한다 | 미국과 기준이 갈리고, 거절 행은 다시 열리지 않으며 근접 중복을 영구히 막는다(반증 밖 렌즈) |
| 신설회사 = 그날 신규 상장이 정확히 하나 | 효성 2024-07-29 산일전기 공모 · 솔브레인 2020-08-06 이루다 · GS리테일 2024-12-23 넷이 같은 날 상장한다 |

## 검증

- 단위 · 통합: `./gradlew test --tests '*KrBasePriceEventsTest*'`(사건 갈래 · 반박 보류 · 상장일 짓기 · 관문 꺼짐).
- 재료 탐침: `-Dprobe.spinoffs=티커:재상장일:전거래일,… ./gradlew liveTest --tests '*KrCapitalEventProbeLiveTest.probeSpinOffChildByEstablishment'`
  · 기준가 스윕 `KrCapitalEventProbeLiveTest.sweepBasePriceAdjustments`(`-Dprobe.tickersFile` · `-Dprobe.out`).

```premise 관문 켬 국내 봉에 기준가가 비지 않았다
psql -d stock_screener -At -c "SELECT CASE
  WHEN NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_name = 'price_daily' AND column_name = 'base_price')
    THEN 'OK 잴 것이 없었다 — 관문 전(V70_13 전)'
  ELSE (SELECT CASE WHEN count(*) FILTER (WHERE p.base_price IS NULL) = 0
      THEN 'OK 국내 봉 ' || count(*) || '개 전부 기준가가 있다'
      ELSE count(*) FILTER (WHERE p.base_price IS NULL) || '/' || count(*) || '개 국내 봉에 기준가가 없다 — 그 봉의 거래소 조정이 원장에 안 오른다. 관문 날 재적재가 그 종목을 빠뜨렸거나 꺼진 jar가 봉을 썼다'
      END FROM price_daily p JOIN stock s ON s.id = p.stock_id WHERE s.market_code IN ('KOSPI', 'KOSDAQ'))
  END"
```

## 결과

**0콜 드라이런(2026-11-01 · 소스 전수 스윕 `adj2.tsv` 490종목 × 2020-01-01~ · 종목당 1콜 · 잘림 0 · 운영 SELECT)**: 기준가 ≠ 직전 종가
299일(사건일 봉 저장 297) — 사건 256(무상 요소 231 · 분할 · 병합 25) · 원장에 그대로(같은 날 APPLIED) 118 · 새로 적힘 136 · OBSERVED를
거래소 값으로 덮음 1(290650 2026-10-26 ×1.99) · 사람 판정 보존(007340 UNSUPPORTED · 020560 UNSUPPORTED) · 짓지 않음: 재개 경매 18 · 시장
이동 5 · 대비 0 미확인 1(000720) · 구조 사건 17 중 원장 있음 10 · 인적분할 6을 짓고 · 1(042670 2021 분할합병 — `cmpDvDecsn` 013)은 판정
대기. 원장 반박 1행(알테오젠 id 10 2022-10-07 · 진짜 권리락 10-25 ×1.1989가 새로 적힌다). 상장일 짓기: 원장 상장일 있는 무상증자 92 대조
92/92 · 새 무상 요소 140 중 계단 찾음 129 · 상장 전 9(2026-07 뒤) · 접힘 2. 신설회사 찾기(탐침 13 · DART · 시세 실호출): 12가 하나로 갈림 ·
1(207940 2025-11-24 — 같은 날 삼양바이오팜 인적분할)은 후보 둘이라 판정 대기.

잰 빗나감: 정지 뒤 기준가 = 시가 35 중 1이 기계적 조정이다 — 정지 중 유상증자 권리락 대한전선 001440 2022-02-07(기준가 1,675 = 시가 ·
×1.0507 · 권리락 산식이 1차 발행가 약 1,485원으로 재현된다) · 코다코 둘은 정지 봉 위 병합이라 원장 행이 있다. 이 갈래를 기준가로 열면 경매가
32를 배수로 읽으므로 열지 않는다 — 처방은 DART 「유상증자 결정」 산식 재현(재지 않음).

재지 않았다: 재적재 뒤 실제 계수 계열(관문 날) · 2020년 전 봉(국내 봉이 2020-01-02부터라 없다) · 새 무상 요소 87의 사건별 정체(유상증자로
묶어 판정하지 않았다 — 거래소가 조정한 사실이 사건이다).
