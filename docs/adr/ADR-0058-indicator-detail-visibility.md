---
id: ADR-0058
status: 채택됨
date: 2026-08-14
applies_to:
  - "src/main/java/**/indicator/**"
  - "src/main/java/**/stock/web/**"
  - "frontend/src/lib/detailVisibility.ts"
  - "frontend/src/views/StockDetailView.vue"
---

# ADR-0058. 지표의 노출을 활성·분류와 다른 축으로 가른다

## 결정

`indicator.detail_default`(정본은 `@IndicatorMeta.detailDefault`, 기본 `true`)를 만들어 **종목 상세의 분류 구획에 기본으로 보일 것인가**를 따로 정한다. 값은 **종목 상세 응답에 실어** 화면이 카탈로그에서 되짚지 않게 한다.

## 왜 — 축 하나가 세 가지를 겸하고 있었다

지표의 노출을 가르는 것이 `is_active` 하나뿐이었고 그것은 **조건 빌더·결과 표·종목 상세 셋에 동시에** 걸린다. 그래서 "상세 화면에서만 감춘다"를 표현할 방법이 없었고, 감추려면 폐기하는 수밖에 없는데 폐기하면 **조건으로도 못 쓴다.**

실제로 그것이 문제가 됐다 — 오실레이터 구획 22개 중 13개가 일목 원시 수식자(구름 색·두께·기울기)였다. 0·1·−1이 초보~중급 사용자에게 뜻이 없는데, 지우면 그 지표로 스크리닝하는 길까지 사라진다.

**세 축은 서로 다른 질문에 답한다.**

| 축 | 질문 | 아니면 |
|---|---|---|
| `is_active` | 아직 쓰는 지표인가 | 폐기 — 조건·결과·상세 전부에서 사라진다 |
| `category` | 어느 구획인가 | 다른 구획으로 간다(ADR-0057) |
| `detail_default` | 상세 화면에 **기본으로** 보이는가 | 조건에 넣어야 보인다 |

## 규칙

- **MUST: `detail_default = false`가 폐기가 아니다.** 조건 빌더 드롭다운·결과 표·종목 상세의 「내 조건」 구획에는 그대로 나온다. 감추는 것은 **분류 구획 하나**다.
- **MUST: 값을 종목 상세 응답(`StockDetailResponse.IndicatorValue`)에 싣는다.** 화면이 카탈로그에서 되짚으면 셋이 어긋난다 — ① `onMounted`가 카탈로그와 상세를 **동시에** 쏘고 순서 보장이 없어 감출 카드가 그려졌다가 접힌다 ② 카탈로그가 실패하면 감춤이 통째로 무효가 되는데 **고지 한 줄도 함께 사라져** 무효가 됐다는 사실조차 안 남는다 ③ `.claude/rules/frontend.md`가 "서버가 이미 아는 사실을 화면이 다른 요청으로 재계산하지 않는다"를 금한다.
  - 구간(`bands`)·백분위를 카탈로그에서 잇는 선례와 **방향이 반대다.** 그쪽은 **덧붙이는** 것이라 실패해도 값과 설명이 남는다("카탈로그가 실패하면 미터가 사라진다. 그것이 옳다"). 이쪽은 **빼는** 것이다.
- **MUST NOT: 서버가 거르지 않는다.** 거르면 「내 조건」 구획이 감춘 지표의 값을 못 받는다 — 서버는 사용자의 초안 조건(`screenDraft`, 화면에만 있는 상태)을 모른다. 전량을 주되 각 행이 자기 노출 정책을 들고 온다.
- **MUST: 감춘 수를 화면에 알린다.** 걸러내고 아무 말도 하지 않는 것이 던지는 것보다 나쁘다(`frontend.md`). 그 수는 **응답에 실린 것 중에서** 센다 — 카탈로그에서 세면 종목과 무관하게 언제나 같은 수가 나오고, 값이 없어 빠진 것까지 "감췄다"고 말하게 되어 사용자가 조건에 넣으면 볼 수 있다고 믿는다.
- **MUST: `missingCount`의 분모를 기본 노출 대상으로 좁힌다.** 전체 활성 지표에서 빼면 감춘 것까지 "값이 없다"로 센다. 감춤은 값의 유무와 무관한 사실이다.

## ADR-0057의 분류 잣대를 넓힌다

ADR-0057은 `TECHNICAL`과 `OSCILLATOR`가 "**기준선을 두고 진동하는가**"로 갈린다고 적었다. `ADX_14`는 그 잣대에 걸리지 않는다 — 0~100 사이에서 **강도만** 재고 방향도 기준선도 없다.

그럼에도 `OSCILLATOR`에 두는 이유는 **0~100으로 정규화되어 종목 간 절대 비교가 되고, 다른 오실레이터의 과열·침체 신호를 언제 믿을지 가르는 데 쓰이기 때문**이다(TradingView도 Oscillators로 분류한다). 잣대를 "진동하거나, 정규화된 값으로 다른 오실레이터의 신뢰도를 가르는 것"으로 넓힌다.

## 검증

```premise 감춘 지표가 조건에서는 여전히 쓸 수 있다
psql -d stock_screener -At -c "SELECT CASE
  WHEN count(*) FILTER (WHERE NOT detail_default) = 0 THEN '잴 것이 없었다 — 감춘 지표가 없다'
  WHEN count(*) FILTER (WHERE NOT detail_default AND NOT is_active) = 0
    THEN 'OK 감춘 ' || count(*) FILTER (WHERE NOT detail_default) || '개가 전부 활성이다'
  ELSE '감췄는데 폐기까지 된 지표가 있다 — 조건으로도 못 쓴다: ' ||
       string_agg(code, ', ') FILTER (WHERE NOT detail_default AND NOT is_active)
  END FROM indicator"
```

```premise 종목 상세 응답이 노출 정책을 싣는다
grep -c 'detailDefault' src/main/java/com/stockscreener/stock/web/StockDetailResponse.java \
  | awk '{print ($1 > 0) ? "OK 상세 응답이 노출 정책을 싣는다" : "상세 응답에서 빠졌다 — 화면이 카탈로그로 되짚게 되고 도착 순서에 따라 카드가 깜빡인다"}'
```
