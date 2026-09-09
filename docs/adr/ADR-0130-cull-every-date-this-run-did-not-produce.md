---
id: ADR-0130
status: 채택됨
date: 2026-09-09
applies_to:
  - "src/main/java/com/stockscreener/indicator/IndicatorRunner.java"
  - "src/test/java/com/stockscreener/probe/backtest/StoredAsOfParityProbeTest.java"
  - "src/test/java/com/stockscreener/probe/backtest/StoredCoverageBoundaryProbeTest.java"
---

# ADR-0130. 걷기를 꼬리에서 「이번 실행이 안 낸 날짜 전부」로 넓힌다

## 결정

`IndicatorRunner`의 걷기 후보를 **마지막 산출 일자 뒤**(ADR-0085 §결정 3)에서
**이번 실행이 안 낸 창의 거래일 전부**로 넓힌다. 가드 넷은 그대로 태운다 —
억제 일자 제외 · 지수 인지 코드의 날짜별 지수 존재 · `inputsWereReadable` · 창의 거래일 상한.

**이것은 ADR-0080 §버린 대안이 기각한 안이고, 그 기각을 다시 연다.** 근거는 아래 §왜 다시 여는가.

## 왜 다시 여는가 — 기각을 받치던 것 둘이 그 뒤에 무너졌다

기각 원문은 *"정상적으로 일부만 내는 계산기가 여럿이라 그들의 보존 창이 매 실행 깎인다.
백필이 날짜를 바깥 루프로 돌므로 **뒤 날짜가 앞 날짜를 지운다**"*이고, 근거가 둘이다.

| 기각 근거 | 무너뜨린 결정 |
|---|---|
| 「일부만 내는 계산기의 보존 창이 깎인다」 | **ADR-0079**(2026-08-21)가 `readWindowBars() = maxEffectiveLookback() + RETAINED_TRADING_DAYS`를 세워 **보존 창의 모든 날짜가 어떤 `asOf`에서든 최대 워밍업을 받는다.** 지금 비는 자리는 「창이 짧아서」가 아니라 **그 날짜에 값이 성립하지 않아서**다 |
| 「뒤 날짜가 앞 날짜를 지운다」 | **ADR-0128**(2026-09-08)이 재무 지표를 봉마다 `KNOWN_ON`으로 내게 해 **날짜 d의 값이 `asOf`와 무관**해졌다(그 문서 §결과가 그렇게 적는다) |

**꼬리만 보면 원리적으로 못 보는 것이 있었다.** ADR-0085 §결과가 그것을
*"못 보는 것: 내부에 남은 낡은 행"*으로 이미 적어 뒀고, 이 결정이 그 자리를 닫는다.

## 무엇이 남아 있었나 — 어느 시점 규칙의 값도 아닌 행

ADR-0128은 계산을 바꾸고 값을 다시 썼지만 **새 규칙이 못 내는 날짜의 옛 행은 안 지웠다** —
`IndicatorValueWriter.write`가 UPSERT라 **산출한 날짜만** 덮고, ADR-0080의 삭제는
**창 전체가 빌 때만**, ADR-0085의 걷기는 **꼬리만** 본다. 그 행들이 지금도 과거 기준일 조회의
판정에 실린다.

**대조군이 갈랐다**(2026-09-09 · `StoredCoverageBoundaryProbeTest`). 같은 날짜를 `KNOWN_ON`과
`LATEST` 둘로 계산해 저장본과 맞춰 보면, 그 행들은 **어느 쪽과도 안 맞는다.**

| 종목·날짜 | 저장 | `KNOWN_ON`과 일치 | `LATEST`와만 일치 | 어느 쪽도 아님 |
|---|---|---|---|---|
| `WAT` 2025-12-24 | 5코드 전부 있음 | 1 | 0 | **4** |
| `WAT` 2026-02-24 | 5코드 전부 있음 | 2 | 1 | 2 |
| `WAT` 2026-05-20 이후 | 5코드 전부 있음 | **5** | 0 | 0 |

즉 **하네스가 덜 내는 것이 아니다** — 가장 관대한 `LATEST`로도 안 나오므로 그 행은
**옛 판의 잔재**다.

**통과 집합까지 움직인다**(`StoredAsOfParityProbeTest` · 프리셋 `BUFFETT_QUALITY` ·
조회 대상 3 × 과거 as-of 4). **12조합 중 8이 갈렸고 방향이 한쪽뿐이다** — 「재계산에만」 0,
「저장에만」이 날짜당 1~2종목(`319660` `278470` `WAT` `UI` `ANET`).

**규모**(2026-09-09 · `US_TOP1000`). 옛 180거래일 창의 경계(2025-12-16 → 12-17)에서
**60코드가 계단**을 이룬다 — 합 **2,515칸**, 그날 US 총 118,235행의 **2.1%**.
큰 것부터 `PEG` 201 · `EPS_CAGR_5Y` 138 · `EPS_GROWTH_TTM_YOY` 77 ·
`RETAINED_TO_MARKET_4Y` 75 · `PER` 65. **새로 연 74일자 쪽이 낮다** — 그쪽이 옳고
**옛 창 구간이 오염된 것**이다.

## 규칙

- **MUST: 후보를 창의 거래일로 묶는다.** `retainDates`가 보존 경계로 한 번 더 거르므로 삭제
  상한이 `asOf`에 구조적으로 묶인다. 그 상한이 없으면 과거 일자 백필이 그 뒤의 라이브 행을 지운다.
- **MUST: 억제 일자를 후보에서 뺀다.** 그 날짜는 억제 분기가 소유하고 보존 창으로 거른 범위만
  지운다. 여기서 또 넣으면 소유가 둘이 되어 한쪽을 고칠 때 다른 쪽이 조용히 남는다.
- **MUST: 지수를 쓰는 코드는 그 날짜에 지수가 있는 날짜만 후보로 둔다.** `DataRequirement`의
  축은 「통째로 없는가」만 가르므로 **날짜별 지수 결손**은 거기서 안 걸리고, 그대로 두면 지수
  적재가 실패한 날 `BETA_104W`·`EXCESS_RETURN_*`이 조용히 지워진다.
- **MUST: `inputsWereReadable`을 그대로 통과시킨다.** 「값이 없다」와 「입력을 못 읽었다」를
  가르는 것이 ADR-0045가 닫은 삭제→재기록 왕복을 막는 유일한 축이다.
- **MUST: 걷기가 돈 뒤에는 파생층을 창 전체로 다시 돌린다** — `--from`을 창 하한으로 준
  날짜 루프다(`batch-load` skill의 ②). 걷기가 원본을 지우면 `DerivedIndicatorCleanup`이 그
  백분위를 함께 걷는데, **다시 매기는 것은 돌린 날짜뿐**이라 나머지 날짜의 백분위가 **줄어든
  모집단으로 매겨진 옛 값**으로 남는다. **base 한 판만 돌리면 그 상태가 된다** —
  실제로 2026-09-09에 그렇게 됐고 ADR-0036의 premise가 *"140거래일이 유니버스 안에서 매겨지지
  않았다"*로 잡았다. 실측 **5분 17초 · 269거래일 · 11,158행**.
  **값을 바꾸는 결정(ADR-0132·0133)에도 똑같이 걸린다.**
- **MUST NOT: 계산기가 「나는 일부러 적게 낸다」를 선언하게 만들지 않는다.** ADR-0080 §버린
  대안이 기각했고 그 근거(*"잊으면 증상이 조용한 데이터 삭제"*)는 **아직 선다.**
- **MUST: `asOf`에 따라 커버리지가 움직이는 지표가 남아 있다는 것을 안다.**
  `RETAINED_TO_MARKET_4Y`가 그것이고(앵커를 `bars.get(0)` 기준으로 찾는다 — ADR-0129 §결과),
  그 부류에서는 다른 `asOf`로 돌린 실행이 서로의 행을 지웠다 다시 쓸 수 있다. **어느 값이
  옳은가는 ADR-0129가 이미 판정했다(새 값이다).** 왕복이 실제로 도는지는 아래 §검증이 잰다.

## 검증

```bash
./gradlew test --tests "*IndicatorRunnerTest*"
```

**대조군은 뒤집힌 단언이다** — `cullsRowsInsideTheWindowThisRunDidNotProduce`가 창 안쪽에
구멍을 뚫고 그 자리의 행이 사라지는 것을 본다. 앞선 판은 같은 자리에서 `isNotNull`이었고,
production 변경이 안 걸리면 그 시험이 그대로 붉는다.

```premise 저장 지표에 어느 시점 규칙으로도 재현되지 않는 행이 남아 있는가
psql -d stock_screener -At -c "WITH act AS (SELECT DISTINCT m.stock_id FROM universe_membership m JOIN universe u ON u.code = m.universe_code WHERE m.effective_to IS NULL AND u.is_active AND u.code = 'US_TOP1000'), d AS (SELECT trade_date, row_number() OVER (ORDER BY trade_date) rn FROM indicator_trade_date WHERE market_code = 'US'), edge AS (SELECT max(trade_date) FILTER (WHERE rn = 74) prev, max(trade_date) FILTER (WHERE rn = 75) cur FROM d), a AS (SELECT indicator_code, count(*) n FROM indicator_value, edge WHERE trade_date = edge.prev AND stock_id IN (SELECT stock_id FROM act) GROUP BY 1), b AS (SELECT indicator_code, count(*) n FROM indicator_value, edge WHERE trade_date = edge.cur AND stock_id IN (SELECT stock_id FROM act) GROUP BY 1) SELECT CASE WHEN (SELECT count(*) FROM b) = 0 THEN '잴 것이 없었다 — 그 거래일에 US 지표가 한 행도 없다' WHEN (SELECT count(*) FROM edge WHERE prev IS NULL OR cur IS NULL) > 0 THEN '잴 것이 없었다 — US 지표 달력이 75거래일에 못 미친다' WHEN (SELECT sum(greatest(b.n - coalesce(a.n, 0), 0)) FROM b LEFT JOIN a USING (indicator_code)) <= 200 THEN 'OK 옛 보존 창 경계의 계단이 ' || (SELECT sum(greatest(b.n - coalesce(a.n, 0), 0))::text FROM b LEFT JOIN a USING (indicator_code)) || '칸이다(' || (SELECT count(*) FROM b) || '코드 검사 · 기준선 43)' ELSE (SELECT sum(greatest(b.n - coalesce(a.n, 0), 0))::text FROM b LEFT JOIN a USING (indicator_code)) || '칸이 경계 뒤쪽에만 있다 — 옛 판의 잔재가 안 걷혔다. 보존 창을 한 판 다시 돌린다' END"
```

**이 전제가 재는 것은 「값이 맞는가」가 아니라 「경계에 계단이 있는가」다.** 계단은 옛 창의
경계에서만 생기므로, 창을 다시 넓히면 그 경계도 함께 움직인다 — `rn = 74/75`가 그 뜻이고
**보존 창 길이를 바꾸면 이 전제의 수를 함께 고친다.**

**문턱을 0이 아니라 200으로 둔 근거는 잔여가 정상 동작이라는 것이다.** 걷기 전 **2,515칸**이던
것이 걷은 뒤 **43칸**인데, 그 43은 **그날 모집단이 1종목 늘어서** 생긴다 — `ADJ_CLOSE`와
`TRADING_DAYS_750`이 각각 +1이고(그 종목의 첫 봉이 그날이다), 업종 집계가 `MIN_PEERS`를
넘으며 `PER_SECTOR_*` +5 · `OPERATING_MARGIN_SECTOR_*` +3으로 번진다. **0으로 두면 이 전제가
매 세션 거짓 발견을 낸다.** 여유를 4배 넘게 둔 것은 그 잔여가 **모집단이 늘어나는 날 커지기**
때문이고, 커지는 방향이 그것 하나라 상한이 폭주하지 않는다.

## 버린 대안

| 안 | 왜 버렸나 |
|---|---|
| **손으로 쓴 마이그레이션을 한 장 더 쓴다** | ADR-0080이 *"두 번 반복된 것을 세 번째로 반복하는 안"*으로 기각했고 그 근거가 그대로 선다 — 지금 낡은 것은 지우지만 **계산기를 고칠 때마다 다시 쌓인다.** 실제로 이번 잔재가 ADR-0128이라는 **계산기 변경**에서 나왔다 |
| **꼬리 걷기를 그대로 두고 결손을 사실로 기록한다** | 그 행이 **화면의 판정에 실린다.** 드러내기만 하는 형태는 ADR-0079 §버린 대안이 2026-08-21에 사용자 판단으로 기각한 것이다 |
| **저장 경로는 두고 백테스트만 시점 재계산을 쓴다** | 하네스는 지켜지지만 **앱은 안 고쳐진다.** 과거 기준일 조회가 같은 행을 읽으므로 막는 것이 한쪽뿐이다(2026-09-09 사용자 판단으로 기각) |
| **계산기가 「안 내는 날짜」를 선언하게 한다** | ADR-0080 §버린 대안이 기각했고 근거가 아직 선다(§규칙) |
| **`points`가 빌 때도 같은 여집합을 지운다** | 그 갈래는 빈-목록 분기가 소유하고 `inputsWereReadable`로 「아직 모른다」를 가른다. 여기서 또 지우면 소유가 둘이 된다 |

## 결과

**쉬워지는 것** — 계산기를 고쳐 어느 날짜의 값이 사라지면 **다음 배치가 그 자리의 옛 행을
스스로 걷는다.** 그전에는 꼬리만 걷혀 **창 안쪽과 앞쪽이 영구히 남았다.**

**어려워지는 것**

- **삭제 스캔이 코드마다 창 전체를 본다.** ADR-0080이 잰 삭제 비용(+18%)의 성질은 같고
  후보 집합만 넓어진다.
- **`asOf`에 따라 커버리지가 움직이는 지표에서 왕복이 생길 수 있다**(§규칙의 마지막 MUST).

### 적용 실측 — 3종목 대조군 (2026-09-09 · `--tickers=WAT,ANET,UI` 단일 날짜)

되돌리기 파일: `~/Library/Application Support/stock-screener/dumps/cull-head-20260909-before.psv`
(93,812행).

| 종목 | 전 | 후 | 차 | 러너가 신고한 걷기 |
|---|---|---|---|---|
| `WAT` | 30,022 | 27,668 | **−2,354** | 28코드 1,727행 |
| `ANET` | 30,763 | 29,283 | **−1,480** | 24코드 960행 |
| `UI` | 33,027 | 32,782 | **−245** | 3코드 245행 |

**차가 신고보다 큰 것은 파생 고아 정리다** — 원본이 사라지면 `DerivedIndicatorCleanup`이
그 백분위를 함께 걷고, 그것은 러너의 로그에 안 잡힌다.

**멱등**: 같은 인자로 한 번 더 돌려 **걷기 로그 0줄 · 행 수 그대로**.

**잔재가 실제로 사라졌다**: `WAT` 2025-12-24가 걷기 전 「`KNOWN_ON` 1 · `LATEST`만 0 ·
어느 쪽도 아님 **4**」에서 걷은 뒤 「`KNOWN_ON` 1 · 나머지 **0**」이 됐다.

### 적용 실측 — 전 종목 판 (2026-09-09 15:16~15:39 · `--as-of=--from=2026-09-08`)

**22분 15초 · 1,350종목 · 성공 1,350 · 실패 0 · `written_row_count` 0**(값이 바뀐 행은 없다 —
걷기만 돌았다는 뜻이다).

| 축 | 값 |
|---|---|
| 걷기가 걸린 종목 | **796** / 1,350 |
| 러너가 신고한 삭제 | **184,977행** |
| 표에서 실제로 줄어든 행 | **285,498행**(42,299,315 → 42,013,817) |
| 시장별 | US **−207,142** · KOSPI **−41,529** · KOSDAQ **−36,827** |
| 경계 계단 | **2,515 → 43칸**(−98.3%) |

**신고와 실제의 차 100,521행이 파생 고아 정리다** — 3종목 대조군과 같은 성질이고,
`DerivedIndicatorCleanup`은 러너의 로그에 안 잡힌다.

**국내 두 시장이 미국의 5분의 1인 것이 이 결함의 모양을 말한다** — 국내 봉은
`listed_shares`가 차 있어 재무 폴백을 안 타므로(ADR-0097) 잔재가 재무 유래 코드에 몰린다.

**멱등**: 가장 많이 걷힌 셋(`HRB` 3,408 · `IFF` 2,361 · `QXO` 1,811)을 같은 인자로 다시 돌려
**걷기 로그 0줄 · 총 행 수 그대로**(42,013,817).

**이 결정이 실제로 닫은 것은 통과 집합이다**(`StoredAsOfParityProbeTest` 전후).

| | 걷기 전 | 걷은 뒤 |
|---|---|---|
| 통과 집합이 갈린 조합 | **8 / 12** | **0 / 12** |
| 「저장에만」 종목 | 날짜당 1~2 | **0** |
| 점수만 갈린 조합 | 4 | **4**(그대로) |

**점수 축은 이 결정이 안 닫는다** — 포함 여부는 필수 게이트가 정하므로 배점 열이 틀려도 집합은
안 움직인다. 남은 4건이 위 §남은 것의 값 수준 look-ahead이고 **`MARKET_CAP`이 배점에 실린다.**

### 남은 것 — 이 결정이 안 닫는 부류 하나 (2026-09-09)

**값 수준의 look-ahead는 걷기로 안 닫힌다.** 걷기는 **이번 실행이 안 낸 날짜**만 보는데,
이번 실행이 값을 내되 **그 값이 시점 재현이 아니면** 그 행은 그대로 덮여 남는다.

실측으로 `MARKET_CAP`이 그 부류다 — 저장값이 `LATEST`와 **정확히 일치**하고 `KNOWN_ON`과 갈린다.

| 저장(=`LATEST`) | `KNOWN_ON` | 차 |
|---|---|---|
| 17,801,570,726.94 | 17,845,929,415.30 | 0.25% |
| 20,541,187,395.30 | 20,592,372,773.50 | 0.25% |
| **19,426,185,662.00** | **32,010,640,507.30** | **65%** |
| 17,974,728,700.80 | 29,618,916,892.32 | 65% |
| 19,907,226,361.20 | 32,803,303,624.98 | 65% |

**원인은 호출부다.** ADR-0128 §규칙이 *"재무에서 값을 내는 계산기는 `byPublication`이나
`bySegment`를 반드시 거친다"*로 못박았는데, **넷이 어느 쪽도 안 거치고
`context.quarterlyFigures()`를 직접 쓴다** — `MarketCapCalculator` ·
`SharesGrowth3yCalculator` · `TurnoverRatio60Calculator` · `TurnoverRatioCalculator`.
`sharesOf`의 `publishedIndexOn`이 **기(期)**는 봉 시점으로 고르지만, 컨텍스트가
`latestPerPeriod`로 **오늘 기준 최신 개정본**을 접어 둔 뒤라 **판(revision)이 미래의 것**이다.
(`GovernanceMath`는 자기 축으로 이미 고르므로 설계다 — ADR-0128 §결과.)

**이것은 별도 결정이다** — `MARKET_CAP`이 유니버스 순위와 버핏 시총 게이트에 실리므로
전후 대조를 먼저 만든다. 사용자가 연 축(`FinancialMath.sharesOf`의 시점 정합)과 같은 함수의
호출부라 그쪽으로 묶는다.

**되돌리려면** `windowDatesWithoutProduction`의 필터를 `date.isAfter(lastProduced)`로 되돌리고
`IndicatorRunnerTest.cullsRowsInsideTheWindowThisRunDidNotProduce`의 단언을 뒤집는다.
**되돌리는 순간 옛 행이 다시 쌓이고 아무 에러도 안 난다.**
