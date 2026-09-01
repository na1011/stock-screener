---
id: ADR-0121
status: 채택됨
date: 2026-09-02
applies_to:
  - "src/main/java/**/screening/preset/PresetCatalog.java"
---

# ADR-0121. 3중창 반등을 Elder·Spiroglou·Granville에서 짓는다

## 결정

테스트베드 프리셋 `TRIPLE_SCREEN_REBOUND`(「3중창 반등」)를 문헌 셋에서 짓는다 — 다중 시간축
구조는 Elder의 Triple Screen(1993), 모멘텀 국면 정의는 Spiroglou의 MACD-V(2022 CMT Charles H.
Dow Award · NAAIM Founders Award), 매집 판정은 Granville의 OBV(1963)다. `FILTER` 4조건,
가중치·`required` 없음, 유니버스는 실행 요청이 고른다.

**조건을 먼저 고르고 문헌을 나중에 붙이지 않았다.** 이 저장소는 스윙 프리셋을 두 번 폐기했고
(ADR-0061·0074) **폐기 근거는 결과 0건이 아니라 「출처」였다.** 그래서 문헌을 먼저 읽고 그
문헌이 정의한 국면을 옮겼다. 사용자가 처음 묘사한 구조가 이미 존재하는 방법론과 같은 것이었다는
점이 출발이다.

| # | 조건 | 출처 갈래 | 문헌 |
|---|---|---|---|
| 1 | `MACD_V_HIST_CHG_W > 0` | **원문의 축을 옮긴 것** | Elder Screen 1 — 주봉 MACD 히스토그램 상승 |
| 2 | `MACD_V BETWEEN -150 AND 50` | **원문 그대로** | Spiroglou Rebounding 밴드 |
| 3 | `MACD_V_HIST > 0` | **원문 그대로** | Spiroglou Rebounding의 *"above its signal line"* |
| 4 | `OBV_T_13 > 1.96` | **원문에 없는데 더한 것** | 개념은 Granville OBV, 임계는 이 지표의 95% 유의 구간 |

`extraColumns`는 `MACD_V_HIST_CHG_5`(일봉이 어느 쪽으로 가는가)와 `MACD_V_W`(주봉 수준)다.

## 규칙

- **MUST: 축 치환을 치환이라고 적는다.** Elder가 쓴 것은 1993년의 고전 MACD이고 이 프리셋은
  MACD-V로 바꿨다. 바꾼 이유는 이 저장소의 `MACD`가 스스로 *"주가 수준에 비례하므로 다른 종목과
  직접 비교하지 않는다"*고 적기 때문이고, 스크리너는 종목을 나란히 세우는 도구다.
  **치환이 「상승」의 뜻을 바꾼다** — 모멘텀이 그대로여도 **변동성이 줄면** `MACD/ATR`
  히스토그램은 오른다. 나쁜 신호는 아니지만 **Elder가 그것까지 뜻했다고 적으면 거짓이다.**
  **둘이 얼마나 자주 갈리는지는 재보지 않았다** — 주봉 고전 MACD 히스토그램을 저장하지 않는다.
- **MUST NOT: ±150/±50을 국내 분포로 재보정하지 않는다**(ADR-0109 — 원저가 그 시장을 보고 쓴
  수다).
- **MUST: 못 옮긴 것 둘을 `description`에 적는다.** Screen 2의 「되돌림을 기다린다」는 조건으로
  두지 않고 모멘텀 국면으로 갈음했고, Screen 3의 장중 진입 시점은 종가 기준 화면이라 다루지
  않는다. **Screen 3을 「원리적으로 불가」로 적지 않는다** — `price_daily.high`가 있어 「종가 >
  전일 고가」는 원자료로 계산된다. 못 하는 것은 **체결 시점**이지 돌파 판정이 아니다.
- **MUST: 밴드 상한이 무엇을 빼는지 좁혀 적는다.** 상한이 빼는 것은 **시그널선 위 기준의
  Rallying 이상**이지 「과열 일반」이 아니다. 시그널선 **아래**의 높은 MACD-V는 Retracing이고
  그것은 조건 ③이 이미 거른다.
- **MUST: 구간이 닫혔다는 것을 적는다.** 원문은 `50 > X > -150`(양끝 열림)이고 조건은
  `BETWEEN`(양끝 닫힘)이다. 저장 자릿수 8에서 차이는 사실상 없지만 「원문 그대로」라 적으려면
  이 차이를 함께 적는다. **쪼개면 안 된다** — `MACD_V > -150`은 **99.2% 통과**(과매도가 1,348 중
  11)라 ADR-0061의 90% 규칙에 걸린다.
- **MUST: 상수·javadoc·팩토리를 한 덩어리로 둔다.** `PresetCatalogTest`가 `PresetCatalog.java`를
  읽어 `String <CODE> =`부터 **다음 `ScreenPreset(`까지**를 잘라 출처 마커를 찾는다. 상수를 파일
  위쪽에 몰아 두면 블록이 **다른 프리셋의 javadoc을 삼켜** 출처를 하나도 안 적어도 초록이 된다.
- **MUST NOT: 결손 원인을 「상장 경과」로 적지 않는다.** `stock.listed_on`은 이 91종목 전부
  NULL이라 **우리가 재는 값이 아니다.** 재는 것은 **우리가 수집한 봉의 수**이고, 그 결손에는
  기업행위 억제(ADR-0055)가 섞인다. 아래 §측정 결과의 세 갈래가 그것이다.

## 검증

**MUST NOT: 이 절의 펜스를 `premise`로 올리지 않는다.** 프리셋 코드를 하드코딩한 전제는 그
프리셋이 폐기되면 영원히 `OK`를 내는 **실패할 수 없는 펜스**가 된다. 세션 시작 예산도
**29초/30초라 여유가 1초뿐이다** — 전제 113개가 전부 판정되는 상태에서 잰 값이다(2026-09-02
전수 재실행, 참 109 · 발견 4 · 판정 불가 0). 흔히 보이는 27.0초는 **22개가 무판정으로 스킵된
채**의 값이라 예산 판단에 쓰면 안 된다. 아래는 재려고 할 때 손으로 돌리는 것이다.

조건 넷의 시장별 통과율(ADR-0061의 90% 규칙, 분모는 유니버스 전체 — ADR-0066):

```bash
psql -d stock_screener -At -F ' | ' -c "
WITH d AS (SELECT max(trade_date) md FROM indicator_value WHERE indicator_code='MACD_V_HIST_CHG_W')
SELECT m.universe_code, c.indicator_code,
       round(100.0*count(*) FILTER (WHERE v.value IS NOT NULL AND (
         (c.operator='GT' AND v.value > c.literal_value) OR
         (c.operator='BETWEEN' AND v.value BETWEEN c.literal_value AND c.literal_value_2)))/count(*),1)
FROM screen s JOIN condition_group g ON g.screen_id=s.id JOIN screen_condition c ON c.group_id=g.id
CROSS JOIN universe_membership m
LEFT JOIN indicator_value v ON v.stock_id=m.stock_id AND v.indicator_code=c.indicator_code
                            AND v.trade_date=(SELECT md FROM d)
WHERE s.code='TRIPLE_SCREEN_REBOUND' AND m.effective_to IS NULL
  AND m.universe_code IN ('KOSPI_TOP200','KOSDAQ_TOP150','US_TOP1000')
GROUP BY m.universe_code, c.indicator_code, c.sort_order ORDER BY m.universe_code, c.sort_order;"
```

네 지표의 순위상관(ADR-0068의 잣대를 같은 형태로 적용 — 그 규칙 자체는 SCORING의 게이트 대
배점에 걸리는 것이라 이 `FILTER`에는 문자 그대로 걸리지 않는다):

**MUST NOT: 순위를 `corr()` 인자 자리에서 매기지 않는다** — `corr(rank() OVER (...), ...)`은
*aggregate function calls cannot contain window function calls*로 죽는다. 순위를 CTE에서 먼저
매기고 바깥에서 상관을 낸다.

```bash
psql -d stock_screener -At -F ' | ' -c "
WITH d AS (SELECT max(trade_date) md FROM indicator_value WHERE indicator_code='MACD_V_HIST_CHG_W'),
p AS (SELECT stock_id,
        max(value) FILTER (WHERE indicator_code='MACD_V') a,
        max(value) FILTER (WHERE indicator_code='MACD_V_HIST') b,
        max(value) FILTER (WHERE indicator_code='MACD_V_HIST_CHG_W') c,
        max(value) FILTER (WHERE indicator_code='OBV_T_13') e
      FROM indicator_value WHERE trade_date=(SELECT md FROM d)
        AND indicator_code IN ('MACD_V','MACD_V_HIST','MACD_V_HIST_CHG_W','OBV_T_13')
      GROUP BY stock_id),
q AS (SELECT * FROM p WHERE a IS NOT NULL AND b IS NOT NULL AND c IS NOT NULL AND e IS NOT NULL),
r AS (SELECT rank() OVER (ORDER BY a) ra, rank() OVER (ORDER BY b) rb,
             rank() OVER (ORDER BY c) rc, rank() OVER (ORDER BY e) re FROM q)
SELECT count(*), round(corr(ra,rb)::numeric,3), round(corr(ra,rc)::numeric,3),
       round(corr(ra,re)::numeric,3), round(corr(rb,rc)::numeric,3),
       round(corr(rb,re)::numeric,3), round(corr(rc,re)::numeric,3) FROM r;"
```

**둘 다 2026-09-02에 실제로 돌렸다.** 첫째는 아래 §측정 결과의 통과율 열두 칸과 같은 수를,
둘째는 `1259 | 0.385 | 0.399 | 0.573 | 0.772 | 0.653 | 0.493`을 냈다 — 순서는
`n · MACD_V~HIST · MACD_V~CHG_W · MACD_V~OBV · HIST~CHG_W · HIST~OBV · CHG_W~OBV`다.

**활성 프리셋 개수 펜스는 ADR-0074에 있고 같은 커밋에서 8 → 9로 고쳤다.** 그 ADR 자신의 MUST가
「프리셋 코드를 하드코딩한 펜스는 같은 커밋에서 걷는다」이다.

## 배경

사용자가 테스트베드용 프리셋을 요청하며 세 가지를 걸었다 — ① *"곧 추세가 반등할 여지가 보이며,
올라타면 스윙용으로 좋은 주식"* ② 과열된 것은 배제 ③ **즉흥이 아니라 실제 금융권에서 검증된
방법론**일 것. ③이 이 결정의 제약을 전부 정했다. 활성 프리셋 여덟이 전부 저자를 이름으로 대고
있고(Piotroski·Sloan·Novy-Marx · Lynch · Minervini · Weinstein · Connors · 저변동성 이상현상),
문헌을 못 댄 프리셋이 실제로 두 번 폐기됐다.

Spiroglou 논문의 7국면 중 **Rebounding**(`50 > X > -150` 이면서 시그널선 위)이 ①의 묘사와
정확히 겹쳤다. 논문이 그것을 강세 진입 국면으로 지목한다.

## 측정 결과 (2026-08-31 단면 · 편입 1,350종목)

### 조건별 통과율 — 열두 칸 어디도 90%를 넘지 않는다

| 조건 | KOSPI(200) | KOSDAQ(150) | US(1,000) |
|---|---|---|---|
| ① `MACD_V_HIST_CHG_W > 0` | 72.5% | 74.7% | 37.2% |
| ② `MACD_V BETWEEN -150 AND 50` | 80.5% | 81.3% | 68.0% |
| ③ `MACD_V_HIST > 0` | 69.5% | 60.0% | 30.4% |
| ④ `OBV_T_13 > 1.96` | 40.0% | 28.0% | 23.2% |
| **넷 모두** | **43** | **21** | **35** |

**화면에서 실제로 돌린 결과가 셋 다 예측과 같았다**(2026-09-02 브라우저 실측).

### 네 조건이 서로 다른 것을 잰다 — 순위상관 여섯 쌍 (n=1,259)

`HIST~CHG_W` **0.772**(최대) · `HIST~OBV` 0.653 · `MACD_V~OBV` 0.573 · `CHG_W~OBV` 0.493 ·
`MACD_V~CHG_W` 0.399 · `MACD_V~HIST` 0.385. **여섯 쌍 다 0.8 미만.**

②와 ③도 중복이 아니다 — `corr(MACD_V, MACD_V_HIST) = 0.364`이고 **밴드가 통과시키는데 ③이
거르는 것 642 · ③이 통과시키는데 밴드가 거르는 것 212.**

### 형제 프리셋과 겹치지 않는다

기존 추세 프리셋 셋의 조건을 SQL로 **재구성**해 같은 날 대조했다(엔진 출력이 아니라 손으로 옮긴
재구성이다 — 자릿수 대조용):

| | 종목 수 |
|---|---|
| 3중창 반등 | **99** |
| ∩ 상승추세 눌림목 | **0** |
| ∩ 2국면 돌파 | **0** |
| ∩ 추세 상승 | 9 |
| **셋 중 어디에도 없는 것** | **90 (91%)** |

기제도 설명된다 — 눌림목은 `RSI_2 < 10`(이틀짜리 극단)이라 **다른 순간**을 재고, 2국면 돌파는
52주 고점 10% 이내라 **이미 돌파한 뒤**이며, 추세 상승은 완전 정배열을 요구한다. 이 프리셋은
**그 셋보다 앞선 자리**를 잡는다.

### 과열 배제 후보를 전부 재봤다 — 대부분이 안 자른다

조건을 하나씩 더해 몇이 줄어드는가(= 그 조건이 자르는 수):
`RSI_14 < 70` **0** · 주봉 `MACD_V_W < 150` **0** · 주봉 `ICHI_OBV_T < 10` 1 · `ADX_14 < 25` 7 ·
`DISPARITY_200 < 1.25` 19 · `PCT_FROM_52W_HIGH <= -10` 20 · **밴드 상한 `MACD_V < 50` 93**.

**사용자 요청 ②의 답이 이 표다** — 밴드 상한이 이미 그 일을 하고 있고, 자르는 것이 0인 조건을
필수 게이트에 두는 것은 이 저장소가 결함으로 잡는 형태다(ADR-0070의 전제가 그것을 신고한다).

### 주봉 조건의 결손은 원인이 셋이다 — 「상장 4년 미만」이 아니다

`FILTER`는 판정불가를 통과시키지 않으므로 커버리지가 그대로 탈락이 된다.

| 지표 | KOSPI | KOSDAQ | US |
|---|---|---|---|
| `MACD_V`·`MACD_V_HIST`·`OBV_T_13` | 100% | 99.3% | 100% |
| **`MACD_V_HIST_CHG_W`** | **91.0%** | **88.0%** | **94.5%** |

결손 **91종목을 전수로 갈랐다**(2026-09-02):

| 갈래 | 수 | 무엇인가 |
|---|---|---|
| 봉이 1,000개 미만 | **86** | 주봉 200주를 못 채운다 |
| 봉은 1,000개 이상인데 값이 없음 | **4** | 넷 다 `UNSUPPORTED` 기업행위를 갖고 있다 — ADR-0055가 장기창 지표를 억제하는 것이고 **옳은 동작이다** |
| 최신 거래일 봉 자체가 없음 | **1** | `WSO.B` — 마지막 봉이 2026-08-28이라 그날 지표가 하나도 없다 |

**`stock.listed_on`은 91종목 전부 NULL이다** — 「상장 4년 미만」은 잰 것이 아니라 추론이었다.
`description`의 그 문장을 「4년치 일봉이 있어야 계산된다」로 고쳤다. Phase 7 축②의
*"지표 이름이 그 값이 실제로 세는 것을 말한다"*가 이 결함을 잡았다.

### 결과 수는 고정된 성질이 아니다 — 13거래일

| 거래일 | 08-13 | 08-14 | **08-17** | 08-18 | 08-19 | 08-20 | 08-21 | 08-24 | 08-25 | 08-26 | 08-27 | 08-28 | 08-31 |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| 합계 | 315 | 328 | **99** | 254 | 207 | 167 | 149 | 129 | 123 | 113 | 111 | 97 | 99 |
| KOSPI | 88 | 98 | **0** | 85 | 69 | 56 | 45 | 39 | 34 | 35 | 36 | 39 | 43 |
| KOSDAQ | 95 | 96 | **0** | 83 | 73 | 51 | 46 | 40 | 37 | 30 | 27 | 23 | 21 |
| US | 132 | 134 | **99** | 86 | 65 | 60 | 58 | 50 | 52 | 48 | 48 | 35 | 35 |

**08-17은 국내 휴장이라 미국 단독 값이다** — 합계만 보면 급락으로 읽히지만 국내는 그날 단면
자체가 없다. **MUST NOT: 달력이 다른 두 계열을 한 줄로 요약하지 않는다**(ADR-0093과 같은 함정).

**「단조 감소」가 아니다** — 315→328과 97→99에서 두 번 반등한다. 범위는 **97~328**이고, 이
프리셋이 재려는 것이 국면이므로 그 흔들림이 설계다. **주봉이 진행 중인 주를 포함하므로 주중
변동이 따로 섞여 있고, 그 둘은 갈라 재지 않았다.**

## 사용자가 고른 문장과 그에 대한 내 해석

**갈라 적는다** — 붙여 적으면 다음 세션이 내 해석을 사용자 결정으로 읽는다.

| 사용자가 고른 문장 | 내 해석 |
|---|---|
| 「교차 직후(문헌 정의)」 | 「교차로 다가가고 있는」을 별도 조건으로 만들지 않는다. Spiroglou의 국면 정의에 그 자리가 없고 실측 5종목(코스닥 0)이었다 |
| 「t > 1.96」 | OBV 매집 임계를 95% 유의 구간으로 둔다. 원문에 없는 임계이므로 출처 갈래를 「원문에 없는데 더한 것」으로 적는다 |
| 「더 안 넣는다」 | 과열 배제 조건을 추가하지 않는다. 밴드 상한이 이미 93을 자르고 후보 대부분이 0~1을 자른다는 실측 위에서 고른 것이다 |

**이 셋 어디에도 「다시 묻지 마라」는 없다.** 시장 국면이 바뀌면 위 실측이 달라지고, 그때 다시
묻는 것이 맞다.

## 버린 대안

- **「교차 직전」 변형** — 「골든크로스로 다가가고 있는」을 조건화하는 안. 실측 5종목(코스닥 0)이고
  Spiroglou의 국면 정의에 그 자리가 없다. 사용자가 「교차 직후」를 골랐다.
- **추가 과열 배제 조건** — 위 표대로 자르는 것이 0이거나 한 자리다. 자르는 것이 0인 조건을
  게이트에 두면 ADR-0070의 전제가 「게이트가 아니라 상수」로 신고한다.
- **`GOLDEN_CROSS_AGE`를 조건으로** — 커버리지 7~33%. 필수로 쓰면 대부분이 조용히 탈락한다.
- **`SCORING` + 가중치** — 형제 추세 프리셋 셋이 전부 `FILTER`다. 배점이 전부 같으면 점수 막대가
  아예 안 그려지는 문제도 있다(ADR-0061 §결과).
- **스윙 명세(`docs/external/screener-spec-midterm-swing.md`)의 조건** — 그 명세는 스스로
  *"모든 임계값은 캘리브레이션 초안"*이라 적고 §9가 *"적으면 T-04를 1.30으로 완화"*를 처방한다.
  **후자는 이 저장소가 금지한 형태**이고(ADR-0071·0072), 그 명세를 출처로 쓰는 것이 ADR-0074가
  프리셋을 걷은 바로 그 이유다.
- **`GateDiagnosticsTest.PRESETS`에 등재** — 필수 게이트가 없는 `FILTER`라 그 진단 축이 겨누는
  대상이 아니고 `backtestProbe`는 이미 37분 55초다.

## 결과

- **테스트베드가 생겼다.** 이 프리셋이 뽑는 99종목 중 90(91%)이 형제 셋 어디에도 없어, 새 오실레이터
  파생(ADR-0119)이 실제로 다른 것을 재는지 볼 자리가 된다.
- **결과 수가 날마다 크게 흔들린다.** 97~328이고 0건인 날이 있을 수 있다. `SWING_MIDTERM_KR` 0건을
  실패로 읽지 않는다는 판단이 여기에도 걸린다 — **임계값부터 풀지 않는다.**
- **되돌리려면** `PresetCatalog`의 `TRIPLE_SCREEN_REBOUND` 덩어리와 `extraColumns` 항목,
  `screenPresets()`의 등재, ADR-0074의 개수 펜스(9 → 8)를 함께 되돌린다. 폐기는 삭제가 아니라
  `is_active = false`다(ADR-0017).
