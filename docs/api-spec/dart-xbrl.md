# DART — 재무제표 원본파일(XBRL) · 종목별 정기 공시 목록 · 주식총수 이력

`status` 판정은 `DartResponse.kindOf`가 정본이다. 받기는 `pipeline/datasource/kr/xbrl/`이고 설계 결정은 ADR-0192다.
일일 총량은 경로마다 센다(`DartDailyQuota` — `/fnlttXbrl.xml` · `/list.json` · `/stockTotqySttus.json`이 따로다).

---

## 재무제표 원본파일 — `fnlttXbrl.xml`

- 요청: `crtfc_key` · `rcept_no`(14자리) · `reprt_code`(11013 · 11012 · 11014 · 11011).
- 성공: `Content-Type: application/x-msdownload` · 본문이 ZIP(`PK`로 시작) · 안에 인스턴스 `.xbrl` 하나와 스키마 · 링크베이스.
  - 2026-10-25 실측 크기: 2026 반기 0.46MB(기아 · 인스턴스 2.4MB) · 2014~2020 판 0.13~0.31MB.
- **판마다 자기 인스턴스다**(2026-10-25 · 고려아연 00102858 FY2024 세 판): 원 공시 20250320001542 · 정정 20250331003521 ·
  정정 20260813001711이 서로 다른 인스턴스를 준다(md5 셋 다름). 원 판의 `ifrs-full:ProfitLoss` 194,781,564,506 = 우리 revision 0 행
  `net_income`. 재무를 안 바꾼 정정(기아 2026 반기 20260814003932 · 20260916000427)은 같은 인스턴스다.
- **`reprt_code`는 고르지 않는다** — 반기 접수번호에 11011을 줘도 같은 ZIP이 온다(2026-10-25). 판은 접수번호가 정한다.
- 실패는 HTTP 200 · `application/xml` 본문이다:
  - 없는 접수번호 → `<status>013</status><message>접수번호 오류 : …</message>`.
  - 파일이 없음 → `<status>014</status><message>파일이 존재하지 않습니다.</message>`(형식은 맞는데 그 접수번호에 원본이 없을 때).
- **세 형식이 있다**(2026-10-25 · 기아 2015~2026 · 표본 20종목 2018~2026):
  - 2021-12 앞에 접수된 판 · 일부 그 뒤 정정: 파일 이름 `<corp_code>_<날짜>.xbrl` · 기본 이름공간(`xbrli:` 접두가 없다) · 주석 태그가 없다 —
    본표(EPS 포함)만. 분류체계는 판마다 다르다: 2019 분류체계(`ifrs-full` 2019-03-27 · `dart` 2019-10-01 · 기아 2020 사업)이거나,
    **2018년 앞 판은 IFRS 이름공간이 IASB 시절 URI `http://xbrl.iasb.org/taxonomy/2010-04-30/ifrs`(접두 `ifrs`)**다(삼성전자 FY2018
    20190401004781 · `dart` 2018-07-01 · 회사 분류 2011-04-30). 같은 IFRS 표준 개념이다.
  - 그 뒤: `entity<corp_code>_<기말>.xbrl` · `xbrli:` 접두 · 주석 태그(가중평균 주식수 · 우선주 EPS 등)가 온다.
  - 판독기는 이름공간 URI로 읽어 형식을 가리지 않는다.
- 맥락의 연결/별도는 `ifrs-full:ConsolidatedAndSeparateFinancialStatementsAxis`(`ConsolidatedMember` · `SeparateMember`)이고, 축이 없는
  사실도 있다(별도만 내는 회사 · 연결 본표의 일부).

## 종목별 정기 공시 목록 — `list.json`(corp_code)

- 요청: `corp_code` · `bgn_de` · `end_de` · `pblntf_ty=A`(정기공시) · `page_no` · `page_count`(1~100).
- `corp_code`를 주면 기간 제한이 없다 — 기아 2015-01-01~2026-10-25 한 번에 58건 · 1쪽(2026-10-25). 날짜별 목록(`dart-filing-list.md`)과
  같은 응답 꼴이고, 정정 · 첨부추가가 각자 접수번호로 온다.
- 보고서 이름 → (사업연도, 보고서 코드)는 `DartReportKey`가 정본이다(`dart-filing-list.md` 「탐침 키」).

## 주식총수 이력 — `stockTotqySttus.json`

- 필드 · 구분 라벨은 `dart-governance.md`가 정본이다. 여기는 이력 범위만 적는다.
- 2015 사업연도도 온다(기아 2015 · 2018 · 2020 사업보고서 · 2026-10-25) — 접수번호는 그 뒤 정정 판(20211210…)일 수 있다.
- **구분 라벨은 회사마다 자유 문구다**(운영 `governance_report_row` 2021~2025 · 2,252접수): 보통주 · 보통주식 · 의결권 있는 주식 ·
  의결권 있는 주식(보통주) · 우선주 · 종류주식 · 종류주 · 의결권 없는 주식 · 배당우선 전환주식(2종 종류주식) … — 합계 · 비고 행만
  모든 회사에 같다. 보통주 판별에 라벨을 쓰지 않는다(ADR-0192).
