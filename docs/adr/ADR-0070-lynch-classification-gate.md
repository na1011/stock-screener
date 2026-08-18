---
id: ADR-0070
status: 채택됨
date: 2026-08-18
applies_to:
  - "src/main/java/**/screening/preset/PresetCatalog.java"
  - "src/main/java/**/indicator/calculator/InventoryGrowthGapCalculator.java"
  - "docs/external/피터린치-방법론.md"
---

# ADR-0070. 린치에서 문에 서는 것은 분류다 — 배치를 원저에서 다시 유도한다

## 결정

`LYNCH_FAST_GROWER_KR`·`LYNCH_STALWART_KR`의 **게이트와 배점 배치**를 다시 정한다. ADR-0066이
정한 배치를 대체한다.

- **유형 분류를 필수 게이트로 올린다.** 축은 `EPS_CAGR_5Y`다 — 고속성장주 `20~50`, 대형우량주
  `10~20`. 통과가 32 → **7종목**, 44 → **9종목**이 된다.
- **가격(`PEG`)을 게이트에서 배점으로 내린다.** `PEG_PCTL`은 `PEG`와 ρ 0.996이라 뺀다.
- **대형우량주에 질 게이트 `OPM_MIN_5Y > 0`을 세우고 `TURNOVER_60`을 뺀다** — 후자는 대형 87종목
  중 **0종목**을 자른다.
- **`INVENTORY_GROWTH_GAP`을 새로 만들어** 린치 3.14를 두 프리셋의 배점에 넣고, 이미 있던
  `FCF_MARGIN`도 배점에 넣는다.

**외부 문서(`docs/external/피터린치-방법론.md`)는 조건의 출처일 뿐이며 조항별로 재판정했다**
(ADR-0066 승계). 분류축을 5년 CAGR로 둔 것, 재고 지표를 만든 것, `PEG`를 내린 것은 **2026-08-18
사용자 결정**이다.

## 규칙

- **MUST: 유형을 정하는 조건을 필수 게이트에 둔다.** 린치는 관문의 연쇄가 아니라 **분류기**다 —
  6유형을 먼저 나누고 유형마다 다른 잣대를 댄다. 분류가 배점에만 있으면 거르는 힘이 없어
  **그 이름이 가리키지 않는 유형이 통과 집합의 다수가 된다.**
- **MUST: 원문의 밴드를 다른 축으로 옮겼으면 그 칸을 따로 만든다.** §2.2·§2.3의 밴드는 **둘 다
  `EPS Diluted Growth (TTM YoY)` 축**이고, 5Y CAGR을 쓰는 조항은 **§3.17 `0~50`(상한만)** 하나다.
  「원문 그대로」로 적으면 거짓이다 — 축을 옮기면 모집단이 달라진다(대형 87 중 TTM 10~20은 6,
  CAGR 10~20은 11이고 **겹치는 종목이 다르다**).
- **MUST: 원문 조건을 게이트로 못 쓰면 그 통과 수를 숫자로 남긴다.** §2.2의 질 조항은
  `ROE > 12`와 `D/E 0~0.6`인데 대형+CAGR 11종목에 걸면 **각각 1종목**이다. 그래서
  `OPM_MIN_5Y > 0`으로 대신하고 **「원문에 없는데 더한 것」으로 분류한다** — 대체가 원문처럼
  읽히면 안 된다. **부호 조건이라 변별력이 낮다는 것도 함께 적는다.**
- **MUST NOT: 「완화 순서」를 §8이라고 적지 않는다.** 린치 문서에서 그것은 **§7 끝(318행)**이고
  *"0종목일 경우"*의 지침이다. **§8은 「한국 시장 조정값」 표이고 살아 있는 근거다** — 현행
  `DEBT_RATIO ≤ 80`의 유일한 출처가 거기 있고 지주회사 제외도 거기 있다. ADR-0068이 같은 문장을
  버핏 문서 기준으로 적었고(거기서는 실제로 §8이다) **그것을 그대로 옮기면 다음 세션에게 살아
  있는 근거를 무시하라고 지시하게 된다.**
- **MUST: 게이트의 한계 기여를 신고한다** — 「그것만 뺐을 때의 통과 수」다. **누적 교집합은 얹는
  순서에 따라 값이 달라져 재현되지 않고**, ADR-0066의 90% 펜스처럼 유니버스를 분모로 잡으면
  **다른 게이트를 통과한 뒤에는 아무도 자르지 않는 조건**을 구조적으로 못 잡는다. 실제로
  `LYNCH_STALWART_KR.TURNOVER_60`이 유니버스 기준 71%라 그 펜스를 통과하면서 **44 → 44**였다.
- **MUST: 상관계수의 종류를 명시한다.** 이 문서의 ρ은 전부 **순위상관(Spearman)**이고 결측 쌍은
  제외했다. 같은 쌍이 Pearson에서 다른 값을 낸다 — `PEG ~ PEG_PCTL`이 Spearman 0.996인데 Pearson
  0.529다. 계수를 안 적으면 다음 세션이 다른 잣대로 재고 정반대 결론을 낸다.
- **MUST: 점수 조건 사이의 상관을 재고 ρ ≥ 0.8이면 뺀다**(ADR-0068 승계). **백분위는 원본의
  순위변환이라 같은 축이다.**
- **MUST NOT: 성패 기준을 밸류에이션 쪽에 두지 않는다**(ADR-0068 승계).
- **MUST NOT: 매출총이익률을 대형우량주의 성패 기준으로 쓰지 않는다.** 버핏의 해자 잣대는 여기에
  옮겨지지 않는다 — 한국 대형주 중 5년 CAGR 10~20%인 것은 정유·상사·통신·지주라 매출총이익률이
  구조적으로 낮다(대형+CAGR 11종목 중앙값 **12.7**, n=9, 유니버스 22.2). **그래서 감사 결과에서도
  그 잣대를 뺐다** — 유죄 증거로 쓴 잣대를 제안이 지자 기준에서만 내리면 골대를 옮긴 것이다.
- **MUST: 표본이 작으면 중앙값·n·기준을 넘는 종목 수를 함께 낸다.** n=7의 중앙값도 **관측치
  하나**다.
- **MUST NOT: 0건 예외를 부여하지 않는다**(ADR-0044). 후계 프리셋에 승계되지 않는다.
- **MUST NOT: `PercentileConfig`의 `PEG` 백분위 선언을 지우지 않는다.** 조건에서 뺐을 뿐 조건
  빌더에서 고를 수 있다 — 죽은 지표가 아니다.

## 검증

```bash
rg -n 'PegCalculator\.CODE, ComparisonOperator' \
   src/main/java/com/stockscreener/screening/preset/PresetCatalog.java
```
린치 두 블록에서 `required(`로 감싸인 것이 없어야 한다 — 가격은 저울이다.

```premise 린치 게이트에 자르는 것이 0인 조건이 없다
psql -d stock_screener -At -c "WITH d AS (SELECT max(trade_date) t FROM indicator_value WHERE indicator_code = 'ADJ_CLOSE'),
uni AS (SELECT DISTINCT stock_id FROM indicator_value iv, d
        WHERE iv.trade_date = d.t AND iv.indicator_code = 'ADJ_CLOSE'),
g AS (SELECT s.code sc, c.id cid, c.indicator_code ind, c.operator op,
             c.literal_value v1, c.literal_value_2 v2, c.operand_type ot
      FROM screen_condition c
      JOIN condition_group cg ON cg.id = c.group_id
      JOIN screen s ON s.id = cg.screen_id
      WHERE s.code LIKE 'LYNCH~_%' ESCAPE '~' AND s.is_active AND c.is_required),
p AS (SELECT g.sc, g.cid, u.stock_id,
        COALESCE(CASE g.op
          WHEN 'LTE' THEN iv.value <= g.v1  WHEN 'LT' THEN iv.value < g.v1
          WHEN 'GTE' THEN iv.value >= g.v1  WHEN 'GT' THEN iv.value > g.v1
          WHEN 'BETWEEN' THEN iv.value >= g.v1 AND iv.value <= g.v2 END, false) pass
      FROM g CROSS JOIN uni u
      LEFT JOIN indicator_value iv ON iv.stock_id = u.stock_id
        AND iv.indicator_code = g.ind AND iv.trade_date = (SELECT t FROM d)),
agg AS (SELECT sc, stock_id, count(*) FILTER (WHERE NOT pass) nf,
               min(cid) FILTER (WHERE NOT pass) fcid FROM p GROUP BY 1, 2),
base AS (SELECT sc, count(*) FILTER (WHERE nf = 0) allpass FROM agg GROUP BY 1),
loo AS (SELECT g.sc, g.ind, b.allpass,
          b.allpass + (SELECT count(*) FROM agg a
                       WHERE a.sc = g.sc AND a.nf = 1 AND a.fcid = g.cid) lo
        FROM g JOIN base b ON b.sc = g.sc)
SELECT CASE
  WHEN (SELECT count(*) FROM uni) = 0
    THEN '잴 것이 없었다 — 최신 거래일에 ADJ_CLOSE가 하나도 없다'
  WHEN (SELECT count(*) FROM g) = 0
    THEN '잴 것이 없었다 — 린치 프리셋의 필수 게이트가 하나도 없다'
  WHEN (SELECT count(*) FROM g WHERE ot <> 'LITERAL') > 0
    THEN '판정할 수 없는 게이트가 있다(리터럴이 아니다): '
         || (SELECT string_agg(DISTINCT sc || '.' || ind, ', ') FROM g WHERE ot <> 'LITERAL')
  WHEN (SELECT count(*) FROM loo WHERE lo = allpass) = 0
    THEN 'OK 린치 게이트 ' || (SELECT count(*) FROM g) || '개가 전부 종목을 자른다 — '
         || (SELECT string_agg(sc || '.' || ind || ' ' || lo || '→' || allpass, ', ' ORDER BY sc, ind) FROM loo)
  ELSE '자르는 것이 0인 게이트가 있다(게이트가 아니라 상수다): '
       || (SELECT string_agg(sc || '.' || ind || ' ' || lo || '→' || allpass, ', ') FROM loo WHERE lo = allpass)
  END"
```

**이 펜스가 이번 결정의 성패다.** ADR-0066의 90% 펜스가 못 보는 자리이고, 지금 이 저장소에서
실제로 붉다(`LYNCH_STALWART_KR.TURNOVER_60 44→44`).

```premise 린치 고속성장주의 통과 집합이 유니버스보다 현금흐름이 좋다
psql -d stock_screener -At -c "WITH d AS (SELECT max(trade_date) t FROM indicator_value WHERE indicator_code = 'ADJ_CLOSE'),
uni AS (SELECT DISTINCT stock_id FROM indicator_value iv, d
        WHERE iv.trade_date = d.t AND iv.indicator_code = 'ADJ_CLOSE'),
g AS (SELECT c.id cid, c.indicator_code ind, c.operator op, c.literal_value v1, c.literal_value_2 v2
      FROM screen_condition c JOIN condition_group cg ON cg.id = c.group_id
      JOIN screen s ON s.id = cg.screen_id
      WHERE s.code = 'LYNCH_FAST_GROWER_KR' AND s.is_active AND c.is_required AND c.operand_type = 'LITERAL'),
p AS (SELECT g.cid, g.ind, u.stock_id,
        COALESCE(CASE g.op
          WHEN 'LTE' THEN iv.value <= g.v1 WHEN 'LT' THEN iv.value < g.v1
          WHEN 'GTE' THEN iv.value >= g.v1 WHEN 'GT' THEN iv.value > g.v1
          WHEN 'BETWEEN' THEN iv.value >= g.v1 AND iv.value <= g.v2 END, false) pass
      FROM g CROSS JOIN uni u
      LEFT JOIN indicator_value iv ON iv.stock_id = u.stock_id
        AND iv.indicator_code = g.ind AND iv.trade_date = (SELECT t FROM d)),
win AS (SELECT stock_id FROM p GROUP BY stock_id HAVING count(*) FILTER (WHERE NOT pass) = 0),
woc AS (SELECT stock_id FROM p WHERE ind <> 'EPS_CAGR_5Y' GROUP BY stock_id
        HAVING count(*) FILTER (WHERE NOT pass) = 0),
m AS (SELECT iv.stock_id, iv.value v FROM indicator_value iv, d
      WHERE iv.trade_date = d.t AND iv.indicator_code = 'FCF_MARGIN'),
umed AS (SELECT percentile_cont(0.5) WITHIN GROUP (ORDER BY v) x FROM m),
s AS (SELECT count(*) n, percentile_cont(0.5) WITHIN GROUP (ORDER BY v) med,
        count(*) FILTER (WHERE v > (SELECT x FROM umed)) better
      FROM m WHERE stock_id IN (SELECT stock_id FROM win)),
o AS (SELECT percentile_cont(0.5) WITHIN GROUP (ORDER BY v) med FROM m WHERE stock_id IN (SELECT stock_id FROM woc))
SELECT CASE
  WHEN (SELECT count(*) FROM g) = 0 THEN '잴 것이 없었다 — LYNCH_FAST_GROWER_KR의 리터럴 필수 게이트가 없다'
  WHEN (SELECT x FROM umed) IS NULL THEN '잴 것이 없었다 — FCF_MARGIN 값이 유니버스에 하나도 없다'
  WHEN (SELECT count(*) FROM win) = 0
    THEN '잴 것이 없었다 — 게이트를 통과한 종목이 없다(0건은 갈라서 보고할 일이다)'
  WHEN (SELECT n FROM s) = 0
    THEN '잴 것이 없었다 — 통과 종목에 FCF_MARGIN 값이 하나도 없다'
  WHEN (SELECT med FROM s) > (SELECT x FROM umed)
    THEN 'OK 중앙값 ' || round((SELECT med FROM s)::numeric,2) || ' (n=' || (SELECT n FROM s)
         || ', 유니버스 ' || round((SELECT x FROM umed)::numeric,2) || '보다 나은 종목 '
         || (SELECT better FROM s) || '개)'
         || ' / 분류축을 뺀 집합은 ' || coalesce(round((SELECT med FROM o)::numeric,2)::text,'없음')
  ELSE 'FCF_MARGIN 축이 무너졌다 — 통과 집합 중앙값 ' || round((SELECT med FROM s)::numeric,2)
       || ' (n=' || (SELECT n FROM s) || ')이 유니버스 ' || round((SELECT x FROM umed)::numeric,2)
       || '보다 낫지 않다'
  END"
```

**이 펜스는 이번 변경을 증명하지 않는다** — 현행 구조에서도 참이다(5.84 > 3.46). 「무너지지
않았는가」를 지키는 자리이며, 그 사실을 적어 두지 않으면 다음 세션이 이것을 성패로 읽는다.

```premise 린치 대형우량주의 이익이 현금으로 뒷받침된다
psql -d stock_screener -At -c "WITH d AS (SELECT max(trade_date) t FROM indicator_value WHERE indicator_code = 'ADJ_CLOSE'),
uni AS (SELECT DISTINCT stock_id FROM indicator_value iv, d
        WHERE iv.trade_date = d.t AND iv.indicator_code = 'ADJ_CLOSE'),
g AS (SELECT c.id cid, c.indicator_code ind, c.operator op, c.literal_value v1, c.literal_value_2 v2
      FROM screen_condition c JOIN condition_group cg ON cg.id = c.group_id
      JOIN screen s ON s.id = cg.screen_id
      WHERE s.code = 'LYNCH_STALWART_KR' AND s.is_active AND c.is_required AND c.operand_type = 'LITERAL'),
p AS (SELECT g.cid, g.ind, u.stock_id,
        COALESCE(CASE g.op
          WHEN 'LTE' THEN iv.value <= g.v1 WHEN 'LT' THEN iv.value < g.v1
          WHEN 'GTE' THEN iv.value >= g.v1 WHEN 'GT' THEN iv.value > g.v1
          WHEN 'BETWEEN' THEN iv.value >= g.v1 AND iv.value <= g.v2 END, false) pass
      FROM g CROSS JOIN uni u
      LEFT JOIN indicator_value iv ON iv.stock_id = u.stock_id
        AND iv.indicator_code = g.ind AND iv.trade_date = (SELECT t FROM d)),
win AS (SELECT stock_id FROM p GROUP BY stock_id HAVING count(*) FILTER (WHERE NOT pass) = 0),
woc AS (SELECT stock_id FROM p WHERE ind <> 'EPS_CAGR_5Y' GROUP BY stock_id
        HAVING count(*) FILTER (WHERE NOT pass) = 0),
m AS (SELECT iv.stock_id, iv.value v FROM indicator_value iv, d
      WHERE iv.trade_date = d.t AND iv.indicator_code = 'ACCRUAL_RATIO'),
umed AS (SELECT percentile_cont(0.5) WITHIN GROUP (ORDER BY v) x FROM m),
s AS (SELECT count(*) n, percentile_cont(0.5) WITHIN GROUP (ORDER BY v) med,
        count(*) FILTER (WHERE v < (SELECT x FROM umed)) better
      FROM m WHERE stock_id IN (SELECT stock_id FROM win)),
o AS (SELECT percentile_cont(0.5) WITHIN GROUP (ORDER BY v) med FROM m WHERE stock_id IN (SELECT stock_id FROM woc))
SELECT CASE
  WHEN (SELECT count(*) FROM g) = 0 THEN '잴 것이 없었다 — LYNCH_STALWART_KR의 리터럴 필수 게이트가 없다'
  WHEN (SELECT x FROM umed) IS NULL THEN '잴 것이 없었다 — ACCRUAL_RATIO 값이 유니버스에 하나도 없다'
  WHEN (SELECT count(*) FROM win) = 0
    THEN '잴 것이 없었다 — 게이트를 통과한 종목이 없다(0건은 갈라서 보고할 일이다)'
  WHEN (SELECT n FROM s) = 0
    THEN '잴 것이 없었다 — 통과 종목에 ACCRUAL_RATIO 값이 하나도 없다'
  WHEN (SELECT med FROM s) < (SELECT x FROM umed)
    THEN 'OK 중앙값 ' || round((SELECT med FROM s)::numeric,2) || ' (n=' || (SELECT n FROM s)
         || ', 유니버스 ' || round((SELECT x FROM umed)::numeric,2) || '보다 나은 종목 '
         || (SELECT better FROM s) || '개)'
         || ' / 분류축을 뺀 집합은 ' || coalesce(round((SELECT med FROM o)::numeric,2)::text,'없음')
  ELSE 'ACCRUAL_RATIO 축이 무너졌다 — 통과 집합 중앙값 ' || round((SELECT med FROM s)::numeric,2)
       || ' (n=' || (SELECT n FROM s) || ')이 유니버스 ' || round((SELECT x FROM umed)::numeric,2)
       || '보다 낫지 않다'
  END"
```

**출력의 「분류축을 뺀 집합」이 leave-one-out이다.** 이 기준을 통과시키는 것이 분류 게이트인지
질 게이트인지 가르기 위해 함께 낸다 — 실측으로 대형+`OPM_MIN_5Y`만으로 −3.12이고 분류축의
기여는 −0.07이다. **분류 게이트의 공을 질 게이트가 세웠다.**

셋 다 게이트를 `screen_condition`에서 **동적으로 읽는다.** 임계를 펜스에 박으면 임계가 바뀔 때
펜스가 조용히 낡는다. 실측 소요는 각 0.04초다.

## 배경

**ADR-0066의 배치가 만든 결과**(기준일 2026-08-13, 유니버스 347): 두 프리셋 모두 **필수 게이트에
분류축이 0개, 질이 0개**였다. 게이트는 가격(`PEG`)·규모·유동성·인기뿐이다.

| | 통과 | 그 이름이 가리키지 않는 것 |
|---|---|---|
| 고속성장주 | 32 | **21(66%)이 TTM 성장 >50%인 급반등주.** 자기 배점 밴드(10~30) 충족은 5/32. 상위는 RFHIC 1905% · 현대백화점 640%(매출성장 1%) · 씨젠 410%(5년 CAGR **−38.8**) — 린치가 **회생주·경기순환주로 따로 분류한** 유형이다 |
| 대형우량주 | 44 | **분류 정의(10~20%) 충족 6/44.** 부채비율 중앙값 **149.5**(유니버스 77.9), 상위 10종목이 전부 금융이고 부채비율 894~1435%. 금융이 **17/44(39%)** 로 유니버스 14%의 세 배 |

**5년으로 보면 「고속성장주」가 시장보다 느렸다** — `EPS_CAGR_5Y` 중앙값 8.6(n=20) vs 유니버스
12.3. 3.17의 「지속 불가능한 성장 배제」가 배점에 있어 거르는 힘이 없었다.

**분류축을 TTM이 아니라 5년 CAGR로 둔 근거는 실측이다.** TTM YoY는 사분위가 −16.7~79.4로 넓어
**밴드가 잡음보다 좁다** — §2.2의 `10~20`을 TTM으로 걸면 대형 87 중 6종목이 남고 거기에
`DEBT_RATIO ≤ 60`을 더하는 순간 **0**이 된다. 린치의 「연 20~25% 성장」은 다년 지속률이지 TTM이
아니다. ρ(TTM, 5yCAGR) = **0.229**로 둘은 사실상 다른 축이다.

**점수 조건이 서로 다른 것을 재지 않고 있었다**(순위상관, 최신 거래일):

| 짝 | ρ |
|---|---|
| `PEG` ~ `PEG_PCTL` | **0.996** ← 백분위는 순위변환이다. 뺀다 |
| `PEG` ~ `EPS_GROWTH_TTM_YOY` | −0.844 |
| `OPERATING_MARGIN` ~ `ROE` | 0.766 |
| `PER` ~ `PER_SECTOR_PCTL` | 0.747 |
| `DEBT_RATIO` ~ `DEBT_RATIO_SECTOR_REL` | 0.745 |
| `FCF_MARGIN` ~ `OPERATING_MARGIN` | 0.680 |
| `REVENUE_GROWTH` ~ `EPS_GROWTH_TTM_YOY` | 0.385 ← 성장 3축은 독립이라 셋 다 남긴다 |
| `EPS_CAGR_5Y` ~ `PEG` | **−0.010** ← 새 게이트축과 강등된 가격이 직교한다 |

**고속성장주 배점 총합은 20이었고** 밸류에이션이 게이트 1 + 배점 8(40%), 성장 셋이 6(30%)이었다.

**열려 있는데 안 쓰던 것이 둘이다.** `FCF_MARGIN`·`FCF_YIELD`가 P16에서 생겼는데(ADR-0067) 두
프리셋 다 조건이 0개였고, `description`은 *"그 자리는 발생액 비율이 지킨다"*고 적었다 —
**ρ(`ACCRUAL_RATIO`, `FCF_MARGIN`) = −0.184로 지키지 않는다.** 재고자산은 ADR-0069가 *"소비자가
없다"*며 원자료만 적재했는데 이 감사가 소비자를 만든다.

**`PBR`은 이번에 쓰지 않는다.** 커버리지가 346/347로 가장 높고 린치 §3.11·§8이 명시하지만, §8의
`0~0.8`은 *"시장 평균 PBR이 1 내외"*라는 **깨진 전제** 위에 있고(우리 유니버스 중앙값 **2.10**,
ADR-0066이 이미 판정), 성장주에 저PBR을 거는 것은 유형 분류와 반대로 작동한다.

## 버린 대안

- **TTM YoY를 분류축으로 두기** — 커버리지가 248/347로 높다. **해 보니 틀렸다**: 밴드가 잡음보다
  좁아 대형우량주가 6종목이 되고 질 게이트 하나에 0이 된다.
- **`DEBT_RATIO ≤ 100`을 대형우량주의 질 게이트로 두기** — 9가 아니라 **4종목**이 되고, 매출총
  이익률 중앙값의 표본이 **n=3**으로 줄어 성패를 잴 수 없다. 부채는 저울에 남긴다.
- **매출총이익률을 대형우량주의 성패 기준으로 두기** — **해 보니 틀렸다.** §규칙 참조.
- **`FCF_YIELD ≥ 5`를 쓰기**(린치 §3.13의 1차 조건) — 커버리지는 더 높지만(323) 밸류에이션 계열
  이라 성패 기준과 축이 겹친다. `FCF_MARGIN > 0`으로 약하게 옮기고 결손으로 적는다.

## 결과

**남는 결손과 노출**(전부 `description`이 아니라 여기에 남긴다 — 화면이 그리지 않는다):

- **`EPS_CAGR_5Y`를 문에 세운 대가.** 배제 사유가 셋이고 뭉뚱그리면 안 된다 — ① 상장 5년 미만
  ② **5년 전이 적자** ③ **미보정 기업행위**. ③은 `EpsCagr5yCalculator`가
  `priceAndFinancial(1261, 6)`이라 `UnadjustedActionGuard`의 **1,261봉 창**(저장소에서 가장 길다,
  ADR-0055)에 걸리는 것이고 **현재 6종목**이다. 지금까지는 배점을 못 받는 것으로 끝났으나 이제
  **두 프리셋에서 사라진다.** 원장에 `UNSUPPORTED`가 쌓일수록 조용히 커진다.
  **ADR-0066이 바로 이 이유로 이 지표를 필수에서 배점으로 내렸고 이 결정이 그것을 뒤집는다.**
- **부채는 고쳐지지 않는다.** 대형우량주 부채비율 중앙값이 149.5 → **117.6**으로 줄 뿐 유니버스
  77.9의 **1.5배**다. 9종목 중 §2.2 원문 `D/E ≤ 0.6`을 충족하는 것은 **하나**다.
- **지주회사를 가릴 수단이 없다.** §8이 `Sector 제외: 금융·부동산 + 지주회사`를 명시하는데 통과
  9종목 중 **GS·LS 둘이 KSIC 64992 지주회사**다. `universeCode`는 지수 단위이고 업종 코드 지표가
  없다. 금융 편중은 39% → 대형 기저율(26%) 수준으로 되돌아갈 뿐 사라지지 않는다.
- **통과 집합이 게이트 경계에 붙어 있다.** 고속성장주 7종목의 CAGR은 `20.1 · 20.5 · 29.9 · 32.3 ·
  41.2 · 46.0 · 48.1` — **둘이 하한 20에서 0.5 안**이다.
- **ADR-0066의 90% 펜스는 새 분류 게이트를 못 본다** — 필터에 `AND c.literal_value_2 IS NULL`이
  있어 **`BETWEEN` 조건이 모집단에서 빠진다.** 고속성장주는 4 중 3, 대형우량주는 3 중 2만
  검사된다. 위 세 번째 펜스가 그 자리를 덮는다.
- **재고 지표는 연간 전용이다.** `financial_statement.inventories`가 FY에만 있고(Q1~Q4 전부 NULL)
  FY2021 이전은 0행이라 **최근 2년만 성립한다.** 산출 가능 종목은 285/347(82%)이다.
