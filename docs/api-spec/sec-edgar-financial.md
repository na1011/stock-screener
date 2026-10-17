# SEC EDGAR — 재무 계정 (companyfacts)

미국 재무의 원자료. **XBRL로 제출된 전 기간의 팩트를 기업당 한 콜**로 준다 — 다만 정기 공시 일부의 us-gaap를
통째로 빠뜨리고, 그 몫은 공시 보충이 그 공시의 인스턴스로 채운다(아래 「공시 보충」 · ADR-0173).
재무를 EDGAR로 정한 근거는 ADR-0086에, 이 축의 결정은 ADR-0089에 있다.

- 어댑터는 `pipeline/datasource/us/SecEdgarFinancialAdapter`, 포트는 `FinancialDataSource`,
  적재는 `InitialLoadJob`이다.
- **HTTP 코드 → `Kind` 매핑의 정본은 `SecEdgarResponse`의 javadoc이다.** 문서로 떼어 두면
  갈려도 아무도 모른다(`DartResponse.kindOf`·`AlpacaResponse.kindOf`와 같은 취급).
- **티커 → CIK 매핑 계약의 정본은 `SecTickerCikCache`의 javadoc이다**(`DartCorpCodeCache`와
  같은 취급) — 해석 지점이 저장소에 그 클래스 하나뿐이다.
- 쿼터와 엔드포인트 색인은 `api-sources` skill이 답한다.
- 탐침: `SecEdgarProbeLiveTest` — **아래 실측이 그 출력 그대로다.**
- 호출 방법과 함정의 원문은 [`../external/us-market-api-dev-guide.md`](../external/us-market-api-dev-guide.md) §2.

여기는 **이 축의 필드 계약과, 그 문서가 답하지 않았거나 실측에서 달랐던 자리**다.

---

## 엔드포인트

```
GET https://data.sec.gov/api/xbrl/companyfacts/CIK##########.json
GET https://www.sec.gov/files/company_tickers_exchange.json
GET https://data.sec.gov/submissions/CIK##########.json                      # 공시 보충(ADR-0173)
GET https://data.sec.gov/submissions/CIK##########-submissions-NNN.json     # 그 페이지
GET https://www.sec.gov/Archives/edgar/data/{cik}/{접수번호 숫자만}/index.json
GET https://www.sec.gov/Archives/edgar/data/{cik}/{접수번호 숫자만}/{인스턴스}
```

**호스트가 둘이다.** JSON API는 `data.sec.gov`, 매핑 파일과 벌크는 `www.sec.gov`다.
인증도 API 키도 없고 **신원을 밝히는 `User-Agent`만** 요구한다.

| 항목 | 쓰는 값 | 왜 |
|---|---|---|
| `User-Agent` | `<이름> <이메일>` — **명시 필수** | 없으면 미분류 봇으로 판정해 차단한다. HTTP 클라이언트가 자기 기본값을 붙이므로 반드시 덮어쓴다 |
| CIK | **10자리 zero-padding** | 안 하면 404다. 개발가이드가 *"404의 최다 원인"*으로 적었고 실측에서도 404였다 |
| 호출 속도 | **초당 5회**(공표 10회의 절반) | `api-sources` skill의 「착수 판단은 공표값의 절반」 |
| `Accept-Encoding` | 붙여도 되고 안 붙여도 된다 | 아래 실측 |

---

## 실측 (2026-08-29)

`./gradlew liveTest --tests "*SecEdgarProbe*"`

### 티커 → CIK 매핑 파일

| 값 | 실측 |
|---|---|
| `company_tickers_exchange.json` | **521,231바이트 · 10,391행** · `fields=["cik","name","ticker","exchange"]` |
| 서로 다른 CIK | **8,004** |
| 티커가 둘 이상인 CIK | **1,443건** (예: `[GOOGL, GOOG, GOOGM, GOOGN]` · `[BRK-B, BRK-A]`) |
| 거래소 분포 | Nasdaq 4,364 · NYSE 3,299 · OTC 2,497 · (없음) 197 · CBOE 34 |

**우리 US 8,504종목 중 — 그대로 붙음 6,665 · 점→하이픈으로 붙음 24 · 못 붙음 1,815.**

| 못 붙은 것의 모양 | 수 |
|---|---|
| 점 없음 (대부분 ETF·펀드) | **1,332** |
| `.PR*` (우선주) | 365 |
| `.WS`·`.WSA` (워런트) | 52 |
| `.U` (SPAC 유닛) | 47 |
| `.RT`·`.RTWI` (권리) | 19 |

**`.PR<X>`를 `-P<X>`로 바꾸면 우선주 365개가 더 붙는다**(소스는 `JPM-PC` 형태로 싣는다).
**그 CIK는 보통주와 같다** — 붙이면 발행사 재무가 우선주 행에 실린다. **지금은 안 붙인다**(ADR-0089).

**파일럿 11 중 10이 붙는다.** `KIM.PRL`만 안 붙고, `BRK.A`와 `BRK.B`는 **같은 CIK**(0001067983)다.

### companyfacts

표본 19종목(파일럿 10 + 은행·1월 결산·5월 결산·바이오·REIT 등 9).

| 값 | 실측 |
|---|---|
| 합계 | **65.2MB · 팩트 425,638개** |
| 한 종목 | **최대 7.9MB**(JPM) · **최소 78KB**(XOM) — **100배 차이가 난다** |
| 전량 환산 | 같은 평균이면 8,504종목이 **약 29GB** |
| `companyfacts.zip`(벌크) | **1.4GB** — 종목당 1콜의 **1/20**이다. 다만 하루 한 번 재발행이라 신선도가 다르다 |
| 네임스페이스 | `us-gaap`·`dei`가 기본이고 `invest`·`srt`·`ecd`·`ffd`가 섞인다 |
| `us-gaap` 개념 수 | 94(XOM) ~ 918(JPM) |

**팩트 하나의 필드**: `start`(기간형만) · `end` · `val` · `accn` · `fy` · `fp` · `form` · `filed`,
그리고 **`frame`은 있을 때만 있다**(AAPL 표본에는 없고 MSFT·JPM에는 있다).

**`units` 키가 통화가 아니다.**

| 키 | 건수 |
|---|---|
| `USD` | 8,539 |
| `pure` | 502 |
| `shares` | 400 |
| `USD/shares` | 280 |
| `EUR`·`JPY`·`GBP` | 5 · 2 · 2 |
| 그 외 | `segment`·`store`·`employee`·`sqft`·`USD/Warrant` … |

**3자를 넘는 키가 44종이다.** `financial_statement.currency_code`가 `character(3)`이라
그대로 담으면 `value too long`으로 **그 종목의 적재가 통째로 롤백된다.**

**`fp`가 `FY|Q1~Q4`만이 아니다**: `FY` 151,975 · `Q2` 99,860 · `Q3` 96,445 · `Q1` 73,334 ·
**`CY` 205 · `Q4` 599 · 빈 값 3,220**.
**`form`도 재무제표만이 아니다**: `10-Q` 265,991 · `10-K` 145,816 · `8-K` 9,688 ·
`10-Q/A` 2,464 · `10-K/A` 1,115 · `20-F` 529 · `DEF 14A` 30 · `PRE 14A` 5.

### 컬럼별 커버리지 — 분모 19종목

| 컬럼 | 커버리지 | 실제로 온 태그 |
|---|---|---|
| `revenue` | **19/19** | `RevenueFromContractWithCustomerExcludingAssessedTax` · `Revenues` · `SalesRevenueNet` · `RevenueFromContractWithCustomerIncludingAssessedTax` · `SalesRevenueGoodsNet` |
| `net_income` | 19/19 | `NetIncomeLoss` · `ProfitLoss` · `NetIncomeLossAvailableToCommonStockholdersBasic` |
| `total_assets` | 19/19 | `Assets` |
| `total_equity` | 19/19 | `StockholdersEquity` · `…IncludingPortionAttributableToNoncontrollingInterest` |
| `operating_cash_flow` | 19/19 | `NetCashProvidedByUsedInOperatingActivities`(+`…ContinuingOperations`) |
| `shares_outstanding` | 19/19 | `dei:EntityCommonStockSharesOutstanding` · `CommonStockSharesOutstanding` · `WeightedAverageNumberOfDilutedSharesOutstanding` · `…SharesOutstandingBasic` |
| `eps` | 19/19 | `EarningsPerShareBasic` · `EarningsPerShareDiluted` |
| `cash_and_equivalents` | 19/19 | `CashAndCashEquivalentsAtCarryingValue` · `CashCashEquivalentsRestrictedCash…` |
| `retained_earnings` | 18/19 | `RetainedEarningsAccumulatedDeficit` |
| `capex` | 17/19 | `PaymentsToAcquirePropertyPlantAndEquipment` · `PaymentsToAcquireProductiveAssets` |
| `operating_income` | **15/19** | `OperatingIncomeLoss` |
| `total_liabilities` | **15/19** | `Liabilities` |
| `current_assets` · `current_liabilities` | **15/19** | `AssetsCurrent` · `LiabilitiesCurrent` |
| `cost_of_sales` | **14/19** | `CostOfGoodsAndServicesSold` · `CostOfRevenue` · `CostOfGoodsSold` |
| `inventories` | **13/19** | `InventoryNet` |

**세율 재료 둘은 다른 표본(10종목)으로 쟀다** — 2026-09-14 · `SecEdgarConceptProbeLiveTest`의
낱말 스캔(`probe.conceptNeedles`). 업종 비중복으로 고르고 **금융업 다섯(SIC 60·61·62·63·67)을
넣었다**(ABBV·AAON·ABCB·AFRM·AMG·ACGL·CBRE·DLB·ABNB·ADM).

| 재료 | 태그 | 표본 | 읽는 법 |
|---|---|---|---|
| **법인세비용** | `IncomeTaxExpenseBenefit` | **10/10** | 금융업 다섯 포함이다 |
| **세전이익** | `IncomeLossFromContinuingOperationsBeforeIncomeTaxes`⏎`ExtraordinaryItemsNoncontrollingInterest` | **10/10** | **계속영업 + 비지배 포함**이다 — 국내가 분자만 계속영업이고 분모가 총액인 것과 **기저가 다르다** |
| 세전이익(지분법 변종) | `…BeforeIncomeTaxesMinorityInterestAndIncomeLossFromEquityMethodInvestments` | 5/10 | 위 태그와 **배타적이 아니다** — 둘 다 가진 회사가 있다 |
| 당기·이연 법인세 | `CurrentIncomeTaxExpenseBenefit` · `DeferredIncomeTaxExpenseBenefit` | 각 10/10 | 합이 `IncomeTaxExpenseBenefit`인지는 **재보지 않았다** |
| 유효세율 | `EffectiveIncomeTaxRateContinuingOperations` | 10/10 | **MUST NOT: 이 값을 쓰지 않는다** — spec §7이 「지표는 원자료를 수집한 뒤 자체 계산」을 걸고 `data-pipeline.md`가 「외부 API가 계산해 주는 지표 값에 의존하지 않는다」를 건다. **대조군으로만 쓴다** |

**MUST: 이 10/10을 US_TOP1000의 커버리지로 옮겨 적지 않는다.** 표본 열은 「닫히지 않았다」까지만
낸다 — ADR-0104의 `117/119`가 **금융업 중 영업이익이 막힌 119**의 수였던 것과 같은 구분이다.

**국내와 기저가 갈리는 자리를 함께 적는다.** 국내는 분자가 `…IncomeTaxExpenseContinuingOperations`
(계속영업)이고 분모로 쓸 수 있는 것이 **총액뿐**이지만(계속영업 세전이익 0/8), 미국은 분모가
**계속영업 + NCI**다. 그리고 미국 `net_income`은 `NetIncomeLoss`(**지배주주 귀속**)라
**항등식 `세전 = 순이익 + 법인세`가 비지배지분만큼 항상 어긋난다** — 국내에서 쓰는 검산을
미국에 그대로 옮기면 안 된다.

**빈 것이 결손이 아니다.** 은행·REIT는 재무상태표를 유동/비유동으로 가르지 않아
`AssetsCurrent`가 원리적으로 없고, 재고가 없는 업종도 마찬가지다 — 국내 금융업과 같은 성질이다.

**`dei:EntityCommonStockSharesOutstanding`이 없는 종목이 19 중 2**(META·VTEX)이지만,
주식수 컬럼 자체는 다른 후보가 받아 **19/19**다.

**부호**: `PaymentsToAcquire…`(capex)가 **2,276건 전부 양수**다 — 포트가 요구하는
「유출을 양수 크기로」와 같다(ADR-0067). 영업활동현금흐름은 양수 2,392 · **음수 232**로,
음수가 정상 값이다(적자 기업).

### 영업이익 결손의 원인 — 큰 쪽은 「뒤 판이 앞 판을 지운 것」이었다 (2026-08-31 실측)

`SecEdgarColumn.OPERATING_INCOME`의 후보가 `OperatingIncomeLoss` **하나뿐인 유일한 손익 컬럼**
이라 「후보를 넓히면 된다」로 보였는데, 결손 종목 12개의 `companyfacts`를 열어 `Operating`·
`GrossProfit`·`CostsAndExpenses` 계열 태그를 전수로 세니 **더할 동의어가 없었다.**
그 다음 처방도 틀렸다 — 「그 창에 duration 팩트를 못 붙였다」로 적었는데 **값은 붙어 있었다.**

**A. 소스에 영업이익 태그 자체가 없다** — `ADM`·`AER`·`BMY`·`IBM`은 FY 7행 중 **0행**이고
`OperatingIncomeLoss`를 아예 안 낸다. 대신 있는 것은 `GrossProfit`(표본 12 중 9종목·연간
973팩트) · `CostsAndExpenses`(6종목) · `OperatingExpenses`(5종목)다.
**MUST: 이것을 「동의어 태그를 더한다」로 읽지 않는다 — 더할 동의어가 없다.**
**MUST: 그 문장의 모집단을 함께 읽는다 — 영업이익 결손 12종목이고 훑은 계열이 `Operating`·
`GrossProfit`·`CostsAndExpenses` 셋이다.** `CostOf*` 계열은 그때 안 훑었으므로
**`COST_OF_SALES`에 대한 주장이 아니다** — 그쪽은 아래 §매출원가 후보를 전수로 물었다 절이
답한다.
여는 길은 **유도**(`GrossProfit − OperatingExpenses` 또는 `Revenues − CostsAndExpenses`)이고,
그것은 태그→컬럼 매핑이 아니라 **새 기제**다. **그 셋은 ADR-0104가 컬럼으로 담았다** —
`financial_statement.gross_profit`·`operating_expenses`·`costs_and_expenses`이고 채워져 있다
(`AER` FY2024 `operating_expenses` 5,739M). 유도는 지표 계층에서 돈다.

**B. 뒤 판이 앞 판의 값을 지웠다 — 이쪽이 컸다.** `AAON` FY2022가 그 모양이다.

```
rev0 10-K 2023-02-27  매출 888,788,000 · 영업이익 126,761,000 · 이익잉여금 461,765,000
rev1 10-Q 2023-11-06  매출 NULL        · 영업이익 NULL        · 이익잉여금 461,657,000  ← is_latest
```

이익잉여금이 **10.8만 달러 실제로 정정**됐고 `conflicts`가 그 한 컬럼을 보고 판 전체를 새로
냈는데, 새 판은 10-Q의 **비교 재무상태표**뿐이라 손익이 통째로 비었다. 전수(US_TOP1000 ·
FY `is_latest` 6,568행)로 **영업이익 373행 · 매출 505행 · 총자산 543행**이 그렇게 사라져 있었다.
**ADR-0102가 판을 겹쳐 닫았다.**

**MUST: 커버리지를 게이트가 쓰는 창으로 잰다.** 「최근 15개월 · 4분기 이상」으로 재면
영업이익 764/1,000이 나오는데 **어느 게이트도 그 창을 안 쓴다.**
`OperatingMarginMin5yCalculator`는 **연속 5개 회계연도 + `revenue > 0`**,
`ProfitStreakYearsCalculator`는 **연속 7개 회계연도**를 요구한다
(`FinancialMath.isConsecutiveAnnual`, 연간 gap 300~430일). **그 창으로 잰 수만 여기 적는다.**

| 게이트 | ADR-0102 전 | 뒤 | 상한(연속성만) |
|---|---|---|---|
| `OPM_MIN_5Y` 산출 가능 | 537 | **690** | 903 |
| `PROFIT_STREAK_Y` 산출 가능 | 478 | **636** | 829 |

**A는 유도로 닫았다 — 다만 절반만이다**(ADR-0104 · 2026-08-31).

**금융업을 「원리적으로 없다」로 읽은 것이 오판이었다.** 막힌 미국 금융업 119종목 전량에
물으니 **117(98.3%)**이 세전이익 태그를, **83(69.7%)**이 은행형 4요소
(`InterestAndDividendIncomeOperating`·`NoninterestIncome`·`InterestExpense`·
`NoninterestExpense`) 또는 보험형(`Revenues` + `BenefitsLossesAndExpenses`)을 갖는다.
비금융 86종목은 `GrossProfit − OperatingExpenses`가 3.7% · `Revenues − CostsAndExpenses`가
21.6% · 둘 중 하나가 **25.3%**를 덮는다.

**유도식은 소스 자신의 신고값으로 검증했다** — 둘 다 있는 **3,258행**에서 오차 10% 이내가
**88.6%**(1% 이내 83.3%)이고 부호가 다른 것이 3.4%다.

| | 착수 전 | ADR-0102 뒤 | ADR-0104 뒤 |
|---|---|---|---|
| `OPM_MIN_5Y` 산출 가능 | 537 | 690 | **752** |
| `PROFIT_STREAK_Y` 산출 가능 | 478 | 636 | **705** |
| 금융업 영업이익 결손율 | 70.0% | 70.0% | **38.2%** |
| 비금융 결손율 | 11.4% | 11.4% | **8.7%** |

**남은 65종목은 재료 자체가 없다.** 그리고 한 종목의 5년 창에 신고값과 유도값이 섞이는 것이
**13종목** 있다(전부 신고 714 · 전부 유도 62) — `OPM_MIN_5Y`가 최솟값을 쓰므로 그 13에서는
최솟값이 정의 차이에서 나올 수 있다.

**「재료 자체가 없다」를 종목마다 다시 물었다 — 셋째 갈래가 있다 (2026-09-28 · `.factcache` 0콜).**
위 두 갈래(`GrossProfit − OperatingExpenses` · `Revenues − CostsAndExpenses`)가 막힌 종목 중
**원장이 `Revenues`와 `OperatingExpenses`를 같은 회계연도에 주는 것이 16**이다 —
`AER AFL AMG APO ARES BGC BRO CACC CASY DVN EVR GATX GLXY HOOD PRU TPG`(보험·자산운용·리스가
대부분이다). 모집단은 「현 편입 US_TOP1000 중 `fiscal_year >= 2023`의 `is_latest` FY에
`operating_income`이 **한 행도 없는** 183종목」이고, 그중 그 회계연도에 매출원가 태그까지 있는
4는 빼고 센 수다. `AER`가 그 자리다 — `OperatingExpenses` **88건(fy2010~2025)**이 SG&A 489M과
**따로 있는 총비용**이고 FY2024 `7,997 − 5,739 = 2,258M`이 순이익 2,099M과 정합한다.

- **이 갈래는 §버린 대안에 없다**(ADR-0104가 기각한 것은 「매출총이익 − SG&A − R&D」와
  「세전이익 대용」이다). **열지도 닫지도 않은 채 여기 수만 적는다** — 판정은 그 ADR의 일이다.
- **정확도를 답을 아는 칸에서 재면 기준 미달이다**: 신고 영업이익과 `OperatingExpenses`를 둘 다
  가진 **317종목** 중 10% 넘게 틀린 것이 247(93%)이고, 「그 해에 매출원가 태그가 없는」
  50종목으로 좁혀도 **14(28%)**다 — §검증이 이 결정을 정당화한 수(10% 이내 88.6%)에 못 미친다.
- **MUST: 그 28%가 발화 칸의 수가 아님을 함께 읽는다.** 유도가 도는 칸은 답을 모른다
  (`.claude/rules/data-pipeline.md`가 이 구분을 요구한다). **가르는 가설은 「영업이익을 신고하는
  회사의 `OperatingExpenses`는 그 소계 위의 항목이고, 안 신고하는 회사의 것은 총비용이다」**이고
  **그것은 아직 안 쟀다.**
- **MUST NOT: 모집단을 「이력 전체에 태그가 있는가」로 가르지 않는다 — 그렇게 갈라서 틀렸다.**
  `AER`는 `CostOfRevenue`를 **fy2010에 3건**만 갖는데 그 축으로는 「매출원가 있음」이 되어
  자기 자신이 모집단에서 빠졌다. **회계연도 안에서 묻는다.**

**`MGA`는 이 갈래로도 안 열린다 — 진짜 한계다.** 원장에
`OperatingIncomeLoss`·`OperatingExpenses`·`CostsAndExpenses`가 **셋 다 없고** 있는 것은
`CostOfGoodsAndServicesSold`·SG&A·R&D다. 남는 길이 이미 기각된 「매출총이익 − SG&A − R&D」뿐이고
FY2025 한 칸으로 재도 `5,989 − 2,221 − 792 = 2,976M` 대 세전 **1,308M**(127% 초과)다.

### 매출원가 후보를 전수로 물었다 — 더할 것이 없다 (2026-09-14 · 약 4,500콜)

`GROSS_MARGIN` 결측 **380**을 전 회계연도로 갈라 **278**(한 번도 없음) + **102**(과거엔 있었음)로
나누고, 380 전량에 후보 아홉(기존 넷 + 낱말 스캔이 낸 다섯)을 `companyconcept`로 물었다.

| | 종목 |
|---|---|
| **(a)** 아홉 태그가 전부 404 | **239** |
| **(b)** 우리 후보 밖 태그만 있거나 그쪽이 더 최신 | **64** |
| 우리 후보가 최신인데 저장은 빔 | **75** |

**(b) 64의 51을 만드는 태그가
`CostOfGoodsAndServiceExcludingDepreciationDepletionAndAmortization`**이고 **정의상 D&A를 뺀
원가**다. 그 51 중 **같은 기간에 D&A 조각을 함께 내는 회사는 10뿐**이고, 그 10에서
**D&A ÷ 제외원가**가 `PACS` 1.8% · `MTZ` 2.4% · `CASY` 2.6% · `ARMK` 2.9% · `CCK` 4.2% ·
`APPF` 5.1% · `ATR` 12.0% · `EAT` 14.7% · **`PEG` 27.4% · `PNW` 48.5%**(중앙 5.1%)다.
**그래서 안 더했다**(ADR-0146 §버린 대안).

**(b) 64를 만든 태그는 다섯이고 전부 부분 개념이다**(2026-09-14 · ADR-0150이 계약에 넣었다).

| 종목 | 태그 | 왜 부분인가 |
|---|---|---|
| 51 | `CostOfGoodsAndServiceExcludingDepreciationDepletionAndAmortization` | 정의상 D&A를 뺀 원가 |
| 6 | `CostOfReimbursableExpense` | 청구대행 비용 — 9종목이 갖고 **6은 그것만 있다** |
| 3 | `CostOfServicesExcludingDepreciationDepletionAndAmortization` | 〃 |
| 3 | `CostOfServices` | **39종목이 갖는데 38이 상품원가 태그를 함께 가진다** — 용역 쪽 절반이다 |
| 1 | `CostOfGoodsSoldExcludingDepreciationDepletionAndAmortization` | 〃 |

**회사가 같은 태그에 담는 내용을 바꾼다 — 이것이 이 소스의 가장 어려운 성질이다.**
`CNP`는 후보가 `CostOfGoodsAndServicesSold` **하나뿐**인데 원가율이 FY2017 **39.37%**에서
FY2018 **0.64%**로 떨어지고 회복되지 않는다(FY2025 0.04%). 태그가 갈린 것이 아니라 **그 태그의
내용이 갈렸다.** `JEF`·`MUR`·`AJG`·`WEX`가 같은 모양이고, **후보 선택 규칙으로는 원리적으로
못 고친다** — 지표 쪽에서 전제를 검사한다(ADR-0149).

**전기가스(`SIC:49`)는 원가를 성분으로 쪼개 낸다** — `CostOfPurchasedPower` ·
`CostOfNaturalGasPurchases` · `CostOfDomesticRegulatedElectric` · `CostOfPurchasedOilAndGas`.
**후보로는 안 열리고 유도가 필요한 별개 기제**다.

**`LiabilitiesCurrent`도 같은 방법으로 물었다** — `current_liabilities` 결손 141종목 중
**133(94.3%)이 404**다. 남는 8(`AMG`·`BEN`·`ENVA`·`FRHC`·`IVZ`·`JEF`·`OWL`·`SOFI`)은 소스는
주는데 우리가 못 담은 것이다. **「금융업은 유동/비유동을 안 가른다」가 이제 종목별 실측이다.**

### 버려지는 팩트가 무엇인가 — 전부 누적이다 (2026-09-14 · `companyfacts` 직접 대조)

적재 로그의 *"기간을 만들지 못한 팩트를 버렸다"*가 **전체 366,578건 · 종목당 평균 374**다.
**그 정체를 저장은 안 남기므로** 확인하려면 `companyfacts`를 받아 창 길이로 분류해야 한다.

`AIG`(452건 버림 · 보고서 78건)를 후보 태그 전수로 분류한 결과:

| 창 | 건 |
|---|---|
| 분기(80~100일) | 1,200 |
| 연간(350~380일) | 607 |
| 시점(잔액) | 858 |
| **반기 누적** | **332** |
| **3분기 누적** | **316** |
| 그 밖 | **0** |

**「그 밖」이 0이다 — 버려지는 것은 누적뿐이다.** 밴드가 분기 80~100일이라 **52/53주 결산의
14주 분기(98일)와 53주 연도(371일)도 들어온다.**

- **누적을 라벨로 쓰면 적극적으로 틀린다** — 9개월 누적(272일)은 **종료일이 Q3와 같다.**
- **실패 방식이 안전하다** — 유도는 기간 포함관계로 **정확히 셋**을 요구하므로 누적이 섞일 수 없다.
  없어지지, 틀려지지 않는다.
- **비용**: 누적만 내는 서명(Q1만 있음)이 **9 종목연도**다.

### 외국 발행인은 6-K로 중간보고를 낸다 — 2026-09-28에 그 폼을 받기 시작했다

`PERIODIC_FORMS`와 `INTERIM_FORMS` **둘 다**에 `6-K`·`6-K/A`가 있다(ADR-0164). 한쪽만 더하면
`ANNUAL_FORMS`가 여집합 유도라 6-K를 **연차로 분류한다.**

**MUST NOT: 「6-K를 받으면 분기가 채워진다」로 읽지 마라 — 실측은 그렇지 않다.** 0콜 스윕
전/후 diff(`.factcache` 1,217본 · 2026-09-28)로 **보고서 +190**(새로 난 210 · 사라진 20)이고
바뀐 종목은 **11**뿐이다. 그중 **Q1~Q4가 다 서서 TTM이 성립하는 것은 `ICLR` 하나다**
(Q1 18 · Q2 18 · Q3 17 · 유도 Q4 16). `AER`·`BNT`는 **연 1분기(Q2)뿐**이고 `DSGX`는 1건,
`CAMT`·`TEVA`·`CNH`는 2024년 이후가 0건이다. **대표 사례로 `AerCap`을 들면 오해를 부른다.**

**「6-K가 없어서 분기가 0」인 종목은 여전히 있다 — 그쪽은 진짜 한계다.** 라이브
`companyfacts` 17콜(2026-09-27)로 `CHKP`는 **9개월 누적만**, `NVMI`는 **반기만** 내고
`ESLT`·`GLBE`·`QGEN`·`STM`·`FSV`·`MGA`·`SOBO`·`WSE`는 **6-K 자체가 없다.** `STM`의 20-F에
있는 분기 창 233건은 전부 배당·손상 태그다.

**재는 법**(위 수를 다시 낼 때):
```
REPLAY_SWEEP_DIR=.factcache REPLAY_SWEEP_OUT=<경로.tsv> REPLAY_SUPPLEMENT_DIR=<같은 날 보충 원자료|off> \
  ./gradlew test --tests '*SecEdgarFactsSweepTest*' --rerun
```
를 변경 전/후로 돌려 `(ticker, fy, fp, start, end, pub)` 키로 diff한다. **`-D`는 이 테스트에
안 닿는다 — 환경변수다.** 모집단은 `.factcache`의 payload 수이고 `tickers.json`을 빼고 센다.

**MUST: `6-K`를 더할 때 통화를 함께 거른다.** `SMMT`의 6-K 팩트가 **`GBP` 1,545 · USD 0**
(2018~2020)이라 폼만 더하면 그 행이 저장되고, `CURRENCY_GATE`가 **종목 단위·기간 무제한**이라
**현 편입 종목의 93코드가 통째로 꺼진다**(ADR-0094). `SecEdgarFacts.MARKET_CURRENCY`가 막는다 —
전/후 diff에서 비-USD `currency_code` 행이 **0건**인 것이 그 검사다.

**FY 행이 새로 나는 것은 거짓 회계연도가 아니다.** 6-K가 FY 수치를 20-F보다 **먼저** 내므로
같은 기간·같은 값의 판이 **더 이른 공시일**로 하나 더 생긴다(`TSEM` FY2015: 20-F pub
2016-05-11 → 6-K pub **2016-02-29**, 순이익 동일). 시점 정확도가 올라가는 방향이다.

**MUST: 은행을 「분기 없음」으로 세지 마라.** `revenue IS NOT NULL`로 세면 `BNY`처럼
`Revenues`를 안 쓰는 은행이 통째로 걸린다 — `BNY`는 Q1~Q4가 다 있고 순이익·이자수익·
비이자수익이 찬다. **행 존재로 센다.**

### 기간 재구성 — 이 축의 핵심

AAPL(9월 결산) · MSFT(6월 결산) · JPM(은행) 셋으로 쟀다.

| 재는 것 | AAPL | MSFT | JPM |
|---|---|---|---|
| `fy`/`fp` 라벨 중 **종료일이 둘 이상**인 것 | **70/70** | **69/69** | **70/70** |
| 한 라벨의 최대 종료일 수 | 9 | **12** | 5 |
| 한 보고서가 **자기 종료일에 기간 길이를 둘 이상** 싣는 것 | **48/72** | 47/71 | 36/74 |
| 같은 기간을 **보고서 둘 이상**이 실은 것 | 112/123 | 110/126 | 100/111 |
| 그중 **값이 실제로 달라진** 것 | **5** | 11 | 13 |
| → 공시일만 보면 **가짜 정정**이 되는 수 | **107** | 99 | 87 |

**기간 길이 분포**(3·6·9·12개월로 접음):

| | 3개월 | 6개월 | 9개월 | 12개월 |
|---|---|---|---|---|
| AAPL 손익 / 현금흐름 | 208 / 28 | 34 / 28 | 36 / 30 | 60 / 48 |
| MSFT 손익 / 현금흐름 | 212 / 76 | 34 / 26 | 34 / 26 | 60 / 42 |
| JPM 손익 / 현금흐름 | 110 / 38 | 38 / 38 | 34 / 34 | 59 / 54 |

**현금흐름에도 3개월이 오지만 손익보다 훨씬 적다**(AAPL 28 대 208). 커버리지가 고르지 않아
분기 현금흐름을 쓰지 않는 근거가 이 표다.

**그 재구성이 회계연도를 통째로 떨어뜨리는 자리가 있다 — 소스 결손이 아니라 우리 구현이다.**
`labelPeriods`가 `fact.end() != anchor.end()`인 팩트를 *"비교표다. 그 기간은 자기 보고서가
라벨을 준다"*로 건너뛰는데, **자기 보고서가 원자료에 없으면 아무도 라벨을 안 준다.**
실측(2026-09-20 · 편입 미국 1,000 · 전 구간): **16종목 32회계연도**가 그렇게 빈다
(`SFD`·`TLN` 각 8 · `DSGX` 3 · `AOS` `CHRD` `CIFR` `DY` `ELF` `EXE` `JEF` `KRYS` `ORKA`
`SMCI` `SPGI` `VAL` `WULF` 각 1). 갈래가 둘이다 — companyfacts에 그 보고서가 **아예 없거나**
(`AOS`·`SPGI`는 10-K가 2024-02와 2026-02 둘뿐), 실려 있는데 **연간 기간을 한 건도 안 싣는다**
(`ORKA`의 2025-03-06 10-K · `DSGX`의 40-F 둘).

**MUST NOT: 이것을 「SEC가 안 준다」로 적지 않는다.** 원자료에는 있다 — `AOS`의
`2024-01-01~2024-12-31`이 뒤 공시 `0000091142-26-000008`의 비교표로 실려 있다. 재적재 없이
0콜로 재현한다:

```bash
REPLAY_FACTS=build/factcache/AOS.json REPLAY_FROM=2022 REPLAY_SUPPLEMENT_DIR=off \
  ./gradlew test --tests "*SecEdgarFactsReplayTest*"   # 2023/FY·2025/FY는 나오고 2024/FY가 없다
```

**`off`일 때의 현상이다** — 공시 보충(ADR-0173)은 companyfacts가 빠뜨린 그 10-K(2025-02-11)의 인스턴스로 2024/FY를
채운다(RS0 「HOLE 해」 4 = `AOS`·`SPGI`·`EMAT`). 보충 원자료 경로를 주면 이 명령은 반대 답을 낸다.

### 적재 실측 (파일럿 10종목 · 재무 3년)

`--job=initial-load --universe=US_LISTED --skip-price=true --reload-existing=true --financial-years=3`

| 값 | 실측 |
|---|---|
| 저장된 행 | **176행 / 10종목** (최신본 166 · 개정 10) |
| 종목당 | 16~27행. **VTEX만 1행** — `ifrs-full` 발행사라 `us-gaap`이 거의 없다 |
| 라벨을 못 만들어 버린 팩트 | 종목당 **207~384건**(반기·9개월 누적·회계연도 창 밖) |
| 두 번째 실행 | **정정 0건 · 행 수 그대로** — 멱등하다 |
| `Q1+Q2+Q3+Q4 = FY`(매출) | **전 종목·전 연도 일치** |
| `자산 = 부채 + 자본` | 151행 중 **10행 어긋남** — 전부 BRK의 2023년 |

**BRK 2023년이 어긋나는 이유는 폴백이다.** 그 해 공시가
`StockholdersEquityIncludingPortionAttributableToNoncontrollingInterest`를 달지 않아
`StockholdersEquity`로 떨어지고, 차이가 정확히 비지배지분이다. 2024년 이후는 맞는다.
국내 대조군은 15,386행 중 19행이다.

### `companyconcept`로 주식수만 따로 받기 — 실측 (2026-08-30 · 145콜)

```
GET https://data.sec.gov/api/xbrl/companyconcept/CIK##########/dei/EntityCommonStockSharesOutstanding.json
```

**`companyfacts`의 라벨 재구성을 통째로 우회한다.** 그래서 `MALFORMED`로 종목 전체가 거절된
`AMZN`·`JNJ`·`CSCO`·`AAP`도 값이 나온다. 거래대금 상위 1,000 사업회사 중 시총을 못 내던
**145종목에 전수로 물은 결과:**

| 결과 | 수 |
|---|---|
| **최근 2년 내 값 있음** | **107** |
| 개념 404 (그 CIK에 이 개념이 없다) | 22 |
| 값은 있으나 낡음(2년 초과) | 10 |
| `units.shares` 없음 | 4 |
| CIK 매핑 없음 | 2 |

**IMPORTANT — ADR에는 원주 수가 온다. 그것을 ADR 종가에 곱하면 조용히 틀린다.**

| 심볼 | `close × shares` | 실제 시총 | 배수 |
|---|---|---|---|
| `TSM` | **10,827B USD** | 약 1,050B | **10배** |
| `HSBC` | 1,778B | 약 220B | 8배 |
| `SHEL` | 517B | 약 220B | 2.3배 |

**MUST: ADR 판별축 없이 이 폴백을 쓰지 않는다.** 그리고 **이름 문자열로는 일부만 잡힌다** —
`SHEL`·`BHP`·`GSK`는 이름에 `American Depositary Shares`가 있지만 **`TSM`·`HSBC`·`NVS`·
`RY`·`SAP`에는 없다.** 미국 발행사(`AMZN` 2,874B · `JNJ` 646B · `XOM` 644B · `CSCO` 433B)는
근사값이 맞으므로, 가르는 축은 **발행사가 외국인가**(20-F 제출자)이지 이름이 아니다.
**그 축을 어디서 얻을지는 아직 안 정했다** — `submissions`의 `entityType`·제출 폼이 후보다.

**MUST: 최신성 가드를 둔다.** `V`가 **2010-01-27**, `BRK.A`/`BRK.B`가 **2011-04-29**로 멈춰
있고 그 값을 지금 종가에 곱하면 몇 배씩 틀린다. **`BRK.A`와 `BRK.B`는 CIK가 같아 같은 값
(941,481 = A의 주식수)을 받는다** — 최신성 가드가 그 둘을 함께 걸러 낸다.
**낡음 10과 404 22의 대부분이 외국 발행사이거나 이중 클래스다**(`BP`·`AZN`·`NVO`·`IBN`·
`ITUB`·`VALE`·`NTR`·`RACE`·`TRI` …). ETF·ETN(`TQQQ`·`SQQQ`)도 404다.

**호출 비용**: 종목당 1콜 · `permits-per-minute: 300`(초당 5회)이면 145콜에 **약 31초**,
8,504 전량이면 약 28분이다.

**IMPORTANT — `companyconcept`는 `companyfacts`와 <u>같은 팩트</u>를 준다(2026-09-14 실측).**
여섯 종목 × 네 태그에서 두 엔드포인트의 팩트 수가 **한 칸도 안 갈렸고**(AMZN 69·138·313·313 ·
BRK.B 7·82 · AKTX 29·72·90 · V 2) **같은 `(start, end, accn)`에 값이 둘인 자리가 0**이다.
즉 **축(`StatementClassOfStockAxis`)이 붙은 팩트를 주지 않는다** — 복수 클래스 발행사의
클래스별 주식수는 **이 엔드포인트로도 못 가른다.** 정본 탐침은 `SecEdgarConceptProbeLiveTest`다.

**그래서 「주식수 전량 NULL」의 원인이 한 갈래가 아니다.** `GEF`/`GEF.B`(같은 CIK 43920)는
후보 태그 넷이 **전부 404이고 `companyfacts`에도 없다** — 클래스가 서로 덮는 것이 아니라
**소스가 그 개념을 아예 안 낸다.** `V`는 팩트가 **2개뿐**이라 낡음이 원인이다(위 최신성 가드).

### 주식수 결손 165종목에 전수로 물었다 — companyfacts·companyconcept로는 메울 수 있는 것이 사실상 없다 (2026-09-14 · 660콜)

> **[2026-10-14 정정] 이 절의 결론은 두 엔드포인트에 대해서만 참이다 — 원장은 공시 인스턴스로 준다.** 정기 공시 폴더의 XBRL 인스턴스(`<primary>_htm.xml`)가 `us-gaap:StatementClassOfStockAxis` 구성원마다 `dei:EntityCommonStockSharesOutstanding`을 싣고(2011 판부터 · ★ `V`·`BRK`·`GOOGL`·`HEI`·`CMCSA` 10-K 4판씩), 클래스별 `EarningsPerShareBasic`·`WeightedAverageNumberOfSharesOutstandingBasic`도 싣는다(`V` C/A 3.997 · `BRK` A/B 1,500.3). 구성원→티커 짝(`dei:TradingSymbol` 구성원 맥락)은 2019 표지 iXBRL부터다. 대조군: 같은 공시의 축 없는 us-gaap 사실(기간까지 같은 것)이 companyfacts와 2,005 중 2,002 같다(`GOOGL`·`HEI`·`TPG`·`META` 4공시 · 팩트 캐시 09-15 · 다른 3은 같은 칸에 값이 둘인 자리 — 재지 않음). 모집단·질의·재현: 브랜치 `explore/financial-sync/SHARES-CLASS-MEASURE-1014.md` §2·§6·§7(SEC 인스턴스 752발행사 · 실패 14 = 정기 공시 없는 ETF·신탁 12 + 404 2). 아래 「`V` 최신 2010-01-27」·「`STZ` 네 태그 전부 404」는 companyfacts에 대한 사실로 남는다 — `V`·`STZ`의 클래스별 주식수는 인스턴스에 있다.

「재무는 있는데 `shares_outstanding`이 전량 NULL인 165종목」에 후보 태그 넷을 전부 물었다
(`-Dprobe.compareFacts=false`로 `companyfacts`를 건너뛰어 약 6분).

| 축 | 수 |
|---|---|
| CIK 매핑 없음 | **0** (`'.' → '-'` 정규화 뒤) |
| 후보 태그가 하나라도 200 | **39 / 165** (23.6%) |
| **최신 팩트가 2년 이내** | **16** |
| 최신 팩트 연도 | 2025:1 · 2024:21 · 2018:1 · 2017:1 · 2016:2 · 2015:4 · 2012:2 · 2011:5 · 2010:2 |

**IMPORTANT — 그 16이 전부 SPAC·유닛·워런트다.** `CHAR(R,U)`·`FACT(U,W)`·`IPEX(R,U)`·
`PLMK(U,W)`·`SVCC(U,W)`가 **`Acquisition Corp`**(SIC 6770 등)이고 매출이 **아예 없으며**,
남는 `MAMK`도 매출 **29.2M**으로 유니버스 진입선($0.32B)의 1/10이다. **열여섯 전부
`US_TOP1000` 편입 이력이 0이다.**

**그리고 ADR-0099 §미룬 문이 든 「실질 여섯」은 하나도 안 메워진다** — `V` 최신 **2010-01-27** ·
`PLNT` **2015-06-30** · `WTTR` **2018-03-31**이고 `STZ`·`IMKTA`·`GRDN`은 네 태그 전부 404다.
**즉 이 폴백으로 유니버스에 돌아오는 사업회사는 0이다.**

### ADS 비율 — 개념은 있고, 내는 발행사가 거의 없다 (2026-09-14 · 16콜)

`dei:EntityListingDepositoryReceiptRatio`(단위 `pure`)가 실재한다 — `BABA`가 **32팩트**를 낸다.

**그러나 ADR·ADS 대표 16종목에 물으면 내는 것은 `BABA` 하나뿐이다**(6.3%).
`AKTX`·`TSM`·`HSBC`·`NVS`·`SAP`·`UL`·`BP`·`TM`·`SONY`·`PDD`·`NIO`·`ASML`·`BIDU`·`GHG`·`FENG`가
전부 **404**다. **「개념이 없다」와 「그 발행사가 안 낸다」를 갈라 적는다** — 앞은 거짓이고
뒤가 참이며, 커버리지가 1/16이라 **ADS 비율의 일반 해법이 못 된다.**

### 공시 보충 — companyfacts가 빠뜨린 정기 공시 (2026-10-06 실측)

**companyfacts는 판 이력 전부가 아니다.** 정기 공시 일부의 us-gaap 팩트를 통째로 안 싣는다 — 83 CIK 표본의
창 안 87건(2026 인라인 10-Q 78 · 정정 6 · 원본 3). 원장은 그 공시의 XBRL 인스턴스로 같은 값을 준다.
어댑터는 그 공시를 보충한다(`SecFilingSupplement` · ADR-0173). 탐침과 수집 도구는 `SecFilingSupplementCacheLiveTest`다.

**submissions의 공시 목록** — `filings.recent`가 **열 배열** 표다(행이 아니다).
- 열 16개: `accessionNumber` · `filingDate` · `reportDate` · `acceptanceDateTime` · `act` · `form` · `fileNumber` ·
  `filmNumber` · `items` · `core_type` · `size` · `isXBRL` · `isInlineXBRL` · `isXBRLNumeric` · `primaryDocument` ·
  `primaryDocDescription`.
- `recent`는 최근 1,000건 남짓이다. 더 오래된 공시는 `filings.files`의 페이지가 같은 열 배열로 싣는다.
  - 페이지 항목: `name`(`CIK##########-submissions-001.json`) · `filingCount` · `filingFrom` · `filingTo`.
  - 83 CIK에 페이지 176장(BTU: 1,631건 · 1998-07-14~2017-07-13 한 장).
- `isXBRL`은 0·1이다. **`isXBRL=1`이어도 폴더에 XBRL이 없을 수 있다** — 모집단 대상 547 중 37(6-K 2017-07~08 36 · ESLT
  2025-06 1)은 폴더에 인스턴스도 스키마도 링크베이스도 없다(어댑터는 `NO_XBRL`로 남긴다). 6-K는 83 CIK 표본에서 154건
  전부 0이었지만, 모집단에는 us-gaap 인스턴스를 실은 6-K가 있다(AER·BNT·DSGX·ICLR — 외국 발행사의 분기 라벨).

**공시 폴더** — `index.json`은 `directory.item[]`(`name` · `type` · `size` · `last-modified`)다. **인스턴스는 `.xml` 중
`FilingSummary.xml`과 링크베이스(`_cal`·`_def`·`_lab`·`_pre`·`_ref`)를 뺀 하나다.**
- 인라인 공시: `…_htm.xml`(표본 인라인 16/16). 주 문서 이름과 다를 수 있다(`main-20220630x10q_htm.xml`).
- 비인라인 공시: `btu-20180331.xml` 꼴.
- 하나로 안 갈리면 고를 근거가 없다(그 공시의 실패).

**인스턴스와 companyfacts의 대응** — companyfacts는 공시의 축 없는 us-gaap·dei 수치를 (개념 · 단위 · 기간)마다
하나씩 싣는다. 대조 결과는 아래와 같다.
- AAPL `0000320193-26-000020`: 283/283
- JPM: 787/788
- MSFT: 597/601

어긋남 다섯은 전부 companyfacts 쪽 정규화이고 `SecEdgarColumn` 매핑 밖이다. 하나는 유량을 시점으로 실은 것이고, 하나는 액면가
0.00000625를 6e-06으로 반올림한 것이다.
- 중복 키는 companyfacts도 가장 정밀한 쪽을 고른다(MSFT `UnrecognizedTaxBenefits` 3/3).
- 같은 정밀도에 다른 값은 인스턴스 8개에서 0건이었다.
- 컨텍스트 식별자는 표본에서 전부 그 공시의 CIK였다. 공동 등록(CEG)은 법인 축 아래에 싣는다.
- **예외 하나(모집단)**: SWX(Southwest Gas Holdings · CIK 1692115)의 FY2016 10-K는 컨텍스트 704개가 전부 전신
  Southwest Gas Corp(CIK 92416)의 것이다 — 그 공시는 다른 등록자의 팩트만 싣고, companyfacts도 그 팩트를 SWX에 안 싣는다.
  폴더는 접수번호마다 하나라 「다른 회사의 파일」이 섞일 수 없으므로, 이 CIK의 컨텍스트 0은 실패가 아니라 팩트 0이다.

**재무가 축에만 실린 공시** — 아래 공시는 companyfacts도 축 없는 리더도 재무를 못 낸다. 값은 원장에 있다.
- BTU 2018 10-Q: 재무가 `us-gaap:StatementScenarioAxis` 아래 443개, 축 없는 us-gaap 0.
- CEG FY2021 10-K: 재무가 `dei:LegalEntityAxis` 아래 659개, 축 없는 수치 6.

그 축을 받는 규칙은 별개 질문이다(`docs/reload-gate.md` 「축에만 실린 미국 재무」). GEHC FY2022 10-K(19KB)는 표지만 있다
— us-gaap 팩트 자체가 0이다.

**재지 않은 것** — 스로틀 응답 · `xbrl.idx`(같은 경로로 303을 돌려준다).

### 등록인 포함 검사 — 행의 재무가 어느 등록인의 것인가 (2026-10-16 실측 · ADR-0180)

저장된 재무 행의 접수번호(`report_code`의 뒤 칸)가 **전부** 「후보 CIK companyfacts의 `accn`(택소노미 무관) ∪ 같은 CIK 공시 보충 표의
접수번호」 안에 들면 그 행은 그 등록인의 것이다. 재무 행 있는 미국 5,128 · 오늘 파일 CIK로 **통과 5,070 · 실패 3**(REAX · XRPN ·
XRPNW — 공유 0 · 등록인 교체) · 부분 누락 109종목의 112건은 **전부 같은 CIK의 보충분**(companyfacts가 그 공시의 us-gaap를 안 싣는다).
매핑 없는 55 중 통과 51(개명 대상 티커의 CIK · EDGAR 회사 검색) · 후보 없음 4(워런트). 음의 대조(후속 등록인 CIK)는 전부 「하나도 안 듦」이다.

### 실패 계약

| 요청 | 응답 |
|---|---|
| 모르는 CIK | **HTTP 404** + `<?xml …><Error><Code>NoSuchKey</Code>…` |
| zero-padding 누락(`CIK320193.json`) | **HTTP 404** — 같은 XML |
| `Accept-Encoding: gzip, deflate` 명시 | **HTTP 200** · 3,789,099바이트 · JSON 파싱 성공 |

**오류 본문이 JSON이 아니라 XML이다.** 상태코드를 안 보고 파싱하면 예외가 나거나 「0건」이 된다.

**스로틀·차단 응답은 재지 않았다.** 유발하면 IP가 일시 제한되어 남은 측정이 전부 막힌다.
그 코드를 모르는 채로 어댑터를 짠다 — §함정의 마지막 줄이 그 처방이다.

---

## 함정

- **제출이 있는데 companyfacts가 그 판을 안 준다 — 「소스가 안 준다」가 아니다.**
  `DOX`(CIK **1062579**)는 20-F를 **2025-12-15**에 냈는데(`0001193125-25-319187`)
  `companyfacts`와 `companyconcept`가 **둘 다 2024-12-17에서 멈춘다**(라이브 실측 2026-09-28).
  그래서 우리 최신 FY가 `2024-09-30`이고 지표가 **21개월 낡은 채 멀쩡해 보인다** —
  결손이 아니라 **낡음**이라 어떤 「값이 없다」 검사에도 안 걸린다.
  **DOX 하나가 아니다** — 2026-10-05에 현 편입 80종목이 2026 Q1에서 멈춰 있었다(`docs/reload-gate.md` (가)).
  - **처방은 공시 보충이다**(ADR-0173 · 아래 「공시 보충」) — 그 공시의 XBRL 인스턴스를 직접 읽는다. 사본 재생에서
    `DOX`의 최신 FY가 `2025-09-30`(공시일 2025-12-15)이 됐다(2026-10-06 · 운영은 그 전이라 `2024-09-30`).
    「색인되면 `financial-refill`이 집어 간다」를 기다리는 것은 버린 대안이다(ADR-0173 — 언제 채우는지 계약이 없다).
  - **MUST: CIK를 풀어 쓴다.** 기억에서 만든 CIK의 404는 「색인 안 됨」과 구분되지 않는다 —
    실제로 `DOX`에 `DSGX`의 `1050140`을 붙여 한 번 오판했다.
- **주식수가 정확히 1000배 틀려서 온다.** 제출자의 inline XBRL `scale` 속성이 틀린 것이고
  **그 파일이 인쇄하는 숫자는 이미 옳다**(`PKG` 10-K: `scale="3" decimals="-3"`에 텍스트
  `89,213,394`). `companyfacts`만 그 속성을 곱해서 주므로 **EDGAR 뷰어로는 안 보인다** —
  SEC 자신의 R-file 렌더링은 텍스트를 찍는다. **후보 순서를 바꿔도 안 고쳐진다**: `PKG`는
  `dei:`만 있고 `DDS`·`ECHO`·`RPAY`는 `dei:`와 `CommonStockSharesOutstanding`이 **둘 다 없다.**
  방향도 양쪽이다(`PKG`·`TBLA`·`RPAY`는 1000배 크고 `DDS`·`ECHO`는 1000배 작다).
  감시와 처방은 ADR-0126에 있다.
- **`fp`를 분기 번호로 쓰면 안 된다 — 같은 보고서가 7~9월 기간에 `Q2`를 붙인다**(실측: AT&T).
  그리고 **1월 결산 회사는 연속한 두 회계연도에 같은 `fy`가 붙는다**(실측: RH의 연간이
  2023-01-28에 끝나는데 같은 `fy`의 분기가 2023-10-29에 끝난다. NVDA도 같다).
  둘 다 **기간이 다른 두 행에 같은 라벨**을 주고, 저장 키에 기간이 없어 앞엣것의 최신 표시가
  조용히 내려간다. 회계연도는 **12개월 팩트가 만드는 창**에서, 분기 번호는 **그 창 안의
  위치**에서 가져와야 한다.
- **진행 중인 회계연도의 분기는 창이 아직 없다.** 사업보고서가 나오기 전이라 그렇고,
  창이 없다고 버리면 **최근 분기 셋이 통째로 빠져 TTM이 최대 1년 뒤처진다.**
- **`fy`/`fp`는 그 사실의 기간이 아니라 그것을 실은 보고서를 가리킨다.** FY2024 10-K에 실린
  FY2023·FY2022 비교표 팩트도 `fy=2024, fp=FY`로 온다. 실측에서 **라벨 70개가 전부 종료일을
  둘 이상 갖고 최대 12개**였다. 그대로 라벨로 쓰면 여러 해가 한 행으로 뭉치는데,
  `financial_statement`의 키에 기간이 없어 **에러가 아니라 조용한 덮어쓰기**가 된다.
- **한 보고서가 같은 종료일에 여러 기간 길이를 싣는다**(72개 중 48개). 10-Q가 누적과 분기
  단독을 함께 싣기 때문이다. **회계연도는 앵커에서, 기간 구분은 길이에서** 가져와야 한다 —
  **다만 그 앵커가 연차보고서일 때만 회계연도 창이다**(ADR-0124).
- **한 라벨이 두 창을 가리키는 원인이 넷이고 처방이 다 다르다.**
  ① **종료일이 같고 시작일만 1~10일 다른 것**(2026-08-31 전수 236 중 93)은 52/53주 회계달력의
  표기 차이라 **같은 기간을 두 번 적은 것**이고 접는다(ADR-0101).
  ② **저장되지도 않을 옛 연도의 충돌**(145 중 116)은 판정이 적재 창 밖에까지 걸려 있던 것이라
  **판정 범위를 좁혔다**(ADR-0124).
  ③ **신년을 넘는 결산의 두 해가 같은 번호를 받는 것**(85)은 회계연도 번호를 `end.getYear()`로
  지은 탓이라 **며칠 물러서서 읽는다**(ADR-0124).
  ④ **10-Q의 「Twelve Months Ended」가 회계연도 창이 되는 것**(55)은 **회계연도 창을
  연차보고서에서만** 만들어 닫았다(ADR-0124) — `AMZN`은 창 69개 중 **52개가 10-Q**에서 왔다.
  **남는 것은 진짜 회계연도 변경**이고 그쪽은 거절이 유지된다.
  **시작일 차이만 보고 접으면 안 된다**: 10-Q가 누적과 분기 단독을 같은 종료일에 함께 실으므로
  분기와 반기가 접힌다.
- **같은 기간이 이후 보고서마다 다시 실리고 `filed`는 매번 늦다.** 값 비교 없이 공시일만 보면
  **112개 중 107개가 가짜 정정**이 되고, 정정 판정은 그 종목의 지표를 **보존 창 전체에서**
  지운다(`FinancialWriter`의 `FINANCIAL_REVISED`). **그 접기는 어댑터만 한다** — 저장 계층은
  공시일만 보고 값을 비교하지 않는다.
- **같은 회사가 연도마다 다른 후보 태그를 쓴다.** 태그를 기간 단위로 고정하되 **그 기간을
  자기 기간으로 낸 보고서가 쓴 태그**를 먼저 골라야 한다 — 아무 보고서나 가진 태그를 고르면
  앵커에 그 태그가 없을 때 값이 **null이 된다.** 실측에서 AT&T의 FY2023·2024 영업활동현금흐름이
  그렇게 사라졌다가, 규칙을 고치자 38.3B·38.8B로 채워졌다.
- **`units` 키를 통화로 쓰면 안 된다.** 44종이 3자를 넘고 `currency_code`가 `character(3)`다.
  통화는 **3자 ISO 키에서만** 뽑고 `-per-`가 있으면 분자에서 뽑는다.
- **`fp`에 `CY`와 빈 값이 온다.** `ck_fs_period`는 `FY|Q1~Q4`뿐이라 그대로 넣으면 제약에 걸린다.
- **`form`에 `8-K`·`DEF 14A`가 섞인다.** 정기보고서가 아닌 팩트를 그대로 담으면 기간 계열이
  오염된다.
- **응답 크기가 종목마다 100배 차이 난다**(78KB ~ 7.9MB). 전량이 약 29GB라 **종목당 1콜로
  전량을 받는 것은 벌크 파일과 견줘 판단할 문제**이지 자명한 선택이 아니다.
- **자본총계는 비지배지분을 포함하는 태그를 먼저 본다.** 국내가 `자본총계`(K-IFRS 연결)를
  쓰므로 같은 것을 골라야 시장 간 계산 방식이 안 갈린다(spec §7). 순서를 뒤집으면
  **`자산 = 부채 + 자본`이 깨지는데**(실측 151행 중 50행) 차이가 비지배지분이라 값이 그럴듯하다.
- **오류 본문이 XML이다.** 상태코드를 먼저 본다. `toEntity(JsonNode)`로 받으면 **상태코드를
  보기도 전에 파싱이 터져** 404가 `TRANSIENT`가 된다 — 재시도해도 영원히 안 되는 것을
  재시도 대상으로 기록하게 된다.
- **스로틀·차단 시의 코드를 모른다.** 그래서 **403을 `AUTH`로 접지 않는다** — `AUTH`는
  `fatal()`이라 스로틀 한 번이 배치 전체를 죽인다. 모르는 것은 **재시도 쪽**으로 접는다.

---

## 이 소스가 답하지 못하는 것

**「없다」와 「아직 안 물었다」를 가르기 위해 적는다.**

- **티커.** CIK로만 조회된다. 매핑 파일이 따로 있고 **우리 8,504 중 1,815가 거기 없다** —
  대부분 ETF·펀드(1,332)와 우선주·워런트·유닛(483)이다. `cusip`은 시세 소스가 안 준다(ADR-0086).
- **XBRL 이전 기간.** 2009년 대형 신속제출자 의무화, 2011년 전체 확대. 그 이전 공시는
  이 API로 조회할 수 없다.
- **IFRS로 보고하는 발행사 — 모집단을 전량으로 셌다(2026-08-29 · 6,689콜).**
  CIK에 붙는 **6,689 중 IFRS를 내는 발행사가 597**, 그중 **`us-gaap`이 아예 없는 「전용」이 494**,
  둘 다 내는 병행이 103이다. 세는 법은 `SecEdgarIfrsAndBulkProbeLiveTest`가 정본이다 —
  `companyconcept`가 **종목당 약 1.2KB**라 `companyfacts`(3.4MB)로 세는 것보다 3,000배 싸다.
  - **MUST NOT: VTEX를 「IFRS 전용」의 사례로 들지 않는다.** 그 종목의 네임스페이스는
    **`[ifrs-full, us-gaap]` 둘 다**이고(실측), `us-gaap`이 623바이트로 얇을 뿐이다.
    적재에서 **176행 중 1행**만 나온 것은 참이지만 원인은 전용이 아니라 **얇음**이다 —
    그래서 이 결손이 걸리는 술어가 둘이고, **얇은 발행사가 몇인지는 안 쟀다**(위 census는
    `Assets` 존재만 봤다).
  - **`frames`는 `ifrs-full`을 안 준다**(404, 대조군 `us-gaap`은 200 · 6,259개 발행사).
    횡단면 한 콜로 세려던 길이 그래서 막혔고, 대신 종목별 `companyconcept`로 셌다.
- **기업행위.** 분할·배당을 주지 않는다. 미국 `adj_factor`가 전 구간 1인 것과 별개 축이다(ADR-0088).
- **업종.** `companyfacts`에는 없다. `submissions`가 주고 **그 축은 열렸다** —
  계약은 [`sec-edgar-company-profile.md`](./sec-edgar-company-profile.md), 결정은 ADR-0092다.
  담는 자리는 `stock.sic_code`이며 **`industry_code`는 여전히 KSIC 전용**이다
  (SIC 4자리가 그 CHECK를 그대로 통과하므로 금지는 살아 있다 — ADR-0086).
- **XOM이 78KB·94개념으로 다른 대형주의 1/50인 이유.** 매핑은 맞다 — 응답의 `entityName`이
  `Exxon Mobil Corporation`이다. 즉 **CIK를 잘못 붙인 것이 아니라 소스 쪽이 희소하다.**
  왜 희소한지는 **재지 않았다.** 크기만으로 결손을 단정하지 않는다.
  **커버리지 표의 분모 19에 이 종목이 들어 있다** — 15/19 같은 수의 일부는 여기서 나온다.
