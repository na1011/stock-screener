---
id: ADR-0146
status: 채택됨
date: 2026-09-14
applies_to:
  - "src/main/java/**/datasource/us/SecEdgarColumn.java"
  - "src/main/java/**/datasource/us/SecEdgarFacts.java"
---

# ADR-0146. 매출원가는 첫 후보가 아니라 가장 큰 후보를 고른다

## 결정

**`SecEdgarColumn.COST_OF_SALES`의 선택 전략을 `FIRST`에서 `LARGEST`로 바꾼다.**
후보 목록은 그대로다(`CostOfGoodsAndServicesSold` · `CostOfRevenue` · `CostOfGoodsSold` ·
`CostOfSales`).

**그리고 후보를 더하지 않는다** — 늘릴 후보를 전수로 찾았고 **실측이 기각했다**(§버린 대안).

## 규칙

- **MUST NOT: 이것을 「값을 버리는 게이트」로 읽지 않는다.** ADR-0126 §결정이 *"입력 값을
  자동으로 버리는 게이트를 만들지 않는다"*이고 ADR-0125가 그런 축 다섯을 실측으로 기각했다.
  이것은 **이미 존재하는 후보 중 하나를 고르는 규칙**이고, ADR-0089 §규칙이 *"컬럼마다 후보
  선택 전략을 정한다"*를 이미 요구한다.
- **MUST: `LARGEST`로 안 닫히는 행은 손으로 고치고 근거를 둘 이상 댄다**(ADR-0126 §규칙).
  후보가 하나뿐인 자리는 이 결정으로 안 고쳐진다.
- **MUST NOT: 후보를 더할 때 「그 태그가 오는가」만 보지 않는다.** 정하는 것은 **기존 후보와
  뜻이 같은가**이고, `LARGEST` 아래에서는 **정의상 더 큰 태그**가 `REVENUE`의 주세·판매세
  사고와 같은 자리가 된다. 위험한 태그는 `candidates`가 아니라 `fallbackCandidates`에 넣어야
  경쟁에서 빠진다 — `competesForLargest`가 `!fallbackCandidates.contains(tag)`다.
- **MUST NOT: 「뒤에 붙이면 값이 있던 종목은 안 바뀐다」를 근거로 쓰지 않는다.** 그 논거는
  `FIRST` 전제이고 이 결정 뒤에는 **거짓**이다 — `coveringTag`가 `picksLargest()`일 때
  **모든 후보를 끝까지 훑는다.**

## 검증

```premise 매출원가·매출·매출총이익의 항등식이 어긋난 행이 늘지 않았다
psql -d stock_screener -At -c "
-- 소스가 셋을 다 주는 행에서 revenue − cost_of_sales = gross_profit 이 성립해야 한다.
-- 어긋나면 셋 중 하나가 틀린 태그에서 왔다는 뜻이고, 어느 것인지는 이 질의가 못 가른다.
-- 모집단을 US_TOP1000 열린 편입 · FY · is_latest 로 박는다 — 창을 바꾸면 수가 갈린다.
WITH us AS (SELECT DISTINCT m.stock_id FROM universe_membership m
            WHERE m.universe_code='US_TOP1000' AND m.effective_to IS NULL),
v AS (SELECT f.stock_id FROM financial_statement f JOIN us ON us.stock_id=f.stock_id
      WHERE f.is_latest AND f.fiscal_period='FY'
        AND f.gross_profit IS NOT NULL AND f.cost_of_sales IS NOT NULL AND f.revenue IS NOT NULL
        AND abs((f.revenue-f.cost_of_sales)-f.gross_profit) > 0.10*abs(f.gross_profit)),
pop AS (SELECT count(*) n FROM financial_statement f JOIN us ON us.stock_id=f.stock_id
        WHERE f.is_latest AND f.fiscal_period='FY'
          AND f.gross_profit IS NOT NULL AND f.cost_of_sales IS NOT NULL AND f.revenue IS NOT NULL)
SELECT CASE
  WHEN (SELECT n FROM pop) = 0
    THEN '잴 것이 없었다 — 셋을 다 가진 행이 하나도 없다'
  WHEN (SELECT count(*) FROM v) <= 69
    THEN 'OK 항등식 위반 ' || (SELECT count(*) FROM v) || '종목 (기준선 69 이하)'
  ELSE '항등식 위반이 ' || (SELECT count(*) FROM v) || '종목으로 늘었다 — 기준선은 69다.'
       || ' 후보 목록이나 선택 규칙을 바꾼 뒤라면 그 변경이 원인이다(ADR-0146)'
  END"
```

```bash
./gradlew test --tests "*SecEdgarFinancialAdapterTest*"
```

## 배경

`CAT`의 매출원가가 **FY2023 42,767M → FY2024 33M → FY2025 49M**이었다. 세 해 다
`revision_no=0`이고 10-K이므로 **정정이 아니라 연도별로 이긴 태그가 갈린 것**이다.

탐침으로 원인을 확정했다. `CAT`은 `CostOfGoodsAndServicesSold`를 **8팩트**만 내고(최신
2025-12-31 = 49M) 진짜 총원가는 `CostOfRevenue` **128팩트**(최신 2026-06-30 = 24.1B)다.
**후보 순서에서 앞의 것이 이기므로 첫값 규칙이 작은 쪽을 집었다.**

**전수로 크기를 쟀다.** 소스가 매출·매출원가·매출총이익을 셋 다 주는 FY 행이 **3,909개**이고
항등식 `매출 − 매출원가 = 매출총이익`이 **3,675(94.0%)에서 1% 이내**로 성립한다. 어긋나는
**155행·69종목**을 방향으로 가르면 **원가 과소 133행 · 과대 22행(16종목)**이다.

## 버린 대안

**크기 축을 새로 둔다(이웃 회계연도 대비 자릿수 · 매출 대비 하한).** ADR-0126 §규칙이
*"MUST NOT: 값을 버리는 판정축을 새로 만들지 않는다. 다섯을 실측으로 재고 다섯 다 기각했다"*
이고, 그 다섯에 **자기 이력 중앙값 대비 배수**와 **절대 문턱**이 그대로 들어 있다.
**그 기각은 아직 선다** — 이 결정은 값을 버리지 않는다.

**후보를 더한다 — 실측이 기각했다.** `GROSS_MARGIN` 결측 380을 전 회계연도로 갈라
**278(한 번도 없음) + 102(과거엔 있었음)**로 나누고, 380 전수에 후보 아홉을 물었다
(2026-09-14 · 약 4,500콜).

| | 종목 |
|---|---|
| (a) 아홉 태그가 전부 404 | **239** |
| (b) 우리 후보 밖 태그만 있거나 그쪽이 더 최신 | **64** |
| 우리 후보가 최신인데 저장은 빔 | 75 |

**(b) 64의 51을 만드는 태그가 `CostOfGoodsAndServiceExcludingDepreciationDepletionAndAmortization`
이고 정의상 D&A를 뺀 원가다.** 그 51 중 **같은 기간에 D&A 조각을 함께 내는 회사는 10뿐이고
41은 조각이 없다.** 조각이 있는 10에서 **D&A ÷ 제외원가**를 직접 쟀다:

`PACS` 1.8% · `MTZ` 2.4% · `CASY` 2.6% · `ARMK` 2.9% · `CCK` 4.2% · `APPF` 5.1% ·
`ATR` 12.0% · `EAT` 14.7% · **`PEG` 27.4% · `PNW` 48.5%** (중앙 5.1%)

**그냥 더하면 41종목의 매출총이익률이 원가의 2~48%만큼 과대해지고**, `버핏 우량주`가
`GROSS_MARGIN >= 40`을 가중치 2로 쓴다. **안전하게 더할 수 있는 것은 `CostOfServices` 하나이고
그것이 여는 것은 3종목**인데, 상품·용역 원가를 **부분으로 나눠 내는 회사에서 `LARGEST`가
부분을 집을 위험**은 재지 않았다. 3종목을 위해 안 잰 하방을 사지 않는다.

**`LARGEST`가 악화시키는 자리를 전수로 물었다.** 과대 16종목 중 후보가 둘 이상 오는 것은
**6**이고, 그중 다섯(`ATI`·`MELI`·`MSFT`·`NTNX`·`APTV`)은 **지금 고르는 것이 이미 최댓값이거나
기간이 안 겹쳐** 선택이 안 바뀐다. 실제로 커지는 것은 `ENS` 하나인데 그 종목의 어긋남은
**매출 쪽**이다 — FY2018 2,582M → FY2019 **187M** → FY2020 216M → FY2021 2,978M이고,
**`REVENUE`는 이미 `LARGEST`인데도 그렇다.** 그 축은 이 결정의 범위 밖이고 수만 남긴다.

**전기가스(`SIC:49`)는 후보로 안 열린다.** 원가를 **성분으로 쪼개** 낸다
(`CostOfPurchasedPower` · `CostOfNaturalGasPurchases` · `CostOfDomesticRegulatedElectric` …).
여는 길은 후보가 아니라 **유도**이고 ADR-0104와 같은 형태의 별개 기제다.

## 결과

**재적재 실측(2026-09-14 · `financial-refill --universe=US_TOP1000 --financial-years=11` ·
`batch_execution` id 431).** **12분 4초 · 정정 5 · 채움 0 · 교정 442.**
채움이 0인 것이 요점이다 — **이 결정은 값을 채우지 않고 고른 값을 바꾼다.**

| 재는 것 | 전 | 후 |
|---|---|---|
| 항등식 위반(10% 초과) 행 / 종목 | 155 / 69 | **138 / 66** |
| 매출원가율 <2% / <10% (분모 601) | 11 / 22 | **9 / 20** |
| 최신 FY `cost_of_sales` non-null | 601 | 601 |
| 국내 재무 행 수 · `is_latest` | KOSDAQ 8180/8170 · KOSPI 9305/9261 | **그대로** |

**대조군이 닫혔다.** `CAT`의 매출원가가 **FY2024 33M → 40,199M · FY2025 49M → 44,752M**로
FY2023의 42,767M과 자릿수가 맞는다.

**`CNC`는 안 닫힌다 — 그것이 이 결정의 경계다.** 후보 넷 중 `CostOfGoodsAndServicesSold`
하나만 오고 그 값이 매출의 **1.4%**(194,777M 대 2,670M)라 **고를 대상이 없다.** 그런 자리의
처방은 ADR-0126 §규칙의 **손으로 고치되 근거를 둘 이상**이고, 이번에 안 했다.

**옆 컬럼은 안 움직였다.** 최신 FY의 매출·영업이익·총자산·총부채·유동부채·영업활동현금흐름
non-null이 전부 전과 같다 — `reportEvidence` 경로로 판 선택이 갈릴 수 있다는 것이
이 변경에서는 **실현되지 않았다.**
