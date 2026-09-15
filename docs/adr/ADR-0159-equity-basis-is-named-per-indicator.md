---
id: ADR-0159
status: 채택됨
date: 2026-09-15
applies_to:
  - "src/main/java/com/stockscreener/indicator/calculator/**"
  - "src/main/java/com/stockscreener/pipeline/datasource/us/SecEdgarFacts.java"
  - "src/main/java/com/stockscreener/pipeline/datasource/kr/DartFullAccountMapper.java"
  - "src/main/java/com/stockscreener/pipeline/datasource/RawFinancialReport.java"
---

# ADR-0159. 지분 기저를 컬럼에 박고 지표마다 이름으로 고른다 — 시장이 아니라 짝으로 가른다

## 결정

**원장이 확인해 주는 개념을 정본 컬럼으로 두고, 확인 못 하는 칸은 지어내지 않고 비운다.**

**컬럼은 이미 둘이다 — 새로 열지 않는다.** `FinancialFigures`의 클래스 javadoc이 **이 계약을
이미 선언해 두었다**: *"순이익이 둘이고 어느 쪽도 틀린 값이 아니다. `netIncome`은 **연결 총액**,
`netIncomeControlling`은 **지배기업 소유주 귀속분**. 어느 쪽을 쓸지는 짝이 정한다."*
**미국 적재가 그 계약을 어기고 `net_income`에 지배주주를 넣은 것**이고(ADR-0154),
이 결정은 **계약을 복원**한다.

| 컬럼 | 뜻(두 시장 공통) | 채우는 순서 (네 갈래, 두 시장 동일) |
|---|---|---|
| `net_income` | **연결 총액** | 직접 → `지배주주 + 비지배` → **그 종목이 비지배를 한 번도 안 내면** 지배주주 → **NULL** |
| `net_income_controlling` | **지배주주 귀속** | 직접 → `연결 − 비지배` → **비지배 전무면** 연결 → **NULL** |
| `total_equity` | **연결 총액** | 직접 → `지배주주 + 비지배지분` → 비지배 전무면 지배주주 → **NULL** |
| `total_equity_controlling` | **지배주주 귀속** | 직접 → `연결 − 비지배지분` → 비지배 전무면 연결 → **NULL** |

**MUST: 기저 판정을 적재 계층에 둔다. 지표 계층에 폴백을 남기지 않는다.**
「그 종목이 비지배를 한 번도 안 내는가」는 **종목 단위 증거**이고 적재 계층에만 있다
(`SecEdgarFacts`가 이미 `noNoncontrolling`을 그렇게 계산해 `totalLiabilities`에 쓴다).
지표 계층에 폴백을 두면 **행마다 기저가 섞이고 그 합은 그럴듯하다.**

**실측 — 게이트가 「지어내기」를 「정직한 NULL」로 바꾼다**(캐시 1,002종목 · 창 65,169):
직접 **54.6%** + 유도 **0.6%** + **비지배 전무 게이트 36.1%** + **NULL 8.7%**(5,702창 · 306종목).
게이트 없이 폴백하면 42.8%가 추정값이고 그중 **32.5%가 비지배를 실제로 신고하는 회사**다.

**지표는 자기 분모에 맞는 기저를 이름으로 고른다.**

| 기저 | 지표 |
|---|---|
| **지배주주** | `ROE` · `ROE_MIN_3Y` · `PER` · `PER_MEDIAN_5Y` · `EPS_TTM` · `EPS_CAGR_5Y` · `EPS_GROWTH_TTM_YOY` · `DIVIDEND_PAYOUT` · `EXEC_PAY_TO_NET_INCOME` · **`PBR`** · **`PBR_MEDIAN_5Y`** |
| **연결 총액** | **`ROA`** · **`NET_MARGIN`** · **`ACCRUAL_RATIO`** · `DEBT_RATIO` · `ALTMAN_Z_DOUBLE_PRIME` · `ROIC_PRETAX` · `OPERATING_MARGIN` · `GROSS_MARGIN` · `GP_TO_ASSETS` · `FCF_MARGIN` |

## 왜 `net_income`이 지배주주여야 하는가 — 커버리지가 정한다

처음에 **`net_income`을 연결총액으로 뒤집으려 했고, 독립반증이 실측으로 기각했다.**

**미국 창 60,054 기준(팩트 캐시 전수 × 스윕 창 정합)**

| 채우는 갈래 | `net_income` = **연결총액**이라면 | `net_income` = **지배주주**라면 |
|---|---|---|
| 직접 태그 | 32,773 **54.57%** | 55,068 **91.70%** |
| 유도(다른 쪽 ± 비지배) | 316 0.53% | 2,030 3.38% |
| **폴백(비지배가 없다고 가정)** | **25,727 42.84%** | 1,718 **2.86%** |
| 못 만듦 | 1,238 2.06% | 1,238 2.06% |

**연결총액으로 뒤집으면 42.84%가 추정값이 된다.** 그리고 그 폴백은 안전하지 않다 —
25,727행 중 **8,351(32.5%)이 비지배를 실제로 신고하는 회사**이고, 대조군(두 태그가 함께 있는 창)에서
**그런 회사는 81.50%에서 두 값이 다르다**(상대오차 중앙 1.95% · p90 **37.8%**).
비지배를 전혀 안 내는 회사에서만 오답률이 **4.01%**로 떨어진다.

**MUST: 「비지배가 없으니 같다」 폴백은 「그 회사가 비지배를 한 번도 안 낸다」일 때만 건다.**
`SecEdgarFacts`가 이미 `noNoncontrolling`을 티커 단위로 계산해 `totalLiabilities`에 쓰고 있다 —
손익에도 같은 게이트를 건다. **창 단위 부재를 회사 단위 부재로 읽으면 안 된다.**

**MUST: 국내의 부재는 「비지배가 0」이 아니라 「요약본 엔드포인트가 그 줄을 안 준다」이다**
(`docs/api-spec/dart-financial.md`). 미국 폴백의 근거를 그대로 이식하면
**269종목 · 분기 4,755행에 「지배주주」 이름표를 단 연결총액이 저장된다.**
**MUST: 국내 비지배 <b>순이익</b> 태그를 함께 매핑한다** —
`ifrs-full_ProfitLossAttributableToNoncontrollingInterests`(실측 연간 8/8 · 분기 8/8 · 항등식 16/16).
**지금 매퍼에 비지배 매핑이 0곳이다.**

## 규칙

- **MUST: 기저를 정할 때 지표를 묶지 않는다. 지표마다 분자와 분모를 적고 각각 판정한다.**
  **같은 컬럼을 분모로 쓴다는 것은 같은 기저라는 근거가 아니다.** ADR-0153이 `PBR`을
  `DEBT_RATIO`·`ALTMAN_Z`와 묶고 *"총자산·총부채와 같은 기저라야 **항등식이 선다**"*를 근거로
  달았는데, **`PBR`의 분자는 시가총액이고 연결자본과는 어떤 회계 항등식도 없다.**
  근거가 묶음의 구성원 하나에서 성립하지 않으면 **그 구성원은 그 묶음이 아니다.**
- **MUST: 컬럼의 뜻을 바꾸면 그 컬럼을 읽는 소비자를 전수로 세고 짝을 다시 적는다.**
  ADR-0154가 미국 `net_income`을 지배주주로 옮겨 `ROE`를 맞췄지만
  **`ROA`·`NET_MARGIN`·`ACCRUAL_RATIO`가 연결 분모에 지배주주 분자를 얹게 됐고 그것을 적지 않았다.**
- **MUST: 시장으로 가르지 않는다.** 술어는 **값의 유무**다(ADR-0153 §규칙의 이 줄은 유지된다).
- **MUST: 분자와 분모의 기저를 한 계산에서 한 번 정하고 둘 다에 적용한다.** 지배주주 짝이
  **둘 다** 있을 때만 지배주주로 내고, 아니면 **둘 다** 연결로 물러난다. **행 단위 폴백을 금지한다** —
  `sumControllingNetIncome`의 javadoc이 이미 *"행마다 폴백하면 합계가 어느 쪽도 아닌 수가 된다.
  값은 그럴듯하고 에러도 없다"*고 적었다. 엄격 짝의 커버리지 실측: US TTM 창 49,240 중
  분자 99.0% · 분모 94.4% · **둘 다 93.5%** — 짝을 강제해 잃는 창은 **0.9%p**뿐이다.
- **MUST: 계산기를 먼저 배포하고 적재를 나중에 바꾼다.** 적재가 먼저 닿으면 `RoeCalculator`가
  **연결총액 ÷ 지배주주자본**이 된다 — 미국 `total_equity / total_equity_controlling`이
  1.01 초과 **206종목** · 1.10 초과 79 · **최대 616.54배**, 국내 `034730` SK **3.197배**다.
  짝 함수를 먼저 넣으면 오늘 상태에서 **값이 한 톨도 안 바뀌므로 무해한 선행 배포**다.
- **MUST: `noNoncontrolling`인데 두 자본이 갈리면 직접 태그를 버린다.** 비지배를 한 번도 안 내는
  388티커에서 두 자본이 1% 넘게 갈리는 행이 **43개 · 6티커**다 —
  `NKE` 2017 Q1 총자본 85,000,000 대 지배주주 **12,165,000,000** · `UNM` 2021 FY −5,164.6M 대 6,033.9M ·
  `NFG` 2025 Q1 2,752.8M 대 **−76.2M**. 지금 순서는 직접 태그가 언제나 이겨
  **그 쓰레기 값을 `ROE`·`PBR` 분모로 승격시킨다.**
- **MUST: `net_income`의 뜻에 기대는 검산식을 함께 본다.** `GovernanceFactDeriver`가
  `net_income / eps`로 주식수를 검산하는데 `eps`는 지배주주 기준이다 — `net_income`이 뜻을 바꾸면
  **335행 · 116종목의 거버넌스 유도값이 빠지고** `EXEC_PAY_TO_NET_INCOME`은
  `버핏 우량주 + 지배구조`의 **필수 조건**이다. (이 결정은 `net_income`의 뜻을 유지하므로 안 깨지지만,
  **뜻을 건드리는 다음 결정은 여기부터 본다.**)

## 짝이 원리적으로 없는 지표 — 한계로 적되 수를 남긴다

`PSR` · `PEG` · `FCF_YIELD` · `NCAV_TO_MARKET` · `ALTMAN_Z`의 X4는 **시가총액(지배주주)을 연결
총량으로 나눈다.** `PBR`과 달리 **원장에 「지배주주 몫의 매출·FCF·순유동자산」이 없다** —
그 개념 자체가 회계에 없다. **우리 구현의 결함이 아니라 지표 정의가 혼합 기저라는 뜻이다.**

**MUST NOT: 이것을 근거로 `PBR`까지 「어차피 혼합이다」로 넘기지 않는다.** `PBR`은 짝이 **있다**
(`total_equity_controlling`, 미국 94.3%). **짝이 있으면 맞추고, 없으면 그 사실을 적는다.**

크기: 미국 최신 FY 987티커에서 `|total_equity − controlling| / |controlling|`이
**1% 초과 208(21.1%) · 5% 초과 112 · 20% 초과 54 · 50% 초과 29.**
상위 `NFG` 185.0배 · `EQH` 20.8 · `CL` 5.76 · `IBKR` 2.82 · `WYNN` 2.74 · `TPG` 2.49 · `KKR` 1.55.

## 측정 — 지금 어느 시장도 두 계열을 동시에 못 맞춘다

| | KR | US |
|---|---|---|
| `net_income` | `ifrs-full_ProfitLoss` = **연결총액**(98.6%) | `controllingIncome()` = **지배주주** |
| `net_income_controlling` | FY 71.0% · Q1 44.3% · Q2 44.3% · Q3 41.1% · Q4 39.2% | 0행 |
| `total_equity_controlling` | **0행** | 스윕 94.3% · **DB 35.5%**(재적재 전) |

**틀어진 크기**

- **미국 `ROA`·`NET_MARGIN`·`ACCRUAL_RATIO`** — 618종목 중 **118(19.1%)이 5% 초과 · 43(7.0%)이
  20% 초과**, **부호가 뒤집히는 종목 다수**: `CWEN` **+169M ↔ −231M** · `INTC` −267M ↔ +26M ·
  `OMC` −54M ↔ +44M. **연결 기준으로 손실인 기업이 양(+)의 마진으로 나간다.**
  `ACCRUAL_RATIO`는 **12개 조건 · 13개 프리셋**에 들어 있고, 최신 FY 425종목에서
  **0.5%p 초과 74종목 · 1%p 초과 38 · 최대 20.11%p(`LB`)**.
- **양 시장 `PBR`** — 위 §짝이 없는 지표의 표와 같은 축. `IBKR` **1.83 → 6.91** ·
  `TPG` 4.94 → 14.96 · `KKR` 1.19 → 2.96. `MAS`는 지배주주자본이 음수인데 연결이 양수라
  **`PBR` 518.61**을 낸다. **`저평가 가치주`가 `PBR`·`PBR_PCTL`을 읽는다.**
- **국내 `ROE`** — 연결/연결이라 내부 정합은 서지만 **주주 수익률이 아니다.**
  DB 전수(순이익 비지배 비중 324종목): **1% 초과 181 · 10% 초과 93 · 30% 초과 57 · 최대 6.35배.**
  캐시된 DART 응답 20종목 직접 계산: `034730` SK **3.197배** · `051910` LG화학 1.442.
- **세율 가족** — `FinancialMath.effectiveTaxRateOf`·`pretaxIncomeOf`가 `netIncome`을 읽어
  `EFFECTIVE_TAX_RATE`·`ROIC_AFTERTAX`·`ROIC_AFTERTAX_AVG_5Y`·`ROIC_AFTERTAX_MIN_5Y`로 흐른다.
  **62티커에서 입력이 바뀐다**(음수 법인세 부호 판정 104행·52티커 + 세전이익 유도 92행·12티커).
  `ROIC_AFTERTAX`·`ROIC_AFTERTAX_AVG_5Y`는 **프리셋 필수 조건**이다.

**MUST: 이득의 갈래를 갈라 적는다.** **오늘 미국 `ROE`는 이미 지배주주 ÷ 지배주주다.**
미국 쪽 `ROE` 이득은 **분모가 `total_equity`로 폴백 중인 43종목**뿐이고,
**`ROE` 이득의 거의 전부는 국내(181종목)에 있다.**

## 검증

**MUST: 쓰기 없는 경로로만 판정한다.** `financial-refill --tickers`는 **드라이런이 아니다** —
`FILL_MISSING`으로 덮고 `invalidate`를 불러 지표를 지운다(실측 한 종목 29,744행).
미국은 `SecEdgarFactsSweepTest` 전/후 diff, 국내는 **캐시된 DART 응답 픽스처 단위시험**과
**읽기 전용 live probe**로 판정한다.

**수용 기준**

1. `net_income`·`net_income_total`·`total_equity_controlling`을 **직접 / 유도 / 폴백** 세 갈래로
   **각각 수를 찍는다.** 단일 백분율은 기준이 아니다.
2. **폴백으로 채운 칸 중 「그 회사가 비지배를 한 번도 안 낸다」가 거짓인 것이 0**이다.
3. **`net_income_total`이 못 만드는 칸은 NULL이고, `ROA`·`NET_MARGIN`·`ACCRUAL_RATIO`가 그 칸에서
   값을 안 낸다.** 지어낸 값으로 채우지 않는다.
4. **한 TTM 창 안에서 기저가 섞인 창이 0**이다(오늘 기준으로 `net_income`을 연결로 뒤집었다면
   **4,730창 18.51% · 352티커**가 섞였을 자리다). **Q4 유도의 기저 혼합도 0**이다(같은 조건에서
   회계연도 693개 12.6% · 214티커).
5. **가변 목록과 예상 크기를 함께 적는다.** 바뀜: `PBR`·`PBR_MEDIAN_5Y` · `ROA` · `NET_MARGIN` ·
   `ACCRUAL_RATIO` · `EFFECTIVE_TAX_RATE` · `ROIC_AFTERTAX*`(62티커) · 국내 `ROE`·`ROE_MIN_3Y`·`PER`.
   **안 바뀜**: `DEBT_RATIO` · `ALTMAN_Z_DOUBLE_PRIME` · **`ROIC_PRETAX`**(영업이익 기저).
   **MUST NOT: 「`ROIC`가 안 바뀐다」로 적지 않는다** — 세후 셋은 바뀐다.
6. 국내 `total_equity_controlling`이 **88% 이상** 차고 항등식
   `자본총계 = 지배주주자본 + 비지배지분`이 **1% 이내로 성립**한다.

```bash
psql -d stock_screener -At -c "
WITH m AS (SELECT CASE WHEN s.market_code='US' THEN 'US' ELSE 'KR' END mk, count(*) n,
                  count(f.net_income) ni, count(f.total_equity_controlling) teqc
           FROM financial_statement f JOIN stock s ON s.id=f.stock_id
           WHERE f.is_latest GROUP BY 1)
SELECT string_agg(mk||' ni='||round(ni*100.0/n)||'% teqc='||round(teqc*100.0/n)||'%', ' | ') FROM m"
```

두 시장의 `teqc`가 **둘 다 80% 이상**이어야 한다. 한쪽이 0%면 이 결정이 안 실행된 것이다.

**비용(실측 외삽)** — 무효화 **17,853,245행 · 578종목**, 백필 **약 3시간**, 국내는
`full-statement-refill --all-quarters` **DART 15,400~16,600콜**(쿼터의 86~92%)이라 **이틀**이다.
**비용은 기각 사유가 아니지만 일정에 적는다.**

**MUST: 재적재·백필 대상에 편출 종목을 명시한다.** 오늘 편입이 아닌 미국 종목
**195개 · 지표 4,965,542행**이 옛 뜻을 든 채 남으면 **한 결과표가 두 기저로 계산된다** —
「시장으로 가르지 말라」가 **편입 상태**를 따라 다시 생긴다.
`activeUniverseMemberIdsByTicker`가 `effective_to`를 안 보므로 `--tickers`로 닿는다.

## 버린 대안

- **`net_income`을 연결총액으로 뒤집되 게이트 없이 폴백한다.** 미국 **42.84%가 추정값**이 되고
  그 32.5%가 비지배를 신고하는 회사에 떨어진다(대조군 오답률 81.5%). **기각.**
  게이트를 걸면 그 자리가 **36.1% 정당 + 8.7% NULL**로 갈린다 — 그것이 §결정이다.
- **`net_income_total` 컬럼을 새로 연다.** 처음에 이 안을 적었는데 **틀렸다** —
  `net_income`·`net_income_controlling` 둘이면 충분하고, `FinancialFigures`의 javadoc이
  **그 계약을 이미 선언**해 두었다. 셋째 컬럼은 같은 개념을 두 번 담는다.
- **연결 기저로 통일한다.** 상장주주가 받지 않는 이익을 그의 수익률로 보고하게 된다. 기각.
- **시장마다 그 시장 원장이 잘 주는 기저를 쓴다(현재 상태).** 같은 임계가 두 시장에서 다른 것을
  잰다. 사용자가 2026-09-15에 명시적으로 기각했다.
- **`PBR`을 연결 기저로 유지한다(ADR-0153 §규칙).** 그 근거인 「항등식」이 시가총액 분자에는
  성립하지 않는다. §규칙 첫 줄이 이 오류의 형태를 이름 짓는다.
