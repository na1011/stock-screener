---
id: ADR-0209
status: 채택됨
date: 2026-11-11
applies_to:
  - "src/main/java/**/pipeline/datasource/kr/DartFullStatementAdapter.java"
  - "src/main/java/**/pipeline/datasource/kr/DartFullAccountMapper.java"
  - "src/main/java/**/pipeline/datasource/kr/xbrl/KrCommonIncome.java"
---

# ADR-0209. 국내 「비지배 전무」는 판 하나의 흔적으로 판정한다

## 결정

국내 관문 켬(`stockscreener.gate.kr-provisional`)에서 전체 재무제표 어댑터는 「이 기간 비지배가 없다」를 **그 판(연결 응답) 하나**로 판정한다 —
`DartFullAccountMapper.hasNoncontrollingTrace`: 표준 비지배 계정(손익 · 포괄 · 재무상태표 · 현금흐름 어디든)이나 자본변동표 비지배 열(구성요소 축)의
당기 값이 0이 아니거나 재무상태표 비지배지분 전기말이 0이 아니면 흔적이 있다. ADR-0159의 「종목 단위 증거」(그 기간 뒤로 비지배를 냈는가)는 국내
켬 갈래에서 이것으로 바뀐다. 같은 증거에서 셋이 나온다.

- **게이트**: 흔적이 없는 판은 지배 줄이 없을 때 지배주주 순이익 = 당기순이익(`gateOpen` perReport). 저장 이른 판(인스턴스)은 그 판과 그 기간 최신
  응답 둘 다 흔적이 없을 때만.
- **지배 중단**: 흔적이 없는 판은 지배 중단 = 연결 중단(`discontinued` noNoncontrolling) — 4분기는 FY · Q3 판마다.
- **K19 후보**: 표준 지배 줄을 자본변동표 지배 열이 반박하고 그 칸에 비지배 흔적이 없으면 당기순이익이 확인 후보다(`confirmedControlling`).
- **보통주 몫 앵커**: 지배 NI = 연결 순이익인 행(게이트가 연 행)도 지배 NI가 빈 행처럼 NC-b(인스턴스 자본총계 = 지배 자본 · 비지배 손익 0)로
  앵커를 받는다(`KrCommonIncome.openedRow`) — 인스턴스 지배 귀속 사실이 몇 원 갈려 1차 앵커가 실패하는 행(043260 FY2021 · 841 대 848).

## 규칙

- MUST: 세 자리는 같은 흔적 술어를 쓴다 — 하나만 바꾸면 한 판이 「비지배 없음」과 「지배 몫 모름」을 함께 말한다.
- MUST NOT: 표준계정코드 없는 줄의 이름으로 흔적을 세지 않는다 — 089970 2025 3분기는 「비지배지분」 이름 줄에 당기순이익과 같은 값을 실었다(지배
  자본 = 총자본). 자본변동표 비지배 열은 줄 ID가 없어도 센다(구성요소 축은 구조다).
- MUST NOT: 값이 0인 비지배 줄을 흔적으로 세지 않는다(171090 — 7년 내내 비지배지분 0인데 0원 줄 하나 · 계획 K9).
- MUST NOT: 별도(OFS) 응답에서 게이트를 열지 않는다 — 별도재무제표에는 비지배 줄이 원리적으로 없다(450080).
- 꺼짐 갈래는 지금 그대로다(그 기간 뒤 비지배 · 존재만 보는 손익 비지배 줄). 관문 뒤 꺼진 갈래와 함께 걷는다.

## 검증

- 단위: `DartFullStatementGateTest` `laterNoncontrollingDoesNotCloseAnEarlierReportWhenGated` ·
  `traceOutsideTheIncomeStatementClosesTheReport` · `zeroNoncontrollingLineIsNotEvidenceWhenGated` · `DartDiscontinuedTest`
  `reportWithoutNoncontrollingTraceGivesTheConsolidatedShare` · `DartFullAccountMapperTest`
  `netIncomeIsTheCandidateWhenTheColumnHasNoNoncontrollingTrace` · `KrCommonIncomeTest` `ncAlsoAnchorsARowTheReportGateOpened`.
- 0콜 재생: `DartRecordedSweepTest`(`DART_SWEEP_KR_GATE`) 켬 전후 diff · 꺼짐은 HEAD와 바이트 동일.
- 작은 DB: `FsrReplayDerivationTest` 꺼짐 재생이 HEAD와 바이트 동일(재무 행 · `flow_superseded`) · 켬 재생이 기준 구현(탐색 `engine4.py`)과 행마다 같다.

## 배경

옛 술어(「그 기간 뒤로 비지배를 냈는가」 · 미국 `SecEdgarFacts.noNoncontrollingIncomeBy`와 같은 축)는 「비지배 줄을 생략하는 판이 있다」를 막으려
종목 이력을 봤다. 두 결함이 났다 — 뒤에 진짜 비지배가 생기면 앞 기간 전부가 닫힌다(033790 · 253590 · 319660 · 계획 K9b) · 답이 호출이 받은 기간
집합에 따라 갈린다(FY만 부른 11-02 관문 FSR과 전 분기 정기 FSR이 FY 24칸에서 엇갈림 · 계획 K10).

그 사유를 녹화본(11-01 · `fnlttSinglAcntAll` CFS 8,462판 · 350기업)으로 쟀다. 게이트가 물리는 판(표준 지배 · 비지배 손익 줄 없음) 1,032 중 이
술어로 새로 열리는 169판에서: 다른 판 재무상태표가 그 기간 비지배 ≠ 0을 실은 것 0 · 뒤 해 같은 보고서의 전기 열이 그 기간 비지배 ≠ 0을 실은 것
0/161 · 같은 해 FY 손익만 비지배를 실은 13판은 7기업 전부 그 해 중간에 비지배가 처음 생겼다(FY 자본변동표 비지배 기초 0 · 종속기업 취득 · 증자).
판 전체(자본변동표 · 현금흐름 포함)를 보면 그 사유는 서지 않는다.

지배 중단: 흔적 없는 판 중 표준 지배 중단 줄이 있는 6판 전부 연결 중단과 같았다. ADR-0184 쪽 「비지배 몫 0 가정 금지」는 판 안 증거 없이 줄
부재로 가정하는 것(미국 1b · 104/144)에 선다 — 여기는 판 전체가 증거다.

K19 후보: 흔적 없는 판 중 표준 지배 줄 ≠ 당기순이익 19판 · 그중 자본변동표 지배 열 = 당기순이익 13판(LG이노텍 2022 — 표준 지배 줄 976.9B는 계속영업
몫 · 비표준 「지배기업의 소유주지분」 2.99B가 중단 몫 · 자본변동표 지배 열 979.8B).

## 버린 대안

- **옛 술어에 「뒤 비지배가 처음 생긴 날」을 더한다**: 기간 집합 의존(K10)이 남는다 — 그날을 그 호출이 받은 판으로만 안다.
- **흔적을 손익 · 재무상태표 표준 줄로만 센다(K9 술어)**: 게이트가 물리는 판 중 손익 · 재무상태표에 없고 자본변동표 · 현금흐름에만 비지배가 실린 판이
  있다(재생 56판 중 게이트가 물리는 것 7) — 판 단독으로 열 때는 판 전체를 본다.
- **미국에도 같이 옮긴다**: companyfacts는 판(응답) 단위가 아니라 사실 단위라 「판 전체」가 같은 뜻이 아니다 — 재보지 않았다(미국 관문 몫).

## 결과

녹화본 0콜 재생(350기업 · 2019~ · 켬 전후): 지배주주 순이익 빈칸 → 값 185칸/17종목 · 지배 중단 빈칸 → 값 17칸/4종목 · 값 → 값 지배 중단 5칸
(LG이노텍 2022 — EPS 비 추정 → 원장 동일값) · 지배주주 순이익 값 → 값 23칸/8종목(전부 비지배 없는 판에서 자본변동표 지배 열 = 당기순이익) ·
잃은 값 0. 꺼짐은 HEAD와 바이트 동일. 작은 DB(켬 · 녹화본 재생 2019~ · 유도까지): 보통주 몫 빈칸 → 값 34칸/13종목 · 값 → 값 16칸/9종목 ·
배당성향 분모 빈칸 → 값 29칸/15종목 · 값 → 값 15칸 · 값 → 빈칸 1(319660 2019 2분기 분모 0원 — 그 분기 순이익 41.8억이라 설 수 없던 값). 바로잡힌
틀린 값의 예: 328130 2022 1분기 지배 NI +21.76B(순이익 −21.76B · 부호가 뒤집힌 표준 지배 줄).
