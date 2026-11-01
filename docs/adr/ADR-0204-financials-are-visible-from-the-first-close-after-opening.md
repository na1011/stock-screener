---
id: ADR-0204
status: 채택됨
date: 2026-11-01
applies_to:
  - "src/main/java/com/stockscreener/indicator/calculator/ReportingBasis.java"
  - "src/main/java/com/stockscreener/indicator/IndicatorDataReader.java"
  - "src/main/java/com/stockscreener/indicator/FinancialFigures.java"
  - "src/main/java/com/stockscreener/indicator/GovernanceValue.java"
  - "src/test/resources/db/testmigration/V70_12__opened_at.sql"
---

# ADR-0204. 재무 · 거버넌스는 공개 시각 뒤 처음 마감하는 봉부터 보인다

## 결정

관문 켬(그 시장의 `stockscreener.gate.us` · `gate.kr`)에서 재무 판 · 거버넌스 사실은 **그 공시의 공개 시각 뒤 처음 마감하는 세션의 봉부터**
보인다(`ReportingBasis.visibleFrom`). 시각을 모르면 저장 공시일 다음 날부터다 — 봉은 세션에만 있어 다음 거래일이 되고, 미래를 보지 않으며 늦는
쪽만 남는다. 두 시장이 한 기준이다(사용자 결정 「두 시장은 한 기준」 2026-10-30).

- **보이는 날은 저장하지 않는다** — 판독기가 원자료(공개 시각)와 세션 달력으로 매번 짓는다(V70_12: `financial_statement.accepted_at` · 표
  `kr_filing_time`). 마감: 미국은 NYSE `market_calendar.core_end`(조기 폐장 13:00), 그날이 달력에 없으면 가장 이른 마감 13:00 · 국내는 15:30
  (2016-08-01 앞 15:00). 국내는 공시 목록 접수일(`KR_PUBLISHED`)보다 이르지 않다. 결과는 저장 공시일보다 이르지 않다.
- **보이는 날과 앵커를 가른다** — `FinancialFigures.visibleOn()` · `GovernanceValue.visibleOn()`은 보이기 소비자(구간 경계
  `financialPublicationDates` · `visibleAt` · `publishedIndexOn` · 주식수 가시성 · `GovernanceMath` · 매출총이익 전제)가, 저장 공시일
  `publishedAt`은 앵커 소비자(`sharesOf` 앵커 · `corporateActionMultiple` · 보통주 몫 스냅숏)가 읽는다. 한 필드에 실으면 장 마감 뒤 10-K ·
  다음 날 병합에서 보정이 꺼진다(AKTX 2026-03-30 17:40 ET 10-K · 03-31 1:40 병합 · TLIH 20-F 04-30 16:01 · 05-01 1:10).
- 미국 배당 사실은 도장(그 해 FY 판의 첫 공시일)이 가리키는 FY 판의 접수 시각으로 같은 규칙을 탄다 — 재무만 옮기면 배당성향 칸이 그 봉에 빈다
  (사실 6,694 중 3,640이 장 마감 뒤 FY 공시일).

## 규칙

- MUST: 규칙은 `ReportingBasis.visibleFrom` 한 곳이다 — SQL에 마감 시각 식을 다시 쓰지 않는다.
- MUST NOT: 실적 발표 **막기**(`effectiveOn` · 저장 칸 `earnings_release.effective_on`)를 이 규칙으로 바꾸지 않는다 — 막기는 일찍 막는 쪽이,
  보이기는 늦게 보이는 쪽이 보수라 「모를 때」의 방향이 반대다.
- MUST NOT: 「달력에 없는 날」을 휴장으로 접지 않는다 — 마감을 모르는 것이라 가장 이른 마감으로 본다(달력은 평일 21:00 미국 사슬이 채운다).
- MUST: 판 묶음의 공개 시각은 그 판에 값을 낸 공시들의 최댓값이고 **하나라도 모르면 모른다**(null) — 아는 쪽 시각을 쓰면 모르는 공시의 값을 미리 본다.
- MUST: 이른 판 사본(`KrEarlierVintages`)은 공개 시각을 옮기지 않는다 — 그 공시의 것이다.
- 꺼진 상태의 출력은 이 결정 전과 바이트까지 같다.

## 버린 대안

| 대안 | 근거 |
|---|---|
| 공시일(`published_at`) 자체를 접수 기준으로 옮긴다 | 주식수 앵커 소비자(`JdbcStoredShareCounts`의 `ca.effective_date > published_at` · `sharesOf`)가 장 마감 뒤 공시 · 다음 날 분할에서 보정을 끈다 |
| 미국만 접수 시각 · 국내는 접수일 | 보이기에서 접수일은 미래를 보는 쪽이다(국내 15:30 ~ 18:00 접수) — 「두 시장은 한 기준」 · 「시각을 모르면 접수일」은 ADR-0195의 막기 근거를 보이기로 옮긴 것(반증 밖 렌즈) |
| 보이는 날을 쓸 때 지어 저장 | 쓰는 순간의 달력에 얼어 낡는다(달력이 08-28에 멈췄던 사례) — 원자료만 저장 |
| 「국내는 원장이 시각을 안 준다」 | OpenAPI(`list.json`)와 공시 뷰어만 물은 판정 — DART 웹 「오늘의 공시」(`dsac001`)가 지난 날짜도 공시마다 시각과 접수번호를 준다(2025-11-14 3,095건) |

## 검증

- `./gradlew test --tests '*ReportingBasisTest*' --tests '*DiscontinuedGateTest*'`.

## 결과

**판독기 · 규칙(2026-11-01)**: 관문 켬 판독기가 보이는 날을 짓는다 · 관문 꺼짐 출력 그대로. 실측: 미국 정기 공시(SEC 데이터셋 2009~ · 편입 CIK
4,477) 187,253 중 동부 16:00 이후 · 접수일 = 제출일 99,607 — 운영 미국 판 216,441 중 그 공시의 판 122,337(57% · 4,877종목)이 지금은 공시일 봉에서
공개 전 재무를 읽는다.

**남은 것(재고 「공개 시각이 공시일 칸과 갈린다」 행 · 설계 v3)**: 원자료 채우기 — 미국 어댑터가 submissions `acceptanceDateTime`으로 판마다
`accepted_at`(묶음 최댓값 · 모르면 null · 유도 4분기) · 쓰기기 관문 칸 · 국내 DART 웹 시각 수집기(새 외부 연동) · 국내 기저 대체(`FlowSupersession`)
날짜 · 국내 실적 발표 · premise 펜스 ADR-0081 · 0085 · 0162. 원자료가 0행인 동안 관문 켬은 모든 판이 공시일 다음 날부터 보인다 — 관문은 원자료와
커버리지 단언 뒤에만 연다.
