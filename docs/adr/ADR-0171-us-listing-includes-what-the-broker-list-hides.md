---
id: ADR-0171
status: 채택됨
date: 2026-10-06
applies_to:
  - "src/main/java/**/datasource/us/UsMarketCapUniverseAdapter.java"
  - "src/main/java/**/datasource/us/AlpacaUniverseAdapter.java"
  - "src/main/java/**/datasource/us/SecTickerCikCache.java"
---

# ADR-0171. 미국 상장은 Alpaca 목록과, 그 목록이 숨긴 상장을 함께 본다

## 결정

`US_TOP1000`의 후보에 **「숨은 상장」**을 더한다. 숨은 상장은 아래를 모두 만족하는 종목이다.
- 우리 DB에 주식수가 있다.
- SEC 파일(`company_tickers_exchange.json`)이 NYSE·Nasdaq로 적는다.
- Alpaca 활성 목록(`/v2/assets?status=active&exchange=NYSE,NASDAQ`)에 없다.

숨은 상장은 아래 조건을 모두 통과해야 후보가 된다.
1. 순위 창의 **마지막 세션(후보 과반이 닿은 날짜)에 봉이 있다.**
2. Alpaca 단건 `/v2/assets/{s}`가 거래소를 **NYSE·NASDAQ**로 준다.
3. 이름 축을 통과한다. 이름은 단건 이름이 먼저이고, 비면 저장된 이름을 쓴다.

`SecTickerCikCache`는 거래소 칸을 들고, 받은 지 24시간이 지나면 다시 받는다. 다시 받기가 실패하면 **명부 판(`freshSnapshot`)은 던지고**, 종목 단위 소비자(`findCik` — 재무·개황)는 옛 판을 나이와 함께 WARN하고 계속 쓴다. 실패한 시도는 10분 동안 기억한다.

## 규칙

- MUST NOT: Alpaca 활성 목록을 상장 사실로 쓰지 않는다. 그 목록은 단건이 아는 자산 일부를 상태와 무관하게 뺀다.
  - 2026-10-05 실측: 상태·거래소 인자 없이 받은 33,550건과 비활성 19,169건 **어디에도** ET·DDS·SUN·USAC·ASND가 없다.
  - 그런데 단건은 이들을 NYSE·NASDAQ `inactive`로 주고, 시세와 배당도 계속 나온다.
- MUST: 숨은 상장의 생존은 **봉으로만** 판정한다. SEC 파일은 폐지·개명을 늦게 반영하고(DBRG 마지막 봉 09-29 · DOMO 09-23인데 SEC에 남음), 단건 `status`는 ET와 폐지 종목 모두에 `inactive`를 준다.
- MUST: 마지막 세션은 후보 **과반**이 닿은 날짜다. 최댓값을 쓰면 이상봉 하나가 숨은 상장 전체를 떨어뜨린다.
- MUST: 숨은 상장 판정을 CIK 중복 제거(ADR-0169)보다 **앞**에 둔다. 뒤에 두면, 거래대금이 큰 낡은 숨은 티커가 CIK 대표를 차지한 뒤 빠지면서 같은 CIK의 산 클래스까지 사라진다.
- MUST: 단건 조회는 생존자에게만 한다. 404는 빼고 INFO를 남긴다. **2xx가 아닌 나머지와 전송 실패는 던진다.** 빼고 넘어가면 원래 결함(상장 종목이 명부에서 빠짐)이 재현되고, 던지면 리밸런싱이 이전 구성을 지킨다(ADR-0008).
- MUST: 생존자가 200을 넘으면 MALFORMED로 멈춘다. 2026-10-02 생존은 10이었으므로, 그 20배를 넘으면 목록이 깨진 것으로 본다.
- MUST NOT: 「같은 CIK의 다른 티커가 목록에 있다」를 개명의 증거로 써서 빼지 않는다. 그 목록이 상장 사실이 아니라고 판정해 놓고 다시 증거로 쓰는 셈이다.
  - 현 편입 45종목(CHTR·CMCSA·T·DUK …)은 같은 CIK의 목록 기호가 우선주·baby bond뿐이다. Alpaca가 보통주만 빼면 원래 결함이 그대로 재현된다.
  - 라벨로만 남긴다.
- MUST: 한 실행 안에서는 SEC 판(`SecTickerCikCache.freshSnapshot()`)을 하나만 쓴다. 숨은 상장의 거래소와 CIK 중복 제거가 같은 판을 본다.
- MUST NOT: 명부가 SEC 매핑을 다시 받다가 실패했을 때 옛 판으로 물러서지 않는다. 낡은 라벨이 명부를 조용히 틀리게 만든다.
- MUST NOT: 종목 단위 소비자에게 그 실패를 던지지 않는다. 그러면 그 실행의 미국 전원이 실패하는데도 `PARTIAL_SUCCESS`라 무거운 잡 시계가 다시 시작된다. 낡은 맵이 만드는 것은 그 뒤의 새 티커의 「모름」뿐이고, 그것은 종목 실패로 시끄럽다.
- MUST: 실패한 시도를 기억한다(10분). 기억하지 않으면 종목 루프가 종목마다 매핑 파일을 다시 받는다.
- 단건 조회는 6시간 메모한다. 복원 격자가 같은 심볼을 다시 물어도 0콜이다. 시각으로 끊는 이유는 포트에 실행 범위가 안 실리고 어댑터가 싱글턴이라서다.

## 검증

- `./gradlew test --tests "*UsMarketCapUniverseAdapterTest" --tests "*SecTickerCikCacheTest" --tests "*AlpacaUniverseAdapterTest"`.
  - 「마지막 세션 무시」 변이체에서 `dropsHiddenListingWithoutTheLastSessionBar`와 `staleHiddenTickerDoesNotTakeTheCikSeat`가 붉다(쟀다).
  - 「최댓값」 변이체에서 `lastSessionIsTheMajorityDate`가 붉다(쟀다).
- 운영: 리밸런싱 로그의 `숨은 상장:` 줄(받음 · 마지막 세션 봉 없음 · 단건 없음 · 거래소 밖).

모집단을 10-09(A·A2가 처음 도는 리밸런싱의 기준일) 뒤 편출로 좁힌 근거: 그 앞의 CIK 교대 편출(MOG·BF·WSO·MKC)은 ADR-0169가 없앤 결함이다. 넣으면 35일 동안 같은 발견이 매 세션 뜬다(2026-10-06 실측 6건).

```premise 큰 종목이 관측 편출되면 그 이유가 설명된다
psql -d stock_screener -At -c "WITH obs AS (SELECT m.stock_id, m.effective_to FROM universe_membership m WHERE m.universe_code = 'US_TOP1000' AND m.origin = 'OBSERVED' AND m.effective_to IS NOT NULL AND m.effective_to > greatest(current_date - 35, date '2026-10-08')), cap AS (SELECT o.stock_id, o.effective_to, (SELECT p.close FROM price_daily p WHERE p.stock_id = o.stock_id AND p.trade_date <= o.effective_to ORDER BY p.trade_date DESC LIMIT 1) * (SELECT f.shares_outstanding FROM financial_statement f WHERE f.stock_id = o.stock_id AND f.is_latest AND f.shares_outstanding > 0 ORDER BY f.period_end DESC LIMIT 1) AS mcap, (SELECT max(p.trade_date) FROM price_daily p WHERE p.stock_id = o.stock_id) AS last_bar FROM obs o), floor AS (SELECT percentile_cont(0.05) WITHIN GROUP (ORDER BY c.close * f.shares_outstanding) AS p5 FROM universe_membership m JOIN LATERAL (SELECT p.close FROM price_daily p WHERE p.stock_id = m.stock_id ORDER BY p.trade_date DESC LIMIT 1) c ON true JOIN LATERAL (SELECT f.shares_outstanding FROM financial_statement f WHERE f.stock_id = m.stock_id AND f.is_latest AND f.shares_outstanding > 0 ORDER BY f.period_end DESC LIMIT 1) f ON true WHERE m.universe_code = 'US_TOP1000' AND m.effective_to IS NULL) SELECT CASE WHEN (SELECT count(*) FROM obs) = 0 THEN 'OK 관측 편출 0건(최근 35일 · 10-09 리밸런싱 뒤) — 잴 편출이 아직 없다' WHEN count(*) FILTER (WHERE c.mcap > fl.p5 * 1.5 AND c.last_bar >= c.effective_to) = 0 THEN 'OK 최근 35일 관측 편출 ' || (SELECT count(*) FROM obs) || ' 중 큰데 봉이 이어진 것 0' ELSE '큰 종목이 봉이 이어지는데 편출됐다(하위 5% × 1.5 위): ' || string_agg(s.ticker || ' ' || c.effective_to || ' ' || round(c.mcap / 1e9, 1) || 'B', ', ') FILTER (WHERE c.mcap > fl.p5 * 1.5 AND c.last_bar >= c.effective_to) || ' — 숨은 상장(ADR-0171) 갈래가 놓쳤거나 CIK 대표 교대(ADR-0169)거나 ADR 배수다. 리밸런싱 로그의 숨은 상장 줄과 CIK 대표 줄로 가른다' END FROM cap c JOIN stock s ON s.id = c.stock_id CROSS JOIN floor fl"
```

## 배경

2026-10-03 21:14 리밸런싱(#703)까지 명부에 있던 ET(시총 약 705억 달러)와 DDS(약 102억 달러)가 그 뒤 Alpaca 활성 목록에서 빠졌다. 그러자 쓰기 없는 재생이 9개 날짜 모두에서 둘을 뺐다. 그대로 두면 다음 리밸런싱에서 일어나는 일은 셋이다.
- 규칙과 무관하게 둘이 편출된다.
- 대신 경계 밑 둘(OLLI·SHC)이 편입된다.
- OBSERVED 원장에 거짓 편출·편입 4행이 남고, 이를 되돌리려면 원시 SQL로 행을 고쳐야 한다.

ADR-0086 §규칙은 「`tradable`이 아니라 `status`로 거른다」였다. 그런데 `status=active`도 브로커의 거래 허용이었다. 원장(SEC 파일)은 거래소와 함께 그 상장을 주므로 가짜 한계다.

우리 DB에 있으면서 이 갈래에 드는 숨은 풀은 다음과 같다(10-02).
- 숨은 풀 29(SIC 전)
- SIC 뒤 25
- 마지막 세션 생존 10
- 경계 위는 ET·DDS 둘

## 버린 대안

- **`status` 인자를 뺀 목록을 쓴다** — 그 목록(33,550)에도 ET·DDS가 없다. 폐지 종목 2,163만 더 섞인다.
- **SEC 파일만으로 상장을 정한다** — SEC는 NYSE American·Arca도 「NYSE」로 적는다(UEC·SEB·PHYS). 폐지·개명도 최소 12일 늦게 반영한다(DBRG·DOMO·TBPH). 그래서 단건 거래소와 봉 생존으로 보완한다.
- **개명 규칙(같은 CIK 활성 티커가 있으면 제외)** — 위 MUST NOT. 지금 숨은 풀에서 이 규칙이 따로 잡아내는 종목은 0이다. 11종목이 그 규칙에 걸리지만 전부 마지막 세션 규칙이 먼저 뺀다.
- **단건 이름이 비면 제외** — 저장된 이름(같은 벤더 목록 08-29)으로 대신하면 0콜이다. 제외하면 원래 결함이 재현된다.
- **SEC 매핑 갱신 실패 시 명부도 옛 판** — 위 MUST NOT. 명부를 던지면 토요일 리밸런싱이 SEC 가용성에 묶인다. 대가는 이전 구성을 한 주 더 지키는 것이다.
- **종목 단위 소비자도 던진다** — 위 MUST NOT(B v7 반증이 같은 모양을 `DartCorpCodeCache`에서 짚었다).

## 결과

- 상장 정의(ADR-0086 §결정 — NYSE·NASDAQ 상장)는 그대로이고, 상장을 재는 소스만 바뀐다. ADR-0086 §규칙의 `status=active` 문장은 「목록의 폐지 방어일 뿐 상장의 정본이 아니다」로 읽는다.
- **복원 평면(ADR-0135)**: 과거 날짜의 숨은 상장은 그 날짜의 봉으로 받는다. 그래서 「미국 폐지 종목은 못 들어온다」가 일부 바뀐다. 오늘 SEC 파일에 남은 폐지 전 티커는 들어오고, SEC에서 빠진 것은 여전히 못 들어온다. 결과는 실행 시각의 SEC 상태에 달린다.
- 우리 `stock`에 없는 새 상장(08-29 뒤 134)과 주식수가 없는 큰 종목(SUN·ASND 등)은 이 결정이 다루지 않는다(`docs/reload-gate.md` A2-b · A2-c 행).
- 되돌리려면 `acceptHidden`을 빼면 된다. 그러면 ET·DDS 모양은 다음 리밸런싱에서 다시 빠진다.
