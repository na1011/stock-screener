---
id: ADR-0091
status: 채택됨
date: 2026-08-29
applies_to:
  - "src/main/java/com/stockscreener/pipeline/datasource/us/AlpacaMarketCalendarAdapter.java"
  - "src/main/java/com/stockscreener/pipeline/datasource/MarketCalendarDataSource.java"
  - "src/main/java/com/stockscreener/pipeline/collector/MarketCalendarCollector.java"
  - "src/main/java/com/stockscreener/pipeline/collector/MarketSessionCalendar.java"
  - "src/main/java/com/stockscreener/pipeline/collector/BarGapReader.java"
  - "src/main/java/com/stockscreener/pipeline/batch/MarketCalendarJob.java"
  # 「공휴일 달력을 두지 않는다」의 사본이 여기 있다. 이 결정이 그 전제를 미국에서만 뒤집는다.
  - "src/main/java/com/stockscreener/pipeline/collector/MarketCalendar.java"
  - "src/main/resources/db/migration/V35__market_calendar.sql"
  - "docs/api-spec/alpaca-calendar.md"
---

# ADR-0091. 세션 달력을 거래소 단위로 담고, 「없음」을 종목별 창 안에서만 판정한다

## 결정

미국 거래소 세션 달력을 Alpaca Trading API `/v3/calendar/{market}`로 받는다. 결정은 셋이다.

1. **표의 grain이 시장이 아니라 거래소이고, 조인 키가 `stock.listing_exchange`다.**
2. **판정 구간을 종목마다 좁히고, 반대 방향(「비세션인데 봉이 있다」)을 값으로 갖는다.**
3. **미래 세션을 담지 않는다** — `--as-of`가 오늘보다 뒤면 거절한다.

> **사용자가 고른 문장(2026-08-29):** *"멈추고 돌아온다"*(탐침 뒤 검문) ·
> *"함께 고친다"*(기존 어댑터의 401 오분류).
> **그에 대한 내 해석 — 갈라 적는다:** 「탐침 출력을 계약 문서로 옮긴 뒤 표의 grain·적재 창·
> 페이징 처리를 그 수 위에서 확정한다」 · 「봉투 판정을 상태 우선으로 바꾸고 세 어댑터가
> 그것을 함께 쓴다」. 사용자가 닫은 것은 *언제 다시 묻는가*와 *기존 코드를 건드릴지*이고,
> **grain·판정값·창의 끝은 내 판단이다**(§규칙).

## 규칙

- **MUST: grain을 거래소로 두고 `stock.listing_exchange`로 조인한다.**
  국내 종목은 그 컬럼이 **전량 NULL**이라 달력과 **원리적으로 조인되지 않는다**(실측: 조인 0행).
  「국내를 달력으로 판정하지 마라」가 규칙이 아니라 **구조**가 된다.
  **MUST NOT: `market_code`로 잡지 않는다** — 그 보호가 사라지고, 달력 소스가 없는 국내가
  「전부 휴장」으로 읽힐 자리가 생긴다.
- **MUST: 판정 구간을 `[그 종목의 첫 봉, 마지막 봉] ∩ [그 거래소 달력의 min, max]`로 좁힌다.**
  안 좁히면 마지막 봉 이후의 모든 세션이 결측이 되어 **첫 실행이 오탐만 낸다** —
  파일럿에서만 산술로 4천 건이 넘는다. **오탐만 내는 판정기는 안 만든 것과 같다.**
  같은 좁힘이 **공개 지연도 함께 닫는다**: 오늘·어제 세션은 어느 종목의 마지막 봉보다 뒤다.
- **MUST: 「비세션인데 봉이 있다」를 판정값으로 갖고 계속 잰다.**
  오류가 대칭이 아니다 — 열린 날을 「닫혔다」고 하면 **수집 실패가 휴장으로 접혀 영구히
  침묵**하고 그 상태는 정상과 같은 모양이다. 반대는 시끄러울 뿐 보인다. 그리고 **적재 창의
  가운데 구멍은 `min/max` 논증으로 안 막힌다** — 이 값이 그 유일한 값싼 신호다.
- **MUST NOT: 판정을 boolean으로 내지 않는다.** `false`가 「닫혔다」와 「모른다」를 합치고,
  그 합침이 이 축이 없애려는 바로 그것이다. 적재 창 밖과 달력 없는 거래소는 **판정불가**다.
- **MUST: `--as-of`가 미래면 거절한다.** 소스가 **2030년부터 휴장일을 모르면서 평일을 그대로
  세션으로 준다**(실측: 알려진 휴장이 2029년 10일 → 2030년 1일 → 2031년 0일). 담으면
  크리스마스가 개장일이 되고 봉이 없는 것이 전부 결측으로 뒤집힌다. **기본값이 마침 안전한
  것과 위험한 값을 거절하는 것은 다르다.**
- **MUST: `session_date`를 소스의 `date` 원문으로 저장한다. MUST NOT: 시각에서 유도하지 않는다.**
  `timestamptz`는 절대 시각이라 UTC로 환산하면 뉴욕 오후가 다음 날로 밀린다. 벽시계 시각은
  `AT TIME ZONE 'America/New_York'`으로 되찾는다.
- **MUST: 창 안에서 소스가 안 준 세션 행을 지운다. MUST NOT: 창 밖을 건드리지 않는다.**
  UPSERT만이면 표가 append-only가 되어 **철회된 세션이 영구히 개장일로 남고**, 창을 안 좁혀
  지우면 **적재 창이 조용히 좁아진다.**
- **MUST: `bar-gap-review`를 고아 실행 스위퍼에서 뺀다.** 스윕은 기동 시각 이전의
  `MANUAL` + `RUNNING`을 전부 닫으므로 **다른 JVM에서 지금 도는 적재의 실행 행까지 마감한다.**
  갭 조회는 정의상 적재 중·직후에 치는 명령이다(ADR-0084가 원장 조회에서 만난 자리와 같다).
  - **MUST: 그 집합을 `--ca-*` 게이트와 갈라 둔다.** 하나로 두면 읽기 전용 잡을 더할 때
    `--ca-*`까지 함께 열리거나, 안 열려고 빼면 스윕이 돈다.
- **MUST NOT: 달력으로 「시장을 안 가르고 거래일을 세는 자리」를 고치지 않는다.**
  그 자리들이 답하는 것은 **「우리가 봉을 가진 날」**이고 달력이 답하는 것은 **「장이 선 날」**
  이라 뜻이 다르다. 바꾸면 국내가 「달력 없는 시장」이 되어 기준일·보존 경계·백필 대상이 밀린다.
  그것은 **시장 분리**가 필요한 것이지 달력이 필요한 것이 아니다.
  **ADR-0093이 그 분리를 했다** — 「일곱」이라 적었던 수는 전수로 세니 더 많았고, 화면 기준일
  하나가 비용 때문에 아직 남아 있다(그쪽 §결과가 조건을 적는다). **이 금지는 그대로다.**
- **적재는 평일 13:30 일일 갱신 뒤에 돈다**(`PipelineScheduler#runDailyUpdate` · 2026-10-31). 미국 장은
  한국 시각 오전 5~6시에 닫으므로 13:30이면 대상일 세션이 끝나 있다 — 「13:30 크론이 뜻을 갖지 않는다」로
  크론 밖에 두었더니 달력이 2026-08-28에 멈췄다. `AdminJobLauncher`에는 **적재 잡만** 둔다 — 조회 잡은 실행 행을
  안 만들어 화면이 202를 받고 아무것도 안 뜬다.

## 검증

```bash
./gradlew test --tests "*MarketCalendar*" --tests "*BarGap*" --tests "*AlpacaMarketCalendar*" \
               --tests "*BootstrapArgumentGuard*" --tests "*AdminJobLauncher*"
```

봉투 키 누락이 `MALFORMED`인지 · HTML 401이 `AUTH`인지 · 빈 배열이 정상인지 · 뒤집힌 창이
콜을 안 태우는지 · 두 번째 실행이 0행인지 · 철회 세션이 지워지는지 · **창 밖을 안 건드리는지** ·
**종목별 클램프가 양 끝을 다 막는지** · **국내가 판정 구간에 안 드는지** · 미래 `--as-of`가
거절되는지 · **`bar-gap-review`가 스위퍼에서 빠져 있는지**가 여기서 걸린다.

```bash
./gradlew liveTest --tests "*AlpacaCalendarProbe*" --tests "*AlpacaMarketCalendarLive*"
```

소스가 아직 그대로인지를 지킨다. 실측값의 정본은 `docs/api-spec/alpaca-calendar.md`다.

```premise 미국 봉 결측이 달력으로 설명된다
psql -d stock_screener -At -c "
WITH cov AS (SELECT exchange_code, min(session_date) lo, max(session_date) hi
               FROM market_calendar GROUP BY exchange_code),
scoped AS (SELECT s.id, s.ticker, s.listing_exchange ex,
                  greatest(min(p.trade_date), c.lo) lo, least(max(p.trade_date), c.hi) hi
             FROM stock s JOIN cov c ON c.exchange_code = s.listing_exchange
             JOIN price_daily p ON p.stock_id = s.id
            GROUP BY s.id, s.ticker, s.listing_exchange, c.lo, c.hi
           HAVING greatest(min(p.trade_date), c.lo) <= least(max(p.trade_date), c.hi)),
-- 세션인데 봉 없음 = 창 안 세션 수 - 세션 위 봉 수. (거래소, 세션일)과 (종목, 거래일)이 둘 다 PK라
-- 정확히 같다. 세션마다 봉을 찌르는 형태는 203만 번 탐침에 1.8초였다(2026-10-03).
missing AS (SELECT (SELECT count(*) FROM scoped sc
                      JOIN market_calendar mc ON mc.exchange_code = sc.ex
                       AND mc.session_date BETWEEN sc.lo AND sc.hi)
                 - (SELECT count(*) FROM scoped sc
                      JOIN price_daily p ON p.stock_id = sc.id AND p.trade_date BETWEEN sc.lo AND sc.hi
                      JOIN market_calendar mc ON mc.exchange_code = sc.ex AND mc.session_date = p.trade_date) AS n),
extra AS (SELECT sc.ticker, p.trade_date d FROM scoped sc
            JOIN price_daily p ON p.stock_id = sc.id AND p.trade_date BETWEEN sc.lo AND sc.hi
            LEFT JOIN market_calendar mc ON mc.exchange_code = sc.ex AND mc.session_date = p.trade_date
           WHERE mc.exchange_code IS NULL)
SELECT CASE
  WHEN (SELECT count(*) FROM market_calendar) = 0
    THEN '잴 것이 없었다 — 세션 달력이 한 행도 없다. --job=market-calendar 를 돌린다'
  WHEN (SELECT count(*) FROM scoped) = 0
    THEN '잴 것이 없었다 — 달력이 있는 거래소에 상장된 종목 중 봉을 가진 것이 없다'
  WHEN (SELECT count(*) FROM extra) > 0
    THEN (SELECT count(*) FROM extra) || '행이 비세션인데 봉이 있다: '
         || (SELECT string_agg(ticker || ' ' || d, ', ') FROM (SELECT * FROM extra LIMIT 10) x)
         || ' — 달력이 세션을 빠뜨렸다. 이쪽이 먼저다: 열린 날을 닫혔다고 하면 수집 실패가 휴장으로 접혀 침묵한다'
  ELSE 'OK 달력이 세션을 빠뜨린 곳이 없다 — 판정 대상 ' || (SELECT count(*) FROM scoped)
       || '종목 · 세션인데 봉 없음 ' || (SELECT n FROM missing)
       || '행(무거래·비활성·거래정지·수집 실패가 안 갈린 수이고 발화 조건이 아니다)'
  END"
```

**MUST: `missing`으로 발화하지 않는다 — 세는 것은 맞고 발화 조건이 아니다.** 이 결정이
스스로 *"`BAR_ON_NON_SESSION`이 먼저다 … 반대 방향은 시끄러울 뿐이다"*로 적어 둔 그 우선순위를
전제에도 그대로 건 것이다. 종목·구간이 늘면 `missing`은 **반드시** 생긴다 — 1,005종목 × 7년에서
**2,778행**이고(2026-08-31 실측) 그중 무거래·비활성·거래정지·수집 실패가 안 갈린다.
파일럿이 11종목 × 1년이라 0이었을 뿐이다.

**상시 붉은 펜스는 곧 무시되고, 그러면 `extra`까지 함께 묻힌다.** 그쪽이 이 결정이 실제로
지키려는 것이다 — **달력이 세션을 빠뜨리면 수집 실패가 휴장으로 접혀 영구히 침묵한다.**
그래서 `missing`은 `OK` 문장 안에 수로 싣고, 붉히는 것은 `extra`만 남긴다.

**두 방향을 한 전제에 둔 이유는 같은 스캔이기 때문이고, 순서를 「비세션인데 봉 있음」 먼저로
둔 이유는 그쪽이 치명적 방향이기 때문이다.** 모집단이 비는 경우를 둘로 갈랐다 —
「달력이 없다」와 「판정할 종목이 없다」는 처방이 다르다.

```premise 세션 달력이 미국 밖 종목에 안 걸린다
psql -d stock_screener -At -c "
SELECT CASE
  WHEN (SELECT count(*) FROM market_calendar) = 0
    THEN '잴 것이 없었다 — 세션 달력이 한 행도 없다'
  WHEN (SELECT count(*) FROM stock WHERE market_code <> 'US') = 0
    THEN '잴 것이 없었다 — 미국 밖 종목이 하나도 없다'
  WHEN (SELECT count(*) FROM market_calendar mc JOIN stock s
          ON s.listing_exchange = mc.exchange_code WHERE s.market_code <> 'US') = 0
    THEN 'OK 달력이 미국 밖 종목에 안 걸린다 (' || (SELECT count(*) FROM stock WHERE market_code <> 'US')
         || '종목 · 달력 ' || (SELECT count(*) FROM market_calendar) || '행)'
  ELSE (SELECT count(DISTINCT s.id) FROM market_calendar mc JOIN stock s
          ON s.listing_exchange = mc.exchange_code WHERE s.market_code <> 'US')
       || '종목이 달력에 걸린다 — listing_exchange가 미국 전용이라는 전제가 깨졌다. '
       || '달력 소스가 없는 시장이 「전부 휴장」으로 읽힌다'
  END"
```

**이 전제가 지키는 것은 데이터가 아니라 설계다.** 국내를 판정에서 빼는 것이 규칙이 아니라
조인 키의 성질이라는 주장이 참인 동안만 이 축의 비대칭이 안전하다.

## 배경

미국 봉이 없는 날을 **휴장·무거래·비활성·거래정지**로 가를 수단이 없었다. 그 결손은 여섯 곳에
명시로 적혀 있었고(두 계약 문서 · P20 축④ · P21 축③ · `progress.md` · V34의 컬럼 주석),
`V34`의 주석은 **달력을 거래소마다 조회할 것**까지 미리 약속해 뒀다.

**어댑터를 쓰기 전에 탐침으로 잰 것 중 계획을 실제로 바꾼 것이 넷이다.**

**첫째, 2030년부터 소스가 휴장일을 모른다.** 평일 수와 세션 수의 차이로 재면 2029년까지 9~11일
인데 2030년 1일, 2031년 0일이다. **응답의 모양이 똑같아** 받아 보기 전에는 안 드러난다.
그래서 창의 끝을 오늘로 막고, 기본값이 아니라 **거절**로 막았다.

**둘째, 페이징이 없다.** `next_page_token` 키 자체가 안 오고 11년치가 한 응답(2,765세션·805KB)
이다. 순회 루프를 없앴다 — 없는 것을 도는 코드는 도는지 확인할 방법도 없다.

**셋째, 401 본문이 JSON이 아니라 nginx HTML이다.** 이것이 **기존 어댑터 둘의 결함**을 드러냈다:
`toEntity(JsonNode.class)`가 상태코드를 보기 전에 터지고 그 예외가 `TRANSIENT`로 접혀,
**인증 실패가 종목별 일시 오류로 기록되고 배치가 부분 성공으로 끝난다.** 같은 벤더 안에서도
갈려서 `/v2/assets`만 JSON을 준다 — 기존 문서의 실측은 그 엔드포인트에서 잰 것이었다.
사용자 승인으로 세 어댑터를 함께 옮겼다(`SecEdgarResponse`가 같은 이유로 이미 그 형태였다).

**넷째, 기본값이 창을 자른다.** `end`를 빠뜨리면 **절단이 아니라 정상 응답으로** 1주만 온다.
`start`·`end`를 둘 다 빠뜨려도 0건이 아니라 당일부터 1주다 — 기업행위(`/v1/corporate-actions`)가
빈 봉투를 주는 것과 **같은 벤더인데 계약이 다르다.**

**적재 결과**: NYSE·NASDAQ **각각 2,679세션**(2016-01-04~2026-08-28) · 재실행 **0행** ·
2024년 NYSE 252세션이 **우리 봉 252일과 양방향 차집합 0** · 전 구간에서 **두 거래소 차집합
0/0** · 조기폐장 3일(07-03·11-29·12-24) · 벽시계 종가 시각이 16:00과 13:00 둘뿐 ·
**국내 조인 0행.** 국내 봉·지표·보존 경계·원장·유니버스 활성 여부 전부 불변이다.

## 버린 대안

- **공휴일 달력을 안 둔다** — **ADR-0042 §버린 대안이 이미 기각한 것이다**: *"두 시장의 휴장일을
  정확히 유지하는 비용이 크고 그 자체가 또 하나의 갱신 대상이 된다. 창은 달력을 몰라도 같은
  결과를 내면서 놓친 실행까지 함께 메운다."* **넘어선 근거 셋이 그 문서 안에 있다.**
  ① 기각된 것은 「달력으로 **수집 대상일을 고른다**」이지 「달력으로 **결측을 판정한다**」가
  아니다 — `PRICE_LOOKBACK_DAYS` 창을 그대로 뒀다. ② 기각 근거인 「손으로 유지하는 비용」이
  이 시장에서는 성립하지 않는다(소스가 준다). **국내에는 소스가 없어 그 기각이 그대로
  유효하고**, 그래서 비대칭이 정상이다. ③ 같은 절이 이 일을 명시로 넘겼다 —
  *"개별 종목만 비는 결손은 못 고친다. **그것은 별도의 갭 메우기 잡이 할 일이다.**"*
  적재 창은 평일 13:30 사슬이 민다 — 그 크론이 안 돌면 낡고, 낡으면 판정이 판정불가로 퇴화한다(틀리지는 않지만 답을 못 한다).
- **grain을 시장(`market_code`)으로 잡는다** — **ADR-0086 §버린 대안이 반대 근거를 적어 뒀다**:
  *"두 거래소는 통화·시간대가 같아 행을 늘려 얻는 것도 없다."* 그 기각은 **`market` 표에 행을
  늘리는 안**에 걸린 것이고(유니버스가 둘로 갈려 백분위 모집단이 상장 장소로 갈린다) 이 표는
  `market`을 참조하지 않으므로 그 근거가 안 걸린다. 뒤 근거는 진짜 반대이지만 답이 있다 —
  통화·시간대는 같아도 **거래일은 같다는 보장이 없고**(가이드가 그렇게 경고했다),
  무엇보다 **국내를 원리적으로 배제하는 것이 조인 키뿐**이다. 시장으로 잡으면 그 보호가 사라진다.
  *(전 구간 차집합이 0으로 나왔지만 그것은 **결과이지 계약이 아니다** — 그래서 따로 묻는다.)*
- **`AlpacaResponse`에 봉투 판정을 새로 쓴다** — **ADR-0090이 기각했다**(*"같은 판정이 세 벌이
  된다. 키를 인자로 뺐다"*). 같은 해법을 썼다 — 기대 노드 종류를 인자로 뺐다.
- **`MarketExchangeCodes` 선언 상수를 만든다** — `MarketIndexCodes`의 형태를 베끼려 했으나,
  그 상수가 있는 이유는 **독자가 따로 둘**이기 때문이고(어댑터 자기검사 · 일일 갱신의 결손 알림
  필터) 거래소에는 그런 독자가 **0**이다. 대상은 레지스트리에서 뽑고 **0건은 알림으로 닫는다** —
  `MarketIndexJob`이 이미 그렇게 한다.
- **`INACTIVE`를 판정값으로 둔다** — 낼 근거가 없다. 미국 8,504종목 중 `is_active=false`가 0,
  `delisted_on`·`listed_on`이 각각 0이고 **그 컬럼을 채우는 코드가 없다.** 상장폐지가
  `universe_membership.effective_to`로만 나타나는데 **그것을 상장폐지로 대신하는 것은 P20 축④가
  금지한다.** 편입 구간은 판정이 아니라 **증거로** 낸다(ADR-0050의 형태다).
- **잡을 둘로 나눈다** — 적재와 조회를 별 클래스로. 버린 이유는 **요약 문장을 짓는 코드가
  하나**이기 때문이다. 나누면 사본이 되고, 갈리면 화면이 말한 결측 수와 알림이 말한 수가 달라진다.
- **`pre_start`·`post_end`·`settlement_date`·`mic`을 담는다** — 읽는 쪽이 없다.
  읽는 쪽 없는 컬럼은 갈려도 아무도 모른다.

## 결과

**쉬워지는 것**: 미국 봉이 없는 날 중 **휴장**이 갈린다. 적재는 거래소당 1콜(전부 2콜)이고
창을 넓혀도 안 는다. 국내는 조인 키가 NULL이라 **아무 규칙 없이도** 이 축에 안 닿는다.

**어려워지는 것**

- **닫히는 것은 넷 중 하나뿐이다.** 개장일인데 봉이 없는 것이 **무거래·비활성·거래정지·수집
  실패** 중 무엇인지는 여전히 안 갈린다 — 거래정지 목록이 없다. `listed_on`이 전량 NULL이라
  **「아직 상장 전」도 못 가른다.** 요약의 「세션인데 봉 없음」을 수집 실패로 읽으면 안 된다.
- **자동 감시가 아니다.** 이 축이 놓은 가장 자동에 가까운 것이 **적재 뒤 요약 알림**이고,
  진짜 경보는 ① 미국이 일일 갱신에 들어오고 ② 그 전에 「시장을 안 가르고 거래일을 세는
  자리」가 닫혀야 성립한다. **②는 ADR-0093이 닫았고**(`alertIfNothingArrived`가 이제 시장별로
  판정한다) **①이 남았다.** 그리고 ②가 닫히면서 **새 조건이 하나 생겼다** — 미국이 일일 갱신에
  들어오면 **미국 휴장일마다 그 알림이 뜬다.** 막으려면 이 표가 그 판정에 들어와야 하는데,
  그것은 위 금지가 **국내에만** 걸린다는 것을 먼저 확인해야 하는 별도 결정이다.
- **13:30 사슬이 안 돌면 적재 창이 낡는다.** 낡으면 최근 구간이 판정불가가
  되는데 **틀린 답이 아니라 답 없음**이라는 것이 이 설계가 고른 실패 방향이다.
- **「NYSE·NASDAQ」 사본이 둘에서 넷으로 늘었다.** `ck_stock_listing_exchange` ·
  `AlpacaUniverseAdapter.EXCHANGES` · `ck_market_calendar_exchange` ·
  어댑터의 `supportedExchangeCodes()`. 뒤 둘은 **강제 장치로 의도된 사본**이다(V23·V34와 같은 판단).
- **읽기 전용 잡 목록이 새로 생겼다.** 거기 안 넣은 조회 잡은 **남의 적재를 마감한다.**

**되돌리려면**: `AlpacaMarketCalendarAdapter`·`MarketCalendarJob`·두 리더·컬렉터를 지우고
`DataSourceRegistry`의 인자와 `BootstrapRunner`의 두 갈래·`READ_ONLY_JOBS`·`AdminJobLauncher`
항목을 되돌린다. `market_calendar`는 **읽는 쪽이 이 축뿐이라** 표를 비워도 다른 축이 안 깨진다.
`AlpacaResponse`의 상태 우선 판정은 **되돌리지 않는다** — 이 결정과 독립이고, 되돌리면 인증
실패가 다시 일시 오류가 된다.
