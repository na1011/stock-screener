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

**이 문서의 통과 수는 전부 2026-08-13 단면이다**(유니버스 347). **재현하려면 그 `trade_date`를
고정해야 한다** — `max(trade_date)`로 재면 다른 수가 나오고, 그 차이는 시장이 아니라 **재무
빈티지**가 만든다(§검증의 발생액 펜스 참조). `indicator_value`가 180거래일을 보관하므로 그
단면은 아직 DB에 있다.

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
o AS (SELECT percentile_cont(0.5) WITHIN GROUP (ORDER BY v) med FROM m WHERE stock_id IN (SELECT stock_id FROM woc)),
f AS (SELECT
        '중앙값 ' || round(s.med::numeric,2)
          || ' / n=' || s.n
          || ' / 유니버스 ' || round(u.x::numeric,2) || '보다 나은 종목 ' || s.better || '개('
          || round(100.0 * s.better / nullif(s.n,0), 1) || '%)'
          || ' / 분류축을 뺀 집합 중앙값 ' || coalesce(round(o.med::numeric,2)::text,'없음')
          || ' / 판정 기준 ' || CASE WHEN s.n < 20 THEN '비율(작은 표본)' ELSE '중앙값' END AS txt,
        CASE WHEN s.n < 20 THEN s.better::numeric / nullif(s.n,0) > 0.5
             ELSE s.med < u.x END AS ok
      FROM s, umed u, o)
SELECT CASE
  WHEN (SELECT count(*) FROM g) = 0 THEN '잴 것이 없었다 — LYNCH_STALWART_KR의 리터럴 필수 게이트가 없다'
  WHEN (SELECT x FROM umed) IS NULL THEN '잴 것이 없었다 — ACCRUAL_RATIO 값이 유니버스에 하나도 없다'
  WHEN (SELECT count(*) FROM win) = 0
    THEN '잴 것이 없었다 — 게이트를 통과한 종목이 없다(0건은 갈라서 보고할 일이다)'
  WHEN (SELECT n FROM s) = 0
    THEN '잴 것이 없었다 — 통과 종목에 ACCRUAL_RATIO 값이 하나도 없다'
  WHEN (SELECT ok FROM f) THEN 'OK ' || (SELECT txt FROM f)
  ELSE 'ACCRUAL_RATIO 축이 무너졌다 — ' || (SELECT txt FROM f)
       || '. 원인은 이 문서의 검증 절에 귀속돼 있다(재무 빈티지가 EPS_CAGR_5Y를 움직였다) — 다시 조사하지 마라. **배치를 고칠지는 아직 안 정해졌다** — 사용자 판단이 먼저이고 그 재료는 이 문서의 개정 절에 있다'
  END"
```

**출력의 「분류축을 뺀 집합」이 leave-one-out이다.** 이 기준을 통과시키는 것이 분류 게이트인지
질 게이트인지 가르기 위해 함께 낸다 — 대형+`OPM_MIN_5Y`만으로 **−3.12**다.

**그 위에 분류축을 얹은 기여의 부호가 하루 만에 뒤집혔고, 원인은 시장이 아니라 재무 빈티지다.**
2026-08-13에 **−0.07**이던 기여가 08-14에 **+1.60**이 됐다(통과 9 → 11, 중앙값 −3.19 → −1.52).
질 게이트가 고르는 54종목은 두 단면에 **집합까지 동일**하고(대칭차 0, 중앙값 −3.12 그대로),
분류축이 그중에서 고르는 쪽만 여덟 종목 뒤집혔다. **그 여덟을 뒤집은 것은 전부 `EPS_CAGR_5Y`**
이고, `MARKET_CAP` 경계를 넘은 종목도 `OPM_MIN_5Y`가 달라진 종목도 **0**이다. 두 날짜 사이에
있었던 것은 P17의 재무 적재이며(ADR-0071), **5년 연간 CAGR은 하루에 움직일 수 없다** — 두
날짜에 값이 다른 종목이 **160**이다.

**그래서 「분류 게이트의 공을 질 게이트가 세웠다」는 성립하지 않는다.** 지금 분류축은 발생액
품질을 **악화**시킨다. **다만 이 펜스로 그것을 단정하지 않는다** — 통과 n=11의 중앙값은 여섯
번째 한 종목이 정하는데, 위 §규칙이 *「표본이 작으면 중앙값·n·기준을 넘는 종목 수를 함께
낸다」*고 적어 놓고 **이 펜스는 중앙값만 본다.** 펜스의 잣대를 고치는 것과 배치를 고치는 것은
다른 결정이며, **후자는 사용자 승인이 먼저다**(ADR-0044·0061).

셋 다 게이트를 `screen_condition`에서 **동적으로 읽는다.** 임계를 펜스에 박으면 임계가 바뀔 때
펜스가 조용히 낡는다. 실측 소요는 각 0.04초다.

### 2026-08-20 — 펜스의 판정식만 고쳤다. **배치는 아직 안 정해졌다**

**고친 것은 펜스뿐이고 게이트·임계·배점은 한 칸도 안 건드렸다.** 표본이 작으면(`n < 20`)
중앙값 단독이 아니라 **「유니버스 중앙값보다 나은 종목의 비율」**로 판정한다. `n = 11`의
중앙값은 여섯 번째 한 종목이 정하지만 비율은 열한 종목이 모두 한 표씩 갖는다. 표본이 크면
종전대로 중앙값을 본다. 붉은 갈래에도 중앙값·n·비율 셋을 함께 싣는다 — 빠져 있던 것은 출력이
아니라 붉은 쪽의 출력이었다.

**잣대를 바꿔도 판정이 안 뒤집혔다** — 2026-08-18 단면 `n=11 / 나은 종목 5개 / 45.5%`로
기준선 50%에 못 미쳤다. **판정이 안 뒤집혔다는 것 자체가 이 변경이 골대 이동이 아니라는
증거다.** 바뀐 것은 붉음의 유무가 아니라 **붉은 이유의 해상도**다.

**MUST: 이 수를 날짜 없이 인용하지 않는다.** 위 머리말이 요구하는 `trade_date` 고정이 여기에도
걸린다 — 08-19 단면은 이미 `n=10 / 5개 / 50.0%`이고, 판정식이 `> 0.5`라 **한 종목이 뒤집히면
초록이 된다.** 「펜스가 붉다」는 상태이지 지켜야 할 규범이 아니다.

**MUST NOT: 붉은 것을 푸르게 하려고 기준선 0.5를 내리지 않는다.** 유니버스 중앙값보다 나은
비율의 기준선은 정의상 0.5다.

**MUST NOT: 「배치를 안 고치기로 했다」로 읽지 않는다.** 사용자가 고른 것은 **「검사 쪽을
고친다」 하나**이고, 그 선택지에 에이전트가 붙인 설명(*"고치면 붉은 줄이 진짜 신호일 때만
뜨게 된다"*)은 **거짓이었다** — 고쳐도 붉었다. **배치를 고칠지는 그 위에서 판단된 적이 없다.**
아래 §분류축 재검토가 그 재료다.


### 분류축 재검토 — 재료(2026-08-19 단면). **아직 판단되지 않았다**

**살아 있는 사실.** 분류축이 발생액 품질을 **악화**시킨다 — 유니버스 341 **−2.19** →
대형+`OPM_MIN_5Y>0` 53 **−3.13** → +`EPS_CAGR_5Y` 10~20 **10종목 −1.95**. 통과 셋(LS +1.12 ·
KT&G +3.66 · 삼성E&A +3.92)은 발생액이 **양수**다. 위 §검증이 *"지금 분류축은 발생액 품질을
악화시킨다"*고 이미 적었다.

**원문 축은 TTM이 맞다** — §규칙이 *"§2.2·§2.3의 밴드는 둘 다 `EPS Diluted Growth (TTM YoY)`
축"*이라고 적는다. 되돌리면 `대형+OPM+TTM 10~20` **4종목 −3.77**이다.

**되돌리기의 대가가 둘이고, 이것이 판단의 핵심이다.**

- **MUST: 되돌리면 이 펜스가 초록이 된다는 것을 먼저 신고한다.** 실측 `4종목 중 유니버스보다
  나은 것 3개 = 75%` → 판정식(`> 50%`)에서 **초록**. **즉 펜스가 재는 값을 최대화하도록 게이트
  축을 고르는 것**이고, `phase-criteria.md`의 *"성패 기준이 실패할 수 있는 형태여야 한다"*가
  금지한 형태의 대칭형이다. **초록이 되는 것은 성과가 아니라 골대 이동 신호다.**
- **n=4다.** 위 §버린 대안이 `DEBT_RATIO ≤ 100`을 버린 이유가 *"표본이 n=3으로 줄어 성패를 잴 수
  없다"*였다. 통과 넷은 현대글로비스 −4.12 · 한화에어로 −3.42 · 현대오토에버 −6.57 ·
  **LG −0.04**이고, **LG 하나가 뒤집히면 2/4 = 50%로 다시 붉어진다.**

**MUST NOT: 「고속성장주도 같은 병이다」로 넓히지 않는다.** 그 프리셋의 실제 필수 게이트
(`MARKET_CAP ≤ 2,616e9`·`TURNOVER_60 ≥ 5e9`·`RETURN_52W < 100`, 91종목)를 얹고 재면
`CAGR 20~50` **9종목 −4.43** vs `TTM 20~50` **9종목 −4.12**로 **CAGR 쪽이 낫다.** 밴드만 걸어
비교하면(41 vs 30) 반대로 보이는데, 그것은 **두 프리셋에 잣대를 다르게 댄 것**이다.

**아직 안 재본 넷째 안 — 밴드 재유도.** 국내 사분위가 `EPS_CAGR_5Y` **0.8~27.5**(중앙 12.0),
`EPS_GROWTH_TTM_YOY` **−4.2~118.0**(중앙 39.0)이다. 린치의 「연 20~25%」를 국내 분포로 옮기면
밴드 자리가 달라진다 — **이 축의 실측이 아직 없다.**

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
`DEBT_RATIO ≤ 60`을 더하는 순간 **0**이 된다.

> **2026-08-20 — 이 문단의 TTM 쪽 수가 재현되지 않는다. 근거로 다시 쓰지 마라.**
> 위 머리말이 지시한 대로 `trade_date`를 08-13으로 고정해 재면 **대형 87 중 TTM 10~20은 11**,
> `DEBT_RATIO ≤ 60`을 더해도 **0이 아니라 1**이다. 사분위도 `−6.6 ~ 129.6`, ρ(TTM, 5yCAGR)도
> **0.110**으로 다르다. **CAGR 쪽은 정확히 맞는다**(대형 87 · CAGR 10~20이 11). 「대형 87 중 6」은
> 이 문서 §배경 표의 **「분류 정의 충족 6/44」**(모집단 44)와 같은 수라, **모집단이 87인 자리에
> 44짜리 수가 앉았을 가능성이 크다.** 「밴드가 잡음보다 좁다」는 **성질 주장은 살아 있다**(TTM
> 사분위 폭이 CAGR의 네 배가 넘는다) — 죽은 것은 **6과 0이라는 수**다. 린치의 「연 20~25% 성장」은 다년 지속률이지 TTM이
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
  **(2026-08-20 — 이 6과 0은 재현되지 않는다. 위 §검증의 무효 표시를 먼저 읽어라. 그리고
  여기 쓰인 「질 게이트」는 `DEBT_RATIO ≤ 60`인데 현행 필수 게이트에는 그것이 없다 —
  `MARKET_CAP`·`EPS_CAGR_5Y`·`OPM_MIN_5Y` 셋뿐이다. 이 기각의 산술은 현행 구성에 그대로
  적용되지 않는다.)**
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
  FY2021 이전은 0행이라 **최근 2년만 성립한다.** 산출 가능 종목은 **284/347(82%)**, 린치 3.14
  통과는 158/284다. **이 수를 낼 때 모집단을 한 번 틀렸다** — `financial_statement`만 보면
  연속 FY 쌍이 286이고 직전 재고가 0인 하나를 빼 285인데, 그중 한화(000880)가 **활성 유니버스에
  없어** 지표가 아예 계산되지 않는다. **ADR-0066의 「모집단은 유니버스 전체」는 게이트 통과율만이
  아니라 커버리지에도 걸린다** — 원자료 테이블을 모집단으로 잡으면 화면에 뜨지 않을 종목까지 센다.
