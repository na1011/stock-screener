# SEC EDGAR — 업종·결산월 (submissions)

미국 업종의 원자료. **발행사 메타데이터를 기업당 한 콜**로 준다.
업종 축의 결정은 ADR-0092에, 같은 소스의 재무 축은
[`sec-edgar-financial.md`](./sec-edgar-financial.md)에 있다.

- 어댑터는 `pipeline/datasource/us/SecEdgarCompanyProfileAdapter`, 포트는
  `CompanyProfileDataSource`, 적재는 `CompanyProfileJob`이다. 국내 대응은
  [`dart-company-profile.md`](./dart-company-profile.md).
- **HTTP 코드 → `Kind` 매핑의 정본은 `SecEdgarResponse`의 javadoc이다** — 아래 §실패 계약이
  재무 축과 **같다**는 것을 실측했으므로 그 판정을 그대로 쓴다.
- **티커 → CIK 매핑 계약의 정본은 `SecTickerCikCache`의 javadoc이다.**
- 인증·User-Agent·쿼터는 `sec-edgar-financial.md` §엔드포인트와 `api-sources` skill이 답한다.
- 탐침: `SecEdgarSubmissionsProbeLiveTest` — **아래 실측이 그 출력 그대로다.**

---

## 엔드포인트

```
GET https://data.sec.gov/submissions/CIK##########.json
```

CIK **10자리 zero-padding 필수**. 한 콜에 발행사 메타데이터와 공시 이력이 함께 온다 —
**우리가 쓰는 것은 앞의 넷뿐**이고 나머지는 버린다.

| 필드 | 쓰는가 | 무엇 |
|---|---|---|
| `sic` | **쓴다** → `stock.sic_code` | SIC 4자리 |
| `sicDescription` | 안 쓴다(관측만) | 업종 이름 |
| `fiscalYearEnd` | **쓴다** → `stock.accounting_month` | `MMDD` |
| `ownerOrg` | **쓰지 않는다** | SEC 심사 사무국. **분류 체계가 아니다** — §함정 |
| `filings.recent` 등 | 안 쓴다 | 응답 부피의 대부분 |

---

## 실측 (2026-08-29)

`./gradlew liveTest --tests "*SecEdgarSubmissionsProbe*"`

표본 27종목 — 파일럿 + **금융업 후보 10** + 비금융 대조군 + **앞자리가 0인 SIC를 받기 위한
농업 발행사 4**. 26이 CIK에 붙었다(`FDP`가 매핑 파일에 없다).

### SIC의 모양 — 이 축의 스키마를 정한 값

| 무엇 | 실측 |
|---|---|
| 자릿수 | **4자리 하나뿐** (26/26) |
| **앞자리가 0인 코드** | **0으로 채워서 온다** — `LMNR`·`ALCO` = `0100`, `CALM` = `0200` |
| 빈 `sic` | **0 / 26** |
| `sicDescription` | 26/26 |
| `fiscalYearEnd` | 26/26. `0926`(AAPL) · `0630`(MSFT) · `0131`(NVDA·RH·WMT) · `0530`(CALM) · `0901`(ALCO) |

**앞자리 0을 일부러 재러 갔다는 것이 요점이다.** 첫 표본 23종목의 최소 SIC가 `2080`이라
**0으로 시작하는 코드가 하나도 없었고**, 그 표본으로 「항상 4자리」라 적으면 안 덮은 자리를
덮었다고 말하는 것이 된다. 농업 발행사를 넣고 다시 재서 답이 나왔다.

**그래서 어댑터는 zero-padding을 하지 않는다.** 소스가 이미 채워서 준다.
`ck_stock_sic_code`가 `'^[0-9]{4}$'`인 근거도 이 줄이다.

### 앞 2자리 분포 (표본 26)

`01`2 · `02`1 · `20`1 · `28`1 · `29`1 · `35`1 · `36`1 · `37`1 · `48`1 · `53`1 · `57`1 ·
**`60`2 · `62`3 · `63`3 · `67`2** · `73`4

**`01`·`02`가 실제로 온다.** KSIC 소분류에도 `011`(농업)이 있어 앞 2자리가 `01`이므로,
**체계 접두가 없으면 두 나라의 농업이 한 파티션에 들어간다.** 가정이 아니라 실측이다.

### 금융업 후보에 무엇이 들었는가

**제외 집합의 근거는 이 표다** — SEC 공식 SIC 목록 페이지가 **신원 안 밝힌 클라이언트에
403**이라(2026-08-29 실측) 정적 목록으로 범위를 확인할 수 없다.

| SIC | `sicDescription` | 표본 |
|---|---|---|
| `6021` | National Commercial Banks | JPM · BAC |
| `6200` | Security & Commodity Brokers, Dealers, Exchanges & Services | CME |
| `6211` | Security Brokers, Dealers & Flotation Companies | SCHW |
| `6282` | Investment Advice | BX |
| **`6331`** | **Fire, Marine & Casualty Insurance** | PGR · ALL · **BRK.A** |
| `6798` | Real Estate Investment Trusts | O · SPG |
| `7320` | Services-Consumer Credit Reporting … | SPGI — **금융처럼 보이지만 73이다** |

**표본이 26이라 이것으로 집합을 정하지 않는다.** 정하는 것은 전량 적재 뒤이고, 그때
**무엇이 들었는지를 종목명까지 눈으로 대조한다**(`phase-criteria.md` P25 ③).

### 응답 크기 — 종목당 1콜을 고른 근거

| 무엇 | 실측 |
|---|---|
| 합계 | **10.0MB / 26종목** (평균 약 385KB) |
| 한 종목 | **최소 6KB · 최대 4.6MB** — 약 **760배** 차이 |
| 전량 환산 | 6,689종목이 **약 2.6GB** · 초당 5회면 **약 22분** |
| `submissions.zip`(벌크) | **1.56GB** |
| DERA 재무제표 데이터셋 | **2026q2 0.06GB · 2026q1 0.09GB** |

**DERA가 가장 작다(약 1/30).** `sub.txt`에 발행사별 SIC가 있다. 그럼에도 종목당 1콜을 고른
근거는 크기가 아니다 — ADR-0092 §버린 대안이 답한다.

### 실패 계약 — 재무 축과 **같다**

| 요청 | 응답 |
|---|---|
| 모르는 CIK | **HTTP 404** + `<?xml …><Error><Code>NoSuchKey</Code>…` |

`companyfacts`와 같은 모양이라 `SecEdgarResponse`의 판정을 그대로 쓴다.
**스로틀·차단 응답은 재지 않았다** — 유발하면 IP가 일시 제한된다.

---

## 함정

- **`ownerOrg`를 분류 체계로 쓰면 안 된다.** SEC의 **심사 사무국**이고 SIC와 대응하지 않는다 —
  실측에서 `CME`(SIC `6200`, 증권거래소)가 **`09 Crypto Assets`**로 왔고, REIT 둘은
  `05 Real Estate & Construction`인데 SIC는 `67`이다. 이름이 그럴듯해서 업종으로 읽기 쉽다.
- **`BRK.A`가 `6331`(손해보험)이다.** 지주회사가 사업 분야로 분류돼 들어온다 — KSIC `64992`에
  금융지주와 사업지주가 섞여 **코드로 가를 수 없던 것**과 같은 모양이고, 이쪽은 보험 버킷에서
  일어난다. 금융업 제외를 SIC 앞 2자리로 정할 때 **무엇이 함께 잘리는지**가 여기서 갈린다.
- **`sicDescription`이 금융처럼 보여도 SIC가 금융이 아닌 것이 있다** — `SPGI`가
  `Services-Consumer Credit Reporting`인데 `7320`이다. **판정은 코드로 한다**(ADR-0044 §규칙).
- **응답의 대부분이 우리가 안 쓰는 공시 이력이다.** 필드 넷을 위해 평균 385KB, 최대 4.6MB를
  받는다. 종목당 1콜을 늘릴 때 이 비용이 선형으로 는다.
- **매핑 파일에 없는 티커가 실재한다** — 표본 27 중 `FDP`가 안 붙었다. 우리 8,504 중
  **1,815가 그 상태**이고(`sec-edgar-financial.md`) 대부분 ETF·펀드·우선주다.
  **그 종목은 업종을 원리적으로 못 받는다** — 예외가 아니라 `Optional.empty()`다.
- **오류 본문이 XML이다.** 상태코드를 먼저 본다.

---

## 이 소스가 답하지 못하는 것

**「없다」와 「아직 안 물었다」를 가르기 위해 적는다.**

- **SIC 체계의 공식 목록.** SEC의 목록 페이지가 **403**이다(위 실측). 우리가 아는 범위는
  **받은 값과 `sicDescription`**뿐이고, 그래서 분류의 경계를 단정하지 않는다.
- **업종 변경 이력.** 현재 값 하나만 온다. 발행사가 SIC를 바꾸면 과거 값을 되찾을 수 없다 —
  국내 업종도 같다(`PointInTimeScreener`의 *"업종 자체에는 이력이 없다"*).
- **CIK에 안 붙는 1,815종목의 업종.** 이 소스로는 원리적으로 못 받는다.
- **표본 26 밖의 자릿수·빈 값.** 전량 적재가 답한다.
