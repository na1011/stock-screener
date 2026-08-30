---
id: ADR-0011
status: 채택됨
date: 2026-08-06
applies_to:
  - "src/main/java/com/stockscreener/indicator/**"
  - "src/main/java/com/stockscreener/pipeline/batch/**"
---

# ADR-0011. 지표 값은 180거래일만 보관하고 원자료는 전량 보관한다

## 결정

`indicator_value`는 최근 **180거래일**만 보관하고 그 이전은 배치가 지운다. 원자료(`price_daily`·`financial_statement`)는 **지우지 않는다.**

## 규칙

- MUST: 원자료는 전량 보관한다. 지표는 원자료에서 재생성할 수 있지만 그 역은 불가능하다.
- MUST: 보존 기간을 늘리는 것은 언제든 가능하되, **줄이는 변경은 지운 뒤 되돌릴 수 없다**는 점을 전제로 판단한다(백필로 복구되지만 850종목 × N일의 재계산 비용이 든다).
- MUST NOT: 보존 정리를 일일 갱신과 같은 트랜잭션에 넣지 않는다. 정리가 실패해도 그날의 수집·계산은 남아야 한다.
- MUST NOT: 백분위(`_PCTL`)에만 다른 보존 기간을 두지 않는다. 같은 테이블의 같은 성격의 행이며, 분리하면 "어떤 지표가 어디까지 남아 있는가"를 매번 확인해야 한다.

## 검증

```bash
./gradlew test --tests "*IndicatorRetentionTest*"
```
보존 기간을 넘긴 행이 지워지고 그 안쪽은 남는지, 원자료는 그대로인지 확인한다.

```premise 지표가 180거래일 안에만 남아 있다
psql -d stock_screener -At -c "
WITH RECURSIVE t AS (
  SELECT min(trade_date) d FROM indicator_value
  UNION ALL SELECT (SELECT min(trade_date) FROM indicator_value iv WHERE iv.trade_date > t.d)
  FROM t WHERE t.d IS NOT NULL),
src AS (SELECT count(*) AS n FROM t WHERE d IS NOT NULL),
idx AS (SELECT market_code, count(*) AS n FROM indicator_trade_date GROUP BY market_code),
allidx AS (SELECT count(DISTINCT trade_date) AS n FROM indicator_trade_date),
over AS (SELECT market_code, n FROM idx WHERE n > 180)
SELECT CASE
  WHEN (SELECT n FROM src) = 0 THEN '지표가 한 행도 없다 — 잴 것이 없었다'
  WHEN (SELECT count(*) FROM idx) = 0
    THEN '지표는 ' || (SELECT n FROM src) || '거래일 있는데 indicator_trade_date가 비었다 — '
         || '색인이 원본과 갈렸다. IndicatorTradeDateIndex.rebuild가 그 자리다'
  WHEN (SELECT n FROM allidx) <> (SELECT n FROM src)
    THEN '색인이 원본과 갈렸다: indicator_trade_date ' || (SELECT n FROM allidx)
         || '거래일 대 indicator_value ' || (SELECT n FROM src)
         || '거래일 — 보존을 재기 전에 색인부터 다시 짓는다(IndicatorTradeDateIndex.rebuild)'
  WHEN (SELECT count(*) FROM over) = 0
    THEN 'OK ' || (SELECT string_agg(market_code || ' ' || n || '거래일', ', ' ORDER BY market_code)
                   FROM idx)
  WHEN (SELECT max(n) FROM over) = 181
    THEN (SELECT string_agg(market_code || ' 181거래일', ', ' ORDER BY market_code) FROM over)
         || '이 남아 있다(보존 180) — 하루면 긴 백필이 경계를 앞지른 대가이고 다음 정리가 지운다'
         || '(ADR-0045 §긴 백필). 앱을 띄워 일일 갱신을 돌린다.'
  ELSE (SELECT string_agg(market_code || ' ' || n || '거래일', ', ' ORDER BY market_code) FROM over)
       || '이 남아 있다(보존 180). 하루가 아니면 정리가 오래 돌지 않았거나 백필이 보존 밖을 되살렸다.'
END"
```
**MUST: 시장마다 센다.** 전 시장 합집합으로 세면 **달력이 다른 시장의 봉이 들어오는 것만으로**
이 전제가 발화한다 — 국내 거래일과 NYSE 세션을 합치면 보존창 경계가 2025-12-01에서
2025-12-12로 밀리고, 그러면 국내 지표 **18거래일**이 「보존 초과」로 신고된다(2026-08-31 실측).
값은 멀쩡한데 렌즈가 합집합이라 생기는 차이이고, `IndicatorRetentionPolicy.purge`는 이미
시장별이다.

**모집단이 `indicator_trade_date`인 이유는 비용이다.** `indicator_value`에 `stock`을 조인하면
0.099ms가 1,333ms가 된다(V37 실측 7,179,721행). 대신 그것은 **파생 색인**이라 원본과 갈릴 수
있으므로, **갈림을 먼저 묻고** 갈렸으면 보존을 재지 않는다 — 원본 쪽 거래일 수는 재귀 CTE로
0.024초에 나온다(실측).

운영 적재 이후, 남은 거래일이 시장마다 180 이내여야 한다. **`n = 0`을 따로 답하는 것이 요점이다** — 빈 테이블이 "OK"로 읽히면 보존은 지켜지는데 지표가 통째로 사라진 상태를 놓친다. `trade_date`가 `NOT NULL`이라 **「행이 없다」와 「거래일 0」은 정확히 같다.**

**MUST NOT: `count(DISTINCT trade_date)`로 되돌리지 않는다.** 전수 Index Only Scan이라 566만 행에서 **3.79초**로 타임아웃한다(2026-08-19). 재귀 CTE는 `trade_date`가 pkey의 **선두**인 것을 써서 거래일 수만큼만 인덱스를 찔러 **힙 페치 138건 · 0.035초**다. **vacuum과 무관하지는 않다** — `min()`이 아래 `purge()`가 지우는 왼쪽 끝에서 출발해 `min()` 456버퍼 대 `max()` 6버퍼다.

**이 전제가 `statement timeout`으로 판정 불가가 되면 인덱스/힙 비를 먼저 잰다 — 그 비가 2배를 넘을 때에 한해 인덱스 블로트다.**

```bash
psql -d stock_screener -At -F'|' -c "SELECT indexrelname, pg_size_pretty(pg_relation_size(indexrelid)) FROM pg_stat_user_indexes WHERE relname='indicator_value' ORDER BY pg_relation_size(indexrelid) DESC"
```

2026-08-10에 실제로 그랬다 — 힙 840MB에 인덱스 **2,348MB(2.8배)**였고 `REINDEX INDEX CONCURRENTLY` 셋이 199MB로 되돌렸다. **그것이 하루 만에 803MB가 된 이유는 ADR-0045가 정본이다**(일일 갱신이 읽기 창 전체를 다시 썼다). 재발 여부는 ADR-0045의 블로트 펜스가 상시로 잰다.

**MUST: 그 비가 2배 아래면 REINDEX가 아니라 VACUUM이다.** 2026-08-19에 비가 **1.10배**(925MB / 840MB)인데 같은 무판정이 났다. 실체는 `ON CONFLICT DO UPDATE`가 쌓은 dead tuple이 visibility map을 무효화해 Index Only Scan이 힙을 랜덤으로 읽는 것이다(566만 중 **486만이 힙 페치**). **`last_autovacuum`이 마지막 쓰기보다 앞서고 `n_dead_tup`이 문턱(`0.2 × live`) 바로 아래 멎으면 아무도 청소하지 않는다** — 실제로 1,006,783 대 1,138,162였고, `VACUUM` 한 번(2.6초)에 3.79초가 **0.32초**가 됐다. 느리면 최적화 전에 `pg_stat_user_tables`의 그 둘을 먼저 본다.

**정리(purge) 직후가 그 상태의 최대 시점이다** — 삭제된 행의 자리는 인덱스에서 바로 회수되지 않는다. 그 사이 판정 불가는 침묵이 아니라 무판정이므로, "전제가 깨졌다"로도 "지켜진다"로도 읽지 않는다.

## 배경

보존 기간을 둘지는 판단 사항으로 남아 있던 항목이다. 850종목 × 150지표 × 500일 ≈ 64M행 / 6.7GB로 추정되며, 이 프로젝트는 맥북 온프레미스에서 돌아 디스크가 곧 비용이다(spec §2 "인프라 운영 비용 최소화").

핵심 관찰은 **스크리닝이 언제나 최신 일자 한 장만 읽는다**는 것이다. 조건 판정은 `trade_date = (최신일)`이고, 과거 지표 시계열이 필요한 차트는 v1 범위 밖이다(spec §10). 즉 오래된 지표 행은 저장 비용만 발생시키고 아무도 읽지 않는다.

180일을 고른 이유는 셋이다. ① 200일 이동평균의 워밍업이 200거래일이므로, 그보다 짧게 잡아도 계산에는 지장이 없다 — 계산 입력은 `price_daily`이지 `indicator_value`가 아니다. ② 분기 재무가 한 번 이상 갱신되는 기간을 덮어, 정정공시 무효화 후 재계산할 구간이 대개 이 안에 들어온다. ③ ADR-0002의 성능 실측이 60일 기준이었는데, 180일이면 그 3배인 23M행 규모에서도 커버링 인덱스가 최신 일자만 훑으므로 조회 비용이 사실상 늘지 않는다.

## 버린 대안

- **무기한 보관** — 가장 단순하지만 6.7GB가 계속 늘고, 그 대부분을 아무도 읽지 않는다. 온프레미스 디스크에서는 이것이 실제 비용이다.
- **60일** — ADR-0002 벤치마크의 전제이고 저장량이 가장 작다. 그러나 정정공시로 무효화되는 구간(직전 분기 시작일 이후)이 60일을 넘는 경우가 흔해, 무효화 후 재계산해야 할 구간이 보존 구간 밖에 있는 상황이 생긴다.
- **원자료도 함께 정리** — 저장량은 크게 줄지만 **되돌릴 수 없다.** 지표는 원자료에서 언제든 재생성되는 반면 원자료는 외부 API에서 다시 받아야 하고, 그 API가 과거 구간을 계속 제공한다는 보장이 없다(Finnhub가 실제로 그렇게 바뀌었다 — ADR-0010).
- **지표별로 다른 보존 기간** — 이론상 최적이지만 "이 지표는 언제까지 있는가"가 지표마다 달라져, 조건을 만들 때마다 그것을 확인해야 한다. 절약되는 양이 그 복잡도를 정당화하지 못한다.

## 결과

- 쉬워지는 것: 저장량이 180/500 수준으로 내려간다. 조회 성능은 최신 일자만 읽으므로 영향이 없다.
- 어려워지는 것: 과거 지표를 보려면 백필을 돌려야 한다. v1에 그 화면이 없으므로 지금은 비용이 아니다.
- 되돌리려면: 보존 기간을 늘리는 것은 상수 하나지만, 이미 지워진 구간은 `IndicatorBackfillJob`으로 다시 계산해야 한다. 원자료가 남아 있으므로 복구는 항상 가능하다 — 그것이 원자료를 지우지 않는 이유다.
