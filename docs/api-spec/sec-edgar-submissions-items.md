# SEC EDGAR — submissions의 8-K 항목(실적 발표)

어댑터는 `pipeline/datasource/us/SecCurrentReportClient`, 쓰는 잡은 `EarningsReleaseJob`, 설계 결정은 ADR-0195다. 받기·재시도·상태 판정은
`SecFilingClient.fetch`·`SecEdgarResponse`(같은 리미터 · User-Agent)를 그대로 쓴다.

---

## `GET data.sec.gov/submissions/CIK##########.json`

- **`filings.recent`는 열 배열이다** — `accessionNumber` `filingDate` `reportDate` `acceptanceDateTime` `act` `form` `fileNumber`
  `filmNumber` `items` `core_type` `size` `isXBRL` `isInlineXBRL` `isXBRLNumeric` `primaryDocument` `primaryDocDescription`
  (2026-10-30 GOOGL 실측). 열 길이가 다르면 본문 이상으로 던진다 — 어긋나면 남의 항목이 붙는다.
- **`items`가 8-K의 항목 목록이다** — GOOGL 실적 8-K는 `2.02,9.01`(2023-10 이후 분기마다 하나 · `acceptanceDateTime` 20:01Z = ET 16:01, 장
  마감 뒤). 10-Q 등 다른 폼은 빈 문자열이다. **실적 발표 = 8-K · 8-K/A 중 항목에 `2.02`(Results of Operations and Financial Condition)가 있는 것.**
- **일일 폼 색인(`form.yyyymmdd.idx`)에는 항목이 없다** — 그래서 공시 목록은 8-K 줄을 「항목 모름」으로 넣고 이 엔드포인트가 CIK마다 한 콜로 푼다.
- **recent는 「최근 1년 또는 1,000건」이다** — C · WFC · GS의 recent 첫 날이 2025-10-30이고(C는 13,169건) 그 앞은 `filings.files[]`
  페이지(`name` · `filingFrom` · `filingTo`)다. 과거 훑기는 감지 시작일(2025-07-01)까지 페이지를 따른다.
- 크기: GOOGL 152KB. 감지 범위(활성 유니버스 멤버십이 2025-07-01 뒤에 걸친 미국 종목 · CIK 1,203)의 하루 8-K는 40~179줄 · 40~170 CIK였다
  (2026-09-15 · 10-21 · 10-29 일일 색인).

### 함정
- **외국 발행사의 실적 발표는 6-K라 항목이 없다** — 미국 편입 1,000 중 21종목(2025-07 이후 재무 `report_code`가 20-F·40-F·6-K뿐 · 2026-10-30).
  6-K로 실적 발표를 판정하지 않는다(항목 구조가 없어 본문을 읽어야 하고 회사마다 다르다) — 화면은 「감지 대상 아님」이다.
- **8-K 재무 사실은 companyfacts에 없다** — 보도자료 수치로 지표를 만들 수 없다(2026-10-30: 보도자료 뒤 정식 공시 전인 15종목 전부 0건 · 대조군
  GOOGL 10-Q 값은 받음). 이 엔드포인트는 「발표가 있었다」만 준다.
- **17:30 ET 뒤 접수는 다음 날 `filingDate`다** — GOOGL 10-Q(10-28 20:29 ET)는 10-29. 8-K는 16:01 접수라 10-28이다.
