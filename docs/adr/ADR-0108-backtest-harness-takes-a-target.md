---
id: ADR-0108
status: 채택됨
date: 2026-09-01
applies_to:
  - "src/test/java/com/stockscreener/probe/backtest/**"
---

# ADR-0108. 백테스트 하네스가 「조회 대상」을 받는다

## 결정

**시점 재현 하네스의 모든 진입점이 조회 대상(유니버스) 하나를 받는다.** 시장·통화·달력·모집단
넷이 그 하나에서 나오며, 대상 목록은 화면의 조회 대상 콤보와 **같은 술어**다
(`ScreeningTargets` — 활성이고 편입이 있는 유니버스).

프리셋의 `universeCode`로는 정할 수 없다 — **활성 프리셋 여덟이 전부 `null`**이라 「전체」가
되고, 통화가 갈려 임계를 해석할 수 없다.

## 규칙

- **MUST: 판정 전에 조건을 그 대상의 통화로 해석한다**(`ConditionGroup.resolveFor`).
  안 하면 `IndicatorCondition.evaluate`가 `MarketLiteral`에 던진다. **`screen` 하나만
  고치면 안 된다** — 진단 축이 귀속을 위해 조건을 **다시** 평가하므로 그 자리도 해석본을
  받아야 하고, 실제로 그 둘째 호출부가 남아 있었다.
- **MUST: 달력 질의에 시장을 넘긴다. 기본값을 두지 않는다.**
  `AsOfCalendar`의 셋과 `ForwardReturns.measure`가 대상이다. 기본값이 있으면 시장을 안 고른
  호출부가 **아무 증상 없이** 국내 휴장·미국 개장일을 as-of로 집는다 — 그날 국내는 봉이 없어
  `computeAt`이 빈 맵을 내고, **통과 0이 게이트 탓으로 읽힌다.**
  `AsOfResolver.resolve`가 같은 이유로 기본값을 안 둔다(ADR-0095).
- **MUST: 값 계산·업종 백분위의 모집단을 활성 유니버스 편입 전체로 둔다.**
  판정만 대상으로 좁힌다. 라이브 `SectorRelativeCalculator`가 그 모집단에서
  `PARTITION BY industry_group_key`로 매기므로, 둘을 같이 좁히면 동료 수와 `MIN_PEERS`
  판정이 갈려 **`AsOfRecomputeTest`의 점수 대조가 붉는다.** 실측으로 활성 편입의 세분류
  250개 중 **26개가 두 국내 유니버스에 걸쳐 있다.**
- **MUST: 격자의 하한을 대상별로 유도한다**(`HarnessWindow.floorFor`). 상수로 박으면
  시장이 늘 때 조용히 낡는다 — §배경의 실측이 그 크기를 든다.
- **MUST NOT: 임계를 `operand().toString()`에서 정규식으로 긁지 않는다.**
  `ConditionOperand.MarketLiteral`이 `Map.copyOf`로 순서를 지워 **실행마다 다른 통화의
  임계**가 잡힌다. `ScreenStore`가 저장 쪽에서 겪은 것과 같은 병이고(그쪽은 정렬로 닫았다),
  이쪽은 정렬조차 없어 더 나빴다. 해석 뒤에는 평범한 `Literal`이므로 값을 직접 읽는다.
- **MUST NOT: 이 하네스에 판정을 더하지 않는다.** 단언은 **「잴 것이 있었는가」와 「귀속이
  성립하는가」** 둘뿐이다. 0건을 실패로 만들면 다음 세션이 초록을 만들려고 임계를 만진다
  (ADR-0071의 금지를 그대로 승계한다).
- **MUST NOT: 대상 목록 질의의 사본을 테스트마다 만들지 않는다.** 갈리면 하네스마다 다른
  모집단을 보게 되고, 그 차이는 통과 수가 조금 다른 것으로만 드러난다.

## 검증

```bash
./gradlew backtestProbe
```

**대조군으로 확인했다.** 이 결정 **직전**에 돌린 실측이 `6 tests completed, 3 failed`이고
셋 다 같은 예외였다 — `IllegalStateException: 해석되지 않은 시장별 임계로 판정을 시도했다:
ROE_MIN_3Y`(`IndicatorCondition.java:142` → `PointInTimeScreener.screen`). 즉 미국을 켠 뒤
**이 하네스는 국내만 보던 것이 아니라 아예 돌지 않았다.** 통화 해석을 되돌리면 그 셋이 다시
붉고, 시장 축을 되돌리면 격자 18점 중 6점이 국내 봉 0인 날로 돌아간다.

```premise 시장 축이 지금도 무언가를 막고 있는가
psql -d stock_screener -At -c "
WITH cur AS (SELECT generate_series(DATE '2022-04-01',
        (SELECT max(trade_date) FROM price_daily), INTERVAL '3 months')::date AS c),
grid AS (SELECT DISTINCT (SELECT max(trade_date) FROM price_daily WHERE trade_date <= c) AS d
         FROM cur),
blind AS (SELECT count(*) n FROM grid g WHERE g.d IS NOT NULL AND NOT EXISTS (
           SELECT 1 FROM price_daily p JOIN stock s ON s.id = p.stock_id
            WHERE p.trade_date = g.d AND s.market_code IN ('KOSPI','KOSDAQ')))
SELECT CASE
  WHEN (SELECT count(*) FROM grid WHERE d IS NOT NULL) = 0
    THEN '잴 것이 없었다 — price_daily에 봉이 한 행도 없다'
  WHEN (SELECT n FROM blind) = 0
    THEN '잴 것이 없었다 — 시장을 안 가른 격자에도 국내 봉 없는 점이 0이다. 지금 이 축이 막는 것이 없으므로 이 전제는 ADR-0108이 옳은지에 대해 아무 말도 하지 않는다'
  ELSE 'OK 시장 축이 격자점 ' || (SELECT n FROM blind) || '개를 막고 있다 — 되돌리면 그 점들에서 국내가 전 종목 빈 맵을 받아 통과 0이 게이트 탓으로 읽힌다'
END"
```

**이 전제는 결정이 *지켜지는가*가 아니라 *아직 필요한가*를 묻는다.** 축이 걸린 코드를 보면
정의상 언제나 참이라 아무것도 재지 못하므로(**실패할 수 없는 펜스**), 대신 **축을 빼면
무슨 일이 나는가**를 잰다 — 시장을 안 가른 격자에 국내 봉 없는 점이 몇이나 생기는지다.
실측 **18점 중 6점**(`2022-12-30 · 2023-09-29 · 2023-12-29 · 2024-10-01 · 2024-12-31 ·
2025-12-31`)이고, 미국 전용 거래일은 **176일**이다. **0이 되면 침묵이 아니라 「잴 것이
없었다」**로 답한다 — 두 시장의 휴장이 우연히 겹치는 기간이 오면 이 축이 무해해 보이는데,
그것은 축이 불필요하다는 뜻이 아니라 **이 전제가 판정력을 잃었다**는 뜻이다.

## 배경

미국을 켠 뒤(ADR-0107) P27 축③(「조건이 축으로 남아 있는가」)을 잴 도구가 필요했는데,
그 도구가 **국내만 보는 것이 아니라 아예 돌지 않았다.** 세 하네스가 전부
`preset.conditions()`를 해석 없이 넘겼고, ADR-0106이 임계에 통화 축을 달면서 그 경로가
예외가 됐다.

**창의 하한이 시장마다 다르다는 것이 이번에 드러난 두 번째 사실이다.** 재무 최초 회계연도가
국내 **FY2015** · 미국 **FY2018**이라(실측), 국내 바닥으로 정한 하한 `2022-04-01`을 미국에
그대로 쓰면 `PROFIT_STREAK_Y >= 7`을 만족할 종목이 **앞쪽 8시점에서 0**이다
(2024-04-01에 30 → 2025-04-01에 799로 는다). 그 0은 게이트가 아니라 적재 범위 탓이고,
격자 하한이라는 장치가 애초에 막으려던 바로 그 실패다. 그래서 하한을 유도로 바꿨고,
국내에 대해서는 **유도가 기존 상수와 같은 날짜(2022-04-01)를 낸다** — 그것이 이 유도가
기존 판단을 재현한다는 대조다.

**그 결과 미국 시계열은 짧다**(분기 격자 약 6점, 국내 18점). **P27 축③의 미국 쪽 답은
주로 ADR 펜스가 낸다** — 최신 단면은 미국도 온전하기 때문이다(실측 `PROFIT_STREAK_Y`
값 있음 705/1,000).

## 버린 대안

- **하네스를 「활성 유니버스 전체」로 한 번만 돌린다** — 통화가 둘이라 임계를 해석할 수 없다.
  ADR-0106이 같은 이유로 라이브에서 「전체」를 막았고, 하네스만 예외로 두면 **화면이 못 하는
  판정을 하네스가 하게 된다.**
- **판정과 업종 백분위 모집단을 둘 다 대상으로 좁힌다** — 라이브와 갈린다(§규칙).
- **`WINDOW_START`를 시장별 상수 둘로 둔다** — 시장이 늘면 조용히 낡고, 낡았다는 사실이
  「그 시장만 통과 0」으로만 드러난다.
- **`market_calendar`로 거래일을 판정한다** — `exchange_code`가 NYSE·NASDAQ뿐이라 국내가
  없다(ADR-0091). `price_daily`가 여전히 거래일의 정본이다.
- **시장별 거래일 사본 테이블을 둔다** — 조인 비용이 문제가 아니었다. 실측으로 시장 한정
  `max(trade_date)`가 **65~86ms**이고 격자 하나당 한 번이다.

## 결과

**쉬워지는 것** — 같은 하네스가 대상만 바꿔 두 시장에서 돈다. 라이브(`ScreeningRepository`)와
같은 축을 쓰므로 `AsOfRecomputeTest`의 대조가 시장마다 성립한다. 배점 조건의 충족률·커버리지
열이 생겨 P27 축③의 *"배점 조건도 함께 센다"*가 처음으로 측정된다.

**어려워지는 것**

- **표가 대상 수만큼 늘고 해상도가 다르다.** 미국 격자가 6점, 국내가 18점이라 회전율을
  나란히 읽으면 안 된다 — 그래서 시점 수를 표 머리에 함께 찍는다.
- **계산이 대상마다 반복된다.** 값 계산 모집단이 활성 유니버스 전체라 국내 두 대상이 같은
  것을 두 번 계산한다. 그래도 **전보다 싸다** — 고치기 전 `null` 갈래가 8,872종목을 돌았고
  지금은 1,350이다(실측).
- **`ConditionReturnTest`의 사전등록 시점 여덟 중 미국은 둘만 쓴다.** 나머지는
  「잴 것이 없었다」로 찍는다 — 사전등록을 고친 것이 아니라 못 재는 구간을 못 잰다고 적은
  것이다.

**되돌리려면** — `ScreeningTargets`·`HarnessWindow`를 지우고 네 클래스의 시그니처를 되돌린다.
그러면 `backtestProbe`의 셋이 다시 `IllegalStateException`으로 죽는다.
