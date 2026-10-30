---
id: ADR-0195
status: 채택됨
date: 2026-10-30
applies_to:
  - "src/main/java/**/stock/FinancialBasisReader*"
  - "src/main/java/**/indicator/calculator/ReportingBasis*"
  - "src/main/java/**/batch/EarningsReleaseJob*"
  - "src/main/java/**/collector/EarningsReleaseStore*"
  - "src/main/java/**/datasource/us/SecCurrentReportClient*"
  - "src/main/java/**/datasource/kr/DartEarningsReleaseNames*"
  - "src/main/resources/db/migration/V69_6__earnings_release.sql"
  - "frontend/src/lib/financialBasis*"
  - "frontend/src/components/result/NewReleaseBadge*"
---

# ADR-0195. 지표가 쓴 재무 기준과 그보다 새로운 실적 발표를 화면에 싣는다

## 결정

두 시장 모두 지표는 **정식공시 기준**(미국 10-Q·10-K · 국내 분기·반기·사업보고서)을 그대로 둔다. 다만 지표가 쓴 재무 기(期)보다 새로운
**실적 발표**가 있으면 화면이 그것을 말한다.
- 결과 표·카드: 「새 실적」 표시.
- 종목 상세: 「재무 기준 2026 Q2」 줄과 발표 안내.
- 워치리스트 알림: 같은 문구.

발표는 원장이 구조로 주는 것만 쓴다.
- 미국: 8-K·8-K/A 중 submissions 항목에 `2.02`가 있는 것.
- 국내: DART I001·I002 중 잠정실적·손익구조 변동 제목.

사용자 결정(2026-10-30)은 이 문장이다: *"두 유니버스가 다른 기준을 가지면 안 돼. 미국을 권고안대로 구현한다면 국내도 당연히 (b)로 선택해야 하는 거 아니야?"*
- 「(b)」는 국내 잠정실적을 지표에 넣지 않고 정식공시 기준을 유지하며 감지·표시만 하는 선택지다.
- 「이 기준이 국내 잠정치 도입을 막는다」는 그 문장에서 나온 내 해석이다.

## 규칙

- **MUST: 기준 기는 지표 계산의 판정을 그대로 쓴다.**
  - 유량 기는 `ReportingBasis` → `FinancialMath.seriesOf`로 정한다. 연속 네 분기가 서면 최신 분기, 아니면 최신 FY다.
  - 잔액 기는 `latestPoint`다.
  - 행은 지표 리더와 같은 술어(공시일 · 기말 · 통화 관문)로 읽는다(`IndicatorDataReader.readKnownFigures`).
  - 그 기준일 지표를 쓴 정기 실행(일일 갱신 · 화해 ①)이 **시작한 뒤** 생긴 재무 행은 뺀다.
  - **MUST NOT: 「`period_end` 최대」로 따로 유도하지 않는다.** 반증이 잰 결과: 그러면 10-29 미국 29종목 · 05-20 국내 28종목에서 라벨과 PER의 기가 갈린다.
- **MUST: 발표 대기는 「유량 기준 기의 다음 기말 뒤 · 기준일까지」의 발표다.**
  - 다음 기말 = 기말 + 3개월이다. 월말 기말은 월말로 맞춘다.
  - 정식 공시 뒤에 나온 같은 분기 보도자료, 분기 중 2.02는 기준 기의 것이라 안 센다.
- **MUST: 모르는 것은 「없음」으로 말하지 않는다** — `UNTRACKED`와 그 사유를 낸다.
  - 외국 발행사(기준 기 양식이 20-F·40-F·6-K뿐)
  - 국내 비12월 결산(저장 기말이 합성 규약)
  - 항목을 아직 못 받은 8-K
  - 훑지 않은 종목
  - 다음 기말이 감지 시작일(2025-07-01) 앞
  - 기준일까지 공시 목록이 안 닫힘
- **MUST: 실적 발표 줄은 공시 목록의 그날과 한 트랜잭션이다**(`FilingLedgerStore.recordDay`). 닫힌 날에 발표가 빠지면 그날 「없음」이 거짓이 된다.
- **MUST: 수집은 종목 단위로 실패를 격리한다.** 접수번호가 아직 submissions에 없는 8-K는 그 줄만 모르는 채 남는다. 시장 커서는 멈추지 않는다.
- **MUST NOT: 값을 바꾸는 표를 이 기능이 읽거나 쓰지 않는다.**
  - 지표 · 프리셋 · 워치리스트 판정은 이 기능과 무관하다.
  - 화면 표시만 이 기능이 정한다.
- **MUST NOT: `SecFilingClient`에 항목 열을 더하지 않는다.** 그 파일은 `ReaderVersionPinTest.SOURCE_SCOPE`라 고치면 판을 올리고 보충 행을 다시 받는다. `SecCurrentReportClient`가 받기 함수만 부른다.

## 검증

- 시험:
  - `FinancialBasisReaderTest`(대기 · 닫힘 · 늦은 보도자료 · 항목 모름 · 미훑음 · 목록 미닫힘 · 외국 발행사 · 비12월 · 지표와 같은 유량 기 · 실행 뒤 생긴 행 · 통화 관문 · 다음 기말)
  - `EarningsReleaseJobTest` · `DartEarningsReleaseNamesTest` · `SecCurrentReportClientTest`
  - `SecFilingListSourceTest`·`DartFilingListSourceTest`의 실적 발표 줄 · `PipelineSchedulerChainTest`의 실적 발표 감지 넷
  - `WireContractTest` · `frontend/src/lib/financialBasis.spec.ts` · `StockHeader.spec.ts`
- 대조군: 「실행 뒤 생긴 행을 뺀다」를 끄면 그 시험이 붉었다(`2031 Q2` 대신 `2031 Q3`). 통화 관문을 끄면 그 시험이 붉었다.

```premise 실적 발표 감지 범위 종목이 전부 훑였다
psql -d stock_screener -At -c "WITH scope AS (SELECT s.id, s.market_code, s.sec_cik FROM stock s WHERE EXISTS (SELECT 1 FROM universe_membership rm JOIN universe ru ON ru.code = rm.universe_code AND ru.is_active WHERE rm.stock_id = s.id AND (rm.effective_to IS NULL OR rm.effective_to >= DATE '2025-07-01')) AND (s.market_code <> 'US' OR s.sec_cik IS NOT NULL)), left_ AS (SELECT * FROM scope WHERE NOT EXISTS (SELECT 1 FROM earnings_release_sweep w WHERE w.stock_id = scope.id)) SELECT CASE WHEN (SELECT count(*) FROM scope) = 0 THEN '잴 것이 없었다 — 감지 범위 종목이 0이다' WHEN (SELECT count(*) FROM left_) = 0 THEN 'OK 감지 범위 ' || (SELECT count(*) FROM scope) || '종목 전부 훑음' ELSE (SELECT count(*) FROM left_) || '종목이 훑기 전이다(미국 ' || (SELECT count(*) FILTER (WHERE market_code = 'US') FROM left_) || ') — 그 종목은 화면에서 판정 불가로 보인다. 정기 상한이 시장별 50이라 첫 백필이면 earnings-release --sweep-limit=2000을 손으로 돈다' END"
```

## 배경

2026-10-30에 GOOGL이 실적 발표(10-28 ET 16:01) 뒤 하락한 종가에 Q2 EPS로 PER 14.21이 나왔다. 순서 결함은 ADR-0176 「13:30 앞 소비」가 닫았다.

남은 것은 **보도자료와 정식 공시 사이의 기간**이다. 원장 실측 결과는 이렇다.
- **미국:** 80종목 1년치에서 간격 중앙값 1일 · 3일 이상 30% · p90 15일 · 최대 41일이다.
  - 그 순간 15/80이 이 상태였고, companyfacts에 8-K 재무가 0건이었다. 지표 값을 만들 원자료가 없는 진짜 한계다.
- **국내:** 편입 350 중 97종목이 10월 잠정실적을 냈는데 지표는 Q2였다.
  - 잠정실적은 손익 다섯 줄뿐이다. 넣으면 한 지표 안에서 잠정치와 Q2 재무상태표가 섞이고, 미국과 기준이 갈린다.

드라이런(0콜 재료 + 운영 SELECT) 결과는 이렇다.
- 미국 표본 120 · 1년: 대기 구간 312개가 **전부 기준이 다음 기로 넘어가며 닫혔다.**

## 버린 대안

- **「가격 날짜 ≠ 재무 날짜」 플래그**: 재무 기말은 늘 가격 날짜보다 앞이라 항상 참이다. 표시가 뜻을 못 갖는다.
- **국내 잠정실적을 지표에 넣기**: 위 사용자 결정으로 기각했다(두 시장 한 기준).
- **미국 보도자료(Ex-99.1) 수치 파싱**: 표가 회사마다 달라 종목별 로직이 필요하다(2026-10-25 사용자 기준 「종목 특정 로직이면 미표시」).
- **별도 커서 · 별도 일일 색인 받기**: 공시 목록이 같은 `form.*.idx`를 이미 받는다. 8-K 줄을 같은 하루 받기에 실었다(커서 하나 · 받기 한 번).
- **기준 기를 지표로 저장하기**: 조건 빌더 카탈로그에 내부 지표가 노출된다. 읽는 시점에 지표와 같은 판정을 다시 쓰고 「실행 뒤 생긴 행」을 빼는 쪽이 소비자가 적다.
- **결과 행에 「모름」 표시**: 외국 발행사처럼 상시인 상태가 표를 덮는다. 결과 행은 대기만, 상세는 셋 다 그린다.

## 결과

- 결과 표에 `financialBasis`가 싣히고, 2,000행에서 재무 한 질의 + 발표 한 질의다.
  - 통화 관문을 집합 꼴로 걸어 1,000종목에 0.11초다(상관 꼴 4.85초 · 운영 실측).
- 새 표 둘은 `StockFold` 규칙에 들어갔다(`uncoveredChildTables`).
- 되돌리려면 V69_6 두 표, `FilingListSource`의 실적 발표 줄, 응답의 `financialBasis`, 프론트 배지·상세 줄을 함께 걷는다.
- 지표 값은 안 바뀌므로 관문과 무관하다.
- 안 닫은 것(재지 않음):
  - 외국 발행사 21종목의 6-K 실적 발표는 감지하지 못한다(진짜 한계 · 「감지 대상 아님」).
  - 국내 정정 사업보고서가 원 공시일을 지워 지표가 몇 달 옛 기로 도는 결함은 이 기능이 「새 실적 · 미반영」으로 드러낼 뿐 고치지 않는다(`docs/progress.md`).
