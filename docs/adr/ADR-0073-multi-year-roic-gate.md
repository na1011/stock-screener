---
id: ADR-0073
status: 채택됨
date: 2026-08-19
applies_to:
  - "src/main/java/**/indicator/calculator/RoicMin3yCalculator.java"
  - "src/main/java/**/screening/preset/PresetCatalog.java"
---

# ADR-0073. 버핏 게이트의 ROIC를 단년 TTM에서 연간 3년 최저로 내린다

## 결정

`ROIC_MIN_3Y`(연간 영업이익 ÷ 그 해 기말 투하자본, 3개 회계연도 최솟값)를 만들고,
`BUFFETT_QUALITY_KR`의 필수 게이트를 `ROIC_PRETAX ≥ 15`에서 **`ROIC_MIN_3Y ≥ 10`**으로 바꾼다.
`ROIC_PRETAX` 지표 자체는 남긴다.

## 규칙

- **MUST: 연간 행 하나에서 분자와 분모를 함께 뽑는다.** 연간 `operating_income`은 이미 그
  회계연도 전체이고 `total_assets`·`current_liabilities`는 그 해 기말 잔액이다.
  `FinancialMath.sumTrailingFourQuarters`를 연간 리스트에 쓰면 **4년 누적 이익 ÷ 1년 자본**이
  되는데 값이 그럴듯해 드러나지 않는다.
- **MUST: 투하자본이 0 이하인 해를 거른다.** 자산이 유동부채보다 작으면 영업적자가 **양수
  ROIC**로 나온다. `FinancialMath.percentage`는 음수 분모를 거르지 않는다.
- **MUST NOT: 임계 10을 세전 보정값 15에서 이어진 수로 읽지 않는다.** 이 수는 **두 번 옮겼다** —
  원문 12(세후)를 세전 기저에서 15로 올렸고(ADR-0067), 다시 기저를 단년 TTM에서 연간 3년
  최저로 바꾸며 10으로 내렸다. **방향이 반대이므로 한쪽만 알고 읽으면 반드시 틀린다.**
- **MUST NOT: 임계를 12나 15로 올리려고 ADR-0068의 겹침 펜스(5.0)를 손대지 않는다.** 그 임계에서
  배수가 5.61·6.30이다. 두 게이트가 하나가 됐다는 신호를 보고 잣대를 느슨하게 하면 **실패할 수
  없는 펜스**가 된다.
- **MUST: 미국 프리셋을 만들 때 이 임계를 복사하지 않고 그 시장 분포로 다시 잰다.** 프리셋 코드의
  `_KR` 접미사가 구조는 갈라 두었으므로 **갈리지 않는 것은 수뿐이고, 그것은 복사할 때 사람이
  지킨다.** 원문 값을 그대로 옮기면 안 되는 이유(ADR-0061)가 시장 사이에도 그대로 걸린다.
  지표 계산기는 시장 중립이라 고칠 것이 없다.

## 검증

```premise 다년 ROIC 게이트를 재료 부족이 막지 않는다
psql -d stock_screener -At -c "WITH d AS (
  SELECT max(trade_date) t FROM indicator_value WHERE indicator_code = 'ADJ_CLOSE'),
w AS (SELECT stock_id,
        max(value) FILTER (WHERE indicator_code='ROE_MIN_3Y') roe3,
        max(value) FILTER (WHERE indicator_code='PROFIT_STREAK_Y') streak,
        max(value) FILTER (WHERE indicator_code='DEBT_RATIO') dr,
        max(value) FILTER (WHERE indicator_code='MARKET_CAP') mc,
        max(value) FILTER (WHERE indicator_code='TURNOVER_60') tv,
        max(value) FILTER (WHERE indicator_code='ROIC_MIN_3Y') roic3,
        max(value) FILTER (WHERE indicator_code='ROIC_PRETAX') roic1
      FROM indicator_value iv, d WHERE iv.trade_date = d.t GROUP BY 1),
n AS (SELECT count(*) FILTER (WHERE roic1 IS NOT NULL) c1,
             count(*) FILTER (WHERE roic3 IS NOT NULL) c3,
             count(*) FILTER (WHERE roe3>=10 AND dr<=100 AND streak>=7
                              AND mc>=1000000000000 AND tv>=10000000000) g5,
             count(*) FILTER (WHERE roe3>=10 AND dr<=100 AND streak>=7
                              AND mc>=1000000000000 AND tv>=10000000000
                              AND roic3 IS NULL) g5_novalue FROM w)
SELECT CASE
  WHEN (SELECT c3 FROM n) = 0
    THEN 'ROIC_MIN_3Y 값이 하나도 없다 — 지표가 사라졌거나 적재가 안 됐다'
  WHEN (SELECT g5 FROM n) = 0
    THEN '잴 것이 없었다 — ROIC 앞의 게이트 다섯을 통과한 종목이 없다(0건은 갈라서 보고할 일이다)'
  WHEN (SELECT g5_novalue FROM n) = 0
    THEN 'OK 커버리지 ' || (SELECT c3 FROM n) || '종목(ROIC_PRETAX ' || (SELECT c1 FROM n)
         || '), 앞 게이트 통과 ' || (SELECT g5 FROM n) || '건 중 재료 부족 0'
  ELSE '앞 게이트를 통과했는데 ROIC_MIN_3Y 값이 없어 탈락하는 종목이 '
       || (SELECT g5_novalue FROM n) || '건이다 — 목록이 준 원인이 게이트가 아니라 재료다. '
       || '3년 연속 결산이 빠졌는지 먼저 본다'
  END"
```

**이 펜스가 잡는 것은 커버리지다.** ADR-0067의 「게이트가 유니버스의 90%를 넘게 통과시키지
않는다」는 **한쪽만** 본다 — 통과가 0이면 `0 > 0.90`이 거짓이라 **OK를 낸다.** 아무도 통과하지
못하는 상태를 조용히 통과시키므로, 재료가 사라지는 방향은 그쪽으로 잡히지 않는다.

겹침 배수와 해자 축은 **ADR-0068의 펜스 둘이 이미 재고 있다**(같은 커밋에서 새 게이트를 가리키게
고쳤다). 여기에 사본을 만들지 않는다.

## 배경

버핏 프리셋의 질 게이트 셋 중 `ROE_MIN_3Y`·`PROFIT_STREAK_Y`는 다년 기준인데 ROIC만 단일 시점
TTM이었다. 그래서 목록에 들고 나는 것이 사업의 변화가 아니라 **분기 공시의 잡음**에서 왔다.
같은 모집단·같은 단위(종목·연)로 임계 교차율을 재면 **12~20%(분기 TTM ≥15)** 대
**9.4/13.1/7.4/5.5%(3년 최저 ≥10)**다.

임계는 셋을 재고 골랐다(기준일 2026-08-18, 유니버스 347).

| 임계 | 유니버스 통과 | 게이트 뒤 | `ROE_MIN_3Y`와의 겹침 | 연간 임계 교차율 |
|---|---|---|---|---|
| `ROIC_PRETAX ≥ 15`(현행) | 74/316 (23%) | 13 | 2.92배 | 16.0/19.9/12.2/11.9% |
| **`ROIC_MIN_3Y ≥ 10`** | **60/300 (20%)** | **16** | **4.47배** | **9.4/13.1/7.4/5.5%** |
| `ROIC_MIN_3Y ≥ 12` | 42/300 (14%) | 15 | 5.61배 ✗ | 5.7/9.8/5.6/4.5% |
| `ROIC_MIN_3Y ≥ 15` | 26/300 (9%) | 10 | 6.30배 ✗ | 3.2/6.2/3.4/3.4% |

**겹침이 임계를 정했다.** 두 게이트의 기저가 같아지면서 ρ가 0.870(`ROIC_PRETAX`~`ROE`) →
**0.882**로 올랐고, 12·15는 ADR-0068의 5.0 펜스를 넘는다. 회전만 보면 12가 낮지만 그 차이를
사려고 펜스를 올리면 안 된다.

점수 조건에는 뺄 것이 없다. 새 게이트와의 ρ가 `OPM_MIN_5Y` 0.786, `FCF_MARGIN` 0.425,
`FCF_YIELD` 0.354, `GROSS_MARGIN` 0.242로 전부 0.8 미만이다. `OPM_MIN_5Y`가 경계에 가까운 것은
둘 다 다년 최솟값 수익성이기 때문이고, 다음에 게이트를 만질 때 먼저 볼 자리다.

## 버린 대안

- **임계 12 + 펜스를 6.0으로 완화** — 회전이 가장 낮았다. 버린 이유는 위 MUST NOT과 같다.
- **임계 15 유지(수를 그대로 옮김)** — 3년 최저는 단년보다 구조적으로 낮아 **같은 수가 같은
  엄격도를 뜻하지 않는다.** 통과가 8.7%로 조이고 겹침이 6.30배다.
- **「같은 종목에서 같은 판정」을 임계의 기준으로 삼기** — 디스카운트 중앙값 3.0%p를 빼서 15→12를
  얻는 방식이다. **틀렸다.** 디스카운트가 수준에 의존해(하위 5분위 중앙 1.08 대 상위 5분위
  **14.54**) 전체 중앙값은 임계가 서는 자리의 값이 아니고, 12에서 현행 통과 74종목 중 **42종목의
  판정이 뒤집힌다**(자카드 최대 0.400 — 어느 임계로도 집합이 보존되지 않는다). 그리고 **애초에
  원하면 안 되는 기준이다** — 한미반도체(TTM 31.3 / 3년최저 5.6)와 제주반도체(38.9 / 5.1)를
  자르는 것이 이 변경의 존재 이유이고, 집합을 보존하는 임계는 그 둘을 남긴다.
- **`ROIC_PRETAX` 지표 삭제** — ADR-0061이 `ROE`를 조건에서만 빼고 지표는 남긴 선례가 있다.
  지우면 ADR-0068의 ρ 표와 `IndicatorUnavailabilityConfig`가 죽은 참조가 된다.

## 결과

**시점 격자로 확인했다**(`./gradlew backtestProbe --tests "*GateDiagnosticsTest*"`, 2022-04-01
~ 2026-07-01 분기 18시점 — ADR-0072와 같은 격자).

| | 교체 전(`ROIC_PRETAX ≥ 15`) | 교체 후(`ROIC_MIN_3Y ≥ 10`) |
|---|---|---|
| ROIC 게이트의 회전 귀속(진입+이탈) | 26 | **13** |
| 통과 집합 회전율(자카드 평균) | 0.33 | **0.276** |
| 전환 사건 수 | 62 | 60 |
| 임계가 앉은 분위의 이동 | — | 0.07(재무 게이트 넷과 같은 자릿수) |
| 아무도 못 자르는 게이트 | 없음 | **없음** |

**이제 회전의 최대 원인은 `TURNOVER_60`(35)이다.** 금액 임계가 시장을 안 따라가는 것이고,
그 수리는 ADR-0072가 막았다(분위로 바꾸면 시장마다 다른 절대 높이의 문이 된다). **알려진
결손으로 남는다.**

**쉬워지는 것**: 목록의 회전이 재무 공시 주기(연 1회)에 묶인다. 질 게이트 셋이 전부 다년 기준이
되어 「일회성 이익으로 튄 해」가 세 축 어디에서도 통과하지 않는다.

**어려워지는 것**: 두 게이트가 같은 것을 재는 정도가 커졌다(겹침 4.47배). 다음에 게이트를 하나
더 얹을 때 이 배수가 먼저 걸린다. 그리고 **3년 최저는 덜 자주 대신 크게 움직인다** — 가장 나쁜
해가 창을 벗어나면 값이 뛴다(연도별 절대 변화량 p90이 단년 TTM보다 크다).

**되돌리려면**: `PresetCatalog`의 게이트 한 줄과 그 javadoc 넷, ADR-0068의 premise 둘, 그리고
`ConditionReturnTest`·`PastUniverseProbeLiveTest`의 인용을 함께 되돌린다. 지표와 계산기는 남겨도
무해하다.
