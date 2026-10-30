---
id: ADR-0195
status: 채택됨
date: 2026-10-30
applies_to:
  - "src/main/java/**/stock/FinancialBasisReader*"
  - "src/main/java/**/indicator/calculator/ReportingBasis*"
  - "src/main/java/**/indicator/calculator/FinancialMath*"
  - "src/main/java/**/indicator/calculator/ValuationHistory*"
  - "src/main/java/**/indicator/IndicatorContext*"
  - "src/main/java/**/batch/EarningsReleaseJob*"
  - "src/main/java/**/collector/EarningsReleaseStore*"
  - "src/main/java/**/collector/KrEarlierVintages*"
  - "src/main/java/**/datasource/us/SecCurrentReportClient*"
  - "src/main/java/**/datasource/kr/DartEarningsReleaseNames*"
  - "src/main/resources/db/migration/V69_6__earnings_release.sql"
  - "src/main/resources/db/migration/V69_7__earnings_release_effective_on.sql"
  - "frontend/src/lib/financialBasis*"
  - "frontend/src/components/result/NewReleaseBadge*"
---

# ADR-0195. 재무 기준보다 새로운 실적 발표가 공개된 봉은 재무 지표를 계산하지 않는다

## 결정

**규칙은 하나다**: 어떤 봉에서 그 종목의 재무 기준(지표 계산이 쓰는 기)보다 새로운 실적 발표가 이미 공개돼 있으면, 그 봉의 재무 지표를
계산하지 않는다(행이 안 생긴다 → 스크리닝은 「데이터 없음」). 정식 공시가 반영돼 기준이 넘어가면 다시 계산한다. 가격은 새 실적에 반응했는데
재무는 옛 분기라, 값을 내면 그 봉의 재무 조건이 전부 어긋난 판정을 낸다.

사용자 결정(문장 그대로):
- 2026-10-30: *"두 유니버스가 다른 기준을 가지면 안 돼. 미국을 권고안대로 구현한다면 국내도 당연히 (b)로 선택해야 하는 거 아니야?"* — 두
  시장 모두 지표 기준은 정식공시이고(국내 잠정실적을 지표에 넣지 않는다), 실적 발표는 감지해 표시한다.
- 2026-10-31: *"새 실적이 발표되었으나 web의 재무기준이 이전 분기 지표라면 모든 스크리닝 조건이 어긋나는 심각한 정합성 오류야 … 이 갭을
  원천적으로 해결하든, 이 갭을 1일 미만으로 줄이든, 아니면 새 실적과 재무기준이 다르다면 지표 계산을 skip하든 무언가 확실한 조치가
  필요해."* — 이 ADR은 세 선택지 중 「skip」을 골랐다(내 판단 · 이유는 §버린 대안).

화면은 같은 판정을 말한다 — 결과 행 「새 실적」 · 종목 상세 「재무 기준 2026 Q2」 줄과 안내 · 워치리스트 알림 줄.

## 규칙

- **MUST: 판정은 `ReportingBasis` 한 자리다.**
  - 기준 기: `ReportingBasis.of` → `FinancialMath.seriesOf`(유량은 연속 네 분기가 서면 최신 분기, 아니면 최신 FY · 잔액은 `latestPoint`).
  - 대기: `ReportingBasis.pendingRelease` — 기준 기의 다음 기말(+3개월 · 월말 정렬) 뒤 ~ 그 봉까지 공개된 실적 발표가 있다.
  - **MUST NOT: 「`period_end` 최대」로 따로 유도하지 않는다.** 분기 하나가 빠진 종목에서 라벨은 새 분기인데 PER은 옛 FY로 돈다
    (2026-10-30 반증 실측: 10-29 미국 29종목 · 05-20 국내 28종목).
- **MUST: 재무를 봉마다 읽는 루프는 `FinancialMath.financialsAt`을 거친다** — `byPublication` · `bySegment` · `ValuationHistory`.
  하나라도 `visibleAt`을 직접 부르면 그 계산기만 막힌 봉에 옛 분기 값을 낸다. 실적 발표 공개일은 구간 경계다
  (`IndicatorContext.financialPublicationDates`).
- **MUST: 러너가 실적 발표 공개일을 싣는다**(`IndicatorRunner.readRawData` → `IndicatorDataReader.readEarningsReleaseDates`). 빠뜨려도
  컴파일되고 값이 그럴듯하다 — `IndicatorRunnerTest`가 지킨다.
- **「공개」의 날짜(`earnings_release.effective_on`)**:
  - 미국: 8-K 접수 시각(`acceptanceDateTime`)이 동부 16:00 전이면 그 날, 뒤면 다음 날. RMD 8-K는 2026-10-29 16:05 ET라 10-29 종가는
    발표 전 가격이고 10-30 봉부터 막힌다. 시각을 모르는 줄은 접수일(보수적으로 하루 일찍 막는다).
  - 국내: 공시검색이 시각을 안 준다 — 접수일이다.
- **실적 발표**:
  - 미국: 8-K · 8-K/A 중 submissions 항목에 `2.02`가 있는 것. 항목을 모르는 줄은 실적 발표가 아니다(화면은 판정 불가).
  - 국내: DART I001 · I002 중 잠정실적 · 손익구조 변동 제목(`DartEarningsReleaseNames` — 자회사 실적 · 결산 일정 · 전망은 아니다).
- **MUST: 실적 발표 줄은 공시 목록의 그날과 한 트랜잭션이다**(`FilingLedgerStore.recordDay`). 닫힌 날에 발표가 빠지면 그날 막아야 할 봉이 안 막힌다.
- **MUST: 수집은 종목 단위로 실패를 격리한다** — 접수번호가 아직 submissions에 없는 8-K는 그 줄만 남고 시장 커서는 안 멈춘다.
- **국내 첫 공시일**(`KrEarlierVintages` · `kr-xbrl-collect` 안):
  - DART 재무 API가 보고서마다 가장 늦은 판만 줘 첫 저장 공시일이 정정일로 밀린다.
  - 재무 행이 기댄 판과 **차원 없는 XBRL 사실 전부가 같은** 더 이른 판이 있으면, 그중 가장 이른 판의 접수일로 같은 값의 판을 하나 넣는다.
  - **MUST NOT: 기존 행의 공시일을 당기지 않는다.** 다음 재무 적재가 소스의 정정일을 「더 늦은 판 = 정정」으로 읽어 지표를 지운다
    (`FinancialWriter.writeOne`).
  - **MUST NOT: 숫자가 하나라도 다르면 넣지 않는다** — 정정이 숫자를 바꾼 경우가 실재한다.
- **MUST NOT: `SecFilingClient`에 항목 · 시각 열을 더하지 않는다** — 그 파일은 `ReaderVersionPinTest.SOURCE_SCOPE`다.
  `SecCurrentReportClient`가 받기 함수만 부른다.
- **관문 밖이다(내 판단)**: CLAUDE.md의 「값을 바꾸는 계산기는 꺼진 설정 뒤」는 에이전트 결정이고, 막는 것은 반복 재적재다. 이 변경은 소스를 다시
  받지 않으며 매 평일 화해(ADR-0166)가 창 전체에 반영한다. 2026-10-31 사용자 지시 「확실한 조치」를 다음 관문까지 미루지 않는다.

## 검증

- 시험:
  - `IndicatorRunnerTest.withholdsFinancialIndicatorsWhileANewerReleaseIsPending` — 잠정 공개 ~ 분기보고서 전에 PER이 없고 앞뒤에 있다.
    대조군: 판정을 「안 막음」으로 바꾸면 붉었다.
  - `ReportingBasisTest`(RMD 16:05 ET → 다음 날 · 장 전 → 그 날 · 시각 없음 → 접수일 · 다음 기말).
  - `FinancialBasisReaderTest`(대기 · 닫힘 · 늦은 보도자료 · 항목 모름 · 미훑음 · 목록 미닫힘 · 외국 발행사 · 비12월 · 지표와 같은 유량 기 ·
    실행 뒤 생긴 행 · 실행 뒤 알게 된 발표 · 통화 관문). 대조군: 「실행 뒤 생긴 행」 · 통화 관문을 끄면 각각 붉었다.
  - `KrEarlierVintagesTest`(같은 숫자 → 원본 날 판 · 멱등 · 숫자 바뀜 → 안 넣음 · 재료 없음 → 안 넣음).
  - `EarningsReleaseJobTest` · `DartEarningsReleaseNamesTest` · `SecCurrentReportClientTest` · 목록 소스 시험의 실적 발표 줄 ·
    `PipelineSchedulerChainTest`의 실적 발표 감지 넷 · `WireContractTest` · 프론트 `financialBasis.spec.ts` · `StockHeader.spec.ts`.

```premise 실적 발표 감지 범위 종목이 전부 훑였다
psql -d stock_screener -At -c "WITH scope AS (SELECT s.id, s.market_code, s.sec_cik FROM stock s WHERE EXISTS (SELECT 1 FROM universe_membership rm JOIN universe ru ON ru.code = rm.universe_code AND ru.is_active WHERE rm.stock_id = s.id AND (rm.effective_to IS NULL OR rm.effective_to >= DATE '2025-07-01')) AND (s.market_code <> 'US' OR s.sec_cik IS NOT NULL)), left_ AS (SELECT * FROM scope WHERE NOT EXISTS (SELECT 1 FROM earnings_release_sweep w WHERE w.stock_id = scope.id)) SELECT CASE WHEN (SELECT count(*) FROM scope) = 0 THEN '잴 것이 없었다 — 감지 범위 종목이 0이다' WHEN (SELECT count(*) FROM left_) = 0 THEN 'OK 감지 범위 ' || (SELECT count(*) FROM scope) || '종목 전부 훑음' ELSE (SELECT count(*) FROM left_) || '종목이 훑기 전이다(미국 ' || (SELECT count(*) FILTER (WHERE market_code = 'US') FROM left_) || ') — 그 종목은 새 실적이 공개돼도 재무 지표가 안 막힌다. 정기 상한이 시장별 50이라 첫 백필이면 earnings-release --sweep-limit=2000을 손으로 돈다' END"
```

## 배경

2026-10-30에 GOOGL이 실적 발표(10-28 ET 16:01) 뒤 하락한 종가에 Q2 EPS로 PER 14.21이 나왔다. 순서 결함(13:30 앞 소비)은 ADR-0176이 닫았다.
남은 것은 **보도자료와 정식 공시 사이의 기간**이다.
- 미국: 80종목 1년치에서 간격 중앙값 1일 · 3일 이상 30% · p90 15일 · 최대 41일이다. 보도자료 수치는 companyfacts에 없다(8-K 사실 0건) — 진짜 한계다.
- 국내: 잠정실적은 손익 다섯 줄뿐이라 넣으면 한 지표 안에서 기준이 섞이고 미국과 기준이 갈린다.

처음 판(10-30)은 표시만 했다. 다음 날 RMD(8-K 10-29 16:05 ET · 10-Q 10-29 18:11 ET 접수 = filingDate 10-30)를 두고 사용자가 「표시가 아니라
확실한 조치」를 지시했고, 같은 날 화면 표시의 날짜 단위 비교가 장 마감 뒤 발표를 그날 봉의 미반영으로 잘못 말한 것도 드러났다(그래서 접수 시각).

국내 첫 공시일은 2026-10-31 실측이다.
- 국내 보고서 16,404건 중 1,680건(418종목)의 첫 저장 공시일이 원본 접수일보다 늦다(중앙값 47일).
- KB금융 FY2025는 원본 03-13 · 저장 06-19, LG전자는 원본 03-13 · 저장 07-06이다. 두 종목 모두 원본과 정정의 XBRL 인스턴스가 바이트까지 같았다.
- 원본 · 마지막 정정을 둘 다 받아 둔 17쌍 중 차원 없는 사실이 같은 것은 13쌍이었다.
- 고치지 않으면 이 규칙 아래서 그 기간(넉 달)의 재무 지표가 통째로 빈다.

## 버린 대안

- **표시만(10-30 판)**: 사용자가 기각했다 — 표시는 스크리닝 판정을 안 고친다.
- **갭을 1일 미만으로 줄이기**: 갭의 원인이 소스다. 미국은 회사가 10-Q를 늦게 내고(p90 15일) 보도자료 수치는 원장에 없다. 국내는 분기보고서가
  잠정 뒤 몇 주다. 우리 쪽 지연(13:30 앞 소비)은 ADR-0176이 이미 닫았다.
- **국내 잠정실적 · 미국 Ex-99.1로 값 만들기**: 국내는 위 사용자 결정(두 시장 한 기준)으로 기각. 미국 표는 회사마다 달라 종목별 로직이
  필요하다(2026-10-25 사용자 기준 「종목 특정 로직이면 미표시」).
- **읽는 쪽에서 가리기(스크리닝 SQL · 상세 · 워치리스트 각각)**: 지표를 읽는 자리가 여럿이라 하나만 빠져도 그 경로만 옛 값으로 판정한다.
  계산 한 자리에서 막으면 모든 소비자가 저장된 부재를 같이 읽는다(백분위 모집단도).
- **「가격 날짜 ≠ 재무 날짜」 플래그**: 늘 참이라 뜻이 없다.
- **국내 첫 판의 공시일을 그냥 원본 접수일로 당기기**: 정정이 숫자를 바꾼 경우(17쌍 중 4쌍) 그 값이 공개되기 전 날짜에 실린다.
- **기준 기를 지표로 저장하기**: 조건 빌더 카탈로그에 내부 지표가 노출된다.

## 결과

- 재무 지표는 실적 발표 대기 동안 「데이터 없음」이다. 근사 실측(2026-10-29 · 분기 기준 근사): 미국 159 · 국내 82종목이 그 상태였다 — 실적
  시즌에는 국내 편입의 약 4분의 1이 몇 주 재무 조건에서 빠진다. 이것이 의도다(옛 분기 값으로 거르는 것보다 낫다).
- 첫 반영(2026-10-31 손 실행): 미국 8-K 19,849줄의 접수 시각을 받았고(장 마감 뒤라 다음 날로 밀린 것 10,629), 국내 재료 13,369판을 받아
  원본 접수일 판 2,112개를 넣었다 — 첫 공시일이 원본보다 늦은 국내 보고서가 1,680건에서 334건(160종목 · 숫자가 바뀌었거나 재료가 없다)으로
  줄었다. 화해 ①이 1,254,878행을 지우고 1,196,426행을 썼다. 확인: 삼성전자 PER 마지막 10-07(잠정 10-08) · 현대차 09-30(잠정 10-01) ·
  C 10-12(8-K 10-13 10:09 ET) · RMD · GOOGL 10-29 값 있음(RMD 8-K 16:05 ET → 10-30부터).
- 결과 표 `financialBasis`는 2,000행에서 재무 한 질의 + 발표 한 질의다(통화 관문을 집합 꼴로 걸어 1,000종목 0.11초 · 상관 꼴 4.85초).
- 새 표 둘은 `StockFold` 규칙에 들어 있다.
- 되돌리려면 `financialsAt`을 `visibleAt`으로, 러너의 실적 발표 공개일을 빈 목록으로 돌린다(지표가 다시 옛 분기로 계산된다).
  `KrEarlierVintages`가 넣은 판은 `published_at`이 원본 접수일인 비최신 판이다.
- 안 닫은 것:
  - 외국 발행사 21종목의 6-K 실적 발표는 감지하지 못한다(진짜 한계 · 「감지 대상 아님」). 그 종목은 막히지도 않는다.
  - 국내 비12월 결산(신영증권 등)은 저장 기말이 합성 규약이라 판정하지 않는다 — 막히지도 않는다(progress A29).
  - 정정이 숫자를 바꾼 국내 보고서는 원본 값의 판이 없다 — 그 기간은 옛 기준이고, 새 실적 발표가 있으면 막힌다.
