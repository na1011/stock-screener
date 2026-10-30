---
id: ADR-0132
status: 채택됨
date: 2026-09-09
applies_to:
  - "src/main/java/com/stockscreener/indicator/calculator/MarketCapCalculator.java"
  - "src/main/java/com/stockscreener/indicator/calculator/SharesGrowth3yCalculator.java"
  - "src/main/java/com/stockscreener/indicator/calculator/TurnoverRatio60Calculator.java"
  - "src/main/java/com/stockscreener/indicator/calculator/TurnoverRatioCalculator.java"
---

# ADR-0132. 주식수를 읽는 계산기 넷을 `bySegment`로 감싼다

## 결정

`FinancialMath.sharesOf`를 부르면서 **`byPublication`·`bySegment` 어느 쪽도 안 거치던 계산기
넷**을 `bySegment`로 감싼다 — `MARKET_CAP` · `SHARES_GROWTH_3Y` · `TURNOVER_RATIO_60` ·
`TURNOVER_RATIO`. **계산기 본체는 한 줄도 안 바꾼다**(`bySegment` javadoc의 계약이다).

## 왜 — 「기(期)는 골랐는데 판(revision)은 미래의 것」

ADR-0128 §규칙이 *"재무에서 값을 내는 계산기는 `byPublication`이나 `bySegment`를 <b>반드시</b>
거친다"*로 못박았는데 **이 넷이 빠져 있었다.** 빠진 이유가 그럴듯하다 — `sharesOf`가 안에서
`publishedIndexOn(quarters, bar.tradeDate())`을 부르므로 **미래 기(期)는 이미 막힌다.**

**막히지 않는 것이 판이다.** `IndicatorContext` 생성자가 `latestPerPeriod`로 기마다 하나를
접는데, 감싸지 않으면 그 접기가 **읽기 시점(=`asOf`, 배치에서는 오늘)** 기준이다. 그래서 정정이
있던 기에서는 **그 봉 시점에 아직 나오지 않은 개정본**이 골라지고, 더 나쁘게는 그 개정본의
`published_at`이 봉보다 뒤라 `publishedIndexOn`이 **그 기를 통째로 건너뛰어 훨씬 오래된 기**로
내려간다. 그 자리에서 주식수가 배수로 갈린다.

**실측(2026-09-09 · `StoredCoverageBoundaryProbeTest`)** — 저장된 `MARKET_CAP`이
`FinancialVisibility.LATEST`로 계산한 값과 **정확히 일치**했다.

| 저장(=`LATEST`) | `KNOWN_ON` | 차 |
|---|---|---|
| 17,801,570,726.94 | 17,845,929,415.30 | 0.25% |
| **19,426,185,662.00** | **32,010,640,507.30** | **65%** |
| 17,974,728,700.80 | 29,618,916,892.32 | 65% |

**발화 모집단은 미국 744종목**이다(활성 유니버스 중 주식수를 실은 기가 판을 둘 이상 가진 종목 ·
최대 공시 간격 1,826일). **국내는 0이다** — 봉의 `listed_shares`가 차 있어 재무 폴백을 안 탄다
(ADR-0097).

**ADR-0130이 이 부류를 원리적으로 못 닫는다.** 걷기는 **이번 실행이 안 낸 날짜**만 보는데,
여기서는 값을 내되 **그 값이 시점 재현이 아니다** — 행이 그대로 덮여 남는다.

## 규칙

- **MUST: `sharesOf`를 부르는 계산기는 `bySegment`를 거친다.** 「봉마다 값을 낸다」는 것으로
  안전하지 않고, 「`sharesOf`가 안에서 봉 날짜를 본다」는 것으로도 안전하지 않다 —
  **`publishedIndexOn`은 기를 고르지 판을 못 고른다.**
- **MUST: 가르는 시험을 「저장값이 `LATEST`와 일치하는가」로 한다.** 값의 크기나 그럴듯함으로는
  안 갈린다. `StoredCoverageBoundaryProbeTest`가 같은 날짜를 두 가시성으로 계산해 저장본과
  맞춰 그 답을 낸다 — **`KNOWN_ON`과 맞아야 하고 `LATEST`와만 맞으면 결함이다.**
- **이 결정이 움직이는 것은 저장된 지표와 그것을 읽는 프리셋 게이트·배점이다.** `US_TOP1000`의
  시총은 `JdbcStoredShareCounts`가 `financial_statement`를 **직접** 읽어 만들어 `sharesOf`를 안 타므로,
  **이 결정만으로는** 유니버스 순위가 안 움직인다.
  - **그 경계는 2026-09-09에 없어졌다(ADR-0135).** 같은 세 축(판 선택 · 기업행위 하한 앵커 · 그 상한)을
    `JdbcStoredShareCounts`에도 세웠고, 그것이 **유니버스 순위를 실제로 움직였다** — `MLI` 시총
    28.13B → 14.06B · `SNEX` 12.50B → 8.33B(실측). **둘 다 진입선 위라 편입은 안 바뀌고 순위만 내려간다.**
    **MUST NOT: 그러므로 「유니버스는 안 움직인다」를 근거로 인용하지 않는다.**
- **MUST NOT: `computeAt`의 출력이 안 바뀐 것을 「고침이 안 걸렸다」로 읽지 않는다.**
  `computeAt(D)`는 리더가 이미 `published_at <= D`로 걸러 오므로 **마지막 봉 하나의 값이 원래
  옳았다.** 이 결정이 고치는 것은 **`run(asOf=오늘)`이 과거 봉에 쓰는 값**이다.

## 검증

```bash
./gradlew test --tests "*FinancialSharesSplitTest*" --tests "*MarketCapCalculatorTest*"

# 감싸지 않은 호출부가 남아 있는가 — 아무것도 반환하지 않아야 한다
rg -l "FinancialMath.sharesOf" src/main/java/com/stockscreener/indicator/calculator \
  | xargs rg -L "bySegment|sharesBySegment|byPublication"   # sharesBySegment: 주식수만 읽는 넷의 입구(새 실적 발표로 안 막음 · ADR-0195)
```

**단위 시험은 이 결함을 못 잡는다** — 픽스처가 기마다 판 하나라 `bySegment`가 항등이다.
**대조군은 탐침이고**, 그것이 이 결정의 유일한 판정 수단이다:

```bash
./gradlew backtestProbe --tests "*StoredCoverageBoundaryProbeTest*"   # LATEST만 == 0 이어야 한다
```

**MUST NOT: 통과 집합이나 점수 갈림으로 이 결정을 판정하지 않는다.** 재보니 **둘 다 안
움직였다**(§결과) — 판정 수단은 위 하나뿐이다.

## 버린 대안

| 대안 | 왜 버렸나 |
|---|---|
| **`sharesOf`가 안에서 `visibleAt`을 부르게 한다** | 호출부마다 봉이 다르므로 봉당 컨텍스트 사본이 생긴다 — `bySegment`는 **공시 구간마다 한 번**이라 창 안 공시일이 한 자릿수인 만큼만 는다 |
| **`publishedIndexOn`이 판까지 고르게 고친다** | 접기(`latestPerPeriod`)가 생성자에 있어 그 함수가 **이미 접힌 목록**을 받는다. 안 접힌 목록을 넘기려면 계약이 바뀌고, 그 계약을 지키는 자리가 `visibleAt` 하나다 |
| **네 계산기만 손으로 필터를 짠다** | 같은 로직의 다섯 번째 구현이 된다. ADR-0128이 그 형태를 이미 `bySegment`로 접었다 |
| **ADR-0130의 걷기를 값 비교로 넓힌다** | 걷기가 **값을 다시 계산해 비교**하게 되어 배치가 두 배가 되고, 무엇보다 **틀린 값을 지우는 것이 고치는 것이 아니다** |

## 결과

**쉬워지는 것** — 저장된 `MARKET_CAP` 계열이 과거 일자에서 시점 재현이 된다. 그 값을 읽는
프리셋 게이트(버핏 시총 하한)와 배점이 **그날 알 수 있던 수**로 판정한다.

**어려워지는 것**

- **`bySegment`가 공시 구간마다 계산기를 다시 돈다.** 창 안 공시일이 종목당 한 자릿수라
  그만큼만 늘고, 아래 실측이 그 값이다.
- **`sharesOf`의 다른 축은 안 닫혔다** — 기준점이 `period_end`라
  `(period_end, published_at]`의 기업행위가 한 번 더 곱해지는 것(ADR-0126 §결과)은 **별도**다.
  그쪽 발화는 **미국 13종목**이라 이 결정과 섞지 않았다.

### 적용 실측 (2026-09-09)

**전 종목 판 22분 41초 · 1,350종목 · 실패 0 · 값이 바뀐 행 37,478.** 표는
42,013,817 → **42,014,392행**(+575 — 판이 바뀌며 값이 성립하게 된 칸이다).

**국내 두 시장의 행 수가 한 행도 안 움직였다**(KOSPI 6,604,355 · KOSDAQ 4,998,190 그대로).
그것이 「국내는 재무 폴백을 안 탄다」의 대조군이다.

**판정**: `WAT`·`ANET`·`UI` 전 표본 날짜에서 **「`LATEST`만 일치」가 0**이 됐다(고치기 전에는
`MARKET_CAP`이 다섯 날짜에서 `LATEST`와 정확히 일치했다).

**안 움직인 것 둘 — 이 결정의 축이 아니다.**

- **통과 집합**: 12조합 전부 일치 그대로(ADR-0130이 이미 닫았다).
- **점수 갈림 4건**: 그대로다. 코드별로 찍어 보니 원인이 둘이고 **둘 다 주식수 축이 아니다** —
  국내는 `RETAINED_TO_MARKET_4Y`(재계산 1.00224610 대 저장 0.95970245)로 **분모가 `asOf`의
  주식수를 따라가는 것**이 원인이며 ADR-0129 §결과가 별도 결정으로 남겨 뒀고, 미국은
  `PER_SECTOR_PCTL`(0.50847458 대 0.50000000 · 0.50000000 대 0.60000000)로 **하네스가
  `PERCENT_RANK`를 손으로 재구현한 자리**다(ADR-0131 §결과).

**MUST: base 판 뒤에 파생층을 창 전체로 다시 돌린다** — 백분위는 돌린 날짜에만 다시 나므로
base만 돌리면 나머지 날짜가 옛 모집단으로 매겨진 채 남는다(ADR-0130 §규칙 · 실측 5분 17초).

**되돌리려면** 네 계산기의 `calculate`를 `computeSegment` 본체로 되돌린다.
`WAT`·`ANET`·`UI`의 **변경 직전** 지표 값을 담은 대조군 파일(`bysegment-20260909-before.psv` · 89,733행)은 2026-10-05에 지웠다(사용자가 직접 실행).
**되돌리는 순간 과거 일자 값이 다시 미래의 판으로 계산되고 아무 에러도 안 난다** —
위 두 탐침만이 그것을 잡는다.
