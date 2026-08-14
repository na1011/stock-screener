---
id: ADR-0063
status: 채택됨
date: 2026-08-14
applies_to:
  - "src/main/java/**/indicator/calculator/WeeklyResampler.java"
  - "src/main/java/**/indicator/calculator/WeeklyBar.java"
  - "src/main/java/**/indicator/calculator/Beta104wCalculator.java"
  - "src/main/java/**/indicator/calculator/ichimoku/**"
---

# ADR-0063. 주봉 리샘플러를 일목 패키지 밖으로 꺼내고, 베타는 진행 중인 주를 쓰지 않는다

## 결정

`WeeklyResampler`와 `WeeklyBar`를 `calculator/ichimoku/`에서 `calculator/`로 올리고 공개한다.
`BETA_104W`(2년 주간 베타, 스윙 원본 명세의 T-08)가 같은 주 경계를 써야 하기 때문이다.

**다만 베타는 진행 중인 주를 회귀에 넣지 않는다.** 각 거래일에 그 날짜가 속한 주의 **직전
주**까지로 회귀한다. 일목은 그 주를 쓴다 — 두 지표가 같은 리샘플러를 쓰되 마지막 주를 다르게
다룬다.

## 규칙

- **MUST: 종목과 지수를 같은 주 경계로 접는다.** `WeeklyResampler.weekKey`를 양쪽에 쓴다.
  지수는 `PriceBar`가 아니라 `NavigableMap`이라 리샘플러를 그대로 못 쓰므로 키만 공유한다.
  **어긋난 짝으로도 회귀는 값을 낸다** — 예외도 빈 결과도 아니다.
- **MUST NOT: 주봉 접기를 다시 짜지 않는다.** ISO `weekBasedYear`를 쓰는 이유(12월 마지막
  주와 1월 첫 주가 갈라지는 것)가 한쪽에만 반영되면 그 주만 어긋난다.
- **MUST: 베타는 그 거래일이 속한 주의 직전 주까지로 잰다.** 리샘플러는 「진행 중인 주 포함」
  으로 접지만(ADR-0046 §승인된 이탈 3) 그것은 **지금 이 주의 상태**를 보는 일목의 요구다.
  베타는 분포를 재는 통계량이라 1~5일짜리 부분 주가 섞이면 **그 한 주만 다른 기간을 잰다.**
  결과로 값이 주 단위로만 바뀌며, 그것이 「주간」 베타의 뜻이다.
- **MUST NOT: `SeriesMath.slope`를 베타에 쓰지 않는다.** 그것은 **시간축** 추세 기울기이지
  이변량 회귀가 아니다. 이름이 비슷해 오용을 부른다.
- **MUST NOT: 국내 베타의 중앙값을 1로 가정하지 않는다.** 지수가 시총가중이라 소수의
  대형주가 지수를 끌고 나머지는 그보다 덜 움직인다. **임계값은 분포를 재고 정한다.**

## 검증

```premise 국내 주간 베타의 중앙값이 1보다 한참 낮다
psql -d stock_screener -At -c "SELECT CASE
  WHEN count(*) = 0 THEN '잴 것이 없었다 — BETA_104W 행이 하나도 없다'
  WHEN percentile_cont(0.5) WITHIN GROUP (ORDER BY value) < 0.95
    THEN 'OK 중앙값 ' || round(percentile_cont(0.5) WITHIN GROUP (ORDER BY value)::numeric, 3)
       || ' (n=' || count(*) || ')'
  ELSE '중앙값이 ' || round(percentile_cont(0.5) WITHIN GROUP (ORDER BY value)::numeric, 3)
       || '로 1에 가깝다 — 지수를 잘못 붙였거나 종목별 시장 분기가 빠졌다' END
FROM indicator_value
WHERE indicator_code = 'BETA_104W'
  AND trade_date = (SELECT max(trade_date) FROM indicator_value WHERE indicator_code = 'BETA_104W')"
```

주봉 접기가 한 곳뿐인지 — 아래가 `WeeklyResampler.java` 하나만 반환해야 한다.

```
rg -l "weekBasedYear" src/main/java
```

## 배경

프리셋 개편(ADR-0061)이 스윙 원본 명세의 T-08을 되살리기로 했다. 그 조건은 ADR-0039에서
「시장 지수 시계열이 없다」는 이유로 버려졌는데, 지수는 ADR-0053으로 적재됐고
`IndicatorContext.marketIndex()`도 열려 있었다. 실제로 `ExcessReturn26wCalculator`의 javadoc이
이미 「이 값은 일목 전용이 아니라 T-08과 공유한다」고 자리를 비워 두고 있었다.

막힌 것은 소스가 아니라 **패키지 가시성**이었다. 저장소의 유일한 주봉 리샘플러가
일목 패키지 안에 package-private으로 있어, 밖에서는 닿지 않았다.

## 버린 대안

- **베타 계산기를 일목 패키지에 두기** — 닿기는 한다. 그러나 `calculator/ichimoku/`는
  「주봉 일목 지표 18개」라는 뜻을 가진 자리이고, 거기 무관한 지표가 들어가면 그 뜻이 흐려진다.
  일목 지표를 통째로 걷어낼 때 베타가 함께 딸려 나간다.
- **베타 안에서 5거래일 간격으로 샘플링** — 리샘플러가 필요 없다. 그러나 휴장이 섞이면
  주 경계가 밀리고, **밀린 채로도 값은 나온다.** 종목마다 다른 주를 재게 되는데 그 사실이
  어디에도 드러나지 않는다.
- **베타도 진행 중인 주를 쓰기** — 일목과 일관되긴 한다. 그러나 마지막 주간 수익률이
  1~5일짜리 부분 기간이 되어 104개 관측치 중 하나만 다른 것을 잰다. 일목의 이탈은
  「Pine Screener가 마지막 봉만 평가한다」는 원본 사정에서 온 것이고, 베타에는 그 사정이 없다.

## 결과

- 주봉을 쓰는 지표를 더할 때 리샘플러를 다시 짜지 않아도 된다.
- 일목 파일 셋에 import가 늘었다. 되돌리려면 그 셋과 두 파일의 패키지 선언을 함께 되돌린다.
- **베타 값은 주 단위로만 바뀐다.** 같은 주 안의 거래일은 값이 같다 — 결함이 아니다.
- 주간 수익률 104개를 못 채우는 종목(실측 17개)은 값이 없다. 그 종목들은 베타를 **필수
  조건으로 쓰는 프리셋에서 판정불가로 탈락**하며, 그 사실을 프리셋 설명이 말해야 한다.
