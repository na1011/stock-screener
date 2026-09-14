---
id: ADR-0143
status: 채택됨
date: 2026-09-14
applies_to:
  - "src/main/java/**/pipeline/datasource/kr/DartFullAccountMapper.java"
  - "src/main/java/**/pipeline/datasource/kr/DartFullStatementAdapter.java"
  - "src/main/java/**/pipeline/datasource/RawFinancialReport.java"
  - "src/main/java/**/pipeline/collector/FinancialWriter.java"
  - "src/main/java/**/indicator/IndicatorDataReader.java"
  - "src/main/java/**/indicator/FinancialFigures.java"
  - "src/main/resources/db/migration/V52__*.sql"
---

# ADR-0143. 법인세비용과 세전이익을 적재하고, 분기에는 담지 않는다

## 결정

`financial_statement`에 `income_tax_expense`·`profit_before_tax` 두 컬럼을 더하고 **두 시장의
이미 쓰고 있는 엔드포인트**에서 읽는다 — 국내는 `fnlttSinglAcntAll`, 미국은 `companyfacts`다.
**연간 행에만 담는다** — 국내는 어댑터의 기간 가드가, 미국은 `annualOnly`가 분기를 막는다.

세후 투하자본이익률 계열 넷이 여기에 걸린다(ADR-0144). 유효세율이
`법인세비용 ÷ 세전이익`이고, 저장소가 **분자만 8/8로 재 두고 분모는 한 번도 묻지 않았다.**

## 규칙

- **MUST: 기간 가드를 어댑터에 둔다.** `toReport`는 FY 전용이 아니라
  `for (StatementPeriod period : values())` 루프 안에서 **Q1~Q3에도 불리고**, 정기 재적재의
  표준 명령이 `JobFreshnessPolicy`의 `--all-quarters=true`다. 가드가 없으면 **Q1~Q3에만 값이
  들어가고 Q4가 NULL**이 되는데, 그것은 `cost_of_sales`가 지금 있는 상태이고 그 컬럼을 TTM으로
  합치는 쪽에서 **한 칸이 비어 값이 통째로 안 난다**(ADR-0098이 그 실패를 이미 겪었다).
- **MUST NOT: Q4를 유도하지 않는다 — 다만 「불가능해서」로 적지 않는다.**
  `deriveFourthQuarterControlling`이 `FY − (Q1+Q2+Q3)`를 이미 돌리고 그 결과가 1,249행 있다.
  안 만드는 이유는 **세후 지표가 직전 회계연도 세율을 쓰기 때문**이다.
- **MUST NOT: 분기를 피해 콜을 아낀다고 적지 않는다 — 아끼는 콜은 0이다.** 정기 재적재가
  이미 분기를 받고 있다. 절약되는 것은 **컬럼 두 개의 저장과 Q4 유도 코드**뿐이다.
- **MUST NOT: 부호 판정을 적재 계층에 두지 않는다.** `financial_statement`는 원자료만 담고
  판단은 지표 계층이 한다(ADR-0067 승계). 법인세비용의 음수가 두 뜻이지만 그것을 가르는 것은
  `세전이익 − 순이익`과의 대조이고, 미리 정규화하면 **소스의 표기가 실제로 바뀌었을 때 알아볼
  방법이 없다.**
- **MUST NOT: 계정명 폴백을 두지 않는다.** 세전이익의 이름이 넷으로 갈리고
  (`법인세비용차감전순이익`·`…(손실)`·`법인세비용차감전당기순이익`·`법인세차감전순이익`),
  포괄손익계산서에 **「법인세비용차감전기타포괄손익」**이 따로 있어 낱말을 공유한다. 간접법
  현금흐름표에는 **「법인세납부」**가 있는데 그것은 현금 유출이지 비용이 아니다. 감가상각비가
  정확히 그 형태로 3/8 → 2/8 오진을 냈다.
- **MUST: record 컴포넌트를 끝에 붙인다.** 중간에 끼우면 위치 인자가 한 칸씩 밀리는데
  **타입이 전부 `BigDecimal`이라 컴파일러가 안 잡는다.** 실측으로 깨지는 호출부가 **열하나**
  이고 그중 둘이 `SecEdgarFacts`다 — **미국을 안 켜도 컴파일이 깨진다.**
- **MUST: `FinancialWriter.Amount` enum에도 더한다.** `findLatest`의 SELECT와 `fillMissing`의
  SET이 둘 다 그 목록에서 만들어지므로, 빠뜨리면 **그 컬럼이 영영 안 채워진다.**
- **MUST: 재적재 창을 `IndicatorRegistry.maxFinancialYears()`(지금 10년)까지 준다**(ADR-0145).
  `--financial-years=6`으로 돌려 **FY2015~2019가 세 시장 모두 0건**으로 남았다. 세율 컬럼만의
  문제가 아니다 — 같은 재무 행을 `ProfitStreakYearsCalculator`가 10년 창으로 읽고,
  `net_income_controlling`·`capex`도 같은 자리에서 비어 있었다.
  **MUST NOT: 이 결손을 세후 지표 커버리지의 원인으로 적지 않는다 — 재보니 아니었다.**
  `RoicAfterTaxMin5yCalculator.YEARS`가 5라 **최근 5개 연간**만 쓰고, 보존 창 앞머리에서
  그것은 FY2020~2024다. FY2015~2019를 채운 뒤 창 앞머리 5년 세후는 KOSPI **88 → 92** ·
  US **358 → 359** · KOSDAQ **51 → 51**로 사실상 그대로였다. **창 앞머리가 최신 일자보다
  낮은 진짜 원인은 아직 안 쟀다.**
- **MUST: 미국 세율 컬럼 둘을 `SecEdgarColumn.reportEvidence()`에서 뺀다.**
  안 빼면 `hasAnyValue`가 참이 되는 앵커가 늘어 **`reporters` 집합이 커지고**, **세금만 든
  얕은 보고서가 `filed` 앞에 서면** baseline을 세운 뒤 진짜 10-K가 `conflicts` 거짓으로
  `continue`되어 **버려진다** — 그 기간 행에 **세금만 남고 매출·영업이익·자산이 전부 NULL**이
  된다. 더 나쁜 것은 `continue`가 `baseline = carried` **앞**이라 **그 상태가 영구히 고정**된다는
  것이다. **값은 안 잃는다** — `valuesOf`가 컬럼을 독립적으로 순회한다.
- **MUST: `empty` 판정도 같은 술어를 쓴다.** 갈리면 세금만 든 판이 reporter는 아닌데 `empty`도
  아니게 되어 **그 자리가 다시 열린다.**
- **MUST NOT: 플래그 이름을 `--years-back`으로 적지 않는다.** `BootstrapRunner.KNOWN_OPTIONS`에
  없어 `rejectUnknownOptions`가 **0콜에서 거절한다.** 실제 이름은 `--financial-years`다.

## 검증

```premise 세율 재료가 연간에만 있고 분기에는 없다
psql -d stock_screener -At -c "WITH f AS (SELECT fiscal_period, count(income_tax_expense) tax, count(profit_before_tax) pre, count(*) rows FROM financial_statement fs JOIN stock s ON s.id = fs.stock_id WHERE fs.is_latest AND s.market_code IN ('KOSPI','KOSDAQ') AND fs.fiscal_year >= 2021 GROUP BY 1)
SELECT CASE
  WHEN (SELECT sum(tax) FROM f) = 0
    THEN '법인세비용이 한 행도 없다 — 컬럼만 생기고 재적재가 돌지 않았다(ADR-0062의 함정)'
  WHEN (SELECT sum(tax) FROM f WHERE fiscal_period <> 'FY') > 0
    THEN '분기 행에 법인세비용이 ' || (SELECT sum(tax) FROM f WHERE fiscal_period <> 'FY') || '건 들어갔다 — 기간 가드가 풀렸다. Q4만 비어 TTM이 한 칸 빈다'
  WHEN (SELECT sum(pre) FROM f WHERE fiscal_period <> 'FY') > 0
    THEN '분기 행에 세전이익이 들어갔다 — 위와 같은 자리다'
  ELSE 'OK 연간 ' || (SELECT rows FROM f WHERE fiscal_period = 'FY') || '행 중 법인세 '
       || (SELECT tax FROM f WHERE fiscal_period = 'FY') || ' · 세전 '
       || (SELECT pre FROM f WHERE fiscal_period = 'FY') || ', 분기는 0'
  END"
```

## 배경

`dart-financial.md`의 「계정별 가용성」 표가 법인세비용을 **8/8**로 적어 두었고
`RoicPretaxCalculator`가 *"법인세비용을 적재하면 그때 세후로 승격한다"*고 재개 조건까지
남겼다. **그런데 그 문장이 유효세율의 분모를 빠뜨리고 있었다.**

탐침(`DartPretaxIncomeProbeLiveTest`, 8종목 × 5년 · 50콜)이 답한 것 넷.

| 물은 것 | 답 |
|---|---|
| 세전이익이 오는가 | **8/8**. `ifrs-full_ProfitLossBeforeTax` |
| 계속영업 세전이익 | **0/8 — 닫혔다** |
| `sj_div` | **`IS`·`CIS` 둘 다.** `sections(IS, CIS)`가 이미 모은다 |
| 분기에도 오는가 | **온다.** 그래서 가드가 필요하다 |

**분자와 분모의 기저가 어긋난다.** 법인세비용의 표준 ID가 `…ContinuingOperations`인데 분모로
쓸 수 있는 것이 **총액뿐**이라, 중단영업이 있는 해에 세율이 어긋난다. **그 빈도는 전수가
답한다** — 계속·중단영업손익이 같은 표에 **1/8**로 적혀 있어 소수라는 것까지만 안다.

## 버린 대안

- **항등식으로 세전이익을 대신하기** — `세전 = 순이익 + 법인세`가 표본에서 **34/38(89%)**
  맞는다. 그러나 **두 시장에서 서로 다르게 깨지고 미국은 체계적이다** — KR `net_income`은
  `ifrs-full_ProfitLoss`(연결 총액)인데 US는 `NetIncomeLoss`(**지배주주 귀속**)이고, 미국
  세전 태그는 NCI를 포함하므로 **항등식이 비지배지분만큼 항상 틀린다.** KR도 중단영업에서
  깨진다. **컬럼을 줄이면 그 회사들에서 유효세율이 조용히 틀린다.**
- **분기까지 적재하고 Q4를 유도하기** — 가능하다(같은 산술이 `netIncomeControlling`에 이미
  돈다). 세후 지표가 연간 세율을 쓰므로 **쓸 곳이 없고**, ADR-0098이 분기 전 종목 적재에서
  **하루 약 20,500콜로 일일 한도 경계에 닿았다**고 실측했다.
- **유효세율을 적재 계층에서 계산해 담기** — spec §7이 「지표는 원자료를 수집한 뒤 자체 계산」을
  걸고, 부호 판정을 미리 하면 소스의 표기 변화를 알아볼 수 없다(위 §규칙).
- **미국의 값 소실을 「발화 규모를 재서」 다루기** — 처음에는 국내만 열고 미국을 미루며
  ①`reporters`가 커지는 (종목, 기간) 수 ②그중 새 reporter가 맨 앞에 서는 수를 재기로 했다.
  **그 측정을 버리고 원인을 없앴다** — `reportEvidence()`가 세율 컬럼 둘을 reporter 판정에서
  빼면 **`reporters` 집합이 원리적으로 안 커진다.** 측정은 「지금 몇 건인가」를 답할 뿐
  **다음 공시에 또 생기는 것을 막지 못한다.**

## 결과

**재적재 실측(2026-09-14 · `--financial-years=6`).**

| | 수 |
|---|---|
| 요청 / **실제 콜** | 9,450 / **3,154** (`dart.daily-quota: 18000`의 17.5%) |
| 쓴 보고서 | 1,885 |
| 공시 없음(`status 013`) | 565 |
| **정정보류 · 교정** | **0 · 0** |

**`교정=0`이 이 적재의 관문이었다.** `fillMissing`은 NULL 채우기와 **덮어쓰기** 두 갈래인데
뒤쪽은 `invalidate`를 불러 그 종목 지표를 보존 창 전체에서 지운다. 매퍼에 **새 ID만 더하고
기존 후보를 안 건드렸으므로** 추출이 바뀔 이유가 없었고, 그것이 기대가 아니라 사실이었음을
이 수가 답한다.

**기간 가드가 동작했다** — 연간에만 값이 있고 **분기 넷이 전부 0**이다.

| 시장 | FY 행 | 법인세비용 | 세전이익 | Q1~Q4 |
|---|---|---|---|---|
| KOSPI | 1,183 | **1,067**(90.2%) | **1,067** | **0** |
| KOSDAQ | 1,116 | **785**(70.3%) | **812**(72.8%) | **0** |

**KOSDAQ에서 분자와 분모의 수가 다르다**(785 대 812) — 세전이익은 오는데 법인세비용이 없는
행이 **27개**다. 그 행에서는 세율이 안 나오므로 세후 지표도 없다. **원인은 재보지 않았다.**

**분자와 분모의 기저는 어긋나지 않는다 — 처음에 「101종목이 어긋난다」고 적었던 것은 오판이다.**

분자가 `…IncomeTaxExpense`**`ContinuingOperations`**(계속영업)인데 분모 `ProfitLossBeforeTax`에는
그 낱말이 없어 **총액이라고 단정했던 것**이 그 오판의 출발이다. **IFRS 손익계산서 구조상 그
줄도 계속영업 기준이다** — 중단영업은 그 아래에 **세후로** 별도 표시된다.

```
계속영업
  법인세비용차감전순이익   ← ProfitLossBeforeTax                    (분모)
  법인세비용              ← IncomeTaxExpenseContinuingOperations    (분자)
  계속영업이익
중단영업이익(세후)         ← 여기부터 별도
당기순이익               ← ProfitLoss = 우리 net_income
```

**데이터가 그것을 확정한다.** NAVER FY2021은 분모가 **2.13조**인데 순이익이 **16.48조**다 —
라인 처분이익 약 15조가 **분모에 안 섞여 있다.** 섞였다면 분모가 17조여야 한다.

**그래서 항등식 `세전 = 순이익 + 법인세`가 깨지는 160행(1,831 중)은 기저 어긋남이 아니다** —
우리 `net_income`이 **총액**이라 중단영업이 있는 해에 **당연히** 깨진다. 그 차이는
**−(중단영업 세후이익)**이고, 실측에서 어긋난 112행 중 **64가 음수(중단영업 이익) · 48이
양수(중단영업 손실)**로 양쪽이 다 나온다.

**MUST NOT: 항등식 일치율을 「기저가 맞다」의 증거로 읽지 않는다.** 그것이 말하는 것은
**중단영업이 없는 회사의 비율**(91.3%)이지 세율의 정확도가 아니다.

**정정본의 세금 결손도 스스로 닫힌다.** 목요일 `financial-refill`이 정정을 판정하면
`insert(…, revisionNo + 1)`로 새 최신본이 생기고 그 행은 주요계정에서 와 **세금이 NULL**이다.
**그런데 그 뒤 `full-statement-refill`이 채운다** — 정정본이 최신본이 된 뒤에는 **저장 공시일 =
소스 공시일**이라 정정 갈래가 아니라 `fillMissing` 경로로 간다.

| `revision_no` | 행 | 세금 있음 | 세금 없음 |
|---|---|---|---|
| 0 | 2,284 | 1,837 | 447 |
| **&gt; 0** | **15** | **15** | **0** |

남는 것은 **정정일부터 다음 수요일 사슬까지 최대 6일의 공백**이고(ADR-0116), **값이 틀리는
것이 아니라 없는 것**이라 사유 규칙이 그것을 말한다.

**미국 재적재(2026-09-14 · `financial-refill --universe=US_TOP1000 --financial-years=6`).**

| | 수 |
|---|---|
| 대상 | 1,000종목 |
| 채움 | 5,796 |
| 정정 | 54 |
| **교정** | **1,359**(130종목) |

**세율 재료가 국내보다 잘 온다** — FY 5,763행 중 **법인세 5,675(98.5%) · 세전 5,449(94.6%)**
이고 **분기는 전부 0**이다(`annualOnly`가 동작한다).

**교정 1,359 중 1,332가 `revenue` 하나다 — 이 작업이 만든 것이 아니라 밀려 있던 수정이다.**
`SecEdgarColumn.REVENUE`에 `Selection.LARGEST`를 도입한 커밋(`e127bd1`) 이후 **미국 재무 전량
재적재가 없었고**, 그 javadoc이 *"970종목 전수에서 92종목이 어긋났고 전부 과소계상"*이라 적어
두고도 DB에 반영되지 않은 채였다. 실례: `revenue 1,570M → 2,643M`(+68% · ASC 606 계약매출이
총매출 자리에 앉아 있었다). **내가 더한 컬럼은 기존 값을 안 건드린다** — 교정 대상이 `revenue`
1,332 · `net_income` 3뿐인 것이 그 증거다.

**정정 54는 소스의 새 보고서다.** `reportEvidence()`가 reporter를 **줄이지 늘리지 않으므로**
이 변경이 정정을 만들 수 없다.

**대상이 350이 아니라 1,350이었다.** 잡이 시장을 안 가리고 활성 유니버스 전체를 돌아 로그가
**「요청 9,450건 · 예상 호출 9,450~18,900회」**를 찍는다. 어댑터가 `supportedMarketCodes`로
`KOSPI`·`KOSDAQ`만 지원하고 레지스트리가 시장별 인덱스라 **미국은 소스를 못 찾아 콜 없이
실패**한다 — 실제 콜 3,154가 그 증거다. **MUST: 다음에는 `--universe`로 좁힌다** — 그 예상치를
콜 예산으로 읽으면 일일 한도에 육박하는 것처럼 보인다.

되돌리려면 컬럼 둘을 드롭하고 위 여덟 자리의 인자를 걷는다. **재적재는 되돌릴 수 없다** —
다만 `full-statement-refill`은 `FILL_WITHOUT_REVISION`이라 정정본을 쌓지 않고, `fillMissing`의
NULL 채우기 갈래는 무효화를 타지 않는다.
