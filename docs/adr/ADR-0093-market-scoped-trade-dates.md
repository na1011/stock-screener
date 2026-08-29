---
id: ADR-0093
status: 채택됨
date: 2026-08-30
applies_to:
  - "src/main/java/com/stockscreener/pipeline/collector/PriceCalendar.java"
  - "src/main/java/com/stockscreener/indicator/IndicatorRetentionPolicy.java"
  - "src/main/java/com/stockscreener/indicator/IndicatorCalendar.java"
  - "src/main/java/com/stockscreener/pipeline/batch/DailyUpdateJob.java"
  - "src/main/java/com/stockscreener/pipeline/batch/IndicatorBackfillJob.java"
  - "src/main/java/com/stockscreener/watchlist/WatchlistAlertRunner.java"
---

# ADR-0093. 거래일과 보존 경계를 시장으로 가르고, 화면 기준일은 비용을 재고 미룬다

## 결정

「거래일」을 묻는 질의를 **시장 하나에 대해서만** 묻게 바꾼다. 축은 `stock.market_code`다.

**화면 기준일(`IndicatorCalendar`)은 이번에 안 가른다.** 비용이 막았고, 지금은 안 닿는다.
여는 조건은 아래 §결과에 있다.

## 규칙

- **MUST: 여러 종목에 걸친 날짜 집합을 만드는 질의는 시장 인자를 받는다.**
  `PriceCalendar`의 셋 · `IndicatorRetentionPolicy.writeCutoff`·`purge`가 그것이다.
  `stock_id = ?`로 좁힌 질의는 **종목이 곧 시장이라** 대상이 아니다.
- **MUST NOT: `IndicatorBackfillJob.tradeDatesBetween`을 시장별로 가르지 않는다.**
  날짜 루프가 묻는 것은 *어느 날짜를 한 번씩 돌 것인가*이고 그 답은 **합집합**이다. 한 시장이
  쉰 날에 그 시장 종목을 돌려도 러너는 그 종목의 마지막 봉 날짜로 같은 값을 다시 쓸 뿐이라
  멱등이다. 가를 것은 그 옆의 **보존 경계 판정**이었다 — 경계가 밀리면 「저장될 값이 없다」로
  읽혀 **잡이 돌지 않고 SUCCESS로 끝난다.**
- **MUST: 결손 알림은 시장이 여럿이어도 한 건이다.** `dedupeKey`가 제목으로 지어지므로
  시장마다 보내면 알림 계층이 뒤엣것을 삼키고 **어느 시장이 살아남는지가 임의**가 된다.
- **MUST: 워치리스트 판정은 시장별 기준일을 받는다.** 하나로 판정하면 그날 쉰 시장의 종목이
  전 조건 판정불가 → 불충족이 되고, `recordEvaluation`이 `last_satisfied`를 내려
  **다음 거래일에 같은 알림이 새 충족인 것처럼 다시 나간다**(ADR-0026).
  기준일이 없는 시장의 항목은 **판정하지 않는다** — 불충족으로 접는 것이 그 사고다.
- **MUST NOT: 고아 파생값 정리의 경계를 시장별로 좁히지 않는다.** 판정("그날 원본이 없다")이
  시장과 무관하므로 **시장별 최솟값**을 써서 창을 넓히는 쪽이 언제나 안전하다.
- **MUST NOT: `market_calendar`로 고치지 않는다**(ADR-0091이 명시적으로 금지한다).
  이 일곱 남짓은 **시장 분리**가 필요한 것이지 달력이 필요한 것이 아니고, 국내에는 달력이 없다.
- **MUST NOT: `MarketCalendar.isWeekend`에 시장 인자를 붙이지 않는다.** 주어진 `LocalDate`의
  요일은 시간대와 무관하다. 시간대가 실제로 걸리는 곳은 **「어제」를 정하는 자리**
  (`DailyUpdateJob.targetDateFor`)이고, 그쪽은 아래 §결과가 조건을 적는다.

## 검증

```bash
./gradlew test --tests "*MarketScopedTradeDateTest*" --tests "*PipelineEndToEndTest*"
```

**단언을 「시장 인자를 받는가」로 세우지 않았다** — 그것은 시그니처를 보는 것이라 정의상 언제나
참이다. 재는 것은 *미국 봉이 국내 답을 움직이는가*이고, **대조군으로 확인했다**: 시장 조건을
지우면 넷이 붉고 되돌리면 초록이다.

```premise 거래일 집합이 시장마다 갈리기 시작했는가
psql -d stock_screener -At -c "
WITH d AS (SELECT s.market_code, p.trade_date FROM price_daily p
             JOIN stock s ON s.id = p.stock_id GROUP BY 1, 2),
     kr AS (SELECT DISTINCT trade_date FROM d WHERE market_code IN ('KOSPI','KOSDAQ')),
     us AS (SELECT DISTINCT trade_date FROM d WHERE market_code = 'US'),
     split AS (SELECT count(*) AS n FROM (
        SELECT trade_date FROM kr EXCEPT SELECT trade_date FROM us
        UNION ALL
        SELECT trade_date FROM us EXCEPT SELECT trade_date FROM kr) x),
     kospi_only AS (SELECT count(*) AS n FROM (
        (SELECT trade_date FROM d WHERE market_code='KOSPI'
         EXCEPT SELECT trade_date FROM d WHERE market_code='KOSDAQ')
        UNION ALL
        (SELECT trade_date FROM d WHERE market_code='KOSDAQ'
         EXCEPT SELECT trade_date FROM d WHERE market_code='KOSPI')) y)
SELECT CASE
  WHEN (SELECT count(*) FROM us) = 0
    THEN 'OK 미국 봉이 없어 아직 갈릴 것이 없다 (국내 거래일 ' || (SELECT count(*) FROM kr) || '일)'
  WHEN (SELECT n FROM kospi_only) > 0
    THEN 'KOSPI와 KOSDAQ의 거래일이 ' || (SELECT n FROM kospi_only) || '일 갈렸다 — '
         || 'market_code로 가른 것이 국내에서 무해하다는 근거가 깨졌다. ADR-0093 §배경을 다시 잰다'
  ELSE 'OK 국내는 한 달력이고 미국과는 ' || (SELECT n FROM split) || '일 갈린다 '
       || '(국내 ' || (SELECT count(*) FROM kr) || '일 · 미국 ' || (SELECT count(*) FROM us) || '일)'
END"
```

**이 전제가 묻는 것은 결함이 아니라 근거다.** 미국과 갈리는 것은 **의도**이고(그래서 이 결정이
있다), 무너지면 안 되는 것은 **KOSPI와 KOSDAQ이 한 달력**이라는 쪽이다 — 그것이 참이라서
`market_code`로 가른 것이 국내에 무해하다.

## 배경

`batch-load` skill이 「거래일을 시장으로 안 가르는 일곱 자리」로 적어 둔 것이 **일곱이 아니었다.**
전수로 세니 `IndicatorRetentionPolicy.purge` · `IndicatorCalendar`의 셋 ·
`alertIfIndexMissingWhilePricesArrived` · 워치리스트까지 걸린 `asOf` 스칼라가 더 있었다.

**축을 `market_code`로 둔 근거는 실측이다.** 진짜로 갈리는 것은 휴장 달력이므로 `country_code`가
더 정확한 축으로 보이지만, 저장된 전 구간에서 **KOSPI와 KOSDAQ의 거래일 집합 차이가 0건**이라
`market_code`로 가르는 것이 국내에서 무해하다. 더 단순한 축(=`stock`이 이미 들고 있는 컬럼)을
고른 대가가 없다.

**비용을 재고 하나를 미뤘다.** `indicator_value`에 `stock`을 조인하면 `max(trade_date)`가
**0.099ms → 1,207ms**다(실측 7,138,936행). PK가 `(trade_date, …)`라 지금은 인덱스 끝을 한 번
훑고 끝나는데, 조인이 그 경로를 없앤다. 날짜로 30일 묶어도 274ms, 종목별 lateral로 46ms로
**요청마다 도는 질의**에 쓸 수 없다. `price_daily` 쪽은 전부 배치 경로라 이 문제가 없다
(`countOn` 0.7ms · `earliestTradeDate` 38ms · `latestTradeDateOnOrBefore` 209ms, 실행당 1회).

## 버린 대안

- **`market_calendar` 표로 고친다** — ADR-0091이 `MUST NOT`으로 금지했고 근거가 그대로 유효하다.
  국내에 세션 달력이 없으므로 그 표로는 국내를 판정할 수 없다.
- **`country_code`를 축으로 쓴다** — 더 정확하지만 지금 재보면 국내에서 결과가 같고
  (§배경의 0건), `stock`에 없는 컬럼이라 질의마다 `market`을 한 번 더 조인해야 한다.
  **갈리는 날 다시 볼 자리이고, 그날 위 premise가 운다.**
- **결손 알림을 시장마다 한 건씩 보낸다** — 처음에 그렇게 만들었고 테스트가 잡았다.
  `dedupeKey`가 제목으로 지어져 **둘 중 하나가 조용히 삼켜진다.**
- **`indicator_value`에 `market_code`를 비정규화한다** — 화면 기준일을 0.1ms로 되돌리는 유일한
  길이지만, 7백만 행(미국을 켜면 억 단위)에 컬럼을 더하는 저장 구조 변경이다. 지금 닿지 않는
  결함에 그 비용을 먼저 치르지 않는다.
- **화면 기준일을 `price_daily`에서 유도한다** — 싸지만 `IndicatorCalendar`가 일부러 지표에
  묻는 이유(시세는 들어왔는데 지표 배치가 실패한 날)를 버린다.

## 결과

**쉬워지는 것**: 미국 봉이 최근 구간에 들어와도 국내의 보존 경계·결손 판정·백필 범위가
움직이지 않는다. 두 알림이 서로 반대 방향으로 깨지던 것도 닫혔다.

**어려워지는 것 — 아직 열려 있는 것 셋. 미국을 켜기 전에 닫아야 한다.**

- **화면 기준일이 아직 시장을 안 가른다.** `IndicatorCalendar`의 세 질의와 `AsOfResolver`가
  전 시장 `max(trade_date)`를 쓴다. **지금은 안 닿는다** — 미국 `indicator_value`가 0행이고,
  백필이 활성 유니버스만 보므로 `US_LISTED`를 켜기 전에는 생기지 않는다.
  **닿는 순간의 증상**: 국내 휴장·미국 개장일에 기준일이 미국 날짜가 되어 **국내 전 프리셋이
  「조건에 맞는 종목이 없다」**가 된다. 여는 조건은 §버린 대안의 비정규화 또는 그보다 싼 안을
  찾는 것이고, **미국 지표가 한 행이라도 생기기 전에 정해야 한다.**
- **`DailyUpdateJob.targetDateFor`가 시장 하나의 달력을 쓴다.** 「어제」를 국내 기준으로 정한다.
  지금은 15일 창이 흡수하므로 결함이 아니다(ADR-0042). **결함이 되는 조건**: 창을 좁히거나,
  미국 세션이 국내 기준 「어제」보다 뒤에 끝나 그날 봉이 창 밖으로 나가는 경우다.
- **미국 휴장일마다 결손 알림이 온다.** 판정이 시장별이 되었으므로 미국이 일일 갱신 대상에
  들어오면 미국 휴장일(연 9~10일)에 알림이 뜬다. 지금은 미국이 대상이 아니라 안 닿는다.
  막으려면 `market_calendar`가 그 판정에 들어와야 하는데, 그것은 ADR-0091의 금지가 **국내에만**
  걸린다는 것을 먼저 확인해야 하는 별도 결정이다.

**되돌리려면**: 각 질의에서 시장 조건을 빼고 `writeCutoffs`·`marketsOf`를 지운다. 그러면
`MarketScopedTradeDateTest`와 `PipelineEndToEndTest`의 시장 분리 테스트가 붉어진다 —
**되돌린 것이 조용하지 않다는 것이 그 테스트의 값이다.**
