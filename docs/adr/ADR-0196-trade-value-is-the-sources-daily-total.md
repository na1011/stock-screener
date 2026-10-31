---
id: ADR-0196
status: 채택됨
date: 2026-10-31
applies_to:
  - "src/main/java/com/stockscreener/pipeline/datasource/us/AlpacaPriceAdapter.java"
  - "src/main/java/com/stockscreener/indicator/calculator/Turnover60Calculator.java"
  - "src/test/java/com/stockscreener/pipeline/datasource/us/AlpacaClosingAuctionProbeLiveTest.java"
  - "src/test/java/com/stockscreener/pipeline/datasource/us/AlpacaHalfHourTurnoverProbeLiveTest.java"
---

# ADR-0196. 거래대금은 소스가 주는 그날 체결 금액 총계다 — 미국은 SIP 일봉의 `vw × v`(관문 뒤)

## 결정

`price_daily.trade_value`의 정의를 **「소스가 주는 그날 그 종목의 체결 금액 총계」**로 한다. 국내는 KRX `trPrc` 그대로, 미국은
SIP 일봉의 **`vw × v`**(그 봉에 든 체결 전부의 Σ가격×수량 — VWAP의 정의라 합성이 아니다)다. 미국 값은 관문 설정(`stockscreener.gate.us`)
켬에서만 싣고 관문의 미국 시세 재적재가 보존 창을 한 번에 채운다. `TURNOVER_60`은 창 60봉 전부에 거래대금이 있어야 값을 낸다.

## 규칙

- MUST: 거래량이 0인 봉의 거래대금은 0이다(국내 정지 봉이 0으로 오는 것과 같다). 거래량이 있는데 `vw`가 없거나 0 이하면 null이다.
- MUST NOT: 미국 거래대금을 정규장으로 자르지 않는다 — 종가 경매가 경계 밖에 찍히고 봉에 안 실리는 체결이 있다(§버린 대안).
- MUST NOT: `close × volume`으로 합성하지 않는다(정규장 종가 × 전 세션 거래량 — 다른 값이다).
- MUST NOT: `TURNOVER_60`에서 거래대금이 빈 날을 빼고 평균하지 않는다 — 빈 칸은 「받지 못한 날」이고, 빼면 짧은 창의 평균이 60일 평균으로 나간다.
- MUST: 시장 간 세션 창 차이는 **시장별 임계**(ADR-0109 분위 보존 · `screen_condition_threshold`)가 흡수한다. 지표 설명이 「다른 시장의 종목과 직접 비교하지 않는다」를 적는다.

## 검증

```bash
./gradlew test --tests "*AlpacaPriceAdapterTest.carriesTradeValueOnlyWhenGateIsOn*" --tests "*SwingIndicatorsTest*Turnover*"
```

꺼짐 갈래는 null 그대로이고 국내는 `trade_value` NULL 0행이라(2026-10-31 · 729,605행) 계산기 규칙 변경이 국내 값을 안 바꾼다.

## 배경

미국 `trade_value`가 전량 NULL이라 `TURNOVER_60`·`_PCTL`이 미국 0행이었다. ADR-0112가 일봉 `vw × v`를 「전 세션 체결금액이라 정규장만 담는
KRX와 재는 창이 다르다」로 기각하고 「정규장 분봉 합」을 남은 문으로 적었다. **그 기각의 전제 「KRX는 정규장만」은 원장에 물은 적이 없었다** —
data.go.kr 15094808 문서와 금융위 FAQ 어디에도 `trPrc`·`trqu`의 창 정의가 없다(2026-10-31 확인).

**정규장 분봉 합은 정규장이 아니었다**(2026-10-31 · `AlpacaClosingAuctionProbeLiveTest` · 13종목 × 4일):
- 종가 경매(조건 `6`) 체결이 하루 거래량의 10~15%(AAPL 4.48M/35.0M · NVDA 13.7M/136M)이고 16:00:00~16:03 ET에 찍힌다(NASDAQ 16:00:00.1 ·
  NYSE PFE 16:02 · F 16:03 · NVR 16:01) — [09:30, 16:00) 봉 합은 그것을 통째로 뺀다. ADR-0112의 진실값도 그랬다.
- 1분 봉 하루 합이 일봉 거래량보다 작은 날이 있다(PFE 08-24 20.9M 대 23.8M · T 08-25 26.0M 대 28.8M) — 봉에 안 실리는 체결이 있다.

## 버린 대안

- **정규장 30분 봉 `vw × v` 합**(v1) — 30분 봉 13개 합 = 1분 봉 합(108 종목일 비 0.99987~1.00010 · `AlpacaHalfHourTurnoverProbeLiveTest`)이라
  1/30 콜로 같은 값이 나오지만, 그 값이 위 이유로 정규장 체결금액이 아니다. 별도 채움 잡은 `PriceWriter`가 시세를 쓸 때마다 `trade_value`를
  null로 되돌려(일일 15일 창 · 확정 재확인 · 구멍 채움) 반증 두 렌즈가 깼다.
- **정규장 봉 + 종가 경매(`/v2/stocks/auctions`) 접합** — 경매 시각이 거래소마다 다르고, 봉에 안 실리는 체결은 여전히 빠지며, 엔드포인트가 하나 는다.
  경계 맞추기가 특례 갈래를 만든다.
- **1분 봉 저장** — 저장 축이 하나 늘고(ADR-0112가 별도 ADR로 남긴 것) 위와 같은 이유로 정규장이 아니다.

## 결과

- 미국 값은 관문 날 생긴다 — 순서와 대상은 `docs/reload-gate.md` [T] 행(시세 재적재 대상은 보존 창 편입 전원 · 현 편입만 잡는
  `--reload-existing`·`--universe`로는 과거 편입 214에 안 닿는다).
- 저장 화면 #18(사용자 것)은 미국 실행에서 계속 `MarketThresholdMissingException`이다 — `TURNOVER_60`·`MARKET_CAP` 둘 다 KRW 축만 있고, 둘을
  풀면 통화 축 없는 원화 조건(`ROE_MIN_3Y` · `DEBT_RATIO` 등 · ADR-0109 §결과)이 미국을 판정하기 시작한다. 관문 뒤 값으로 분위를 재서 사용자에게 올린다.
- 국내 `trPrc`의 창(정규장만인지)은 여전히 원장에 안 물었다 — KRX OPEN API `stk_bydd_trd`의 `ACC_TRDVAL`과 대조하는 것이 그 물음이다. 원칙은 어느 답에도 선다.
