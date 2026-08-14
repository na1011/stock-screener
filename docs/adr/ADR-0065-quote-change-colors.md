---
id: ADR-0065
status: 채택됨
date: 2026-08-15
applies_to:
  - "frontend/src/theme/preset.ts"
  - "frontend/src/theme/contrast.spec.ts"
  - "frontend/src/lib/quote.ts"
  - "frontend/src/components/stock/StockHeader.vue"
---

# ADR-0065. 시세 등락은 한국 시장 관례를 따라 상승 빨강·하락 파랑으로 그린다

## 결정

종목 상세 헤더의 전일 대비 등락에 **상승 빨강(`{red.400}`)·하락 파랑(`{sky.400}`)**을 쓴다.
**사용자가 정한 것이다** — ADR-0021이 색 계열을 늘리지 않기로 한 것을 이 자리에 한해 뒤집는다.

## 규칙

- MUST: 색 토큰의 이름을 `tone`과 **나눈다**(`quote.riseText`/`fallText`/`flatText`). 재사용하면
  한 토큰이 두 뜻을 겸하고, ADR-0021 §검증 3-1(`rg -ln -- '--p-tone-'`)이 재는 채널 분리도
  함께 무너진다.
- MUST NOT: 등락에 **`▲`/`▼`를 쓰지 않는다.** 이유가 둘이고 둘 다 이 저장소 안에 있다.
  ① ADR-0021 §형태로 가른다가 `MUST NOT: 유불리 방향 표식(▲/▼)에 색을 주지 않는다`를 못 박았다.
  ② 같은 화면의 `IndicatorCard`가 그 글리프를 이미 쓰는데 **극성이 반대다** — `lib/direction.ts`의
  `▼`는 「낮을수록 유리」라 PER 카드의 `▼`는 *좋다*는 뜻이다. 방향은 부호와 색이 진다.
- MUST NOT: 하락에 `{primary.*}`(cobalt)를 쓰지 않는다. 링크·주요 액션과 한 색이 되면 하락이
  "누를 수 있는 것"으로 읽힌다.
- MUST: 새 색을 더하면 `contrast.spec.ts`가 그 대비를 재게 한다. 기존 세 describe는 버튼만
  재므로 **새 토큰은 아무도 재지 않는 자리**였다.

## 검증

```bash
# 1. 토큰 이름이 tone과 갈려 있는가 (ADR-0021 §검증 3-1이 계속 통과하는 근거)
rg -n 'quote: \{' frontend/src/theme/preset.ts
#    ADR-0021 §검증 3-1과 같은 형태로 화면 쪽만 뒤진다 — 정의(preset)와 주석을 세면 검사가
#    자기 자신을 문다.
rg -l -- '--p-quote-' frontend/src/components frontend/src/views
#    → components/stock/StockHeader.vue 뿐이어야 한다

# 2. 등락에 방향 글리프가 없는가
#    마지막 필터가 블록 주석을 거른다 — 주석이 "왜 안 쓰는지"를 적으려고 그 글리프를 부르고,
#    그것까지 잡으면 이 검사가 늘 빨개져 아무도 안 보게 된다(ADR-0021 §검증 1번과 같은 함정).
rg -n '[▲▼]' frontend/src/lib/quote.ts frontend/src/components/stock/StockHeader.vue | rg -v ':\s*\*'
#    → 아무것도 나오지 않아야 한다

# 3. 대비를 실제로 재는가
cd frontend && npx vitest run src/theme/contrast.spec.ts
```

실측 대비비(WCAG 2.x, 이 앱의 네 면 중 최악값인 「들린 카드」 기준):
**상승 `#f87171` 6.03:1 · 하락 `#38bdf8` 7.79:1.** 둘 다 본문 기준 4.5:1을 넘는다.
캔버스 기준은 각각 7.03 · 9.08이다.

## 배경

사용자가 국내 증권앱 관례(상승 빨강·하락 파랑)를 요구했다. 이 앱의 색 예산은 ADR-0021이
이미 꽉 채워 두었고, 그 §버린 대안이 **"값 구간에 새 색쌍(teal ↔ rose 등)을 도입"을 명시적으로
기각**했다 — 색 계열이 일곱으로 늘고 그중 넷이 "좋다/나쁘다"를 뜻하게 되어 사용자가 무엇을
좋다고 읽어야 하는지 배워야 한다는 이유였다. 이 결정은 그 기각을 **사용자 판단으로 번복한다.**

## 버린 대안

- **앱 기존 토큰 그대로(상승 초록·하락 빨강)** — 색 예산과 충돌이 없고 지표 카드와 뜻이
  하나로 유지된다. 사용자가 고르지 않았다.
- **색 없이 부호만**(`FinancialsPanel`의 YoY와 같은 처리) — 충돌이 아예 없다. 역시 고르지 않았다.
- **형태로 가르기** — 이 저장소가 ADR-0021에서 쓴 수법이지만 여기서는 **쓸 수 없다.** 남은
  형태 채널이 `▲`/`▼`인데 위 §규칙이 그것을 막는다.

## 결과

**대가를 그대로 적는다.** 종목 상세 한 화면에서

- **빨강이 두 가지를 말한다** — 헤더에서는 "상승", 지표 카드(`--p-tone-unfavorable-text`)에서는
  "불리". 구분 수단은 자리(헤더 vs 카드)와 부호뿐이다.
- **파랑이 둘이 된다** — `primary`가 cobalt(`#4d93ff`)이고 하락이 sky(`#38bdf8`)다. 하나는
  링크·주요 액션, 하나는 하락이다.

되돌리려면 `preset.ts`의 `QUOTE_RISE`·`QUOTE_FALL`을 바꾼다 — 소비처는 `StockHeader.vue`의
클래스 둘뿐이고 `contrast.spec.ts`가 새 값을 다시 잰다.
