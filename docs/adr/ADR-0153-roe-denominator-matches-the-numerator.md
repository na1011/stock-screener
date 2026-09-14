---
id: ADR-0153
status: 채택됨
date: 2026-09-15
applies_to:
  - "src/main/java/com/stockscreener/indicator/calculator/FinancialMath.java"
  - "src/main/java/com/stockscreener/indicator/calculator/RoeCalculator.java"
  - "src/main/java/com/stockscreener/indicator/calculator/RoeMin3yCalculator.java"
  - "src/main/java/com/stockscreener/pipeline/datasource/us/SecEdgarColumn.java"
---

# ADR-0153. ROE의 분모를 분자와 같은 기저로 맞춘다

## 결정

**지배주주 자본을 `total_equity_controlling`으로 갈라 담고 `ROE`·`ROE_MIN_3Y`가 그것을 분모로
쓴다.** 없으면 `total_equity`로 물러난다.

## 규칙

- **MUST NOT: `TOTAL_EQUITY`의 후보 순서를 뒤집어 해결하지 않는다.** `PBR`·`DEBT_RATIO`·
  `ALTMAN_Z`는 **총자산·총부채와 같은 기저라야 항등식이 선다** — 그쪽은 총자본이 옳다.
  `ROIC`도 분자가 영업이익(비지배 이전)이라 총자본이 맞다. **갈라 담는 것이 요점이다.**
- **MUST: 없으면 총자본으로 물러난다.** 국내는 분자도 비지배 포함(`ifrs-full_ProfitLoss`)이고
  자본총계도 비지배 포함이라 **지금도 기저가 맞는다** — 그래서 DART 매핑을 안 했다.
- **MUST NOT: 「미국이면」으로 가르지 않는다.** 술어는 **값의 유무**다. DART에 지배주주 자본을
  매핑하는 날 국내는 **분자도 함께** 지배주주로 바꿔야 하고, 그러지 않으면 기저가 반대로 어긋난다.

## 검증

```bash
./gradlew test --tests "*RoeEquityBasisTest*"
```

```premise 지배주주 자본이 있는 미국 종목이 줄지 않았다
psql -d stock_screener -At -c "SELECT CASE
  WHEN count(*) = 0 THEN '잴 것이 없었다 — 최신 FY가 하나도 없다'
  WHEN count(*) FILTER (WHERE total_equity_controlling IS NULL AND total_equity IS NOT NULL) <= 75
    THEN 'OK 지배주주 자본 결손 ' || count(*) FILTER (WHERE total_equity_controlling IS NULL AND total_equity IS NOT NULL) || '종목'
  ELSE '지배주주 자본이 없어 총자본으로 물러나는 종목이 ' || count(*) FILTER (WHERE total_equity_controlling IS NULL AND total_equity IS NOT NULL) || '이다(착수 시 62) — 그만큼 ROE의 분모가 분자와 기저가 다르다'
  END FROM (
  SELECT DISTINCT ON (f.stock_id) f.total_equity, f.total_equity_controlling
  FROM financial_statement f
  WHERE f.is_latest AND f.fiscal_period = 'FY' AND f.stock_id IN (
    SELECT stock_id FROM universe_membership
    WHERE universe_code = 'US_TOP1000' AND effective_to IS NULL)
  ORDER BY f.stock_id, f.period_end DESC) t"
```

## 배경

**둘 다 첫 후보라 이 불일치는 예외가 아니라 기본값이었다.**

| 컬럼 | 첫 후보 | 뜻 |
|---|---|---|
| `NET_INCOME` | `NetIncomeLoss` | **지배주주 귀속** |
| `TOTAL_EQUITY` | `StockholdersEquityIncludingPortionAttributableToNoncontrollingInterest` | **비지배 포함** |

**표본 실측**(`companyfacts` 직접 대조 · 2026-09-15):

| 종목 | 비지배 비중 | ROE 지금 → 맞춤 |
|---|---|---|
| `LNG` | **46.1%** | 59.13% → **109.74%**(+50.61%p) |
| `KKR` | **59.9%** | 3.06% → **7.63%**(+4.57%p) |
| `KMI` · `GM` | 3.8% · 2.6% | +0.37%p · +0.12%p |
| `AIG`·`JNJ`·`F`·`BRK-B` | ~0% | 변화 없음 |

**전수**(재적재 뒤 · `US_TOP1000` 최신 FY): **932종목이 지배주주 자본을 갖고 62가 폴백**이다.
그리고 **비지배 비중이 1%를 넘는 것이 179 · 10%를 넘는 것이 60**이다 — **그 179의 `ROE`가
그만큼 틀려 있었다.** `ROE`는 `버핏 우량주`의 **필수 게이트**다.

## 버린 대안

- **`TOTAL_EQUITY`의 후보 순서를 뒤집는다**(지배주주를 먼저) — 한 줄이지만 `PBR`·`DEBT_RATIO`·
  `ALTMAN_Z`·`ROIC`의 분모가 함께 바뀐다. 그쪽은 **총자본이 옳다.**
- **분자를 `ProfitLoss`(비지배 포함)로 바꾼다** — `EPS`·`PER`이 같은 값을 읽는데
  **주당 지표는 보통주주 몫이라야** 한다. 분자가 옳고 분모가 틀렸다.
- **미국일 때만 적용한다** — 술어를 시장으로 두면 DART에 지배주주 자본을 매핑하는 날
  **분자를 함께 안 바꾸면 기저가 반대로 어긋난다.** 값의 유무로 두면 그 실수가 안 난다.

## 결과

**닫힌 것** — `ROE`·`ROE_MIN_3Y`의 기저 불일치. 실측으로 `KKR` 5.09% → **13.01%**,
`LNG` 32.35% → **57.06%**(연간 기준. 화면의 TTM 값은 분자 기간이 달라 수가 다르다).

**남는 것**

- **폴백 62종목**은 소스가 `StockholdersEquity`를 안 내 **여전히 총자본을 분모로 쓴다.**
  그중 비지배지분이 큰 회사가 있으면 `ROE`가 계속 낮게 나온다 — **그 수는 못 잰다**(지배주주
  자본이 없어 비교 대상이 없다).
- **국내는 안 바뀌었다.** 분자·분모가 둘 다 비지배 포함이라 지금 맞지만, **`net_income_controlling`이
  이미 저장돼 있으므로** 둘을 함께 지배주주로 옮기는 것이 다음 칸이다.
- **`ROE`가 100%를 넘는 미국 종목이 25**다. 자본이 거의 0인 회사(`GDDY` 7M · `MTD` 13M)의
  성질이고 **비지배 비중은 0**이라 이 결정과 무관하다 — 다만 **필수 게이트가 그 값을 통과시킨다**는
  사실은 별도 축이다.
