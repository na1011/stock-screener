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
  단독을 함께 싣기 때문이다. **회계연도는 앵커에서, 기간 구분은 길이에서** 가져와야 한다.
- **같은 기간이 이후 보고서마다 다시 실리고 `filed`는 매번 늦다.** 값 비교 없이 공시일만 보면
  **112개 중 107개가 가짜 정정**이 되고, 정정 판정은 그 종목의 지표를 **보존 창 전체에서**
  지운다(`FinancialWriter`의 `FINANCIAL_REVISED`). **그 접기는 어댑터만 한다** — 저장 계층은
  공시일만 보고 값을 비교하지 않는다.
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
- **IFRS로만 보고하는 발행사.** VTEX는 `ifrs-full` 네임스페이스이고 `dei`가 **0개**다.
  적재해 보니 **176행 중 1행**만 나왔다 — 형식은 통과하지만 내용이 없다.
  **모집단에서 몇인지는 안 쟀다.**
- **기업행위.** 분할·배당을 주지 않는다. 미국 `adj_factor`가 전 구간 1인 것과 별개 축이다(ADR-0088).
- **업종.** `submissions` 엔드포인트에 SIC가 있으나 **이 저장소는 저장하지 않는다** —
  `stock.industry_code`가 KSIC 전용이고 SIC 4자리가 그 CHECK를 그대로 통과한다(ADR-0086).
- **XOM 표본이 78KB·94개념으로 다른 대형주의 1/50이다.** 그 티커의 CIK가 무엇을 가리키는지
  **확인하지 않았다.** 크기만으로 결손을 단정하지 않는다 — 재려면 그 CIK의 `submissions`를 본다.
