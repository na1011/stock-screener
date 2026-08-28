---
id: ADR-0006
status: 채택됨
date: 2026-08-05
applies_to:
  - "src/main/java/com/stockscreener/pipeline/datasource/**"
  - "src/main/java/com/stockscreener/pipeline/collector/**"
---

# ADR-0006. 수집 계층을 포트-어댑터로 나누고 벤더 지식을 어댑터에 가둔다

## 결정

외부 소스 접근을 네 개의 포트(시세·재무·유니버스·종목마스터)로 나누고, 벤더 지식은 어댑터 패키지에만 둔다. 어댑터가 상위로 던지는 예외는 `DataSourceException` 하나이며, 시장 코드로 어댑터를 고르는 것은 `DataSourceRegistry`가 한다.

> spec §7이 이미 세 소스(공공데이터포털·DART·Finnhub)와 "데이터 소스 계층은 특정 API에 종속되지 않는 구조"를 요구했다. 이 ADR은 그 요구를 옮겨 적는 것이 아니라, **경계를 어디에 긋고 무엇으로 강제하는가**라는 그 이후의 판단만 기록한다.

## 규칙

- MUST: 시세와 재무의 포트를 분리한다. 조회 단위가 다르고(날짜 구간 vs 회계기간), 재무에는 정정공시라는 개정 개념이 있다.
- MUST: 한 벤더가 두 종류를 제공하더라도 **어댑터 클래스를 나눈다.** 한 클래스가 두 포트를 구현하면 한쪽만 교체할 때 클래스를 쪼개야 한다.
- MUST: 어댑터는 모든 벤더 예외를 `DataSourceException`으로 감싼다.
- MUST: `DataSourceException`은 `Kind`로 재시도 가능 여부를 담는다. 실패를 하나로 뭉뚱그리면 재시도 가치가 없는 실패에 백오프를 낭비하고, 재시도해야 할 실패를 영구 실패로 기록한다.
- MUST NOT: `collector`·`batch`·`scheduler` 패키지의 **코드**에 벤더 이름이 등장하지 않는다. 규칙의 이유를 설명하는 주석은 예외다.
- MUST NOT: 포트 DTO에 비율 지표(PER·ROE 등) 필드를 두지 않는다. 이것이 "외부 API 계산값에 의존하지 않는다"를 타입 수준에서 강제하는 수단이다.
- MUST NOT: 소스가 계산한 수정주가를 받지 않는다. `adj_factor`는 우리가 유지한다.

## 검증

```bash
grep -rnE 'finnhub|Finnhub|[Dd]art|data\.go\.kr|opendart|stooq|Stooq|KRX|[Aa]lpaca|APCA|sec\.gov|EDGAR' \
  src/main/java/com/stockscreener/pipeline/collector \
  src/main/java/com/stockscreener/pipeline/batch \
  src/main/java/com/stockscreener/pipeline/scheduler 2>/dev/null \
  | grep -vE ':[0-9]+: *(\*|//|/\*)'
```
아무것도 반환하지 않아야 한다. 주석 줄을 제외하는 이유는, 규칙의 근거를 설명하려면 어느 벤더가 그렇게 동작하는지 적어야 하기 때문이다(예: "거래대금은 KRX만 제공한다"). 코드에 등장하면 위반이다.

**`DART|dart`가 아니라 `[Dd]art`인 것이 중요하다.** 벤더 클래스 이름이 `DartGovernanceFactMapper`처럼 **대문자 D + 소문자**로 오면 앞 패턴이 못 잡는다 — 위반이 성립해도 통과하는 펜스가 된다(ADR-0074가 금지한 형태). 실제로 그 이름을 collector에 노출할 뻔했고, 그래서 구현을 `datasource/kr`에 package-private으로 두고 포트만 내보냈다(ADR-0081).

**미국 소스가 붙으면서 패턴에 넷을 더했다**(`[Aa]lpaca`·`APCA`·`sec.gov`·`EDGAR`). 시장이 늘 때 이 목록을 함께 늘리지 않으면 **위반이 성립해도 통과하는 펜스**가 된다 — 위 `[Dd]art`와 같은 자리다. `APCA`가 따로 있는 이유는 인증 헤더 이름이 벤더명을 안 쓰기 때문이다(`APCA-API-KEY-ID`).

```bash
grep -rnE 'RestClientException|HttpClientErrorException|JsonProcessingException' \
  src/main/java/com/stockscreener/pipeline/collector \
  src/main/java/com/stockscreener/pipeline/batch
```
아무것도 반환하지 않아야 한다 — 벤더 예외가 어댑터 밖으로 새면 상위가 벤더를 알게 된다.

```bash
grep -nE '\b(per|pbr|roe|ratio)\b' src/main/java/com/stockscreener/pipeline/datasource/RawFinancialReport.java | grep -v '^\s*\*'
```
아무것도 반환하지 않아야 한다 — 비율 필드가 없는 것이 자체 계산 규칙의 구조적 보장이다.

```bash
./gradlew test --tests "*DataSourceRegistryTest*"
```
같은 시장에 두 소스가 등록되면 **기동 시점에** 실패하는지 확인한다.

## 배경

spec §7이 인용한 "국내 거래소 정보 시스템의 회원제 전환" 전례가 추상적인 우려가 아니라는 것이 Phase 2 착수 시점에 확인됐다. Finnhub 무료 티어가 과거 OHLC 제공을 중단해, spec이 지정한 "미국 시세는 Finnhub" 전제가 시세 부분에서 깨졌다. 즉 **소스 교체는 Phase 2 안에서 실제로 일어나는 사건**이며, 경계는 장식이 아니라 당장 쓰이는 것이다.

경계를 주석이 아니라 **grep으로 검증 가능한 형태**로 만든 것이 이 ADR의 핵심이다. "벤더를 알면 안 된다"는 규칙은 시간이 지나면 반드시 새는데, 검증 명령이 있으면 새는 순간 드러난다.

`RawFinancialReport`에 비율 필드를 두지 않은 것도 같은 발상이다. `data-pipeline.md`의 "외부 API 계산값에 의존하지 않는다"를 사람의 주의력에 맡기지 않고, 그 값을 담을 곳 자체를 없앴다.

## 버린 대안

- **포트 하나로 통합(`MarketDataSource`)** — 시세만 교체하고 싶을 때 재무까지 건드리게 된다. 실제로 그 상황이 발생했다.
- **어댑터가 벤더 예외를 그대로 던짐** — 상위 계층이 `RestClientException`을 잡게 되고, 소스를 바꾸면 잡는 쪽도 바뀐다.
- **`RuntimeException`으로 던짐** — 종목 단위 실패 격리를 빠뜨린 코드가 컴파일된다. checked로 두면 호출부가 실패 처리를 마주하게 된다.
- **포트 DTO를 지표 계층 모델(`PriceBar`·`FinancialFigures`)과 공유** — 소스 스펙 변경이 지표 계산기까지 번진다.
- **소스가 준 수정주가 사용** — 소스를 바꾸는 순간 과거 전체의 기준이 조용히 달라진다.

## 결과

- 쉬워지는 것: 소스 교체가 어댑터 클래스 하나 교체로 끝난다. API 키 없이도 Fake 소스로 파이프라인 전체를 검증할 수 있다.
- 어려워지는 것: 벤더 응답을 포트 DTO로 옮기는 매핑 코드가 어댑터마다 필요하다. 벤더가 주는 편의 값(계산된 비율)을 쓰지 못한다 — 의도된 대가다.
- 되돌리려면: `datasource` 패키지의 포트와 그것을 소비하는 `collector`를 함께 고쳐야 한다.
