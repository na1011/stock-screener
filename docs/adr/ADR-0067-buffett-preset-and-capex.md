---
id: ADR-0067
status: 채택됨
date: 2026-08-18
applies_to:
  - "src/main/java/**/screening/preset/**"
  - "src/main/java/**/indicator/calculator/RoeMin3y*.java"
  - "src/main/java/**/indicator/calculator/ProfitStreak*.java"
  - "src/main/java/**/indicator/calculator/NetMargin*.java"
  - "src/main/java/**/indicator/calculator/RoicPretax*.java"
  - "src/main/java/**/indicator/calculator/OperatingMarginMin5y*.java"
  - "src/main/java/**/indicator/calculator/RetainedToMarket*.java"
  - "src/main/java/**/indicator/calculator/Fcf*.java"
  - "src/main/java/**/pipeline/datasource/kr/DartFullAccountMapper.java"
  - "src/test/java/**/DartCapexProbeLiveTest.java"
  - "docs/external/워렌버핏-방법론.md"
---

# ADR-0067. 버핏 방법론을 프리셋 하나로 옮기고, 재보지 않은 채 닫아 두었던 자본적지출을 연다

## 결정

`docs/external/워렌버핏-방법론.md`를 **조건의 출처로만** 들인다 — ADR-0041이 스윙 명세에 준
「프리셋 한정 정본」 지위를 주지 않는다(ADR-0066과 같다). 임계값은 그 문서 §10이 아니라 국내
분포 실측이 정한다.

**자본적지출을 적재한다.** 저장소가 "소스가 주지 않는다"고 적어 두고 있었으나 **재본 적이
없었고, 재보니 8/8로 왔다**(§배경). `financial_statement.capex` 컬럼을 더하고
`full-statement-refill`로 채운다. 새 엔드포인트가 아니다 — 이미 쓰고 있는
`fnlttSinglAcntAll` 응답 안에 있었고 매퍼가 읽지 않았을 뿐이다(ADR-0062와 같은 형태다).

지표 여덟(`ROE_MIN_3Y`·`PROFIT_STREAK_Y`·`NET_MARGIN`·`ROIC_PRETAX`·`OPM_MIN_5Y`·
`RETAINED_TO_MARKET_4Y`·`FCF_MARGIN`·`FCF_YIELD`)과 프리셋 하나(`BUFFETT_QUALITY`)를
만든다. 프리셋은 아홉이 된다 — **폐기하는 것이 없다.**

**워밍업 최댓값은 1,261 그대로다**(읽기 창은 ADR-0079가 그 위에 보존 일수를 얹어 1,441이다).

## 규칙

- **MUST NOT: 이 문서에 「프리셋 조건셋의 정본」 지위를 주지 않는다.** TradingView Stock Screener
  문법으로 쓰인 이식본이고 원저는 버핏의 주주서한과 해그스트롬·메리 버핏·AAII의 계량 구현이다.
  문서가 "스크리너로 불가"라 적은 것은 **TradingView의 한계**이지 우리의 한계가 아니다.
- **MUST NOT: 문서 §10의 한국 조정값을 재지 않고 옮기지 않는다**(ADR-0066 승계). 린치 문서 §8과
  달리 **`ROE`는 방향이 실측과 맞았다** — 그렇다고 나머지가 맞다는 뜻이 아니므로 각각 잰다.
- **MUST: 필수 게이트의 통과율을 「값이 있는 종목」이 아니라 「유니버스 전체」로 답한다**
  (ADR-0066). `satisfiesRequired()`가 `allMatch`라 **판정불가는 탈락**이다.
- **MUST: 게이트끼리 같은 방향으로 겹치는지 독립 가정 대비 배수로 답한다.** 개별 통과율이 멀쩡해도
  교집합이 무너질 수 있다. 실제로 `ROE_MIN_3Y`와 `PROFIT_STREAK_Y`가 **1.53배** 겹친다.
- **MUST NOT: 결손을 「소스가 주지 않는다」로 적기 전에 재보지 않은 것이 아닌지 확인한다.**
  이 ADR이 존재하는 첫째 이유다. 재서 닫은 판정과 재보지 않은 추정이 **같은 문장 형태로** 나란히
  앉으면 다음 세션은 둘을 구별하지 못한다.
- **MUST: 자본적지출을 크기로 읽는다.** 부호 규약이 **회사마다 갈린다** — 탐침 8종목은 전부
  양수였으나 350종목 재적재에서 **22행(1.5%)이 음수**다. 계정이 「유형자산의 취득」이라 음수일 수
  없으므로 그것은 값이 아니라 표기다(§배경). 부호를 그대로 빼면 그 행에서 **뺄셈이 덧셈이 되어**
  잉여현금흐름이 영업활동현금흐름보다 커지고 **값이 그럴듯해 드러나지 않는다.**
- **MUST NOT: 그 정규화를 적재 계층으로 내리지 않는다.** `financial_statement`는 원자료만 담고
  판단은 지표 계층이 한다 — 미리 정규화하면 소스의 표기가 실제로 바뀌었을 때 알아볼 방법이 없다.
- **MUST NOT: 크기 취하기를 순증감 계정에 복사하지 않는다.** 「취득」이 한 방향 항목이라 안전한
  것이고, `단기금융상품의 순감소(증가)`류에 같은 짓을 하면 방향 정보가 통째로 사라진다.
- **MUST NOT: 분기 자본적지출을 저장하지 않는다.** CF의 분기 금액이 3개월인지 누적인지 재지
  않았다(`dart-financial.md`의 「전체 재무제표」 절). 모른 채 합치면 4배나 1/4로 조용히 틀린다 —
  `operating_cash_flow`가 연간 전용인 것과 같은 이유다(ADR-0056).
- **MUST NOT: 잉여현금흐름을 「오너 어닝」이라 부르지 않는다.** 오너 어닝은 운전자본 증분까지
  차감하며 감가상각비가 필요한데 그것은 닫혀 있다(§7 1/4 · §12 실질 2/8). 문서 3.3이 그 차이를
  스스로 명시한다. **대체품**이라고 적는다.
- **MUST NOT: 세전 투하자본이익률을 `ROIC`라 부르지 않는다.** 표준 ROIC는 NOPAT(세후) 기준인데
  법인세비용 컬럼이 없다. 유효세율을 순이익에서 역산하는 것은 **소스가 주지 않는 값을 합성하는
  것**이라 하지 않는다(ADR-0061). 이름이 세전임을 말하게 한다.
- **MUST: 연수를 계산기 안에서 상수로 자른다.** `IndicatorRegistry.maxFinancialYears()`가
  **전 계산기의 최댓값 하나**이고 러너가 그 하나로 모두에게 같은 목록을 준다. `annual.size()`에서
  연수를 유도하면 **나중에 `financialYears(10)`짜리 지표가 하나 생기는 순간 `PROFIT_STREAK_Y`가
  8·9·10을 내고 `≥ 7` 게이트가 조용히 헐거워진다.**
- **MUST: 과거 주식수에 `corporateActionMultiple`을 곱해 현재 축척으로 옮긴다**(ADR-0066 승계).
  `sharesOn()`은 권리락~신주상장 구간만 보정한다 — 분할·무상증자 배수는 따로 걷어내야 하고, 안
  걷으면 5:1 분할이 400% 희석으로 읽힌다.
- **MUST NOT: 데이터가 우연히 걸러 주는 것을 안전장치로 세지 않는다.** 소스 정책 때문에 금융업이
  덜 통과하는 것은 **설계가 아니라 부작용**이다 — 실측으로 KSIC 64·65·66의 **50종목 중 14가**
  7년 연속 흑자 게이트를 통과한다. 「금융업을 제외했다」고 적으면 거짓이다.
- **MUST: 옮기지 못한 조건을 `description`에 적는다**(ADR-0039·0061·0066 승계).

## 검증

```bash
rg -n 'name = "[^"]*오너' src/main/java/com/stockscreener/indicator/calculator/
```
아무것도 반환하지 않아야 한다 — 어느 지표도 자기 이름을 「오너 어닝」이라 말하지 않는다.

**낱말을 통째로 찾지 않고 `name` 필드만 보는 이유**는 이 낱말이 저장소에서 **부정문으로 더 많이
쓰이기 때문이다** — 계산기 javadoc과 `interpretation`이 *"이 값은 오너 어닝이 아니다"* 라고
적고 프리셋 설명도 결손으로 같은 말을 한다. 낱말을 세면 그 부정문이 전부 발견으로 잡혀
**펜스가 처음부터 빨갛고, 시끄러운 펜스는 곧 무시된다**(ADR-0066이 `SHARES_GROWTH_3Y`에서
같은 함정을 밟았다). 거짓이 되는 자리는 **표시 이름 하나**이므로 거기만 본다.

```bash
rg -n "abs" src/main/java/com/stockscreener/pipeline/collector/FinancialWriter.java \
   src/main/java/com/stockscreener/pipeline/datasource/kr/DartFullAccountMapper.java
```
아무것도 반환하지 않아야 한다 — 부호 정규화는 지표 계층(`FreeCashFlow`)의 몫이고 적재 계층은
소스가 준 값을 그대로 담는다.

```premise 자본적지출이 재무제표에 실제로 채워져 있다
psql -d stock_screener -At -c "WITH fy AS (
  SELECT f.capex FROM financial_statement f JOIN stock s ON s.id = f.stock_id
  WHERE s.market_code IN ('KOSPI','KOSDAQ') AND f.fiscal_period = 'FY' AND f.is_latest AND f.fiscal_year >= 2021)
SELECT CASE
  WHEN (SELECT count(*) FROM fy) = 0
    THEN '잴 것이 없었다 — FY2021 이후 재무제표 행이 하나도 없다'
  WHEN (SELECT count(capex) FROM fy) = 0
    THEN '자본적지출이 한 행도 없다 — 컬럼만 생기고 재적재가 돌지 않았다(ADR-0062의 함정)'
  WHEN (SELECT count(capex) FROM fy)::numeric / (SELECT count(*) FROM fy) < 0.70
    THEN '자본적지출 커버리지가 ' || round((SELECT count(capex) FROM fy)::numeric
         / (SELECT count(*) FROM fy) * 100, 1) || '%로 떨어졌다 — 재적재가 덜 돌았거나 소스가 바뀌었다'
  ELSE 'OK 자본적지출 ' || (SELECT count(capex) FROM fy) || '/' || (SELECT count(*) FROM fy)
       || '행, 그중 음수 ' || (SELECT count(*) FROM fy WHERE capex < 0)
       || '개(표기 관례이며 지표 계층이 크기로 읽는다)'
  END"
```

```premise 버핏 프리셋의 필수 게이트가 유니버스의 90% 넘게 통과시키지 않는다
psql -d stock_screener -At -c "WITH tgt AS (SELECT u.code tc, mk.currency_code cc, (SELECT max(d.trade_date) FROM indicator_trade_date d WHERE d.market_code = u.market_code) t FROM universe u JOIN market mk ON mk.code = u.market_code JOIN universe_membership m ON m.universe_code = u.code AND m.effective_to IS NULL WHERE u.is_active GROUP BY 1, 2), uni AS (SELECT t.tc, t.cc, t.t, m.stock_id FROM tgt t JOIN universe_membership m ON m.universe_code = t.tc AND m.effective_to IS NULL WHERE t.t IS NOT NULL), pop AS (SELECT tc, count(*) n FROM uni GROUP BY 1), codes AS (SELECT DISTINCT c.indicator_code ind FROM screen_condition c JOIN condition_group cg ON cg.id = c.group_id JOIN screen s ON s.id = cg.screen_id WHERE s.code LIKE 'BUFFETT~_%' ESCAPE '~' AND s.is_active AND c.is_required), vals AS MATERIALIZED (SELECT iv.stock_id, iv.trade_date, iv.indicator_code, iv.value FROM indicator_value iv WHERE iv.trade_date IN (SELECT DISTINCT t FROM tgt WHERE t IS NOT NULL) AND iv.indicator_code IN (SELECT ind FROM codes)), g AS (SELECT t.tc, c.indicator_code ind, c.operator op, c.threshold_currency base, COALESCE(x.literal_value, CASE WHEN c.threshold_currency IS NULL OR c.threshold_currency = t.cc THEN c.literal_value END) v1 FROM screen_condition c JOIN condition_group cg ON cg.id = c.group_id JOIN screen s ON s.id = cg.screen_id CROSS JOIN tgt t LEFT JOIN screen_condition_threshold x ON x.condition_id = c.id AND x.currency_code = t.cc WHERE s.code LIKE 'BUFFETT~_%' ESCAPE '~' AND s.is_active AND c.is_required AND c.operand_type = 'LITERAL' AND c.literal_value_2 IS NULL AND c.indicator_code <> 'TURNOVER_RATIO_60'), pass AS (SELECT g.tc, g.ind, g.v1, count(*) FILTER (WHERE CASE g.op WHEN 'LTE' THEN iv.value <= g.v1 WHEN 'LT' THEN iv.value < g.v1 WHEN 'GTE' THEN iv.value >= g.v1 WHEN 'GT' THEN iv.value > g.v1 END) k FROM g JOIN uni u ON u.tc = g.tc LEFT JOIN vals iv ON iv.stock_id = u.stock_id AND iv.indicator_code = g.ind AND iv.trade_date = u.t WHERE g.v1 IS NOT NULL GROUP BY 1, 2, 3), loose AS (SELECT p.tc, p.ind, round(p.k::numeric * 100 / o.n, 1) r FROM pass p JOIN pop o ON o.tc = p.tc WHERE p.k::numeric / o.n > 0.90) SELECT CASE WHEN (SELECT count(*) FROM uni) = 0 THEN '잴 것이 없었다 — 편입이 있고 지표 달력이 찬 조회 대상이 하나도 없다' WHEN (SELECT count(*) FROM g) = 0 THEN '잴 것이 없었다 — 버핏 프리셋의 리터럴 필수 게이트가 하나도 없다' WHEN (SELECT count(*) FROM g WHERE v1 IS NULL) > 0 THEN '그 시장 임계가 없어 실행이 거절되는 게이트가 있다(느슨한 것과 다른 사실이다): ' || (SELECT string_agg(DISTINCT tc || ' ' || ind, ', ') FROM g WHERE v1 IS NULL) WHEN (SELECT count(*) FROM loose) = 0 THEN 'OK 버핏 필수 게이트가 조회 대상 ' || (SELECT count(DISTINCT tc) FROM pass) || '곳에서 전부 90% 미만을 통과시킨다' ELSE '게이트가 아니라 상수인 조건이 있다: ' || (SELECT string_agg(tc || ' ' || ind || ' ' || r || '%', ', ' ORDER BY tc, ind) FROM loose) END"
```

**조회 대상마다 따로 잰다**(ADR-0108). 한 시장으로 뭉쳐 세면 미국 쪽이 안 보이고, 임계는
그 대상의 통화로 해석한다 — `screen_condition.literal_value`는 **기준 통화 하나**뿐이라
직접 읽으면 원화 임계로 미국을 판정한다(ADR-0106). **그 통화 임계가 없으면 「실행 거절」로
따로 신고한다** — 느슨한 것과 다른 사실이고, 축을 안 쓴 조건을 「시장 중립」으로 읽는 것은
P27 축①이 금지한다.

**대상을 가르면 모집단이 좁아져 전보다 붉어질 수 있다.** 그것은 회귀가 아니라 **해상도**다 —
라이브가 대상 하나로만 도므로(ADR-0107) 그 수가 사용자가 실제로 보는 것이다.

**`TURNOVER_RATIO_60`을 모집단에서 뺀다** — ADR-0070의 펜스가 이미 신고한다(위와 같은 이유).

**`TURNOVER_RATIO_60`을 모집단에서 뺐다(2026-08-31).** 그 조건은 **선별자가 아니라 거래
가능성 하한**이고, 이 규칙(ADR-0061 §규칙)이 잡으려는 것은 「안전장치처럼 보이는데 아무것도
안 하는 선별자」다. 하한이 대부분을 통과시키는 것은 하한이 제 일을 하는 모양이다 —
실측으로 국내 96.3% · 미국 96.6%를 통과시키고 걸러내는 3.7%가 거래가 얇은 종목이다(ADR-0105).
안 빼면 **매 세션 거짓 발화**하고, 거짓 발견은 침묵보다 나쁘다.

**MUST: 이 목록이 자라면 그때 규칙을 다시 본다.** 지금은 하나이고, 둘째가 생기는 순간
「하한인가 선별자인가」를 조건마다 손으로 판정하고 있다는 뜻이므로 그 축을 데이터로
표현할 때다. **그리고 이 제외는 「자르는 것이 0인 게이트」 축(ADR-0070)에는 걸지 않았다** —
그쪽은 다른 질문이고, 답이 게이트를 빼는 것이라 사용자 결정이다.

```premise 3년 연속 ROE 임계가 분포의 상위권에 앉아 있다
psql -d stock_screener -At -c "WITH v AS (
  SELECT value FROM (SELECT iv0.* FROM indicator_value iv0 JOIN stock s0 ON s0.id = iv0.stock_id WHERE s0.market_code IN ('KOSPI','KOSDAQ')) ivkr
  WHERE indicator_code = 'ROE_MIN_3Y'
    AND trade_date = (SELECT max(trade_date) FROM indicator_trade_date WHERE market_code IN ('KOSPI','KOSDAQ'))),
r AS (SELECT (SELECT count(*) FROM v WHERE value < 10)::numeric / NULLIF((SELECT count(*) FROM v), 0) pr)
SELECT CASE
  WHEN (SELECT count(*) FROM v) = 0 THEN '잴 것이 없었다 — ROE_MIN_3Y 값이 하나도 없다'
  WHEN (SELECT pr FROM r) BETWEEN 0.70 AND 0.95
    THEN 'OK 임계 10이 분포의 상위 ' || round((1 - (SELECT pr FROM r)) * 100, 1) || '%에 앉아 있다'
  ELSE '임계 10이 분포에서 벗어났다 — 상위 ' || round((1 - (SELECT pr FROM r)) * 100, 1)
       || '% (의도 5~30%). 벗어난 방향을 보고 게이트인지 상수인지 다시 판정한다'
  END"
```

**판정 범위를 숫자로 못박은 이유**는 「분포 안에 있다」가 판정이 아니기 때문이다. 범위를 안 정하면
값이 움직였을 때 다음 세션이 **전제 대신 펜스를 고치는 쪽**으로 간다.

## 배경

**이 결정의 첫째 근거는 우리 자신의 기록이 틀렸다는 것이다.** `PresetCatalog`의 프리셋 설명 둘이
*"감가상각비·총차입금·자본적지출을 소스가 주지 않아 빠졌으며"* 라고 적고 있었다. 앞의 둘은
§7(각 1/4)과 §12(감가상각비 실질 2/8, 총차입금은 원리적으로 합성)가 **재서** 닫았지만
**자본적지출은 어느 표에도 없었다.** 2026-08-18에 §12와 같은 8종목·8콜로 재보니
`ifrs-full_PurchaseOfPropertyPlantAndEquipmentClassifiedAsInvestingActivities`가 **8/8**로 왔고
계정명도 여덟이 전부 `유형자산의 취득`으로 같다. **부호는 전부 양수다.** 유·무형을 한 줄로 합쳐
내는 회사는 0/8이라 합산 규칙이 회사마다 갈릴 위험도 없다. 실측 전문은 `dart-financial.md`의 「계정별 가용성」 절이다.

**그 탐침이 스스로 오진을 하나 냈고 그것도 §13에 적었다.** 무형자산 잔액을 8/8로 셌는데 표준코드
`IntangibleAssetsOtherThanGoodwill`(영업권 제외)과 `IntangibleAssetsAndGoodwill`(영업권 포함)을
**한 칸에 묶어** 세었기 때문이다. §12의 감가상각비 오진(계정명 부분일치)과 **원인이 다르다** —
표준코드를 쓴다고 오진이 사라지지 않는다. 3.6(유형자본 대비 수익률)은 그래서 이번에 열지 않는다.

**표본 8종목이 부호에서 일반화되지 않았다 — 이 결정의 둘째 교훈이다.** 재적재 전이라면 이
문서는 *"부호는 여덟 모두 양수다"* 라고 적을 수 있었고 그것은 틀렸을 것이다. 350종목에서
**22행(1.5%)이 음수**이고 LG화학 FY2023의 −12.96조는 그 해 실제 설비투자 규모와 맞는다.
**표본이 답할 수 있는 것은 존재 여부이고 분포는 전수가 답한다** — §12가 *"8종목은 「닫혔다」는
결론만 낼 수 있다"* 라고 적은 원칙이 반대 방향으로도 성립한다는 뜻이다.

**§10은 린치 §8과 달리 방향이 맞았다**(2026-08-13 기준일, 활성 347종목).

| §10이 적은 것 | 실측 |
|---|---|
| `ROE`를 15 → **10으로 푼다** — "ROE 15% 지속 기업이 극소수" | `ROE` 중앙값 **7.92**, p75 **14.33**. 15 이상이 79종목(23%)이라 방향이 맞다 |
| `Debt to Equity`를 0.5 → **1.0으로 푼다** | 부채비율 중앙값 **77.87**. 50 이하가 124(36%), 100 이하가 196(57%) |

**그래도 §10을 인용해서 정하지 않고 잰 값으로 정했다.** 방향이 맞은 것과 값이 맞은 것은 다르고,
§10의 사유가 서술뿐이라 「몇 %가 통과하는가」에 답하지 못하는 것은 린치 문서와 같다.

**필수 게이트를 단일 시점 ROE가 아니라 3년 연속 ROE에 둔 근거는 문서 자신이다.** §11이
*"가장 중요한 건 3.1의 ROE 시계열 확인 — TradingView 필터는 TTM 단일 시점만 보므로 일회성 이익으로
ROE가 튄 해에 걸린 종목이 반드시 섞여 들어온다"* 라고 적는다. 우리는 그 확인을 지표로 만들 수
있으므로, 이식본이 사람에게 미룬 것을 게이트로 옮긴다. §8이 *"이 둘을 분리하는 순간 버핏 기준이
아니게 된다"* 라고 한 것은 **둘을 함께 두라는 요구**이지 필수/점수의 구분이 아니다.

**게이트와 점수의 배치는 ADR-0068이 정한다.** 이 결정이 정한 배치(가격을 배점 28 중 4에 둔 것)는
통과 종목이 시장보다 비싸지는 결과를 낳았고, ADR-0068이 그것을 뒤집었다. **여기에는 배치를 적지
않는다** — 두 곳에 적으면 갈린다.

**유보 테스트가 4년인 이유는 거래일 좌표다.** 워밍업 1,261봉 전이 **2021-06-21**인데 FY2020
기말은 **1,377봉** 전이다. 5년으로 두면 분자(수정주가)는 2021-06부터, 분모(주당 이익잉여금)는
FY2020 기말부터 재어 **기준점이 7개월 어긋난다.** FY2021 기말은 **1,129봉**이라 창 안이고 결산월이
12월이 아닌 종목까지 여유가 있다. **앵커를 회계연도 기말에 맞추는 것이 연수보다 중요하다** —
어긋난 5년보다 정직한 4년이 낫다. 덧붙여 이익잉여금은 지금 **FY2021부터만** 있다.

**`ROIC_PRETAX`의 분모가 금융업에 원리적으로 없다.** 최근 분기 `current_liabilities`가 NULL인
33종목이 KSIC 64(13)·65(9)·66(5)·업종미상(6)이다 — K-IFRS 금융업은 유동/비유동을 가르지 않는다.
`AltmanZCalculator`가 같은 자리에서 `{65,66}`을 제외하는 선례가 있다(ADR-0044).

**프리셋을 폐기하지 않는다.** 개수 상한은 코드·DB·화면 어디에도 없다(ADR-0066). 축 중복도 낮다 —
`VALUE`은 백분위 밸류에이션이, `LOWVOL_QUALITY`은 변동성이 1차 축이고, **다년 ROE 일관성과
저부채를 짝지어 필수로 거는 프리셋이 없다.** `QVG_KR` 때와 달리 공유하는 축이 점수 조건뿐이다.

## 버린 대안

- **워밍업을 1,377로 올려 유보 테스트를 원문대로 5년으로 하기** — 원문에 충실해지지만 재귀 지표
  넷의 스냅숏 대조가 다시 붙고(P12·P15가 밟은 절차), 결산월이 12월이 아닌 종목은 FY−5 기말이 더
  이르러 창이 또 모자란다. **얻는 것이 한 해뿐이라 대가가 더 컸다.** `price_daily`가 2020-01-02부터
  1,624거래일이라 물리적 상한도 멀지 않다.
- **`RETAINED_TO_MARKET`을 시가총액 증분으로 재기** — 주당이 아니라 총액으로 재면 **증자한 회사가
  시총이 늘었다는 이유로 점수를 얻는다.** AAII 구현도 주가 기준이다.
- **법인세비용을 함께 적재해 세후 `ROIC`를 내기** — §12가 8/8로 확인했으므로 기술적으로 열린다.
  이번에 하지 않은 것은 **컬럼 하나가 여섯 자리를 함께 움직이고**(ADR-0062) 세후로 바뀌어도 순위가
  크게 달라지지 않기 때문이다. 세전이라는 것이 이름에 있으므로 나중에 승격해도 거짓이 남지 않는다.
- **버핏 프리셋을 둘로 나누기(질 / 안전마진)** — 문서 조사 노트 §2가 *"가격은 항상 마지막에 온다"*
  는 위계를 못박는다. 질과 가격을 두 프리셋으로 가르면 **그 위계가 사라지고** 밸류에이션만 걸린
  쪽을 버핏이라 부르게 된다. 문서 §8도 통합 스크린 하나를 준다.
- **`IS_FINANCIAL` 0/1 지표로 §7·§10의 섹터 제외를 열기** — ADR-0066이 이미 버렸다. KSIC 중분류
  64가 금융업과 지주회사를 겸해 코드로 가를 수 없고, 반쪽이면 *"제외했다"는 문구만 남고 지주회사가
  통과한다.* **이 프리셋에서는 그 대가가 더 크다** — §10이 지주회사를 "가장 오염이 심한 구간"으로
  지목하는데 필수 게이트가 ROE 계열이기 때문이다. 그래서 `description`에 적는다.
- **`FCF_CAGR_5Y`와 `PFCF`를 함께 만들기** — `PFCF`는 `FCF_YIELD`의 역수라 같은 값을 두 이름으로
  부르는 것이고(`P/FCF ≤ 20`과 `FCF Yield ≥ 5`는 같은 조건이다), `FCF_CAGR_5Y`는 기저가 음수일 때
  값을 내지 않는 처리가 붙어 커버리지가 더 떨어진다.

## 결과

문서 §11이 「수동 조사 항목」으로 분류한 것 중 **넷이 자동화된다** — 3.1(ROE 시계열)·0.2/1.2(연속
흑자)·3.4(마진 일관성)·3.5(1달러 유보 원칙). 5.5(순이익 vs 영업현금흐름)는 `ACCRUAL_RATIO`가 이미
덮고 있었다. **버핏 4층(안전마진)이 P/FCF 축으로 열린다** — 자본적지출이 열린 결과다.

**닫힌 채로 남는 것**은 4.4(Buffettology 지속성장 모형, 배당 부재)·3.2의 이자보상배율과
순부채/EBITDA(이자비용·감가상각비)·3.6(유형자본, 무형자산과 영업권을 갈라 재기 전까지)·섹터
제외·정성 항목 전부다.

되돌리려면 `PresetCatalog`에서 프리셋을 빼고 계산기 여덟을 지운다. **`capex` 컬럼은 되돌리지
않는다** — 원자료이고, 지우면 §13의 측정을 다시 태워야 한다. 창은 건드리지 않았으므로 재귀 지표
대조도 필요 없다.
