---
id: ADR-0200
status: 채택됨
date: 2026-11-01
applies_to:
  - "src/main/java/com/stockscreener/pipeline/datasource/kr/DartFlowBasis.java"
  - "src/main/java/com/stockscreener/pipeline/datasource/FlowBasis.java"
  - "src/main/java/com/stockscreener/pipeline/datasource/kr/DartFinancialAdapter.java"
  - "src/main/java/com/stockscreener/pipeline/datasource/kr/DartFullStatementAdapter.java"
  - "src/main/java/com/stockscreener/pipeline/collector/FinancialWriter.java"
  - "src/main/java/com/stockscreener/indicator/FinancialFigures.java"
  - "src/main/java/com/stockscreener/indicator/IndicatorContext.java"
  - "src/test/resources/db/testmigration/V70_9__flow_superseded.sql"
---

# ADR-0200. 국내 분기 손익은 뒤 보고서가 다른 기저로 다시 실으면 그날부터 낡았다 — 값을 바꾸지 않고 칸 속성으로

## 결정

국내 관문 켬(`stockscreener.gate.kr`)에서 뒤 보고서가 앞 분기 손익을 다른 기저로 다시 실은 것을 계정마다 검출해, 저장된 그 분기 행에
「그 칸이 언제부터 낡았나」(`financial_statement.flow_superseded` — 관문 시험 마이그레이션 `V70_9`)를 싣는다. 봉은 그날부터 그 칸을 모른다
(`FinancialFigures.asSeenOn` · `IndicatorContext.visibleAt`) — 두 기저를 한 TTM 창 · 분기 성장률에 섞지 않는다.

- 검출(`DartFlowBasis` · 두 국내 어댑터) — 원칙 하나: **보고서가 실은 어떤 기간의 값이 저장된 그 기간 값과 안 닫히면 그 보고서 공시일부터 그 기간의 그
  칸은 낡았다.** 짝은 그 해 누계(반기 누계 ↔ 1 + 2분기 → 1분기 · 9개월 누계 ↔ 반기 누계 + 3분기 → 1 · 2분기)와 다음 해 보고서 넷의 전기 열(3개월
  열 → 그 분기와 뒤 분기 · 누계 열 → 1~4분기 · 전기 FY → FY · 전기 FY − 전기 9개월 → 4분기). 대체일은 쓴 보고서 공시일의 최댓값 · 한 칸이 여러
  짝에 걸리면 이른 날. 저장 값이 0이면 0이 아닌 값이 곧 어긋남(중단영업은 대개 0에서 생긴다 · 손익 행이 온 보고서에 중단 줄이 없으면 0).
- 허용: 저장된 항 절댓값 합의 1% — 미국의 같은 꼴(비교열 세대 맞춤 `keepSameGeneration` · 「1% 안의 변화는 같은 세대」)과 같은 문턱.
- 칸마다 주인 하나: 매출 · 영업이익 · 순이익은 주요계정 어댑터, 매출원가 · 지배주주 순이익(표준 줄이 없으면 그 소스의 순이익 판정) · 중단영업 둘은
  전체 재무제표 어댑터.
- 저장(`FinancialWriter.applyFlowBases`): 쓰기 모드와 무관하게 **그 기간의 판 전부**에, 판정한 칸만 다시 짓는다 — 다음 판정이 대체를 안 내면 지운다.
- 읽기: 순이익 · 지배주주 순이익 · 중단영업 둘은 함께 낡고, 그때 보통주 몫 엔진의 손익 몫(주당 지표 분자 · 배당성향 분모)도 함께 비운다
  (`CommonIncomeRead.withoutIncome` — 자본 재료는 그대로). 국내 관문 켬 공시 소비는 창을 한 해 더 당긴다(앞 해의 전기 열 단계를 다시 짓는다).

## 규칙

- MUST NOT: 판(합성 행)을 더해 표지하지 않는다 — 회수할 길이 없고(DART는 판 이력 회수 대상이 아니다) 화면에 「정정본」으로 거짓 표시되며,
  전체 재무제표 쓰기(`FILL_WITHOUT_REVISION`)가 원 1 · 2분기 행을 다시 못 고친다(반증 두 렌즈 · 2026-11-01).
- MUST NOT: 다음 해 전기 열을 **분기 3개월 값**의 새 판으로 넣지 않는다 — 녹화본에서 닫히는 것이 매출 26/50 · 영업이익 29/63뿐이다(그 뒤 또
  다시 실은 해 · 원장 자기 모순 · 반기가 이미 고친 해). 분기에서 전기 열은 **검출기**다. 연간(전기 FY)을 FY의 새 판으로 받는 것은 이 근거가 안 덮는다
  (재고 「A6 남은 것」).
- MUST: 계정마다 판정한다 — 한 계정이 어긋났다고 손익 칸 전부를 비우면 멀쩡한 칸이 빈다(v1 반증: 68칸 · 순이익 34).
- MUST: 누계 · 전기 열을 읽을 때 빈 칸을 다른 칸으로 메우지 않는다(`DartAccountMapper.findField`) — 메우면 언제나 어긋남으로 걸린다.
- MUST: 전체 재무제표 분기 손익의 전기 3개월 칸은 `frmtrm_q_amount`다(`frmtrm_amount`는 비어 있다 — 주요계정과 다르다).
- MUST: 같은 연결 기저(CFS/OFS) · 같은 통화(주요계정)의 보고서끼리만 잰다.
- 화면 재무 표는 원값을 그대로 보인다(속성이라 값을 안 바꾼다).

## 버린 대안

| 대안 | 근거(실측) |
|---|---|
| 다음 해 전기 열을 그 공시일의 판으로(재고의 첫 처방) | 닫힘 매출 26/50 · 영업이익 29/63 |
| 3분기 단계만 · 유량을 비운 합성 판(v1) | 반기 단계 35 (종목, 연도)를 놓치거나 늦게 걸고 · 다음 해 전기 열로만 보이는 74 · 63종목을 놓친다 · 합성 판은 회수 불가 · 거짓 정정 표시 · 전체 재무제표 쓰기 동결 · 보통주 몫 엔진 판 묶기 깸 |
| 문턱 2%(보통주 자본 immat) | 자본 구성의 축이지 분기 재작성의 축이 아니다 — 같은 꼴의 미국 문턱은 1% |

## 검증

- 판정: `./gradlew test --tests '*DartFlowBasis*' --tests '*CommonEquityBasis*' --tests '*FinancialWriterTest*'`.
- 0콜: `DartRecordedSweepTest` 재생(관문 켬) — 판정 줄 `flow-summary` · `flow-full`.

## 결과

**0콜 재생(편입 350 × 2019~ · 녹화본 · 2026-11-01 · 커밋 코드 출력)**: 관문 꺼짐 정렬 md5 같음 · 관문 켬 보고서 행 정렬 md5 같음(판정 줄만 더해짐).
- 대체가 있는 (종목, 연도) 420 · 172종목(주요계정 346 · 전체 재무제표 400) · 2025 ~ 2026 행 301 · 45종목.
- 칸(행 × 칸): 영업이익 997 · 매출 751 · 순이익 420(주요계정) · 중단영업 870 · 매출원가 709 · 지배주주 순이익 557 · 지배 중단 179(전체 재무제표).
- 반증 두 렌즈 세 판(v1 합성 판 「다시 짠다」 → v2 칸 속성 「고쳐서 간다」 두 렌즈 — 중단영업 · 지배 NI 미판정 · 다음 해 반기 · 3분기 ·
  사업보고서 전기 열 미사용 · 표지가 한 판에만 · 보통주 몫 엔진 분자가 `asSeenOn`을 우회(국내 PER 계열 13종목) · 공시 소비 창 → 모두 이 판에서 닫음).
- 재지 않았다: 미표시가 되는 TTM 창 · 봉 수(지표 계층 스윕).
- 남는 것(재고 행): 4분기로 끝나는 TTM 창의 FY 대체(① · ② 해 67 (종목, 연도) · 약 3,716 달력일) · 국내 연간 손익의 다음 해 전기 열을 판으로
  받기(지금은 FY를 비운다 · 미국 `revisionsOf`와 같은 축) · 미국은 같은 꼴(다음 해 10-Q 비교열)을 섞어 두는데 국내는 비운다(두 시장 한 기준 —
  사용자 결정 「맞추는 안이 기본」에 닿는 방향 선택) · 화면 재무 표 전년 동기 대비(`StockFinancialsAssembler.yoy`)가 낡은 칸으로 비교한다.
