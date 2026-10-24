---
id: ADR-0098
status: 채택됨
date: 2026-08-30
applies_to:
  - "src/main/java/com/stockscreener/indicator/calculator/FinancialMath.java"
  - "src/main/java/com/stockscreener/indicator/FinancialFigures.java"
  - "src/main/java/com/stockscreener/pipeline/datasource/RawFinancialReport.java"
  - "src/main/java/com/stockscreener/pipeline/datasource/kr/DartFullAccountMapper.java"
  - "src/main/java/com/stockscreener/pipeline/batch/FullStatementRefillJob.java"
---

# ADR-0098. 주당 지표의 분자를 지배기업 소유주 귀속 순이익으로 바꾼다

## 결정

`financial_statement.net_income_controlling`을 더하고 **주당 지표 일곱**이 그것을 쓴다.
**`net_income`은 연결 총액 그대로 둔다** — 분모가 연결 전체인 비율 지표는 계속 그쪽을 쓴다.

전 종목 **분기**가 필요해져 `full-statement-refill`의 「전 종목 분기 거절」 가드에
`--all-quarters` 갈래를 더한다.

## 규칙

- **MUST: 짝으로 고른다.** 분모가 보통주 주식수면 분자도 지배주주 귀속(주당 지표), 분모가
  자본총계·자산총계·매출이면 분자도 연결 총액(비율 지표)이다. **순이익이 분모인 둘
  (`DIVIDEND_PAYOUT`·`EXEC_PAY_TO_NET_INCOME`)은 주당 쪽이다** — 배당과 임원보수를 받는 몫이
  지배주주의 것이고, DART가 계산해 주는 `(연결)현금배당성향`이 정확히 그 분모를 쓴다.
- **MUST: 폴백을 창 단위로 둔다**(`FinancialMath.sumControllingContinuingIncome` — F2b 뒤 이름 · 계속영업 분자 · ADR-0184). 소비처가 전부
  네 분기를 더하므로 **행마다 폴백하면 합이 두 기준의 혼합**이 되어 어느 쪽도 아닌 수가
  나온다. 넷이 모두 있을 때만 지배주주로 합하고, 하나라도 없으면 넷 다 연결 총액으로 합한다.
- **MUST NOT: `account_id`를 부분일치로 맞추지 않는다.** `ifrs-full_ProfitLoss`가
  `ifrs-full_ProfitLossAttributableToOwnersOfParent`의 **접두사**라 부분일치면 정반대 값을 문다.
- **MUST NOT: 계정명 폴백을 두지 않는다.** 표본 여덟이 한 계정을 **일곱 가지 이름**으로 부른다.
- ~~**MUST NOT: `net_income`의 뜻을 바꾸지 않는다.**~~ 바꾸면 비율 지표가 조용히 따라 움직이고
  두 값을 대조할 근거도 사라진다.
  - **2026-09-15 재판정 — 이 MUST NOT은 걷는다(ADR-0159).** 근거 둘을 따로 재봤다.
    **앞의 근거는 참이고, 바로 그것이 지금 일어나고 있다** — 미국 `net_income`이 지배주주라
    `ROA`·`NET_MARGIN`·`ACCRUAL_RATIO`가 **연결 분모에 지배주주 분자**를 얹고 있고,
    같은 공시에 두 태그가 다 있는 **618종목 중 118(19.1%)이 5% 초과 · 43(7.0%)이 20% 초과**,
    **부호가 뒤집히는 종목이 여럿**이다(`CWEN` +169M ↔ −231M · `INTC` −267M ↔ +26M).
    **뜻을 안 바꾸는 것이 비율 지표를 조용히 틀리게 두는 것이었다.**
    **뒤의 근거는 무효가 된다** — `net_income_controlling`을 두 시장에 채우므로
    **두 값을 대조할 근거가 오히려 처음으로 생긴다**(지금 미국은 그 컬럼이 0행이다).
  - **MUST: 대신 이것을 지킨다** — 뜻을 바꾸는 결정은 **그 컬럼을 읽는 소비자를 전수로 세고
    짝을 다시 적는다.** 이 문서가 그 셈을 안 해서 위 세 지표가 어긋난 채 남았다.
- **MUST: Q4 유도를 함께 본다.** TTM은 최근 4분기이고 12월 결산 종목의 그 창에 Q4가 반드시
  들어가는데, **Q4는 DART에 보고서가 없어 유도 행**이다. 주요계정 쪽 유도는 지분을 가르는
  계정이 없어 이 값을 원리적으로 못 만들므로 **전체본 어댑터가 `FY − (Q1+Q2+Q3)`로 만든다**
  (`deriveFourthQuarterControlling`). **이것을 빠뜨리면 이 결정 전체가 무력해진다** —
  실측으로 전 종목 적재를 마치고도 Q4 채움이 0%라 **PER이 한 종목도 안 바뀌었다.**
  폴백이 창 단위라 한 분기만 비어도 넷 다 연결 총액으로 간다.
- **MUST: 적재 뒤 `--job=backfill`을 잇는다.** `fillMissing`은 무효화를 부르지 않으므로
  백필이 없으면 `financial_statement`가 다 차도 화면이 한 글자도 안 바뀐다.
- **MUST NOT: `--all-quarters`를 기본값으로 만들지 않는다.** 명시적으로 적는 행위 자체가
  이 잡의 유일한 예산 통제다 — 로그의 `요청=N 실제콜=M`은 **다 태운 뒤에야** 나온다.

## 검증

```bash
./gradlew test --tests "*ControllingNetIncomeTest*" --tests "*FullStatementRefillJobTest*"
```

**대조군으로 확인했다**: `PerCalculator`를 연결 총액으로 되돌리면 `ControllingNetIncomeTest`
넷 중 **둘이 붉고** 폴백 경로 둘은 그대로 초록이다 — 그 둘이 실제로 분자를 재고 있다.

**소스가 계산해 준 값과 맞는지가 이 결정의 유일한 외부 대조군이다.** 삼성전자 FY2025에서
DART 배당표의 `(연결)현금배당성향` **25.10%** 대 우리 `DIVIDEND_PAYOUT` **25.096**이다
(바꾸기 전 24.571).

```premise 주당 지표의 분자가 지배주주 귀속으로 남아 있다
psql -d stock_screener -At -c "WITH d AS (SELECT max(trade_date) t FROM indicator_trade_date WHERE market_code IN ('KOSPI','KOSDAQ')),
src AS (SELECT NULLIF(replace(g.payload->>'thstrm', ',', ''), '-')::numeric v
        FROM governance_report_row g JOIN stock s ON s.id = g.stock_id
        WHERE s.ticker = '005930' AND g.endpoint = 'DIVIDEND' AND g.bsns_year = 2025
          AND g.payload->>'se' LIKE '%현금배당성향%'),
ours AS (SELECT iv.value v FROM (SELECT iv0.* FROM indicator_value iv0 JOIN stock s0 ON s0.id = iv0.stock_id WHERE s0.market_code IN ('KOSPI','KOSDAQ')) iv JOIN stock s ON s.id = iv.stock_id, d
         WHERE s.ticker = '005930' AND iv.indicator_code = 'DIVIDEND_PAYOUT'
           AND iv.trade_date = d.t)
SELECT CASE
  WHEN (SELECT count(*) FROM src) = 0 THEN '잴 것이 없었다 — 배당표에 성향 행이 없다'
  WHEN (SELECT count(*) FROM ours) = 0 THEN '잴 것이 없었다 — DIVIDEND_PAYOUT이 0행이다'
  WHEN abs((SELECT v FROM ours) - (SELECT v FROM src)) <= 0.05
    THEN 'OK 소스 ' || (SELECT v FROM src) || ' 대 우리 ' || round((SELECT v FROM ours), 3)
  ELSE '분모가 갈렸다 — 소스 ' || (SELECT v FROM src) || ' 대 우리 '
       || round((SELECT v FROM ours), 3) || ' (연결 총액이면 약 24.57)' END"
```

모집단을 삼성전자 하나로 좁힌 이유: **소스가 성향을 계산해 주는 종목이 대조군이 될 수 있는데,
그 값의 반올림 자릿수가 회사마다 달라** 전 종목으로 넓히면 의도한 동작에 우는 펜스가 된다.
삼성전자는 이 결정을 만든 실측의 출처이기도 하다.

```premise 정정공시가 지배주주 순이익을 되돌리지 않았다
psql -d stock_screener -At -c "SELECT CASE
  WHEN (SELECT count(*) FROM financial_statement WHERE is_latest AND revision_no > 0) = 0
    THEN '잴 것이 없었다 — 개정본이 하나도 없다'
  WHEN count(*) = 0 THEN 'OK 개정본이 지운 지배주주 순이익이 없다'
  ELSE count(*) || '행이 개정본에서 사라졌다 — full-statement-refill에 그 종목만 넘긴다: '
       || string_agg(DISTINCT s.ticker, ',') END
FROM financial_statement cur
JOIN financial_statement old ON old.stock_id = cur.stock_id
  AND old.fiscal_year = cur.fiscal_year AND old.fiscal_period = cur.fiscal_period
  AND old.revision_no < cur.revision_no AND NOT old.is_latest
JOIN stock s ON s.id = cur.stock_id
WHERE cur.is_latest AND s.market_code IN ('KOSPI', 'KOSDAQ')
  AND cur.net_income_controlling IS NULL AND old.net_income_controlling IS NOT NULL"
```

**축은 「개정본인가」가 아니라 「이전 리비전에 있던 값이 사라졌는가」다.** 처음에는 개정본에서
NULL인 행을 셌는데 **8행이 전부 거짓 발견이었다** — 재보니 소스 사정이었다: 그 분기의
연결재무제표가 아예 없어 별도로 폴백한 경우(SFA넥셀 2026 Q2·카카오뱅크 2026 Q1이 `status 013`),
그리고 보험사처럼 지분 구분을 원래 안 내는 회사(코리안리). **전자는 별도재무제표라 지배·비지배
구분이 원리적으로 없고, 둘 다 폴백이 옳은 동작이다.**

`FinancialWriter`가 개정본을 NULL로 쌓는 경로는 실재하므로(같은 파일 javadoc의 *"메우러 와서
지우고 가는 셈"*) 그것만 정확히 잡는다 — **옛 행에 값이 있었는데 최신 개정본에 없으면** 그것이
지운 것이다. 실측 0행.

## 배경

사용자가 삼성전자의 네이버 PER 11.53과 우리 10.32의 차이를 물었다. 성분이 셋이었고 **둘은
정의 차이, 하나가 결함**이다.

| 성분 | 우리 | 네이버 | PER 배수 |
|---|---|---|---|
| 주가 기준일 | 266,000(08-27) | 257,000(08-28) | ×1.035 — ADR-0042대로다 |
| 주식수 분모 | 보통주 5,846,278,608 | 보통주+우선주 | ×0.878 — 유지 결정 |
| **이익 귀속** | **연결 총액** | **지배주주** | **×0.979 — 이 결정** |

**결함인 이유는 짝이 안 맞기 때문이다.** 분모가 보통주 주식수인데 분자에 비지배지분의 몫이
들어 있었다. K-IFRS 1033이 주당이익을 지배기업 보통주 귀속 이익으로 정의하는 것이 그 이유다.

**크기가 종목마다 달라 랭킹이 기운다.** 배당표의 `(연결)당기순이익`(지배주주 귀속)과 우리
`net_income`을 전수 대조하니 **512쌍 중 96쌍이 5% 초과, 65쌍이 20% 초과**였다. 중앙값 배수는
1.0000인데 꼬리가 두껍다 — 다우데이타 5.48 · 한화 5.35 · 신세계 4.64 · HD현대 3.82로 **지주회사**다.
즉 「PER이 낮은 종목」 조건의 최상위를 지주회사가 점거하고 있었다.

**요약본으로는 못 얻는다.** `fnlttSinglAcnt`는 이미 받고 있어 거기 있으면 추가 호출이 0이었는데,
픽스처 실측에서 중복된 `당기순이익(손실)` 두 행의 **값이 같았다**(FY2025 `ord` 29·61 둘 다
45,206,805,000,000). 지분 구분이 아니다.

**전체 재무제표는 연간·분기 모두 8/8로 준다**(`DartControllingProfitProbeLiveTest`).
검산 `연결총액 = 지배주주 + 비지배`가 16/16 통과했고, 삼성전자 FY2025가
**44,260,956,000,000으로 배당표 값과 정확히 일치**한다.

## 측정 결과 (2026-08-30 · `trade_date=2026-08-27` · 유니버스 350)

**적재**: 요청 8,400 · 실제 콜 10,270(배율 1.22) · 22분 · 정정보류 10 · 불일치 4.
**TTM 4분기가 완비된 종목은 편입 349 중 287(82.2%)**이고 나머지는 창 단위 폴백으로 연결
총액을 쓴다. **모집단은 현재 편입 종목이다** — 이탈 종목은 `full-statement-refill`이 애초에
받지 않으므로(`resolveTargets`) 함께 세면 설계대로인 것이 결손으로 보인다.

**폴백으로 남는 62종목은 고칠 수 없는 것이다.** 갈라 보니 둘뿐이었다 — **소스가 그 분기의
연결재무제표를 안 준다**(별도로 폴백하는데 별도에는 지분 구분이 원리적으로 없다. SFA넥셀
2026 Q2·카카오뱅크 2026 Q1이 `status 013`), 그리고 **비지배지분이 없어 그 계정을 애초에 안
내는 회사**(코리안리 같은 보험사). **둘 다 연결 총액이 곧 지배주주 귀속분이라 폴백이 옳다.**

**정정공시 10종목은 별도 순서가 필요했다.** `full-statement-refill`은 `FILL_WITHOUT_REVISION`이라
정정을 만나면 채우지 않고 세므로(`정정보류`), **`financial-refill`이 먼저 가야 한다.**
`DartRestatementProbeLiveTest`(128콜)가 후보를 찾고 → `financial-refill`(정정 반영, 지표
무효화 종목당 약 1.2만 행) → `full-statement-refill`(정정보류 0) → `backfill --tickers`로
복구했다. **순서를 뒤집으면 저장 공시일이 아직 옛것이라 또 정정보류로 남는다**(`batch-load` skill).

| 종목 | PER 전 | PER 후 | 배수 |
|---|---|---|---|
| 다우데이타 | **0.426** | 2.282 | 5.36 |
| 한화 | 1.802 | 8.704 | 4.83 |
| HD현대 | 2.243 | 6.239 | 2.78 |
| 두산 | 30.614 | 53.448 | 1.75 |
| 삼성전자 | 10.318 | 10.390 | 1.007 |

**PER 1 미만 종목이 0이 됐다**(전에는 다우데이타 0.426). PER 3 미만은 6종목이다.

**필수 게이트 통과 수는 하나도 안 바뀌었다** — 린치 고속성장주 11 · 린치 대형우량주 11 ·
버핏 우량주 15 · 버핏+지배구조 0 · 유저커스텀 15로 전후가 같다. **필수 축이
`EPS_CAGR_5Y`(성장률이라 분자가 비례해 줄면 거의 안 변한다)와 `ROE_MIN_3Y`(안 바꾼 지표)이기
때문이다.** 바뀌는 것은 가중 조건(`PER`·`PEG`)이 정하는 **순위**다.

**그래서 임계값을 건드리지 않았고, 건드릴 근거도 없다.** ADR-0061 §37이 금지하는 것은
「결과 수를 근거로」 임계를 푸는 것이고, 이 변경은 임계의 **뜻을 출처(문헌)에 맞춘 것**이라
임계 자체는 그대로가 옳다.

**삼성전자로 성분이 닫힌 것을 확인했다.** 우리 PER 10.390(종가 266,000)을 네이버 기준일
종가 257,000으로 환산하면 **10.039**이고, 네이버 11.53과의 나머지 비율이
**EPS 25,601 대 22,292 = 1.1484**다 — **우선주 분모 하나로 설명된다.** 주가 성분과 이익 귀속
성분은 사라졌다.

**MUST NOT: FY 배수로 TTM을 예측하지 않는다.** 삼성전자 FY2025 배수는 1.0214인데 **TTM 배수는
1.0070**이었다(EPS 25,780 → 25,601). 창이 다르면 비지배 비중이 다르다.

## 버린 대안

- **자본총계도 지배주주지분으로 바꾼다** — 짝은 맞지만 `자산 = 부채 + 자본`이 깨진다.
  `SecEdgarColumn.TOTAL_EQUITY` javadoc이 실측으로 이미 기각해 뒀다(BRK·F 151행 중 50행이
  어긋났고 차이가 정확히 비지배지분). `DEBT_RATIO`·`ALTMAN_Z`가 함께 바뀐다.
- **주식수 분모에 우선주를 넣어 네이버와 맞춘다** — **네이버와 남은 차이 전부가 이것이고**
  실측으로 삼성전자 EPS 25,601 대 22,292 = **1.1484배**다. 그러나 **우리 시가총액이 보통주
  기준**이라 내부 일관성이 지금 쪽이 낫다(네이버는 시총 보통주 / EPS 우선주 포함으로 서로
  어긋난다). K-IFRS 1033도 보통주 기준이다. 2026-08-30 사용자 결정.
  **다시 열려면 시가총액 정의를 함께 본다** — 한쪽만 바꾸면 PER과 시총이 어긋나고,
  그 어긋남은 화면 어디에도 안 뜬다.
  - 2026-10-24(에이전트 재측정 — 사용자 결정 아님): 「내부 일관성이 지금 쪽이 낫다」는 분자가 보통주 몫일 때만 선다.
    지금 분자(지배주주 순이익 전체)는 우선주 몫을 포함해 PER = 보통주 시총 ÷ (보통주 + 우선주 몫 이익)이다 — 사용자에게 다시 묻는다.
- **주요계정의 둘째 `당기순이익(손실)` 행을 쓴다** — 추가 호출이 0이라 가장 쌌다. 위 실측으로
  닫혔다.
- **연간 지배주주 비율을 분기에 곱해 합성한다** — 콜이 4배 대신 그대로다. 그러나 phase-criteria
  P22 축② 「소스가 주지 않는 값을 합성하지 않았다」를 어긴다. Q4 유도는 **산술적 유도**지만
  이것은 **추정**이라 성격이 다르다.
- **미국 SEC도 같이 갈라 담는다** — spec §7의 시장 간 일관성 때문에 검토했으나 **지금보다
  나빠진다.** 미국 `net_income`은 이미 `NetIncomeLoss`(us-gaap 정의상 지배주주 귀속)를 첫
  후보로 써서 158,925행 중 149,761행이 **한 정의로 일관**돼 있고, 덮어쓸 경로가 없다
  (`fillMissing`은 `stored == null`일 때만 채우고 ADR-0056이 덮기를, ADR-0040이 지우고
  재적재를 못박아 기각했다). 갈라 담으면 옛 행과 새 행이 다른 정의인 혼합물이 된다.
  **국내가 이 결정으로 바뀌면 주당 지표의 분자는 두 시장이 자동으로 일치한다.**

## 결과

**쉬워지는 것**: 주당 지표가 회계기준의 정의와 같아진다. 지주회사의 PER이 제자리로 올라가
「PER 낮은 종목」 랭킹이 뜻을 되찾는다. 배당성향이 소스가 계산해 준 값과 맞아 **대조군이 생겼다**
— 그전에는 어긋나는 것이 정상이라 검증할 방법이 없었다.

**어려워지는 것**

- **화면 실적표와 EPS가 어긋난다.** `StockReader.financialsOf`의 「당기순이익」은 연결 총액인데
  `EPS_TTM`은 지배주주라 **EPS × 주식수가 화면의 순이익과 안 맞는다.** 이번 범위에 넣지 않았다
  (`FinancialRow` → `FinancialsPanel.vue`가 프론트 계약이라 화면 변경이 따라온다).
  ADR-0059가 지배하는 자리다.
- **NULL 하나가 두 뜻을 갖는다** — 「비지배지분이 없어 소스가 행을 안 낸다」와 「못 받았다」.
  스키마로는 못 가르므로 위 premise가 개정본으로 좁혀 센다.
- **분기 적재 비용이 상시화된다.** 이 잡이 이제 전 종목 분기를 도는 이유가 생겨, 새 종목이
  편입될 때마다 그 종목의 분기를 받아야 한다. 전 종목 6년이 **요청 8,400 · 실제 콜 10,270**
  (배율 1.22)이고 **22분**이다.
- **Q4 유도를 빠뜨리면 그 비용을 두 번 낸다.** 실제로 그렇게 됐다 — 1차 적재가 Q4를 못 채워
  같은 8,400요청을 다시 돌렸고, 하루에 **약 20,500콜**로 일일 한도(20,000) 경계에 닿았다.
  **적재 전에 Q4 채움을 먼저 확인한다**(§검증의 기간별 질의).
- **네이버와 여전히 12% 다르다 — 그 차이는 분모가 아니라 분자의 우선주 몫이다**(2026-10-24 재측정 · DART
  `fnlttSinglAcntAll` FY2025: 삼성전자 지배 NI ÷ 보통주 수가 원장 보통주 기본주당이익의 1.15배 · 현대차 1.29).
  이 문단은 처음에 「우선주 분모 결정의 결과이지 결함이 아니다」라 적었고 그 설명 위에서 아래 사용자 결정을 받았다 —
  설명이 거짓이었으므로 다시 묻는다(`docs/reload-gate.md` 국내 표 「주당 지표 분자가 우선주 몫을 포함한다」).

**되돌리려면**: 일곱 계산기의 `sumControllingNetIncome`·`controllingNetIncomeOf` 호출을
되돌리고 V38 컬럼을 지운다. 적재된 값은 `full-statement-refill`로 복구된다.
