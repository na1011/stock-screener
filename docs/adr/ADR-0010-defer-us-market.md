---
id: ADR-0010
status: 대체됨(→ ADR-0086)
date: 2026-08-06
applies_to:
  - "src/main/java/com/stockscreener/pipeline/datasource/**"
  - "src/main/resources/universe/sp500.csv"
  - "docs/stock-screener-project-spec.md"
---

# ADR-0010. 미국 시장을 v1.1로 미룬다

> **이 결정은 [ADR-0086](./ADR-0086-us-universe-by-listing.md)이 대체했다(2026-08-29).**
> 아래 §규칙의 *"MUST NOT: 미국 종목을 유니버스에 편입하지 않는다"*는 더 이상 유효하지 않다 —
> 유니버스는 거래소 상장 기준으로 채워졌다. 다만 **§버린 대안의 「미국을 유니버스에 두고 실패를
> 감수」는 아직 살아 있는 근거다**: 그 상태를 피할 수 있게 된 것은 ADR-0087이 수집 대상을
> 선언된 시장으로 좁혔기 때문이고, 그 장치를 걷으면 이 기각 근거가 그대로 되살아난다.
>
> **아래 §배경의 실측(Finnhub 403·Yahoo 429·Stooq 챌린지·각 ToS)은 지우지 않는다** — 그 소스들을
> 다시 시도하려는 세션에 여전히 답한다.

## 결정

Phase 2를 **코스피200·코스닥150만으로 완성**하고, S&P500은 v1.1로 미룬다. spec §7이 지정한 미국 시세 소스가 실제로 막혀 있고, 대안이 전부 무료 티어 제약이나 ToS 문제를 갖기 때문이다.

> 이것은 spec §7·§9의 전제를 뒤집는 판단이었다. **사용자가 2026-08-06에 승인해 spec §1·§3·§7·§9·§10·§11이 수정되었다.**

## 규칙

- MUST: 미국 관련 포트(`PriceDataSource`·`FinancialDataSource`)를 **지우지 않는다.** 어댑터만 없을 뿐 구조는 그대로 두며, v1.1에서 어댑터 하나를 추가하면 된다.
- MUST: `universe.code = 'SP500'` 행과 `sp500.csv` 시드를 유지한다. 스키마와 유니버스 구조는 미국을 이미 수용한다.
- MUST NOT: 미국 종목을 유니버스에 편입하지 않는다. 시세 소스가 없어 수집이 전량 실패한다.
- MUST NOT: Finnhub `/stock/candle`을 다시 시도하지 않는다. 무료 티어에서 403이다.

## 검증

```bash
grep -rn "supportedMarketCodes" src/main/java/com/stockscreener/pipeline/datasource/kr/
```
`KOSPI`·`KOSDAQ`만 나와야 한다. `US`를 담당하는 어댑터가 없다는 것이 이 결정의 코드상 표현이다.

```bash
psql -d stock_screener -c "SELECT code FROM universe ORDER BY code"
```
`SP500` 행이 남아 있어야 한다 — 구조는 유지하고 데이터만 넣지 않는다.

```bash
./gradlew test --tests "*DataSourceRegistryTest*"
```
담당 어댑터가 없는 시장을 조회하면 명확한 예외가 나는지 확인한다. 조용히 건너뛰면 그 시장이 통째로 누락된 채 배치가 성공으로 끝난다.

## 배경

2026-08-06 실측으로 확인한 것들이다.

- **Finnhub `/stock/candle` → HTTP 403** (`{"error":"You don't have access to this resource."}`). 무료 티어에서 과거 OHLC가 막혔다. spec §7의 "미국 시세 및 재무 데이터는 Finnhub를 통해 확보한다" 중 **시세 부분이 성립하지 않는다.**
- **Finnhub `/stock/financials-reported` → HTTP 200.** 재무는 무료로 살아 있고 `bs`/`ic`/`cf`가 `{concept, unit, label, value}` 배열로 온다. 즉 **재무만으로는 진행할 수 있으나 시세가 없다.** 무료 티어의 호출 한도는 **분당 60회 수준**이라, v1.1에서 500종목 재무를 적재하려면 그 전제 위에 배치를 설계해야 한다.
  - **이 한 줄은 더 이상 후속 판단의 근거가 아니다.** [ADR-0086](./ADR-0086-us-universe-by-listing.md)이 재무도 유료 티어로 이관돼 **채택 근거가 소멸**했음을 기록하고 미국 재무 소스를 **SEC EDGAR로 확정**했다. **그 이관을 이 프로젝트가 다시 재보지는 않았다** — 재볼 이유가 없어서다. 지우지 않는 이유는 위 문장이 "재무는 열려 있다"로 읽혀 실제로 `.env.example`에 그렇게 적히는 일이 있었기 때문이다.
- **Stooq → JS proof-of-work 챌린지.** 다수 자료가 여전히 추천하지만 스크립트로 접근할 수 없다.
- **SEC EDGAR → 정상**(키 불필요, 10,398종목). 미국 재무의 최선이지만 **OHLC는 제공하지 않는다.**

시세 대안은 전부 문제가 있었다. Alpha Vantage는 무료 25 req/day로 500종목에 20일이 걸리고, EODHD는 20 req/day다. Tiingo는 수치상 맞지만 ToS가 "Internal Use Only"라 spec §2의 "본인과 소수 지인" 공유와 충돌한다. massive(구 Polygon) 유료는 ToS가 깨끗하나 spec §2의 "인프라 운영 비용 최소화"와 충돌한다.

**국내만으로도 v1의 핵심 가치는 성립한다.** 스크리닝 엔진·지표 계산·워치리스트는 시장에 무관하고, 국내 두 소스는 실측으로 검증됐다. 미국을 위해 ToS를 위반하거나 비용을 들이는 것보다, 국내를 완성해 파이프라인 전체를 실증한 뒤 미국을 붙이는 편이 낫다. 포트가 있으므로 그 비용은 어댑터 하나다.

## 버린 대안

- **Tiingo 무료 채택** — 수치는 맞으나 "Internal Use Only" ToS가 지인 공유와 충돌한다. 개인 프로젝트라도 명시된 금지를 우회할 이유가 없다.
- **massive 유료 전환** — 가장 깔끔하지만 spec §2의 비용 최소화와 충돌한다. 국내가 완성된 뒤 사용자가 판단할 문제다.
- **Yahoo Finance 비공식 API** — 첫 요청부터 HTTP 429. 비공식이라 안정성 근거도 없다.
- **재무만 수집하고 기술적 지표는 국내만** — PER·PBR도 주가가 필요하다. `/quote`는 현재가만 주어 과거 이력이 없으므로 반쪽짜리다.
- **미국을 유니버스에 두고 실패를 감수** — 500종목이 매일 실패로 기록되어 부분 실패 알림이 상시화되고, 그러면 진짜 장애가 묻힌다.

## 결과

- 쉬워지는 것: Phase 2를 실측 검증된 국내 소스만으로 완주할 수 있다. 추가 키 발급도, ToS 판단도 필요 없다.
- 어려워지는 것: spec §9의 수용 기준("S&P500 편입 종목 대상 스크리닝") 미달 상태로 v1이 끝난다. 사용자가 spec 수정을 승인해야 한다.
- 되돌리려면: US 시세 소스를 정하고 `PriceDataSource` 구현 하나를 추가한 뒤, `sp500.csv`를 실제 구성종목으로 채우면 된다. **포트 밖에서 고칠 파일은 없어야 한다** — 그것이 ADR-0006이 만든 구조의 실증이 된다.
