---
id: ADR-0151
status: 채택됨
date: 2026-09-14
applies_to:
  - "src/main/java/com/stockscreener/pipeline/datasource/us/SecEdgarFacts.java"
---

# ADR-0151. Q4 유도는 컬럼마다 넷이 같은 태그에서 왔을 때만 뺀다

## 결정

`FY − Q1 − Q2 − Q3`로 4분기를 만들 때 **컬럼마다** FY와 분기 셋이 **같은 태그에서 뽑혔는지**
묻고, 아니면 그 컬럼만 값을 내지 않는다.

## 규칙

- **MUST: 판정을 컬럼별로 한다.** 통화 축(ADR-0110)은 판 전체가 갈리지만 **태그는 컬럼 하나만
  갈릴 수 있다** — 한 컬럼 때문에 나머지 일곱을 버리면 안 된다.
- **MUST: 넷 중 하나라도 그 컬럼의 태그를 모르면 접는다.** 모르는 것을 「같다」로 읽으면 이
  판정이 **아무것도 막지 않는다.**
- **MUST: 기간별로 고른 태그를 유도 자리까지 들고 간다.** `chooseTags`는 `revisionsOf` 안에서
  계산되던 것을 `build`로 올렸다 — **그 사실은 거기서만 알 수 있다.**
- **MUST NOT: 음수를 지우거나 0으로 깎는 것으로 대신하지 않는다.** 결과가 **그럴듯한 양수로
  앉는 경우**를 못 잡고, 값을 버리는 게이트라 ADR-0126 §결정에 걸린다.

## 검증

```bash
./gradlew test --tests "*SecEdgarFinancialAdapterTest.doesNotSubtractAcrossATagSwitch*"
```

**대조군으로 확인했다.** 게이트를 되돌리면 붉고, 넣으면 초록이며, **같은 유도에서 매출은 그대로
나온다** — 판정이 컬럼별이라는 증거다.

```premise 유도된 4분기에 음수 유량이 남아 있지 않다
psql -d stock_screener -At -c "SELECT CASE
  WHEN count(*) = 0 THEN '잴 것이 없었다 — 미국 4분기 행이 없다'
  WHEN count(*) FILTER (WHERE revenue < 0 OR cost_of_sales < 0) = 0 THEN 'OK 음수 매출·매출원가 0건'
  ELSE '유도 4분기에 음수 매출 ' || count(*) FILTER (WHERE revenue < 0) || '건 · 음수 매출원가 ' || count(*) FILTER (WHERE cost_of_sales < 0) || '건 — 뜻이 다른 두 수를 뺐다'
  END FROM financial_statement f JOIN stock s ON s.id = f.stock_id
  WHERE s.market_code = 'US' AND f.is_latest AND f.fiscal_period = 'Q4'"
```

## 배경

`chooseTags`는 **기간 단위로** 태그를 고정한다(같은 기간을 판마다 다른 태그로 내는 일이 실재해서
그렇게 했다 — ADR-0102). **그런데 기간이 다르면 다른 태그가 이길 수 있다.** 그때 이 뺄셈은
뜻이 다른 두 수를 빼고, 결과가 음수로 드러나기도 하지만 **그럴듯한 양수로 앉기도 한다.**

**실측(2026-09-14 · `is_latest` 분기 행):**

| 축 | US 전 상장 | 열린 `US_TOP1000` | KOSDAQ | KOSPI |
|---|---|---|---|---|
| Q4 `revenue` 음수 | **253** | 45 | **9** | **1** |
| Q4 `cost_of_sales` 음수 | **131** | 41 | 0 | 0 |

**매출 음수가 원가 음수보다 크다.** 그리고 그것은 결측이 아니다 —
`OperatingMarginCalculator`가 `revenue.signum() <= 0`을 **합계에만** 걸므로, 음수 한 분기가
섞이면 값이 사라지는 게 아니라 **마진이 부풀어 그럴듯하게 나간다.** `NET_MARGIN`·`PSR`·
`FCF_MARGIN`도 같은 TTM 매출을 쓴다.

## 버린 대안

- **음수면 그 행을 버린다** — 값을 버리는 게이트이고(ADR-0126), 무엇보다 **양수로 앉은 오염을
  못 잡는다.** 원인이 아니라 증상에 처방하는 것이다.
- **FY의 태그로 분기를 강제한다** — 그 태그가 분기 창에 팩트를 안 냈으면 분기가 통째로 빈다.
  지금은 분기가 **자기 태그로 정상 저장되고** Q4 유도만 접힌다.
- **통화 축(ADR-0110)처럼 판 단위로 접는다** — 한 컬럼이 갈렸다고 여덟을 다 버리는 것이라
  멀쩡한 유도를 잃는다.

## 결과

**닫힌 것** — 미국 Q4 유도에서 태그가 갈린 컬럼의 뺄셈. 그 수는 재적재 뒤에 다시 센다.

**안 닫힌 것 — 국내다.** `DartFinancialAdapter.deriveFourthQuarter`는 **다른 코드**이고 이 결정이
안 닿는다. **같은 뺄셈이므로 같은 결함이 있을 수 있고, 국내 Q4 매출 음수 10건이 그 신호다.**
국내는 계정명 매핑이라 「태그」에 해당하는 축이 무엇인지부터 정해야 한다 — **다음 칸이다.**
