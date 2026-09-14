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

`financial_statement`에 `income_tax_expense`·`profit_before_tax` 두 컬럼을 더하고 **이미 쓰고
있는** `fnlttSinglAcntAll`(전체 재무제표)에서 읽는다. **연간 행에만 담는다** — 어댑터가 기간
가드로 분기를 막는다.

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
- **MUST: `--financial-years=6`으로 재적재한다.** 기본값 5면 `asOf.year − 5`부터 올해까지라
  FY2021~2026인데, **보존 창이 255거래일**이라 창 앞머리 as-of에서는 5년 창이 **FY2020**을
  요구한다. 안 채우면 그 구간이 비는데 **최신 일자 화면은 멀쩡하다.**
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
- **미국까지 한 번에 열기** — 태그는 **10/10**으로 실측했다(`sec-edgar-financial.md`). 미룬
  이유는 **`SecEdgarColumn`에 컬럼을 더하면 `hasAnyValue`가 참이 되는 앵커가 늘어 `reporters`
  집합이 커지고**, 세금만 든 얕은 보고서가 `filed` 앞에 서면 뒤의 진짜 10-K가 `conflicts`
  거짓으로 `continue`되어 **버려지기** 때문이다(그 기간 행에 세금만 남는다). 사용자가
  2026-09-14에 「국내 먼저」를 골랐다. **재개 조건은 그 발화 규모를 먼저 재는 것**이다 —
  ①`reporters`가 커지는 (종목, 기간) 수 ②그중 새 reporter가 맨 앞에 서는 수.

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

**분자와 분모의 기저가 어긋나는 종목을 전수로 셌다 — 101이다.**
계속영업 세전이익이 안 오므로 총액을 분모로 쓰는데, 그 어긋남은 `세전 = 순이익 + 법인세`가
깨지는 것으로 드러난다(우리 `net_income`은 **연결 총액**이라 비지배지분은 이 항등식에 영향이
없고, 남는 원인이 **중단영업**이다).

| | 수 |
|---|---|
| 대조 가능한 행 | 1,831 |
| 항등식 1% 이내 일치 | **1,671**(91.3%) |
| **어긋난 종목** | **101** |

**알려진 결손으로 남는다.** 그 101종목에서 유효세율이 계속영업/총액 차이만큼 어긋나고,
**그 크기는 재지 않았다** — 중단영업이 작은 회사에서는 오차도 작지만 그 분포는 모른다.
닫으려면 계속영업 세전이익이 오는 다른 경로가 필요하고 **그것은 별도 결정이다.**

**대상이 350이 아니라 1,350이었다.** 잡이 시장을 안 가리고 활성 유니버스 전체를 돌아 로그가
**「요청 9,450건 · 예상 호출 9,450~18,900회」**를 찍는다. 어댑터가 `supportedMarketCodes`로
`KOSPI`·`KOSDAQ`만 지원하고 레지스트리가 시장별 인덱스라 **미국은 소스를 못 찾아 콜 없이
실패**한다 — 실제 콜 3,154가 그 증거다. **MUST: 다음에는 `--universe`로 좁힌다** — 그 예상치를
콜 예산으로 읽으면 일일 한도에 육박하는 것처럼 보인다.

되돌리려면 컬럼 둘을 드롭하고 위 여덟 자리의 인자를 걷는다. **재적재는 되돌릴 수 없다** —
다만 `full-statement-refill`은 `FILL_WITHOUT_REVISION`이라 정정본을 쌓지 않고, `fillMissing`의
NULL 채우기 갈래는 무효화를 타지 않는다.
