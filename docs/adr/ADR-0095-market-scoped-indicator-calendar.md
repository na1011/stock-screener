---
id: ADR-0095
status: 채택됨
date: 2026-08-30
applies_to:
  - "src/main/java/com/stockscreener/indicator/IndicatorCalendar.java"
  - "src/main/java/com/stockscreener/indicator/IndicatorTradeDateIndex.java"
  - "src/main/java/com/stockscreener/web/AsOfResolver.java"
  - "src/main/java/com/stockscreener/web/MarketScope.java"
  - "src/main/resources/db/migration/V37__indicator_trade_date.sql"
---

# ADR-0095. 화면 기준일을 시장으로 가르고, 그 비용을 파생 색인으로 치른다

## 결정

**화면 기준일을 시장별로 묻는다.** `IndicatorCalendar`의 세 질의가 시장 인자를 받고,
`AsOfResolver`가 그것을 요청에서 유도해 넘긴다(`MarketScope`).

**읽는 것은 `indicator_value`가 아니라 파생 색인 `indicator_trade_date`(market_code, trade_date)다.**
원본에 `stock`을 조인하면 그 질의가 **0.099ms → 1,333ms**인데(실측 7,179,721행) **요청마다 돈다.**
색인은 시장 수 × 보존 180거래일이라 수백 행이다.

**ADR-0093이 「미국을 켜기 전에 닫아야 한다」로 남긴 셋 중 하나를 닫는다.** 나머지 둘
(`DailyUpdateJob.targetDateFor` · 미국 휴장일 결손 알림)은 그대로 열려 있다.

## 규칙

- **MUST: 호출부가 시장을 반드시 고른다.** 무인자 갈래를 두지 않았다 — 기본값을 주면 전 시장을
  고른 새 호출부가 **아무 증상 없이** 국내 휴장·미국 개장일에 국내를 통째로 비운다.
  `FinancialVisibility`가 같은 이유로 기본값이 없다.
- **MUST: 빈 시장 집합을 「전 시장」으로 접지 않는다.** `IndicatorCalendar`가 예외를 던지고
  `AsOfResolver`는 그것을 「준비되지 않음」(503)으로 접는다 — 활성 유니버스가 0인 상태
  (ADR-0012가 막는 그것)가 조용한 성공으로 보이지 않게 한다.
- **MUST: 지표를 쓰거나 지우는 잡의 끝에서 색인을 다시 짓는다.** 그 잡은 둘이다 —
  `IndicatorBackfillJob`과 **`DailyUpdateJob`**. **매일 도는 것은 후자이므로 백필에만 달면
  기준일이 마지막 백필 시점에 언 채 매일 하루씩 벌어진다.** 값은 전부 옳고 어긋남은 조용하다.
- **MUST: `DailyUpdateJob`에서는 `purge()` 뒤다.** 앞에 두면 방금 지운 날짜가 색인에 남아
  화면이 **조회하면 비는 범위**를 연다.
- **MUST NOT: 잡 전체를 한 트랜잭션으로 묶어 색인 갱신을 그 안에 넣지 않는다.** 지표 계층에
  잡 단위 트랜잭션이 없고(`IndicatorRunner`·`IndicatorValueWriter`·`IndicatorRetentionPolicy`
  전부), 만들면 `data-pipeline.md`의 **「실패 격리의 단위를 종목으로 둔다」**를 깬다.
  색인은 원본에서 유도하므로 **몇 번을 불러도 결과가 같다.**
- **MUST NOT: `market_calendar`로 이 일을 하지 않는다**(ADR-0091·0093이 금지한다). 그것은
  **거래소 세션 달력**이고 국내에 소스가 없다. 이 색인이 담는 것은 **「지표가 실제로 있는 날」**이라
  다른 물건이다 — 지표 배치가 실패한 날은 시장이 열렸어도 여기 없는 것이 옳다.
- **MUST NOT: `indicator_value`에 `market_code`를 비정규화하지 않는다.** ADR-0093 §버린 대안이
  잰 유일한 안이 그것인데, 7백만 행(미국을 켜면 억 단위)에 컬럼을 더하는 저장 구조 변경이다.
- **MUST NOT: 시장 코드를 `MarketScope`에 상수로 박지 않는다.** `universe.market_code`와
  `stock.market_code`에서 유도한다 — 박으면 시장이 늘 때 값은 나오고 새 시장만 조용히 빠진다.

## 검증

```bash
./gradlew test --tests "*IndicatorCalendarTest*" --tests "*AsOfResolverTest*"
```

**단정을 「시장 인자를 받는가」로 세우지 않았다** — 그것은 시그니처를 보는 것이라 정의상 언제나
참이다. 재는 것은 *다른 시장의 더 최근 지표가 이 시장의 기준일을 움직이는가*이고,
**대조군으로 확인했다**: `single`에서 시장 조건을 지우면
`otherMarketDoesNotMoveThisMarketsLatest`가 붉고 되돌리면 초록이다.

**픽스처가 색인을 손으로 넣지 않는다.** 운영 경로와 같이 `IndicatorTradeDateIndex.rebuild()`를
부른다 — 손으로 넣으면 **잡이 그것을 빠뜨려도 테스트가 초록이다.**

```premise 화면 기준일 색인이 원본과 맞는가
psql -d stock_screener -At -c "
WITH src AS (
  SELECT DISTINCT s.market_code, v.trade_date
  FROM indicator_value v JOIN stock s ON s.id = v.stock_id),
 missing AS (SELECT count(*) AS n FROM (SELECT * FROM src EXCEPT SELECT * FROM indicator_trade_date) a),
 extra   AS (SELECT count(*) AS n FROM (SELECT * FROM indicator_trade_date EXCEPT SELECT * FROM src) b)
SELECT CASE
  WHEN NOT EXISTS (SELECT 1 FROM src)
    THEN '잴 것이 없었다 — indicator_value가 비어 있다'
  WHEN (SELECT n FROM missing) = 0 AND (SELECT n FROM extra) = 0
    THEN 'OK 색인이 원본과 같다 (' || (SELECT count(*) FROM indicator_trade_date) || '건 · '
         || (SELECT count(DISTINCT market_code) FROM indicator_trade_date) || '개 시장)'
  ELSE '색인이 원본과 어긋났다 — 원본에만 ' || (SELECT n FROM missing)
       || '건, 색인에만 ' || (SELECT n FROM extra) || '건. '
       || '지표를 쓰는 잡이 IndicatorTradeDateIndex.rebuild()를 빠뜨렸는지 본다 '
       || '(그 잡은 IndicatorBackfillJob과 DailyUpdateJob 둘이다)'
END"
```

**이 전제가 재는 것은 색인의 신선도이지 결정의 옳음이 아니다.** 어긋나면 고칠 것은 **색인**이고
(`rebuild()` 한 번), 원본이 아니다. 비용은 원본 스캔 한 번이라 전제당 상한 안이다.

## 배경

`IndicatorCalendar`의 세 질의가 전 시장 `max/min(trade_date)`를 썼다. **국내 휴장·미국 개장일이
실재한다** — 보존창 안 국내 휴장 평일 13일 중 미국 개장이 9일이다(실측). 미국 지표가 한 행이라도
생기면 그날 기준일이 미국 날짜가 되고, 그 날짜에는 국내 지표가 없어 **국내 전 프리셋이
「조건에 맞는 종목이 없다」**가 된다.

**걸리는 자리를 전수로 셌다.** `indicator_value`에 `max/min(trade_date)`를 묻는 곳은
**`IndicatorCalendar` 하나이고 질의가 셋**이며, 부르는 곳은 **`AsOfResolver` 하나**다.
그 위의 호출부는 넷(`ScreeningService` 하나 · `StockController` 셋)이고 **전부 시장을 유도할
재료를 이미 갖고 있다** — 앞은 `universeCode`를 정한 뒤에 부르고, 뒤 셋은 `stockId`를 갖는다.

## 버린 대안

- **`indicator_value`에 `market_code`를 비정규화한다** — ADR-0093 §버린 대안이 잰 유일한 안이고,
  7백만 행에 컬럼을 더하는 저장 구조 변경이다. 색인은 수백 행이면 같은 일을 한다.
- **`price_daily`에서 유도한다** — 싸지만 `IndicatorCalendar`가 일부러 지표에 묻는 이유
  (시세는 들어왔는데 지표 배치가 실패한 날)를 버린다.
- **종목별 lateral로 좁힌다** — ADR-0093이 46ms로 쟀다. 요청마다 도는 질의에 여전히 크다.
- **캐시를 둔다** — 배치가 쓰고 앱이 읽으므로 배치가 돈 뒤 앱의 캐시가 낡는다. 그 낡음은
  「화면이 어제 값을 본다」라 결함이고, TTL로는 못 닫는다.
- **잡 하나(백필)에만 색인 갱신을 단다** — 매일 도는 것은 `DailyUpdateJob`이다. 달지 않으면
  기준일이 **매일 하루씩** 벌어지는데 값은 전부 옳아 아무 증상이 없다.

## 결과

**쉬워지는 것**: 미국 지표가 생겨도 국내 기준일이 안 움직인다. ADR-0093이 미뤄 둔 셋 중
가장 위험한 하나가 닫혔다.

**어려워지는 것 — 남은 것 셋**

- **유니버스 미지정 요청은 활성 유니버스 전체의 시장 집합을 쓴다.** 지금 그것이 국내 둘이라
  기준일이 국내 달력에서 나오지만, **미국 유니버스가 활성이 되면 그 집합에 US가 들어가
  최댓값이 다시 미국 날짜가 될 수 있다.** 그때 필요한 것은 시장별 기준일로 판정을 가르는
  것이고(ADR-0093이 워치리스트에 세운 형태), **응답의 `asOf`가 하나이며 `screen(...)`이
  `trade_date = asOf` 한 장을 읽으므로** 그 변경은 질의 계약까지 간다.
  **미국을 켜는 단계에서 다시 연다.**
- **색인이 원본보다 넓어질 수 있다.** 원본이 줄었는데 `rebuild()`가 안 돌면 그렇다.
  위 premise가 그것을 신고하고 `rebuild()` 한 번으로 닫힌다.
- **ADR-0093의 나머지 둘은 그대로다** — `DailyUpdateJob.targetDateFor`(지금은 15일 창이
  흡수한다, ADR-0042)와 **미국 휴장일마다 오는 결손 알림**(연 9~10일, 켜는 즉시 발화한다).

**되돌리려면**: `IndicatorCalendar`의 시장 조건을 빼고 두 잡의 `rebuild()` 호출과 `MarketScope`를
지운 뒤 V37을 되돌린다. 그러면 `IndicatorCalendarTest`의 시장 분리 테스트가 붉는다 —
**되돌린 것이 조용하지 않다는 것이 그 테스트의 값이다.**
