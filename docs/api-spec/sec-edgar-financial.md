# SEC EDGAR — 재무 계정 (companyfacts)

미국 재무의 원자료. **XBRL로 제출된 전 기간의 팩트를 기업당 한 콜**로 준다.
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
여는 길은 **유도**(`GrossProfit − OperatingExpenses` 또는 `Revenues − CostsAndExpenses`)이고,
그것은 태그→컬럼 매핑이 아니라 **새 기제**다. **그 셋은 지금 수집조차 하지 않는다.**

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
