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
- ~~**MUST NOT: 유도를 적재 계층으로 내리지 않는다.**~~ · ~~**MUST NOT: 실적 원수치 화면에
  유도값을 넣지 않는다.**~~ — **이 둘은 ADR-0157이 다시 열었다(2026-09-15).**
  **축이 거칠었다**: 「적재/지표」로 갈랐는데 실제 축은 **「공시에 있는 개념을 복원하는가 /
  없는 개념을 만드는가」**였다. 이 규칙을 지키면 `ZION`의 매출이 화면에 **662M**(소스가 좁은
  하위 항목에 쓴 값)으로, `RF`가 **「안 신고했다」**로 남는다 — **목적이 「화면이 공시와
  어긋나지 않게」인데 지키는 쪽이 어긋난다.** 새 경계와 실측은 ADR-0157에 있다.
  **이 ADR의 §결정(2026-08-31 사용자 결정)과 아래 나머지 규칙은 그대로다** — 은행 영업이익은
  **공시에 없는 개념**이라 여전히 지표 계층이다.
- **MUST: 갈래 순서를 바꾸지 않는다.** 은행형 → 보험형 → 매출총이익 → 총원가. 뒤 갈래가
  앞 갈래보다 넓어 순서를 뒤집으면 은행이 「매출 − 총원가」로 떨어진다.

## 검증

```bash
./gradlew test --tests "*OperatingIncomeDerivationTest*"
```

**소스 자신의 신고값으로 유도식을 검증했다.** 둘 다 있는 **3,258행**에서 오차 1% 이내가
**2,713(83.3%)** · 10% 이내가 **2,887(88.6%)** · 부호가 다른 것이 **110(3.4%)**이다.

```premise 영업이익을 소비하는 계산기가 전부 유도를 탄다
# MUST: 최신 거래일을 **시장으로 좁힌다**. 대상이 US_TOP1000 인데 전역 max 를 쓰면,
# 미국이 휴장이고 국내가 개장인 날(예: 2026-09-07 노동절)에 국내 날짜가 뽑혀
# 미국 종목이 한 행도 없고 '잴 것이 없었다'로 거짓 발화한다. ADR-0128 의 펜스가
# 같은 자리에서 같은 수정을 했다(하한을 시장마다 잡는다).
psql -d stock_screener -At -c "WITH d AS (SELECT max(trade_date) t FROM indicator_trade_date WHERE market_code = 'US'),
mem AS (SELECT DISTINCT um.stock_id AS sid FROM universe_membership um
        WHERE um.universe_code = 'US_TOP1000' AND um.effective_to IS NULL),
have AS (SELECT iv.indicator_code AS code, iv.stock_id AS sid FROM indicator_value iv
         CROSS JOIN d JOIN mem m ON m.sid = iv.stock_id WHERE iv.trade_date = d.t),
gap AS (SELECT sid FROM have WHERE code = 'ROIC_MIN_3Y'
        EXCEPT SELECT sid FROM have WHERE code = 'ROIC_PRETAX'),
q AS (SELECT g.sid, count(*) FILTER (WHERE t.rn <= 4 AND t.der IS NOT NULL) der4
      FROM gap g JOIN (
        SELECT f.stock_id AS sid2,
               COALESCE(f.operating_income,
                 (f.interest_and_dividend_income + f.noninterest_income)
                   - (f.interest_expense + f.noninterest_expense),
                 f.revenue - f.benefits_losses_and_expenses,
                 f.gross_profit - f.operating_expenses,
                 f.revenue - f.costs_and_expenses) AS der,
               row_number() OVER (PARTITION BY f.stock_id ORDER BY f.period_end DESC) AS rn
        FROM financial_statement f WHERE f.is_latest AND f.fiscal_period <> 'FY') t
        ON t.sid2 = g.sid GROUP BY 1)
SELECT CASE
  WHEN (SELECT count(*) FROM have WHERE code = 'ROIC_MIN_3Y') = 0
    THEN '잴 것이 없었다 - 최신 거래일에 편입 종목의 ROIC_MIN_3Y가 한 행도 없다'
  WHEN count(*) = 0 THEN 'OK 유도로 분기 넷을 채울 수 있는데 ROIC_PRETAX가 없는 종목 0'
  ELSE '영업이익 유도를 안 타는 계산기가 있다: ' || count(*) || '종목이 3년 최저 ROIC는 내면서 현재 ROIC를 못 낸다'
  END
FROM q WHERE der4 = 4"
```

**모집단을 「분기 넷을 유도로 채울 수 있는 종목」으로 좁힌 것이 이 펜스의 전부다.**
`ROIC_MIN_3Y`가 `ROIC_PRETAX`의 부분집합이어야 한다고 읽으면 안 된다 — **둘은 읽는 행이
다르다**(연간 3개 대 분기 TTM). 좁히지 않고 세면 **지금도 붉는다**: 실측으로 그 차집합이
49종목인데 그중 10은 분기 재무가 아예 없는 20-F·40-F 제출자(10-Q를 법적으로 안 낸다)이고,
16은 유도해도 재료가 부족하며, 2는 분기가 넷이 안 된다. **의도한 동작에 우는 펜스는
침묵보다 나쁘다.**

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

**이 지표를 쓰는 것이 아홉이다.** 착수 시 넷이었고(`OPM_MIN_5Y` · `PROFIT_STREAK_Y` ·
`ROIC_MIN_3Y` · `OPERATING_MARGIN`) 그 뒤 `PROFITABLE_QUARTERS_4` · `ALTMAN_Z` ·
`ALTMAN_Z_DOUBLE_PRIME`이 붙었다. **`ROIC_PRETAX`·`OPERATING_INCOME_GROWTH` 둘은 raw를 읽고
있었고 2026-09-07에 유도로 옮겼다** — 이 목록이 낡으면 새 계산기가 조용히 raw를 읽는다.

## 버린 대안

- **매출총이익 − 판관비(SG&A) − 연구개발비로 유도한다** — NKE·JNJ·IBM·LLY·MRK·PFE처럼
  `OperatingIncomeLoss`도 `OperatingExpenses`도 `CostsAndExpenses`도 내지 않는 회사가 있고
  (US_TOP1000에 38종목), 그들은 `SellingGeneralAndAdministrativeExpense`를 낸다.
  **재보니 기준 미달이다.** 신고 영업이익과 함께 가진 대조군 53종목에서 **1% 이내가 28%(15/53) ·
  10% 이내가 49% · 중앙오차 12.0% · 부호 반전 3**이다 — 위 §검증이 이 결정을 정당화한 실측
  (**1% 이내 83.3% · 10% 이내 88.6% · 부호 반전 3.4%**)보다 **세 배 나쁘다.** 영업비용에는
  구조조정·손상·무형자산상각처럼 SG&A·R&D 밖의 항목이 들어가기 때문이다.
  커버리지도 작다 — 3년 연속을 채우는 대상이 38 중 **14**뿐이다.
  **새 컬럼 둘 · 마이그레이션 · 미국 재무 재적재를 치르고 얻는 것이 14종목이고 정확도는
  기준의 1/3이라, 값이 없는 채로 두는 쪽이 낫다.**
  - **세전이익 − 영업외손익**도 함께 쟀다. 1% 이내 68%로 더 정확하지만 재료가 85종목 중
    31에만 있고, 아래 「세전이익을 대용으로 쓴다」의 기각 사유(영업외손익·중단영업이 섞인다)가
    그대로 걸린다.

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
