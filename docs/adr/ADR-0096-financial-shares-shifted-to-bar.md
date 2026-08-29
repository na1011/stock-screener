---
id: ADR-0096
status: 채택됨
date: 2026-08-30
applies_to:
  - "src/main/java/com/stockscreener/indicator/calculator/FinancialMath.java"
  - "src/test/java/com/stockscreener/indicator/calculator/FinancialSharesSplitTest.java"
---

# ADR-0096. 재무에서 폴백한 주식수를 봉 시점으로 옮긴다 (ADR-0051 보완)

## 결정

`FinancialMath.sharesOf`의 **재무 폴백**이 내는 주식수에 **기업행위 배수를 건다.**
보고 종료일의 값을 그 봉 시점의 값으로 옮기는 것이고, **양방향이다** — 봉이 뒤면 곱하고
앞이면 나눈다.

**`sharesOn`은 그대로 안 건다**(ADR-0051 §규칙의 MUST NOT을 지킨다). 두 함수는 다른 것이다:
`sharesOn`은 **권리락~신주상장 구간의 계단**이고, `corporateActionMultiple`은
**두 시점 사이의 누적 배수**다.

## 규칙

- **MUST: 양방향으로 보정한다.** 재무 스냅샷 하나를 읽기 창 전체에 펴는 것이 이 프로젝트의
  계약이라(`batch-load` skill) 봉은 보고 종료일보다 **앞일 수도 뒤일 수도** 있다.
  한쪽만 보정하면 **반대쪽이 배수만큼 틀린 채 그럴듯하게 남는다.**
- **MUST NOT: 재무 폴백에 `sharesOn`을 곱하지 않는다**(ADR-0051 §규칙). 그 값은 공시 시점의
  것이라 권리락 구간과 무관하고, 곱하면 이미 신주가 반영된 수에 한 번 더 곱한다.
- **MUST: 경계의 등호를 `corporateActionMultiple`의 것으로 둔다** — `from`(보고 종료일) 당일
  사건은 빼고 `to`(봉) 당일 사건은 넣는다. **그쪽 javadoc이 그 방향을 정한 근거는 `sharesOn`과의
  짝인데 여기서는 `sharesOn`을 안 곱하므로 그 전제가 성립하지 않는다.** 보고 종료일 당일에
  효력이 생긴 사건은 그 보고서의 주식수에 이미 반영돼 있다고 보는 것이 옳아 같은 방향이 맞고,
  **테스트가 그 하루를 고정한다.**
- **MUST NOT: 배수가 0 이하일 때 나누지 않는다.** 원장 판정이 그런 값을 거절하지만
  (`CorporateActionLedger.judge`), 여기서 나눗셈이 터지면 **그 종목의 지표가 통째로 사라진다.**

## 검증

```bash
./gradlew test --tests "*FinancialSharesSplitTest*"
```

**대조군으로 확인했다**: `shiftToBar`를 걷고 값을 그대로 돌려주면 넷 중 **둘(곱하기·나누기)이
붉고** 나머지 둘(사건 없음 · 봉 우선)은 그대로 초록이다 — 보정이 실제로 그 두 방향을 만든다.

**이 결정은 별도 premise를 두지 않는다.** 재는 대상이 「지금 저장된 값이 맞는가」인데
미국 `indicator_value`가 0행이라 모집단이 없고, 생기는 시점이 곧 미국을 켜는 단계다.
그때 ADR-0086의 premise가 축별로 답한다.

## 배경

**미국 봉은 `listed_shares`가 전량 NULL이라**(실측 2,772행) PER·PBR·PSR 계열이 재무 폴백을 탄다.
그 폴백이 내던 값은 **보고 종료일의 주식수 그대로**였는데, 봉의 종가는 그 뒤 분할을 이미
반영한다.

**NVDA 실측**: 2024-06-10에 10:1 분할로 종가가 `1,208.88 → 121.79`가 되는데, 그 시점 최신 재무는
`period_end 2024-04-28`의 **2,460,000,000**(분할 전)이다. 곱하면 시가총액이 **정확히 1/10**이고
다음 보고(2024-08-28 공시)까지 **약 55거래일** 그대로다. 반대로 최신 재무(분할 후 24.53B)를
분할 전 봉에 쓰면 **10배**다. 어느 쪽도 결측이 아니라 그럴듯한 숫자다.

**국내는 이 폴백을 타지 않는다** — 543,370봉에서 `listed_shares`가 NULL인 행이 **0**이다(실측).
그래서 이 변경의 국내 영향이 0이고, 그 불변을 테스트가 잠근다.

**타는 계산기는 일곱이다** — `PbrCalculator`가 직접, 나머지 여섯은 `perShareOn`을 거친다
(`PerCalculator`·`EpsTtmCalculator`·`PsrCalculator`·`Per/Pbr/PsrMedian5yCalculator`).
`docs/api-spec/alpaca-bars.md`가 「`PbrCalculator` 하나」로 적고 있었고 함께 고쳤다.

## 버린 대안

- **`IndicatorDataReader`가 `PriceBar.listedShares`를 재무로 채운다** — 처음 설계였고
  **ADR-0051을 두 곳에서 어긴다**: `sharesOf`의 첫 갈래가 잡혀 `sharesOn`이 곱해지고(§규칙의
  MUST NOT), §버린 대안이 기각한 「`MARKET_CAP`이 값을 안 내는 봉에 값이 생기는」 상태에
  문만 다르게 도달한다.
- **`price_daily.listed_shares`에 합성값을 쓴다** — phase-criteria P22 축②
  「소스가 주지 않는 값을 합성하지 않았다」를 어기고, `corporate-action-review`가 그 컬럼의
  점프를 판정 증거로 쓰므로 **증거가 오염된다.**
- **봉마다 그 시점에 유효한 재무를 고른다** — 더 정확하지만 「재무 스냅샷 하나를 창 전체에
  편다」는 프로젝트 계약을 바꾸는 일이고, PER·PBR·TTM 전부에 걸린다. **이 결정의 크기가 아니다.**
  그 계약이 바뀌는 날 이 보정도 함께 본다.
- **한 방향만 보정한다** — 봉이 보고 종료일보다 앞인 경우가 실제로 흔하다(창이 180거래일이고
  분기 보고는 3개월마다다). 반대쪽이 배수만큼 틀린 채 남는다.

## 결과

**쉬워지는 것**: 미국에서 PER·PBR·PSR·EPS_TTM과 5년 중앙값 셋이 분할 구간에서도 맞는 값을 낸다.
`MARKET_CAP` 계열을 이 폴백으로 옮기는 다음 결정의 전제가 여기서 갖춰진다.

**어려워지는 것**

- **분할·병합만 닫힌다.** 미국 원장이 담는 것이 그 둘뿐이고(ADR-0090) 배당은 안 담는다.
  무상증자가 있으면 원장이 차 있어야 이 보정이 걸린다.
- **재무 스냅샷을 창 전체에 펴는 계약은 그대로다.** 자사주 매입처럼 사건 없이 주식수가
  바뀌는 것은 보정 대상이 아니다 — AAPL 실측으로 분기당 약 0.6%다.

**되돌리려면**: `shiftToBar` 호출을 걷고 `fromFinancials`를 그대로 돌려준다. 그러면
`FinancialSharesSplitTest`의 넷 중 둘이 붉는다.
