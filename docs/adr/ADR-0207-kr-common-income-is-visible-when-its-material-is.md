---
id: ADR-0207
status: 채택됨
date: 2026-11-10
applies_to:
  - "src/main/java/**/pipeline/datasource/kr/xbrl/KrCommonIncome.java"
  - "src/main/java/**/pipeline/datasource/kr/xbrl/KrCommonIncomeRows.java"
  - "src/main/java/**/pipeline/datasource/kr/xbrl/KrCommonIncomeDeriver.java"
  - "src/main/java/**/pipeline/datasource/kr/xbrl/DartXbrlFactReader.java"
  - "src/main/java/**/pipeline/datasource/kr/xbrl/KrXbrlStore.java"
  - "src/main/java/**/indicator/CommonIncomeRead.java"
  - "src/test/resources/db/testmigration/V70_16__kr_common_visible.sql"
---

# ADR-0207. 국내 보통주 몫은 그 재료가 공개된 날부터 보인다

## 결정

국내 관문 켬(`stockscreener.gate.kr-provisional`)의 보통주 몫 엔진 4판(ADR-0192의 엔진 · `KrCommonIncome.Rules.V4`)은 행 공시일에 아직
공개되지 않은 재료로도 값을 정할 수 있다 — 분기 행이 그 해 명부(그 해 사업보고서에 실린다)를 쓰거나(PIT-a), 그 판에 W가 없어 뒤 판 비교열 W를
쓴다(CMP). 그 값은 **재료가 공개된 날부터** 보이게 `financial_common_income`에 가시일 두 칸을 둔다.

- `common_visible_from` — 보통주 몫이 보이는 첫날(접수일). NULL은 행 공시일. 그 해 사업보고서가 아직 없으면 9999-12-31.
  Q2 · Q3 누계와 Q4는 재료 행의 가시일을 물려받는다.
- `payout_visible_from` — 배당성향 분모가 보통주 몫에 기댈 때만 같은 날. 분모가 지배 NI 그대로면 NULL.
- 읽는 쪽: 지표는 접수일을 보이는 날로 옮겨(`krVisibleDays` · ADR-0204) 봉마다 손익 몫만 비운다(`FinancialFigures.asSeenOn` · 자본 재료는 안 가린다)
  · 그날을 재무 경계에 넣는다 · 종목 상세는 오늘 기준 · 거버넌스 유도는 FY 행 공시일에 보이던 몫만 분자로 쓴다.
- 관문 켬 판독기 판 2 — 상충 해소의 증거(희석 EPS)를 켬일 때만 재료에 남긴다(`DartXbrlFactReader.version`). 꺼짐은 판 1 그대로다.

## 규칙

- MUST: 가시일 두 칸과 판 2를 읽고 쓰는 코드는 관문 켬 갈래에만 둔다 — 운영 DB에는 관문 직전 마이그레이션(V70_14 · V70_15 · V70_16)까지 칸이 없다.
- MUST: 행 공시일보다 늦게 공개되는 재료로 값을 정하는 규칙을 더하면 그 날을 `Resolved.visible`로 단다 — 안 달면 과거 봉에 미래 재료가 실린다.
- MUST NOT: 가시일로 자본 판정(`common_fraction` · `equity_branch`)을 가리지 않는다 — 대차대조표 판정은 그 판 재료다.
- MUST NOT: 「아직 안 보임」을 지배 NI 갈래로 내리지 않는다 — 그 봉의 주당 지표 분자는 모른다(`none:not-yet`).

## 검증

- 단위: `KrCommonIncomeTest` `pitUsesTheSameYearRegisterOnlyWithoutAPriorOneAndShowsItFromTheAnnualReport` · 통합:
  `KrCommonIncomeDeriverTest` `comparativeWeightBecomesVisibleOnTheLaterFiling`(유도 → 저장 → 관문 켬 지표 읽기 → 봉 사본).
- 0콜 대조: FSR 녹화본 재생 작은 DB에서 기준 구현(탐색 `KR-BLANKS-PLAN-1106.md`의 `engine4.py`)과 갈래 · 값 · 가시일이 행마다 같다
  (`FsrReplayDerivationTest` · 2026-11-10 · 22,604/22,604).

## 배경

엔진 3판은 분기 행에도 그 해 명부를 썼다 — 분기 공시 때 아직 없는 명부라 과거 기준일 조회에 미래 재료가 실렸다. 4판은 그 자리를 닫고(PIT-a),
W 없는 판을 뒤 판 비교열 W로 채운다(CMP · 작은 DB 새 값 200). 둘 다 값은 원장이 밝힌 것이지만 공개된 날이 행 공시일보다 늦다. 값을 버리면
멀쩡한 결손이 남고, 행 공시일로 실으면 시점 정합성이 깨진다 — 날을 따로 저장하는 것이 둘 다 지키는 길이다.

## 버린 대안

- **늦게 보이는 값은 안 낸다**: 작은 DB에서 값이 서는 행 중 가시일이 늦은 것이 400여 행이다. 그 재료가 공개된 뒤의 봉까지 비게 된다.
- **가시일 한 칸으로 분모도 함께 가린다**: 지배 NI 그대로인 분모(가시일이 늦은 행 대부분)는 미래 재료가 아닌데 몇 달씩 빈다. 분모가 보통주 몫에
  기대는 행(작은 DB 37)만 같은 날을 단다.
- **판독기 판을 모두에게 올린다**: 배포 다음 평일 14시대 사슬의 재료 받기가 국내 전 판(작은 DB 18,445)을 다시 읽어 뒤 사슬을 민다. 켬에서만
  판 2를 쓰면 관문 날 켬으로 도는 재료 받기가 한 번 다시 읽는다.

## 결과

관문 날 순서에 `kr-xbrl-collect`(켬 · 판 2로 다시 읽음)가 유도 앞에 와야 한다 — 판 1 재료만 있으면 켬 유도는 재료를 못 본다. 되돌리려면
`Rules.V4`에서 PIT · CMP를 빼고 가시일 두 칸을 걷는다(칸이 NULL이면 행 공시일이라 읽는 쪽은 그대로 돈다).
