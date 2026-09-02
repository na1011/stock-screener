---
id: ADR-0046
status: 채택됨
date: 2026-08-11
applies_to:
  - "src/main/java/**/indicator/calculator/ichimoku/**"
  - "src/main/java/**/indicator/calculator/WeeklyResampler.java"
  - "src/main/java/**/indicator/calculator/WeeklyBar.java"
  - "src/main/java/**/indicator/calculator/SeriesMath.java"
  - "src/main/java/**/indicator/IndicatorContext.java"
---

# ADR-0046. 지표 계층의 정밀도·주봉 경계·기준지수·센티널

주봉 일목 지표 31개를 TradingView Pine Script 원본에서 이식하며 정해진 규칙 중
**일목 밖 지표에도 걸리는 것**을 이 문서가 든다 — 내부 연산 정밀도, 주봉 경계 의미론,
기준지수 선택, 센티널 값의 취급, 그리고 원본과 다르게 옮긴 자리들.

## 결정

이 규칙들의 정본을 한 곳에 둔다. **소비자 대부분이 일목 밖이다** — `SeriesMath`(→ `OBV_T_13`·
`BETA_104W`·`PER_MEDIAN_5Y`) · `WeeklyResampler`(→ `BETA_104W`) · `IndicatorRunner` ·
`IndicatorContext` · `GoldenCrossAgeCalculator`가 전부 이 문서를 근거로 든다.

## 규칙

### 정밀도 — 내부 연산을 `double`로 한다

이 저장소의 관례는 `BigDecimal` + `HALF_UP`이지만 Pine은 float64이고 `math.round`가
**ties round up**(−1.5 → −1)이라 `HALF_UP`(−1.5 → −2)과 갈린다. **경계(`IndicatorPoint`)에서만
`BigDecimal`로 바꾼다.**

- **MUST NOT: `SeriesMath`를 `BigDecimal` + `HALF_UP`으로 바꾸지 않는다.** 그 안의 OLS
  t-통계량은 Pine의 이식원이 기준선이고, 지금은 일목 밖 셋이 같은 함수를 쓴다.
- **MUST: 잔차가 0일 때 t를 0으로 둔다.** 완전 직선인데 "유의하지 않음"으로 떨어지는 셈이지만
  이식원이 그렇게 한다.

### 승인된 이탈 — 원본과 다르게 옮긴 자리

**MUST NOT: 아래 넷 말고 기록 없는 이탈을 만들지 않는다.** 더 생기면 그때 이 ADR을 다시 연다.
**MUST NOT: 번호를 다시 매기지 않는다** — 15개 파일이 이 번호로 가리키고 그중 `V23`·`V25`는
Flyway 체크섬 때문에 고칠 수 없다.

1. **207 주봉 미만이면 행을 쓰지 않는다.** 원본의 `sufficient_data` 가드는 실제로 **117 주봉**만
   보장한다 — 소비는 **207 주봉**까지 가고, 그 사이 종목은 `nz()`가 0을 채워
   `close > max(0,0)`이 **항상 참**이 되어 **강세 방향 거짓 양성**이 나온다. 즉 **이력이 짧을수록
   게이트를 잘 통과한다.** 착수 시점 실측으로 이 밴드는 10종목이었다.

2. **철회됐다(2026-08-14). 철회는 유지되지만 근거가 갈렸다(2026-09-02).** 「시장 지수가
   결손이면 그날 `ICHI_*` 행을 하나도 쓰지 않는다」는 게이트다. 철회 당시의 근거는 D5에
   기대는 코드가 전부 삭제돼 **남은 18개 중 지수를 쓰는 것이 없다**는 것이었는데, ADR-0122가
   그 13개를 되살려 그 문장은 더 이상 참이 아니다.
   **그래도 게이트는 되살리지 않는다** — 관측 18개는 여전히 지수를 쓰지 않으므로 함께 막으면
   회귀다. 대신 **D5에 기대는 일곱만**(tier·signal·extSignal·confidence·conclusion·bull·bear)
   `DataRequirement.priceAndMarketIndex`를 선언하고 결손일에 `null`을 낸다.
   `IchimokuAnalyzerTest.suppressesOnlyTheIndexDependentSeven`이 그 비대칭을 잰다 —
   **지수를 준 대조군을 함께 단언한다.** 없으면 "언제나 null"인 구현도 초록이다.
   살아남은 규칙은 §지수 정확 일치로 옮겼다.
   **이 번호를 비워 두는 이유는 `V23__market_index.sql:31`이 아직 이것을 가리키고 있고
   적용된 마이그레이션이라 못 고치기 때문이다.**

3. **주봉 경계는 「진행 중인 주 포함」으로 모든 일자에 일관 적용한다.** Pine의
   `request.security(…, "W", …)`는 비대칭이라 **실시간 봉에서는 진행 중인 주, 과거 봉에서는
   마지막 확정 주**를 준다. Pine Screener는 마지막 봉만 평가하므로 사용자가 보는 것은 전자이고,
   그쪽에 맞춘다. **결과로 과거 일자는 TradingView *차트*와 값이 다르다 — 버그가 아니다.**
   - **이 이탈의 적용 범위는 일목이다.** `BETA_104W`는 같은 `WeeklyResampler`를 쓰되
     **진행 중인 주를 버린다**(ADR-0063) — 회귀의 입력이 확정 주여야 하기 때문이다.

4. **기준지수를 종목의 시장으로 가른다** — 코스피 종목은 코스피, 코스닥 종목은 코스닥.
   원본의 `market_idx_mode`는 선택지가 `["AUTO","KOSPI","SPX"]` 셋뿐이라 **코스닥이 아예 없고**,
   AUTO의 분기가 `syminfo.currency == "KRW"`라 **통화 검사로는 코스피/코스닥을 원리적으로
   표현할 수 없다**(둘 다 KRW다). **판단이 아니라 한계이며, 정보가 없어 생긴 폴백을 정보가 있는
   쪽에서 재현하는 것은 충실이 아니라 결함 복제다.** 두 지수의 26주 격차는 임계 ±5%를 예사로
   넘는다(거래일 67.9%, `data-go-kr-market-index.md` §기준지수 선택). 경위는 ADR-0053에 있다.
   **결과로 코스닥 종목은 TradingView와 값이 다르다.**

### 지수 정확 일치

- **MUST: 대상 일자의 지수를 정확 일치로 묻는다.** `floorEntry`로 직전 값을 집으면 그날 지수가
  없어도 값이 나오고, **상대 수익률이 절대 수익률로 조용히 바뀐다.** 전역 `CLAUDE.md`의 silent
  fail 금지와 ADR-0039의 "뜻이 다른 값을 같은 이름으로 부르지 않는다"가 원본 충실보다 우선한다.
- **예외 하나** — 130봉 전만 `floorEntry`를 쓴다. 종목의 그 날짜가 지수 휴장일과 어긋날 수 있기
  때문이고, Pine의 forward-fill과 같은 모양이다.

### 호소다 anchor

**MUST: 실효 anchor를 9개로 센다** — `9 · 17 · 26 · 33 · 42 · 52 · 65 · 76 · 129`.
`drv_51 ∈ [0,128]`이라 172·200·257은 도달 불가이지만 **129는 `drv_51 = 128`에서 ±1로 걸린다.**
빠뜨리면 `ICHI_MOD_E`(시간론 변화일)가 그 자리에서 뒤집힌다.

### 도달 불가 분기

**MUST: 도달 불가를 알고 옮긴다.** 모르면 검증할 수 없는 셀에 시간을 쓴다.

- `DRV-54`의 `n_54 = 26`은 비교 기준이 자기 자신이라 발화하지 않는다 — **실효 범위는 1~25다.**
  이것을 모르고 26을 재려 들면 항등식을 테스트하게 된다.

### 죽은 코드

**MUST NOT: 원본에서 죽은 코드를 옮기지 않는다.** 옮기면 검증 대상만 늘고 출력은 안 바뀐다.

- 일봉 블록에서 살아 있는 것은 `drv_43`(→`drv_45`)과 `drv_55` **둘뿐**이다.
  `px_*`·`atr_01`·`atr_02`·`vol_01`·`vol_02`·**`vol_05`(일봉 OBV)**·`ich_d_05`·`_06`·`_08`·`drv_48`이 죽었다.
  **살아 있는 OBV는 주봉 `obv_w` 하나다.**
- `vol_sma_w`·`drv_04`·`drv_49`, 그리고 반환만 되고 미소비인 `w_pa`·`w_pb`·`w_pc`·`w_pass34`·`w_drop`.

### 승인된 예외 — TradingView 원본 대조를 하지 않는다

**2026-08-13 사용자 판단이다.** `phase-criteria.md` §P12의 첫 축이 *"TradingView 실제 출력과
대조해 판정된다. 내부 일관성만으로는 이 phase가 끝나지 않는다"*를 명시하지만, **이 프리셋은
종목을 추천받는 물건이 아니라 지표를 늘리기 위한 테스트베드였다.** 그 전제에서는 대조 비용이
얻는 것보다 크다. 프리셋 자체는 2026-08-14에 폐기됐다(ADR-0061).

**대가를 명시한다 — "옮긴 것이 원본과 같다"고 말할 근거가 내부 일관성뿐이다.** 수식자는 전부
정수를 내므로 **부등호 하나가 뒤집혀도 값이 여전히 그럴듯하고 어떤 테스트도 깨지지 않는다.**

- **MUST NOT: 지표 31개의 값을 검증된 것으로 읽지 않는다.** 원본 대조를 한 적이 없다.
  **되살린 점수화 13개는 더 그렇다**(ADR-0122) — 52셀 매트릭스가 전부 정수라 셀 하나가
  틀려도 Tier는 여전히 1~4다.
- **MUST NOT: 이 지표들의 결과를 매매 판단의 단독 근거로 쓰지 않는다.**
- **MUST NOT: 대조를 재개하기로 하면 평일에 하지 않는다.** 위 이탈 3 때문에 평일에는 두 의미론이
  갈린다. **주말에는 TradingView의 마지막 주봉이 확정된 월~금이고 우리 `asOf = 금요일`과 같은
  봉이 된다** — 두 의미론이 일치하는 유일한 시점이라 그때만 불일치가 곧 결함이다.

**이 예외가 P12 완료 판정의 승인 근거다.** 함께 전제를 잃어 `phase-criteria.md` §P12에서 빠진
항목은 다섯이고, 전부 **철회된 점수화 계층**(Tier·9등급·leaf)을 전제하던 것이다 —
① Tier 4와 결측을 가르는 수단 ② 지수 없는 날짜에 Tier가 안 나온다 ③ 동점 처리 방향별 테스트
④ 임계값이 픽스처보다 먼저 고정 ⑤ 표본에 단계 1~4와 여러 leaf. **대상이 없다는 것과 통과했다는
것은 다르다.**

## 검증

```premise 207주 미만 이탈이 아직 필요한가
psql -d stock_screener -At -c "SELECT CASE
  WHEN count(*) = 0 THEN '잴 것이 없었다 — 국내 price_daily가 비어 있다'
  WHEN count(*) FILTER (WHERE wk BETWEEN 130 AND 206) = 0
    THEN 'OK 130~206주 밴드가 비었다 — 원본 가드와 우리 이탈이 같은 결과를 낸다'
  ELSE 'OK 130~206주 밴드 ' || count(*) FILTER (WHERE wk BETWEEN 130 AND 206)
       || '종목 / 207주 이상 ' || count(*) FILTER (WHERE wk >= 207) || '종목'
  END FROM (SELECT p.stock_id, count(DISTINCT date_trunc('week', p.trade_date)) wk
            FROM price_daily p JOIN stock s ON s.id = p.stock_id WHERE s.market_code IN ('KOSPI','KOSDAQ') GROUP BY 1) w"
```

**시장 지수 결손과 `ICHI_*` 산출 일자는 여기서 재지 않는다** — ADR-0053·ADR-0054의 `premise`
펜스가 각각 묻는다. 사본을 두지 않는다(`.claude/rules/doc-facts.md`).

## 배경

2026-08-11에 TradingView Pine Screener용 주봉 일목 스크리너가 외부 명세로 들어와 프리셋
`ICHIMOKU_SP_KR`로 이식됐다. 그 프리셋은 폐기됐고(ADR-0061) 점수화 계층은 철회됐다가 **2026-09-02에 되살아났다**
(ADR-0122) — 철회 근거였던 "소비자가 없다"가 사용자 지시가 아니었기 때문이다.
지금 있는 것은 **관측 18개 + 점수화 13개**와 그것을 내기 위해 정해진 이 규칙들이다.

**규칙이 살아남은 이유는 소비자가 일목 밖으로 번졌기 때문이다.** 정밀도는 `SeriesMath`가 셋에게,
주봉 경계는 `WeeklyResampler`가 `BETA_104W`에게, 기준지수 분기는 `IndicatorRunner`가 지수를
쓰는 지표 전부에게 물려줬다. 이식이 지표 계층에 **종목 밖 시계열을 받는 길**을 처음 열었고,
그 길의 계약이 여기 있다.

## 버린 대안

- **원본 컬럼 48개를 전부 지표로 저장한다** — `score_*`·leaf·D2·D5 점수 16개는 (수식자·단계·임계)
  에서 결정론적으로 유도되는 순수 중복이고 `indicator_value`가 2.4배가 된다. 기준은 "유도
  가능성"이 아니라 **"조건·정렬·진단 중 어디에 쓰이는가"**였다.
- **Tier 산출을 조건 엔진으로 표현한다** — `screen_condition`은 `지표 × 비교연산자 ×
  {상수|지표×계수}`가 전부라 52셀 매트릭스를 표현할 수 없었다. 지표 계층에서 Tier까지 내는 것이
  유일한 길이었고, **그것이 정확히 점수화 계층을 철회한 이유가 됐다** — 판정이 지표 안에서
  끝나 사용자가 왜 걸렸는지 볼 수 없었다.
- **불연속을 감지해 해당 종목만 판정불가로 돌린다** — 30% 이내 기업행위를 못 잡아 방어가 샌다.
  "원자료를 여는 길을 먼저 본다"(2026-08-10 사용자 판단)에 어긋난다. P9가 수정주가로 풀었다.

## 결과

- **센티널(−1·−2)이 섞인 지표가 생긴다.** `ICHI_MOD_E`(기준 시점 없음 −1) ·
  `ICHI_MOD_H`(판정 불가 −2) · `ICHI_MOD_M`(구름이 안 뒤집힘 −1)은 **「해당 없음」이 유리한 쪽
  극단에 앉는다.**
  - **MUST NOT: 이런 지표를 조건의 좌변으로 쓰지 않는다.** `>= 1`로 걸면 걸러지지만
    `<= 0`이나 `< 1`로 걸면 **해당 없는 종목이 전부 걸린다.** `bands`·`direction`을 붙이지 않고
    `interpretation`에 그 사실을 적는다.
  - **MUST: 새 지표가 같은 형태면 값을 내지 않는 쪽을 먼저 본다.** `GOLDEN_CROSS_AGE`가 그렇게
    했다 — 골든크로스가 없을 때의 "나이"가 정확히 이 문제라 아예 값을 내지 않는다.
  - 이것을 프리셋이 지키는지 `SentinelAndExtraColumnGuardTest`가 문구가 아니라 **조건 자체를
    훑어** 확인한다. 프리셋이 폐기된 뒤 검사 대상을 **전 프리셋**으로 넓혔다.
- **지표 계층이 종목 밖 시계열을 받는 길이 열렸다.** `market_index_daily`와 그 계약은
  `EXCESS_RETURN_26W`·`EXCESS_RETURN_52W`·`BETA_104W`가 이어받았다(ADR-0053·0054).
- **되돌리려면**: 지표 31개와 `calculator/ichimoku/` 패키지를 지운다. **읽기 창은 안 내려간다** —
  `IndicatorRegistry.maxEffectiveLookback()`이 전 지표 최댓값으로 매번 계산하는데 지금 그것을
  정하는 것은 `EPS_CAGR_5Y`·`RETAINED_TO_MARKET_4Y`다(ADR-0066). 다만 `WeeklyResampler`·
  `WeeklyBar`·`SeriesMath`·`PriceScaling`은 **일목 밖 소비자가 있어 남는다.**
