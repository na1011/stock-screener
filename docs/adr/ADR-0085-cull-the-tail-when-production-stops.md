---
id: ADR-0085
status: 채택됨
date: 2026-08-28
applies_to:
  - "src/main/java/**/indicator/IndicatorRunner.java"
  - "src/main/java/**/indicator/DataRequirement.java"
  - "src/main/java/**/indicator/SectorRelativeCalculator.java"
---

# ADR-0085. 계산이 그만 난 자리를 「창 전체가 빌 때」 말고 셋에서 더 걷는다

## 결정

**이 결정은 `IndicatorRunner.java`를 ADR-0080과 공동 지배한다.** 그쪽이 「창 전체가 빈 경우」를
소유하고, 이쪽은 그것이 **안 걸리는 세 자리**를 소유한다. 어느 한쪽만 읽고 다른 쪽을 지우지 않는다.

1. **억제 게이트를 보존 창으로 거른 집합으로 판정한다** — ADR-0080 구현의 버그 수정이다.
2. **시장 지수를 `DataRequirement`의 셋째 입력 축으로 세운다** — ADR-0080 §결과가 적어 둔 구멍이다.
3. **마지막 산출 일자 뒤에 남은 창의 거래일을 걷는다**(꼬리 걷기).
4. **업종 상대값이 그날 안 쓰기로 한 자리의 옛 행을 걷는다** — 러너 밖이라 1~3이 원리적으로 못 닿는다.

## 규칙

- **MUST: 억제 게이트와 억제 삭제가 같은 집합을 본다.** 삭제는 `retainDates(suppressed, cutoff)`로
  거르는데 게이트가 거르지 않은 집합을 보면, **억제 일자가 전부 컷오프 아래일 때 삭제는 0행이고
  걷기는 거부된다.** 그 조합의 종목은 걷기에서 **영구히 면제**된다.
  - **MUST NOT: `if (!suppressed.isEmpty())` 진입 조건까지 바꾸지 않는다.** 거기를 바꾸면
    `suppressedCodes`가 안 올라가 **「미보정 기업 행위로 지표를 뺐다」 로그가 사라진다** — 그 로그가
    「이 종목이 왜 화면에서 빠졌는가」를 답할 수 있는 유일한 흔적이다.
- **MUST: 꼬리만 본다.** ADR-0080 §버린 대안이 「창 안에서 안 낸 날짜를 전부 지운다」를 기각한
  근거는 **정상적으로 일부만 내는 계산기**였는데, 열거된 넷이 비우는 곳이 전부 **앞쪽**이라
  꼬리에는 안 걸린다. 백필의 날짜 바깥 루프도 안전하다 — 창이 `asOf`에서 끝나고
  `lastProduced <= asOf`라 삭제 상한이 구조적으로 묶인다.
- **MUST: 꼬리 후보에서 억제 일자를 뺀다.** 그 날짜는 억제 분기가 소유하고 그쪽은 보존 창으로
  거른 범위만 지운다. 여기서 또 넣으면 **소유가 둘이 되어 한쪽을 고칠 때 다른 쪽이 조용히 남는다.**
- **MUST: 시장 지수를 쓰는 코드는 그 날짜에 지수가 있는 날짜만 꼬리 후보로 둔다.**
  `ExcessReturn.at`이 지수를 정확 일치로 묻고 없으면 건너뛰므로, 창 **끝쪽**의 지수 결손은
  `points`를 비우지 않으면서 `lastProduced`만 앞으로 당긴다.
  - **MUST: 입력 축이 「통째로 없는가」만 가른다는 것을 안다.** `IndicatorContext`가 `null`을 빈 맵으로
    접으므로 판정이 결국 「맵이 비었는가」다. **부분 결손은 원리적으로 거기서 못 잡는다.**
    같은 한계가 `needsFinancial`·`needsGovernance`에도 있다.
  - **MUST NOT: 같은 날짜 필터를 거버넌스에 걸지 않는다.** 앵커가 없어 값이 없는 것은 **결손이
    아니라 사실**이고, 그것이 정확히 이 걷기가 걷어야 하는 것이다.
- **MUST: 업종 상대값의 삭제 술어를 UPSERT의 `WHERE`와 정확히 여집합으로 둔다.** 그 조건은
  `peers >= ? AND … IS NOT NULL`이라 **배제 사유가 둘**이고, 표본 수만 보면 **중앙값이 0 이하라
  값이 빠진 자리**(자본잠식 업종)의 옛 행이 남는다. 표본 수 코드도 함께 지운다.
  - **MUST NOT: 「이번에 안 썼다」로 판정하지 않는다.** `DerivedIndicatorCleanup`의 javadoc이 그것을
    금지하는데, **거기서 금지하는 것은 UPSERT가 반환한 행 수를 기준으로 삼는 것**이다
    (`IS DISTINCT FROM` 때문에 값이 그대로인 날은 0행이라 멀쩡한 행을 지운다). 이 결정의 기준은
    **집합의 소속**이라 값이 안 바뀐 날에도 그 종목은 `chosen`에 있어 안 지워진다. **둘을 같은
    것으로 읽으면 이 결정이 그 javadoc과 모순으로 보인다.**

## 검증

```bash
./gradlew test --tests "*IndicatorRunnerTest*" --tests "*SectorRelativeCalculatorTest*"
```

**단언이 짝을 이룬다 — 지워야 할 것 하나에 지우면 안 되는 것 여럿이다.** 뒤엣것들이 없으면
「전부 지운다」로도 초록이 된다. **각 단언은 절제로 확인했다**(해당 분기를 되돌리면 그 단언만 붉어진다):

| 단언 | 무엇을 끄면 붉어지는가 |
|---|---|
| `cullsWhenSuppressionIsEntirelyBelowTheRetentionCutoff` | 게이트를 `suppressed.isEmpty()`로 되돌린다 |
| `keepsRowsWhenTheMarketIndexCouldNotBeReadAtAll` | `needsMarketIndex` 판정을 `true`로 만든다 |
| `deletesRowsAfterTheLastProducedDate` | 꼬리 걷기를 끈다 |
| `keepsRowsBeforeTheLastProducedDate` | 꼬리 제한(`isAfter(lastProduced)`)을 없앤다 |
| `doesNotCullTheTailWhenTheMarketIndexIsMissingOnThatDate` | 지수 날짜 필터를 없앤다 |
| `deletesRowsWhenThePopulationFallsBelowTheThreshold` | 업종 삭제를 끈다 |
| `deletesRowsWhenTheMedianGuardRemovedTheValue` | 삭제 술어에서 `IS NOT NULL`을 뺀다 |
| `keepsRowsOnDaysWhenTheUpsertWroteNothing` | 술어를 「UPSERT가 반환한 행 수」로 바꾼다 |

**MUST: 보존 컷오프를 non-empty로 주는 단언이 하나 있어야 한다.**
`retainDates(dates, Optional.empty())`는 입력을 그대로 돌려주므로 **빈 컷오프로 쓰면 새 게이트와
옛 게이트가 같아져 항등식이 된다.** 기존 걷기 단언 넷이 전부 `Optional.empty()`를 쓴다.

**MUST NOT: 「값이 바뀐 행 0」·「`asOf` 이후 삭제 0」을 합격 기준으로 쓰지 않는다.** 전자는
`DELETE`만 하는 변경에서 자동 참이고, 후자는 후보가 `windowDates`에서만 나오므로 구조적으로 참이다.
**MUST NOT: 「삭제한 행의 값이 오늘 계산값과 다르다」를 쓰지 않는다.** 지우는 조건이 「오늘 그
날짜에 값이 없다」이므로 **비교 대상이 정의상 없다** — 무조건 통과하면서 반증 가능해 보인다.

```premise 앵커가 적자 연도로 옮겨간 뒤에도 남은 배당성향 행이 없다
psql -d stock_screener -At -c "WITH anchored AS (SELECT v.stock_id, v.trade_date, (SELECT g.fiscal_year FROM governance_fact g WHERE g.stock_id = v.stock_id AND g.metric_key = 'CASH_DIVIDEND_TOTAL' AND g.published_at <= v.trade_date ORDER BY g.fiscal_year DESC, g.published_at DESC LIMIT 1) fy FROM indicator_value v WHERE v.indicator_code = 'DIVIDEND_PAYOUT'), bad AS (SELECT a.stock_id, count(*) n FROM anchored a JOIN financial_statement f ON f.stock_id = a.stock_id AND f.fiscal_year = a.fy AND f.fiscal_period = 'FY' AND f.is_latest AND COALESCE(f.net_income_controlling, f.net_income) <= 0 GROUP BY 1) SELECT CASE WHEN (SELECT count(*) FROM anchored WHERE fy IS NOT NULL) = 0 THEN '잴 것이 없었다 — 앵커가 잡히는 배당성향 행이 하나도 없다' WHEN (SELECT count(*) FROM bad) = 0 THEN 'OK 앵커 회계연도가 적자인 배당성향 행이 없다(' || (SELECT count(*) FROM anchored WHERE fy IS NOT NULL) || '행 검사)' ELSE (SELECT sum(n)::text FROM bad) || '행이 적자 앵커 위에 남아 있다 — 꼬리 걷기가 안 도는 것이다(ADR-0085)' END"
```

**적자 판정은 배당성향의 실제 분모로 한다** — 2026-08-30부터 그것이
`net_income_controlling`이고(ADR-0098), 없으면 `net_income`으로 폴백하는 것까지 계산기와 같다.
**`net_income`으로 재면 377행이 거짓 발견으로 뜬다**(실측: 그 377행 전부가 「연결 총액은 적자인데
지배주주는 흑자」이고 둘 다 적자인 행은 0이다). **분모를 바꾸는 결정은 이 펜스를 함께 고쳐야 한다.**

**모집단을 「배당성향 전량」이 아니라 「앵커가 잡히고 그 해가 적자인 행」으로 좁힌 것이 요점이다.**
「마지막 값 일자가 그 종목의 마지막 지표 일자보다 이르다」로 넓게 물으면 **정상 중단이 224건**
(`GOLDEN_CROSS_AGE` 173 + 거버넌스 공시일 직전 51) 걸려 매 세션 거짓 발견이 난다.
**이 좁힘이 자라는 자리**는 「분모 가드로 값이 사라지는 거버넌스 지표」이며, 지금은 배당성향
하나다 — 늘면 `metric_key`와 분모를 여기 함께 더한다.

```premise 최신 거래일에 표본 미달인데 남은 업종 상대값이 없다
psql -d stock_screener -At -c "WITH d AS (SELECT max(trade_date) t FROM indicator_value WHERE indicator_code LIKE '%\_SECTOR\_N'), bad AS (SELECT n.stock_id, n.indicator_code FROM indicator_value n, d WHERE n.trade_date = d.t AND n.indicator_code LIKE '%\_SECTOR\_N' AND n.value < 5) SELECT CASE WHEN (SELECT t FROM d) IS NULL THEN '잴 것이 없었다 — 업종 표본 수 행이 하나도 없다' WHEN (SELECT count(*) FROM bad) = 0 THEN 'OK 최신 거래일에 표본 미달로 남은 업종 상대값이 없다' ELSE (SELECT count(*)::text FROM bad) || '행이 표본 5 미만인데 남아 있다 — 여집합 삭제가 안 도는 것이다(ADR-0085)' END"
```

**최신 거래일만 묻는다.** 업종 상대값은 **돌린 날짜에만** 나므로(`batch-load` skill), 과거 일자에는
그날의 모집단으로 쓴 값이 남아 있는 것이 정상이다. 전 구간을 물으면 그것이 매 세션 거짓 발견이 된다.

## 배경

`docs/progress.md`의 「ADR-0080의 걷기가 안 걸리는 자리가 있다」를 갈랐다. ADR-0082 §결과가
006400 삼성SDI 하나를 적으면서 *"왜인지는 재보지 않았다. 늘어나면 그 경로를 먼저 본다"*로
남겨 둔 자리다.

전수(2026-08-28, 정의: `(종목, 지표)`별 마지막 값 일자 < 그 종목의 마지막 지표 일자)는
**246쌍 / 188종목 / 11지표**였고, 갈라 보니 **정상 224 · 결함 2 · 러너 밖 20**이었다.

| 부류 | 쌍 | 판정 |
|---|---|---|
| `GOLDEN_CROSS_AGE` | 173 | **정상.** 마지막 행 값이 **전부 정확히 120 = `SEARCH_DAYS`**라 그 뒤에 낼 수 있는 값이 없다 |
| 거버넌스 4종 | 51 | **정상.** 멈춘 날짜가 앵커 지표의 다음 공시일 직전 거래일이다 |
| `006400` `DIVIDEND_PAYOUT` | 1 | **결함.** FY2025 배당총액이 2026-03-10 공시인데 값이 2026-08-20까지 나갔다 |
| `010060` `EPS_GROWTH_TTM_YOY` | 1 | **결함.** `priorTtm`이 −1,981억이라 계산기가 빈 목록을 내는데 79행이 남았다 |
| `DEBT_RATIO_SECTOR_*` | 8 | **결정 4가 앞으로를 막는다.** 4종목이 전부 2026-08-20에 멈췄다 — §결과 「어려워지는 것」의 경계 노출(`n=4`)과 이어진다 |
| `PER_SECTOR_*` | 12 | **결정 4가 원리적으로 못 닿는다.** 4종목이 전부 2025-12-30에 멈췄다 — **다른 기제다** |

**MUST NOT: 뒤의 20쌍을 「결정 4가 지울 것」으로 읽지 않는다.** 그 삭제는 `v.trade_date = ?`
**하루만** 보므로(§규칙), **이미 과거에 멈춘 행에는 원리적으로 안 닿는다.** 결정 4가 막는 것은
**앞으로 새로 생기는 것**이고, 이미 쌓인 것의 복구는 백필이다(§결과 「어려워지는 것」).
**전수를 다시 재면 이 20은 그대로 남는 것이 정상이다** — 실제로 2026-08-28 일일 갱신(#155) 뒤
전수는 **242쌍 / 186종목 / 10지표**로 20쌍이 남았고, 줄어든 4쌍은 결정 1~3이 걷은 006400·010060이다.
**이 오독은 실증됐다** — 이 문단이 없을 때 반증 라운드가 「20이 남았으니 결정 4가 안 돈다」로 읽었다.

## 버린 대안

- **ADR-0082의 판단을 유지하고 기제를 안 만든다.** 그 항목이 *"1종목이라 기계를 더 만들지 않는다"*로
  적혀 있어 처음에 이것을 권했는데, **그 문장은 450080을 다루는 다른 항목의 것**이었다. 006400이
  걸린 항목은 처음부터 *"늘어나면 **그 경로를 먼저 본다**"*였다. **권장 근거가 존재한 적이 없었다.**
- **`needsMarketIndex`만으로 지수 회귀를 막는다.** 축이 「통째로 없는가」만 가르므로 **부분 결손에
  안 걸린다.** 축은 축대로 두고(빈-목록 분기의 구멍을 닫는다) 꼬리 쪽은 날짜 단위로 거른다.
- **`PercentileCalculator`도 같은 여집합 삭제를 넣는다.** 임계가 `population >= 2`이고 취지가
  **모집단 1 방지**라 업종의 「대표성」과 다르며, 실측 대상이 **0건**이다. 근거가 0건인 변경이다.
- **손으로 쓴 마이그레이션을 한 장 더 쓴다**(V19·V25 선례) — ADR-0080이 이미 *"두 번 반복된 것을
  세 번째로 반복하는 안"*으로 기각했다.

## 결과

- **적용 실측 — 결정 1~3(2026-08-28, 단일 날짜 백필)**: 삭제 **191행**(`006400` 112 · `010060` 79),
  전체 7,139,487 → 7,139,296. **다른 124개 코드는 한 행도 안 줄었다.** 고아 파생 정리 0행.
  2회차 삭제 **0**(멱등). 다중 날짜(3일자) 실행에서 **삭제 왕복 0**.
  006400의 남은 64행은 `2025-12-01~2026-03-09`의 거래일 64개와 **정확히 일치**하고(구멍 없음),
  경계가 FY2025 공시일(2026-03-10) **직전 거래일**이다.
  - **MUST: 이 수치를 결정 4의 것으로 읽지 않는다.** 그 백필(`batch_execution` #151~153)은
    **10:56:50에 끝났고 결정 4의 커밋은 11:00:48이다** — 여집합 삭제는 안 실려 있었다.
    갈라 적는 이유는 **이 문단을 넷 전부의 실측으로 읽은 전례가 실제로 있어서다.**
- **적용 실측 — 결정 4(2026-08-28 15:21, 일일 갱신 `batch_execution` #155, `asOf=2026-08-27`)**:
  `업종 상대값을 계산했다. 지표=6쌍 **저장=0행 정리=0행**`. `indicator_value` 전체
  **7,138,994 그대로**, 08-27의 `*_SECTOR_*` 열 코드 2,624행도 **한 행도 안 변했다.**
  - **이 실행이 하필 가장 위험한 조건을 밟았다.** 같은 날 13:30이 이미 같은 값을 써 둔 뒤라
    `IS DISTINCT FROM`이 전부 걸러 **UPSERT가 0행을 반환했다.** 그 조건에서 삭제가 0인 것이
    §검증의 `keepsRowsOnDaysWhenTheUpsertWroteNothing`가 지키는 자리이고, **실 DB에서 확인된 것은
    이것이 처음이다.** 술어를 「UPSERT가 반환한 행 수」로 바꾸면 그날 2,624행이 통째로 지워진다.
  - **아직 안 밟은 것**: 표본 미달·중앙값 가드로 **실제로 지우는** 갈래. 08-27 모집단에 그 대상이
    0건이라(`*_SECTOR_N`에 `value < 5`가 없다) 발화 조건 자체가 없었다.
- **어려워지는 것 — 업종 상대값의 삭제·복구가 비대칭이다.** `calculate`가 **그 날짜 하나**만
  처리하므로 모집단이 5 아래로 내려가면 그날부터 매 실행이 그날 행을 지우지만, 회복돼도
  **그날 하루만** 다시 쓴다. 잠깐 내려갔다 돌아온 업종은 **보존 창이 영구히 빈다** — 복구는 백필이다.
  경계 노출(2026-08-26, `DEBT_RATIO` 실측): fine `n=5`가 3개·`n=4`가 9개 / broad `n=5`가 2개·`n=4`가 3개.
- **남는 것 — 이 결정이 안 닫는 부류 둘.** ① **값이 바뀌었는데 안 덮인 행**: `*_PCTL`·`*_SECTOR_*`는
  모집단이 바뀌면 과거 일자 값이 전부 틀려지는데 두 계산기 다 「돌린 날짜」 하나만 다시 쓴다.
  이 결정은 **행이 없어진 경우**만 걷는다. ② **유니버스에서 빠진 15종목의 동결**: 시세·지표·파생이
  함께 멈춰 파생만 **28,704행**이다. 위 측정 정의가 「그 종목의 마지막 지표 일자」 기준이라
  **이 부류를 원리적으로 못 본다.** 둘 다 `docs/progress.md`에 있다.
- **못 보는 것**: **내부에 남은 낡은 행.** 꼬리만 보므로 산출 구멍 안쪽은 대상이 아니다
  (실측으로 지금 그런 쌍은 없다).
- **되돌리려면**: `IndicatorRunner`의 `tailAfterLastProduced`와 게이트 한 줄,
  `DataRequirement.needsMarketIndex`, `SectorRelativeCalculator.DELETE_UNWRITTEN`.
  **되돌리는 순간 옛 행이 다시 쌓이고 아무 에러도 안 난다** — 위 단언 여덟과 `premise` 둘만이 잡는다.
