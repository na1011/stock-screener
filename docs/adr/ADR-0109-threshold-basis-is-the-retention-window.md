---
id: ADR-0109
status: 채택됨
date: 2026-09-01
applies_to:
  - "src/main/java/com/stockscreener/screening/preset/PresetCatalog.java"
---

# ADR-0109. 임계의 측정 기준을 단면에서 보존 창 전체로 옮기고, 출처 미상 다섯을 채운다

## 결정

**시장별 임계를 정할 때 쓰는 분포를 「기준일 하루의 단면」에서 「보존 창 180거래일 전체」로
바꾼다.** 그 기준으로 둘을 한다.

1. **출처가 코드에 없어 안 옮겼던 무차원 임계 다섯**에 통화 축을 단다.
2. **ADR-0106이 단면으로 넣은 USD 값**을 창 기준 값으로 갈아끼운다.

**KRW 값은 한 자리도 바꾸지 않는다** — 국내 결과가 안 바뀐다는 보증이 여기서 나온다.

## 승인의 출처 — 사용자가 고른 문장과 내 해석을 갈라 적는다

**MUST: 이 절을 지우지 않는다.** 붙여 적으면 다음 세션이 내 해석을 사용자 결정으로 읽는다.

**사용자가 적은 문장(2026-09-01, 그대로):**
> *"누누히 얘기하지만, 낡은 ADR 때문에 현재의 최선의 판단이 방해받아선 안 됨. 너와 반증
> 에이전트 둘 다 같은 방향으로 판단했고 현 시점에서 그게 옳다면 과거의 ADR이 잘못 판정된
> 것임."*

**내 해석:** 올린 선택지 셋 중 **「창 기준으로 통일 — ADR-0106의 열도 다시 넣는다」**를
고른 것으로 읽었다. 근거는 *"과거의 ADR이 잘못 판정된 것"*이 셋 중 그것에만 해당한다는 것이다.
**사용자가 값을 하나씩 승인한 것은 아니다** — 기준을 고른 것이고, 값은 그 기준의 결과다.

**앞선 승인 하나는 무효였고 다시 받았다.** 첫 질문에서 나는 선택지 설명에
*"나머지 넷은 단면과 창이 같다"*고 적었는데 **거짓이었다** — `PCT_FROM_52W_HIGH`가 국내
통과율 단면 6.7% 대 창 18.6%로 갈리고 USD 값이 −2.79 대 −4.58이 된다. 사용자는 그 거짓
설명 위에서 한 번 골랐고, 정정해 다시 물어 위 문장을 받았다. **틀린 설명으로 받은 승인은
승인이 아니다**(CLAUDE.md).

## 규칙

- **MUST: 임계를 정할 때 보존 창 전체를 쓴다.** 단면 하나는 그날의 국면을 탄다 —
  실측으로 `CMF_21`의 국내 통과율이 창 평균 63.9%인데 2026-08-28 하루는 **95.1%**로
  창의 최댓값이고, 그 하루로 정하면 미국 임계의 **부호 방향까지 뒤집힌다**.
- **MUST: 그 창을 날짜로 적는다.** 보존 창은 매일 미끄러지고 **두 시장의 구간이 같지 않다** —
  국내 `2025-12-03 ~ 2026-08-28`, 미국 `2025-12-10 ~ 2026-08-28`(각 180거래일).
  날짜 없이 「보존 창」이라고만 적으면 다음 세션이 다시 잴 때 다른 수가 나온다(P27 축⑥).
- **MUST: (종목,일자) 관측을 한 표본으로 모아 분위를 잡는다.** 일자별 분위의 평균이 아니다 —
  둘은 종목 수가 날마다 다를 때 갈린다.
- **MUST: 유효숫자 3자리로 반올림한다.** 그 이상은 없는 정밀도다(ADR-0106에서 승계).
- **MUST NOT: 문헌 원문값 17개를 이 축으로 옮기지 않는다.** 그 값들은 원저가 **그 시장을 보고
  쓴 수**라 분포로 다시 재는 것이 이식이 아니라 새 조건을 들이는 것이다(ADR-0061).
- **MUST NOT: 사용자 화면(`CUSTOM`)의 임계를 대신 채우지 않는다.** 임계를 정하는 것은 사용자
  결정이다(ADR-0044·0061). §결과에 그 수를 세어 둔다.
- **MUST NOT: ADR-0106의 premise를 무차원까지 넓히지 않는다.** 그 술어는
  `value_type IN ('CURRENCY','PRICE')`인데 넓히면 **문헌 원문값 17개에 영구 오탐**이 난다.

## 검증

```bash
./gradlew test --tests "*MarketThresholdAxisTest*" --tests "*PresetCatalogTest*"
./gradlew backtestProbe
```

```premise 통화 축을 단 조건이 두 시장에서 비슷한 선택도를 갖는가 (지표 무리 1/3)
psql -d stock_screener -At -c "WITH cond AS ( SELECT s.code sc, c.id cid, c.indicator_code ind, c.operator op, c.threshold_currency base, c.literal_value bval FROM screen_condition c JOIN condition_group g ON g.id = c.group_id JOIN screen s ON s.id = g.screen_id WHERE s.is_active AND s.screen_type = 'PRESET' AND c.operand_type = 'LITERAL' AND c.threshold_currency IS NOT NULL AND c.literal_value_2 IS NULL), thr AS (SELECT cid, ind, op, base AS ccy, bval AS v FROM cond UNION ALL SELECT c.cid, c.ind, c.op, t.currency_code, t.literal_value FROM cond c JOIN screen_condition_threshold t ON t.condition_id = c.cid), inds AS (SELECT ind, ntile(3) OVER (ORDER BY ind) k FROM (SELECT DISTINCT ind FROM thr GROUP BY cid, ind HAVING count(DISTINCT ccy) > 1) z), mine AS (SELECT ind FROM inds WHERE k = 1), days AS (SELECT trade_date d, market_code mc FROM indicator_trade_date), mem AS MATERIALIZED (SELECT DISTINCT m.stock_id id, mk.currency_code ccy, dd.d FROM universe_membership m JOIN universe u ON u.code = m.universe_code AND u.is_active JOIN stock s ON s.id = m.stock_id JOIN market mk ON mk.code = s.market_code JOIN days dd ON dd.mc = s.market_code AND m.effective_from <= dd.d AND (m.effective_to IS NULL OR m.effective_to > dd.d)), vals AS MATERIALIZED ( SELECT iv.stock_id, iv.indicator_code ind, iv.value, iv.trade_date FROM indicator_value iv WHERE iv.trade_date IN (SELECT DISTINCT d FROM days) AND iv.indicator_code IN (SELECT ind FROM mine)), pop AS MATERIALIZED (SELECT m.ccy, v.ind, v.value FROM vals v JOIN mem m ON m.id = v.stock_id AND m.d = v.trade_date), rate AS (SELECT t.cid, t.ind, t.ccy, count(*) FILTER (WHERE CASE t.op WHEN 'GTE' THEN p.value >= t.v WHEN 'GT' THEN p.value > t.v WHEN 'LTE' THEN p.value <= t.v WHEN 'LT' THEN p.value < t.v END)::numeric / nullif(count(*), 0) AS pass FROM thr t JOIN pop p ON p.ccy = t.ccy AND p.ind = t.ind WHERE t.ind IN (SELECT ind FROM mine) GROUP BY 1, 2, 3), gap AS (SELECT cid, ind, max(pass) - min(pass) d FROM rate GROUP BY 1, 2 HAVING count(*) > 1) SELECT CASE WHEN (SELECT count(*) FROM thr) = 0 THEN '잴 것이 없었다 — 통화 축을 단 프리셋 조건이 하나도 없다' WHEN (SELECT count(*) FROM mine) = 0 THEN '잴 것이 없었다 — 이 무리에 든 통화 축 지표가 없다(두 통화 임계를 가진 지표가 셋 미만이면 무리가 빈다)' WHEN (SELECT count(*) FROM gap) = 0 THEN '잴 것이 없었다 — 두 통화의 임계를 함께 가진 조건이 없다(한 시장만 켜져 있다)' WHEN (SELECT max(d) FROM gap) <= 0.05 THEN 'OK 통화 축 ' || (SELECT count(*) FROM gap) || '조건(지표 ' || (SELECT string_agg(ind, ' ' ORDER BY ind) FROM mine) || ' · 그날 편입 · 보존 창 전 거래일)의 시장 간 통과율 차가 최대 ' || (SELECT round(max(d) * 100, 1) FROM gap) || '%p다' ELSE '통과율이 시장마다 갈린 조건이 있다: ' || (SELECT string_agg(ind || ' ' || round(d * 100, 1) || '%p', ', ' ORDER BY d DESC) FROM gap WHERE d > 0.05) || ' — 분위 보존으로 옮긴 값이 더는 같은 선택도를 내지 않는다. 분포가 움직였으므로 다시 잰다(백테스트로 고치지 않는다 — ADR-0071)' END"
```

```premise 통화 축을 단 조건이 두 시장에서 비슷한 선택도를 갖는가 (지표 무리 2/3)
psql -d stock_screener -At -c "WITH cond AS ( SELECT s.code sc, c.id cid, c.indicator_code ind, c.operator op, c.threshold_currency base, c.literal_value bval FROM screen_condition c JOIN condition_group g ON g.id = c.group_id JOIN screen s ON s.id = g.screen_id WHERE s.is_active AND s.screen_type = 'PRESET' AND c.operand_type = 'LITERAL' AND c.threshold_currency IS NOT NULL AND c.literal_value_2 IS NULL), thr AS (SELECT cid, ind, op, base AS ccy, bval AS v FROM cond UNION ALL SELECT c.cid, c.ind, c.op, t.currency_code, t.literal_value FROM cond c JOIN screen_condition_threshold t ON t.condition_id = c.cid), inds AS (SELECT ind, ntile(3) OVER (ORDER BY ind) k FROM (SELECT DISTINCT ind FROM thr GROUP BY cid, ind HAVING count(DISTINCT ccy) > 1) z), mine AS (SELECT ind FROM inds WHERE k = 2), days AS (SELECT trade_date d, market_code mc FROM indicator_trade_date), mem AS MATERIALIZED (SELECT DISTINCT m.stock_id id, mk.currency_code ccy, dd.d FROM universe_membership m JOIN universe u ON u.code = m.universe_code AND u.is_active JOIN stock s ON s.id = m.stock_id JOIN market mk ON mk.code = s.market_code JOIN days dd ON dd.mc = s.market_code AND m.effective_from <= dd.d AND (m.effective_to IS NULL OR m.effective_to > dd.d)), vals AS MATERIALIZED ( SELECT iv.stock_id, iv.indicator_code ind, iv.value, iv.trade_date FROM indicator_value iv WHERE iv.trade_date IN (SELECT DISTINCT d FROM days) AND iv.indicator_code IN (SELECT ind FROM mine)), pop AS MATERIALIZED (SELECT m.ccy, v.ind, v.value FROM vals v JOIN mem m ON m.id = v.stock_id AND m.d = v.trade_date), rate AS (SELECT t.cid, t.ind, t.ccy, count(*) FILTER (WHERE CASE t.op WHEN 'GTE' THEN p.value >= t.v WHEN 'GT' THEN p.value > t.v WHEN 'LTE' THEN p.value <= t.v WHEN 'LT' THEN p.value < t.v END)::numeric / nullif(count(*), 0) AS pass FROM thr t JOIN pop p ON p.ccy = t.ccy AND p.ind = t.ind WHERE t.ind IN (SELECT ind FROM mine) GROUP BY 1, 2, 3), gap AS (SELECT cid, ind, max(pass) - min(pass) d FROM rate GROUP BY 1, 2 HAVING count(*) > 1) SELECT CASE WHEN (SELECT count(*) FROM thr) = 0 THEN '잴 것이 없었다 — 통화 축을 단 프리셋 조건이 하나도 없다' WHEN (SELECT count(*) FROM mine) = 0 THEN '잴 것이 없었다 — 이 무리에 든 통화 축 지표가 없다(두 통화 임계를 가진 지표가 셋 미만이면 무리가 빈다)' WHEN (SELECT count(*) FROM gap) = 0 THEN '잴 것이 없었다 — 두 통화의 임계를 함께 가진 조건이 없다(한 시장만 켜져 있다)' WHEN (SELECT max(d) FROM gap) <= 0.05 THEN 'OK 통화 축 ' || (SELECT count(*) FROM gap) || '조건(지표 ' || (SELECT string_agg(ind, ' ' ORDER BY ind) FROM mine) || ' · 그날 편입 · 보존 창 전 거래일)의 시장 간 통과율 차가 최대 ' || (SELECT round(max(d) * 100, 1) FROM gap) || '%p다' ELSE '통과율이 시장마다 갈린 조건이 있다: ' || (SELECT string_agg(ind || ' ' || round(d * 100, 1) || '%p', ', ' ORDER BY d DESC) FROM gap WHERE d > 0.05) || ' — 분위 보존으로 옮긴 값이 더는 같은 선택도를 내지 않는다. 분포가 움직였으므로 다시 잰다(백테스트로 고치지 않는다 — ADR-0071)' END"
```

```premise 통화 축을 단 조건이 두 시장에서 비슷한 선택도를 갖는가 (지표 무리 3/3)
psql -d stock_screener -At -c "WITH cond AS ( SELECT s.code sc, c.id cid, c.indicator_code ind, c.operator op, c.threshold_currency base, c.literal_value bval FROM screen_condition c JOIN condition_group g ON g.id = c.group_id JOIN screen s ON s.id = g.screen_id WHERE s.is_active AND s.screen_type = 'PRESET' AND c.operand_type = 'LITERAL' AND c.threshold_currency IS NOT NULL AND c.literal_value_2 IS NULL), thr AS (SELECT cid, ind, op, base AS ccy, bval AS v FROM cond UNION ALL SELECT c.cid, c.ind, c.op, t.currency_code, t.literal_value FROM cond c JOIN screen_condition_threshold t ON t.condition_id = c.cid), inds AS (SELECT ind, ntile(3) OVER (ORDER BY ind) k FROM (SELECT DISTINCT ind FROM thr GROUP BY cid, ind HAVING count(DISTINCT ccy) > 1) z), mine AS (SELECT ind FROM inds WHERE k = 3), days AS (SELECT trade_date d, market_code mc FROM indicator_trade_date), mem AS MATERIALIZED (SELECT DISTINCT m.stock_id id, mk.currency_code ccy, dd.d FROM universe_membership m JOIN universe u ON u.code = m.universe_code AND u.is_active JOIN stock s ON s.id = m.stock_id JOIN market mk ON mk.code = s.market_code JOIN days dd ON dd.mc = s.market_code AND m.effective_from <= dd.d AND (m.effective_to IS NULL OR m.effective_to > dd.d)), vals AS MATERIALIZED ( SELECT iv.stock_id, iv.indicator_code ind, iv.value, iv.trade_date FROM indicator_value iv WHERE iv.trade_date IN (SELECT DISTINCT d FROM days) AND iv.indicator_code IN (SELECT ind FROM mine)), pop AS MATERIALIZED (SELECT m.ccy, v.ind, v.value FROM vals v JOIN mem m ON m.id = v.stock_id AND m.d = v.trade_date), rate AS (SELECT t.cid, t.ind, t.ccy, count(*) FILTER (WHERE CASE t.op WHEN 'GTE' THEN p.value >= t.v WHEN 'GT' THEN p.value > t.v WHEN 'LTE' THEN p.value <= t.v WHEN 'LT' THEN p.value < t.v END)::numeric / nullif(count(*), 0) AS pass FROM thr t JOIN pop p ON p.ccy = t.ccy AND p.ind = t.ind WHERE t.ind IN (SELECT ind FROM mine) GROUP BY 1, 2, 3), gap AS (SELECT cid, ind, max(pass) - min(pass) d FROM rate GROUP BY 1, 2 HAVING count(*) > 1) SELECT CASE WHEN (SELECT count(*) FROM thr) = 0 THEN '잴 것이 없었다 — 통화 축을 단 프리셋 조건이 하나도 없다' WHEN (SELECT count(*) FROM mine) = 0 THEN '잴 것이 없었다 — 이 무리에 든 통화 축 지표가 없다(두 통화 임계를 가진 지표가 셋 미만이면 무리가 빈다)' WHEN (SELECT count(*) FROM gap) = 0 THEN '잴 것이 없었다 — 두 통화의 임계를 함께 가진 조건이 없다(한 시장만 켜져 있다)' WHEN (SELECT max(d) FROM gap) <= 0.05 THEN 'OK 통화 축 ' || (SELECT count(*) FROM gap) || '조건(지표 ' || (SELECT string_agg(ind, ' ' ORDER BY ind) FROM mine) || ' · 그날 편입 · 보존 창 전 거래일)의 시장 간 통과율 차가 최대 ' || (SELECT round(max(d) * 100, 1) FROM gap) || '%p다' ELSE '통과율이 시장마다 갈린 조건이 있다: ' || (SELECT string_agg(ind || ' ' || round(d * 100, 1) || '%p', ', ' ORDER BY d DESC) FROM gap WHERE d > 0.05) || ' — 분위 보존으로 옮긴 값이 더는 같은 선택도를 내지 않는다. 분포가 움직였으므로 다시 잰다(백테스트로 고치지 않는다 — ADR-0071)' END"
```

**2026-09-08에 이 전제가 처음 붉었다 — 회귀가 아니다.** 그날 두 일이 겹쳤다: ① 리밸런싱이
편입을 바꿨고(US 7 · KR 4 교체) ② 일일 갱신이 국내에만 09-07을 더했다(미국은 노동절 휴장).
**둘 중 무엇이 분포를 움직였는지는 못 갈랐다** — 편입은 되돌릴 수 없어 대조군을 못 만든다.
값은 `VOLUME_RATIO_20` 6.4%p · `CMF_21` 5.2%p이고 이 문서가 적은 기준선은 최대 2.9%p였다.
**처방은 이 펜스가 이미 적은 「다시 잰다」이고 백테스트로 고치지 않는다**(ADR-0071·0072) —
프리셋 임계를 움직이는 일이라 별도 결정이다.

**이 전제가 재는 것은 「값이 맞는가」가 아니라 「같은 뜻을 유지하는가」다.** 분위 보존의
조작적 정의가 *두 시장에서 같은 선택도*이므로, 그것이 5%p 넘게 갈리면 옮긴 값이 낡은 것이다.
**모집단을 활성 유니버스 편입 × 보존 창으로 잡은 것이 요점이다** — 단면으로 재면 이 전제
자체가 이 결정이 버린 기준을 쓰게 된다. **창 전 거래일을 그날 편입(as-of)으로 잰다**(2026-10-03). 옛 판은 「최신일부터 20거래일 간격」 표본이었는데
**그 표본 날짜가 매일 하루씩 밀려** 같은 창에서도 위상에 따라 `CMF_21`의 차가 **1.33~7.21%p**로 흩어졌다
(20위상 중 6개가 5%p를 넘는다). 그래서 단면으로 읽은 「5.0 → 5.4 → 7.2」는 위상 잡음이고, 그날 낸 발견
7.2%p도 그 산물이었다(전 창 4.0%p). **다만 전 창 값 자체도 오른다** — 247일 고정 창을 미끄러뜨리면
3.19 → 3.90%p(2026-09-22 → 10-01, 거래일당 약 0.09%p)라 문턱까지 여유가 1%p 남짓이다. 그 상승은
최근 국면의 일별 선택도 괴리이고, **이 결정의 처방은 다시 재는 것이지 백테스트로 고치는 것이 아니다**
(ADR-0071). 비용 때문에 **조건별로 나눈다** — 판정(조건마다 통화 간 차)이 조건끼리 독립이라 정확하고,
두 통화 임계를 가진 지표를 이름순 `ntile(3)`으로 셋으로 가른다(조각 2.5~2.8초 · 나누지 않으면 6.95초).
세 조각의 조건 수 합(19)과 조건별 차가 나누지 않은 판과 같다. **`BETWEEN`을 뺀 이유**는 상·하한 둘을 가진 조건에서
「통과율」이 한 축으로 정의되지 않기 때문이고, 지금 그런 통화 축 조건은 없다.
**이 모집단이 자라는 자리**: 세 번째 통화가 붙으면 `max - min`이 세 시장의 폭이 되므로,
그때 이 잣대(5%p)가 여전히 맞는지 먼저 본다.

## 배경

ADR-0106이 임계에 통화 축을 달면서 **열 개를 2026-08-28 단면 분위로 옮겼고**, 출처가 코드에
없는 무차원 임계는 *"다시 잴 근거가 없어"* 그대로 뒀다. 그 두 문장이 이번에 다 뒤집혔다.

**「다시 잴 근거가 없다」가 거짓이다.** 출처를 모른다는 것은 **국내 값의 유래**를 모른다는
뜻이지, 그 값이 국내 분포에서 앉은 자리를 못 잰다는 뜻이 아니다. 다섯 다 국내 통과율이
나오고, 미국에서 같은 통과율을 내는 값도 나온다.

**단면 기준이 가격 유래 임계에서 흔들린다.** 창 전체로 다시 재니 재무 유래 넷은 사실상 같은데
(`DEBT_RATIO` 183.2 · `ROIC_MIN_3Y` 15.8) **가격·시장 유래 여섯이 7~21% 움직인다.** 재무는
분기마다 계단으로 바뀌어 단면과 창이 다르지 않고, 가격은 매일 움직이기 때문이다.

### 옮긴 것 전부 (2026-09-01 · 180거래일 창 · 유효숫자 3자리)

**아래 두 표의 USD 값은 2026-09-09에 갈렸다**(ADR-0134) — 창이 255거래일이 되어 같은 방법으로
다시 쟀다. **§규칙과 §검증의 premise는 그대로 이 문서가 소유한다** — 바뀐 것은 값뿐이다.

**안 옮겼던 다섯 — 전부 배점이고 필수 게이트는 없다.**

| 프리셋 | 조건 | KRW | **USD** | 국내 통과 | USD를 안 넣으면 |
|---|---|---|---|---|---|
| `BUFFETT_QUALITY` | `OPM_MIN_5Y >=` | 8 | **17.8** | 20.3% | 47.6% |
| `LYNCH_FAST_GROWER` | `OPERATING_MARGIN >` | 10 | **16.3** | 41.8% | 61.0% |
| `VALUE` | `DEBT_RATIO <=` | 150 | **276** | 70.9% | 48.2% |
| `STAGE2_BREAKOUT` | `PCT_FROM_52W_HIGH >=` | -10 | **-4.58** | 18.6% | 37.5% |
| `PULLBACK_UPTREND` | `CMF_21 >=` | -0.10 | **-0.0503** | 63.9% | 75.6% |

**기준을 갈아끼운 열 — 갈리는 것은 가격·시장 유래 여섯뿐이다.**

| 조건 | KRW | 단면 USD | **창 USD** | 차 |
|---|---|---|---|---|
| `MARKET_CAP >=` (버핏) | 1.000조 | 7.94B | **6.50B** | −18% |
| `MARKET_CAP <=` (린치 고성장) | 2.616조 | 17.3B | **15.8B** | −9% |
| `MARKET_CAP >=` (린치 대형우량) | 6.773조 | 46.5B | **41.8B** | −10% |
| `BETA_104W <=` | 0.40 | 0.55 | **0.436** | −21% |
| `ATR_PCT_14 <=` | 5.9 | 2.79 | **3.09** | +11% |
| `VOLUME_RATIO_20 >=` | 1.5 | 1.48 | **1.38** | −7% |
| `ROE_MIN_3Y >=` | 10 | 22.4 | **22.6** | +1% |
| `ROIC_MIN_3Y >=` · `DEBT_RATIO <=` 100·80 | — | 15.8 / 183 / 152 | **같다** | 0 |

## 버린 대안

- **단면 기준을 유지하고 다섯만 채운다** — 저장소가 하나의 기준을 갖는다는 장점이 있으나,
  그 기준이 **가격 유래 임계에서 그날의 국면을 탄다**는 것이 실측으로 드러났다.
  사용자가 *"낡은 ADR 때문에 현재의 최선의 판단이 방해받아선 안 됨"*으로 기각했다.
- **다섯을 그대로 두고 결핍을 프리셋 설명에 적는다**(ADR-0039의 형태) — 배점의 비대칭이
  그대로 남아 P27 축③이 계속 붉는다. 사용자에게 선택지로 올렸고 안 골랐다.
- **다섯을 KRW 전용으로 못박아 미국 실행을 거절한다** — 다섯이 서로 다른 프리셋에 하나씩
  박혀 있어 **활성 여덟 중 다섯이 미국에서 안 돈다.** ADR-0107이 *"미국을 버리는 것"*으로
  기각한 형태에 가깝다.
- **일자별 분위의 평균을 쓴다** — 종목 수가 날마다 달라(신규 편입·결손) 가중이 암묵적으로
  들어간다. (종목,일자)를 한 표본으로 모으는 쪽이 정의가 하나다.

## 결과

**쉬워지는 것** — `marketLiteral` javadoc의 *"여기 없는 무차원 임계는 문헌 원문값이라 그대로
둔 것"*이 **참이 됐다**(그전에는 출처 미상 다섯이 섞여 거짓이었다). 통화 축을 단 프리셋 조건이
11 → **16**이다.

**어려워지는 것**

- **미국 통과 집합이 움직인다**(실측, 2026-08-28 필수 게이트 기준):
  `BUFFETT_QUALITY` 41 → **43**(신규 2·이탈 0) · `LYNCH_FAST_GROWER` 57 → **51**(신규 0·이탈 6) ·
  `LYNCH_STALWART` 39 → **46**(신규 7·이탈 0). **국내는 한 종목도 안 바뀐다.**
- **프리셋과 사용자 화면 사본의 간격이 넓어진다.** 활성 `CUSTOM` 둘이 프리셋을 복사한 채 남아
  `ROE_MIN_3Y 10` · `DEBT_RATIO 100·50` · `ROIC_MIN_3Y 10` · `OPM_MIN_5Y 8` · `CMF_21 -0.10`을
  **통화 축 없이** 들고 있다. **지금은 안 샌다** — 둘 다 `MARKET_CAP`·`TURNOVER_60`이 KRW 축만
  들어 미국 실행이 통째로 거절되기 때문이다(ADR-0107 §결과). **그 거절이 풀리는 날 다섯 개
  원화 사본이 조용히 미국 종목을 판정하기 시작한다.**
- **저장소에 기준이 하나로 통일된 대신, 그 기준이 미끄러진다.** 보존 창은 매일 움직이므로
  1년 뒤 같은 방법으로 재면 다른 수가 나온다. 그것을 회귀로 읽지 않도록 §검증의 premise가
  「같은 값인가」가 아니라 **「같은 선택도인가」**를 잰다.

**되돌리려면** — `PresetCatalog`의 16개 `…In(...)` 호출에서 USD 인자를 되돌린다.
**지금 그 자리의 값은 ADR-0134의 것이다.** 다섯은
`weighted`·`literal`로 되돌리면 축이 사라지고, 시더가 기동 때 자식 표를 지운다.
