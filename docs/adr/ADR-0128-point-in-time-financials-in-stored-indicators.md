---
id: ADR-0128
status: 채택됨
date: 2026-09-08
applies_to:
  - "src/main/java/com/stockscreener/indicator/IndicatorContext.java"
  - "src/main/java/com/stockscreener/indicator/IndicatorDataReader.java"
  - "src/main/java/com/stockscreener/indicator/IndicatorRunner.java"
  - "src/main/java/com/stockscreener/indicator/calculator/FinancialMath.java"
  - "src/main/java/com/stockscreener/indicator/calculator/ValuationHistory.java"
  - "src/main/java/com/stockscreener/indicator/calculator/ValuationDiscount.java"
---

# ADR-0128. 저장 지표를 봉마다 그 시점에 공시돼 있던 재무로 낸다

## 결정

저장 경로가 재무를 **봉 단위로** 읽는다. 리더는 `asOf`까지의 **판 이력 전부**를 주고,
기(期)마다 하나로 접는 것은 `IndicatorContext`가 하며, **어느 판을 볼지는 봉이 정한다**
(`IndicatorContext.visibleAt`). 재무에서 나온 값은 `FinancialMath.byPublication`이 공시일로
구간을 나눠 구간마다 다시 계산한다.

**창 전체를 쓰는 성질은 그대로 둔다.** 바뀐 것은 「어느 재무로 계산하는가」뿐이다.

## 왜 — 과거 기준일 조회가 판정을 정하는데 그 값이 미래를 봤다

기준일 선택기가 보존 창 전체로 열려 있고(`ScreenSettingsPopover.vue`), 그 요청이
`iv.trade_date = asOf` 하나로 저장 값을 읽어 **종목 선정·점수·선정 이유·상세 지표표**를 정한다
(`ScreeningRepository:192` · `ConditionTranslator:96,156` · `AsOfResolver`가 보존 하한까지 허용).

| asOf | 그날 아직 공시되지 않았는데 계산에 들어간 재무 행 | 종목 | 활성 1,350 대비 |
|---|---|---|---|
| 2025-12-17 (창 하한) | 830 | 225 | **16.7%** |
| 2026-03-02 | 1,263 | **600** | **44.4%** |
| 2026-06-01 | 313 | 199 | 14.7% |

**거버넌스가 이미 같은 문제를 같은 방식으로 풀어 놨다** — `GovernanceMath`가 봉마다
`publishedAt <= tradeDate`로 거르고(`:47,64,85,157`), `readGovernanceFacts`가 판을 접지 않고
연수로도 자르지 않는다. `FinancialMath.publishedIndexOn`도 주식수·밸류에이션에서 같은 일을 한다.
**재무 계산기 25개만 그 대열에서 빠져 있었다.**

## 규칙

- **MUST: 리더의 `KNOWN_ON`은 판 이력을 통째로 준다 — 기(期)마다 접지 않는다.**
  접으면 `asOf` 하나에 대해서만 판이 정해져 **봉마다 다른 판을 고를 수 없다.**
- **MUST: 접는 키는 `periodEnd`다. 회계연도 라벨로 가르지 않는다.**
  `FinancialFigures` javadoc이 *"`periodEnd`가 기간의 근거다 … `fiscalYear`는 라벨일 뿐"*으로
  그 축을 정했고, **미국 라벨은 실제로 틀린다**(ADR-0124가 회계연도 배정 결함 셋을 고쳤다).
  라벨로 가르면 라벨이 겹친 분기들이 하나로 접혀 **TTM이 네 분기를 못 채우고 값이 판정불가로
  사라진다** — 에러가 아니라 「없는 지표」로 나타난다. 실제로 시험 픽스처에서 그 모양이 났다.
- **MUST: 판 선택을 `published_at`으로 한다.** `revision_no`가 `FinancialFigures`에 없다.
  저장소 전체 **32,198개 다판(多版) 행에서 공시일이 판 번호와 역전되거나 같은 경우가 0**이다
  (2026-09-07 실측). `FinancialWriter`가 **더 늦은 공시일에만** 새 판을 만들기 때문이며
  (`writeOne`의 정정 갈래) **그 성질이 깨지면 이 선택이 조용히 옛 판을 고른다.**
- **MUST: `byPublication`에 넘기는 것은 `visibleAt`의 사본이다.** 원래 컨텍스트를 넘기면
  전 봉이 같은 값을 받아 이름만 바뀐 「하나를 펴 바르기」가 된다. **컴파일도 되고 값도 그럴듯하다.**
- **MUST NOT: 저장 경로가 창 전체를 쓴다는 성질을 좁히지 않는다.** 넷이 거기 얹혀 있다 —
  ① 정정공시 무효화(`StoredIndicatorInvalidationPort`가 `trade_date >= ?`로 **상한 없이** 지우는데
  `PipelineScheduler.runHeavy`가 `financialRefillJob` 뒤에 **백필을 잇지 않는다**)
  ② 미보정 기업행위 억제(ADR-0055) ③ 꼬리 걷기(ADR-0085)
  ④ `adj_close` 재작성 복구(`AUTO_BACKFILL_STOCK_LIMIT = 10`을 넘으면 자동 백필이 포기한다).
  지금 그 넷을 메우는 유일한 기제가 **다음 실행이 창 전체를 다시 쓰는 것**이다.
- **MUST NOT: 연수나 행 수로 재무를 자르지 않는다.** `LIMIT`은 **행** 수라, 창 앞부분에서는 최신
  기(期)가 아직 공시되지 않아 **더 오래된 행으로 채워진다** — 요구한 연수가 조용히 한 해씩
  미끄러지고, 자르면 그 깊은 해가 잘려 **창 앞부분이 통째로 비는데 최신 일자는 멀쩡하다.**
  거버넌스가 같은 자리에서 같은 결론에 도달했다(`readGovernanceFacts` javadoc).
  자르지 않아도 싸다 — 활성 유니버스 종목당 **평균 44.9행 · 최대 114행**이고 자르던 시절이 39행이었다.
- **MUST NOT: 이 변경이 만드는 커버리지 손실을 `--financial-years` 확대로 메우지 않는다**
  (ADR-0124 §규칙 — 연수를 키우면 같은 종목이 `MALFORMED`로 다시 막힌다).
- **MUST: `is_latest`를 재료 정의로 쓰는 펜스를 「최신 거래일에 대해서만 참」으로 읽는다.**
  과거 일자 지표는 이제 **그 시점의 판**을 읽으므로 그 펜스들의 보증 범위가 좁아졌다.
  특히 ADR-0071의 「정정에 밀려난 옛 판이 아직 비어 있다」는 **OK가 안전 신호에서 위험 신호로
  뒤집힌다** — 그 OK의 근거가 *"as-of 경로가 옛 판을 고를 일이 없다"*였는데 이제 정확히 그것을 고른다.
- **MUST: 주식수 수리를 `is_latest`로 좁히지 않는다**(ADR-0126 §규칙에 함께 적었다).

## 검증

```bash
./gradlew test --tests "*IndicatorRunnerTest*" --tests "*FinancialVisibilityTest*"
```

**대조군을 실제로 되돌려 확인했다** — 그때마다 지정한 것만 붉는다.

| 되돌린 것 | 붉는 시험 |
|---|---|
| `IndicatorRunner`의 `KNOWN_ON` → `LATEST` | 「정정 전 봉은 그때 유효했던 판으로 계산한다」 **하나** |
| `byPublication`이 `visibleAt` 사본 대신 원래 컨텍스트를 넘기게 | 위 하나 **+** 「공시일을 넘는 봉에서 계단이 생긴다」 |

**MUST: 대조군을 「enum 하나」로 만들지 않는다.** 첫 판이 그랬는데 **안 붉었다** —
`visibleAt`이 봉별로 거르므로 리더의 `published_at <= asOf` 필터를 **덮어쓴다.** 두 모드가 실제로
갈리는 자리는 **정정본 선택**이고, 대조군은 거기를 겨냥해야 한다.

```premise 저장된 재무 지표에 공시일 계단이 있다
psql -d stock_screener -At -c "WITH act AS (SELECT DISTINCT m.stock_id FROM universe_membership m JOIN universe u ON u.code = m.universe_code WHERE m.effective_to IS NULL AND u.is_active), d AS (SELECT iv.stock_id, count(DISTINCT iv.value) n FROM indicator_value iv JOIN act ON act.stock_id = iv.stock_id WHERE iv.indicator_code = 'GROSS_MARGIN' GROUP BY 1) SELECT CASE WHEN (SELECT count(*) FROM d) = 0 THEN '잴 것이 없었다 — 편입 종목의 GROSS_MARGIN 행이 하나도 없다' WHEN (SELECT count(*) FROM d WHERE n > 1) = 0 THEN '계단이 하나도 없다 — 저장 경로가 재무 스냅샷 하나를 창 전체에 펴는 상태로 돌아갔다(ADR-0128)' ELSE 'OK ' || (SELECT count(*) FROM d WHERE n > 1) || '/' || (SELECT count(*) FROM d) || '종목이 창 안에서 값이 둘 이상이다 — 공시일 계단이 있다' END"
```

```premise 보존 창 안에 시세는 있는데 지표가 없는 거래일이 없다
# MUST: 하한을 **시장마다** 잡는다. 전역 min을 쓰면 국내 하한(2025-12-10)이 미국(2025-12-16)에도
# 걸려 미국 4일이 상시 거짓 발화한다 — 그 4일은 구멍이 아니라 미국의 보존 경계 밖이다.
psql -d stock_screener -At -c "WITH floors AS (SELECT market_code, min(trade_date) f FROM indicator_trade_date GROUP BY 1), pd AS (SELECT s.market_code, p.trade_date FROM price_daily p JOIN stock s ON s.id = p.stock_id GROUP BY 1, 2), miss AS (SELECT pd.market_code, count(*) n FROM pd JOIN floors ON floors.market_code = pd.market_code AND pd.trade_date >= floors.f LEFT JOIN indicator_trade_date itd ON itd.market_code = pd.market_code AND itd.trade_date = pd.trade_date WHERE itd.trade_date IS NULL GROUP BY 1) SELECT CASE WHEN (SELECT count(*) FROM floors) = 0 THEN '잴 것이 없었다 — 지표 거래일 색인이 비었다' WHEN (SELECT count(*) FROM miss) = 0 THEN 'OK 시장마다 보존 하한 이후 시세 거래일에 지표가 전부 있다' ELSE '지표가 빠진 거래일이 있다: ' || (SELECT string_agg(market_code || ' ' || n || '일', ' · ') FROM miss) || ' — 그 날짜는 화면에서 고를 수 있는데 결과가 빈다' END"
```

## 버린 대안

| 대안 | 왜 버렸나 (전부 실측) |
|---|---|
| **`IndicatorRunner`의 `LATEST`를 `KNOWN_ON`으로만 바꾼다** | `docs/progress.md`가 처방으로 올려 둔 것인데 **무효과다.** 오늘 asOf에서 두 모드가 고르는 행이 완전히 같다 — `published_at > CURRENT_DATE`인 행 **0**, `is_latest`와 `max(revision_no)`가 갈리는 기 **0 / 190,876행**. 그리고 저장 경로의 asOf는 언제나 창의 마지막 날짜다 |
| **`run`이 asOf 한 봉만 쓰게 한다**(쓰기를 좁힌다) | §규칙의 넷이 창 전체 쓰기에 얹혀 있다. 특히 정정공시 무효화가 창을 통째로 지우는데 이어지는 백필이 없어, 좁히면 **정정 난 종목이 빈 채로 남는다.** 재구축 비용도 이쪽이 나쁘다 — 날짜 루프라 **7.5~9.3시간**이고, 채택안은 한 판이면 창이 채워진다 |
| **계산기 25개를 봉 루프로 각각 고친다** | 계산기 본체를 건드리면 그 25개의 값 규칙이 함께 흔들린다. `byPublication`은 **호출 형태만** 바꾸고 본체를 그대로 둔다 |
| **접는 키를 `(fiscal_year, fiscal_period)`로 둔다** | 미국 라벨이 틀린다(ADR-0124). 실제로 라벨이 겹친 분기 넷이 하나로 접혀 TTM이 무너졌다 |
| **5년 배수 중앙값을 그대로 둔다** | 재무 쪽 look-ahead만 닫고 가격 창을 남기면 과거 일자의 「5년 중앙값」이 **그 뒤의 종가**로 계산된 채 남는다. 창을 한 칸씩 미는 형태라 비용이 정렬 하나가 아니라 삽입·삭제 하나씩이다 |
| **유니버스 편입도 시점화한다** | ADR 때문이 아니라 **데이터가 없어서** 못 한다 — `universe_membership` 이력이 KOSPI/KOSDAQ 2026-08-05 · **US_TOP1000 2026-08-31**부터라 지표 달력(2025-12-10~) 대비 **KR 22거래일 · US 5거래일**뿐이다. 지금 시점 조인으로 바꾸면 **1,000/1,350 종목이 176일자에서 0건**이 된다. ADR-0036 §검증이 같은 자리를 *"조용히 커버리지가 무너지는 쪽이 거짓 양성보다 나쁘다"*로 이미 경고했다 |

## 결과

**쉬워지는 것**

- 과거 기준일 조회가 **그날의 값**으로 판정한다. 실측 확인: 삼성전자 `GROSS_MARGIN`이
  **2026-03-10에 37.99% → 39.38%로 계단**을 만들고, 그날이 FY2025의 실제 공시일이다.
- **`byPublication`을 거치는 지표는 asOf와 무관해졌다.** 날짜 d의 값이 `KNOWN_ON(d)`이므로
  언제 다시 계산해도 같다.

**MUST: 이 결정은 아직 절반이다 — 봉 루프를 스스로 도는 계산기 일곱이 남았다.**
`PER`·`PBR`·`PSR`·`EPS_TTM`·`ALTMAN_Z`·`NCAV_TO_MARKET`·`FCF_YIELD`은
`spreadOverTradeDates`를 쓰지 않아 이 변경의 대상이 아니었는데, **재무 합산을 봉 루프 밖에서
한 번** 하고 봉마다는 주식수·종가만 다시 읽는다(`PerCalculator`의
`sumControllingNetIncome(quarters)`가 그 자리다). 그래서 **분자가 asOf 스냅샷 하나**다.

**실측(2026-09-08)**: `005930`을 asOf 2026-09-04와 2026-06-01로 각각 계산하면 `PER`이
**모든 날짜에서 정확히 1.796배**로 갈린다(2025-12-17 4.268 대 7.665 · 2026-03-10 7.432 대 13.348 ·
2026-06-01 13.632 대 24.484). **배수가 날짜에 무관하게 일정한 것이 「분모가 하나」라는 증거다.**
그 종목은 정정본이 0이고 KR 분기 주식수가 전부 NULL이라 **다른 원인이 배제된다.**

**고치는 형태는 이미 저장소에 있다** — `PerMedian5yCalculator`의 람다가
`publishedIndexOn`으로 봉의 분기를 고른 뒤 `sumControllingNetIncome(quarters, published)`를
부른다. 즉 `PER_MEDIAN_5Y`의 표본은 봉별로 맞는데 **`PER` 자신은 아니고**,
`PER_DISCOUNT_5Y`가 그 둘을 비교한다.
- **보존 창 확대가 다시 판단할 자리가 됐다.** ADR-0071 §버린 대안이 그것을 기각한 근거가
  *"쌓이는 값이 여전히 오늘 재무로 계산된 것"*인데 **이 결정이 그 사유를 없앤다.** 확대 자체는
  별도 결정이다(재백필 비용은 그대로다).

## 측정 결과 (2026-09-08 재구축 직후)

| | 실측 |
|---|---|
| 재구축 비용 | **16분 24초** — 전 종목 1일자 833초(1,350/1,350 성공) + 파생층 184일자 151초 |
| 공시일 계단이 생긴 종목 | **809 / 919**(`GROSS_MARGIN` 기준. 나머지는 창 안에 공시가 없다) |
| 시간축이 없는 지표 | **0 / 132** — 바꾸기 전 다섯이 평평했다(`PROFIT_STREAK_Y`·`ROE_MIN_3Y`·`ROIC_MIN_3Y`·`OPM_MIN_5Y`·`INVENTORY_GROWTH_GAP`) |
| 원본 없는 파생행 | **0**(네 날짜 표본) |
| 보존 하한 이후 시세 거래일 중 지표가 빠진 날 | **0**(시장 셋 전부) |

**커버리지 손실은 사실상 0이다.** 겉보기 손실은 전부 **그날 봉이 없는 종목**이었다 —
모집단을 「그날 `ADJ_CLOSE`가 있는 종목」으로 잡으면:

| 날짜 | 봉 있는 종목 | `ACCRUAL_RATIO` | `GROSS_MARGIN` | `FCF_YIELD` | `PROFIT_STREAK_Y` |
|---|---|---|---|---|---|
| 2025-12-17 (창 하한) | 1,341 | 1,336 | 916 | 1,175 | 972 |
| 2026-09-04 (최신) | 1,349 | 1,340 | 910 | 1,185 | 973 |

**MUST: 이 축을 잴 때 모집단을 「봉이 있는 종목」으로 잡는다.** 활성 1,350으로 나누면
`ACCRUAL_RATIO`가 창 하한에서 714로 보이는데, 그 차이의 대부분은 **신규 편입 종목의 시세가
그날부터 없기 때문**이다(예: `A`는 `ADJ_CLOSE`부터 2025-12-17에 시작한다). 그것을 이 결정의
대가로 읽으면 없는 손실을 쫓게 된다.

**어려워지는 것**

- **`KNOWN_ON`이 고르는 옛 판은 컬럼이 비어 있을 수 있다.** 실측(asOf 2026-03-02 · 활성 유니버스 ·
  FY 9,908기): 옛 판을 고른 기 **79**, 그중 `operating_cash_flow` 결손 **8** · `cost_of_sales`
  결손 **31**, **영향 종목 21**. ADR-0071 §premise가 그 결손을 이미 지키고 있다.
  **다만 재구축 뒤 전수로 재니 커버리지 손실이 사실상 0이었다** — 아래 §측정 결과.
- **시험 픽스처가 봉과 공시일을 맞춰야 한다.** 스냅샷을 펴던 시절에는 봉이 재무보다 몇 해
  앞이어도 값이 나왔다. 실제로 여섯 클래스가 그 상태였고 66개가 붉었다.
- **`FinancialVisibility.LATEST`를 고르는 운영 경로가 없어졌다.** 지우지 않은 이유는 그것이
  **두 모드의 차이를 말하는 유일한 자리**이기 때문이다(`FinancialVisibilityTest`의
  「LATEST는 미래 공시를 준다」). 지우면 `KNOWN_ON`이 무엇을 막는지 설명하는 대조가 사라진다.
