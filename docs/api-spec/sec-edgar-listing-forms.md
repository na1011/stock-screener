# SEC EDGAR — 거래소 양식 (상장 제거 · 등록 · 표지 거래소)

이 문서는 과거 날짜 명부의 「그날 거래소 범주」와 폐지 실체의 등록인 찾기에 쓰는 SEC 원자료를 적는다(탐색 `US-PAST-LISTING-PLAN-1017.md` D5·D6 · 구현 계획 `US-PAST-LISTING-IMPL-1018.md` U1).

- 받기: `pipeline/datasource/us/SecListingFormClient`
- 해석: `pipeline/datasource/us/listing/`의 `ListingFormReader` · `Venue` · `ClassLines` · 표지는 `ClassFactReader.readListingCover`

창의 양식 줄은 일일 색인(`sec-edgar-daily-index.md`)으로 모은다. 리미터와 User-Agent는 SEC 전체와 같다.

---

## 셋 양식 — 무엇이 거래소를 말하나

| 양식 | 누가 내나 | 거래소 | 증권 종류 | 날짜 |
|---|---|---|---|---|
| `25-NSE` | 거래소 | XML `exchange/entityName`(「NEW YORK STOCK EXCHANGE LLC」 · 「NYSE AMERICAN LLC」 · 「Nasdaq Stock Market LLC」) | `descriptionClassSecurity` | 색인 제출일 |
| 발행사 `25` | 발행사 | HTML — 「(Exact name of Issuer … name of Exchange …)」 라벨 바로 앞, 발행사 이름에 붙어 온다(「Centrus Energy Corp. NYSE American LLC (Exact name …」) | 「(Address … principal executive offices)」와 「(Description of class …」 사이 | 제출일 + 10일 효력(탐색 ★12 TWOD) |
| `8-A12B` | 발행사 | HTML 「Title of each class … Name of each exchange …」 표 — 종류마다 거래소 이름이 붙는다 | 같은 표 | 제출일 |

- **한 글에 여러 종류가 든다.** LEU 발행사 `25`(2025-12-11)는 「Class A Common Stock - par value $0.10 per share Rights to purchase Series A … Preferred Stock, par value $1.00 per share」 한 글이다. ABL(2025-12-29)은 보통주와 사채를 한 글에 싣는다. 그래서 `;` · 줄바꿈 · 「per share」 뒤에서 줄로 가른다(`ClassLines.split`).
- **같은 등록인의 등록이 보통주가 아닐 수 있다.** BMNR `8-A12B`의 2026-04-08은 보통주 · NYSE이고, 2026-06-10은 9.5% Series A 우선주 · NYSE다.
- **상향 이전은 짝으로 온다** — 새 거래소 `8-A12B`가 먼저이고, 옛 거래소에서 빼는 발행사 `25`가 뒤다.
  - LEU: 8-A12B NYSE 2025-12-03 → 발행사 25 NYSE American 2025-12-11
  - BMNR: 8-A12B NYSE 2026-04-08 → 발행사 25 NYSE American 2026-04-16
  - 짝의 날짜 창과 전이일의 정확도는 재지 않았다(R-D5).
- 분기마다 시장 전체 수(2025 QTR3 ~ 2026 QTR3, 분기 전체 색인에서 셈):
  - `25-NSE` 406~548
  - 발행사 `25` 24~40
  - `8-A12B` 458~529

## R-D5에서 잰 꼴 (2026-10-18 · 묶인 등록인 4,458 · 창 2025-08-26~2026-10-17 · 양식 654 → 654 읽음)

- **`8-A12B` 표는 꼴이 여럿이다** — 머리 「Name of each exchange …」 · 「Name of exchange …」 · 「Name of the Exchange …」. 값이 머리 뒤에도 앞에도(괄호째 「(Title of each class …)」) 오고, 종류가 두 머리 사이에 끼기도 한다(Liberty Energy). 구역 시작은 「Section 12(b) of the Act」 또는 「… of the Exchange Act」이고, 끝은 「If this form (8-A) relates」 · 「Securities to be registered pursuant to Section 12(g)」 · 「Item 1」 중 먼저 오는 것이다. 그래서 머리 문구에 기대지 않는다: 구역을 잘라 머리 문구를 걷은 뒤 거래소 이름으로 가른다. 1판 판독기는 38건(8-A12B 35)을 못 읽었다.
- 거래소 이름에 밑줄 칸(「New York Stock Exchange ____」)이 붙고, 「New York Stock Exchange American」 꼴도 있다.
- **이전일은 `8-A12B` 제출일이 아니다** — 발행사 `25` 설명문이 이전일을 적은 상향 둘에서 등록 제출일보다 늦었다: LEU 12-03 → 12-04, UAMY 03-06 → 03-11. 설명문이 없으면 원장이 주는 것은 「등록 제출일 ≤ 이전일 < 옛 거래소 제거 효력일」뿐이다(BMNR 04-08~04-26 · SENS · PTN).
- **짝 없는 보통주 제거는 상장 끝이 아닐 수 있다** — 증권 교체 11건(FLG 은행 승계 · QGEN · CWK · GTES 본사 이전 · FWONA 재분류 · SOS · CANG · DEC 예탁증권 · BKKT · PR · CWEN)은 제거 뒤 다시 등록 없이 같은 실체가 상장돼 있었다.
- 짝 거리(효력일 사이): 이전(안 ↔ 안) 0~19일 · 상향(등록 ↔ 밖 거래소 제거) 10~20일 · 짝 없는 등록 중 120일 안에 제거가 붙는 것 0.
- 워런트 제거가 보통주 이전처럼 셀 수 있다(RFL 25-NSE 2025-12-11 「Warrants to Purchase Shares of Class B Common Stock」) — 줄 판정이 거른다.

## 표지 거래소 — `dei:SecurityExchangeName`

- 정기 공시 표지의 거래소 코드는 증권 줄(클래스 축 구성원)마다 있다: `NYSE` · `NASDAQ` · `NYSEAMER` · `NYSEArca` ….
- **SEC 티커 파일(`company_tickers_exchange`)과 다르다** — 그 파일은 NYSE American을 「NYSE」로 적는다(탐색 ★11).
- 40-F 표지에도 있다. ORLA 40-F(2026-03-20)는 `NYSE` · `ORLA` · 「Common Shares, no par value」다.
- **표지와 제거 양식이 갈리는 실체가 있다.** ORLA는 3월 40-F 표지가 `NYSE`인데 2026-07-31 `25-NSE`의 거래소는 NYSE American LLC다. 원장이 갈리는 실체로 센다.

## 받기에서 잰 것 (2026-10-18)

- **HTML 문서는 저장된 그대로가 아니다** — SEC 가장자리가 `</BODY>` 앞에 `<script type="text/javascript"  src="/…"></script>`를 하나 끼운다.
  - 고정물 HTML 8/8에서 받은 길이가 폴더 색인 `size`보다 112바이트 길었다. 그 태그를 빼면 8/8이 크기와 같다.
  - XML과 작은 HTML(`ruleprovisionnotice.htm`) 13/13은 그대로 같았다.
  - 그래서 그 태그를 뺀 길이로 끊긴 전송을 가른다(`SecListingFormClient.sizeMismatch`).
- 저장 HTML은 SGML 머리로 시작한다(`<DOCUMENT>` `<TYPE>25` …). 폴더 항목은 이름순이라, 주 문서는 이 `<TYPE>`으로 고른다.
- **분기 전체 색인(`/Archives/edgar/full-index/{yyyy}/QTR{n}/form.idx`)은 끊긴 전송을 못 가른다.**
  - 그 분기 목록(`index.json`)이 적은 크기가 받은 바이트와 다르다 — 2025 QTR4는 `41894 KB`인데 받은 것은 43,155,988바이트였다.
  - 제출일 꼴도 `yyyy-mm-dd`라 일일 색인과 다르다.
  - 그래서 창의 양식 줄은 크기가 맞는 일일 색인으로 모은다.
- 받기 부하: 503이 한 번 왔고(16콜 중) 다시 받기로 닫혔다.

## 재현

- 고정물: `src/test/resources/fixtures/sec-edgar/listing/`. 받은 그대로이고, 표지 인스턴스 둘만 dei 사실과 그 맥락을 잘랐다.
- 시험: `ListingFormReaderTest` · `SecListingFormClientTest`.
- 대조군: 「per share」 가르기를 빼면 셋만 붉다(LEU · ABL · 워런트 글).
