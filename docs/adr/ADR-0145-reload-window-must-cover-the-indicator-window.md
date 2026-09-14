---
id: ADR-0145
status: 채택됨
date: 2026-09-14
applies_to:
  - "src/main/java/**/pipeline/batch/**"
  - "src/main/java/**/pipeline/datasource/**"
  - "src/main/java/**/pipeline/collector/**"
---

# ADR-0145. 재적재 창은 지표가 읽는 창을 덮는다

## 결정

**적재 기본 창을 지표가 선언한 요구보다 크게 두고, 그 부등식을 시험으로 묶는다.**
축이 둘이고 둘 다 묶었다.

| 축 | 지표 요구 | 정하는 자리 | 적재 기본값 |
|---|---|---|---|
| 재무 연수 | **7** (`maxFinancialYears()`) | `ProfitStreakYearsCalculator.YEARS` | 3·5·3 → **8** 셋 다 |
| 시세 봉 | **1,261** (`maxEffectiveLookback()`) | `RetainedToMarket4yCalculator.LOOKBACK` | `initial-load` 2 → **7년** |

지표가 더 깊은 재무·시세를 요구하게 되면 `FinancialLoadWindowTest`가 **빌드에서 붉는다.**
(`MembershipGapFiller.PRICE_YEARS`는 이미 7이었지만 같은 시험에 함께 묶었다 — 그 값이 줄면
**신규 편입 종목만 조용히 짧아진다.**)

그 위에 사람이 읽는 자리를 둘 더 둔다 — 손으로 좁게 준 실행을 알리는 `FinancialLoadWindow`
경고와, 이미 생긴 결손을 세션 시작에 신고하는 premise 펜스다. **MUST NOT: 그 둘을 방어선으로
읽지 않는다** — 로그도 펜스도 사람이 읽어야 발화하고, 실측된 누수는 로그가 *"2020년 이후"*를
정확히 찍었는데도 났다.

## 규칙

- **MUST: 적재 기본 창을 지표 요구보다 크게 유지한다 — 재무 연수도 시세 봉도.**
  같으면 안 된다 — 적재는 `asOf.year − N`이라 **역년**이고 지표는 **회계연도 행 수**라
  단위가 다르며, 비12월 결산(약 24%)은 같은 N이 한 해 적게 걸린다.
  `FinancialLoadWindowTest`가 이 부등식을 단언한다.
  **MUST NOT: 그 시험의 기대값을 낮춰 통과시키지 않는다** — 고칠 것은 적재 기본값이다.
- MUST NOT: 고칠 범위를 **결함을 센 단면**으로 잡지 않는다. 세는 자리는 대개 최신 FY 단면이고
  오염은 창 전체에 있다. **종목 축과 연도 축이 서로 독립이라** 한쪽만 채우면 절반만 고쳐진다.
- MUST: 매핑·후보 목록·선택 규칙을 바꾼 커밋 뒤에는 **전 종목·전 창으로 한 번 더 돌린다.**
  기본값이 창을 덮어도 그 실행이 없으면 옛 값은 그대로다 — 정기 잡은 다음 주에야 돈다.
- MUST: 창을 넓혀 돌린 뒤 **지표 백필을 잇는다.** 교정·정정은 그 종목의 지표를 보존 창에서
  지우고, 재적재 잡은 `indicator_value`를 다시 만들지 않는다.
- **MUST: 재적재 전에 지표 기준선을 찍는다.** 교정이 값을 얼마나 바꿨는지는 **뒤에는 못 잰다.**

## 검증

```premise 지표가 읽는 창 안에 통째로 빈 회계연도가 없다
psql -d stock_screener -At -c "
WITH cur AS (
  -- 모집단은 ADR-0069와 같은 형태다 — 활성 유니버스의 열린 편입 행.
  -- US_LISTED는 is_active=false라 자동으로 빠진다.
  SELECT DISTINCT m.stock_id, m.universe_code
  FROM universe_membership m JOIN universe u ON u.code = m.universe_code
  WHERE m.effective_to IS NULL AND u.is_active),
lo AS (
  -- 상한이 아니라 하한을 정한다. 지표가 읽는 창은 보존 창 앞머리에서 시작해
  -- IndicatorRegistry.maxFinancialYears()(지금 7, ProfitStreakYearsCalculator.YEARS가 정한다)
  -- 만큼 거슬러 간다. SQL이 그 상수를 못 읽으므로 FinancialLoadWindowTest가 적재 기본값과
  -- 그 수의 부등식을 단언한다 — 지표가 창을 넓히면 거기서 먼저 붉는다.
  SELECT EXTRACT(year FROM min(trade_date))::int - 7 AS y FROM indicator_trade_date),
fy AS (
  SELECT c.universe_code AS uc, f.fiscal_year AS y, to_jsonb(f) AS j
  FROM financial_statement f JOIN cur c ON c.stock_id = f.stock_id, lo
  WHERE f.is_latest AND f.fiscal_period = 'FY'
    AND f.fiscal_year >= lo.y
    AND f.fiscal_year <  EXTRACT(year FROM current_date)::int),
cell AS (
  -- 행 전체를 jsonb로 돌려 컬럼마다 센다. UNION ALL로 나열하면 컬럼을 더할 때
  -- 여기에도 더해야 하고, 빠뜨린 컬럼은 영원히 안 세어진다.
  SELECT fy.uc, fy.y, t.key AS col, count(*) AS rows,
         count(*) FILTER (WHERE t.value <> 'null'::jsonb) AS filled
  FROM fy, LATERAL jsonb_each(fy.j) t
  WHERE t.key NOT IN ('id','stock_id','fiscal_year','fiscal_period','period_start','period_end',
                      'published_at','revision_no','is_latest','report_code','currency_code','created_at')
  GROUP BY 1,2,3),
peak AS (
  -- 그 시장에서 한 번도 30%를 못 넘긴 컬럼은 모집단에서 뺀다. 금융업 전용 여덟은 국내에서
  -- 원래 0이고, 그것까지 세면 매 세션 거짓 발견이 난다.
  SELECT uc, col, max(filled::numeric / NULLIF(rows,0)) AS best FROM cell GROUP BY 1,2),
gap AS (
  SELECT c.uc, c.col, string_agg(c.y::text, ',' ORDER BY c.y) AS years
  FROM cell c JOIN peak p ON p.uc = c.uc AND p.col = c.col
  WHERE c.filled = 0 AND p.best >= 0.3
  GROUP BY 1,2)
SELECT CASE
  WHEN (SELECT count(*) FROM cell) = 0 THEN '잴 것이 없었다 — 창 안에 연간 재무가 한 행도 없다'
  WHEN (SELECT count(*) FROM gap) = 0 THEN 'OK 창 안 ' || (SELECT count(DISTINCT y) FROM cell)
       || '개 회계연도에 통째로 빈 컬럼이 없다'
  ELSE (SELECT string_agg(uc || ' ' || col || ' FY' || years, ' / ' ORDER BY uc, col) FROM gap)
       || ' — 그 연도는 재적재 창 밖이라 옛 규칙의 값이 남거나 컬럼이 NULL이다(ADR-0145)'
  END"
```

**이 펜스가 답하지 않는 것 하나** — **행이 아예 없는 회계연도**는 `cell`에 잡히지 않아 못 본다.
「컬럼이 비었다」와 「그 해 재무를 한 종목도 안 받았다」는 다른 축이고, 뒤의 것은 각 시장의
커버리지 펜스가 답한다.

**시험이 실제로 붉는지 대조군을 둘 태웠다.** `FinancialRefillJob.DEFAULT_YEARS_BACK`을
`maxFinancialYears()`와 같은 **7**로 낮추면 `적재_기본_창이_지표가_읽는_창보다_넓다()`가
FAILED이고 8로 되돌리면 통과한다. `PRICE_HISTORY_YEARS`를 **5**(=1,200봉)로 낮추면
`시세_적재_기본_창이_지표가_읽는_봉수보다_길다()`가 FAILED이고 7로 되돌리면 통과한다 —
요구가 1,261봉이라 **경계에서 갈린다.**

```bash
./gradlew test --tests "*FinancialLoadWindowTest*"
```

`FinancialLoadWindow`가 세 잡 전부에서 불리는지는 정적으로 본다 — 아래가 **3건**이어야 한다.

```bash
rg -n "loadWindow\.warnIfShorterThanIndicators" src/main/java | wc -l
```

## 배경

세율 두 컬럼을 적재하며 `--financial-years=6`을 줬다. 로그는 *"2020년 이후"*를 정확히 찍었고
잡은 성공했으며 최신 일자 커버리지도 예측과 맞았다. **그런데 FY2015~2019가 세 시장 모두
0건으로 남았다.**

**MUST NOT: 그 결손을 커버리지 손실과 인과로 잇지 않는다 — 재보니 아니었다.** 창을 11년으로
넓혀 두 시장을 다시 받고 백필까지 돌린 뒤, 보존 창 앞머리의 5년 세후는 KOSPI 88 → **92** ·
US 358 → **359** · KOSDAQ 51 → **51**이었다. 그 지표가 쓰는 것은 **최근 5개 연간**(창
앞머리에서 FY2020~2024)이라 FY2019 이전이 안 들어간다. **창 앞머리가 최신보다 낮은 원인은
따로 있고 아직 안 쟀다.**

**그럼에도 결손 자체는 결함이다.** 같은 자리에 `net_income_controlling`·`capex`가 함께 비어
있었고, 창 앞머리에서 `PROFIT_STREAK_Y`가 실측 최댓값 **7**을 내므로 **FY2018·2019는 실제로
읽힌다.** 다만 **재적재 전 지표 기준선을 안 찍어 그 교정이 값을 얼마나 바꿨는지는 못 쟀다** —
바뀐 것을 세려면 재적재 **전에** 찍어야 했다.

같은 자리를 되짚자 형태가 같은 것이 셋 더 나왔다. **`Selection.LARGEST`**(e127bd1)는 최신 FY
단면에서 센 **92종목만** 다시 받았는데 창을 넓혀 전수로 돌리자 교정이 **130종목 1,332행**이었고
그 연도가 전부 2020~2022였다. **`net_income_controlling`**(dca7b22)은 **당일 전 종목 350을 두 번**
돌렸는데도 창이 기본 5년이라 FY2016~2019가 비었다. **`capex`**(79dd078)도 KOSDAQ FY2016~2017이
비었다.

**두 축이 서로 독립이다** — 종목을 다 돌려도 연도가 모자라고, 연도를 다 돌려도 종목이 모자란다.
공통점은 **고친 범위를 관찰한 범위로 잡은 것**이고, 그 관찰은 언제나 최신 단면이다.

무증상인 이유가 구조에 있다. **쓰기 창과 읽기 창이 서로를 모른다** —
`InitialLoadJob.FINANCIAL_HISTORY_YEARS`는 3이고 `IndicatorRunner:449`는
`registry.maxFinancialYears()`로 10을 읽는데, 이 변경 전까지 `maxFinancialYears()`를 부르는
배치가 **하나도 없었다.**

## 버린 대안

**경고만 두고 기본값은 그대로 둔다(처음에 그렇게 적었다가 철회했다).** 기각 근거였던
*"`financial-refill`을 10년으로 돌리면 국내가 15,400콜이라 쿼터의 86%"*가 **잘못된 수 위에
있었다.** `maxFinancialYears()`를 10으로 읽었는데 실제로는 **7**이고
(`ProfitStreakYearsCalculator.YEARS`), 8년이면 `350 × 9 × 4 = 12,600`콜로 **70%**다.
전체본은 `350 × 9 = 3,150` 요청이고 실측 배수 1.32를 곱하면 **약 4,160콜(23%)**이다.
**쿼터가 기본값 상향을 막지 않는다.**

**MUST: 국내 `financial-refill` 12,600콜은 아직 실행으로 재지 않았다.** 리미터
`dart.daily-quota: 18000` 안이고 목요일에 그 잡만 돌지만(ADR-0116), 같은 잡이 미국 1,000종목도
돌아 **소요가 약 21분 + 14분**이 된다 — 14:30 시작이면 15:05쯤이고 15:10 신선도 점검과
가깝다. 첫 실행의 실제 콜과 소요를 여기 적는다.

**결손 연도를 보고 창을 스스로 넓히는 잡을 만든다.** 정합적이지만 이 결함을 잡는 데 필요한
것보다 크다 — 무엇이 결손인지는 위 펜스가 이미 답하고, 답을 받은 뒤 창을 주는 것은 사람의
한 줄이다.

**경고를 `AlertSeverity`로 올려 알림으로 내보낸다.** 정기 잡이 기본 창으로 도는 것이 정상이라
**매주 목요일마다 알림이 뜬다.** 거짓 발견은 침묵보다 나쁘다.

## 결과

**복구 실측(2026-09-14 · 창 11년 = FY2015~2026).**

| 단계 | 명령 | 소요 | 결과 |
|---|---|---|---|
| 미국 재무 | `financial-refill --universe=US_TOP1000 --financial-years=11` | **13분 54초** | 정정 7,741 · 채움 1,623 · **교정 864** · `MALFORMED` 0 |
| 국내 재무 | `full-statement-refill --financial-years=11` | **9분 56초** | 실제콜 5,552 · 쓴보고서 3,105 · 공시없음 1,095 · 교정 0 |
| 지표 base | `backfill --as-of=2026-09-10 --from=2026-09-10` | **3분 45초** | 1,350종목 · 창 전체가 한 판에 채워진다 |
| 지표 파생층 | `backfill --from=2025-08-25 --tickers=005930` | **4분 23초** | 255일자 |

**콜 예상치를 예산으로 읽으면 안 된다** — 국내 로그가 *"요청 16,200건"*을 찍는데 그 대상은
활성 유니버스 전량(1,350종목)이고 **미국 1,000은 DART 소스가 없어 콜 없이 실패한다.**
실제 DART 콜은 **5,552**다.

**펜스가 재적재 전 8건 → 후 4건이다.** `net_income_controlling`이 두 시장에서 완전히 닫혔고
(FY2015~2019), 세율 둘은 KOSDAQ FY2016·2017만 남았다. 남은 넷은 전부 **FY2017 이하**이고
`capex`가 셋이다.

**남은 넷의 원인은 창이 아니다 — 탐침이 확정했다(2026-09-14 · KOSDAQ 4종목 · 21콜).**
국내 세율은 **FY2019에서 계단이 진다**(KOSPI FY2018 5/145 → FY2019 148/150 · KOSDAQ
FY2018 3/112 → FY2019 113/117). 창 11년으로 그 해를 실제로 요청했는데도 안 왔다.
**소스는 준다 — 우리 매핑이 옛 네임스페이스를 안 본다.** FY2017 응답의 태그가
`ifrs_ProfitLossBeforeTax` · `ifrs_IncomeTaxExpenseContinuingOperations`로 **하이픈 없는
`ifrs_`**인데, `DartFullAccountMapper`의 후보는 `ifrs-full_`과 `dart_`뿐이다.
(036930 FY2017에서 세전 44,006,700,466 = 순이익 42,019,907,763 + 법인세 1,986,792,703으로
항등식이 맞아 그 나열이 2017년 것임이 확정된다.)

**걸리는 컬럼은 셋뿐이다.** FY2017과 FY2019의 충전률 격차가 15%p를 넘는 컬럼을 전수로 세면
`income_tax_expense`(1% → 98%) · `profit_before_tax`(1% → 100%) · `capex`(0% → 84%)이고,
매출·영업이익·순이익·자산·부채는 FY2017에도 정상이다.

**지금 열지 않는다.** 그 연도를 읽는 지표가 없다 — 보존 창 앞머리에서 `PROFIT_STREAK_Y`의
실측 최댓값이 **7**이라 FY2018부터이고, 세율 지표는 최근 5개 연간(FY2020~2024)만 쓴다.
매핑만 고치고 재적재를 안 하면 **이 ADR이 금지한 상태를 내가 다시 만든다.**
**재개 조건**: 백테스트 창을 2022-04보다 앞으로 늘릴 때(ADR-0071 §규칙 — 재무 바닥이
`bsns_year` 2015다), 또는 `maxFinancialYears()`가 커져 FY2017 이하가 읽히게 될 때.
그때는 **매핑에 `ifrs_` 후보를 더하고 전 창을 함께 재적재한다.**

### 재적재가 지표를 얼마나 바꿨나 — 덤프에서 실측했다

**「기준선을 안 찍어 못 잰다」고 적었는데 거짓이었다.** `ops/dump-db.sh`가 재적재 직전에
돌았으므로 그 덤프에 그 시점의 `indicator_value` 4,941만 행이 통째로 들어 있다. 별도 DB에
`--data-only -t indicator_value`로 복원해 대조했다.

| 기준일 | 지표 | 값 바뀜 | 새로 생김 |
|---|---|---|---|
| **최신(2026-09-10)** | `ROIC_PRETAX`·`ROIC_MIN_3Y`·`OPM_MIN_5Y` | **0** | 0 |
| 〃 | `PSR`·`PER`·`EPS_TTM`·`REVENUE_GROWTH`·마진 넷 | **0** | 0 |
| 〃 | `PSR_MEDIAN_5Y` | 1 | 0 |
| **창 앞머리(2025-09-08)** | `PROFIT_STREAK_Y` | 5 | **46** |
| 〃 | `PSR_MEDIAN_5Y` | **24** | 0 |
| 〃 | `ROIC_MIN_3Y`·`OPM_MIN_5Y` | 0 | 0 |

**재적재의 효과는 과거 날짜에만 있다.** 최신 일자만 보면 **전부 0**이라 「아무것도 안 바뀌었다」로
읽히는데, 창 앞머리에서는 7년 창 지표가 FY2018~2019 충전을 받아 **46종목이 새로 값을 얻었다.**
**MUST: 재적재의 효과는 최신 일자가 아니라 창 앞머리에서 잰다** — 고친 것이 과거 회계연도이기
때문이다.

**MUST NOT: 「기준선을 안 찍었다」를 「못 잰다」로 적지 않는다** — 그 둘은 다르고, 이 저장소에는
파괴적 작업 앞에 덤프를 요구하는 가드가 이미 있다(`guard-rebuild-without-dump.sh`).
같은 방법으로 P27 축⑤①도 다시 볼 수 있다.

**같은 형태를 세 축 전부에서 찾았다.** 지표가 선언하는 요구는 셋이다 —
`financialYearsNeeded` · `priceBarsNeeded` · `governanceYearsNeeded`.
재무는 넷이 어긋나 있었고(고쳤다), 시세는 `MembershipGapFiller`가 이미 7년이라 실재 결손이
없었으나 `initial-load` 기본값 2년이 같은 형태로 남아 있어 함께 묶었다.
**1,261봉 미만인 종목을 전수로 세면 KOSPI 22 · KOSDAQ 27 · US 65인데, 그중 첫 봉이 5년 안인
것(=최근 상장)을 빼면 KOSPI 3 · KOSDAQ 1 · US 2다.** 국내 넷은 2021년 8월 상장이고
(크래프톤·카카오뱅크·HK이노엔·롯데렌탈) 미국 둘은 봉 자체에 구멍이 있어 이 축이 아니다.

**거버넌스 축에는 같은 형태가 없다 — 재보니 아니었다.** 처음에 `DIVIDEND` 표가 `bsns_year`
2024~2025 두 해뿐인데 계산기가 `governanceYearsNeeded` 3을 선언한다고 적었는데, **`bsns_year`는
요청 연도이지 데이터 연도가 아니다.** 그 응답은 `thstrm`(당기)·`frmtrm`(전기)·`lwfr`(전전기)로
**한 행에 3개년**을 담고, 그것을 두 해 부르므로 실제 범위가 **2022~2025 네 해**다.
배당 지표 커버리지가 그것을 확인한다 — `DIVIDEND_YIELD`가 KOSPI 199/200 · KOSDAQ 147/150 ·
US 974/1000이다. 다른 일곱 엔드포인트도 전부 요구를 덮는다.

**MUST NOT: 저장 컬럼 이름을 데이터 범위로 읽지 않는다** — `bsns_year`로 세면 세 해를 담은
행이 한 해로 세어진다.
