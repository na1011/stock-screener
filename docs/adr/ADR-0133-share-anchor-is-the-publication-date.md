---
id: ADR-0133
status: 채택됨
date: 2026-09-09
applies_to:
  - "src/main/java/com/stockscreener/indicator/calculator/FinancialMath.java"
  - "src/test/java/com/stockscreener/indicator/calculator/FinancialSharesSplitTest.java"
---

# ADR-0133. 주식수 보정의 앵커를 기말에서 공시일로 옮긴다

## 결정

`FinancialMath.shiftToBar`의 앵커를 `period_end`에서 **`published_at`**으로 옮긴다.
그 결과 **나눗셈 갈래가 도달 불가**가 되므로 지운다 — 남기면 아무도 떨어뜨릴 수 없는
분기가 된다(ADR-0074가 금지한 형태).

**이것은 ADR-0096 §결정을 바꾸는 것이고, ADR-0126 §규칙이 *"전후 대조를 먼저 만들고 별도
결정으로 연다"*로 미뤄 둔 자리다.** 사용자가 2026-09-09에 **「전후 대조를 만들고 연다」**를
골랐고, 아래 §범위를 다시 쟀다가 §결과를 재서 실행했다.

## 왜 — 표지일 값에 기말부터 배수를 걸면 한 번 더 곱해진다

`dei:EntityCommonStockSharesOutstanding`은 **보고서 표지일** 기준이다. 그래서 기말 이후
공시 전에 일어난 기업행위가 그 수에 **이미 반영돼 있다.** 앵커가 기말이면
`corporateActionMultiple(period_end, bar)`가 그 구간을 한 번 더 곱한다.

## 범위 — 기제로 세면 부풀고, 값으로 세면 6이다

**MUST: 이 발화를 「원장에 `(period_end, published_at]` 사건이 있는 판」으로 세지 않는다.**
그렇게 세면 **미국 29종목 · 114판**이 나오는데 **대부분이 틀리지 않았다.** 값으로 확인하니
`NFLX`·`ORLY`·`LRCX`의 시가총액이 분할 후 주식수와 정합했다.

**이유는 한 공시가 두 종류의 행을 싣기 때문이다.**

| 행 | 기말~공시 간격 | 주식수 기준 | 골라지는가 |
|---|---|---|---|
| 당분기(as-filed, `revision_no=0`) | **25~37일** | **표지일** | **골라진다** |
| 전년 비교열(`revision_no=1`) | 389~402일 | 그 기말 | **안 골라진다** |

`publishedIndexOn`이 **`period_end`가 가장 큰 판**을 고르므로, 같은 날 실린 비교열은
당분기에 밀려 **한 번도 안 쓰인다.** 그래서 그 행의 사건은 값에 안 닿는다.

**선택 규칙을 맞춰 다시 세면 미국 6종목이고 국내는 0이다**(2026-09-09 실측).

| 종목 | 중복 배수 | 물던 구간 |
|---|---|---|
| `BKNG` | **25.0** | 2026-04-28 ~ 2026-08-03 |
| `POWL` | 3.0 | 2026-05-05 ~ 2026-08-03 |
| `MLI` | 2.0 | 2026-07-22 ~ 2026-09-08 |
| `APG` | 1.5 | 2025-09-03 ~ 2025-10-29 |
| `SNEX` | 1.5 | 2026-08-05 ~ 2026-09-08 |
| `AMCR` | 0.2 | 2026-02-04 ~ 2026-05-06 |

**`MLI` 2.0 · `SNEX` 1.5는 ADR-0126이 적어 둔 그대로이고, 넷이 더 있었다.** 그 문서가 둘만
센 것은 **최신 재무 한 판만** 봤기 때문이고, ADR-0129의 13종목은 **새로 연 구간만** 본 수다.

**값 쪽 대조도 같은 답을 낸다** — 공시일 경계에서 시가총액이 계단을 이루는 종목을 세면 같은
다섯에 `BMNR`·`AKTX`가 더 붙는데, **그 둘은 다른 결함이다**(주식수 축척 오태깅 · ADS 비율 —
ADR-0126).

## 규칙

- **MUST: 앵커는 공시일이다.** 공시일이 없는 행에서만 기말로 물러선다.
- **MUST: 주식수 없는 판에서 옛 기로 내려갈 때 공시일을 다시 본다.** `publishedIndexOn`은
  **첫 자리만** 고르고 그 뒤 루프는 **기말이 더 앞선** 판으로 가는데, 그런 판이 뒤 보고서의
  비교열로 실려 **봉보다 늦게 공시**돼 있을 수 있다. **이 결함은 이 결정이 만든 것이 아니라
  드러낸 것이다** — 실측으로 `LYFT`·`HOOD`·`TWLO`·`UHAL` 넷이고 봉이 공시일보다 최대 9개월
  앞이었다.
- **MUST: `shiftToBar`는 한 방향뿐이고, 봉이 앵커보다 앞이면 던진다.** 그 자리는
  **세 기제가 함께 보증한다** — `publishedIndexOn`의 첫 선택 · 위 내려가기 검사 ·
  ADR-0132의 컨텍스트 좁히기. **MUST: 셋 중 하나를 되돌리면 나눗셈 갈래를 함께 되살린다** —
  안 되살리면 봉보다 뒤에 공시된 주식수가 그대로 쓰여 배수만큼 틀린다. **던지게 둔 것이
  값을 냈다**: 그 예외가 위 넷을 찾았고, 안 던졌으면 조용히 미래 값을 썼다.
- **MUST NOT: 발화 범위를 원장 조인만으로 세지 않는다**(§범위). 선택 규칙(`period_end` 최대)을
  빼면 **다섯 배로 부풀고**, 그 목록으로 처방을 정하면 멀쩡한 종목을 고치러 간다.
- **이 결정 자체는 유니버스 순위를 안 움직인다** — `US_TOP1000`의 시총은 `JdbcStoredShareCounts`가
  `financial_statement`를 직접 읽어 만들어 `sharesOf`를 안 탄다(ADR-0099).
  - **그 자리는 2026-09-09에 함께 고쳤다(ADR-0135).** 같은 앵커 교체를 `JdbcStoredShareCounts`의
    기업행위 하한에도 적용했고, **선택 규칙에 맞춰 세니 편입 중 두 종목**이 그 상태였다 —
    `MLI` 2.0배 · `SNEX` 1.5배. 여기 §범위의 여섯과 **집합이 다르다**(그쪽은 `shiftToBar` 축이다).
    **MUST NOT: 두 목록을 개수로 견주지 않는다** — 우연히 같아도 아무것도 증명하지 않는다.

## 검증

```bash
./gradlew test --tests "*FinancialSharesSplitTest*"
```

**대조군이 그 안에 있다** — `doesNotDoubleCountActionsBetweenPeriodEndAndPublication`은
앵커를 기말로 되돌리면 값이 **10배**가 되어 붉는다.

**`shiftToBar`의 「도달할 수 없다」를 실측으로 바꿨다**(2026-09-10). 이 결정 **전에** 돈 전 종목
백필(`batch_execution` id=354 · 2026-09-09 17:15, 이 커밋은 18:14)이 그 가드로 **4종목을 떨어뜨렸다**
(`HOOD` 봉 2021-10-29 ↔ 공시 2022-08-03 · `LYFT` · `TWLO` · `UHAL`). 같은 넷을 같은 `as-of`로 다시
돌려 **성공 4 · 실패 0 · 가드 0건**을 확인했다(id=369 · 61초). **넷 다 지금 보존 창 255일자를 온전히
갖고 있어 결손도 없다.** 그 실패 행은 `resolved`로 표시돼 있고, 무엇이 닫았는지가 이 문단이다.

```premise 기말과 공시일 사이의 기업행위가 시가총액에 두 번 세이지 않는가
psql -d stock_screener -At -c "WITH act AS (SELECT DISTINCT m.stock_id FROM universe_membership m JOIN universe u ON u.code = m.universe_code WHERE m.effective_to IS NULL AND u.is_active), win AS (SELECT market_code, min(trade_date) lo, max(trade_date) hi FROM indicator_trade_date GROUP BY 1), r AS (SELECT f.stock_id, s.ticker, f.period_end, f.published_at, w.lo, w.hi FROM financial_statement f JOIN act ON act.stock_id = f.stock_id JOIN stock s ON s.id = f.stock_id JOIN win w ON w.market_code = s.market_code WHERE f.shares_outstanding IS NOT NULL AND f.published_at <= w.hi), picked AS (SELECT r.*, (SELECT min(r2.published_at) FROM r r2 WHERE r2.stock_id = r.stock_id AND r2.period_end > r.period_end) blocker FROM r), live AS (SELECT p.ticker, p.stock_id, greatest(p.published_at, p.lo) f0, least(coalesce(p.blocker - 1, p.hi), p.hi) f1, exp(sum(ln(ca.ratio))) mult FROM picked p JOIN corporate_action ca ON ca.stock_id = p.stock_id AND ca.status = 'APPLIED' AND ca.effective_date > p.period_end AND ca.effective_date <= p.published_at GROUP BY 1, 2, 3, 4) SELECT CASE WHEN (SELECT count(*) FROM r) = 0 THEN '잴 것이 없었다 — 주식수를 실은 재무 판이 하나도 없다' WHEN (SELECT count(*) FROM live WHERE f1 >= f0) = 0 THEN 'OK 기말과 공시일 사이에 기업행위가 낀 판이 창 안에서 골라지지 않는다' ELSE 'OK 그 자리가 ' || (SELECT count(DISTINCT ticker) FROM live WHERE f1 >= f0) || '종목이다(앵커가 공시일이라 두 번 안 세인다): ' || (SELECT string_agg(DISTINCT ticker, ' ') FROM live WHERE f1 >= f0) END"
```

**이 전제는 「값이 틀렸는가」를 안 잰다 — 발화 모집단이 얼마나 되는지를 신고한다.** 앵커를
되돌리는 변경은 코드에서만 드러나고, 그때 이 목록이 곧 피해 목록이 된다. **0건도 정상이므로
`OK`로 접는다** — 모집단이 비는 것과 코드가 틀린 것은 다르다.

## 버린 대안

| 대안 | 왜 버렸나 |
|---|---|
| **표지일을 소스에서 받아 컬럼을 늘린다** | 오차 창이 표지일~공시일 며칠까지 좁아지지만 스키마가 늘고 재적재가 따라붙는다. **그 며칠 안에 기업행위가 든 사례가 실측 0건**이라 지금은 값이 같다 — 생기면 다시 연다 |
| **나눗셈 갈래를 남겨 둔다** | 두 기제가 도달을 막으므로 **떨어질 수 없는 분기**가 된다(ADR-0074). 대신 그 자리에서 던지게 해 **선택 규칙이 깨지면 조용히 틀린 값 대신 실패**가 나게 했다 |
| **원장 조인으로 센 29종목을 전부 고친다** | §범위가 그 답이다 — 대부분이 안 틀렸고, 고치면 **멀쩡한 값을 깬다** |
| **`sharesOf` 대신 함의 주식수(`net_income/eps`)를 쓴다** | ADR-0125가 그 축을 이미 쟀다 — 적자·`eps=0`에서 판정불가라 모집단이 갈린다 |

## 결과

**실측 (2026-09-09)**

| 축 | 값 |
|---|---|
| 좁힌 판(6종목) | `written_row_count` **7,474** |
| `BKNG` 최대 시가총액 | **3,899.58B → 181.04B** |
| `POWL` 최대 | 35.20B → **11.73B** |
| `MLI` 최대 | 30.73B → **15.57B** |
| `SNEX` 최대 | 13.75B → **11.02B** |
| `AMCR` 최소 | 3.41B → **16.96B**(0.2배로 눌려 있었다) |
| `APG` | 14.59~22.21B → **13.99~21.31B** |
| 전 종목 판(고침 뒤) | 22분 42초 · 1,350종목 · 실패 0 · **쓰기 0행** |

**마지막 줄이 이 결정의 범위를 못박는다** — 전 종목을 다시 돌려 **한 행도 안 바뀌었다는 것**이
「움직이는 것은 위 6종목뿐」의 대조군이다.

**통과 집합과 점수 갈림은 안 움직였다**(12조합 일치 · 점수 갈림 4 그대로). 그 넷의 원인은
ADR-0132 §결과가 적은 둘이고 **주식수 축이 아니다.**

**쉬워지는 것** — 기말과 공시일 사이에 분할이 낀 종목의 시가총액·회전율·주당 지표가
그날의 실제 규모를 낸다. `BKNG`은 **3.9조 달러**로 저장돼 있었고 어떤 펜스에도 안 걸렸다
(계단이라 출력 축에 안 걸리고, 주식수 자체는 제출자가 옳게 낸 값이라 입력 축에도 안 걸린다).

**딸려 나온 것 — 내려가기의 look-ahead.** 앵커를 옮기며 세운 예외가 **기존 결함 하나를
드러냈다**(§규칙). 그 넷은 예외가 **보존 창 밖 봉**(2020~2023)에서 났던 것이라 고친 뒤
창 안 값은 **한 행도 안 바뀌었다** — 즉 지금 화면에는 안 닿아 있었고 **다음에 창이 그쪽으로
넓어지면 닿는다.**

**어려워지는 것**

- **남는 오차 창이 표지일~공시일 며칠이다.** 그 안에 기업행위가 든 사례는 **실측 0건**이다.
- **`FinancialSharesSplitTest`의 나눗셈 시험이 사라졌다.** 그 갈래가 없어졌기 때문이고,
  대신 「봉보다 뒤에 공시된 판은 안 골라진다」가 그 자리를 지킨다.

**MUST: base 판 뒤에 파생층을 창 전체로 다시 돌린다** — 백분위는 돌린 날짜에만 다시 나므로
base만 돌리면 나머지 날짜가 옛 모집단으로 매겨진 채 남는다(ADR-0130 §규칙 · 실측 5분 17초).

**되돌리려면** 앵커를 `asOf.periodEnd()`로 되돌리고 나눗셈 갈래를 함께 되살린다.
**되돌리는 순간 위 6종목이 다시 배수만큼 틀리고 아무 에러도 안 난다.**

**대조군은 `~/Library/Application Support/stock-screener/dumps/anchor-20260909-before.psv`다**
— 그 6종목의 **변경 직전** 지표 값 183,849행(`ticker|indicator_code|trade_date|value` ·
2025-09-03~2026-09-08)이고, §범위 표의 여섯과 티커 집합이 정확히 같다. **에러가 안 나는 회귀라
되돌린 뒤 틀렸는지를 이 파일 말고는 판정할 수단이 없다.**
