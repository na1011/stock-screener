---
id: ADR-0083
status: 채택됨
date: 2026-08-24
applies_to:
  - "src/main/java/com/stockscreener/screening/preset/PresetCatalog.java"
  - "src/main/java/com/stockscreener/indicator/calculator/ProfitRetirement3yCalculator.java"
---

# ADR-0083. 이익소각을 세 프리셋의 배점으로 올리고, 배당은 「확인 못 한다」로 닫는다

## 결정

`PROFIT_RETIREMENT_3Y > 0`을 **배점 1**로 세 프리셋에 얹는다. **게이트는 안 건드린다** — 통과
집합의 크기가 안 변하고 순위만 바뀐다.

| 프리셋 | 더할 조건 | 출처 갈래 | 조건 수 | 배점 |
|---|---|---|---|---|
| `buffettQualityKr()` | `PROFIT_RETIREMENT_3Y > 0` | 원문에 없는데 더한 것(버핏 §2.1) | 15 → **16** | 16 → **17** |
| `lynchFastGrowerKr()` | 같음 | 원문에 없는데 더한 것(린치 §4.5) | 17 → **18** | 21 → **22** |
| `lynchStalwartKr()` | 같음 | 같음 | 13 → **14** | 19 → **20** |

**`DIVIDEND_YIELD > 2`는 어느 프리셋에도 안 넣는다.** 근거는 아래 §배당 — 원문으로 확인 못 한다.

**이 문서의 수는 전부 2026-08-20 단면이다**(`indicator_value`의 최신 거래일, `ADJ_CLOSE` 값이
있는 347종목). **MUST: 다시 잴 때 그 날짜를 고정한다** — `max(trade_date)`로 재면 다른 수가 나오고
그 차이는 시장이 아니라 재무 빈티지가 만든다.

**MUST NOT: 「347」을 유니버스라 적지 않는다.** 현 편입은 `universe_membership` **350**,
`stock.is_active`는 **365**이고 347 중 **12는 이미 편입 해제**다. 프리셋 셋 다
`universeCode = null`이라 `ScreeningRepository.selectStockIds`가 유니버스를 안 건다.
통과 수에는 영향이 없다 — 그 12 중 어느 통과 집합에 든 것도 없다(실측 0).

## 규칙

- **MUST: 갈래를 셋 안에서 적는다**(ADR-0061). `PROFIT_RETIREMENT_3Y`를 「원문의 우회를 자동화」
  같은 넷째 범주로 적지 않는다 — 원문이 지시한 우회(「발행주식수 추이를 본다」)는 **이미
  `SHARES_GROWTH_3Y <= 0`으로 들어가 있어** 이것은 **같은 조항에 붙는 두 번째** 조건이다.
- **MUST: 조항이 둘이라는 것을 적는다 — 버핏 §2.1과 린치 §4.5다.** 초안이 버핏만 적어 린치 쪽
  근거가 비어 있었다. 둘 다 *"스크리너 구현 불가 / 우회: 발행주식수 추이"*로 적는다.
  javadoc에 **각 프리셋에서 그 조항이 배점 둘을 갖게 된다**는 것을 적는다.
- **MUST NOT: 「자사주 매입을 본다」로 적지 않는다**(ADR-0066). 국내에서 취득은 안 잡히고
  **소각만** 잡힌다.
- **MUST NOT: `PROFIT_RETIREMENT_3Y`에 `@IndicatorMeta.direction`을 주지 않는다.** 배점 조건을
  거는 것과 방향을 주는 것은 **다른 일**이고, 이 저장소는 이미 그렇게 하고 있다 — 실측으로
  `higher_is_better`가 NULL인 지표 **13종**이 활성 프리셋의 배점 조건으로 쓰인다. 그 계산기
  javadoc의 *「주가에 유리한지는 재본 적이 없다」*는 **그대로 산다.**
- **MUST: 동률 처리를 명시한다.** `PROFIT_RETIREMENT_3Y`는 값 있는 종목의 **74.0%(222/300)가
  정확히 0**이라 방법에 따라 순위상관이 갈린다 — `~SHARES_GROWTH_3Y`가 **동률 평균 −0.432 /
  min-rank −0.282 / 이진화 −0.441 / 원값 피어슨 −0.071**이다. 이 저장소의 잣대는 **동률 평균
  스피어만**이고(ADR-0070 §규칙), **어느 방법으로도 0.8 결론은 안 뒤집힌다.**
- **MUST: 순위상관을 26종 전부에 댄다.** `screen_condition`에서 뽑되 **`indicator_code`와
  `operand_indicator_code`를 union**해야 26이 된다 — 안 하면 25이고 `PER_MEDIAN_5Y`가 빠져
  최대 |ρ|가 0.592가 아니라 0.535로 보인다. **지표 짝으로 중복 제거한다**(`PER`가 고속성장주에
  조건 둘로 들어가 있다).
- **MUST: 프리셋 조건을 바꾸면 참조 워치리스트의 `last_satisfied`를 NULL로 되돌린다**
  (ADR-0061 §규칙). **이번에는 대상이 없다** — `watchlist_item`이 참조하는 프리셋은 `VALUE_KR`
  1건뿐이고 안 바뀐다. **의무가 공전한다는 사실을 적어 둔다**(다음에 참조가 생기면 다시 선다).

## 측정 (2026-08-20)

### 통과 수와 충족 수

| 프리셋 | 게이트 통과 | `PROFIT_RETIREMENT_3Y` 커버 / `> 0` / `= 0` |
|---|---|---|
| `BUFFETT_QUALITY_KR` | **15** | 15 / **6** / 9 |
| `LYNCH_FAST_GROWER_KR` | **9** | 9 / **3** / 6 |
| `LYNCH_STALWART_KR` | **10** | 10 / **4** / 6 |

**하루 단면이 아니라 최근 5거래일(08-13·14·18·19·20) 전부에서 같은 결론이다** — 통과 15~16 ·
8~9 · 10~11이고, **커버리지가 세 통과 집합 모두 닷새 내내 100%**이며 어느 날에도 0/n이나 n/n이
되지 않는다. 커버리지 100%가 하루의 우연이 아니라는 것이 **「배점이 판정불가로 조용히 빠지지
않는다」**의 근거다. **고아 행도 없다** — 최신 거래일의 값 전부가 `governance_fact` 뒷받침을
갖는다(ADR-0080의 「계산이 그만 난 옛 행」이 이 자리엔 없다).

### 기존 조건이 못 보는 자리

`SHARES_GROWTH_3Y <= 0`과 교차하면 두 조건이 **다른 것을 잰다**는 것이 드러난다:

| | `PR = 0` | `PR > 0` |
|---|---|---|
| `SG <= 0` | **130** | 58 |
| `SG > 0` | 89 | **16** |

**130종목이 소각을 한 번도 안 하고 기존 조건을 통과한다**(발행을 안 했을 뿐이다). 반대로
**16종목은 실제로 소각했는데** 순증이라 빠진다. 원문이 두 반쪽을 적는다 — 버핏 §2.1
*"… 아니면 **주주에게 돌려주는지**. 버핏은 이 중 **자사주 매입을 선호**한다."*

### 순위상관과 분포

`PROFIT_RETIREMENT_3Y`의 26쌍 **전부 |ρ| < 0.8**이고 최대가 `~SHARES_GROWTH_3Y` **−0.432**,
다음이 `~OPM_MIN_5Y` +0.267 · `~PER_MEDIAN_5Y` −0.211이다. 유니버스 커버 **300**, `> 0`이
**77(25.7%)**, `= 0`이 222(74.0%)다. 통과 집합에서 6/15·3/9·4/10으로 갈려 **상수가 아니다.**

## 검증

```
rg -n 'ProfitRetirement3yCalculator[.]CODE' src/main/java/com/stockscreener/screening/preset/PresetCatalog.java
```
세 프리셋 블록에서 **각각 한 줄씩, 전부 `weighted(`**여야 한다 — `required(`로 감싸이면 이
결정이 안 지켜진 것이다.

**IMPORTANT — 아래 두 펜스가 감시하는 값은 `OK` 문자열이 아니라 FAIL 분기의 조건이다.**
`check-premises.sh:172-173`이 `elif [ "${out#OK}" != "$out" ]`로 **OK 페이로드를 세기만 하고
버리고**, `:248`이 발견·무판정이 0이면 **아무것도 안 찍는다.** 초안이 *"OK 문자열에 비율을
싣는다"*로 적었는데 **그것은 무동작이었다** — 이 문서가 두 번 비난한 「실패할 수 없는 펜스」를
스스로 만들 뻔한 자리다.

```premise 새 배점 조건이 통과 집합에서 상수가 아니고 커버리지도 안 떨어졌다
psql -d stock_screener -At -c "WITH d AS (SELECT max(trade_date) t FROM indicator_trade_date WHERE market_code IN ('KOSPI','KOSDAQ')), uni AS (SELECT DISTINCT stock_id FROM indicator_value iv, d WHERE iv.trade_date = d.t AND iv.indicator_code = 'ADJ_CLOSE'), new AS (SELECT s.code sc FROM screen_condition c JOIN condition_group cg ON cg.id = c.group_id JOIN screen s ON s.id = cg.screen_id WHERE s.is_active AND NOT c.is_required AND c.indicator_code = 'PROFIT_RETIREMENT_3Y'), g AS (SELECT s.code sc, c.indicator_code ind, c.operator op, c.literal_value v1, c.literal_value_2 v2 FROM screen_condition c JOIN condition_group cg ON cg.id = c.group_id JOIN screen s ON s.id = cg.screen_id WHERE s.is_active AND c.is_required AND c.operand_type = 'LITERAL' AND s.code IN (SELECT sc FROM new)), p AS (SELECT g.sc, u.stock_id, COALESCE(CASE g.op WHEN 'LTE' THEN iv.value<=g.v1 WHEN 'LT' THEN iv.value<g.v1 WHEN 'GTE' THEN iv.value>=g.v1 WHEN 'GT' THEN iv.value>g.v1 WHEN 'BETWEEN' THEN iv.value>=g.v1 AND iv.value<=g.v2 END, false) pass FROM g CROSS JOIN uni u LEFT JOIN indicator_value iv ON iv.stock_id = u.stock_id AND iv.indicator_code = g.ind AND iv.trade_date = (SELECT t FROM d)), win AS (SELECT sc, stock_id FROM p GROUP BY sc, stock_id HAVING count(*) FILTER (WHERE NOT pass) = 0), v AS (SELECT w.sc, count(*) n_pass, count(iv.value) n_val, count(*) FILTER (WHERE iv.value > 0) n_hit FROM win w LEFT JOIN indicator_value iv ON iv.stock_id = w.stock_id AND iv.indicator_code = 'PROFIT_RETIREMENT_3Y' AND iv.trade_date = (SELECT t FROM d) GROUP BY 1) SELECT CASE WHEN (SELECT count(*) FROM new) = 0 THEN '잴 것이 없었다 — 세 프리셋 어디에도 이익소각 배점 조건이 없다(배포 전이거나 되돌려졌다)' WHEN (SELECT count(*) FROM win) = 0 THEN '잴 것이 없었다 — 그 프리셋들의 게이트를 통과한 종목이 없다' WHEN (SELECT count(*) FROM v WHERE n_val = 0) > 0 THEN '통과 종목에 이익소각 값이 하나도 없는 프리셋이 있다 — 배점이 전원 미충족으로 죽는다: ' || (SELECT string_agg(sc, ', ') FROM v WHERE n_val = 0) WHEN (SELECT count(*) FROM v WHERE n_val < n_pass) > 0 THEN '커버리지가 100%에서 내려갔다 — 그 종목은 사업 성적이 아니라 자료 사유로 배점을 잃는다: ' || (SELECT string_agg(sc || ' ' || n_val || '/' || n_pass, ', ') FROM v WHERE n_val < n_pass) WHEN (SELECT count(*) FROM v WHERE n_hit = 0 OR n_hit = n_val) > 0 THEN '통과 집합에서 상수가 된 배점이 있다(순위를 못 바꾼다): ' || (SELECT string_agg(sc || ' ' || n_hit || '/' || n_val, ', ') FROM v WHERE n_hit = 0 OR n_hit = n_val) ELSE 'OK ' || (SELECT string_agg(sc || ' ' || n_hit || '/' || n_val, ', ' ORDER BY sc) FROM v) END"
```

**모집단이 「게이트 통과 종목」이고 값 없는 종목을 거기서 빼지 않는 것이 요점이다.** 빼면
커버리지가 15/15에서 8/15로 내려가도 **침묵한다** — 그리고 커버리지 100%가 이 결정의 근거였다.

```premise 이익소각 배점이 기존 26종과 같은 축을 재지 않는다
psql -d stock_screener -At -c "WITH d AS (SELECT max(trade_date) t FROM indicator_trade_date WHERE market_code IN ('KOSPI','KOSDAQ')), tgt AS (SELECT DISTINCT s.code sc FROM screen_condition c JOIN condition_group cg ON cg.id = c.group_id JOIN screen s ON s.id = cg.screen_id WHERE s.is_active AND NOT c.is_required AND c.indicator_code = 'PROFIT_RETIREMENT_3Y'), used AS (SELECT DISTINCT c.indicator_code i FROM screen_condition c JOIN condition_group cg ON cg.id = c.group_id JOIN screen s ON s.id = cg.screen_id WHERE s.is_active AND s.code IN (SELECT sc FROM tgt) UNION SELECT DISTINCT c.operand_indicator_code FROM screen_condition c JOIN condition_group cg ON cg.id = c.group_id JOIN screen s ON s.id = cg.screen_id WHERE s.is_active AND s.code IN (SELECT sc FROM tgt) AND c.operand_indicator_code IS NOT NULL), other AS (SELECT i FROM used WHERE i <> 'PROFIT_RETIREMENT_3Y'), v AS (SELECT indicator_code code, stock_id, value FROM indicator_value iv, d WHERE iv.trade_date = d.t), pr AS (SELECT o.i bc, x.value av, y.value bv FROM other o JOIN v x ON x.code = 'PROFIT_RETIREMENT_3Y' JOIN v y ON y.code = o.i AND y.stock_id = x.stock_id), r1 AS (SELECT bc, av, bv, row_number() OVER (PARTITION BY bc ORDER BY av) ra, row_number() OVER (PARTITION BY bc ORDER BY bv) rb FROM pr), r2 AS (SELECT bc, avg(ra) OVER (PARTITION BY bc, av) x, avg(rb) OVER (PARTITION BY bc, bv) y FROM r1), q AS (SELECT bc, count(*) n, corr(x, y) rho FROM r2 GROUP BY 1 HAVING count(*) >= 30) SELECT CASE WHEN (SELECT count(*) FROM tgt) = 0 THEN '잴 것이 없었다 — 이익소각 배점 조건을 쓰는 프리셋이 없다(배포 전이거나 되돌려졌다)' WHEN (SELECT count(*) FROM q) = 0 THEN '잴 것이 없었다 — 30종목 이상 겹치는 짝이 하나도 없다' WHEN (SELECT max(abs(rho)) FROM q) >= 0.8 THEN 'ADR-0068의 0.8 잣대를 넘는 짝이 생겼다: ' || (SELECT string_agg(bc || ' ' || round(rho::numeric,3), ', ') FROM q WHERE abs(rho) >= 0.8) ELSE 'OK 비교 대상 ' || (SELECT count(*) FROM other) || '종 · ' || (SELECT count(*) FROM q) || '쌍 최대 |rho| ' || (SELECT round(abs(rho)::numeric,3) || ' (' || bc || ', n=' || n || ')' FROM q ORDER BY abs(rho) DESC LIMIT 1) END"
```

**MUST: 이 펜스가 무엇을 안 보는지 함께 읽는다 — 모집단이 「이익소각 × 26종」이지 프리셋 전체의
짝이 아니다.** 좁은 것이 잘못이 아니라 **좁다는 것을 안 적으면 그 침묵이 「이 프리셋들의 겹침은
깨끗하다」로 읽히는 것**이 잘못이다. 실제로 프리셋 안에는 **열린 규칙 위반이 둘 있다** —
`PEG ~ EPS_GROWTH_TTM_YOY` −0.832(ADR-0070 §미결)와 버핏의 게이트 `DEBT_RATIO ≤ 100` × 배점
`≤ 50`(ADR-0068 §규칙). **전 짝으로 넓히면 이 펜스가 첫날부터 붉은데, 그 둘은 이 결정의 전제가
아니다.**

**MUST: 이 펜스가 값의 표류에 울지 않는다는 것을 안다.** ρ이 닷새에 **0.005**밖에 안 움직인다
(`~SHARES_GROWTH_3Y` −0.430 → −0.432). 울리는 것은 **새 조건이 더해질 때**다.

## 배경

거버넌스 축(ADR-0081)이 열렸는데 **프리셋에는 하나도 안 걸려 있었다.** 처음에는 *「무배당인지
데이터가 없는지 구분할 수 없으니 배당을 조건으로 못 쓴다」*와 *「얹어도 순위가 2자리밖에 안
바뀐다」*로 닫았는데 **둘 다 사용자가 반박했고 재보니 맞았다.**

- 앞엣것은 **우리 코드 결함**이었고 ADR-0082가 고쳤다(`DPS_COMMON` 255 → 347).
- 뒤엣것은 **이 저장소가 금지한 추론**이다 — ADR-0061이 *「결과 수를 근거로 임계값을 풀지
  않는다」*를 걸고 ADR-0074가 *「근거는 결과 수가 아니라 출처」*를 못박는다.

**MUST NOT: 아래 §배당의 판단을 그 둘 중 하나로 읽지 않는다.** 죽은 근거를 되살린 것이 아니라
**배치**를 못 정한 것이다.

## 배당 — `DIVIDEND_YIELD > 2`를 안 넣는다. 「원문으로 확인 못 한다」다 (2026-08-24 사용자 결정)

**MUST: 사용자가 고른 문장과 그에 대한 해석을 갈라 읽는다.** 사용자가 고른 것은
**「확인 못 한다로 적고 안 넣는다」**이고, 그것은 **「배당이 이 유형들에 안 맞는다고 판정됐다」가
아니다.** 형식은 ADR-0070이 분류축에서 쓴 것과 같다(*"이 표본으로는 뒤집을 수단이 없다"*).

**MUST: 안 넣는 근거가 「출처가 없다」가 아님을 안다.** §3.10에는 **명문 조건
`Dividends → Dividend Yield : > 2`가 있다.** 못 정한 것은 **그것이 어느 유형에 걸리는가**다.

**양쪽 증거를 그대로 싣는다 — 어느 쪽도 이기지 못한다.**

| 안 넣는 쪽 | 판정 |
|---|---|
| §7(고속성장주 통합본)에 배당이 없다 | **약하다.** §7은 구현 가능한 §3층 조건을 여럿 뺐다 — §3.11 `P/B 0~1.5`(§8이 한국 조정값 `0~0.8`까지 준다) · §3.14 부분 대용 · §3.6 |
| §2.2·§2.3 유형 스크린에 배당이 없다 | **약하다.** 둘은 유형을 정하는 다섯 줄짜리 블록이고, 현행 프리셋도 그 밖의 조건을 열 개 넘게 쓴다 |
| 배당이 유형 스크린에 명시된 것은 **§2.1 저성장주뿐이고 `> 3`**이다 | **선다.** 다만 §3.10은 §2.1의 셋 중 **둘만, 주 임계를 3 → 2로 완화해** 가져왔다 — 「§2.1의 파편」이 아니라 **더 약한 일반 조건**의 모양이다 |
| §3.10 둘째 문장 *"다만 배당 때문에 성장 여력이 깎이는지도 봐야 한다"* | **약하다.** 앞 문장 *"배당을 주는 회사는 무모한 사업 확장을 덜 한다"*가 **유형 중립의 친배당 논거**다 |
| 선례 — ADR-0070이 §3.11 P/B를 유형 근거로 뺐다 | **반만.** 그 기각의 다리 **둘 중 하나(§8의 깨진 전제)가 배당에는 없다.** 나머지도 형태가 다르다 — ρ(PBR, 성장) **+0.274/+0.237**로 실제로 성장주를 자르는데 ρ(`DIVIDEND_YIELD`, `EPS_CAGR_5Y`)는 **−0.145**, 매출성장 **−0.079**로 거의 직교한다(배당은 성장축이 아니라 **가치축**이다 — ρ(DY, PBR) **−0.574**) |

| 넣는 쪽 | 판정 |
|---|---|
| §3.10의 명문 `> 2` | **선다** |
| §3 열여덟 중 **열넷이 유형에 침묵**하고 명시가 예외다(§3.18 `(장기보유형)` · §3.14 업종 제한) | **선다.** §3.10은 침묵하는 열넷에 든다 |
| §3.18은 `(장기보유형)`을 달고도 **§7 고속성장주 통합본에 들어 있다** | **선다.** 문서 자신의 사례가 「§3의 유형 단서가 그 조항을 다른 유형 스크린에서 빼지 않는다」이다 |
| 저장소가 이미 **§7 밖 §3층 조건을 둘 쓴다**(§3.5 `PER < PER_MEDIAN_5Y` · §3.14 `INVENTORY_GROWTH_GAP`) | **약하다.** 둘은 원문이 「구현 불가」로 적어 §7에 못 실린 것이고 §3.10은 다르다 |
| §2층 머리말 *"분류를 먼저 하지 않으면 **아래** 숫자 조건은 의미가 없다"* | **중립.** 분류를 **선행 조건**으로 둘 뿐 유형별로 갈린다고는 안 한다. **MUST NOT: 앞 문장 *"유형마다 판단 잣대가 전부 다르다"*만 떼어 반대 근거로 쓰지 않는다 — 저자가 굵게 표시한 것은 둘째 문장뿐이다** |

**MUST: 다시 열 조건을 적는다 — 원문 밖의 근거가 생겨야 열린다.** 사후 수익률·새 문헌·사용자
판단이 그것이다. **원문을 다시 읽는 것으로는 안 열린다** — 이 결론에 닿기까지 §3.10 배치를
**다섯 번** 판단했고 그때마다 근거가 원문 대조에서 무너졌다(§버린 대안의 마지막 항목).

**연속 배당 연수(§3.10 보조)도 같이 보류한다.** 본조항과 함께 가는 조항이다. 지금 관측 상한이
**4년**인데(`governance_report_row`의 `DIVIDEND`가 `bsns_year` 2024·2025뿐이라 FY2022~2025이고,
**203종목이 4년 전부 배당해 상한에 붙는다**) §8 한국 조정값은 `≥ 5 years`다. 여는 비용은
`--job=governance-load --governance-year=2023 --governance-span=2 --governance-endpoints=DIVIDEND`
**700콜**(DART 보수 예산 10,000의 7%)이고 그러면 FY2020~2025 6년이 된다.
**MUST: 두 해를 받아야 하는 이유를 안다** — `GovernanceMath.figuresOn`이 그 거래일에 **공시돼
있던** 최신 회계연도를 앵커로 잡는데 FY2025 DPS 공시일 중앙값이 2026-03-18이라 저장 창
180거래일 중 **앞 74일은 앵커 FY2024, 뒤 106일은 FY2025**다. 한 해만 받으면 뒤 106일만 5년이 된다.
**MUST NOT: `--governance-span`을 「안 주면 최대 5년」으로 적지 않는다** — `GovernanceLoadJob`의
`YEARS_BACK`에서 **`DIVIDEND`는 2**다(5는 `SHARE_TOTALS`).

## 버린 대안

거버넌스 지표 열셋 중 **열둘을 안 넣는다.**

**MUST: 아래 통과 수·상관이 ADR-0082 적재 이전 값임을 안다.** 특히 `DIVIDEND_PAYOUT`의 기각
근거였던 *「버핏 통과 14 중 13이 통과해 상수」*는 **통과가 이제 15이고 커버가 231 → 268**이라
**다시 재야 한다.** 나머지도 재측정하지 않았다.

- **`OUTSIDE_DIRECTOR_RATIO`** — **규모를 중복해서 잰다.** 자산 2조 이상 상장사에 사외이사 과반이
  법정 의무라 시총 2조 이상은 75.7%가 ≥50인데 미만은 37.1%다(ρ(시총) **+0.52**). 세 프리셋 전부
  `MARKET_CAP`을 게이트로 두고, 린치 대형우량주는 통과 **전원**이 ≥50이다.
- **`AUDIT_EMPHASIS`** — **출처가 다른 물건을 가리킨다.** 린치 §8이 드는 것은 **감사의견 거절**이고
  대응물은 `AUDIT_OPINION_ADVERSE`다. 강조사항은 **적정 의견에도 붙는다**.
- **`AUDIT_OPINION_ADVERSE`** — 활성 유니버스 전원 0이다. 허용목록이 비적정 8행 중 1행만 잡는
  별건도 있는데 **공시일이 2020~2023이라 읽기 창 밖**이다.
- **`TREASURY_SHARE_RATIO`** — `phase-criteria`가 명시로 금지한다(*「자기주식은 주주환원이기도
  하고 경영권 방어이기도 한데 어느 쪽이 지배적인지 재본 적이 없다」*).
- **`DIVIDEND_PAYOUT`** — 버핏 §4.4는 **모형의 입력**이고 원문이 *「스크리너 구현 불가」*로 적는다.
- **`DIVIDEND_YIELD`** — 위 §배당. **버핏에는 아예 넣지 않는다** — 버핏 문서에서 「배당」이 나오는
  곳은 `rg` 실측 **한 줄(§4.4)**뿐이고 거기서 배당은 성장률을 **깎는 항**(`평균 ROE × (1 −
  배당성향)`)이다. 실측으로도 버핏 배점 `RETAINED_TO_MARKET_4Y`와 **역방향**이다(−0.359).
  **컬럼으로도 넣지 않는다** — `extraColumns`는 `codeIfPreset()`일 때만 걸려 **복제해서 커스텀으로
  저장하면 사라진다**(`ScreeningResponseAssembler.java:92`).
- **나머지 여섯**(`EXEC_PAY_TO_NET_INCOME`·`DIRECTOR_TURNOVER_3Y`·`LARGEST_HOLDER_STAKE`·
  `MINORITY_HOLDER_STAKE`·`CONTROL_TRANSFER_3Y`·`AUDITOR_CHANGE_3Y`) — **버핏·린치 원문에 대응
  조항이 없다.** 커스텀 화면이 이들을 쓴다 — **커스텀에는 문헌 출처 요구가 안 걸린다.**
  **MUST NOT: `MINORITY_HOLDER_STAKE`를 린치 §4.2(기관 보유 비중)의 구현이라 적지 않는다** —
  다른 물건이고 저장소가 §4.2를 판정한 적이 없다.

### 린치 §3.4 배당조정 PEG — 기각. **분모 정의와 무관하다**

`PER ÷ (이익성장률 + 배당수익률)`. 문서가 스스로 *"AAII Lynch Screen의 핵심 컷오프"*라 부르고
입력이 다 있다(`PER` · 성장률 · `DIVIDEND_YIELD`).

**`DIVIDEND_YIELD`는 실측 최솟값 0.00 · 음수 0건**이므로 `dy ≥ 0`이고, `PER > 0 ∧ g > 0`인
구간에서 **`adj = PER/(g+dy) ≤ PER/g = PEG`** — 배당조정판은 **PEG의 완화판**이다. 분모에 무엇을
넣든 그 구간에서는 참이고, 네 컷오프 전부 **`PEG만 충족 = 0`**이다(TTM 1.0: 151/**0**/2 ·
TTM 0.5: 124/**0**/5 · 5Y 1.0: 70/**0**/8 · 5Y 0.5: 40/**0**/2).

- **MUST: 정의역 밖을 함께 적는다.** `PegCalculator`가 `growthPct.signum() <= 0`에서 값을 안 내므로
  **`g ≤ 0`에서는 위 항등식이 공전한다** — 그 구간이 **47종목**, 배당으로 되살아나는 것 **5**,
  컷오프를 넘는 것 **0**이다. **오늘은 비어 있어 결론이 서지만** 배당이 늘면 그 구간이 찬다.
- **MUST: 「한 방향으로만 넓힌다 — 문턱을 푸는 것과 같다」로 적는다.** *"아무것도 안 바꾼다"*는
  거짓이다 — 5년축 1.0에서 **8종목 유입**, `dy > 2` 안에서는 7/38이다.
- **MUST NOT: 「`PEG`의 분모축은 ADR-0070이 이미 TTM으로 정했다」고 적지 않는다 — 거짓이다.**
  `rg` 실측으로 **어느 ADR도 `PEG`의 분모축을 결정한 적이 없다.** TTM은 **계산기의 구현
  기본값**이고, **구현을 결정으로 승격시키지 않는다.** 「`PEG`의 성장축을 TTM에서 5년 CAGR로
  바꿀 것인가」는 **§3.3의 열린 질문**이지 §3.4가 아니다.

### 판단을 다섯 번 틀렸다 — 그 경로를 남긴다

**§3.10 배치는 이 저장소에서 가장 여러 번 뒤집힌 자리다.** 매번 *직전 근거가 원문 대조에서
거짓 → 반대편으로*였다. **MUST: 이 목록을 지우지 않는다** — 지우면 여섯 번째가 온다.

1. **「§7 밖의 §3층 조건을 이미 쓴다」**(이 문서의 초안) — 예시 넷 중 **둘(§3.9·§3.13)이 §7 안**.
2. **「§7이 §3.10만 뺐다」** — **§3.11 P/B**도 구현 가능하고 §8 조정값까지 있는데 §7에 없다.
   **사용자가 이 거짓 근거 위에서 한 번 승인했고, 그래서 다시 물었다.**
3. **「§2층 머리말이 §3층을 공통층으로 둔다」** — **인용을 절단했다**(ADR-0068 §규칙이 금지한 형태).
4. **「§3층 제목이 유형 중립이라 §9 침묵이 근거가 된다」** — §3.16·§3.18이 **§3 안에서** 반증한다.
   그리고 §9의 침묵은 **§7의 침묵과 같은 지위**다(§3.2·§3.9·§3.11이 「불가」를 적고도 §9에 없다).
5. **「앞 문장이 주장이다」로 3번을 바로잡은 것** — **저자가 굵게 표시한 것은 둘째 문장뿐이다.**
   절단을 고친다면서 **강조를 옮겼다.**

## 결과

- **함께 고친 것**: `PresetCatalog`의 javadoc·`description`과 통과 수 드리프트, `ADR-0068`
  §결정의 **날짜 못박기**(41%로 고치지 않았다 — 16 → 17은 이 문서가 만든 조성이라 그 문서가
  자기가 안 한 결정을 주장하게 된다), `AsOfRecomputeTest`·`ADR-0071`의 「배점 아홉」 → 「배점 열」,
  `ConditionReturnTest`·`StudentTTest`의 「조건 15개」 → 「16개」.
- **MUST NOT: `docs/phase-criteria.md`의 수를 고치지 않는다.** P14·P15·P16·P16-B·P16-C·P17의
  측정표는 전부 **「이 기준은 코드를 한 줄도 만들기 전에 적었다」**로 시작하는 **사전등록**이고,
  거기 실린 수는 그 시점에 무엇을 알고 시작했는가의 기록이다. **고치면 골대 이동이다.**
  같은 이유로 `ADR-0071`의 사전등록 절(*"게이트 6 / 배점 9 … 실제로 잰 것은 14"*)도 안 고친다.
- **「배점 N 중 7」이 여러 곳에 사본으로 있다** — `PresetCatalog`의 javadoc·`description`·주석 셋과
  `ADR-0068`·`phase-criteria`다. **`check-doc-staleness.sh`의 거울 표는 파일 둘·파일당 한 문장만
  받으므로 이 형태를 다 못 지킨다.** 그래서 **날짜를 박는 쪽**을 골랐다 — 각 자리가 어느 시점의
  조성인지 적으면 갈려도 갈린 것이 드러난다.
- **테스트 핀**: `PresetCatalogTest`의 가장 큰 프리셋 조건 수 **17 → 18**(고속성장주). 상한
  `MAX_CONDITIONS = 25`는 안 건드린다. **MUST: 그 수가 「조건 개수」이고 「배점 총합 17」과 값이
  같아도 무관한 수임을 주석이 적는다.**
- **배포가 필요하다** — `PresetSynchronizer`가 기동 시 투영하므로 재시작 없이는 안 반영된다.
  **백필·적재는 없다** — 조건은 조회 시점에 `indicator_value`를 읽고, 두 지표가 저장 창
  180거래일을 이미 전부 채운다(59,802 / 53,530행).
- **커스텀 화면 18 「버핏 우량주 + 지배구조」에 같은 조건 한 행을 더한다**(2026-08-24 사용자 결정).
  그 화면의 버핏 쪽 15조건이 **현행 프리셋과 완전히 같아**(diff 0행) **삭제가 필요 없고**,
  되돌리기는 한 행 DELETE다. `sort_order`를 버핏 배점 블록 끝에 맞춘다.
  **버린 안**: 초안의 「배포 뒤 UI에서 지우고 다시 복제한다」 — 거버넌스 5조건을 손으로 다시
  넣어야 하고 **삭제가 되돌릴 수 없다.**
- **알려진 한계**: 대형우량주는 **ADR-0070의 발생액 펜스가 붉은 채로** 남아 있는 프리셋이다.
  이 변경은 배점만 건드려 그 미결을 건드리지 않지만, **붉은 채로 배점을 얹는다.**
- **되돌리려면** 세 프리셋에서 `weighted(ProfitRetirement3yCalculator.CODE, ...)` 한 줄씩을 빼고
  배포한다. 지표·적재·스키마는 이 결정과 무관하다.
