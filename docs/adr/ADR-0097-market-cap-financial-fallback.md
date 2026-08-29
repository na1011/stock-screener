---
id: ADR-0097
status: 채택됨
date: 2026-08-30
applies_to:
  - "src/main/java/com/stockscreener/indicator/calculator/MarketCapCalculator.java"
  - "src/main/java/com/stockscreener/indicator/calculator/TurnoverRatioCalculator.java"
  - "src/main/java/com/stockscreener/indicator/calculator/SharesGrowth3yCalculator.java"
  - "src/main/java/com/stockscreener/indicator/calculator/EpsCagr5yCalculator.java"
  - "src/main/java/com/stockscreener/indicator/calculator/EpsGrowthTtmYoyCalculator.java"
  - "src/main/java/com/stockscreener/indicator/calculator/RetainedToMarket4yCalculator.java"
---

# ADR-0097. 시가총액 계열도 재무 주식수를 폴백으로 쓴다 (ADR-0051 §규칙 개정)

## 결정

**`sharesOn`만 쓰던 계산기 여섯을 `FinancialMath.sharesOf`로 갈아탄다** — `MARKET_CAP` ·
`TURNOVER_RATIO` · `SHARES_GROWTH_3Y` · `EPS_CAGR_5Y` · `EPS_GROWTH_TTM_YOY` ·
`RETAINED_TO_MARKET_4Y`. 봉에 상장주식수가 있으면 그것이 이기고(국내), 없으면 재무에서
받아 봉 시점으로 옮긴다(ADR-0096).

**ADR-0051 §규칙의 `MUST NOT: MarketCapCalculator를 FinancialMath.sharesOf로 갈아타지 않는다`를
개정한다.** 그 금지의 사유는 **국내 무상증자 구간**, 즉 「값이 있는데 짝이 안 맞아 일부러 뺀
봉」이다. 미국 봉은 `listed_shares`가 **전량 NULL**이라 그 개념이 성립하지 않는다 — 값이 없는
이유가 의도가 아니라 **소스 부재**다.

## 규칙

- **MUST NOT: 이 여섯에 `DataRequirement`의 재무를 선언하지 않는다.** 재무는 이 지표들의
  **필수 입력이 아니다**(국내는 재무 없이 값을 낸다). 선언하면
  `IndicatorRunner.inputsWereReadable`이 「재무가 통째로 없으면 걷지 않는다」로 접어
  **재무 0행 종목에서 옛 행이 영영 안 걷힌다** — 국내에 실재한다(맥쿼리인프라·인제니아).
  **`IndicatorRunnerTest`의 꼬리 걷기 테스트가 이것을 잡았다.**
  - 그런데도 `calculate`가 `context.quarterlyFigures()`를 쓸 수 있는 이유는 러너가
    **`registry.maxFinancialYears()`로 전 계산기의 최대 연수를 한 번에 읽기** 때문이다.
    **MUST: 그 성질이 바뀌면 이 결정을 다시 본다.**
- **MUST: 폴백 값에 기업행위 배수를 건다**(ADR-0096). 보정 없이는 분할 구간이 배수만큼 틀린다.
- **MUST NOT: `price_daily.listed_shares`에 합성값을 쓰지 않는다.** phase-criteria P22 축②를
  어기고, `corporate-action-review`가 그 컬럼의 점프를 판정 증거로 쓰므로 증거가 오염된다.

## 검증

```bash
./gradlew test --tests "*UsMarketCapFallbackTest*" --tests "*IndicatorRunnerTest*"
```

**대조군으로 확인했다**: `MarketCapCalculator`를 `sharesOn`으로 되돌리면 「봉에 주식수가 없어도
값을 낸다」 하나만 붉고, 「봉이 이긴다」·「양쪽 다 없으면 안 낸다」는 그대로 초록이다 —
폴백이 정확히 그 한 갈래만 연다.

**`IndicatorRunnerTest`를 함께 돌리는 이유**는 이 변경이 실제로 그것을 한 번 깼기 때문이다.
재무를 선언했더니 꼬리 걷기가 막혔고, 그 테스트가 유일한 신호였다.

## 배경

미국 봉은 상장주식수를 안 준다(`alpaca-bars.md`). 그래서 이 여섯과 그 파생 셋
(`FCF_YIELD`·`NCAV_TO_MARKET`·`ALTMAN_Z`)이 미국에서 **영구 0행**이었고, 시총 게이트를 가진
프리셋이 전부 0건이었다.

**국내는 한 값도 안 바뀐다** — 543,370봉에서 `listed_shares`가 NULL인 행이 **0**이라(실측)
폴백을 탈 봉이 없다. 그 불변을 `UsMarketCapFallbackTest`의 둘째 단정이 잠근다.

## 버린 대안

- **`DataRequirement`에 재무를 선언한다** — 처음에 그렇게 했고 **꼬리 걷기가 막혔다.**
  재무 0행 종목에서 옛 행이 영영 안 걷힌다.
- **`DataRequirement`에 「선택적 재무」 축을 더한다** — 정확하지만 record에 필드가 늘고
  삭제 판정(ADR-0080·0085)에 직접 닿는다. **러너가 이미 최대 연수로 한 번에 읽으므로
  그 축 없이 성립한다** — 필요해지는 날 다시 본다.
- **`IndicatorDataReader`가 봉의 `listedShares`를 채운다** — ADR-0096 §버린 대안이 기각했다.

## 결과

**쉬워지는 것**: 재무만 적재하면 미국에서 `MARKET_CAP` 계열 여섯과 파생 셋이 값을 낸다.
미국 유니버스를 시총 상위로 정의하는 길이 여기서 열린다.

**어려워지는 것**

- **ADR-0051이 경계한 결합이 실제로 생겼다.** 이 여섯이 이제 재무를 읽는다 —
  **선언은 안 하지만 읽는다.** 러너가 재무를 안 읽게 되면 이 지표들이 조용히 미국에서만
  다시 0행이 된다. 위 MUST가 그 조건을 적는다.
- **해상도가 시장마다 다르다.** 국내는 봉마다, 미국은 보고 기간마다(약 63거래일)다.
  자사주 매입만큼 시총이 과대해진다(AAPL 실측 분기당 약 0.6%).
- **BRK는 여전히 빠진다.** `companyfacts`가 dimension 없는 주식수 팩트를 2011년 이후 안 낸다.

**되돌리려면**: 여섯의 `sharesOf` 호출을 `context.sharesOn(bar.tradeDate(), bar.listedShares())`로
되돌린다. 그러면 `UsMarketCapFallbackTest`의 첫 단정이 붉는다.
