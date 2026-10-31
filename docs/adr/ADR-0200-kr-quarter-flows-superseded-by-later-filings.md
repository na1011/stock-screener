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

- 검출(`DartFlowBasis` · 두 국내 어댑터): ① 반기 누계 ≠ 1 + 2분기 → 1분기(반기 공시일) ② 9개월 누계 ≠ 반기 누계 + 3분기 → 1 · 2분기(3분기
  공시일) ③ 다음 해 1분기 보고서의 전기 3개월 열 ≠ 1분기 → 그 해 1~4분기(그 공시일). 대체일은 쓴 보고서 공시일의 최댓값.
- 허용: 저장된 항 절댓값 합의 1% — 미국의 같은 꼴(비교열 세대 맞춤 `keepSameGeneration` · 「1% 안의 변화는 같은 세대」)과 같은 문턱.
- 칸마다 주인 하나: 매출 · 영업이익 · 순이익은 주요계정 어댑터, 매출원가 · 지배주주 순이익(그 소스의 순이익 판정을 따른다)은 전체 재무제표 어댑터.
- 저장(`FinancialWriter.applyFlowBases`): 쓰기 모드와 무관하게 그 공시일의 행에, 판정한 칸만 다시 짓는다 — 다음 판정이 대체를 안 내면 지운다.

## 규칙

- MUST NOT: 판(합성 행)을 더해 표지하지 않는다 — 회수할 길이 없고(DART는 판 이력 회수 대상이 아니다) 화면에 「정정본」으로 거짓 표시되며,
  전체 재무제표 쓰기(`FILL_WITHOUT_REVISION`)가 원 1 · 2분기 행을 다시 못 고친다(반증 두 렌즈 · 2026-11-01).
- MUST NOT: 다음 해 전기 열을 그 기간의 새 값으로 넣지 않는다 — 녹화본에서 닫히는 것이 매출 26/50 · 영업이익 29/63뿐이다(그 뒤 또 다시 실은
  해 · 원장 자기 모순 · 반기가 이미 고친 해). 전기 열은 **검출기**다.
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

**0콜 재생(편입 350 × 2019~ · 녹화본 · 2026-11-01)**: 관문 꺼짐 정렬 md5 같음 · 관문 켬 보고서 행 변화 0(판정 줄만 더해짐).
- 대체가 있는 (종목, 연도): 주요계정 195 · 전체 재무제표 201(주요계정 195를 다 포함 · 더한 6은 매출원가 · 통화 전환 241560).
- 칸(분기 행 × 칸): 영업이익 581 · 매출 428 · 순이익 210(주요계정) · 매출원가 399 · 지배주주 순이익 214(전체 재무제표).
- 단계는 ③ 다음 해 전기 열이 가장 크다(파이썬 추정 151 (종목, 연도) · ① 35 · ② 34).
- 재지 않았다: 미표시가 되는 TTM 창 · 봉 수(지표 계층 스윕) · 보통주 몫 엔진의 Q4 · 누계 갈래가 대체된 칸을 읽는 꼴.
- 남는 것: 4분기로 끝나는 TTM 창은 FY 행이 정확히 주는데 지금은 대체된 1 · 2분기 때문에 FY 공시일 ~ 다음 해 1분기 공시일에 미표시다(① · ② 단계 해 · 반증 밖 렌즈 7) — 지표 계층의 「4분기 끝 창 = FY」로 닫는다.
