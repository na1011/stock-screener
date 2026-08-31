---
id: ADR-0103
status: 채택됨
date: 2026-08-31
applies_to:
  - "src/main/java/com/stockscreener/indicator/calculator/FinancialMath.java"
  - "src/test/java/com/stockscreener/indicator/calculator/UsSharesResolutionTest.java"
  - "src/test/java/com/stockscreener/indicator/calculator/FinancialSharesSplitTest.java"
---

# ADR-0103. 주식수 폴백은 봉 시점에 공시된 분기를 고른다 (ADR-0096 §결정 개정)

## 결정

`FinancialMath.sharesOf`의 **재무 폴백**이 `quarters.get(0)` 한 행을 어느 봉에나 쓰던 것을
그만두고, **그 봉 시점에 이미 공시돼 있던 분기**를 `publishedIndexOn`으로 고른다.

**그 봉 시점에 공시된 분기가 없으면 값을 내지 않는다.**

**범위는 주식수 하나다.** TTM 합·PER의 분자는 그대로 「스냅샷 하나를 창 전체에 편다」를 따른다.

## 규칙

- **MUST: 시점 판정을 `published_at`으로 한다.** `period_end`로 자르면 기말은 지났지만 아직
  공시되지 않은 수를 쓰게 되어 **미래를 알고 계산한 값**이 된다. `publishedIndexOn`이 그
  판정을 이미 갖고 있다 — 새로 만들지 않는다.
- **MUST: 고른 판이 주식수를 안 실었으면 그보다 앞선 판으로 내려간다.** 그 시점에 알 수 있었던
  가장 최근 수가 답이지 「그 판이 안 실었으니 없다」가 아니다.
- **MUST NOT: `publishedIndexOn`이 −1일 때 최신 분기로 폴백하지 않는다.** 그것이 정확히
  「없는 것을 그럴듯한 수로 내는」 자리다(phase-criteria §P27 축②).
- **MUST NOT: 이 시점 선택을 TTM 합·주당 분자로 넓히지 않는다.** 그것은 국내에도 걸리고
  PER·PBR·EPS 전부에 닿는다. **넓히는 날 이 결정도 함께 본다.**
- **MUST: 원장 커버리지를 이 결정의 정확도로 읽는다.** 분모가 이제
  `공시 주식수 × 그 뒤 원장 사건의 배수`라, **원장에 없는 분할은 그 구간을 배수만큼 틀리게
  한다.** 시점 선택이 오차 창을 「읽기 창 전체」에서 **「분할일~다음 공시(약 63거래일)」**로
  좁히지만 **없애지는 않는다.**

## 검증

```bash
./gradlew test --tests "*UsSharesResolutionTest*" --tests "*FinancialSharesSplitTest*" \
  --tests "*UsMarketCapFallbackTest*"
```

**국내 불변**은 `FinancialSharesSplitTest.priceBarWins`가 잠근다 — 봉에 상장주식수가 있으면
첫 갈래가 이긴다. 국내 543,735봉에서 `listed_shares`가 NULL인 행이 **0**이고 0인 행도 **0**이라
이 폴백을 타는 봉이 없다.

**이 결정은 별도 premise를 두지 않는다** — ADR-0096이 같은 이유로 그렇게 했다. 재는 대상이
「저장된 `SHARES_GROWTH_3Y`가 상수인가」인데 **미국 `indicator_value`가 0행이라 모집단이 없고**,
생기는 시점이 곧 미국을 켜는 단계다. 그때까지 매 세션 「잴 것이 없었다」를 신고하는 것은
발견이 아니라 소음이다.

**켠 뒤에 그것을 묻는 자리는 둘이다** — `UsSharesResolutionTest`(계약)와 P27의 커버리지
탐침(분포). 전자는 지금도 돌고, 후자가 「값이 났는데 distinct가 1인가」를 지표별로 답한다.

## 배경

**미국에서 `SHARES_GROWTH_3Y`가 항등 0이었다.** `sharesOf`가 봉을 안 보고 최신 분기 하나를
양 끝에 쓰므로 `f(t) = S ⊗ m(periodEnd, t)`가 되고, `corporateActionMultiple`이 `(from, to]`
반개구간이라 **구간 가법적**이다. 그래서 두 봉의 비가 정확히 `m(t₀,t₁)`이 되는데
`SharesGrowth3yCalculator`가 분모에 **똑같은 `m(t₀,t₁)`**을 곱해 **완전히 상쇄된다.**
기업행위가 있든 없든 0이다.

그 지표는 프리셋 **넷의 배점 조건**이라 미국 종목 전부가 「희석하지 않은 회사」 점수를 공짜로
받고 있었다. 같은 상쇄가 `EPS_GROWTH_TTM_YOY`·`EPS_CAGR_5Y`에서는 주식수를 **완전히 약분**시켜
「주당」이 아닌 순이익 성장률을 내게 했다 — 그쪽은 값이 0이 아니라 **그럴듯해서** 상수 판정에도
안 걸린다.

**고친 뒤 무엇이 나와야 하는지를 착수 전에 적었다**(최신 봉 2026-08-28 · 750거래일 전
2023-08-31 기준, SQL로 미리 계산): 값이 나는 종목 1,000 → **915**, 정확히 0인 종목
1,000 → **1**, 10/50/90 분위 `−13.93% / −1.02% / +49.23%`, `≤ 0` 충족 1,000 → **507**.

## 버린 대안

- **−1일 때 가장 오래된 분기로 폴백한다** — 값은 더 많이 나오지만 **look-ahead**다. 비용을
  재보니 최신 봉에서 잃는 종목이 **0**이고(982가 그대로 값을 낸다) 3년 전 기준점에서 **67**,
  읽기 창 앞부분에서 **169**뿐이라 감당할 수 있다.
- **봉마다 그 시점에 유효한 재무를 **전부** 고른다** — ADR-0096 §버린 대안이 「이 결정의 크기가
  아니다」로 미뤄 둔 것이고 **거절이 아니라 유예였다.** 여기서는 **주식수만** 옮긴다.
- **`IndicatorDataReader`가 `PriceBar.listedShares`를 재무로 채운다** — ADR-0096 §버린 대안이
  기각했다(ADR-0051을 두 곳에서 어기고 `corporate-action-review`의 증거를 오염시킨다).

## 결과

**쉬워지는 것**: `SHARES_GROWTH_3Y`가 미국에서 축이 된다. `EPS_GROWTH_TTM_YOY`·`EPS_CAGR_5Y`가
다시 「주당」이 된다. 5년 중앙값 셋(`Per/Pbr/PsrMedian5y`)의 **분자와 분모가 같은 시점 기준**이
된다 — 그전에는 분자만 봉 시점이고 분모는 최신이었다.

**어려워지는 것**

- **ADR-0096의 「양방향 보정」 중 나눗셈 갈래가 거의 안 쓰인다.** 고른 판의 기말이 통상 봉보다
  앞이라 곱셈으로 간다. **지우지 않는다** — 기말 전에 공시된 판이 실재하고(리더가 5행을 세어
  두었다) 그때는 나눗셈이 옳다.
- **창 앞부분이 짧아진다.** 읽기 창 시작 이전에 첫 공시가 있는 종목 **169**가 그 구간의
  `MARKET_CAP`·`PER` 계열을 잃는다. **최신 일자의 스크리닝 결과는 안 바뀐다.**
- **원장 커버리지가 값의 정확도가 됐다.** 그전에는 스냅샷 하나가 창 전체에 펴져 배수가
  상쇄되거나 상수였는데, 이제 분기마다 계단이 생겨 **원장이 빠진 분할이 그 구간에 남는다.**

**되돌리려면**: `sharesOf`를 `quarters.get(0)`으로 되돌린다. 그러면 `UsSharesResolutionTest`가
다시 「상수 0」을 신고한다 — **되돌린 것이 조용하지 않다는 것이 그 시험의 값이다.**
