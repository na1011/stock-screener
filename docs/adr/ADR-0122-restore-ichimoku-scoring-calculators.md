---
id: ADR-0122
status: 채택됨
date: 2026-09-02
applies_to:
  - "src/main/java/**/indicator/calculator/ichimoku/**"
  - "docs/adr/ADR-0046-indicator-layer-precision-and-boundaries.md"
  - "docs/adr/ADR-0077-shrink-ichimoku-footprint.md"
---

# ADR-0122. 소비자가 없다는 이유로 지운 일목 점수화 13개를 되살린다

## 결정

**사용자 결정이다(2026-09-02).** `ffd8f24`(2026-08-14)가 지운 계산기 13개와 그 근거 코드
(`IchimokuScoring` 170줄 · `ValueWidth` 267줄)를 되살린다. 활성 `ICHI_*`는 18 → **31**이다.

**되살리며 하나를 바꿨다.** 원래 코드는 시장 지수가 없는 일자를 **통째로 버렸다**. 그 게이트는
`c6d12ba`가 철회한 것이라 되살리지 않는다 — 대신 **D5(26주 초과 수익률)에 기대는 일곱만**
값을 내지 않는다.

| | 코드 | 지수 |
|---|---|---|
| **D5 의존 7** | `ICHI_TIER` · `ICHI_SIGNAL` · `ICHI_EXT_SIGNAL` · `ICHI_CONFIDENCE` · `ICHI_CONCLUSION` · `ICHI_BULL` · `ICHI_BEAR` | 필요 |
| **독립 6** | `ICHI_PILLAR` · `ICHI_STAGE` · `ICHI_LEAF` · `ICHI_FRESHNESS` · `ICHI_T3YEOK` · `ICHI_KAIZEN_MATCH` | 불필요 |
| **기존 관측 18** | `ICHI_MOD_*` · `ICHI_OBV_T` · `ICHI_PRICE_VS_KUMO` 등 | 불필요 |

## 규칙

- **MUST NOT: 지수 결손일에 `ICHI_*` 행을 전부 억제하지 않는다.** ADR-0046 §승인된 이탈 2가
  철회한 게이트가 그것이고, 되살리면 **지수를 쓰지 않는 스물넷이 함께 사라진다.**
- **MUST NOT: 낼 수 없는 D5 값을 0으로 채우지 않는다.** 「지수 없음」이 「초과 수익률 0%」가
  되면 조건 `<= 0`이 그것을 **전부 통과시킨다** — ADR-0046 §결과가 센티널에 대해 적은 함정과
  같은 모양이고, 그쪽은 값이 눈에 보이기라도 한다.
- **MUST: D5에 기대는 계산기는 `needsMarketIndex()`를 `true`로 선언한다.** 계약은
  `IndicatorRunner`에 이미 있다(`DataRequirement.priceAndMarketIndex` · `ExcessReturn26wCalculator`가
  먼저 쓰는 사용자다). **새로 만들지 않는다.**
- **MUST: 이 비대칭을 재는 테스트에 지수를 준 대조군을 함께 단언한다.** 없으면
  「언제나 `null`」인 구현도 초록이다.
- **MUST NOT: 되살린 13개를 검증된 값으로 읽지 않는다.** ADR-0046 §승인된 예외가 그대로
  걸린다 — TradingView 원본 대조를 한 적이 없고, 52셀이 전부 정수라 **셀 하나가 틀려도
  Tier는 여전히 1~4다.**

## 검증

**MUST NOT: 새 `premise`를 더하지 않는다.** 세션 시작 예산이 **28초/30초**라 여유가 2초이고,
ADR-0077의 전제가 이미 활성 개수를 센다(18 → 31로 같은 커밋에서 고쳤다).

지수 결손은 이 결정의 전제이므로 여기 실측만 남긴다 — 2026-09-02 기준 **세 시장 전부 0**이다.

```
psql -d stock_screener -P pager=off -c "
WITH d AS (
  SELECT s.market_code, p.trade_date,
         CASE WHEN s.market_code='US' THEN 'US' ELSE s.market_code END AS idx
  FROM price_daily p JOIN stock s ON s.id=p.stock_id
  WHERE p.trade_date >= '2025-12-11'
  GROUP BY 1,2,3)
SELECT d.market_code, count(*) AS 거래일,
       count(*) FILTER (WHERE m.trade_date IS NULL) AS 지수결손일
FROM d LEFT JOIN market_index_daily m ON m.index_code=d.idx AND m.trade_date=d.trade_date
GROUP BY 1 ORDER BY 1"

 market_code | 거래일 | 지수결손일
-------------+--------+------------
 KOSDAQ      |    175 |          0
 KOSPI       |    175 |          0
 US          |    180 |          0
```

**결손이 0인데도 계약을 코드에 적는 이유는 그것이 데이터의 성질이지 코드의 성질이 아니기
때문이다.** 지수 적재가 하루 밀리면 그날이 결손이 된다.

## 측정 결과

| | |
|---|---|
| 되살린 파일 | 계산기 13 + `IchimokuScoring` + `ValueWidth` + `ValueWidthTieTest` = **16** |
| 충돌 | 3파일 · 각 헝크 1개 · 합 **32줄**(javadoc 2 · 지수 배선 1) |
| 테스트 | **1,326건 중 실패 1** — `SpaFallbackTest`뿐이고 `-PskipFrontend`가 만드는 예상된 실패다 |
| 배선 | `IndicatorContext.marketIndex()` · `ExcessReturn.at()`이 **그대로 남아 있었다** — 새로 깔 것이 없었다 |

## 사용자가 고른 문장과 내 해석

**MUST NOT: 둘을 붙여 적지 않는다** — 붙이면 다음 세션이 해석을 결정으로 읽는다.

| 갈림 | 사용자 문장 | 내 해석 (결정 아님) |
|---|---|---|
| 삭제의 정당성 | *"일목 지표를 화면에서 삭제해달라고 했을 뿐 계산기까지 삭제해달라고 한 적은 없어"* | ADR-0077의 「지표 18개 유지」는 **그때 이미 13개가 없는 상태**를 사실로 받아들여 적힌 수다. 사용자가 18을 고른 적이 없다 |
| 복구 여부 | *"만약 복구비용이 크지 않다면 복구해줘"* | 조건부 실행 지시로 읽었다. 비용을 재고(충돌 32줄·배선 잔존·테스트 회귀 0) 작다고 판정해 그대로 진행했다 |
| 지수 결손 처리 | **없다** | **사용자가 닫지 않은 질문이다.** D5 의존 일곱만 억제하는 것은 내 판단이고, 전면 억제(철회된 게이트)와 0 채우기(silent fail)를 둘 다 피하려면 남는 길이 이것뿐이었다 |

## 버린 대안

- **`git revert ffd8f24` 그대로** — `V25__drop_ichimoku_scoring_values.sql`이 **함께 지워진다.**
  적용된 마이그레이션이 사라지면 Flyway가 기동에서 죽는다(ADR-0077 §알려진 낡음).
  파일 복원 + 충돌 해결로 갈랐고 V25는 `git checkout HEAD --`로 되돌렸다.
- **전면 억제 게이트를 함께 되살린다** — 원래 코드가 그 모양이지만 `c6d12ba`가 철회한
  결정이다. 되살리면 지수 결손일에 **관측 18개까지 사라진다.**
- **낼 수 없는 D5를 0으로 채운다** — 코드가 짧아지지만 「지수 없음」과 「D5 중립」이
  화면에서 구별되지 않는다. 전역 `CLAUDE.md`의 silent fail 금지에 정면으로 걸린다.
- **D5 의존 여부를 세지 않고 열셋 전부에 지수를 요구한다** — 독립 6개까지 결손일에 사라진다.
  세는 비용이 `IchimokuScoring`의 반환문 한 번 읽는 것이라 갈랐다.
- **점수화를 조건 엔진으로 표현한다** — ADR-0046 §버린 대안이 이미 기각했다.
  `screen_condition`은 `지표 × 비교연산자 × {상수|지표×계수}`가 전부라 52셀 매트릭스를
  표현할 수 없다. **그 한계가 애초에 점수화 계층을 철회하게 만든 이유이기도 하다** —
  되살려도 그 대가(판정이 지표 안에서 끝나 사용자가 왜 걸렸는지 못 본다)는 그대로다.

## 결과

- **값이 아직 없다.** `V25`가 지운 54만 행은 백필로 채운다. 그전까지 13개는 조건 빌더에
  뜨지만 결과가 판정불가다.
- **`IchimokuAnalysis`의 일곱 필드가 `Integer`가 됐다.** 새 소비자는 `null`을 다뤄야 한다.
  기반 클래스가 `null`이면 그 일자를 담지 않으므로 계산기 쪽에는 번지지 않는다.
- **읽기 창은 안 움직인다.** `IchimokuIndicator.LOOKBACK = 1100`이 그대로이고, 워밍업
  최댓값은 여전히 `EPS_CAGR_5Y`·`RETAINED_TO_MARKET_4Y` 쪽이다(ADR-0066·0079).
- **되돌리려면**: `ffd8f24`가 지운 것과 같은 16파일을 다시 지우고, `IchimokuAnalyzer`의
  지수 인자와 `IchimokuIndicator.needsMarketIndex()`를 걷는다. `V25`는 그때도 남긴다.
