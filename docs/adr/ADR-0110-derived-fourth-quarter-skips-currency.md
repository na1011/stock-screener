---
id: ADR-0110
status: 채택됨
date: 2026-09-01
applies_to:
  - "src/main/java/com/stockscreener/pipeline/datasource/kr/DartFinancialAdapter.java"
  - "src/main/java/com/stockscreener/pipeline/datasource/kr/DartFullStatementAdapter.java"
  - "src/main/java/com/stockscreener/pipeline/datasource/us/SecEdgarFacts.java"
  - "src/main/resources/db/migration/V46__blank_currency_crossing_derived_q4_income.sql"
---

# ADR-0110. 유도 4분기가 통화를 건너뛰지 않는다 — 손익만 비우고 잔액은 남긴다

## 결정

**회계연도 안에서 보고 통화가 갈리면 유도 4분기의 손익을 내지 않는다.** 잔액은 그대로 낸다 —
그것은 FY의 연말 잔액이고 **뺄셈을 거치지 않아** 통화가 갈려도 참이다.

**이미 저장된 11행의 손익도 비운다**(V46). 지우지 않는다.

## 규칙

- **MUST: 유도 자리 <b>넷</b>에 전부 건다.** 하나만 빼먹으면 그 경로로 오염이 다시 들어온다.
  1. `DartFinancialAdapter.deriveFourthQuarter` — `FY − Q3누적`
  2. `SecEdgarFacts.deriveFourthQuarters` — `FY − (Q1+Q2+Q3)`
  3. `DartFullStatementAdapter.deriveFourthQuarterControlling` — 지배주주 순이익의 같은 뺄셈
  4. `DartFullStatementAdapter.toFourthQuarterBalance` — 3을 부르는 자리. 나머지 유량은
     이미 비우고 있어 3을 고치면 닫힌다
- **MUST NOT: 행을 지우지 않는다.** TTM이
  `fiscal_period <> 'FY' ORDER BY period_end DESC LIMIT 4`로 뽑히므로 Q4 행이 사라지면
  **Q3·Q2·Q1(N) + Q3(N−1)**의 **다섯 분기에 걸친 구멍 난 합**이 된다 — 값이 그럴듯하고
  에러가 없다(`SecEdgarFacts.deriveFourthQuarters`의 javadoc이 그 이유로 Q4를 만든다).
  손익이 NULL이면 `FinancialMath`가 null을 전파해 **판정불가**가 된다. 참인 침묵이다.
- **MUST: 라벨을 「아무 행의 통화」로 붙이지 않는다.** 섞인 보고서에서 첫 행을 집으면 라벨이
  실제와 다른 값이 되고, 그 오류는 값이 나오는 형태라 드러나지 않는다.
  `DartFinancialAdapter.singleCurrency`가 그 판정이다.
- **MUST NOT: NULL 통화 갈래를 만들지 않는다.** `financial_statement.currency_code`가
  **NOT NULL**이라 「모른다」가 원리적으로 없다(ADR-0094 §규칙).
- **MUST: 마이그레이션을 티커가 아니라 술어로 쓴다.** 열거로 두면 다음 적재에서 늘어난 행을
  아무도 안 센다. **라벨(`fiscal_year` + `Q3`)로 묶으면 안 된다** — 분기 통화가 `{GBP, USD}`인
  **MLR 2025가 빠진다**(Q3만 보면 안 갈린 것으로 읽힌다). **FY의 기간에 포함되는 분기 전체**를
  본다.

## 검증

```bash
./gradlew test --tests "*FourthQuarterControllingTest*" --tests "*SecEdgar*" \
               --tests "*DartFinancialAdapter*"
```

```premise 통화가 갈린 회계연도의 유도 4분기에 손익이 남아 있지 않다
psql -d stock_screener -At -c "
WITH crossing AS (
  SELECT q4.id, q4.stock_id, q4.fiscal_year
  FROM financial_statement q4
  WHERE q4.fiscal_period = 'Q4'
    AND EXISTS (
        SELECT 1 FROM financial_statement fy
        WHERE fy.stock_id = q4.stock_id AND fy.fiscal_period = 'FY'
          AND fy.fiscal_year = q4.fiscal_year
          AND EXISTS (
              SELECT 1 FROM financial_statement q
              WHERE q.stock_id = fy.stock_id AND q.fiscal_period NOT IN ('FY','Q4')
                AND q.period_start >= fy.period_start AND q.period_end <= fy.period_end
                AND q.currency_code <> fy.currency_code))),
dirty AS (
  SELECT s.ticker, f.fiscal_year
  FROM financial_statement f
  JOIN crossing c ON c.id = f.id
  JOIN stock s ON s.id = f.stock_id
  WHERE f.revenue IS NOT NULL OR f.operating_income IS NOT NULL
     OR f.net_income IS NOT NULL OR f.net_income_controlling IS NOT NULL
     OR f.cost_of_sales IS NOT NULL OR f.gross_profit IS NOT NULL
     OR f.operating_expenses IS NOT NULL OR f.costs_and_expenses IS NOT NULL)
SELECT CASE
  WHEN (SELECT count(*) FROM financial_statement WHERE fiscal_period = 'Q4') = 0
    THEN '잴 것이 없었다 — 유도 4분기 행이 하나도 없다'
  WHEN (SELECT count(*) FROM crossing) = 0
    THEN '잴 것이 없었다 — 회계연도 안에서 통화가 갈리는 종목이 하나도 없다'
  WHEN (SELECT count(*) FROM dirty) = 0
    THEN 'OK 통화가 갈린 유도 4분기 ' || (SELECT count(*) FROM crossing) || '행에 손익이 없다'
  ELSE '통화를 건너뛴 손익이 ' || (SELECT count(*) FROM dirty) || '행에 남아 있다: '
       || (SELECT string_agg(ticker || ' ' || fiscal_year, ', ') FROM dirty)
       || ' — 유도 자리 넷 중 하나가 풀렸거나 full-statement-refill --include-quarters가 '
       || '다시 넣었다. 그 수는 결손처럼 보이지 않아 화면이 틀린 사실을 확신 있게 말한다'
END"
```

**이 전제가 재는 것은 어댑터가 아니라 저장 상태다.** 어댑터만 고치면 `FinancialWriter`가
UPSERT·개정판만 하고 **삭제를 안 하므로**(`writeOne`에 DELETE가 없다) 이미 저장된 값이
영구히 남는다 — ADR-0094가 지표 계층에서 겪은 것과 같은 자리다.
**모집단을 「통화가 갈린 회계연도의 Q4 행」으로 좁힌 이유**: 전체 Q4를 세면 정상 유도값까지
들어와 매 세션 거짓 발견이 난다. **이 모집단이 자라는 자리**: 통화를 갈아타는 종목이 새로
편입되면 자동으로 늘고, 그때 이 전제는 어댑터가 막았는지를 그대로 신고한다.

## 배경

ADR-0094가 지표 계층에 통화 게이트를 세우면서 **저장된 오염값 자체는 안 고쳤다**고 적었고,
그 자리가 여기다. 실측으로 **11행 / 9종목**이다(2026-09-01).

```
241560 2023(KOSPI·활성편입)   CODA 2024·2025   HOLO 2022   HOLOW 2022
MLGO 2021·2022   MLR 2025   SGLY 2019   SJ 2019   SSM 2025
```

**MLR 2025가 이 결정의 요점이다.** 미국 여덟 종목 중 여섯은 `revenue`가 NULL이라 결손으로
보이는데, MLR만 **`171,918,000`이라는 그럴듯한 수**를 든다 — FY 790,271,000 USD에서
Q1 225,651,000 **GBP**를 포함한 3분기를 뺀 값이다. P27 축②가 *"그럴듯한 수로 나타나면
결손보다 나쁘다"*로 가리키는 바로 그 행이고, **`docs/progress.md`가 이 결함을 「한 메서드」
「10행」으로 적고 있었다** — 라벨(`fiscal_year` + `Q3`)로 세면 MLR이 빠지기 때문이다.

**지금 이 행을 읽는 쪽은 없다.** 아홉 종목 전부 `CURRENCY_GATE`에 걸려 `FUNDAMENTAL` 지표가
0행이고(ADR-0094), 실적 표는 `StockFinancialsAssembler`가 이미 막는다(ADR-0094 §읽기 계층).
**고치는 이유는 가드가 열리는 날 되살아나지 않게 하는 것과, 새로 통화를 갈아타는 종목이
편입될 때 조용히 들어오지 않게 하는 것**이다.

## 버린 대안

- **행을 지운다** — 처음 계획이었다. TTM에 다섯 분기 구멍을 만들고, `full-statement-refill
  --include-quarters`가 `existing == null` 갈래로 **막힘 없이 다시 넣는다**(11행 전부
  `revision_no = 0` 단일행이라 `uq_fs_revision` 충돌도 안 난다). 비우는 쪽은 고친 어댑터가
  같은 결과를 내므로 **재적재에 멱등**하다.
- **재유도한다** — `FinancialWriter.writeOne`에 DELETE가 없어 **안 걷힌다.** 고친 어댑터가
  Q4 손익을 안 내도 기존 행이 `is_latest = true`로 남는다.
- **환산한다** — 환율 시계열이 없다(ADR-0094 §버린 대안).
- **`toFourthQuarterBalance`의 통화 라벨(`any.path("currency")`)도 고친다** — 재보고 안 고쳤다.
  그 `any`는 **잔액을 뽑은 바로 그 목록의 첫 행**이라 라벨과 값의 출처가 같다. 뺄셈이 없으므로
  `DartFinancialAdapter` 쪽과 성질이 다르다.

## 결과

**쉬워지는 것** — 통화를 갈아탄 회계연도의 4분기가 **판정불가로 나타난다.** 결손이 침묵으로
드러나고 그럴듯한 수로 위장하지 않는다(P27 축②).

**어려워지는 것**

- **그 해 4분기의 잔액만 남는다.** TTM 손익은 null이 전파돼 판정불가이고, 잔액 기반 지표
  (`DEBT_RATIO`·`BPS` 계열)는 그대로 값을 낸다 — **한 행 안에서 어떤 컬럼은 값이 있고 어떤
  컬럼은 없는 상태**가 정상이 된다. 실적 표는 이미 그 해 손익을 안 내보낸다(ADR-0094).
- **`full-statement-refill --include-quarters`를 돌려도 되살아나지 않지만, 넷 중 하나라도
  풀리면 그 경로로 돌아온다.** §검증의 premise가 그 자리를 지킨다.
- **`docs/progress.md`의 ㉮ 서술이 세 군데 틀렸다** — 「한 메서드」(넷이다) ·
  「10행」(11행이다) · 「재유도가 함께 붙는다」(재유도로는 안 걷힌다). 그 세 정정을 함께 적는다.

**되돌리려면** — 세 어댑터의 통화 검사를 빼고 `V46`을 되돌리는 새 마이그레이션을 쓴다.
다만 **비운 손익은 코드를 되돌려도 안 돌아온다** — 재적재해야 하고, 그러면 다시 오염된다.
