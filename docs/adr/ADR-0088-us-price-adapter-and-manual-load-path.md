---
id: ADR-0088
status: 채택됨
date: 2026-08-29
applies_to:
  - "src/main/java/com/stockscreener/pipeline/datasource/us/AlpacaPriceAdapter.java"
  - "src/main/java/com/stockscreener/pipeline/datasource/CollectedMarkets.java"
  - "src/main/java/com/stockscreener/pipeline/collector/StockRegistrar.java"
  - "src/main/java/com/stockscreener/pipeline/batch/InitialLoadJob.java"
  # 시장을 안 가르고 price_daily의 거래일을 세던 자리. ADR-0093이 넷을 닫았고,
  # 백필의 날짜 합집합 하나만 의도로 남았다(§결과).
  - "src/main/java/com/stockscreener/pipeline/collector/PriceCalendar.java"
  - "src/main/java/com/stockscreener/indicator/IndicatorRetentionPolicy.java"
  - "docs/api-spec/alpaca-bars.md"
---

# ADR-0088. 미국 시세를 붙이되 수정계수는 1로 두고, 비활성 유니버스를 손으로 골라 적재한다

## 결정

미국 일별 시세를 Alpaca `/v2/stocks/bars`로 받는다. 결정은 셋이다.

1. **기업행위 없이 시작한다.** `adj_factor`가 미국 전 구간 1이고 `adj_close = close`다.
   **부채이고 만기가 있다 — 미국 지표를 만들기 전에 닫는다.**
   **적재된 구간에 대해서는 ADR-0090이 닫았다** — 원장이 차고 `adj_factor`가 실제로 들어갔다.
   **시세를 새 구간으로 넓히면 그만큼 다시 열린다**: 그 구간의 기업행위를 함께 받아야 한다.
2. **비활성 유니버스를 손으로 고르는 적재 경로를 연다.** 그 경로는 `universe.is_active`를
   보지 않으므로 **`CollectedMarkets`가 유일한 게이트**가 된다.
3. **수집 선언의 시점을 앞당긴다.** `CollectedMarkets.ALL`에 `US`가 「시세와 재무 어댑터가
   붙는 phase」가 아니라 **시세 어댑터가 붙는 시점**에 들어간다.

> 3은 `CollectedMarkets`의 javadoc이 스스로 적어 둔 조건을 푸는 판단이다.
> **사용자가 고른 문장(2026-08-29):** *"지금 더한다 (브리핑대로)"*.
> **그에 대한 내 해석 — 갈라 적는다:** 「선언의 시점을 시세 어댑터로 앞당긴다」.
> 사용자가 닫은 것은 *언제 더하는가*이고, *그 대가를 무엇으로 막는가*는 내 판단이다(§규칙).

## 규칙

- **MUST: `end`를 RFC-3339 시각으로 보내고 `now − 16분`으로 클램프한다.**
  날짜만 주면 뉴욕 기준 그날의 끝으로 해석되어 UTC로는 미래가 되고, **어제 날짜도 403**이다
  (실측). 그 403은 `AlpacaResponse.kindOf`가 `AUTH`로 접고 `AUTH`는 `fatal()`이라
  **종목 하나가 아니라 배치 전체가 죽는다** — 미국이 일일 갱신에 들어온 뒤라면 국내까지 함께.
  - **MUST NOT: 그 매핑을 `MALFORMED`로 낮춰 「고치지」 않는다.** 그러면 종목마다 조용히
    실패하고 배치는 부분 성공으로 끝나, 원인이 **우리 요청**이라는 사실이 묻힌다.
- **MUST: `feed`·`adjustment`·`limit`을 명시한다.** 기본값에 기대면 각각 단일 거래소 데이터,
  소스 계산 수정주가, 1000개 절단으로 **조용히** 갈린다.
- **MUST: `next_page_token`이 null이 될 때까지 순회하고 부분 결과를 반환하지 않는다.**
  정렬이 **심볼 우선**이라 중간에 멈추면 뒤쪽 심볼이 통째로 빈다(실측 13페이지).
- **MUST NOT: 소스가 주지 않는 값을 합성하지 않는다.** 거래대금·상장주식수는 null이다.
  `vw`(VWAP)는 거래대금의 대용이 아니다.
- **MUST: `splitAdjustedBySource = false`와 `adjustment=raw`를 함께 바꾼다.** 조정된 시세에
  원장 계수를 다시 곱하면 **분할비의 제곱**만큼 틀리는데 그 값은 그럴듯하다(ADR-0049).
- **MUST: 미국 `adj_factor`를 1이 아닌 값으로 만들기 전에 기업행위 원장을 채운다.**
  그리고 **MUST NOT: 미국 지표를 만들기 전에 이 부채를 남겨 두지 않는다** — 분할을 건너는
  이동평균·추세 게이트가 아무 에러 없이 틀린다.
- **MUST: 비활성 유니버스 경로가 0건이면 예외로 끝낸다.** 그것이 두 번째 겹(활성 유니버스
  조인)을 포기하는 대가다 — 조용한 0건은 "적재할 것이 없다"와 구별되지 않는다.
- **MUST NOT: 그 경로를 스케줄러·`AdminJobLauncher`에 걸지 않는다.** 자동 실행은 활성
  유니버스만 돈다 — 그래야 "활성"이 무엇을 뜻하는지가 한 가지로 남는다.
- **MUST: `US_LISTED`를 켜기 전에 재무·기업행위 어댑터를 붙인다.** 지금 그 다섯 잡이 미국을
  대상으로 삼지 않는 이유는 `CollectedMarkets`가 아니라 **그 유니버스가 비활성이라는 것**이고,
  켜는 순간 `CorporateActionJob`이 `requireSources`에서 `failFatally`로 끝나 **국내 수집까지
  멈춘다.** 무엇이 비어 있는지는 ADR-0086의 premise가 수로 답한다.

## 검증

```bash
./gradlew test --tests "*Alpaca*" --tests "*StockRegistrarLookupTest*" \
               --tests "*InitialLoadTargetSelectionTest*" --tests "*BootstrapArgumentGuardTest*"
```

`end`가 맨 날짜가 아닌지 · 403이 `AUTH`이고 `fatal()`인지 · `feed`/`adjustment`/`limit`이
쿼리에 있는지 · 페이지를 끝까지 도는지 · 비활성 유니버스가 손으로 골리는지 ·
재무를 건너뛰면 계획도 빠지는지가 여기서 걸린다.

```bash
./gradlew liveTest --tests "*AlpacaBarsProbe*"
```

소스가 아직 그대로인지를 지킨다. 실측값의 정본은 `docs/api-spec/alpaca-bars.md`다.

미국 시세가 어디까지 들어와 있는가 — **파일럿을 전량으로 오독하지 않게 사람이 확인한다:**

```bash
psql -d stock_screener -At -c "
SELECT count(DISTINCT p.stock_id) || '종목 / ' || count(*) || '행 / '
       || coalesce(min(p.trade_date)::text, '-') || '~' || coalesce(max(p.trade_date)::text, '-')
FROM price_daily p JOIN stock s ON s.id = p.stock_id WHERE s.market_code = 'US'"
```

```premise 미국 지표가 미보정 시세 위에 서 있지 않다
psql -d stock_screener -At -c "
WITH scoped AS (
  -- 종목마다 EXISTS로 묻는다. DISTINCT i.stock_id 는 같은 집합을 내지만
  -- indicator_value 전수를 훑는다(실측 1,519 ms vs 7.75 ms · 집합 동일성은
  -- EXCEPT 양방향 0으로 확인). 미국 종목 수만큼 idx_iv_stock_date 를 찌르는 쪽이 싸다.
  SELECT s.id AS stock_id FROM stock s
  WHERE s.market_code = 'US'
    AND EXISTS (SELECT 1 FROM indicator_value i WHERE i.stock_id = s.id)),
t AS (
  SELECT (SELECT count(*) FROM price_daily p JOIN stock s ON s.id = p.stock_id
           WHERE s.market_code = 'US') AS bars,
         (SELECT count(*) FROM scoped) AS stocks,
         (SELECT count(DISTINCT c.stock_id) FROM corporate_action c
           JOIN scoped x ON x.stock_id = c.stock_id) AS with_actions,
         (SELECT count(DISTINCT p.stock_id) FROM price_daily p
           JOIN scoped x ON x.stock_id = p.stock_id WHERE p.adj_factor <> 1) AS adjusted)
SELECT CASE
  WHEN bars = 0 THEN '잴 것이 없었다 — 미국 시세가 한 행도 없다'
  WHEN stocks = 0 THEN 'OK 미국 지표가 없다 — 수정계수 부채가 아직 아무것도 떠받치지 않는다 (시세 '
                       || bars || '행)'
  WHEN with_actions > 0 OR adjusted > 0
    THEN 'OK 지표가 있는 미국 ' || stocks || '종목 중 원장이 닿은 것이 ' || with_actions
         || '종목, 보정된 봉을 가진 것이 ' || adjusted || '종목이다'
  ELSE '지표가 있는 미국 ' || stocks || '종목 전부에 기업행위 원장도 보정된 봉도 없다 — '
       || 'adj_factor가 전 구간 1이라 분할을 건너는 이동평균·추세 게이트가 아무 에러 없이 '
       || '틀린다(ADR-0088의 부채가 만기를 넘겼다)'
  END FROM t"
```

**이 전제가 묻는 것은 시세의 유무가 아니라 부채의 만기다.** 지금은 미국 지표가 없어 조용하고,
**지표를 만드는 순간 원장이 비어 있으면 발화한다** — 그 시점이 정확히 이 결정이 미뤄 둔 값을
치러야 하는 때다.

**MUST: 모집단을 「지표가 있는 종목」으로 좁힌다.** 처음에는 미국 **전체**의 원장 행 수와 보정된
봉 수를 세었는데, 그러면 **파일럿 한 종목이 전 종목의 부채를 대변한다** — 실측으로 원장 1건
(NVDA)·보정된 봉 110행이 이미 있어 그 형태는 **어떤 규모의 미국 적재에도 도달 불가능**했다
(언제나 `OK`). 부채는 「미국에 원장이 있는가」가 아니라 **「지표가 선 그 종목들에 원장이
닿았는가」**다.

**이 전제가 못 보는 것도 적는다.** 좁힌 뒤에도 판정은 **집합 전체에 대해 하나**라, 1,000종목
중 한 종목만 보정돼 있어도 `OK`다. 종목마다 물을 수 없는 이유는 **분할이 없는 종목의 원장
0건이 정상**이기 때문이다 — 「사건이 없다」와 「원장을 안 돌렸다」를 데이터로 가를 수 없다.
그것을 가르는 것은 이 펜스가 아니라 **적재 절차**이고, `batch-load` skill이 두 잡을 이어
돌리라고 적는 자리가 그것이다.

## 배경

미국 유니버스는 이미 있었고(8,504종목) 남은 것이 시세·재무·지표였다. 시세를 먼저 붙였다.

**착수 전에 잰 것 중 계획을 실제로 바꾼 것이 둘이다.**

**첫째, `end`.** 개발가이드는 15분 규칙을 문서로 적었지만 *날짜만 주는 `end`가 어제도
막힌다*는 것은 적지 않았다. 그것을 모르면 `InitialLoadJob`이 기본값으로 넘기는
`asOf = 오늘`이 그대로 403이 되고, 그 403은 종목 실패가 아니라 **배치 전체의 죽음**이다.

**둘째, 파일럿의 창.** 처음 계획은 「최근 2년」이었다. 그런데 `IndicatorRetentionPolicy`,
`PriceCalendar`, `IndicatorBackfillJob`이 **시장을 가르지 않고 `price_daily`의 거래일을 센다.**
보존창 안에 국내 휴장 평일이 18일 있고 그중 **미국 개장이 14일**이다(2026-09-08 실측).
그 9일이 들어오면 보존 경계가 9거래일 뒤로 밀리는데, `purge()`는 `indicator_value` 기준이라
**어긋남이 자연 소멸하지 않고** ADR-0045의 premise가 원인을 틀리게 가리키며 상시 발화한다.
그래서 파일럿을 **2024년**으로 옮겼다 — 현재 181번째 거래일이 2025-12-01이라 2024년 일자는
그 집합에 들어올 수 없다. 적재 뒤 경계가 2025-12-01 그대로임을 확인했다.

**미국 시세가 매일 들어오기 시작하면 그 자리들을 먼저 고쳐야 한다.** 파일럿은 그것을
피한 것이지 고친 것이 아니다 — **넷은 ADR-0093이 닫았고 화면 기준일은 ADR-0095가 닫았다.**

## 버린 대안

- **기업행위를 함께 붙인다** — 사용자 결정으로 범위 밖(2026-08-29). 되돌릴 수 있다는 것이
  근거다: 원장을 나중에 채우고 `adjustment-rebuild`를 돌리면 전 구간이 다시 지어진다.
  **다만 지표를 만들기 전이어야 한다** — 그것이 §규칙의 만기다.
- **여러 종목을 한 콜로 묶는다** — 콜 수가 크게 준다(200개 묶음으로 8,495종목이 43콜, 실측).
  버린 이유는 **실패 격리의 단위가 종목이 아니게 되기 때문**이다(`data-pipeline.md`의 MUST,
  ADR-0007). 그리고 모르는 심볼이 응답에서 조용히 빠지므로, 묶으면 **한 종목의 결측과 묶음
  전체의 실패가 같은 모양**이 된다. 탐침은 묶는다 — 거기서는 격리가 목적이 아니다.
- **`asof=-`로 순수 데이터를 받는다** — 리네임 이전 봉이 빠져 **시계열이 리네임 지점에서
  끊긴다.** 2년 창의 이동평균이 그 지점을 건너므로, 원자료 보존보다 연속성을 골랐다.
  대가는 「이 봉이 어느 티커로 체결됐는가」가 사라지는 것이다.
- **`registry.hasPriceSource()`로 수동 경로의 시장을 좁힌다** — `CollectedMarkets`를 안
  건드려도 됐다. 버린 이유는 사용자가 3을 골랐기 때문이고, 그 안 자체도 ADR-0087이
  §버린 대안에서 기각한 형태다(*"등록된 어댑터가 있는 시장으로 거른다"*).
- **`US_LISTED`를 켜서 기존 경로로 적재한다** — 켜는 순간 지표 하나 없는 8,504종목이
  프리셋 결과에 실리고(ADR-0087) `MAX_ROWS = 2000`이 상시 발화한다(ADR-0016).
- **`InitialLoadJob`과 별개인 미국 전용 잡을 만든다** — 「초기 적재」의 정의가 둘로 갈리고
  대상 선정·재개 로직이 사본이 된다. 이 잡이 이미 그 사본 때문에 결함을 갖고 있었다.

## 결과

**쉬워지는 것**: 미국 시세를 종목당 1콜로 받는다. 어댑터가 `PriceDataSource` 하나라
`PriceCollector`·`PriceWriter`·`InitialLoadJob`이 그대로 미국을 처리한다.

**어려워지는 것**

- **미국 `adj_factor`가 전 구간 1이다.** 분할·병합이 반영되지 않은 시세이며, 지표를 만들기
  전에 닫아야 한다. 위 premise가 그 만기를 지킨다.
  **부채는 가설이 아니라 실물이다** — 파일럿 창 안에 **10:1 분할이 하나 이미 들어와 있다**
  (NVDA 2024-06-10, 종가 1208.88 → 121.79). 저장된 11종목 2,772행에서 `PriceDiscontinuity`의
  임계(1.30/0.70)를 넘는 불연속은
  그 하나뿐이다(실측). 그 종목의 이동평균·추세 게이트는 **원장을 채우기 전까지 그 경계에서
  한 자릿수 틀린다.**
- **`CollectedMarkets`가 여섯 축의 선언인데 채워진 축이 하나다.** 목록이 「모으기로 했다」를
  뜻하는 이상, 미국을 켜는 날 나머지 다섯이 함께 있어야 한다.
- **수동 적재 경로가 「전 종목」의 네 번째 정의이고 잡 셋에 걸린다**(ADR-0090이 둘을 더했다).
  `StockRegistrar` 안에 두고 MUST NOT을
  붙였지만, 자동 경로가 그것을 부르기 시작하면 활성 유니버스의 뜻이 갈린다.
- **시장을 안 가르던 거래일 읽기 다섯 자리 중 넷은 ADR-0093이 닫았다.**
  `PriceCalendar`의 셋과 `IndicatorRetentionPolicy.writeCutoff`가 시장을 받는다.
  **남은 하나(`IndicatorBackfillJob.tradeDatesBetween`)는 의도다** — 날짜 루프가 묻는 것은
  *어느 날짜를 한 번씩 돌 것인가*이고 그 답이 합집합이라, ADR-0093 §규칙이 가르는 것을 금지했다.

**되돌리려면**: `AlpacaPriceAdapter`를 지우고 `CollectedMarkets.ALL`에서 `US`를 빼고
ADR-0087의 premise 리터럴을 되돌린다. 적재된 미국 봉은 `price_daily`에서 시장으로 지운다 —
그것을 참조하는 지표·원장 행이 없는 동안에만 깨끗하다 — **원장 행은 이제 있으므로**
(ADR-0090) 봉을 지우기 전에 `corporate_action`에서 미국 행을 먼저 지운다.
