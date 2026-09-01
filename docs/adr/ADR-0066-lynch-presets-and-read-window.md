---
id: ADR-0066
status: 채택됨
date: 2026-08-18
applies_to:
  - "src/main/java/**/screening/preset/**"
  - "src/main/java/**/indicator/calculator/Peg*.java"
  - "src/main/java/**/indicator/calculator/EpsCagr*.java"
  - "src/main/java/**/indicator/calculator/PerMedian*.java"
  - "src/main/java/**/indicator/calculator/Return52w*.java"
  - "docs/external/피터린치-방법론.md"
---

# ADR-0066. 린치 방법론을 프리셋 둘로 옮기며 읽기 창을 1,261로 올리고 QVG_KR을 폐기한다

## 결정

`docs/external/피터린치-방법론.md`를 **조건의 출처로만** 들인다 — ADR-0041이 스윙 명세에 준 「프리셋 한정 정본」 지위를 주지 않는다. 임계값은 그 문서 §8이 아니라 국내 분포 실측이 정한다.

지표 넷(`PEG`·`RETURN_52W`·`EPS_CAGR_5Y`·`PER_MEDIAN_5Y`)과 프리셋 둘(`LYNCH_FAST_GROWER`·`LYNCH_STALWART`)을 만든다. 뒤의 두 지표 때문에 **러너의 워밍업 최댓값이 1,100 → 1,261봉으로 오른다** — 그때는 그것이 곧
읽기 창이었다. ADR-0079가 둘을 갈라 지금 읽기 창은 1,441이다.

`QVG_KR`을 폐기한다(2026-08-17 사용자 결정). 프리셋은 일곱에서 여덟이 된다.

## 규칙

- **MUST NOT: 이 문서에 「프리셋 조건셋의 정본」 지위를 주지 않는다.** ADR-0041의 스윙 명세와 다르다. 그 문서는 **TradingView Stock Screener 문법으로 쓰인 이식본**이고, 원저는 린치의 저술과 AAII 구현이다. 문서가 "스크리너로 불가"라 적은 것은 **TradingView의 한계**이지 우리의 한계가 아니므로, 그 분류를 우리 결손 목록으로 옮겨 적으면 열려 있는 길을 닫는다.
- **MUST NOT: 문서 §8의 한국 조정값을 재지 않고 옮기지 않는다.** 사유가 서술뿐이라 「몇 %가 통과하는가」에 답하지 못한다. **실측으로 전제 둘이 이미 깨졌다** — §배경.
- **MUST: 필수 게이트의 통과율을 「값이 있는 종목」이 아니라 「유니버스 전체」로 답한다.** `ConditionEvaluator.satisfiesRequired()`가 `allMatch(passesFilter)`이므로 **판정불가는 탈락**이다. 분모를 값이 있는 종목으로 잡으면 커버리지가 낮은 지표일수록 통과율이 높게 보여, ADR-0061의 90% 규칙이 **정반대 방향을 감시하게 된다.**
- **MUST NOT: `SHARES_GROWTH_3Y`를 「자사주 매입」이라 부르지 않는다.** 그것은 **상장주식수 희석**을 재고 분할·무상증자는 원장 배수로 걷어낸다(ADR-0051). 한국에서 자사주 *취득*은 상장주식수를 줄이지 않으므로 **매입은 탐지되지 않고 소각만 잡힌다.** 문서 4.5의 우회(발행주식수 증감 육안 확인)를 자동화한 것이며 그 이상이 아니다.
- **MUST NOT: 「ADR-0061이 `QVG_KR`을 감사된 근거 없음으로 판정했다」고 적지 않는다.** 그런 문장은 저장소에 없다(`rg "감사된" docs/adr/`이 0건). 폐기 근거는 **축 중복과 사용자 결정**이며 §배경이 정본이다.
- **MUST NOT: 「자리를 만들려면 폐기해야 한다」고 적지 않는다.** 프리셋 개수 상한은 코드·DB·화면 어디에도 없다. 폐기와 추가는 기술적으로 독립이고, 묶은 것은 사용자가 고른 방향이다.
- **MUST: `EPS_CAGR_5Y`의 주식수를 양 끝에서 각각 읽는다.** 같은 날짜 주식수를 쓰면 약분되어 값이 **순이익 CAGR**과 정확히 같아지는데 이름은 EPS라고 말한다. 이것이 워밍업 최댓값을 1,261로 미는 유일한 이유이며, **창을 아끼려고 약분을 택하면 그 지표는 이름이 거짓이 된다.**
- **MUST NOT: `EPS_CAGR_5Y`·`PER_MEDIAN_5Y`를 `financialYears(6)`만으로 선언하지 않는다.** `priceBarsNeeded = 0`이면 `effectiveLookback = 0`이 되고, `IndicatorRunner`가 `UnadjustedActionGuard.suppressedDates(bars, actions, 0)`을 불러 **미보정 기업행위 억제(ADR-0055)가 무력화된다.**
- **MUST: 옮기지 못한 조건을 `description`에 적는다**(ADR-0061 승계). 이 프리셋들은 결손이 많다 — 배당 계열 셋, 순부채/EBITDA, FCF, 재고, 섹터 제외.

## 검증

```bash
rg -n "자사주 매입" src/main/java/com/stockscreener/indicator/calculator/SharesGrowth3yCalculator.java
```
아무것도 반환하지 않아야 한다. **대상을 그 파일 하나로 좁힌 이유**는 프리셋 설명이 "자사주 매입을
보지 못한다"라고 **부정문으로** 같은 낱말을 쓰기 때문이다 — 저장소 전체를 훑으면 그 부정문까지
발견으로 잡혀 펜스가 매번 시끄럽고, 시끄러운 펜스는 곧 무시된다. 지표의 뜻을 말하는 자리는
`@IndicatorMeta` 하나이므로 거기만 본다.

```bash
rg -n "financialYears\(" src/main/java/com/stockscreener/indicator/calculator/EpsCagr5yCalculator.java \
   src/main/java/com/stockscreener/indicator/calculator/PerMedian5yCalculator.java
```
아무것도 반환하지 않아야 한다 — 둘 다 `priceAndFinancial(...)`이어야 한다.

```premise 린치 고속성장주의 시총 상한이 아직 중앙값 근처에 앉아 있다
psql -d stock_screener -At -c "WITH v AS (
  SELECT value FROM (SELECT iv0.* FROM indicator_value iv0 JOIN stock s0 ON s0.id = iv0.stock_id WHERE s0.market_code IN ('KOSPI','KOSDAQ')) ivkr
  WHERE indicator_code = 'MARKET_CAP'
    AND trade_date = (SELECT max(trade_date) FROM indicator_trade_date WHERE market_code IN ('KOSPI','KOSDAQ')))
SELECT CASE
  WHEN (SELECT count(*) FROM v) = 0 THEN '잴 것이 없었다 — MARKET_CAP 값이 하나도 없다'
  WHEN (SELECT count(*) FROM v WHERE value <= 2616000000000)::numeric / (SELECT count(*) FROM v)
       BETWEEN 0.40 AND 0.60
    THEN 'OK 시총 상한 통과율 ' || round((SELECT count(*) FROM v WHERE value <= 2616000000000)::numeric
         / (SELECT count(*) FROM v) * 100, 1) || '%'
  ELSE '시총 상한이 중앙값에서 벗어났다 — 통과율 ' || round((SELECT count(*) FROM v WHERE value <= 2616000000000)::numeric
       / (SELECT count(*) FROM v) * 100, 1) || '% (의도 40~60%). 원화 리터럴 상한이라 시장이 오르면 조여진다'
  END"
```

```premise 린치 프리셋의 필수 게이트가 유니버스의 90% 넘게 통과시키지 않는다
psql -d stock_screener -At -c "WITH days AS ( SELECT trade_date d, market_code mc FROM ( SELECT trade_date, market_code, row_number() OVER (PARTITION BY market_code ORDER BY trade_date DESC) rn FROM indicator_trade_date) z WHERE rn % 20 = 1), tgt AS (SELECT u.code tc, u.market_code mc, mk.currency_code cc FROM universe u JOIN market mk ON mk.code = u.market_code WHERE u.is_active), uni AS (SELECT t.tc, t.cc, dd.d, m.stock_id FROM tgt t JOIN days dd ON dd.mc = t.mc JOIN universe_membership m ON m.universe_code = t.tc AND m.effective_to IS NULL), pop AS (SELECT tc, count(*) n FROM uni GROUP BY 1), codes AS (SELECT DISTINCT c.indicator_code ind FROM screen_condition c JOIN condition_group cg ON cg.id = c.group_id JOIN screen s ON s.id = cg.screen_id WHERE s.code LIKE 'LYNCH~_%' ESCAPE '~' AND s.is_active AND c.is_required), vals AS MATERIALIZED (SELECT iv.stock_id, iv.trade_date, iv.indicator_code, iv.value FROM indicator_value iv WHERE iv.trade_date IN (SELECT DISTINCT d FROM days) AND iv.indicator_code IN (SELECT ind FROM codes)), g AS (SELECT t.tc, c.indicator_code ind, c.operator op, c.threshold_currency base, COALESCE(x.literal_value, CASE WHEN c.threshold_currency IS NULL OR c.threshold_currency = t.cc THEN c.literal_value END) v1 FROM screen_condition c JOIN condition_group cg ON cg.id = c.group_id JOIN screen s ON s.id = cg.screen_id CROSS JOIN tgt t LEFT JOIN screen_condition_threshold x ON x.condition_id = c.id AND x.currency_code = t.cc WHERE s.code LIKE 'LYNCH~_%' ESCAPE '~' AND s.is_active AND c.is_required AND c.operand_type = 'LITERAL' AND c.literal_value_2 IS NULL AND c.indicator_code <> 'TURNOVER_RATIO_60'), pass AS (SELECT g.tc, g.ind, g.v1, count(*) FILTER (WHERE CASE g.op WHEN 'LTE' THEN iv.value <= g.v1 WHEN 'LT' THEN iv.value < g.v1 WHEN 'GTE' THEN iv.value >= g.v1 WHEN 'GT' THEN iv.value > g.v1 END) k FROM g JOIN uni u ON u.tc = g.tc LEFT JOIN vals iv ON iv.stock_id = u.stock_id AND iv.indicator_code = g.ind AND iv.trade_date = u.d WHERE g.v1 IS NOT NULL GROUP BY 1, 2, 3), loose AS (SELECT p.tc, p.ind, round(p.k::numeric * 100 / o.n, 1) r FROM pass p JOIN pop o ON o.tc = p.tc WHERE p.k::numeric / o.n > 0.90) SELECT CASE WHEN (SELECT count(*) FROM uni) = 0 THEN '잴 것이 없었다 — 편입이 있고 지표 달력이 찬 조회 대상이 하나도 없다' WHEN (SELECT count(*) FROM g) = 0 THEN '잴 것이 없었다 — 린치 프리셋의 리터럴 필수 게이트가 하나도 없다' WHEN (SELECT count(*) FROM g WHERE v1 IS NULL) > 0 THEN '그 시장 임계가 없어 실행이 거절되는 게이트가 있다(느슨한 것과 다른 사실이다): ' || (SELECT string_agg(DISTINCT tc || ' ' || ind, ', ') FROM g WHERE v1 IS NULL) WHEN (SELECT count(*) FROM loose) = 0 THEN 'OK 린치 필수 게이트가 조회 대상 ' || (SELECT count(DISTINCT tc) FROM pass) || '곳에서 전부 90% 미만을 통과시킨다(보존 창 ' || (SELECT count(DISTINCT d) FROM days) || '시점 표본)' ELSE '게이트가 아니라 상수인 조건이 있다: ' || (SELECT string_agg(tc || ' ' || ind || ' ' || r || '%', ', ' ORDER BY tc, ind) FROM loose) END"
```

**기준이 단면이 아니라 보존 창이다(2026-09-01).** 「이 게이트가 상수인가」는 **시간에 대한
주장**이라 하루로는 성립하지 않는다 — 실측으로 `RETURN_52W < 100`의 미국 통과율이 최신
일자에 **90.7%**인데 20거래일 간격 9시점에서 85.2~94.0으로 **네 번 90%를 넘나든다.**
그 하루로 판정하면 국면을 상수로 신고한다. ADR-0109 §규칙이 임계에 대해 같은 이동을 이미
했고(*"단면 하나는 그날의 국면을 탄다"*), 이 술어도 같은 기준을 쓴다.
**진짜 상수는 여전히 붉는다** — 모든 시점에서 100% 가까이 통과하면 창 평균도 90%를 넘는다.
**MUST: 20거래일 간격으로 표본한다.** 전 구간을 훑으면 전제 예산(30초)을 먹는다 — 표본으로
0.25초다.

**조회 대상마다 따로 잰다**(ADR-0108). 한 시장으로 뭉쳐 세면 미국 쪽이 안 보이고, 임계는
그 대상의 통화로 해석한다 — `screen_condition.literal_value`는 **기준 통화 하나**뿐이라
직접 읽으면 원화 임계로 미국을 판정한다(ADR-0106). **그 통화 임계가 없으면 「실행 거절」로
따로 신고한다** — 느슨한 것과 다른 사실이고, 축을 안 쓴 조건을 「시장 중립」으로 읽는 것은
P27 축①이 금지한다.

**대상을 가르면 모집단이 좁아져 전보다 붉어질 수 있다.** 그것은 회귀가 아니라 **해상도**다 —
라이브가 대상 하나로만 도므로(ADR-0107) 그 수가 사용자가 실제로 보는 것이다.

**`TURNOVER_RATIO_60`을 모집단에서 뺀다.** ADR-0070의 leave-one-out 펜스가 그것을 이미
신고하고 있어(세 대상 전부에서 상수다) 여기서도 세면 **같은 발견이 둘이 된다** — 그 결함이
아니라 그 소리에 익숙해지는 것이 문제다. 답이 게이트를 빼는 것이라 사용자 결정으로 묶여 있다
(ADR-0044). **이 제외가 자라는 자리**: 다른 게이트가 같은 상태가 되면 여기서 빼지 말고
그 게이트를 고칠지부터 정한다.

**`TURNOVER_RATIO_60`을 모집단에서 뺐다(2026-08-31).** 그 조건은 **선별자가 아니라 거래
가능성 하한**이고, 이 규칙(ADR-0061 §규칙)이 잡으려는 것은 「안전장치처럼 보이는데 아무것도
안 하는 선별자」다. 하한이 대부분을 통과시키는 것은 하한이 제 일을 하는 모양이다 —
**그 판정의 근거는 통과율이 아니라 「무엇을 자르는가」다**(통과율 근거는 ADR-0112가 무너뜨렸다).
실측으로 0.15가 잘라 내는 집합의 일평균 체결금액 중앙값이 **국내 24.6억원 · 미국 $5.9M**이고
남는 집합은 **241억원 · $220M**이다(2026-09-01). 안 빼면 **매 세션 거짓 발화**하고, 거짓 발견은
침묵보다 나쁘다.

**MUST: 이 목록이 자라면 그때 규칙을 다시 본다.** 지금은 하나이고, 둘째가 생기는 순간
「하한인가 선별자인가」를 조건마다 손으로 판정하고 있다는 뜻이므로 그 축을 데이터로
표현할 때다. **그리고 이 제외는 「자르는 것이 0인 게이트」 축(ADR-0070)에는 걸지 않았다** —
그쪽은 다른 질문이고, 답이 게이트를 빼는 것이라 사용자 결정이다.

## 배경

문서가 "스크리너로 불가"라 적은 항목 중 **일곱이 우리에게는 열린다** — 조건 우변에 지표를 배수와 함께 둘 수 있고(`ConditionOperand.IndicatorRef`), 업종 상대 지표가 이미 있고(`SectorMetricConfig`), 없는 지표를 파일 하나로 만들 수 있기 때문이다. 반대로 배당 데이터가 없어 린치의 배당 계열 셋이 통째로 닫히고, 조건 좌변이 지표 FK라 섹터 범주 제외를 표현할 수 없다.

**§8의 전제 둘이 실측으로 깨졌다**(2026-08-13 기준일, 활성 347종목).

| §8이 적은 것 | 실측 |
|---|---|
| `P/B 0~0.8` — "시장 평균 PBR이 1 내외" | 우리 유니버스 PBR 중앙값이 **2.10**이다. 그 값은 상위 20%(69/346)만 남긴다 |
| `PEG`를 1.0 → **1.2로 푼다** — "성장 종목 절대수가 적음" | PEG 근사 중앙값이 **0.31**(p25 0.09)이라 방향이 반대다 |

코스피200·코스닥150은 시장 전체보다 PBR이 높다. 이것이 ADR-0061의 「절대 임계는 국내 분포를 재고 정한다」가 겨냥한 자리다.

**`QVG_KR` 폐기의 근거는 축 중복이다.** `QVG_KR`과 린치 고속성장주가 `PER`·`EPS_GROWTH_TTM_YOY`·`REVENUE_GROWTH`·`DEBT_RATIO`를 공유하는데, `QVG_KR`의 성장 축인 GARP는 개념 자체가 린치에게서 왔으면서 **그 판별 도구인 PEG를 갖고 있지 않다.** 사용자가 "잡탕 방식은 원하지 않는다, 아예 린치로 갈아엎어야 한다"로 정했다(2026-08-17, 프리셋이 서로 독립이라 유지해도 조건이 섞이지 않는다는 것을 확인한 뒤의 결정이다). 잃는 것은 `GP_TO_ASSETS` 축 하나이고 `VALUE`에 남는다. `QVG_KR`을 참조하는 워치리스트는 0건이라 `last_satisfied` 리셋(ADR-0061)이 걸리지 않는다.

**spec §3이 `(예: Value, QVG형 전략)`이라 적어 QVG를 이름으로 언급한다.** 괄호가 예시임을 말하고 §9 수용 기준은 "최소 1개 이상"이지만, spec에 이름이 적힌 둘 중 하나가 사라지는 변경이라 사용자에게 제안하고 승인받았다(CLAUDE.md의 기획 정본 조항). `PresetCatalogTest.declaresRequiredPresets`의 DisplayName "spec §3이 요구한"은 과장이므로 함께 고친다.

**워밍업 최댓값이 오르는 이유는 정확도가 아니라 원자료의 형태다.** `financial_statement.shares_outstanding`과 `eps`가 **10,963행 전량 NULL**이라 주당 값을 재무제표에서 얻을 수 없고, 주식수의 정본은 `price_daily.listed_shares`다(ADR-0013). 5년 전 주식수를 읽으려면 **1,261거래일**이 필요하다 — 저장소가 1년을 252거래일로 보는 관례(`ExcessReturn.LOOKBACK_52W`·`EpsGrowthTtmYoyCalculator.LOOKBACK`)를 따른 값이며, 달력으로 FY2020 기말까지 거슬러 세면 1,376이지만 그것은 기간을 회계연도에 맞출 때의 수다.

**창 상향이 기존 지표를 바꾸지 않는다.** P12가 같은 일을 했고(751 → 1,100, 349봉) 값이 바뀐 지표가 하나도 없었다 — Wilder/EMA 잔차가 `(13/14)^349 ≈ 6e-12`로 저장 자릿수(8) 아래이기 때문이다. 이번은 276봉을 더 넓히므로 잔차는 더 작다. `TRADING_DAYS_750`은 실측 최댓값이 750으로 캡되어 있어(min 59 / max 750) 창과 무관하다. 비용은 단일 거래일 백필 91초에서 읽는 봉이 25% 느는 것이고, **백필은 단일 날짜 1회로 끝난다.**

## 버린 대안

- **문서를 ADR-0041처럼 프리셋 한정 정본으로 승격** — 그러면 §8을 그대로 옮겨야 하는데 전제 둘이 이미 깨졌다. 원저(린치·AAII)와 이식본(TradingView 문법)을 구분하지 않으면 **이식자의 도구 한계가 원저의 조건인 것처럼 굳는다.**
- **`EPS_CAGR_5Y`를 3년으로 좁혀 창을 지키기** — 읽기 창이 그대로라 싸지만 린치 3.17의 원문(5Y CAGR)에서 이탈하고, 같은 창으로 함께 열리는 3.5(자기 5년 평균 PER 대비)를 잃는다. 실측으로 창 상향의 시간 비용이 백필 1회 20초 안팎이라 이탈의 대가가 더 컸다.
- **양 끝에 같은 날짜 주식수를 써서 창을 아끼기** — 약분되어 순이익 CAGR이 된다. `EpsGrowthTtmYoyCalculator` javadoc이 같은 함정을 이미 적어 뒀다.
- **`IS_FINANCIAL` 0/1 지표로 린치 5.5(금융·부동산 제외)를 열기** — KSIC 중분류 64가 금융업과 지주회사 버킷을 겸해 코드로 가를 수 없고, §8은 지주회사도 빼라고 한다. **`AltmanZCalculator`의 `{65, 66}`은 승인된 반쪽이지만**(ADR-0044) 목적이 다르다 — 그것은 지표가 뜻을 잃는 자리에서 **값을 안 내는** 것이고, 린치 5.5는 **종목을 결과에서 빼는 게이트**라 반쪽이면 "제외했다"는 문구만 남고 지주회사가 통과한다.
- **`QVG_KR`을 남기고 린치 둘을 더하기** — 기술적으로 가능하고(개수 상한 없음) 조건도 섞이지 않는다. 사용자가 단일 방법론으로의 개편을 택했다.

## 결과

린치 항목 중 스크리너로 판별 가능한 것이 거의 전부 열린다 — 배당 계열과 원자료가 없는 넷을 뺀 나머지다. 문서 §9의 「수동 조사 항목」은 그대로 남는다.

**워밍업 최댓값 1,261이 새 바닥이 된다.** `ICHI_*` 18개가 1,100을 붙들고 있던 자리를 이 둘이 넘겨받으므로, **이제 `ICHI_*`를 지워도 창이 1,100으로 내려가지 않는다** — ADR-0061의 "그 18개가 창을 붙들고 있다"는 근거가 이 결정 이후 더는 창을 지키지 못한다. 그 MUST NOT의 나머지 근거(조건 빌더에서 계속 고를 수 있다)는 유효하다.

되돌리려면 `PresetCatalog`에서 프리셋 둘을 빼고 `QVG_KR`을 되살리며 계산기 넷을 지운다. **창이 저절로 1,100으로 돌아가지는 않는다** — `RETAINED_TO_MARKET_4Y`가 이 결정과 무관하게 1,261을 선언하고, `maxEffectiveLookback()`은 매번 전 지표 최댓값으로 계산한다. **다만 창이 좁아지면 재귀 지표 넷의 시드가 뒤로 밀리므로, 되돌릴 때도 스냅숏 대조를 밟는다.**
