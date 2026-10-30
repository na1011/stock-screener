---
id: ADR-0068
status: 채택됨
date: 2026-08-18
applies_to:
  - "src/main/java/**/screening/preset/PresetCatalog.java"
  - "docs/external/워렌버핏-방법론.md"
---

# ADR-0068. 버핏 프리셋에서 질은 문이고 가격은 저울이다 — 배치를 원저에서 다시 유도한다

## 결정

`BUFFETT_QUALITY`의 **게이트와 배점 배치**를 다시 정한다. ADR-0067이 정한 배치를 대체한다.

- **`ROIC_PRETAX ≥ 15`를 점수에서 필수 게이트로 올린다** — 2019년 주주서한의 **첫 번째** 기준이다.
  게이트가 여섯이 되고 통과가 19 → **14종목**이 된다.
- **가격은 게이트로 올리지 않고 배점의 중심으로 옮긴다** — 배점 16 중 **7(44%)**.
  (2026-08-18 시점의 조성이다. ADR-0083이 이익소각 배점 1을 더해 지금은 **17 중 7(41%)**이고,
  **이 줄은 안 고친다** — 고치면 이 문서가 자기가 안 한 결정을 주장하게 된다.)
- **게이트가 이미 재는 것과 순위상관 ρ ≥ 0.8인 점수 조건을 뺀다.** 다섯이 빠지고 배점 총합이
  28 → 16이 된다.

**외부 문서(`docs/external/워렌버핏-방법론.md`)는 조건의 출처일 뿐이며 조항별로 재판정했다**
(2026-08-18 사용자 결정 — *"이건 정본이 아니라 외부 세션에 맡긴 설계서다"*).

## 규칙

- **MUST: 게이트와 점수의 구분에 원저 근거를 댄다.** 「무엇이 문이고 무엇이 저울인가」는 조건의
  출처와 **다른 질문**이다 — 조건마다 출처가 있어도 배치가 틀리면 방법론이 아니다. ADR-0067은
  조건의 출처를 갈랐지만 배치는 따지지 않았고, 그래서 **통과 종목이 시장보다 비싼데도 어떤 검사도
  울지 않았다**(게이트는 전부 90% 미만이었고 배점에도 규칙 위반이 없었다).
- **MUST NOT: 버크셔 인수 기준 6항을 「가격도 AND의 한 항」의 근거로 쓰지 않는다.** 그 조항은
  *"제시 가격이 명확할 것"* — **존재 조건이지 수준 조건이 아니다.**
- **MUST NOT: 가격을 필수 게이트로 올리지 않는다.** 실측이 그것을 막는다 — §배경.
- **MUST: 점수 조건 사이의 순위상관을 재고, 게이트가 재는 것과 ρ ≥ 0.8이면 뺀다.** 잣대를
  **전 조건에 같게** 적용한다 — 초안이 `ROA`(ρ 0.832)를 빼면서 더 겹치는 `ROE`(ρ 0.870)를 남긴
  것이 그 실패다. **축에 이름을 여섯 개 붙이는 것은 측정이 아니다.**
- **MUST: 게이트끼리의 겹침을 독립 가정 대비 배수로 신고한다.** `ROE_MIN_3Y ≥ 10` ∧
  `ROIC_MIN_3Y ≥ 10`이 **4.47배**(교집합 41 · 독립 가정 9.2)다. **겹치는데도 두기로 했으면 그
  배수를 남긴다** — 다음 세션이 게이트 개수를 안전장치 개수로 읽지 않게.
  **그리고 그 배수가 임계를 정한다.** ROIC 게이트를 연간 3년 최저로 옮기면서(ADR-0073) 두
  게이트의 기저가 같아져 ρ가 0.870 → **0.882**로 올랐고, 임계를 12·15로 두면 배수가 5.61·6.30이
  되어 아래 펜스의 5.0을 넘는다. **MUST NOT: 임계를 올리려고 그 5.0을 손대지 않는다** — 두
  게이트가 하나가 됐다는 신호를 보고도 잣대를 느슨하게 하면 실패할 수 없는 펜스가 된다.
- **이 문서의 「게이트가 재는 것과 ρ ≥ 0.8이면 뺀다」에 열린 위반이 하나 있다 — 안 고쳤다.**
  게이트 `DEBT_RATIO ≤ 100`과 배점 `DEBT_RATIO ≤ 50`은 **같은 지표**라 ρ = 1.0이고 통과 집합이
  **완전히 포함된다**(2026-08-20: 둘 다 131 · 게이트만 66 · **배점만 0**). 그 배점은 게이트를
  통과한 종목을 **다시 같은 축으로** 줄 세운다.
  **MUST NOT: 「의도된 단계 배점이다」로 닫지 않는다** — 이 문서 어디에도 그 의도가 없고 위 MUST는
  예외를 두지 않았다. **빼는 것이 ADR-0083의 범위 밖인 이유**는 배점 총합이 17 → 16이 되어
  「배점 N 중 7」 표기와 그 사본 전부에 파급되기 때문이고, 그것은 별도 판단이다.
- **MUST NOT: 인용을 절단하지 않는다.** 문서 §4.3의 원문은 *"버핏은 PER 자체를 절대 기준으로 쓰지
  않지만 **1차 관문으로는 유효하다**"* 이고, 뒷절을 떼면 단서 붙은 진술이 자기모순으로 바뀐다.
  같은 문장이 여러 지표를 가리키면(§1.3의 *"결과이지 원인이 아님"*이 `Gross Margin`과 `ROIC`를
  함께 가리킨다) **그 잣대를 전부에 적용한다.**
- **MUST NOT: 문서 §8의 완화 순서를 우선순위로 읽지 않는다.** *"0종목일 경우"* 푸는 지침이라
  **결과 수로 임계를 푸는 행동**이고 ADR-0061이 금지한다.
- **MUST NOT: 0건 예외를 부여하지 않는다.** 그것은 사용자 결정이고 후계 프리셋에 자동 승계되지
  않는다(ADR-0044). 0건이면 게이트별 단독 통과 수와 누적 교집합을 내고 원인을 ① 조건 ② 데이터
  ③ 국면으로 갈라 **보고한다.**
- **MUST NOT: 성패 기준을 밸류에이션 쪽에 두지 않는다.** 밸류에이션 조건을 건 뒤 *"통과 집합이
  싼가"*를 묻는 것은 **정의상 참**이라 실패할 수 없는 펜스다. 기준은 **질 쪽**(매출총이익률·ROIC
  중앙값이 유니버스보다 높은가)에 둔다.

## 검증

```bash
rg -n 'PerCalculator|RoaCalculator|NetMarginCalculator' \
   src/main/java/com/stockscreener/screening/preset/PresetCatalog.java | rg BUFFETT
```
버핏 프리셋 블록에서 아무것도 반환하지 않아야 한다 — 셋 다 게이트와 ρ ≥ 0.8이라 뺐다.

```premise 버핏 프리셋이 유니버스보다 해자가 강한 집합을 낸다
psql -d stock_screener -At -c "WITH d AS (SELECT max(trade_date) t FROM indicator_trade_date WHERE market_code IN ('KOSPI','KOSDAQ')),
w AS (SELECT stock_id,
        max(value) FILTER (WHERE indicator_code='ROE_MIN_3Y') roe3,
        max(value) FILTER (WHERE indicator_code='PROFIT_STREAK_Y') streak,
        max(value) FILTER (WHERE indicator_code='DEBT_RATIO') dr,
        max(value) FILTER (WHERE indicator_code='ROIC_MIN_3Y') roic,
        max(value) FILTER (WHERE indicator_code='GROSS_MARGIN') gm,
        max(value) FILTER (WHERE indicator_code='MARKET_CAP') mc,
        max(value) FILTER (WHERE indicator_code='TURNOVER_60') tv
      FROM (SELECT iv0.* FROM indicator_value iv0 JOIN stock s0 ON s0.id = iv0.stock_id JOIN universe_membership m0 ON m0.stock_id = s0.id AND m0.effective_to IS NULL JOIN universe u0 ON u0.code = m0.universe_code AND u0.is_active WHERE s0.market_code IN ('KOSPI','KOSDAQ')) iv, d WHERE iv.trade_date = d.t GROUP BY 1),
g AS (SELECT gm FROM w WHERE streak>=7 AND roe3>=10 AND dr<=100 AND roic>=10
        AND mc>=1000000000000 AND tv>=10000000000 AND gm IS NOT NULL)
SELECT CASE
  WHEN (SELECT count(*) FROM w WHERE gm IS NOT NULL) = 0
    THEN '잴 것이 없었다 — 매출총이익률 값이 하나도 없다'
  WHEN (SELECT count(*) FROM g) = 0
    THEN '잴 것이 없었다 — 게이트 여섯을 통과한 종목이 없다(0건은 갈라서 보고할 일이다)'
  WHEN (SELECT percentile_cont(0.5) WITHIN GROUP (ORDER BY gm) FROM g)
       > (SELECT percentile_cont(0.5) WITHIN GROUP (ORDER BY gm) FROM w WHERE gm IS NOT NULL)
    THEN 'OK 통과 ' || (SELECT count(*) FROM g) || '종목 매출총이익률 중앙값 '
         || round((SELECT percentile_cont(0.5) WITHIN GROUP (ORDER BY gm) FROM g)::numeric,1)
         || ' > 유니버스 '
         || round((SELECT percentile_cont(0.5) WITHIN GROUP (ORDER BY gm) FROM w WHERE gm IS NOT NULL)::numeric,1)
  ELSE '해자 축이 무너졌다 — 통과 집합의 매출총이익률 중앙값이 유니버스 이하다. '
       || '가격 축이 목록을 코모디티 쪽으로 끌고 갔는지 본다'
  END"
```

**이 펜스가 이 결정의 성패다.** 가격 조건을 조이면 통과 집합이 싸지는 것은 정의상 참이라
검증이 되지 못하고, **해자가 유지되는가**만 실패할 수 있다.

```premise 버핏 게이트끼리의 겹침이 신고한 배수 근처에 있다
psql -d stock_screener -At -c "WITH d AS (SELECT max(trade_date) t FROM indicator_trade_date WHERE market_code IN ('KOSPI','KOSDAQ')),
w AS (SELECT stock_id,
        max(value) FILTER (WHERE indicator_code='ROE_MIN_3Y') a,
        max(value) FILTER (WHERE indicator_code='ROIC_MIN_3Y') b
      FROM (SELECT iv0.* FROM indicator_value iv0 JOIN stock s0 ON s0.id = iv0.stock_id JOIN universe_membership m0 ON m0.stock_id = s0.id AND m0.effective_to IS NULL JOIN universe u0 ON u0.code = m0.universe_code AND u0.is_active WHERE s0.market_code IN ('KOSPI','KOSDAQ')) iv, d WHERE iv.trade_date = d.t GROUP BY 1),
n AS (SELECT count(*) FILTER (WHERE a IS NOT NULL AND b IS NOT NULL) tot, count(*) FILTER (WHERE a>=10) na,
             count(*) FILTER (WHERE b>=10) nb,
             count(*) FILTER (WHERE a>=10 AND b>=10) nab FROM w)
SELECT CASE
  WHEN (SELECT na FROM n) = 0 OR (SELECT nb FROM n) = 0
    THEN '잴 것이 없었다 — 두 게이트 중 하나를 통과하는 종목이 없다'
  WHEN (SELECT nab::numeric / (na::numeric * nb / tot) FROM n) <= 5.0
    THEN 'OK 겹침 ' || round((SELECT nab::numeric / (na::numeric * nb / tot) FROM n),2)
         || '배 (신고값 4.47)'
  ELSE '겹침이 신고값에서 크게 벗어났다: '
       || round((SELECT nab::numeric / (na::numeric * nb / tot) FROM n),2)
       || '배. 두 게이트가 사실상 하나가 됐는지 본다'
  END"
```

모집단은 **두 게이트 값이 다 있는 종목**이다 — 겹침 배수는 판정이 성립하는 종목 사이의 독립성을 재는 것이다. 「아무 지표나 있는 종목」으로
세면 재무 지표가 막힌 종목(새 실적 발표 대기 · ADR-0195 · 시세 지표는 있다)이 분모에만 들어가 배수가 기계적으로 커진다(2026-10-31:
분모 350 → 5.84배 · 분모 233 → 3.89배).

## 배경

**ADR-0067의 배치가 만든 결과**(2026-08-13 기준일, 유니버스 347): 통과 19종목의 **PER 중앙값
23.2 · PBR 4.81**로 시장(18.6 / 2.10)보다 비쌌다. 밸류에이션이 배점 28 중 4라 순위를 정하지
못했고, 최상위 종목이 두 밸류에이션 조건을 **둘 다 불충족**한 채 1위였다.

**가격을 게이트로 올리는 것이 첫 수정안이었고 실측이 그것을 무너뜨렸다.** `FCF_YIELD ≥ 5`를
여섯째 게이트로 두면 5종목이 남고 PER 중앙값이 9.1로 떨어지지만:

| 무엇 | 실측 |
|---|---|
| **해자가 무너진다** | 통과 5종목의 매출총이익률이 **9.4 / 14.0 / 19.7** / 36.9 / 77.5 — 셋이 문서 §5.2의 코모디티 배제선(30) 아래이고 전부 자동차 체인이다. 반면 그 게이트 없는 19종목의 중앙값은 **44.8** |
| **최대 오염원이 커진다** | `FCF_YIELD ≥ 5` 통과율이 **금융·보험(KSIC 64·65·66) 48.0% vs 그 외 25.3%**. 문서 §10이 지주회사를 「가장 오염이 심한 구간」으로 지목했고 우리는 그것을 제외하지 못한다(ADR-0066) |
| **저PBR 쪽으로 쏠린다** | ρ(FCF수익률, PBR) = **−0.338**. AAII가 `P/FCF`를 **성장률로 나눈** 이유가 그 보정인데 게이트는 분자만 쓴다 |

**그 가설을 지지하려고 든 근거 하나도 틀렸다.** *"ROIC를 게이트로 넣으면 PER 중앙값이 9.1 →
11.8로 올라 안전마진과 반대로 작동한다"* 고 적었는데, 그것은 표본이 5 → 3으로 줄 때의 중앙값
이동이었다. 모집단에서 재면 **ρ(ROIC, PER) = −0.163 · ρ(ROIC, PBR) = +0.021** — **고ROIC는 오히려
약간 싸다.** n = 3의 중앙값은 관측치 하나다.

**계량 구현체도 가격을 랭크로 쓴다.** AAII Buffett-Hagstrom은 `P/FCF ÷ FCF 성장률`이 낮은 순으로
30개를 뽑는다 — 문턱이 아니라 **정렬**이다. 1989년 주주서한의 *"fair price에 wonderful company를
사는 것이 낫다"* 도 **두 축의 교환율**을 말하는 문장이다.

**점수 조건이 서로 다른 것을 재지 않고 있었다**(순위상관, 최신 거래일):

| 짝 | ρ |
|---|---|
| `ROIC_PRETAX` ~ `ROE` | **0.870** |
| `OPERATING_MARGIN` ~ `OPERATING_MARGIN_SECTOR_PCTL` | **0.866** |
| `OPERATING_MARGIN` ~ `NET_MARGIN` | **0.857** |
| `ROIC_PRETAX` ~ `OPERATING_MARGIN` | **0.836** |
| `ROIC_PRETAX` ~ `ROA` | **0.832** |
| `DEBT_RATIO` ~ `DEBT_RATIO_SECTOR_REL` | 0.744 |
| `FCF_YIELD` ~ `FCF_MARGIN` | 0.737 |
| `ROIC_PRETAX` ~ `GROSS_MARGIN` | **0.332** ← 낮다. 남긴다 |

마진·수익성이 한 덩어리로 배점 28 중 12를 먹고 있었다. **축 이름이 여섯이어도 실제로는 두
덩어리였다.** `GROSS_MARGIN`만 남기는 이유는 ρ가 0.332로 낮고, 위 표가 보여 주듯 **그 축이
무너질 때 목록이 통째로 무너지기 때문**이다.

**`ROIC_PRETAX ≥ 15`는 새로 만든 수가 아니다.** ADR-0067이 *"원문 12는 세후이고 우리 지표는
세전이라 같은 수를 쓰면 원문보다 느슨해진다"* 는 근거로 점수 조건에 등록한 값이고, 그것을
게이트로 올리는 것이다. 유니버스 통과가 69/347(20%)이라 상수가 아니다.

**이 프리셋을 참조하는 워치리스트는 0건이다**(실측 — 참조가 있는 것은 `VALUE` 1건뿐).
`PresetSynchronizer`는 조건을 통째로 교체하면서 `last_satisfied`를 되돌리지 않으므로(리셋은
`screen_id`가 바뀔 때만) 참조가 생기면 그때 이 자리를 다시 본다.

## 버린 대안

- **가격을 게이트로 올리기** — §배경. 버린 것이 아니라 **해 보니 틀렸다.**
- **5년 평균 잉여현금흐름 수익률을 만들기** — 버핏의 *"3~5년 평균으로 보라"*에 가장 충실하고
  자본적지출이 FY2021~2025로 적재돼 있어 계산할 수 있다. **커버리지가 227/347(65%)** 이라
  게이트로 두면 3분의 1이 데이터 사유로 탈락하고(린치 `EPS_CAGR_5Y`가 같은 이유로 필수에서
  점수로 내려간 선례), 점수로 두면 단년과 ρ 0.642로 겹친다. **단년이 투자 사이클에 흔들린다는
  것은 결손으로 적는다.**
- **`ROIC_PRETAX`를 점수에 두고 게이트를 다섯으로 유지** — 겹침 3.3배를 피할 수 있었다.
  2019 주주서한이 그것을 첫 기준으로 들고 실제로 19 → 14로 자른다는 것을 알고 **사용자가 게이트를
  골랐다**(2026-08-18).
- **`PER`·`PBR`을 `extraColumns`로 내리기** — **무동작이다.** 둘 다
  `@IndicatorMeta(defaultColumn = true)`라 이미 모든 결과 표에 나온다.

## 결과

통과 집합의 매출총이익률 중앙값이 **44.8 → 50.5**(유니버스 22.2)로 오르고, 배점에서 가격이
**4/28 → 7/16**이 되어 순위가 가격을 반영한다. 조건 수가 20 → 15로 줄어 조건 내역 화면이 읽힌다.

**여전히 남는 것**: 통과 집합은 시장보다 **비싸다**(질 좋은 회사가 프리미엄에 거래되므로). 이
프리셋은 「살 회사」가 아니라 **「살 만한지 볼 회사」의 순위**이며, 그 순위에서 가격이 가장 큰
몫을 갖는다. 섹터 제외·정성 항목의 결손은 ADR-0067이 적은 그대로다.

되돌리려면 `PresetCatalog`에서 `ROIC_PRETAX`를 점수로 내리고 뺀 조건 다섯을 되살린다. 지표·적재·
스키마는 이 결정과 무관하다.
