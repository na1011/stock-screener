---
id: ADR-0014
status: 채택됨
date: 2026-08-06
applies_to:
  - "src/main/java/com/stockscreener/stock/SupportResistanceAnalyzer.java"
  - "src/main/java/com/stockscreener/stock/PriceReference.java"
  - "src/main/java/com/stockscreener/stock/web/**"
---

# ADR-0014. 지지·저항은 저장하지 않고 읽기 시점에 계산하며 지표 네임스페이스 밖에 둔다

## 결정

지지·저항 참고정보를 `indicator_value`에도 별도 테이블에도 저장하지 않는다. 종목 상세를 볼 때 `price_daily`에서 그 자리에서 계산하고, 지표와 **다른 엔드포인트**(`/api/stocks/{id}/price-reference`)로 내보낸다. `IndicatorCalculator`를 구현하지 않는다.

## 규칙

- MUST NOT: 지지·저항 값을 `indicator_value`에 쓰지 않는다. 지표 카탈로그에 코드를 만들지 않는다.
- MUST NOT: 지지·저항을 위한 `IndicatorCalculator` 구현을 만들지 않는다.
- MUST: 응답에 `kind`·`method`·`window`·`disclaimer`를 항상 싣는다. 선택 필드로 두지 않는다.
- MUST: 구간마다 `touchCount`와 `lastTouchedOn`을 함께 낸다. 숫자만 주고 근거를 감추지 않는다.
- MUST NOT: 진입가·손절가·목표가에 해당하는 **단일 값**을 어떤 이름으로도 내지 않는다(spec §10).
- MUST: 종목 상세와 별도 엔드포인트로 유지한다. 한 응답에 합치지 않는다.
- MUST: 봉이 부족하면(`MIN_BARS` 미만) 값을 지어내지 않고 `insufficientData`로 표시한다.

## 검증

```bash
grep -rn 'SUPPORT\|RESISTANCE' src/main/java/com/stockscreener/indicator/
```
아무것도 반환하지 않아야 한다. 반환되면 지지·저항이 지표 계층으로 새어 들어간 것이다.

```bash
./gradlew test --tests "*SupportResistanceAnalyzerTest*" --tests "*ReadApiTest*"
```
카탈로그 응답에 `SUPPORT`/`RESISTANCE` 문자열이 없고, 구간에 지표 코드 필드가 없으며, `disclaimer`가 비어 있지 않고, 액면분할 구간에 가짜 존이 생기지 않는 것을 확인한다.

```bash
grep -rn 'entryPrice\|stopLoss\|targetPrice' src/main/java/
```
아무것도 반환하지 않아야 한다.

## 배경

`frontend.md`가 이유를 명시한다 — **사용자가 지지·저항을 확정 매매 신호로 오해하면 실제 금전적 손실로 이어진다.** design §4의 Phase 4 완료 조건도 "지지·저항이 확정 신호가 아님이 **API 응답부터** 구분될 것"이다.

핵심 통찰은 그 구분을 **문구가 아니라 타입으로** 보장할 수 있다는 것이다. 조건의 좌변은 언제나 `indicator` 카탈로그에 존재하는 코드다(`screen_condition.indicator_code`가 FK). 지지·저항을 카탈로그에 넣지 않으면 그것을 스크리닝 조건으로 쓰는 것이 **원리적으로 불가능**해진다. 저장하지 않는 선택이 곧 안전장치다.

읽기 시점 계산이 감당 가능한 이유는 이것이 목록이 아니라 **종목 상세 한 건**의 기능이기 때문이다. 120봉을 읽어 O(n·k) 스캔 두 번이면 끝난다.

## 버린 대안

- **지표 EAV에 `SUPPORT_1`·`RESISTANCE_1`로 저장** — 배치·보존 정리·무효화를 그대로 재사용하고 목록 화면에서도 싸게 읽힌다. 그러나 `indicator` 카탈로그에 올라가 조건 빌더에 노출되고, 그것을 조건으로 쓰지 못하게 막는 예외 분기가 어딘가에 생긴다. 그 분기는 `backend.md`가 금지한 지표별 하드코딩이고, 무엇보다 **한 번 노출되면 사용자가 이미 신호로 읽는다.** 구조가 주는 보장을 문서로 대체하는 교환이라 받아들이지 않았다.
- **`support_resistance_level` 별도 테이블 + 일일 배치** — 지표와 섞이지 않으면서 목록에서도 읽을 수 있다. 그러나 채우는 배치 단계와 보존 정리를 새로 만들어야 하고, 알고리즘을 고칠 때마다 과거 값이 낡는다. v1에 목록 노출 요구가 없어 그 비용을 지금 지불할 이유가 없다.
- **종목 상세 응답에 필드로 포함** — 엔드포인트가 하나로 줄지만 계산 실패가 상세 전체를 500으로 만든다. spec §8·`frontend.md`가 요구하는 "영역 단위 오류 격리"를 URL 분리가 가장 싸게 이행한다. 모바일 지연 로드도 자연스러워진다.
- **강도를 소수점 점수로** — 정밀해 보이지만 그 정밀함이 신뢰도처럼 읽혀 과신을 부른다. HIGH/MEDIUM/LOW로 거칠게 둔다.
- **`ATR_14` 지표로 밴드 폭 산출** — 이미 있는 값을 재사용하는 셈이지만, 그 지표가 없는 종목에서 지지·저항이 통째로 사라지고 참고정보가 지표 계산에 의존하기 시작한다. 봉에서 직접 평균 변동폭을 구한다.

## 결과

- 쉬워지는 것: 스키마 변경이 0이다. 알고리즘을 고쳐도 마이그레이션·백필이 없다. 조건으로 오용되는 경로가 원리적으로 없어 리뷰에서 그것을 확인할 필요가 없다.
- 어려워지는 것: 결과 표(목록)에 지지·저항을 컬럼으로 넣을 수 없다. 350종목을 한 번에 계산해야 하기 때문이다. v1에 그 요구가 없고, 생긴다면 그때가 별도 테이블을 도입할 시점이다.
- 되돌리려면: `stock` 패키지의 계산기·응답 타입과 상세 API를 함께 고쳐야 한다. 저장을 시작하기로 하면 마이그레이션·배치 단계·보존 정리가 추가된다.
