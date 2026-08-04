---
id: ADR-0002
status: 채택됨
date: 2026-08-05
applies_to:
  - "src/main/resources/db/migration/**"
  - "src/main/java/com/stockscreener/indicator/**"
---

# ADR-0002. 지표는 EAV로 저장하고 수정주가와 원주가를 함께 보관한다

## 결정

지표 값을 `indicator_value(stock_id, trade_date, indicator_code, value)` 행 단위로 저장한다. 시세는 `close`(원주가)와 `adj_close`(수정주가)를 함께 보관하고, 지표 종류에 따라 다른 것을 쓴다.

## 규칙

- MUST: 새 지표는 `indicator` 카탈로그에 행 하나를 추가하는 것으로 끝난다. 컬럼·테이블을 늘리지 않는다.
- MUST: 기술적 지표는 예외 없이 `adj_close`로 계산한다.
- MUST: PER·PBR·시가총액·화면 표시가는 `close`로 계산한다. 시점의 발행주식수와 짝이 맞아야 하고 사용자가 실제 지불하는 가격이어야 한다.
- MUST: 계산할 수 없으면 행을 쓰지 않는다. NULL 행을 만들지 않는다.
- MUST: `indicator_value`의 PK 순서를 `(trade_date, indicator_code, stock_id)`로 유지한다.
- MUST: `idx_iv_screen`(커버링 인덱스)을 유지한다. 스크리닝 성능이 여기에 달려 있다.
- MUST NOT: 폐기된 지표를 삭제하지 않는다. `is_active=false`로 둔다.
- MUST NOT: `screen_condition.indicator_code`에 FK를 걸지 않는다.

## 검증

```bash
grep -rn 'adjClose\|adj_close' src/main/java/com/stockscreener/indicator/calculator/
```
기술적 지표 계산기(SMA 등)가 `adjClose()`를 쓰는지 확인한다. `close()`를 쓰는 것은 재무 지표(PER·PBR)뿐이어야 한다.

```bash
psql -d stock_screener_test -c "\d indicator_value"
```
PK가 `(trade_date, indicator_code, stock_id)` 순이고 `idx_iv_screen`이 존재해야 한다.

```bash
./gradlew test --tests "*IndicatorExtensibilityTest*"
```
`indicator_value`의 컬럼이 5개로 유지되는지(지표 개수와 무관) 확인한다.

## 배경

spec §7이 "지표 데이터는 스키마 변경 없이 새로운 지표를 추가할 수 있는 유연한 구조"를 요구한다.

수정주가 결정은 spec에 없지만 V1을 막는 문제였다. 액면분할 시 원주가로 계산한 이동평균은 무의미해진다. 삼성전자 50:1 분할 기준으로 계산하면 분할 당일 SMA200이 실제의 **50배로 왜곡**되고 거짓 데드크로스가 발생하며, 이후 200거래일간 모든 기술적 지표가 망가진다. 이 오류는 분할이 일어나기 전까지 그럴듯한 값을 내므로 아무도 눈치채지 못한다.

## 버린 대안

- **지표별 전용 컬럼** — 쿼리는 단순하지만 새 지표마다 마이그레이션이 필요해 spec §7과 정면 충돌한다.
- **JSONB 단일 컬럼** — 읽기는 빠르지만 QueryDSL 타입세이프 이점이 사라지고 동적 조건이 JSONB 연산자 문자열 조립으로 흐른다. "이 지표를 비활성화하면 어떤 화면이 깨지는가"도 인덱스 없는 스캔이 된다.
- **수정주가만 저장** — PER 계산 시 시점의 발행주식수와 짝이 맞지 않고, 사용자에게 실제와 다른 가격을 보여주게 된다.
- **원주가만 저장하고 지표 계산 시 조정** — 분할 이력을 매번 조회해야 하고, 조정이 적용되었는지 확인할 수단이 없다. `adj_factor`를 명시적으로 저장하면 재계산 여부를 검증할 수 있다.
- **`stock_id` 선행 PK** — 자연스러워 보이지만 스크리닝이 하루치를 읽을 때 850행이 인덱스 전체에 흩어진다. 야간 대량 upsert가 인덱스 오른쪽 끝에 append되는 이점도 잃는다.

## 결과

- 쉬워지는 것: 지표 추가가 파일 하나로 끝난다(실측 확인). 백분위 같은 파생 지표도 코드만 다른 평범한 행이라 스키마 변경이 없다.
- 어려워지는 것: 임의 컬럼 정렬이 자연스럽지 않다. 사용자가 한 번에 한 컬럼으로만 정렬한다는 점이 완화 근거이며, 정렬용 상관 스칼라 서브셀렉트 하나로 해결한다.
- 실측: 7,650,000행(850종목 × 150지표 × 60일)에서 조건 3개 AND 스크리닝이 **0.185ms**, Index Only Scan.
- 되돌리려면: 마이그레이션과 모든 지표 계산기를 함께 고쳐야 한다. Phase 2에서 데이터가 적재된 뒤에는 비용이 급증한다.
