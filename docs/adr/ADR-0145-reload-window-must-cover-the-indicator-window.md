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

재무의 **매핑·후보 목록·선택 규칙을 고치거나 컬럼을 더하면**, 그 뒤의 재적재를
`IndicatorRegistry.maxFinancialYears()`가 내는 연수까지 넓혀 **전 종목**으로 돌린다.
그것을 잊었을 때 드러나게 하는 자리를 둘 둔다 — 실행 시점의 `FinancialLoadWindow` 경고와,
세션 시작의 premise 펜스다.

## 규칙

- MUST: 매핑·후보 목록·선택 규칙을 바꾼 커밋 뒤의 첫 재적재는 `--financial-years`를
  `maxFinancialYears()` 이상으로 준다. **기본값은 그보다 좁다** — `financial-refill` 3년 ·
  `full-statement-refill` 5년 · 지표 10년.
- MUST NOT: 고칠 범위를 **결함을 센 단면**으로 잡지 않는다. 세는 자리는 대개 최신 FY 단면이고
  오염은 창 전체에 있다.
- MUST NOT: 정기 스케줄이 그것을 대신한다고 읽지 않는다. `PipelineScheduler.runHeavy`는
  `financialRefillJob.run(asOf, by, null, List.of(), null)`로 **연수를 `null`로 넘겨 기본값**을
  쓴다. 매주 돌아도 그 창 밖은 영원히 안 바뀐다.
- MUST: 창을 넓혀 돌린 뒤 **지표 백필을 잇는다.** 교정·정정은 그 종목의 지표를 보존 창에서
  지우고, 재적재 잡은 `indicator_value`를 다시 만들지 않는다.

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
  -- IndicatorRegistry.maxFinancialYears()(지금 10, ProfitStreakYearsCalculator가 정한다)만큼
  -- 거슬러 간다. 그 수가 커지면 여기도 함께 커져야 한다.
  SELECT EXTRACT(year FROM min(trade_date))::int - 10 AS y FROM indicator_trade_date),
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
    -- FY2018 이하의 이 셋은 창이 아니라 네임스페이스다 — DART가 그 구간에서 'ifrs_'를 쓰는데
    -- DartFullAccountMapper의 후보는 'ifrs-full_'과 'dart_'뿐이다(§결과의 탐침).
    -- 그 연도를 읽는 지표가 없어 열지 않기로 했으므로, 세면 매 세션 조치 없는 발견이 난다.
    -- MUST: 이 목록이 자라는 자리는 매핑에 'ifrs_' 후보를 더하는 커밋이다 — 더하고 전 창을
    -- 재적재하면 이 예외를 함께 지운다. 다른 컬럼이나 FY2019 이상은 그대로 걸린다.
    AND NOT (c.y <= 2018 AND c.col IN ('income_tax_expense','profit_before_tax','capex'))
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

**정기 잡의 창을 `maxFinancialYears()`로 올린다.** 가장 근본적이고 실제로 이 결함을 영구히
닫는다. 기각 근거는 쿼터다 — `financial-refill`을 10년으로 돌리면 국내가
`350 × 11 × 4 = 15,400`콜이고 `dart.daily-quota`가 18,000이라 **한 번에 86%를 태운다.**
그날 다른 DART 잡이 조금이라도 끼면 넘는다. **MUST NOT: 이 기각을 「불가능」으로 읽지 않는다** —
막는 것은 쿼터 하나이고, 소스가 창을 한 응답에 주는 미국은 **창을 넓혀도 콜이 안 는다**(SEC
`companyfacts`는 종목당 1콜이다).

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

**재적재 전 지표 기준선을 안 찍었다.** 교정 864건이 지표 값을 얼마나 바꿨는지는 원리적으로
못 잰다 — 바뀐 것을 세려면 **재적재 전에** 찍어야 했다. 같은 실패가 P27 축⑤①에도 있다.

**같은 형태가 거버넌스 축에도 하나 있다(미조치).** `DIVIDEND` 표는 `bsns_year` 2024~2025
**2년**만 적재돼 있는데 `DividendPayoutCalculator`·`DividendYieldCalculator`가
`governanceYearsNeeded` **3**을 선언한다. 이 ADR의 펜스는 재무만 보므로 그것을 신고하지 않는다.
