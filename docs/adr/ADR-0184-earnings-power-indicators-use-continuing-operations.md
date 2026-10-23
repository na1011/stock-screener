---
id: ADR-0184
status: 채택됨
date: 2026-10-23
applies_to:
  - "src/main/java/com/stockscreener/indicator/FinancialFigures.java"
  - "src/main/java/com/stockscreener/indicator/calculator/FinancialMath.java"
  - "src/main/java/com/stockscreener/pipeline/datasource/us/SecEdgarFacts.java"
  - "src/main/java/com/stockscreener/pipeline/datasource/kr/DartFull*.java"
  - "src/test/resources/db/testmigration/V70_2__discontinued_income.sql"
  - "src/test/resources/db/testmigration/V70_3__discontinued_rule.sql"
---

# ADR-0184. 이익력 지표는 계속영업 이익을 쓰고, 중단영업은 새 컬럼으로 싣는다(관문 뒤)

## 결정

이익력 지표(PER 계열 · EPS 계열 · ROE · ROA · 순이익률 · 세율 계열)의 분자에서 **중단영업 손익을 뺀다.** 중단영업은
`financial_statement`의 새 세 칸 — `discontinued_income`(연결) · `discontinued_income_controlling`(지배) ·
`discontinued_rule`(판정 규칙 판) — 에 싣고, **기존 `net_income` · `net_income_controlling`의 뜻은 그대로 둔다.**
미국 몫은 `stockscreener.gate.us`, 국내 몫은 새 `stockscreener.gate.kr` 뒤다.

설계 · 실측 · 반증의 정본은 탐색 브랜치 `explore/s-vintage-coherence`의 `explore/universe/F2B-PLAN-1023.md`다(v1 두 렌즈
「고쳐서 간다」 → v1.1 · 구현 중 바뀐 것 6절 · 0콜 재현 `f2b/measure.py` · `measure2.py`).

## 왜

- 순이익에 중단영업(매각 차익 포함)이 섞여 PER이 이익력을 안 말했다 — PAA Q2'26 순이익 1,830M 중 1,649M이 중단영업이라
  10-01 PER 6.05로 화면 #1 문을 통과했다. 미국 편입 최근 TTM에 중단 ≠ 0이 81종목, 국내 편입 40종목(2025 사업 · 2026 반기
  두 보고서 기준 하한)이다.
- **가짜 한계다** — 원장(companyfacts · `fnlttSinglAcntAll`)이 중단영업을 준다. 미국 어댑터는 그것을 유도 재료로만 읽고
  저장하지 않았고, 국내 매퍼는 읽지 않았다.
- 정의를 정한 사용자 결정은 없었다(ADR-0098의 사용자 결정은 「우선주를 분모에 안 넣는다」) — 판단 기준으로 정했다.
- **기존 두 컬럼의 뜻을 바꾸지 않는 이유**: 원장 `eps`(기본 EPS · 중단 포함)와 맞대는 소비자가 셋이다 — 배당 주식수 20% 게이트
  (`GovernanceFactDeriver`) · 순위 오염 훅(`guard-rebalance-on-dirty-rank.sh`) · ADR-0126 축척 판정.

## 규칙

- MUST: 지표 묶음 — **계속영업**: PER · PER_MEDIAN_5Y · EPS_TTM · EPS_GROWTH_TTM_YOY · EPS_CAGR_5Y · (PEG) ·
  ROE · ROE_MIN_3Y · ROA · NET_MARGIN · EFFECTIVE_TAX_RATE · ROIC_AFTER_TAX(셋). **합계 유지**: ACCRUAL_RATIO(영업현금흐름도
  중단 포함) · DIVIDEND_PAYOUT · EXEC_PAY_TO_NET_INCOME(그 기간 주주에게 귀속된 것 전체 — 배당 게이트의 `eps`와 같은 뜻).
- MUST: 지배 몫을 원장이 못 정해 주는 창은 **PER · EPS 계열을 미표시**한다 — 연결 계속영업으로도, 지금 값(중단 포함)으로도
  떨어뜨리지 않는다. ROE는 연결 짝(연결 계속영업 ↔ 총자본)으로 간다.
- MUST: 지배 몫은 **항등식만** 쓴다 — 「비지배 몫 0」 가정은 두지 않는다. 국내만 주당이익 비(계속 EPS ÷ EPS · 원화 반올림 오차
  상한 0.5% 안)를 마지막 갈래로 둔다.
- MUST: 「계속영업」 태그는 그 행에 중단 줄이 있을 때만 쓴다 — 이름이 계약이 아니다(중단 없는 행에서 그 태그가 지분법 전
  이익인 칸 88).
- MUST: Q4 중단영업 = **FY − 같은 해 9개월 누계**(미국: FY 공시일 이하에서 그 9개월 순이익을 실은 가장 늦은 공시 · 국내: Q3
  보고서 `thstrm_add_amount`). `FY − (Q1 + Q2 + Q3)`가 아니다. 9개월 공시에 중단 줄이 없는데 FY ≠ 0이면 그 판에서는 모른다.
- MUST: `discontinued_rule`이 NULL이면 「이 규칙으로 안 읽음」 — 계속영업 = 순이익(관문 꺼짐과 같은 동작). 있는데
  `discontinued_income`이 NULL이면 「읽었는데 모름」이다. 두 금액 칸만으로 이 둘을 가르지 않는다.
- MUST: 미국 관문 뒤 재료(`SecEdgarColumn.gated`)는 값만 댄다 — 앵커 · 연차 창 · 라벨 탐색과 원시 비교(`conflicts`)에 넣지
  않는다. 판은 저장 칸(`storedDiffers`)이 달라질 때만 새로 낸다.
- MUST: 판독기 · 재무 탭 · Writer는 **그 행 시장의 관문**이 켜졌을 때만 새 칸을 읽고 쓴다(「us 켬 · kr 끔에서 국내 행 무변화」).
- MUST: 계산기 메타 설명 · 미표시 사유 카드의 「순이익」 → 「계속영업 순이익」은 기본값을 뒤집은 jar와 같은 커밋에 넣는다.
- MUST: 세 마이그레이션(`V70_2` · `V70_3` · `V70_4`)은 먼저 여는 관문 직전에 `main` 마이그레이션으로 옮긴다.

## 버린 대안

- **기존 순이익 컬럼의 뜻을 계속영업으로 바꾸기**: 위 「왜」의 `eps` 소비자 셋이 깨진다.
- **「비지배 몫 0」(지배 중단 = 연결 중단)**: 답을 아는 칸 2,178 중 같은 것 1,764(81.0%). 같은 공시 대조로 어긋난 144 중
  104는 원장이 자기정합한데 가정만 틀렸다 — 항등식 갈래가 어긋날 때(275/275)는 원장 자체가 모순이다. PAGP처럼 비지배가 다수
  지분인 곳에서 부호가 뒤집힌다.
- **EPS 증인(미국)**: 받은 1,469 중 157(10.7%)이 틀린다. 미국 주당이익 비로 지은 지배 계속영업은 0.5% 안이 68%.
- **지배 미정 창을 연결 계속영업으로**: 그 창은 비지배가 있는 회사에서만 생겨 ADR-0159 폴백의 근거(「연결 = 지배」)가 안 선다 —
  SK H1'26 3.1배 · 두산 +64% · QSR PER 분자 +30%(반증 렌즈 안 · 밖).
- **지금 값(중단 포함) 유지**: 그 오차가 연결 중단 안이라는 보장(DI_P가 0과 DI_T 사이)이 2,996칸 중 89.5%에서만 선다.
- **Q4 = FY − (Q1 + Q2 + Q3), 분기 줄 없음 = 0**: 재분류 몫을 Q4로 몬다 — MLM FY2025 94 대 46 · SWX −63.4 대 −1.5 ·
  솔브레인홀딩스 445.2B 대 424.4B.

## 결과

- 0콜 스윕(`.factcache` 1,218 · IFRS USD 130): **관문 꺼짐 = `main`(`42764154`) 바이트까지 같다**(TSV · 부가 출력 md5). 켬은
  새 판 208(204는 중단영업만 다름) · 기존 칸이 바뀐 Q4 판 4 · 같은 판 기존 칸 변화 0.
- 최신 TTM 미국: PER 계열 미표시 15 · ROA · 순이익률만 미표시 8 · IFRS PER 계열 미표시 2. TTM 분자: MLM 2,457M → 929M ·
  PAA 2,765 → 1,041 · PAGP 554 → 69 · GPN −934 → 528 · ETSY 209 → 405.
- 국내 0콜 스윕(696보고서): 중단 ≠ 0 59 · 지배 모름 12(주당이익 비 전 26) · 지배주주 순이익을 계속 + 중단 지배 몫 합으로 짓는
  보고서 5.

## 검증

- `./gradlew test --tests '*ContinuingIncomeTest*' --tests '*DiscontinuedGateTest*' --tests '*SecEdgarDiscontinuedTest*' --tests '*DartDiscontinuedTest*' --tests '*FinancialWriterTest*' --tests '*GateSettingGuardTest*' --tests '*BatchExecutionScopeTest*'`
- 관문 꺼짐 동일성: `REPLAY_SWEEP_DIR=.factcache REPLAY_SUPPLEMENT_DIR=off US_GATE=false ./gradlew test --tests '*SecEdgarFactsSweepTest*'` 출력을 이 결정 전 커밋의 같은 실행과 md5로 대조한다.
