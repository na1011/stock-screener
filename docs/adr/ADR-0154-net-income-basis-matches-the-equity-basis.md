---
id: ADR-0154
status: 채택됨
date: 2026-09-15
applies_to:
  - "src/main/java/com/stockscreener/pipeline/datasource/us/SecEdgarColumn.java"
  - "src/main/java/com/stockscreener/pipeline/datasource/us/SecEdgarFacts.java"
---

# ADR-0154. 순이익의 기저를 자본의 기저에 맞춘다

## 결정

**뜻이 다른 후보들 위에서 첫값을 쓰지 않는다 — 컬럼을 갈라 들고 유도한다.** 두 자리에 건다.

1. **미국 `net_income`을 지배주주 귀속으로 통일한다.** 소스가 `NetIncomeLoss`를 내면 그것을,
   안 내면 **`ProfitLoss` − 비지배 귀속 순이익**을 쓴다.
2. **미국 `total_equity`를 비지배 포함으로 통일한다.** 소스가
   `StockholdersEquityIncludingPortionAttributableToNoncontrollingInterest`를 내면 그것을,
   안 내면 **`StockholdersEquity` + `MinorityInterest`**를 쓴다 — ADR-0153이 넣은
   `controllingEquity`의 **정확한 역**이다.

둘 다 안 되면 예전과 똑같이 그 컬럼의 첫값이 나간다 — **어느 갈래에서도 값을 잃지 않는다.**

## 왜 지금인가 — ADR-0153이 이 어긋남을 만들었다

`NET_INCOME`의 후보 셋은 **뜻이 서로 다르다**: `NetIncomeLoss`(지배주주 귀속) ·
`ProfitLoss`(비지배 포함) · `NetIncomeLossAvailableToCommonStockholdersBasic`(우선주 배당 차감).
`Selection.FIRST`라 앞의 것이 이긴다.

**ADR-0153 전에는 이것이 문제가 아니었다.** `NetIncomeLoss`가 없는 종목은 분자가
`ProfitLoss`(비지배 포함)이고 분모도 `TOTAL_EQUITY`(비지배 포함)라 **둘이 같은 기저**였다.
분모를 지배주주로 옮기자 **분자만 비지배 포함으로 남았다.**

**즉 이것은 소스의 결함이 아니라 내 변경이 만든 회귀다.** 「원장의 한계인가 구현의 한계인가」를
물으면 **구현**이고, 구현이면 한계가 아니라 **정합성 하락**이다.

## 규칙

- MUST: **직접 낸 값이 언제나 이긴다**(ADR-0104). 유도는 없을 때만이다.
- MUST: **`total_equity`는 `total_assets`·`total_liabilities`와 같은 기저로 둔다** —
  `자산 = 부채 + 자본`이 서야 하고, 국내 `자본총계`와도 같은 것이라야 시장 간 계산 방식이
  갈리지 않는다(spec §7).
- MUST: **연간과 Q4가 같은 기저여야 한다.** `EPS_TTM`처럼 네 분기를 더하는 지표가 서로 다른
  기저를 더하면 합이 무엇도 아니게 된다. `controllingIncomeQ4`가 그 자리다.
- MUST NOT: **`NetIncomeLossAvailableToCommonStockholdersBasic`에서 비지배분을 빼지 않는다.**
  그것은 **이미 지배주주 기준**이라 또 빼면 두 번 빠진다. `NET_INCOME_TOTAL`을 따로 든 이유가
  그것이다 — 값만 보고는 어느 태그가 이겼는지 알 수 없다.
- MUST NOT: **합계 태그와 구성요소를 함께 더하지 않는다.** `…AttributableToNoncontrollingInterest`가
  합계이고 `…Redeemable…`·`…Nonredeemable…`이 그 구성요소다. 구성요소 갈래는 **둘 다 있을
  때만** 성립한다 — 한쪽만 쓰면 과소 차감이라 지배주주 순이익이 실제보다 커진다.
- MUST NOT: **후보를 줄여 해결하지 않는다.** 줄이면 그 태그만 내는 종목의 순이익이 통째로
  빈다(ADR-0126 §결정이 입력 값을 버리는 것을 금지한다).
- MUST: **비지배 재료 셋은 `reportEvidence`에서 뺀다.** 그 컬럼만 든 얕은 보고서가 baseline을
  세우면 뒤따르는 진짜 10-K가 버려진다(ADR-0143 §버린 대안의 세율 재료와 같은 성질).
  `NET_INCOME_CONTROLLING`·`NET_INCOME_TOTAL`은 후보가 `NET_INCOME`의 부분집합이라
  증거 자격을 새로 만들지 않으므로 뺄 이유가 없다.

## 측정

**표본 47**(`.factcache/` · 기간을 맞춰 비교했다 — 태그별 「마지막 연간」끼리 비교하면
서로 다른 해가 붙어 `AVGO`가 292%로 보인다).

| 축 | 수 |
|---|---|
| `NetIncomeLoss`가 없어 `ProfitLoss`로 떨어지는 기간이 있는 종목 | **13** |
| 그중 **한 기간이라도 유도되는** 종목 | **8** |
| 같은 기간에 둘 다 있고 값이 다른 종목(= 실제 비지배지분) | **35** |

유도되는 8의 기간 비율: `CAT` 11/11 · `WEC` 9/9 · `F` 6/6 · `EL` 4/6 · `TEL` 3/3 ·
`AVAV` 2/7 · `AVGO` 2/4 · `MPLX` 2/2. 유도가 0인 5는 `FSV`·`JNJ`·`MORN`·`POR`·`VTRS`인데,
그중 **`MORN`·`POR`·`VTRS`는 비지배지분 관측이 0이라 폴백이 정답**이고 `JNJ`의 폴백 기간은
2010-01-03 이전이다. **실제로 안 닫히는 것은 `FSV` 하나**(분자 오차 8.32%)다.

**처음에 「유도 7」이라 적었고 틀렸다.** 비지배 태그 넷을 OR로 세어 `FSV`를 유도 가능으로
넣었는데, **코드는 합계 태그 하나 또는 상환가능·상환불능 *둘 다*를 요구**한다. 리플레이가
그것을 잡았다 — `FSV` FY2025가 `ProfitLoss` 그대로 나왔다. **코드의 조건을 그대로 재현해
세지 않으면 이런 수가 나온다.**

**MUST: 「얼마나 자주 발화하는가」와 「발화했을 때 얼마나 옳은가」를 갈라 적는다.** 두 수의
표본이 다르다 — **유도가 발화하는 칸은 답을 모른다**(`NetIncomeLoss`가 없어서 유도한다).
그래서 레시피의 정확도는 **답을 아는 379칸**에 같은 산수를 태워 쟀다: 유도가 **314칸에서 더
가깝고 19칸에서 멀어지며 절대오차 총합이 90.8% 준다**(100,352M → 9,234M). **그 379칸은
유도가 실제로 도는 칸이 아니다.**

**대표 8종목 드라이런으로 확인했다**(2026-09-15 · `교정 280 · 정정 0 · 채움 0`):
`F` FY2022 **−2,152M → −1,981M**(포드 지배주주 순손실 실제값) · `WEC` FY2025
**1,555.5M → 1,558.7M** · `MPLX` FY2025 **4,952M → 4,912M** · `CAT`·`EL` 소폭.
`AVGO`·`CPRT`·`TEL`은 안 바뀌었다 — 유도 재료가 없거나 폴백 기간이 창 밖이다.

같은 기간에 둘 다 오는 35에서는 **지금도 `NetIncomeLoss`가 이기고 있어 옳다.** 그 차이가
`LNG` 689% · `KKR` 159%까지 벌어지므로, **순서를 뒤집는 처방이었다면 재앙이었다.**

## 검증

```bash
# 유도 재료가 저장 컬럼으로 새지 않는다 — RawFinancialReport에 그 이름이 없어야 한다
rg "netIncomeNoncontrolling|NET_INCOME_NONCONTROLLING" \
  src/main/java/com/stockscreener/pipeline/datasource/RawFinancialReport.java

# 우선주 차감값에서 비지배분을 빼는 경로가 없다 — NET_INCOME을 피감수로 쓰는 뺄셈이 없어야 한다
rg "NET_INCOME\).*subtract|subtract.*NET_INCOME\)" \
  src/main/java/com/stockscreener/pipeline/datasource/us/SecEdgarFacts.java
```

<!-- 전제 후보. **아직 펜스가 아니다** — 전제 예산이 63/75초라 여유가 12초뿐이고,
     올리려면 무엇을 뺄지 함께 정해야 한다. 기준선은 재적재 뒤에 조용한 기계에서 잰다. -->
```bash
# 이 결정이 지키는 항등식: 자산 = 부채 + 자본. TOTAL_EQUITY가 지배주주분으로 떨어지면 깨진다.
psql -d stock_screener -At -c "SET statement_timeout='60s';
WITH us AS (
  SELECT f.* FROM financial_statement f
  JOIN universe_membership m ON m.stock_id = f.stock_id
   AND m.effective_to IS NULL AND m.universe_code = 'US_TOP1000'
  WHERE f.is_latest AND f.total_assets IS NOT NULL AND f.total_liabilities IS NOT NULL
    AND f.total_equity IS NOT NULL AND f.total_assets <> 0)
SELECT count(*) FROM us
WHERE abs(total_assets - total_liabilities - total_equity) / abs(total_assets) > 0.01"
```

**MUST NOT: 「FY 순이익과 네 분기 합의 차이」를 이 결정의 펜스로 쓰지 않는다.** 재보니
**315 종목연도**가 걸리는데 그 대부분은 기저가 아니라 **A7(분기 계열의 세대가 최신 FY와
다르다)**이다. 두 축이 한 수에 섞이면 어느 쪽이 움직였는지 못 가른다.

## 버린 대안

- **`NET_INCOME`의 후보에서 `ProfitLoss`를 뺀다** — 그 태그만 내는 종목의 순이익이 통째로
  빈다. 표본 47에서 **13종목**이 그 자리다.
- **`ProfitLoss`가 이긴 행을 버린다** — ADR-0126 §결정이 입력 값을 자동으로 버리는 게이트를
  금지한다. 그리고 오차가 대부분 0.25% 이하라 **버리는 쪽이 더 크게 틀린다.**
- **국내와 같은 모양으로 둔다** — `net_income`에 연결 총액을, `net_income_controlling`에
  지배주주분을. **분기점이 느는 게 아니라서 기각한 것이 아니다**(`FinancialMath`가 그 분기를
  **이미 갖고 있다**). **미국에서 성립하지 않아서**다: 표본 47의 연간 **827칸 중 총액
  태그(`ProfitLoss`)가 있는 것은 589(71.2%)**뿐이고 **9종목은 지배주주 태그만 낸다.**
  그 모양으로 두면 `net_income`이 **28.8%의 기간에서 빈다** — `NET_MARGIN`·`PER`이 그만큼
  꺼진다. 총액을 유도하려면 비지배 태그가 필요한데 그것이 없어서 이 갈래를 타는 것이다.
- **두 시장의 `net_income`이 지금 다른 개념을 담는다**(국내 총액 · 미국 지배주주). **각
  시장 안에서는 분자·분모가 맞으므로 지표는 옳다** — `ROE`가 국내는 총액/총자본, 미국은
  지배주주/지배주주 자본이다(실측: 국내 `total_equity_controlling` **0행**). 고르게 하려면
  **국내를 지배주주로 옮겨야** 하고 그것은 분모 소스 확인이 먼저다(`docs/progress.md` A1 꼬리).
- **미국일 때만 적용한다** — ADR-0153이 같은 이유로 기각했다. 술어를 시장으로 두면 DART에
  지배주주 자본을 매핑하는 날 분자를 함께 안 바꾸는 실수가 난다. **값의 유무로 둔다.**

## 뿌리는 하나다 — 뜻이 다른 후보 위의 `Selection.FIRST`

이 결함은 `NET_INCOME`만의 것이 아니다. **후보 목록의 members가 뜻이 다른데 첫값을 그대로
쓰는 컬럼이 넷 더 있다.** 표본 47을 기간 맞춰 전수로 쟀다(2026-09-15).
**그중 `TOTAL_EQUITY`를 이 결정이 함께 닫고, 셋이 남는다.**

| 컬럼 | 2순위로 떨어지는 종목 | 다리 태그로 유도 가능 | 다리가 없을 때 실제로 차이가 나는 비율 | 읽는 지표 |
|---|---|---|---|---|
| `CASH_AND_EQUIVALENTS` | 17 | 2 | **47.7%** | **없다** |
| `PROFIT_BEFORE_TAX` | 24 | 9 | 8.9% | 유효세율(ADR-0143·0144) |
| `OPERATING_CASH_FLOW` | 17 | 3 | 21.9% | `ACCRUAL_RATIO`·`OCF_5Y_SUM`·`FCF` |
| `TOTAL_EQUITY` **(이 결정이 닫음)** | 17 | 2 | 15.8% | `PBR`·`DEBT_RATIO`·`ALTMAN_Z`·`ROIC` |

**MUST NOT: 「다리 태그가 없다 = 차이가 0이다」로 읽지 않는다.** 그렇게 추측했다가
**대조군이 기각했다** — `CASH_AND_EQUIVALENTS`는 사용제한현금 태그가 없어도 두 태그가
**47.7%의 칸에서 실제로 다르다.**

**`CASH_AND_EQUIVALENTS`는 지금 아무 지표도 안 읽는다**(`indicator/` 전수 grep에서
`FinancialFigures`의 선언 한 줄뿐). **틀린 값이지만 지금 닿지 않는다** — 그래서 이번 칸이
아니다. 나머지 셋은 닿는다.

**`TOTAL_EQUITY`는 뜻이 가장 무겁고 발화는 가장 적다.** 2순위 `StockholdersEquity`는
**지배주주분만**이라 `총자산 = 총부채 + 자본`이 깨진다. `MINORITY_INTEREST`를 이미 걷고
있으므로 **총자본 = 지배주주 자본 + 비지배지분**이 선다 — 항등식 **997/1042(95.7%)**이고
상환가능 비지배지분을 더하면 오히려 **85.5%로 나빠진다.** **다만 폴백 17종목 중 유도 재료가
있는 것은 2뿐**이라, 이번 변경이 실제로 고치는 것은 그 2다. 나머지 15는 **원장이 재료를 안
줘서** 남는다 — 구현의 한계가 아니다.

## 결과

(재적재 뒤에 채운다)
