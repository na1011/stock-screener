# DART — 공시검색 (그날 접수된 정기보고서 목록)

`status` 판정은 `DartResponse.kindOf`가 정본이다. 어댑터는 `pipeline/datasource/kr/DartFilingListClient`(받기·해석) ·
`DartFilingListSource`(닫는 규칙·거름) · `DartReportKey`(탐침 키), 포트는 `FilingListSource`, 적재는 `FilingListingJob`이고
설계 결정은 ADR-0176이다. 일일 총량은 경로 `/list.json`으로 센다(ADR-0176).

---

## 공시검색 — `list.json`

- 요청: `crtfc_key` · `bgn_de`·`end_de`(`yyyymmdd` · 같은 날) · `pblntf_detail_ty` · `corp_cls` · `page_no` · `page_count`(1~100).
  `corp_code`를 안 주면 검색 기간이 3개월로 묶인다(하루만 묻는다).
- 우리가 묻는 조합: 상세유형 **A001 사업 · A002 반기 · A003 분기** × 법인구분 **Y 유가 · K 코스닥** = 하루 6조합 + 쪽수.
- 응답: `status` · `message` · `page_no` · `page_count` · `total_count` · `total_page` · `list[]`(`corp_code` `corp_name` `stock_code`
  `corp_cls` `report_nm` `rcept_no` `flr_nm` `rcept_dt` `rm`). **상세유형은 응답에 없다** — 물은 값을 우리가 붙인다.
- **정정과 첨부추가도 그 상세유형으로 걸리고 각자 접수번호를 갖는다**(2026-10-12 실측: `[첨부추가]반기보고서 (2026.06)` ·
  `[기재정정]반기보고서 (2026.06)`). 그래서 보고서 종류를 이름 문자열로 가르지 않는다.
- **공휴일은 `013`이다**(10-09 한글날 실측: `{"status":"013","message":"조회된 데이타가 없습니다."}`) — 그날 그 조합에 공시가 없다는 답이다.
- 반기 마감일(08-14) A002 하루가 666건 · 7쪽이었다(`total_count` 666 · `page_count` 100 · 그때 준 법인구분 인자는 재료에 안 남았다).

## 탐침 키 — 보고서 이름 → (사업연도, 보고서 코드)

재무 API(`fnlttSinglAcnt` · `fnlttSinglAcntAll`)의 `bsns_year`·`reprt_code`다. 2026-10-12에 세 회사 다섯 사례를 재무 API 응답과 맞췄다
(001720 3월 결산의 사업·분기 · 009310·077970 반기 · 2027/11013은 `013` 대조).

- 사업연도 = 이름 「(yyyy.mm)」의 연도 — 3월 결산의 「사업보고서 (2026.03)」은 2026/11011이다.
- A001 11011 · A002 11012 · A003은 (이름의 월 − 결산월) mod 12가 3이면 11013, 9면 11014(결산월은 `stock.accounting_month`).
- 응답 `rcept_no`는 그 보고서의 가장 늦은 판이다 — 반영 판정은 「응답 `rcept_no` ≥ 목록 `rcept_no`」(n=2 · 077970 첨부추가 그대로 ·
  009310은 뒤 10-08 정정본).

### 함정

- **MUST: `total_count`와 받은 줄 수를 맞춘다** — 쪽을 넘기는 사이 공시가 접수되면 줄이 밀려 빠지거나 겹친다. 어긋나면 그날을 닫지 않고
  다음 실행이 다시 받는다(`DartFilingListClient` · 받기 실패 TRANSIENT).
- **MUST NOT: 오늘을 닫지 않는다** — 오후에도 접수된다. 오늘 받은 줄은 장부에 넣고 커서는 어제까지만 넘긴다.
- 접수일(`rcept_dt`)이 물은 날이 아닌 줄은 본문 이상(MALFORMED)이다 — 다른 날 공시를 그날로 닫지 않는다(실측에서 어긋난 줄은 없었다).
