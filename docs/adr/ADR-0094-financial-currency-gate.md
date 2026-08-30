---
id: ADR-0094
status: 채택됨
date: 2026-08-30
applies_to:
  - "src/main/java/com/stockscreener/indicator/IndicatorDataReader.java"
  # 걷기를 결정하는 자리. 통화 축이 여기 없으면 게이트가 새 계산만 막는다(§규칙).
  - "src/main/java/com/stockscreener/indicator/IndicatorRunner.java"
  - "src/test/java/com/stockscreener/indicator/CurrencyGateTest.java"
---

# ADR-0094. 재무 통화가 시장 통화와 다른 종목은 재무를 통째로 주지 않는다

## 결정

**재무의 통화가 그 종목이 상장된 시장의 통화와 다르면, 지표 계층이 그 종목의 재무를 한 행도
읽지 않는다.** 판정의 축은 `market.currency_code`이고, 게이트는 `IndicatorDataReader`의
재무 질의 둘 안에 있다(`CURRENCY_GATE`).

**환산하지 않는다.** 환율 시계열이 없고, 그것을 들이는 것은 새 외부 소스와 새 축이다.

## 규칙

- **MUST: 게이트를 종목 단위로 건다.** 행 단위로 거르면 창이 두 통화를 섞은 채 계산된다 —
  두산밥캣이 KRW 34행(2016~2023)과 USD 14행(2023~2026)을 함께 갖고 **5년 창 지표가 그 둘을
  더한다.** USD 행만 빼면 2023-09 재무가 최신인 척하고, KRW 행만 빼면 5년 창이 2년치가 된다.
  **어느 쪽도 「정상값 보존」이 아니다.**
- **MUST: 판정을 시장 통화와의 대조로 둔다.** `MUST NOT: 통화 리터럴로 바꾸지 않는다` —
  「USD를 버린다」로 만들면 **미국을 켜는 순간 전 종목의 재무가 사라진다.**
- **MUST: 리더에서 막는다.** ADR-0080이 옛 행을 걷는 조건이 「계산기가 창 전체에 대해 한 점도
  못 냈을 때」라, `IndicatorValueWriter`나 상위에서 거르면 **이미 저장된 오염값이 안 걷힌다.**
  - **그것만으로는 안 걷혔다.** 리더에서 막으면 재무가 0행으로 오는데, ADR-0080의
    `inputsWereReadable`이 그것을 **「아직 모른다」로 접어** `needsFinancial` 계산기의 삭제를
    스킵한다. **MUST: 러너가 「우리가 막은 것」을 알아야 한다** —
    `StockAttributes.financialCurrencyMismatched`가 그 축이고, 없으면 이 결정은
    **새 계산만 막고 저장된 오염값은 영구히 남긴다.**
- **MUST: 그 판정의 술어를 `CURRENCY_GATE`와 같게 유지한다.** 게이트가 `is_latest`를 안 보므로
  판정도 안 본다 — 통화가 다른 행이 **개정 전 판에만** 있는 종목이 실재하고(실측 6종목, 전부 US),
  갈리면 **게이트는 막는데 러너는 「불일치 아님」으로 읽어** 옛 행이 영영 안 걷힌다.
  `CurrencyGateTest`의 「두 술어가 안 갈린다」가 그 자리를 잠근다.
- **MUST: `LATEST`와 `KNOWN_ON` 둘 다 같은 게이트를 탄다.** 한쪽만 걸면 시점 재현이 라이브와
  다른 모집단을 보게 되고, 그 차이는 값이 나오는 형태라 드러나지 않는다.
- **MUST NOT: NULL 통화 갈래를 만들지 않는다.** `financial_statement.currency_code`가
  **NOT NULL**이라 「모른다」가 원리적으로 없다(실측 15,695행 전부 KRW 아니면 USD).
  NULL 방어를 적으면 **없는 방어선을 보증하는 문장**이 된다.

## 검증

```bash
./gradlew test --tests "*CurrencyGateTest*"
```

**단정을 「게이트 SQL이 있는가」로 세우지 않았다** — 그것은 문자열을 보는 것이라 정의상 언제나
참이다. 재는 것은 *섞인 종목의 재무가 실제로 안 나오는가*이고, **대조군으로 확인했다**:
게이트를 빼면 「막힌다」를 단정하는 둘이 붉고, 「통과한다」를 단정하는 둘은 그대로 초록이다.

```premise 통화가 섞인 종목에 재무 기반 지표가 남아 있는가
psql -d stock_screener -At -c "
WITH mismatched AS (
  SELECT DISTINCT s.id, s.ticker
  FROM financial_statement f
  JOIN stock s ON s.id = f.stock_id
  JOIN market m ON m.code = s.market_code
  WHERE f.currency_code <> m.currency_code),
 stale AS (
  SELECT count(*) AS rows, count(DISTINCT v.stock_id) AS stocks
  FROM indicator_value v
  JOIN mismatched x ON x.id = v.stock_id
  JOIN indicator i ON i.code = v.indicator_code
  WHERE i.category = 'FUNDAMENTAL')
SELECT CASE
  WHEN (SELECT count(*) FROM mismatched) = 0
    THEN '잴 것이 없었다 — 재무 통화가 시장 통화와 다른 종목이 하나도 없다'
  WHEN NOT EXISTS (SELECT 1 FROM indicator WHERE category = 'FUNDAMENTAL')
    THEN '잴 것이 없었다 — FUNDAMENTAL 지표가 카탈로그에 하나도 없다'
  WHEN (SELECT rows FROM stale) = 0
    THEN 'OK 통화가 섞인 ' || (SELECT count(*) FROM mismatched)
         || '종목에 재무 기반 지표가 없다 (' || (SELECT string_agg(ticker, ',') FROM mismatched) || ')'
  ELSE '통화가 섞인 ' || (SELECT stocks FROM stale) || '종목에 재무 기반 지표 '
       || (SELECT rows FROM stale) || '행이 남아 있다 — IndicatorRunner.inputsWereReadable의 '
       || '통화 축부터 본다. 그 축이 없으면 게이트가 재무를 0행으로 만드는 것이 '
       || 'ADR-0080의 「입력을 못 읽었다」로 접혀 걷기가 스킵된다'
END"
```

**이 전제가 묻는 것은 게이트가 아니라 그 뒤처리다.** 게이트를 넣은 직후에는 반드시 발화하고,
**걷기가 열린 뒤의 백필 한 번으로 닫힌다.** 그 백필의 대상은 활성 유니버스뿐이다
(`IndicatorBackfillJob.resolveTargets`) — 지금은 국내 350종목이고 이 결함이 국내 3종목이다.

**MUST: 모집단을 `indicator.category = 'FUNDAMENTAL'`로 좁힌 것이 이 전제의 핵심이다.**
처음에는 그 종목의 `indicator_value`를 **전량** 셌는데, 그러면 **활성 종목에 대해 원리적으로
`OK`를 못 낸다** — 두산밥캣은 편입 중이라 매 거래일 122개 지표가 새로 들어오고 그중 대부분이
`ADJ_CLOSE`·이동평균처럼 **재무를 안 쓰는 정상값**이다. `0000-template.md`가
*"MUST NOT: 의도한 동작에 우는 펜스를 만들지 않는다"*로 이름 붙인 병이 그것이다.

**`FUNDAMENTAL` 53종은 전수로 확인했다 — 재무 없이 값을 내는 것이 하나도 없다.**
`MARKET_CAP`·`TURNOVER_RATIO`·`SHARES_GROWTH_3Y`는 `PRICE`이고
`DIVIDEND_PAYOUT`·`EXEC_PAY_TO_NET_INCOME`은 `GOVERNANCE`다. 카테고리는
`@IndicatorMeta` → `IndicatorCatalogSynchronizer`가 유지하므로 **손으로 적은 목록처럼 낡지
않는다.** **이 모집단이 자라는 자리**: 재무를 안 쓰는 `FUNDAMENTAL` 지표가 새로 생기면
이 전제가 영구히 붉는다 — 그때 여기서 그 코드를 빼는 것이 아니라 **그 지표의 카테고리가
맞는지를 먼저 본다.**

## 배경

`market.currency_code`(KOSPI·KOSDAQ = KRW, US = USD)와 `financial_statement.currency_code`가
어긋나는 종목이 **셋** 있고(두산밥캣 241560 · 코오롱티슈진 950160 · 인제니아테라퓨틱스 950260),
아무도 둘을 대조하지 않았다. 리더는 후자를 이미 읽고 있었으므로 **재료는 다 있고 판정만 없었다.**

저장된 값이 무증상이 아니다 — 두산밥캣의 `PER 17,386` · `PBR 1,163` · `PSR 937` ·
`ALTMAN_Z 960` · `PER_DISCOUNT_5Y −163,019` · `PER_PCTL 1.0`. 주가를 안 쓰는 것도 오염된다
(`EXEC_PAY_TO_NET_INCOME 1,873` = 보수 KRW ÷ 순이익 USD). **비율 지표는 멀쩡하다**
(`OPM_MIN_5Y 7.8`) — 분자와 분모가 같은 통화라 상쇄되기 때문이고, 그래서 **눈으로 훑으면
정상으로 보이는 지표가 섞여 있다.**

**지금 고치는 이유는 미국 때문이다.** 미국을 켜면 이 게이트의 뜻이 시장마다 달라지므로,
**켜기 전에 형태를 정해야 한다.** 판정 축을 시장 통화로 둔 것이 그 답이다.

## 버린 대안

- **행 단위로 거른다** — 처음에 「KRW 시절의 정상값을 안 지운다」는 이유로 그렇게 적었는데
  **재보니 거짓이었다.** 두산밥캣의 저장된 `EPS_CAGR_5Y`가 **−75.2%**이고 `OCF_SUM_5Y`가
  원화 3년과 달러 2년의 단순 합이다. 어느 쪽을 남겨도 창이 망가진다.
- **환산한다** — 환율 시계열이 없다. 들이면 새 외부 소스·새 저장 축·시점별 환율 선택 규칙이
  함께 붙고, 그 셋 중 어느 것도 이 결함이 요구하는 크기가 아니다.
- **`IndicatorValueWriter`나 상위에서 거른다** — ADR-0080의 걷기가 「계산기가 못 냈을 때」에만
  걸리므로 **이미 저장된 오염값이 영구히 남는다.**
- **통화가 USD인 재무를 버린다** — 미국을 켜면 전 종목이 지워진다. 지금은 무해해 보이지만
  **v1.1에서 정확히 반대로 작동한다.**
- **종목 단위 판정을 별도 질의로 한다** — 왕복이 는다. 백필이 날짜×종목이라 180×350×2 =
  약 12만 회가 되므로 서브쿼리로 넣었다. 실측 비용은 종목당 **+0.7ms**(1.178 → 1.898ms).
  **이 기각은 「질의를 하나 더 던지는 것」에 걸린다** — `readStockAttributes`처럼 **이미 종목당
  한 번 도는 질의에 컬럼을 얹는 것**은 왕복이 안 늘어 여기 해당하지 않는다(실측 0.084 → 0.48ms,
  350종목 × 1거래일에 **+0.14초**). 그 자리가 러너의 통화 축이다.

## 결과

**쉬워지는 것**: 통화가 섞인 종목이 「조건 미달」로 위장하지 않는다. 값이 안 나오면
판정불가이고, `ConditionEvaluator.satisfiesRequired`가 그것을 충족으로 읽지 않는다.

**어려워지는 것**

- **국내 3종목의 재무 지표가 사라진다.** `EPS_CAGR_5Y`가 린치 프리셋 둘의 필수 게이트라
  그 셋은 **전부터 탈락하고 있었고**, 달라지는 것은 탈락의 이유가 「오염된 값」에서
  「값 없음」으로 바뀌는 것이다. 화면에서는 여전히 구별되지 않는다 — **그 구별을 만드는 것은
  이 결정의 범위가 아니다.**
- **백분위 모집단이 바뀐다.** 값을 지우면 국내 350종목의 `*_PCTL`이 다시 매겨지고,
  **알림이 걸린 워치리스트 1건**이 `PER_PCTL`·`PBR_PCTL`을 건다(ADR-0026의 엣지 트리거).
- **저장된 오염값을 걷으려면 러너에 통화 축이 있어야 한다.** 이 결정만으로는 안 걷혔다 —
  위 §규칙의 두 번째 항목이 그 이유이고, 축을 더한 뒤의 단일 거래일 백필 한 번이 닫는다.
- **걷히는 것의 절반쯤은 오염값이 아니다.** 비율 지표는 분자·분모가 같은 통화라 상쇄되므로
  값 자체는 맞는데(`OPM_MIN_5Y 7.8`), `needsFinancial`을 선언한 이상 함께 걷힌다.
  **그것이 이 결정이 이미 치르기로 한 대가다** — 종목 단위 게이트를 고른 순간 정해졌다.
- **업종 상대값이 백분위보다 크게 움직인다.** 오염값이 백분위 **최상단**이라 그것을 빼도
  남은 종목의 `rank`는 **한 종목도 안 바뀌고**(실측) 분모만 줄어 최대 0.014 움직인다.
  반면 업종 중앙값은 **`DEBT_RATIO`가 54종목·최대 26.5%, `PSR`이 42종목·최대 28.7%**로 바뀐다.
  **MUST NOT: 「백분위가 밀린다」를 이 결정의 근거로 적지 않는다 — 재보니 거짓이다.**

**되돌리려면**: `CURRENCY_GATE`를 두 질의에서 빼면 된다. 그러면 `CurrencyGateTest`의 넷 중
둘이 붉는다 — **되돌린 것이 조용하지 않다는 것이 그 테스트의 값이다.** 다만 **지워진 지표는
코드를 되돌려도 안 돌아온다**(백필이 다시 계산해야 하고, 백분위는 전 종목 × 보존창이라
실측 1시간 51분이다).
