---
id: ADR-0104
status: 채택됨
date: 2026-08-31
applies_to:
  - "src/main/java/com/stockscreener/indicator/calculator/FinancialMath.java"
  - "src/main/java/com/stockscreener/pipeline/datasource/us/SecEdgarColumn.java"
  - "src/test/java/com/stockscreener/indicator/calculator/OperatingIncomeDerivationTest.java"
---

# ADR-0104. 영업이익을 영업수익 − 영업비용으로 유도해 두 시장을 대칭으로 만든다

## 결정

소스가 영업이익을 직접 내지 않으면 **영업수익에서 영업비용을 빼서 만든다**(2026-08-31 사용자
결정). K-IFRS의 정의를 그대로 쓴다.

**원자료는 적재 계층, 유도는 지표 계층이다.** `financial_statement`에 재료 컬럼 여덟을 더하고
(V40) 유도는 `FinancialMath.operatingIncomeOf`가 한다.

## 규칙

- **MUST: 소스가 낸 값이 언제나 이긴다.** 유도는 그것이 없을 때만이다. 국내는 이 갈래로만
  떨어지므로 **한 값도 안 바뀐다.**
- **MUST: 재료가 하나라도 없으면 그 갈래를 건너뛴다.** 없는 것을 0으로 두면 이자비용이 0인
  은행이 되어 영업이익이 부풀고, **그 값은 그럴듯해서 안 드러난다.**
- **MUST NOT: 유도를 적재 계층으로 내리지 않는다.** `financial_statement`는 원자료만 담고
  판단은 지표 계층이 한다(`FreeCashFlow` javadoc · phase-criteria §P22 축②).
- **MUST NOT: 실적 원수치 화면에 유도값을 넣지 않는다.** `StockFinancialsAssembler`가 답하는
  것은 **「회사가 무엇을 신고했나」**다(P13). 거기에 유도값을 넣으면 화면이 공시와 어긋난다.
- **MUST: 갈래 순서를 바꾸지 않는다.** 은행형 → 보험형 → 매출총이익 → 총원가. 뒤 갈래가
  앞 갈래보다 넓어 순서를 뒤집으면 은행이 「매출 − 총원가」로 떨어진다.

## 검증

```bash
./gradlew test --tests "*OperatingIncomeDerivationTest*"
```

**소스 자신의 신고값으로 유도식을 검증했다.** 둘 다 있는 **3,258행**에서 오차 1% 이내가
**2,713(83.3%)** · 10% 이내가 **2,887(88.6%)** · 부호가 다른 것이 **110(3.4%)**이다.

```premise 미국 금융업이 영업이익 결손에서 비금융보다 크게 뒤처지지 않는가
psql -d stock_screener -At -c "SELECT CASE
  WHEN (SELECT count(*) FROM universe_membership WHERE universe_code = 'US_TOP1000'
        AND effective_to IS NULL) = 0
    THEN '잴 것이 없었다 — US_TOP1000 편입이 0건이다'
  WHEN max(rate) FILTER (WHERE fin) IS NULL THEN '잴 것이 없었다 — SIC가 붙은 금융업이 없다'
  WHEN max(rate) FILTER (WHERE fin) <= max(rate) FILTER (WHERE NOT fin) * 6
    THEN 'OK 금융업 ' || round(max(rate) FILTER (WHERE fin), 1) || '% · 비금융 '
         || round(max(rate) FILTER (WHERE NOT fin), 1) || '%'
  ELSE '금융업 결손율 ' || round(max(rate) FILTER (WHERE fin), 1) || '%가 비금융 '
       || round(max(rate) FILTER (WHERE NOT fin), 1) || '%의 6배를 넘는다 — 유도가 안 걸렸다'
  END
FROM (
  SELECT s.sic_code ~ '^6' AS fin,
         100.0 * count(*) FILTER (WHERE a.null5 > 0) / count(*) AS rate
  FROM (SELECT stock_id, count(*) FILTER (WHERE rn <= 5 AND oi IS NULL) AS null5
        FROM (SELECT f.stock_id,
                COALESCE(f.operating_income,
                  (f.interest_and_dividend_income + f.noninterest_income)
                    - (f.interest_expense + f.noninterest_expense),
                  f.revenue - f.benefits_losses_and_expenses,
                  f.gross_profit - f.operating_expenses,
                  f.revenue - f.costs_and_expenses) AS oi,
                row_number() OVER (PARTITION BY f.stock_id ORDER BY f.period_end DESC) AS rn
              FROM financial_statement f
              JOIN universe_membership m ON m.stock_id = f.stock_id
                AND m.universe_code = 'US_TOP1000' AND m.effective_to IS NULL
              WHERE f.is_latest AND f.fiscal_period = 'FY') t
        WHERE rn <= 5 GROUP BY stock_id) a
  JOIN stock s ON s.id = a.stock_id
  WHERE s.sic_code IS NOT NULL GROUP BY 1) g"
```

**모집단에서 뺀 것**: `sic_code`가 없는 종목. SIC를 못 받은 종목은 업종을 물을 수 없어
비율의 분모가 될 수 없다. **그 목록이 자라는 자리는 `company-profile`**이다.
**배수 6은 유도 전 상태(70.0% 대 11.4% = 6.1배)를 겨우 넘는 선**으로 잡았다 — 유도가
되돌아가면 걸리고, 지금(38.2% 대 8.7% = 4.4배)은 통과한다.

## 배경

**같은 이름의 조건이 시장마다 다른 것을 재고 있었다.** K-IFRS는 은행도 영업이익을 표시해
국내 활성 유니버스의 금융업 **50종목이 FY 영업이익을 100%** 갖고 그중 **23종목**이
`OPM_MIN_5Y` 값을 낸다. 반면 US-GAAP은 `OperatingIncomeLoss`를 금융업에 잘 쓰지 않아
미국 금융업 **170 중 119(70%)**가 판정불가였다 — phase-criteria §P27 축①·②가 금지하는 모양이다.

**「금융업은 영업이익이 원리적으로 없다」로 읽은 것이 오판이었다.** 막힌 119종목 전량에
물으니 **117(98.3%)**이 세전이익 태그를, **83(69.7%)**이 은행형 4요소 또는 보험형 구성요소를
갖는다. 막은 것은 소스가 아니라 **우리 태그 매핑이 제조업 모양**이라는 것이었다.

**이 지표를 쓰는 것이 넷이다** — `OPM_MIN_5Y` · `PROFIT_STREAK_Y` · `ROIC_MIN_3Y` ·
`OPERATING_MARGIN`. 넷 다 유도를 타게 했다.

## 버린 대안

- **세전이익을 대용으로 쓴다** — 커버리지가 117/119로 가장 넓지만 금융업의 「영업이익」이
  비금융과 **다른 것을 재게 된다**(영업외손익·중단영업이 섞인다). 국내와도 정의가 갈려
  대칭을 수치가 아니라 이름으로만 맞추는 셈이다.
- **양 시장에서 금융업을 함께 제외한다** — 이것도 대칭이고 가장 싸지만, **지금 국내에서
  살아 있는 23종목을 죽인다.** 버핏·린치 프리셋이 금융업을 아예 안 보게 된다.
- **적재 계층에서 영업이익 컬럼에 유도값을 써 넣는다** — 원자료와 합성이 한 컬럼에 섞여
  `StockFinancialsAssembler`의 원수치 노출이 공시와 어긋난다. `FreeCashFlow` javadoc이
  같은 이유로 이미 금지했다.

## 결과

**쉬워지는 것**: `OPM_MIN_5Y` 산출 가능이 **690 → 752**, `PROFIT_STREAK_Y`가 **636 → 705**다.
금융업 결손율이 **70.0% → 38.2%**로 떨어져 비금융(8.7%)과의 격차가 6.1배에서 4.4배로 좁았다.

**어려워지는 것**

- **한 종목의 5년 창에 두 정의가 섞일 수 있다** — 신고값과 유도값이 함께 든 종목이 **13개**다
  (전부 신고 714 · 전부 유도 62). `OPM_MIN_5Y`가 5년 최솟값을 쓰므로 그 13종목에서는
  최솟값이 정의 차이에서 나올 수 있다. **작지만 0이 아니다.**
- **유도값이 신고값과 완전히 같지는 않다** — 둘 다 있는 3,258행에서 10% 밖이 371(11.4%),
  부호가 다른 것이 110(3.4%)이다.
- **여전히 65종목이 결손이다.** 재료 자체가 없는 금융업이 남는다.

**되돌리려면**: `operatingIncomeOf`가 `figures.operatingIncome()`만 돌려주게 한다. 컬럼 여덟은
남지만 아무도 안 읽는다. 그러면 `OperatingIncomeDerivationTest`의 넷이 붉는다.
