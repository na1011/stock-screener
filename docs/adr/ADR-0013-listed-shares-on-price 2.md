---
id: ADR-0013
status: 채택됨
date: 2026-08-06
applies_to:
  - "src/main/resources/db/migration/V9__price_listed_shares.sql"
  - "src/main/java/com/stockscreener/indicator/calculator/**"
  - "src/main/java/com/stockscreener/pipeline/datasource/RawPriceBar.java"
---

# ADR-0013. 발행주식수는 시세와 함께 날짜별로 저장하고 재무는 폴백으로 둔다

## 결정

PER·PBR의 분모인 상장주식수를 `price_daily.listed_shares`에 **날짜별로** 저장한다. 소스는 이미 받고 있는 시세 응답의 `lstgStCnt`다. `financial_statement.shares_outstanding`은 지우지 않고 **폴백**으로 남긴다.

## 규칙

- MUST: 지표 계산은 **시세 쪽 주식수를 우선**하고, 없을 때만 재무 쪽을 쓴다(`FinancialMath.sharesOf`).
- MUST: PER·PBR은 주식수를 **봉마다** 읽는다. 하나로 고정하면 증자·분할 구간의 값이 통째로 틀린다.
- MUST NOT: 주식수를 얻으려고 DART `stockTotqySttus`를 종목마다 호출하지 않는다. 같은 정보에 쿼터를 두 번 쓴다.
- MUST NOT: `financial_statement.shares_outstanding` 컬럼을 지우지 않는다. 소스에 따라 재무 쪽에만 있을 수 있다.

## 검증

```bash
./gradlew test --tests "*PerCalculatorTest*" --tests "*PbrCalculatorTest*"
```
"시세의 상장주식수를 재무보다 우선한다"와 "시세에 없으면 재무로 폴백한다"가 통과해야 한다.

```sql
SELECT count(*) FILTER (WHERE listed_shares IS NULL) AS 빈값, count(*) FROM price_daily;
```
국내 종목은 빈값이 0이어야 한다. 대량으로 비면 소스가 `lstgStCnt`를 주지 않기 시작한 것이다.

```sql
SELECT count(DISTINCT stock_id) FROM indicator_value WHERE indicator_code = 'PER';
```
0이면 이 결정이 무너진 것이다 — 분모가 없어 PER이 하나도 계산되지 않는 상태다.

## 배경

**실제 적재를 돌려보고서야 드러났다.** `financial_statement.shares_outstanding`이 398행 전부 NULL이었고, 그 결과 PER·PBR이 **하나도 계산되지 않는다.** DART 어댑터에 "발행주식수는 `stockTotqySttus` 별도 호출이 필요하다. Phase 3에서 붙인다"는 주석이 남아 있던 미완성이며, Phase 3에서 그것을 놓쳤다.

단위 테스트가 이 구멍을 잡지 못한 이유가 중요하다. 픽스처에 주식수를 직접 넣어 계산기를 검증했으므로 **계산기는 옳았고 입력이 비어 있었다.** 어댑터·writer·리더·계산기가 각각 자기 몫을 하는데 전체로는 값이 나오지 않는, 통합 지점의 결함이다.

두 소스를 실측 비교했다.

| | DART `stockTotqySttus` | 시세 `lstgStCnt` |
|---|---|---|
| 삼성전자 값 | 보통주 5,919,637,922 / 우선주 815,974,664 / 합계 6,735,612,586 | 5,846,278,608 |
| 추가 호출 | **종목·연도당 1콜** (7년치 +2,450콜) | **0** (이미 받는 응답에 포함) |
| 시간 해상도 | 분기 | **날짜별** |
| 보통주 구분 | 직접 골라야 함 | 우선주가 별도 종목이라 자동 |

시세 쪽이 세 가지 모두에서 낫다. 특히 **날짜별**이라는 점이 결정적이다 — 유상증자·액면분할로 주식수가 바뀌면 그날부터 EPS가 달라져야 하는데, 분기 재무에 묶으면 다음 공시까지 낡은 수로 나눈다. 그 오류는 값이 그럴듯해서 눈치채기 어렵고, ADR-0002가 수정주가에서 경계한 것과 정확히 같은 계열이다.

재무 쪽 컬럼을 남기는 이유는 시장 중립성이다. 국내 시세 API는 `lstgStCnt`를 주지만 그것을 주지 않는 소스가 있을 수 있고(미국이 그럴 수 있다), 그때는 재무 쪽만 채워진다. 지표 계층이 한쪽만 보게 만들면 그 시장에서 재무 지표가 통째로 사라진다.

## 버린 대안

- **DART `stockTotqySttus`를 종목마다 호출** — 공시 원본이라 권위 있지만 7년치에 2,450콜이 추가되고, 분기 해상도라 증자·분할 구간이 여전히 틀린다. 보통주·우선주를 직접 골라야 하는 부담도 있다.
- **재무 테이블에만 두고 시세 응답의 값을 재무 행에 채우기** — 스키마 변경이 없지만 시간 해상도 문제가 그대로 남는다. 분기 행에 날짜별 값을 욱여넣는 셈이라 어느 날짜의 값인지도 불명확해진다.
- **시가총액(`mrktTotAmt`) ÷ 종가로 유도** — 추가 컬럼이 없지만 소스의 계산값에서 역산하는 것이라 spec §7("원자료에서 자체 계산")의 취지에 어긋나고, 반올림 오차가 주식수에 그대로 남는다.
- **재무 쪽 컬럼 삭제** — 중복이 사라져 깔끔하지만, 시세가 주식수를 주지 않는 소스에서 재무 지표가 통째로 없어진다. 폴백을 남기는 비용이 컬럼 하나뿐이다.

## 결과

- 쉬워지는 것: 추가 호출 없이 PER·PBR·ROE가 계산된다. 증자·분할이 그날부터 반영되어 분할 구간의 값이 정확해진다.
- 어려워지는 것: 주식수의 출처가 둘이라 "어느 쪽이 쓰였는가"를 물으려면 `sharesOf`의 우선순위를 알아야 한다. 그 대가로 시장 중립성을 얻는다.
- 되돌리려면: V9 컬럼과 `sharesOf`, 그리고 PER·PBR의 봉 단위 루프를 함께 고쳐야 한다. 이미 적재된 `listed_shares`는 시세를 다시 받으면 복구된다.
