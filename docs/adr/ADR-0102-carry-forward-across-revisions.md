---
id: ADR-0102
status: 채택됨
date: 2026-08-31
applies_to:
  - "src/main/java/com/stockscreener/pipeline/datasource/us/SecEdgarFacts.java"
  - "src/test/java/com/stockscreener/pipeline/datasource/us/SecEdgarFinancialAdapterTest.java"
---

# ADR-0102. 한 기간의 뒤 판이 앞 판의 값을 지우지 않게 판을 겹친다

## 결정

`SecEdgarFacts.revisionsOf`가 판을 낼 때 **앞 판이 낸 값을 이어받는다.** 뒤 판의 non-null이
이기고, 뒤 판이 안 실은 컬럼은 앞 판 값을 그대로 쓴다.

**겹치는 범위는 라벨 하나 안이다** — 같은 `(회계연도, 기)`의 판들 사이에서만이고, 기간을
넘지 않는다.

## 규칙

- **MUST: 라벨 하나 안에서만 겹친다.** 기간을 넘어 겹치면 **낡은 해의 값이 다음 해로 번지고**,
  그것은 결손보다 나쁘다 — 값이 그럴듯하기 때문이다.
- **MUST: `empty` 판정은 겹치기 *전* 값으로 한다.** 겹친 뒤에 보면 그 기간을 아무것도 보고하지
  않은 판이 **상속만으로 「보고자」**가 되어, 값 하나 없는 판이 라벨마다 쌓인다.
- **MUST NOT: `conflicts`를 함께 고치지 않는다.** 가짜 정정 접기(실측 112 중 107)가 그 함수에
  걸려 있다. 이어받은 컬럼은 `baseline`과 같아져 **차이를 안 만든다.**
- **MUST: 접기가 그대로인지는 재서 답한다.** 함수는 그대로여도 **첫 인자가 촘촘해진다** —
  `baseline`의 non-null이 누적되면 비교 대상이 늘고, 정정 판정이 참이 되는 경우가 **늘거나
  같다.** 「함수를 안 고쳤으니 접기도 그대로」는 **성립하지 않는 논증**이다.
- **MUST NOT: 이것을 저장 계층으로 옮기지 않는다.** `FinancialWriter`는 공시일만 보고 값을
  비교하지 않는다(ADR-0089). 겹치는 것은 어댑터의 일이다.

## 검증

```bash
./gradlew test --tests "*SecEdgarFinancialAdapterTest*"
```

「뒤 판이 한 컬럼만 정정하고 나머지를 안 실으면 앞 판의 값이 살아남는다」가 새 계약을 잠근다.

**대조군으로 확인했다**: `carryForward`가 `values`를 그대로 돌려주게 만들면 **정확히 그 시험
하나만 붉고** 나머지 열여덟은 초록이다.

```premise 노출 유니버스에서 뒤 판이 앞 판의 영업이익을 지운 행
psql -d stock_screener -At -c "SELECT CASE
  WHEN (SELECT count(*) FROM financial_statement f
        JOIN universe_membership m ON m.stock_id = f.stock_id
        WHERE m.universe_code = 'US_TOP1000' AND m.effective_to IS NULL
          AND f.fiscal_period = 'FY') = 0
    THEN '잴 것이 없었다 — US_TOP1000에 FY 재무가 한 행도 없다'
  WHEN count(*) = 0 THEN 'OK 0행'
  ELSE '뒤 판이 앞 판의 영업이익을 지운 FY 행이 ' || count(*) || '개다 — 겹치기가 되돌아갔거나, 겹치기 이후 적재되지 않은 종목이 편입됐다'
  END
FROM financial_statement l
JOIN universe_membership m ON m.stock_id = l.stock_id
  AND m.universe_code = 'US_TOP1000' AND m.effective_to IS NULL
WHERE l.is_latest AND l.fiscal_period = 'FY' AND l.operating_income IS NULL
  AND EXISTS (SELECT 1 FROM financial_statement o
              WHERE o.stock_id = l.stock_id AND o.fiscal_year = l.fiscal_year
                AND o.fiscal_period = l.fiscal_period AND NOT o.is_latest
                AND o.operating_income IS NOT NULL)"
```

**MUST: 모집단을 `US_TOP1000`으로 좁힌 근거를 함께 읽는다.** 이 결정은 **코드**에 걸리지만
**이미 저장된 행은 재적재가 닿아야 고쳐진다.** `US_LISTED`에만 있고 노출 유니버스 밖인 종목은
겹치기 이전 모양 그대로이고 **실측 1,578행**이 그렇다(전부 `is_latest`의 공시일이 더 늦어
`FinancialWriter`가 정당하게 손대지 않은 것이다). 그것까지 세면 **의도한 동작에 우는 펜스**가
된다. **그 목록이 자라는 자리는 리밸런싱이다** — 새로 편입된 종목이 겹치기 이후 적재를 안
거쳤으면 이 전제가 그때 발화하고, 처방은 `--job=financial-refill --universe=US_TOP1000`이다.

## 배경

**문서가 적어 둔 원인이 틀렸다.** `sec-edgar-financial.md`는 미국 영업이익 결손의 한 갈래를
*"그 창에 duration 팩트를 못 붙인 것"*으로 적었는데, `AAON` 2022를 열어 보니 **값은 붙었고
뒤 판이 그것을 지웠다.**

```
AAON FY2022 rev0 10-K 2023-02-27  매출 888,788,000 · 영업이익 126,761,000 · 이익잉여금 461,765,000
            rev1 10-Q 2023-11-06  매출 NULL        · 영업이익 NULL        · 이익잉여금 461,657,000  ← is_latest
```

이익잉여금이 **10.8만 달러 실제로 정정**됐고 `conflicts`가 그 한 컬럼을 보고 판 전체를 새로
냈다. 새 판은 10-Q의 **비교 재무상태표**뿐이라 손익이 통째로 비었다. 코드가 그 동작을 이미
알고 있었다 — *"채워 넣지는 않는다. 뒤 보고서가 값을 「더하는」 것은 정정이 아니라 보정이고,
이 잡은 APPEND_ONLY라 보정 경로가 없다."*

**전수(US_TOP1000 · FY `is_latest` 6,568행 · 판 둘 이상인 라벨 1,365개 · 최대 6판)**: 뒤 판이
지운 것이 **영업이익 373행 · 매출 505행 · 총자산 543행**이다.

## 버린 대안

- **`conflicts`를 「컬럼이 줄어든 것도 정정」으로 바꾼다** — 방향이 정반대다. 그러면 비교표로
  다시 실린 112건 중 107건이 **가짜 정정**이 되고, 그 판정이 그 종목의 지표를 보존 창 전체에서
  지운다. 그 접기가 이 어댑터의 존재 이유 중 하나다.
- **저장 계층(`FinancialWriter`)이 NULL을 안 덮게 한다** — 행이 두 공시의 혼합이 되어
  「어느 시점의 값인가」를 물을 수 없게 된다. `FinancialWriter`가 그 이유로 이미
  `FILL_WITHOUT_REVISION`을 두었고, 같은 근거가 여기에도 걸린다.
- **`labelPeriods`의 유량 매칭을 느슨하게 한다** — 처음에 세운 가설이고 **틀렸다.** 값은
  붙었고 지워진 것이라 그 자리를 고쳐도 한 행도 안 돌아온다.

## 결과

**쉬워지는 것**: 정정이 낀 기간의 손익이 살아남는다. 이미 저장된 옛 판으로 미리 세니
`OPM_MIN_5Y` 산출 가능이 **537 → 681**, `PROFIT_STREAK_Y`가 **478 → 635**다.

**어려워지는 것**

- **판이 더 나온다.** `baseline`이 촘촘해져 비교가 늘고, 그만큼 정정 판정이 늘거나 같다.
  적재에서 실제로 종목당 여러 판이 새로 쌓였다.
- **`report_code`가 그 판의 것인데 값은 앞 판에서 온 것이 섞인다.** 행의 뜻이 「그 보고서가
  말한 것」에서 **「그 보고서 시점까지 우리가 아는 것」**으로 바뀌었다.
- **`initial-load`의 수용 검사 「두 번 돌려 정정 0건」이 한 번 흔들린다.** 코드 변경이 만든
  1회성 재정렬이고, **3회차가 0건이면 그것이다.**

**되돌리려면**: `carryForward`가 `values`를 그대로 돌려주게 한다. 그러면
`SecEdgarFinancialAdapterTest`의 「뒤 판이 …」 하나가 붉는다. **이미 쌓인 판은 안 사라진다.**
