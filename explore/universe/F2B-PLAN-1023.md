# F2b 중단영업 — 이익력 지표는 계속영업 이익을 쓴다 (2026-10-23 · 계획 v1)

★ = 내가 원장 · 코드 · DB에서 직접 쟀다. 재현은 `f2b/measure.py`(0콜 · 미국 `.factcache` · 국내 `f2b/kr_probe.py`가 받은 원응답).

## 0. 질문과 판정

- 순이익에 중단영업 손익(매각 이익 포함)이 섞여 PER · EPS · ROE가 이익력을 안 말한다. PAA는 Q2'26 순이익 1,830M 중 1,649M이 중단영업이다(10-01 PER 6.05 · 화면 #1 문 통과).
- **가짜 한계다.**
  - ① 물은 곳: 미국 companyfacts(`.factcache` 994/1,000) · 국내 `fnlttSinglAcntAll`(CFS · 없으면 OFS) 2025 사업(11011) · 2026 반기(11012) 350종목 742콜(10-23 07:5x).
  - ② 대조군: 같은 질의가 PAA · MLM · KMB에서 `IncomeLossFromDiscontinuedOperationsNetOfTax` 값을 받았다 · 국내 삼성바이오로직스 FY2025 `ProfitLossFromDiscontinuedOperations` 170.0B.
  - ③ 모집단: 미국 편입 · 국내 편입(KOSPI200 + KOSDAQ150) 전원.
  - ④ 뿌리: 미국은 어댑터가 중단영업을 유도 재료(`SecEdgarColumn.DISCONTINUED_OPERATIONS` · 저장 안 함)로만 읽고, 국내는 `DartFullAccountMapper`가 계속 · 중단 줄을 안 읽는다. 계산기는 `net_income` · `net_income_controlling`(둘 다 중단 포함)만 본다. 0콜 재현은 `measure.py`.
- 정의 결정의 기존 사용자 결정은 없다(ADR-0098의 사용자 결정은 「우선주를 분모에 안 넣는다」). 판단 기준으로 내가 정하고 ADR에 적는다.

## 1. 실측

### 1a. 크기 ★

| | 미국(편입 · 캐시 994) | 국내(편입 350 · 응답 696/700) |
|---|---|---|
| 중단영업 ≠ 0 | 최근 네 분기(3개월 팩트) 207칸 · **74종목** · 최근 두 FY 155칸 · 95종목 | FY2025 또는 H1'26 **40종목** · 60보고서 |
| 순이익의 10% 넘음 | 분기 30종목 · FY 40종목 | 17종목 |
| 50% 넘음 | (10-23 앞선 실측 13) | 6종목(스피어 · 네패스 · 카카오 · 솔브레인홀딩스 · LG화학 · CJ제일제당) |

- 방향: 이익 쪽 PAA · PAGP · MLM · ACA · SWX · KMB · HL(거짓 저PER) · 손실 쪽 DD · SSRM · ETSY · GPN · TFX · OC(거짓 고PER · 미표시) · 국내 솔브레인홀딩스 FY2025 순이익 495.6B 중 중단 445.2B.
- 앞선 실측 「78 · 21 · 13」(10-23 progress)과 다른 것은 술어가 달라서다(그때는 DI_N 태그도 셈 · 분모 지배주주). 이 계획의 수는 위 표다.

### 1b. 원장이 주는 것 ★

- **미국 — 연결 몫(DI_T)은 거의 늘 온다.** 중단 ≠ 0 분기 207 중 `NI_T = CO_T + DI_T` 항등식이 103/104 · FY 79/79로 선다.
- **미국 — 지배 몫(DI_P · 계속영업 지배 CO_P)은 덜 온다.** 직접 · 정확한 유도 갈래(아래 2절 ②)로 분기 207 중 171 · FY 155 중 141이 정해진다. 못 정하는 칸이 분기 36 · FY 14(17종목: C · CARR · D · DD · GE · HON · KMB · LEVI · LYB · OXY · PAA(Q4) · PAGP(Q4) · PFE · QSR · SWX · TEL · TFX). 5년(2021-07 뒤) 1,546칸 중 196 · 45종목.
  - GE는 지배 몫을 **회사 확장 태그**로만 준다(`ge:EarningsFromContinuingOperationsAttributableToCommonShareownersBasic` Q2'25 2,008M · 인스턴스 2콜). companyfacts에 없고 회사마다 이름이 달라 계약이 없다 — 이 칸은 표준 택소노미 층에서 **진짜 한계**다.
- **이름이 계약이 아니다.** 같은 공시 · 같은 기간에 중단 태그가 없는데 `NetIncomeLoss − IncomeLossFromContinuingOperations ≠ 0`인 칸이 2019 뒤 798. 그중 차이가 `IncomeLossFromEquityMethodInvestments`와 같은 것 88(「계속영업」 태그에 지분법 전 이익을 싣는 회사 — MPLX · THG 등). **중단영업이 없는 행에서 계속영업 태그를 쓰면 없던 오차를 만든다.**
- **중단영업 구성요소만 내는 행**(후보 태그 없음 · `…GainLossOnDisposal…NetOfTax` · `…DuringPhaseOutPeriodNetOfTax` · `…AvailableToCommonShareholdersBasic` ≠ 0): 지금 창 16칸 · 6종목(GTES · KEY · LAUR · AES · ESE · TEX).
- **국내**: 중단 ≠ 0 보고서 60 중 지배 몫이 정확히 정해지는 것 28(계속 지배 · 중단 지배 · 비지배 몫 직접) · 비지배가 0인 것 6 · 나머지 26은 연결 몫만.
  - K-IFRS는 중단영업이 있을 때만 계속 · 중단을 갈라 표시한다(`dart-financial.md` 「계속영업손익 1/8」 · 이번 72종목이 줄을 가졌고 그중 40이 ≠ 0). 줄이 없으면 「중단영업 없음」이다.
  - 지배주주 순이익 줄 없이 계속 · 중단 지배 몫만 주는 보고서 5(036540 · 솔브레인홀딩스 · 스피어 — `reload-gate.md` 국내 「계속+중단영업으로 나뉜 값」 행의 꼴).

### 1c. 버린 대안의 수 ★

- **「지배 몫 = 연결 몫」(비지배 몫 0 가정)**: 답(DI_P 직접)을 아는 칸 2,178 중 같은 것 1,764(81.0%). 계속영업 답(CO_P 직접) 2,973칸에서 지금(NI_P)보다 가까워지는 것 2,773 · 멀어지는 것 196 · 절대오차 −80.7%. PAGP처럼 비지배가 다수 지분인 곳에서 부호가 뒤집힌다(Q4'25 62 − 102 = −40).
- **EPS 증인**(`NI_P × EPS_CO / EPS`와 위 값이 1% 안이면 받기): 받은 1,469 중 157(10.7%)이 틀린다.
- 둘 다 「지어낸다」. 지배 몫을 못 정하는 창은 **이미 있는 폴백**(연결 총액 순이익 · ADR-0159)과 같은 갈래 — 연결 계속영업 — 로 간다(2절 ④). 연결 몫은 항등식으로 확인된 값이라 지어낸 값이 아니다.

### 1d. 소비자 ★(`grep` 전수 · 코드 · 시험 · 펜스)

- 계산기(net income을 읽는 13): PER · PER_MEDIAN_5Y · EPS_TTM · EPS_GROWTH_TTM_YOY · EPS_CAGR_5Y(PEG는 PER과 성장을 받는다) · ROE · ROE_MIN_3Y · ROA · NET_MARGIN · ACCRUAL_RATIO · DIVIDEND_PAYOUT · EXEC_PAY_TO_NET_INCOME. 입구는 `FinancialMath.sumControllingNetIncome` · `controllingNetIncomeOf` · `PeriodSeries.controllingTtm` · `roeBasis`(둘) · `sumTrailingFourQuarters(…::netIncome)`.
- 계산기 밖: `GovernanceFactDeriver:236 · 290`(배당 주식수 20% 게이트 — 분자 `coalesce(nic, ni)`를 `eps`로 나눈다) · 훅 `guard-rebalance-on-dirty-rank.sh:102`(같은 `net_income / eps`) · `StockReader:171`(화면 재무 표) · `StockFinancialsAssembler` · `ops/apply-window/02-snapshot.sh`(md5 스냅숏 — 새 컬럼이 행 md5를 바꾼다).
  - 원장의 `eps`는 기본 EPS(중단 포함)라 **`net_income` · `net_income_controlling`의 뜻을 바꾸면 이 셋이 깨진다.** 그래서 두 컬럼은 그대로 두고 중단영업을 **새 컬럼**으로 싣는다.
- 시험: `ControllingNetIncomeTest` · `FinancialFiguresFixture` · `IndicatorRunnerTest` · `FinancialWriterTest` · `SecEdgarFactsSweepTest` · `FourthQuarterControllingTest` · `SecEdgarControllingQ4GateTest` · `SecEdgarIncomeBasisTest` · `FullStatementRefillJobTest` · `ReadApiTest`.

## 2. 설계

### ① 저장 — 새 컬럼 둘, 기존 두 컬럼의 뜻은 안 바꾼다

- `discontinued_income`(연결 · 세후 · 비지배 몫 포함) · `discontinued_income_controlling`(지배주주 귀속).
- **값의 뜻 셋**: 둘 다 NULL = 그 행에 중단영업 줄이 없다(= 0) · 연결만 있고 지배 NULL = 지배 몫을 원장이 안 정해 준다 · 둘 다 있음.
- 마이그레이션은 시험 전용 `V70_2__discontinued_income.sql`(관문 직전에 `main` 마이그레이션으로 옮긴다).

### ② 채우는 순서

**미국**(`SecEdgarFacts` · `gate.us` 뒤)
- 연결: `IncomeLossFromDiscontinuedOperationsNetOfTax` → `DiscontinuedOperationIncomeLossFromDiscontinuedOperationNetOfTax` → 구성요소만 있으면 `NI_T − CO_T`가 구성요소 합과 같을 때만(증인) → 없음.
- 지배: `…AttributableToReportingEntity` → `연결 − …AttributableToNoncontrollingInterest` → `지배주주 순이익 − IncomeLossFromContinuingOperations`(그 행에 중단 연결 몫이 있을 때만 — 1b의 「이름이 계약이 아니다」) → `지배주주 순이익 − NetIncomeLossFromContinuingOperationsAvailableToCommonShareholdersBasic`(같은 행 `NetIncomeLossAvailableToCommonStockholdersBasic` = 지배주주 순이익일 때 — 우선주 배분 0의 증인) → `지배주주 순이익 − (CO_T − IncomeLossFromContinuingOperationsAttributableToNoncontrollingEntity)` → 그 판까지 비지배를 한 번도 안 낸 발행사면 연결 몫(기존 `noNoncontrollingIncomeBy`) → NULL.
- 판: 새 두 컬럼도 `carryForward` · `conflicts` · `storedDiffers`를 탄다. 재분류는 **총액 순이익을 안 바꾸므로** 「NI 옛 판 · 중단 새 판」 섞임이 계속영업을 틀리게 하지 않는다(NI는 재분류에 불변). 중단이 새로 실린 비교열 판은 새 판으로 나온다.
- Q4: `FY − Q1 − Q2 − Q3`. 분기에 줄이 없으면 0(그 분기 공시에 중단영업이 없었다 — 그때 보이던 판의 뜻). FY의 연결 몫은 있는데 분기 중 하나의 지배 몫이 NULL이면 Q4 지배 몫도 NULL.

**국내**(`DartFullAccountMapper` · `DartFullStatementAdapter` · 새 `gate.kr` 뒤)
- 연결: `ifrs-full_ProfitLossFromDiscontinuedOperations`.
- 지배: `IncomeFromDiscontinuedOperationsAttributableToOwnersOfParent` → `ProfitLossFromDiscontinuedOperationsAttributableToOrdinaryEquityHoldersOfParentEntity` → `연결 − …AttributableToNoncontrollingInterests` → `지배주주 순이익 − 계속영업 지배(두 ID)` → 그 보고서의 비지배 순이익이 0이거나 줄이 없으면 연결 몫 → NULL.
- 함께: 지배주주 순이익 줄이 없고 계속 · 중단 지배 몫이 있으면 `net_income_controlling` = 둘의 합(보고서 5 · 같은 줄을 읽는 일이라 묶는다 — `reload-gate.md` 국내 행의 그 꼴만 닫고 「비표준 계정 행」 꼴은 그 행에 남긴다).
- Q4: 지배주주 순이익 Q4와 같은 자리(`DartFullStatementAdapter` · `FY − 3분기 합`).

### ③ 지표 정의

| 묶음 | 지표 | 분자 |
|---|---|---|
| **이익력 — 계속영업** | PER · PER_MEDIAN_5Y · EPS_TTM · EPS_GROWTH_TTM_YOY · EPS_CAGR_5Y · (PEG) · ROE · ROE_MIN_3Y | 지배주주 순이익 − 지배 중단 |
| | ROA · NET_MARGIN | 연결 순이익 − 연결 중단 |
| **회계 항등 — 합계 유지** | ACCRUAL_RATIO | 연결 순이익(영업현금흐름도 중단 포함 합계라 짝) |
| | DIVIDEND_PAYOUT · EXEC_PAY_TO_NET_INCOME | 지배주주 순이익(기간에 주주에게 귀속된 것 전체 · 배당 주식수 게이트의 `eps`와 같은 뜻) |

- 이유: 중단영업은 정의상(ASC 205-20 · K-IFRS 1105) 이어지지 않는 손익이고 매각 차익을 싣는다. NET_MARGIN의 분모 매출은 이미 계속영업만이다(중단 매출은 매출 줄에서 빠진다) — 분자만 합계면 짝이 어긋난다.

### ④ 창 폴백 — 기존 규칙을 그대로 넓힌다

- 지배 계열: 네 분기(또는 그 FY) 모두 지배 계속영업이 서면 그것 → 아니면 **넷 다 연결 계속영업**(`연결 − 연결 중단`) → 그것도 안 서면 NULL. 지금 `sumControllingNetIncome`의 「행마다 폴백하지 않는다 · 넷 다 연결로」와 같은 모양이다.
- ROE는 짝을 지킨다(ADR-0153): 지배 계속영업 ↔ 지배주주 자본, 연결 계속영업 ↔ 총자본.
- 행 단위 계속영업 = 순이익 − 중단(NULL이면 0). 지배 중단만 NULL이고 연결 중단이 있으면 그 행은 지배 계열에서 「없음」이다.

### ⑤ 관문

- 미국 몫은 `stockscreener.gate.us` 뒤(어댑터 · Writer 컬럼 목록 · 판독기 SELECT).
- **국내 몫은 새 `stockscreener.gate.kr`** — 시장마다 관문 하나(2026-10-04 사용자 결정 「시장별 관문」). `GateSettingGuard`가 두 키를 같은 규칙(허용 출처 · 매 기동 INFO)으로 본다.
- 판독기(`IndicatorDataReader`)는 두 설정 중 하나라도 켜졌을 때만 새 컬럼을 읽는다(마이그레이션이 그때만 있다). 계산기는 그 값이 NULL이면 지금과 같은 수를 낸다 — 꺼진 출력은 `main`과 바이트까지 같다.
- 「무해한 선행 배포」: 계산기 · 판독기 · 시험 마이그레이션을 먼저 넣는다(새 컬럼 0행 동안 값 무변화) → 그 뒤 미국 어댑터 · Writer → 국내 매퍼 · Writer.

### ⑥ 기록

- 새 ADR-0184(정의 · 컬럼 · 폴백 · 버린 대안 1c).
- ADR-0159 §지표표에 「계속영업」 열.
- `dart-financial.md` 「계속영업손익 1/8」 절(전수 수로 바꾼다) · `sec-edgar-financial.md`(중단영업 컬럼) · `reload-gate.md` F2b 행 · 국내 「계속+중단영업으로 나뉜 값」 행.

## 3. 드라이런

1. **미국 0콜 스윕**(`SecEdgarFacts`): 꺼짐 = `main`(md5) · 켬 = `main` 켬 대비 새 두 컬럼만 채워지고 기존 컬럼 0칸 변화 · 새 판(재분류 비교열)이 몇 개 생기는지 센다 · 1a의 이름 종목 칸을 손으로 대조(PAA · MLM · KMB · PAGP · GE · TFX).
2. **국내 0콜 스윕**: `f2b/kr_probe.py` 원응답을 매퍼에 먹이는 스윕을 만든다(지금 국내 전체 재무제표 스윕은 지배주주 자본만 본다) — 60보고서의 두 컬럼 · 5보고서의 `net_income_controlling`.
3. **계산기**: 단위 시험(지배 · 연결 폴백 · Q4 · ROE 짝 · 합계 유지 묶음 불변) + 작은 DB(PAA · PAGP · MLM · GE · 솔브레인홀딩스 · 카카오의 행 · 봉만) — 관문 켬 · 끔으로 지표를 뽑아 대조.

## 4. 순서

반증(두 렌즈) → 구현(계산기 먼저) → 드라이런 1~3 → 커밋 · 배포(관문 꺼짐 · 값 무변화).
