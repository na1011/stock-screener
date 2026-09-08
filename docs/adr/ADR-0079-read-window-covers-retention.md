---
id: ADR-0079
status: 채택됨
date: 2026-08-21
applies_to:
  - "src/main/java/**/indicator/IndicatorRegistry.java"
  - "src/main/java/**/indicator/IndicatorRunner.java"
  - "src/main/java/**/indicator/IndicatorRetentionPolicy.java"
---

# ADR-0079. 읽기 창을 워밍업 최댓값에서 떼어 보존 창을 덮게 한다

## 결정

`IndicatorRegistry.readWindowBars() = maxEffectiveLookback() + RETAINED_TRADING_DAYS`를 새로 두고,
`IndicatorRunner`의 두 호출 지점(`readRawData`·`marketIndexesFor`)이 **그것만** 본다.
2026-08-21에 1,261 → **1,441봉**이 됐고, 보존이 255로 넓어지며 **1,516봉**이다(ADR-0129).
**수는 상수 둘에서 나온다 — 여기 적힌 값이 정본이 아니다.**

**`effectiveLookback(code)`·`maxEffectiveLookback()`·`RETAINED_TRADING_DAYS`는 안 바꾼다.**

## 규칙

- **MUST NOT: `maxEffectiveLookback()`을 읽어올 봉 수로 쓰지 않는다.** 계산기는 워밍업 W일 때
  `bars.size() − W + 1`개 포인트를 내므로, 읽어오는 봉이 W의 최댓값과 **같으면 그 지표는 `asOf`당
  한 일자**밖에 못 낸다. 되돌려도 값은 옳고 배치는 SUCCESS이며 최신 일자 화면도 멀쩡하다 —
  드러나는 것은 과거 일자를 고른 사용자에게 **이유 없는 0건**뿐이다.
- **MUST NOT: `effectiveLookback(code)`를 읽기 창으로 쓰지 않는다.** 그것은 `UnadjustedActionGuard`의
  **억제 창**이다(ADR-0055). 넓히면 미보정 기업행위 억제가 과잉 적용된다.
- **MUST: 시세 창과 지수 창이 같은 메서드를 부른다.** 갈리면 오래된 산출 일자가 전부 「지수 결손」으로
  접혀 사라지고 백필은 **SUCCESS로 끝난다**(`marketIndexesFor` javadoc).
- **MUST NOT: `RETAINED_TRADING_DAYS`를 「보존만」의 손잡이로 읽지 않는다.** 이제 읽기 창이 여기 얹혀
  있어, 줄이면 가장 긴 워밍업 지표가 실행당 그만큼밖에 못 낸다.
- **MUST: 「읽기 창」과 「워밍업」을 같은 낱말로 쓰지 않는다.** 값이 갈렸다(1,516 대 1,261).
- **MUST: 창을 움직이면 스냅숏 대조를 밟는다**(ADR-0066 §결과 승계). **대상은 일봉 재귀 넷이 아니라
  전 코드다** — 아래 §측정이 그 이유를 실측으로 답한다.

## 검증

```bash
./gradlew test --tests "*IndicatorReadWindowTest*"
rg -n "maxEffectiveLookback\(\)" src/main/java
```

`IndicatorReadWindowTest`가 산식(합성 레지스트리)과 **등록된 전 지표**(진짜 레지스트리)를 함께 단언한다.
뒤엣것이 **다음에 최댓값이 되는 지표**를 잡으며, 실패 메시지에 그 코드 이름을 박는다.
`rg`는 `IndicatorRegistry.java`에만 걸려야 한다 — 러너가 다시 그것을 부르면 회귀다.

```premise 가장 긴 워밍업 지표가 보존 창을 한 번에 채운다
psql -d stock_screener -At -c "WITH RECURSIVE dates AS (SELECT min(trade_date) d FROM indicator_value UNION ALL SELECT (SELECT min(trade_date) FROM indicator_value WHERE trade_date > dates.d) FROM dates WHERE dates.d IS NOT NULL), r AS (SELECT count(*) n FROM dates WHERE d IS NOT NULL), e AS (SELECT count(DISTINCT trade_date) n FROM indicator_value WHERE indicator_code = 'EPS_CAGR_5Y') SELECT CASE WHEN (SELECT n FROM r) = 0 THEN '잴 것이 없었다 — indicator_value가 비어 있다' WHEN (SELECT n FROM e) = 0 THEN 'EPS_CAGR_5Y가 한 행도 없다 — 지표가 폐기됐으면 이 펜스도 함께 걷어라' WHEN (SELECT n FROM e) >= (SELECT n FROM r) THEN 'OK 보존 창 ' || (SELECT n FROM r) || '일자를 EPS_CAGR_5Y가 전부 덮는다' ELSE 'EPS_CAGR_5Y가 보존 창 ' || (SELECT n FROM r) || '일자 중 ' || (SELECT n FROM e) || '일자에만 있다 — 읽기 창이 다시 워밍업 최댓값으로 좁혀졌는지 본다(ADR-0079)' END"
```

**보존 창 일자 수는 재귀 CTE(loose index scan)로 묻는다.** `count(DISTINCT trade_date)`는 인덱스를
끝까지 훑어 **2,434ms / 제한 3,000ms**인데 탐침은 **173ms**다(출력 동일). `trade_date`가 선두인
`indicator_value_pkey`에 기대는 형태다 — 기본키를 갈아엎지 않는 한 사라지지 않는다.

**`EPS_CAGR_5Y`를 이름으로 박는 이유**는 그것이 지금 워밍업 최댓값을 정하는 지표이고, 굶는 것은 언제나
**최댓값을 정하는 그 지표 하나**이기 때문이다. 최댓값이 다른 지표로 옮겨가면 이 펜스는 낡는다 —
그때는 위 단위 테스트가 먼저 붉어진다. **`TRADING_DAYS_750`을 뺐던 이유는 2026-09-08에 사라졌다** — 그때는 일부러 마지막 봉만 내서
넣으면 **아무도 떨어뜨릴 수 없는 펜스**가 됐는데(ADR-0074가 금지한 형태), 이제 창 안 산출
일자마다 값을 낸다(ADR-0129). **그 지표의 격자 채움은 ADR-0129 §검증이 잰다** — 여기 더하면
같은 것을 두 곳에서 재고 전제 예산만 먹는다.

```premise 지수 이력이 읽기 창을 덮는다
psql -d stock_screener -At -c "WITH i AS (SELECT index_code, count(*) n FROM market_index_daily GROUP BY 1) SELECT CASE WHEN (SELECT count(*) FROM i) = 0 THEN '잴 것이 없었다 — market_index_daily가 비어 있다' WHEN (SELECT min(n) FROM i) >= 1516 THEN 'OK 지수 최소 ' || (SELECT min(n) FROM i) || '봉이 읽기 창 1516을 덮는다(여유 ' || ((SELECT min(n) FROM i) - 1516) || '일자)' ELSE '지수 ' || (SELECT index_code FROM i ORDER BY n LIMIT 1) || '가 ' || (SELECT min(n) FROM i) || '봉뿐이라 읽기 창 1516에 못 미친다 — 오래된 산출 일자가 조용히 지수 결손이 된다' END"
```

**여기 1516은 손으로 적은 수다** — SQL이 Java 상수를 못 읽는다. 창을 바꾸면 이 수도 함께 바꾼다.

## 배경

`EPS_CAGR_5Y`가 보존 창 180거래일 중 **172일에 한 행도 없었다**(2026-08-21 · 그때의 창). 린치 두 프리셋의 필수 게이트라
(ADR-0070) 화면의 날짜 선택기로 과거 일자를 고르면 두 프리셋이 **이유 없이 0건**이 됐다.

기제는 산술 하나다. `readRawData`가 종목당 `maxEffectiveLookback()`봉을 한 번만 읽고(N+1 회피),
계산기는 `bars.size() − W + 1`개 포인트를 낸다. `EpsCagr5yCalculator`의 요구 1,261이 **저장소의
최댓값**이라 창 = 자기 워밍업 → **`asOf`당 1일자**. 값이 있던 8일자는 그 지표가 생긴 2026-08-18 이후
실행이 닿은 날짜와 정확히 일치했다.

**「최댓값을 정하는 지표는 굶는다」는 참이지만 「워밍업이 1,261인 지표는 굶는다」는 거짓이다.**
1,261을 선언하는 지표는 셋이고(`EPS_CAGR_5Y`·`PER_MEDIAN_5Y`·`RETAINED_TO_MARKET_4Y`) 뒤 둘은
180일자를 채운다 — 재무값 하나를 `spreadOverTradeDates`로 창 전 봉에 찍기 때문이다. `EpsCagr5y`만
**산출일 i를 i−1,260과 짝지어야** 해서 묶인다. **창이 아니라 계산기의 루프 방식이 가른다.**

**ADR-0066 §배경의 *"백필은 단일 날짜 1회로 끝난다"*는 그 결정 자신 때문에 거짓이 됐다** — 창을
1,261로 올리면서 `EPS_CAGR_5Y`를 창의 최댓값 자리에 앉혔기 때문이다. 이 결정이 그 문장을 되돌린다.

커밋 `211af6b`가 이 수정을 *"창을 고치지 않고 드러나게만 한다 … 백필 시간과 **저장이 함께** 는다"*로
미뤄 뒀다. **그 전제 하나가 틀렸다** — 창을 요구의 최댓값에서 뗐으므로 창을 올리려고 가짜 지표를
만들 필요가 없고, **저장은 안 는다**: `retain()`이 그대로라 보존 밖 쓰기는 여전히 0이고 순증은
`EPS_CAGR_5Y` 자신의 30,599행(**+0.54%**)뿐이다. **시간도 안 늘었다**(§측정).

## 측정 결과 (2026-08-21)

전 코드 스냅숏을 뜨고 단일 날짜 백필을 돌린 뒤 대조했다.

| 무엇 | 전 | 후 |
|---|---|---|
| `EPS_CAGR_5Y` 일자 / 행 | **8 / 1,066** | **180 / 31,665** |
| `indicator_value` 전체 | 5,713,477행 | 5,744,076행 (**+0.54%**) |
| 사라진 행 | — | **0** |
| 단일 날짜 백필 소요 | 78초(id 97) · 145초(id 99) | **64초**(id 104) |
| 2회차(멱등) | — | **0행 · 62초**(id 105) |
| 읽기 창 안 미보정 기업행위 | 5건 | **6건** |
| `RETAINED_TO_MARKET_4Y` 보유 종목 | 224 | **224** |

- **소요는 늘지 않았다.** 봉이 +14.3%인데 실측이 기준선 둘보다 짧다 — 실행 간 산포(78↔145초)가
  창의 증분보다 크다. **`written_row_count`로는 판별할 수 없다**(ADR-0045 §배포와 그 효과).
- **값이 바뀐 코드는 `ICHI_MOD_C` 하나, 14행뿐이다**(55,166행 중, 9종목 / 14일자, 0↔1 양방향).
  **최신 일자에는 없어 지금 화면은 그대로다.** 일봉 재귀 지표는 `ATR_14`·`RSI_14`·`MACD`·`MACD_V`·
  `ADX_14`·`RSI_2`·`ATR_PCT_14` 전부 **0행**이다.
  **이 비대칭이 스냅숏을 전 코드로 떠야 하는 이유다.** ADR-0066 §배경의 잔차 논거(`(13/14)^k`)는
  **일봉에만** 걸린다 — `WeeklySeries`의 RMA는 **주봉**을 평활하므로 시드까지의 거리가 봉 수가 아니라
  그 1/5쯤인 주 수이고, 잔차가 저장 자릿수(8) **위**로 올라온다. 넷만 떴으면 이 14행을 못 봤다.
- **억제는 「안 바뀐다」가 아니라 「비로소 온전해졌다」.** `suppressedDates`가 창 시작을
  `bars.get(max(0, i − N + 1))`로 잡는데, `bars.size() == N`이던 동안 **보존 창 180일 중 179일에서
  인덱스가 0으로 클램프돼 정본보다 짧은 창으로 판정**받고 있었다 — 즉 **과소 억제 상태**였고 증상이
  "값이 나온다"라 아무 데도 안 드러났다. 창 앞머리가 `2021-06-24` → `2020-09-29`로 밀리며
  **`020560 아시아나항공`(2021-01-15 `REVERSE_SPLIT`, `UNSUPPORTED`)이 새로 창에 들었다.**
  그 종목은 워밍업 1,261짜리 지표 셋을 하나도 안 갖고 있어 **삭제 0행**이었다(백필 로그가 그렇게 찍었다).
- **과거 일자 필수 게이트 통과**(고치기 전에는 전부 0): 2026-06-01 대형우량주 13 · 고속성장주 7,
  2026-03-16 11 · 9, 2025-12-01 8 · 11.
- ADR-0045의 두 `premise`(보존 경계 밖 쓰기 / 인덱스 블로트)는 **그대로 OK**다.

## 버린 대안

- **구간 백필만 돌린다(코드 변경 0).** 잡이 날짜를 바깥 루프로 도므로 `--from=<보존창 첫날>`이면
  180일자가 채워진다(실측 거래일당 48초 → 약 2시간 24분, `batch-load` skill의 다른 실측은 1시간 51분).
  **속도 때문에 버린 것이 아니다** — 무효화는 종목 단위라 `--tickers`로 좁히면 6종목 × 180일자가
  2분 37초다(id 96). 버린 이유는 셋이다. ① **정기 절차가 이 지표에만 거짓으로 남는다** —
  `batch-load` skill이 *"refill 뒤에는 전 종목 단일 거래일 백필을 돌린다. 그 한 번으로 보존 창이
  채워진다"*로 절차를 정해 뒀는데, 그 문장이 `EPS_CAGR_5Y`에만 거짓이고 **거짓인 채로 SUCCESS가 찍힌다.**
  ② **지키려던 「날짜별 재무 빈티지」가 반쪽이다** — `SELECT_FINANCIALS_LATEST`에 `published_at` 필터가
  없어(그 javadoc이 *"미래 공시는 막지 못한다"*로 스스로 적는다) 미공시 재무가 과거 일자 계산에 샌다.
  진짜 시점 재현의 정본 경로는 `computeAt(KNOWN_ON)`이다(ADR-0071). ③ **억제 클램프가 그대로 남는다.**
- **계산기가 봉마다 TTM을 다시 세게 한다**(창 확대 + 빈티지 보존) — **2026-09-08에 착수했다**
  (ADR-0128). 여기서 「사용자 판단이 먼저다」로 미뤄 둔 것이 그 판단이고, 기준은
  *「어느 시점에 돌려도 정확한 스크리닝」*이었다. 막는다고 적은 것(**값이 바뀐다** — ADR-0070이
  못박은 통과 수가 재현 불가가 된다)은 **그대로 일어난다.** 실제 구현은 `ttmKnownOn`을 빼는
  형태가 아니라 `IndicatorContext.visibleAt` + `FinancialMath.byPublication`이다 —
  계산기 본체를 안 건드리는 쪽이 25개 파일의 값 규칙을 함께 흔들지 않는다.
- **드러내기만 한다**(172일을 빈 채로 두고 0건의 이유를 화면에 말한다) — 2026-08-21 사용자가 기각했다.
- **`AsOfResolver`에 넷째 경우를 더한다** — 그 클래스는 *날짜*를 해석하고 화면이 고른 스크린을 모른다.
  프리셋별 게이트 커버리지를 알려면 지표·프리셋 계층이 웹 예외에 결합된다. 원인을 없애는 쪽을 골랐고
  관측은 위 `premise`가 맡는다.
- **창을 1,440(= 최댓값 + 179)으로 둔다** — 산출 일자가 정확히 180이라 여유가 0이다. `DailyUpdateJob`이
  오늘 봉이 들어오기 전에 `writeCutoff()`를 잡아 보존 집합이 잠시 **181일자**가 되는데(ADR-0071도
  *"보존 창이 181일자뿐"*이라 적는다) 그 하루가 갱신에서 빠진다. 비용은 봉 하나다.
- **`maxEffectiveLookback()`의 뜻을 바꾼다** — `UnadjustedActionGuard`가 같은 축에 의존하고
  `IndicatorRegistryTest`가 그것을 못박는다. 이름을 하나 더 두는 편이 싸다.
- **`RETAINED_TRADING_DAYS`를 올린다** — ADR-0071 §버린 대안이 이미 기각했다(*"쌓이는 값이 여전히
  오늘 재무로 계산된 것"*). 이 결정은 그 값을 **안 만진다.**
- **계산기마다 자기 창만큼만 읽어 준다** / **계산기가 마지막 포인트만 내게 고친다** — **ADR-0045
  §버린 대안이 각각 N+1과 규약 증가로 기각했다.** 이 결정은 둘 다 아니다 — 읽기는 여전히
  **종목당 한 번**이고 **폭만** 넓힌다. 다시 제안되지 않게 여기 함께 적는다.
- **`EPS_CAGR_5Y`의 `LOOKBACK`을 3년으로 줄인다** / **양 끝에 같은 주식수를 쓴다** —
  **ADR-0066 §버린 대안이 기각했다**(린치 3.17 원문 이탈 · 약분되어 순이익 CAGR인데 이름은 EPS).

## 결과

- **쉬워지는 것**: 단일 날짜 백필 한 번이 **모든 원본 지표의 보존 창을 채운다.** 그래서
  `batch-load` skill의 정기 절차가 예외 없이 참이 되고, 무효화 뒤 복구도 같은 한 번이다.
  **다음에 워밍업 최댓값이 되는 지표도 같은 굴레에 안 걸린다.**
- **어려워지는 것**: `EPS_CAGR_5Y`의 과거 일자가 **오늘 이익 ÷ 그 날짜 주식수**로 계산된다.
  「전부 오늘 재무」가 아니다 — 분자는 최신 TTM인데 분모는 그 날짜의 `listed_shares`다.
  다른 재무 지표가 이미 그런 것처럼(실측: `ROE`는 유니버스 348종목이 보존 창 180일자에서 값 하나뿐)
  **저장된 과거 지표에 시간축이 없다는 성질**에 이 지표도 합류한 것이며, 시점 재현은 `computeAt`이 맡는다.
- **되돌리려면** `IndicatorRunner`의 두 호출 지점을 `maxEffectiveLookback()`으로 되돌린다.
  **되돌리는 순간 다시 `asOf`당 1일자가 되고 아무 에러도 안 난다** — `IndicatorReadWindowTest`와
  위 `premise` 말고는 그것을 알릴 경로가 없다.
