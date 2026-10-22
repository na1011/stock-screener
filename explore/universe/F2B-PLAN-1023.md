# F2b 중단영업 — 이익력 지표는 계속영업 이익을 쓴다 (2026-10-23 · 계획 v1.1)

★ = 내가 원장 · 코드 · DB에서 직접 쟀다. 재현은 0콜 스크립트 두 개다.
- `f2b/measure.py`(v1 수 · 미국 `.factcache` · 국내 `f2b/kr_probe.py`가 받은 원응답)
- `f2b/measure2.py`(v1.1 수 · 운영 술어 · 잔차 Q4 · IFRS 130)

v1을 두 렌즈로 반증했고 둘 다 「고쳐서 간다」였다. 지적과 내가 다시 잰 수는 5절에 있다. 아래 본문은 그것을 반영한 판이다.

## 0. 질문과 판정

- 순이익에 중단영업 손익(매각 이익 포함)이 섞여 PER · EPS · ROE가 이익력을 말하지 않는다. PAA는 Q2'26 순이익 1,830M 중 1,649M이 중단영업이다(10-01 PER 6.05 · 화면 #1 문을 통과했다).
- **가짜 한계다.**
  - ① 물은 곳
    - 미국: companyfacts(`.factcache` 994/1,000 · IFRS USD 발행사 130 — 10-23 130콜).
    - 국내: `fnlttSinglAcntAll`(CFS, 없으면 OFS) 2025 사업(11011) · 2026 반기(11012), 350종목 742콜.
    - GE 10-Q 인스턴스(2콜).
  - ② 대조군: PAA · MLM · KMB가 `IncomeLossFromDiscontinuedOperationsNetOfTax` 값을 줬고, 국내 삼성바이오로직스 FY2025가 `ProfitLossFromDiscontinuedOperations` 170.0B를 줬다.
  - ③ 모집단: 미국 편입 · IFRS(관문 켬 명부) · 국내 편입(KOSPI200 + KOSDAQ150) 전원.
  - ④ 뿌리: 미국 어댑터는 중단영업을 유도 재료로만 읽는다(`SecEdgarColumn.DISCONTINUED_OPERATIONS` · `annualOnly` · 저장 안 함). 국내 `DartFullAccountMapper`는 계속 · 중단 줄을 읽지 않는다. 계산기는 `net_income` · `net_income_controlling`(둘 다 중단 포함)만 본다.
- 정의를 정한 기존 사용자 결정은 없다(ADR-0098의 사용자 결정은 「우선주를 분모에 안 넣는다」다). 판단 기준으로 내가 정하고 ADR에 적는다.

## 1. 실측

### 1a. 크기 ★

| | 미국 us-gaap(편입 · 캐시 994) | 미국 IFRS(USD 130 · 관문 켬) | 국내(편입 350 · 응답 696/700) |
|---|---|---|---|
| 최근 TTM에 중단 ≠ 0(`measure2`) | **81종목** | 15종목 | **40종목 · 60보고서**(두 보고서 기준 하한) |
| 순이익의 10% 넘음(`measure`) | 분기 30 · FY 40 | — | 17 |
| 50% 넘음 | (10-23 앞선 실측 13) | — | 6(스피어 · 네패스 · 카카오 · 솔브레인홀딩스 · LG화학 · CJ제일제당) |

- 방향
  - 이익 쪽은 거짓 저PER이다: PAA · PAGP · MLM · ACA · SWX · KMB · HL.
  - 손실 쪽은 거짓 고PER이거나 미표시다: DD · SSRM · ETSY · GPN · TFX · OC.
  - 국내 솔브레인홀딩스는 FY2025 순이익 495.6B 중 중단영업이 445.2B다.
- 국내 수는 2025 사업 · 2026 반기 두 보고서 기준의 하한이다. Q1 · Q3 보고서와 과거 연도는 드라이런 재료를 받을 때 전수로 다시 센다(3절).

### 1b. 원장이 주는 것 ★

- **연결 몫(DI_T)은 거의 늘 온다.** 미국에서 중단 ≠ 0인 분기 207 중 `NI_T = CO_T + DI_T`가 103/104, FY는 79/79로 선다.
- **지배 몫(DI_P)을 정하는 갈래 — 같은 공시 안에서 직접 DI_P와 대조(2019년 뒤)**:

  | 갈래 | 일치 |
  |---|---|
  | `NIC − CO_P` | 4,912/5,187(94.7%) |
  | `DI_T − DI_N` | 631/708(89.1%) |
  | `NIC − (CO_T − CO_N)` | 1,188/1,345(88.3%) |
  | 「비지배 몫 0」 가정 `DI_T` | 1,089/1,233(88.3%) |

  - **항등식 갈래와 가정은 일치율이 아니라 어긋남의 성질로 갈린다.**
    - `NIC − CO_P`가 어긋나는 275칸은 모두 원장 자신이 `NI_P ≠ CO_P + DI_P`인 자기모순이다(태그 하나가 틀렸다).
    - 가정이 어긋나는 144칸 중 104칸은 원장이 자기정합한데(`NI_P = CO_P + DI_P`) 가정만 틀렸다.
    - 항등식은 원장이 실은 값만 쓰고, 가정은 원장이 안 준 값(DI_N = 0)을 지어낸다.
    - 가정은 PAGP처럼 비지배가 다수 지분인 곳에서 부호를 뒤집는다(Q4'25에서 62 − 102 = −40).
- **회사 확장 태그**: GE는 지배 몫을 `ge:EarningsFromContinuingOperationsAttributableToCommonShareownersBasic`(Q2'25 2,008M)로만 준다. 이 태그는 companyfacts에 없고 회사마다 이름이 달라 계약이 없다. 표준 택소노미 층에서 **진짜 한계**다.
- **이름이 계약이 아니다.** 2019년 뒤 같은 공시 · 같은 기간에 중단 태그가 없는데 `NetIncomeLoss − IncomeLossFromContinuingOperations ≠ 0`인 칸이 798이다. 그중 88은 차이가 `IncomeLossFromEquityMethodInvestments`와 같다(MPLX · THG 등). 중단 줄이 없는 행에서 계속영업 태그를 쓰면 없던 오차가 생긴다.
- **구성요소만 내는 행**: 지금 창에 16칸 · 6종목이다(GTES · KEY · LAUR · AES · ESE · TEX).
- **재분류는 Q4 잔차를 오염시킨다**(반증 두 렌즈):
  - 해 중간에 중단으로 분류되면 FY 중단액에 앞 분기 사업부 손익이 재분류되어 들어간다. 그런데 그 분기들의 원 공시에는 중단 줄이 없다.
    - MLM FY2025: `FY − ΣQ`는 94인데 참값 `FY − 9개월`은 46이다.
    - SWX FY2025: −63.4 대 −1.5다.
    - 솔브레인홀딩스: 445.2B 대 424.4B다.
  - `measure2`로 센 최근 Q4 70의 상태(5년 FY는 492):

    | 상태 | 최근 Q4 | 5년 FY |
    |---|---|---|
    | `FY − 9개월`(같은 해 Q3 공시)로 정해짐 | 57 | 333 |
    | 9개월 공시에 중단 줄이 없음(Q3 뒤 재분류) | 8 | 113 |
    | FY 또는 9개월 지배 미정 | 5 | 37 |
    | 9개월 누계 없음 | — | 9 |

- **순이익은 재분류에 늘 불변인 것이 아니다.** 원 공시에 중단 줄이 없다가 뒤 판에 생긴 기간이 `NetIncomeLoss` 322 · `ProfitLoss` 317이다. 그중 1% · 1M을 넘게 순이익이 바뀐 것이 26이다(반증 렌즈 안 · CR · SWX · PRI · FLR).
- **국내**
  - 중단 ≠ 0인 보고서 60 중 지배 몫이 정확히 정해지는 것이 28이다. 같은 보고서에서 `NI_P = NI_T`가 확인되는 것이 6이다. 나머지 26은 연결 몫만 있다.
  - 지배주주 순이익 줄 없이 계속 · 중단 지배 몫만 주는 보고서가 5다(036540 · 솔브레인홀딩스 · 스피어).
  - IFRS 미국 발행사는 VEON이 지배 몫을 `…AttributableToOrdinaryEquityHoldersOfParentEntity` · `IncomeFromDiscontinuedOperationsAttributableToOwnersOfParent`로 준다(반증 렌즈 밖).

### 1c. 지배 몫을 못 정하면 무엇을 내나 ★

- 「넷 다 연결 계속영업」(v1 ④)은 폐기한다. 이 폴백은 지배 몫이 미정인 창에서만 켜지는데, 그런 창은 비지배가 있는 회사에서만 생긴다(비지배가 없으면 「비지배 없음」 갈래가 이미 정한다).
  - 그래서 「연결 = 지배」라는 ADR-0159 폴백의 근거가 서지 않는 집합으로 옮겨 간다(폴백 근거의 이식).
  - 그 결과 SK H1'26은 3.1배, 두산은 +64%, QSR은 PER 분자가 +30%가 된다. D · CARR · HON · C는 가능 구간 [NIC − DI_T, NIC] 밖으로 나간다.
- 「지금 값 유지(NIC · 중단 포함)」도 버린다. 그 오차가 |DI_T| 안이라는 보장(DI_P가 0과 DI_T 사이)이 2,996칸 중 2,682(89.5%)에서만 선다.
- **그래서 지배 기저 지표(PER 계열 · EPS 계열)는 미표시한다**(진짜 한계 → 미표시).
  - 지금 창 기준으로 미국 us-gaap 14종목(APLD · C · CARR · DHR · DVA · GE · HON · KEY · KEYS · OXY · PFE · SWX · TEL · WMS)이 미표시된다. IFRS는 6종목(AZ · BVN · HUBC · IHS · MRX · TNMG)이다. 국내는 26보고서에 걸린 종목이 상한이다.
  - 미국 14 중 Q4 재분류 몫과 지배 분할 몫의 비중은 드라이런 1이 가른다.
- **ROE · ROE_MIN_3Y는 연결 짝**(연결 계속영업 ↔ 총자본)으로 간다. 분자와 분모가 같은 기저라 정당하고, 지금 `roeBasis`의 두 번째 짝과 같다.
- 지배주주 순이익 컬럼 자체가 비어 이미 연결로 가는 창(기존 ADR-0159 폴백)은 그 폴백 안에서 연결 계속영업을 쓴다 — 기존 갈래를 넓히지 않는다.

### 1d. 소비자 ★(`grep` 전수 · 코드 · 시험 · 펜스)

- **계산기 17개.** 입구는 `FinancialMath.sumControllingNetIncome` · `controllingNetIncomeOf` · `PeriodSeries.controllingTtm` · `roeBasis`(둘) · `sumTrailingFourQuarters(…::netIncome)` · `effectiveTaxRateOf` · `pretaxIncomeOf`다.
  - PER · PER_MEDIAN_5Y(PER_DISCOUNT_5Y가 뒤따른다) · EPS_TTM · EPS_GROWTH_TTM_YOY · EPS_CAGR_5Y · PEG(PER과 성장을 받는다)
  - ROE · ROE_MIN_3Y · ROA · NET_MARGIN
  - **EFFECTIVE_TAX_RATE · ROIC_AFTER_TAX · ROIC_AFTER_TAX_AVG_5Y · ROIC_AFTER_TAX_MIN_5Y**(부호 판정 `PBT − NI` · 폴백 `NI + tax` — 반증 렌즈 밖)
  - ACCRUAL_RATIO · DIVIDEND_PAYOUT · EXEC_PAY_TO_NET_INCOME
- **계산기 밖**: 원장 `eps`(기본 EPS · 중단 포함)와 맞대는 자리가 셋이다. 그래서 `net_income` · `net_income_controlling`의 뜻을 바꾸지 않는다.
  - `GovernanceFactDeriver:236 · 290`(배당 주식수 20% 게이트)
  - 훅 `guard-rebalance-on-dirty-rank.sh:102`
  - ADR-0126 축척 판정
- **화면 · 문구**
  - `StockReader:171` → `FinancialsPanel.vue:109`(재무 탭은 `net_income`만 그린다)
  - `StockFinancialsAssembler`
  - 계산기 메타 설명 10개(「순이익」 · 「당기순이익」)
  - 미표시 사유 `IndicatorUnavailabilityConfig:71`(「최근 4개 분기 순이익이 적자라」)
- **스냅숏**: `ops/apply-window/02-snapshot.sh` · `ops/apply-window/q4v/02-snapshot.sh`(행 md5 — 새 컬럼이 md5를 바꾼다)
- **시험**: `ControllingNetIncomeTest` · `FinancialFiguresFixture` · `IndicatorRunnerTest` · `FinancialWriterTest` · `SecEdgarFactsSweepTest` · `FourthQuarterControllingTest` · `SecEdgarControllingQ4GateTest` · `SecEdgarIncomeBasisTest` · `FullStatementRefillJobTest` · `ReadApiTest` · `StockFoldTest`(새 컬럼은 `financial_statement` 행에 붙어 접기 규칙이 따로 필요 없는지 시험이 답한다)

## 2. 설계

### ① 저장 — 새 컬럼 둘, 기존 두 컬럼의 뜻은 안 바꾼다

- `discontinued_income`(연결 · 세후 · 비지배 몫 포함) · `discontinued_income_controlling`(지배주주 귀속)
- **뜻**
  - NULL = 그 행을 이 규칙으로 읽은 적이 없다.
  - **0 = 읽었고 중단영업 줄이 없다**(어댑터 판정 · 명시 저장).
  - 연결 값은 있고 지배가 NULL이면 = 원장이 지배 몫을 안 정해 준다.
- 0을 명시해 「안 읽음」과 가른다(반증 렌즈 밖 7). 두 컬럼은 `overwritable`이라 규칙이 바뀌어 0이 되면 옛 값을 고친다.
  - 「지배 미정」의 NULL은 `assertedAbsent`에 넣어 옛 값을 지운다(ADR-0159 뒤 5,366칸 선례).
- 마이그레이션은 시험 전용 `V70_2__discontinued_income.sql`이다. 관문 직전에 `main` 마이그레이션으로 옮긴다.

### ② 채우는 순서

**미국**(`SecEdgarFacts` · `gate.us` 뒤 · 새 `SecEdgarColumn` 셋 — 연결 · 지배 · 비지배 몫. 분기도 읽는다. 기존 `DISCONTINUED_OPERATIONS`(`profitBeforeTax`의 회사 단위 게이트)는 건드리지 않는다)
- **연결**: 아래 순서로 처음 서는 값을 쓴다.
  1. `IncomeLossFromDiscontinuedOperationsNetOfTax`
  2. `DiscontinuedOperationIncomeLossFromDiscontinuedOperationNetOfTax`
  3. 구성요소만 있으면 `NI_T − CO_T`. 단 그 값이 구성요소 합과 같을 때만(증인)
  4. 그 판까지 중단 태그를 하나도 안 냈으면 0
  5. 그 밖은 NULL
- **지배**: 아래 순서로 처음 서는 값을 쓴다. 항등식 갈래만 두고 가정 갈래는 두지 않는다(1b).
  1. `…AttributableToReportingEntity`
  2. `연결 − …AttributableToNoncontrollingInterest`
  3. `NIC − IncomeLossFromContinuingOperations`. 그 행에 중단 연결 몫 ≠ 0이 있을 때만 쓴다(이름이 계약이 아니다)
  4. `NIC − NetIncomeLossFromContinuingOperationsAvailableToCommonShareholdersBasic`. 같은 행 `NetIncomeLossAvailableToCommonStockholdersBasic` = NIC일 때만 쓴다(우선주 배분 0의 증인)
  5. `NIC − (CO_T − IncomeLossFromContinuingOperationsAttributableToNoncontrollingEntity)`
  6. 그 기간 시작 뒤로 비지배 손익을 안 냈으면(운영 `noNoncontrollingIncomeBy`) 연결 몫
  7. 연결이 0이면 0
  8. 그 밖은 NULL
- **IFRS 별칭**(`TaxonomyFrame.ALIASES`): 아래 개념을 위 us-gaap 열쇠에 잇는다(반증 렌즈 밖 3).
  - `ProfitLossFromDiscontinuedOperationsAttributableToOrdinaryEquityHoldersOfParentEntity`
  - `IncomeFromDiscontinuedOperationsAttributableToOwnersOfParent`
  - `ProfitLossFromDiscontinuedOperationsAttributableToNoncontrollingInterests`
  - `IncomeFromContinuingOperationsAttributableToOwnersOfParent`
  - `ProfitLossFromContinuingOperationsAttributableToOrdinaryEquityHoldersOfParentEntity`
  - `ProfitLossFromContinuingOperationsAttributableToNoncontrollingInterests`
- **판**: 중단 값은 **그 공시가 같은 기간의 순이익도 실을 때만** 그 공시에서 받는다. 순이익 없이 중단만 실은 비교열은 순이익 판과 섞이므로 받지 않는다. 새 두 컬럼은 `conflicts` · `storedDiffers`에 든다(관문 켬일 때만). `reportEvidence`에는 들지 않는다(ADR-0143의 얕은 보고서 자리).
- **Q4**: `FY − 같은 해 9개월 누계`(Q3 공시의 판)로 짓는다. 다음 셋은 Q4를 NULL로 둔다.
  - 9개월 공시에 중단 줄이 없는데 FY ≠ 0이면(Q3 뒤 재분류 — 그 판 시점에는 원장이 쪼개 주지 않는다)
  - 9개월이 없으면
  - 어느 쪽이든 지배가 미정이면
  - 다음 해 비교열로 뒤 판 Q4를 채우는 길은 P3a(분기 새 판마다 Q4를 다시 유도)와 같은 설계다. 그때까지 NULL이다.

**국내**(`DartFullAccountMapper` · `DartFullStatementAdapter` · 새 `gate.kr` 뒤)
- **연결**: `ifrs-full_ProfitLossFromDiscontinuedOperations` → 보고서를 읽었고 줄이 없으면 0(K-IFRS 1105 — 중단영업이 있을 때만 갈라 표시한다).
- **지배**: 아래 순서로 처음 서는 값을 쓴다.
  1. `IncomeFromDiscontinuedOperationsAttributableToOwnersOfParent`
  2. `ProfitLossFromDiscontinuedOperationsAttributableToOrdinaryEquityHoldersOfParentEntity`
  3. `연결 − …AttributableToNoncontrollingInterests`
  4. `지배주주 순이익 − 계속영업 지배(두 ID)`
  5. 같은 보고서에서 `지배주주 순이익 = 연결 순이익`이 확인되면 연결 몫
  6. 그 밖은 NULL
- **함께 고친다**: 지배주주 순이익 줄이 없고 계속 · 중단 지배 몫이 있으면 `net_income_controlling` = 둘의 합으로 한다(보고서 5). `reload-gate.md` 국내 행의 이 꼴만 닫고, 「비표준 계정 행」 꼴은 그 행에 남긴다.
- **Q4**: `FY − Q3 보고서 누계(thstrm_add_amount)`로 짓는다. Q3 보고서에 줄이 없는데 FY ≠ 0이면 NULL이다(카카오 · CJ제일제당 꼴).

### ③ 지표 정의

| 묶음 | 지표 | 분자 |
|---|---|---|
| **이익력 — 계속영업** | PER · PER_MEDIAN_5Y · EPS_TTM · EPS_GROWTH_TTM_YOY · EPS_CAGR_5Y · (PEG) | 지배 계속영업(NIC − 지배 중단) · 미정이면 **미표시** |
| | ROE · ROE_MIN_3Y | 지배 계속영업 ↔ 지배주주 자본, 미정이면 연결 계속영업 ↔ 총자본 |
| | ROA · NET_MARGIN | 연결 계속영업(NI − 연결 중단) |
| | EFFECTIVE_TAX_RATE · ROIC_AFTER_TAX(셋) | 부호 판정 · 폴백의 순이익을 연결 계속영업으로 — 세전이익(미국 태그 · 국내 `ProfitLossBeforeTax`)이 계속영업이라 `PBT − 계속NI = 법인세`가 항등식이다 |
| **회계 항등 — 합계 유지** | ACCRUAL_RATIO | 연결 순이익(영업현금흐름도 중단 포함 합계라 짝) |
| | DIVIDEND_PAYOUT · EXEC_PAY_TO_NET_INCOME | 지배주주 순이익(그 기간 주주에게 귀속된 것 전체 — 배당 주식수 게이트의 `eps`와 같은 뜻) |

- **이유**: 중단영업은 정의상(ASC 205-20 · K-IFRS 1105) 이어지지 않는 손익이고, 매각 차익을 싣는다. NET_MARGIN의 분모 매출은 이미 계속영업뿐이다(MLM은 중단 매출을 `DisposalGroupIncludingDiscontinuedOperationRevenue`로 따로 낸다).
- **행 단위 계속영업 = 순이익 − 중단**이다. 중단이 NULL(안 읽음)이면 지금과 같은 값이 나온다(관문 꺼짐과 관문 전 행). 국내에서 정기 FSR이 안 닿은 행이 이 갈래를 탄다 — 중단영업 40종목 중 347700의 15행(반증 렌즈 밖 7 · 드라이런 2가 다시 센다).

### ④ 창 규칙

- **지배 계열**: 네 분기(또는 ANNUAL 기저의 FY)가 모두 지배 계속영업을 가지면 그것을 쓴다. 하나라도 미정이면 NULL(PER 계열 · EPS 계열 미표시)이다.
- EPS 성장의 offset 창, PER_MEDIAN의 과거 판, EPS_CAGR의 끝점도 창마다 같은 규칙을 탄다. 기저가 창마다 섞이지 않는다 — 연결로 가는 갈래가 없다.
- **지배주주 순이익 컬럼이 비어 이미 연결로 가는 창**(기존 `sumControllingNetIncome` 폴백)은 연결 계속영업으로 간다.

### ⑤ 관문

- **미국 몫**은 `stockscreener.gate.us` 뒤에 둔다(어댑터 · `TaxonomyFrame` 별칭 · Writer 컬럼 목록).
- **국내 몫**은 새 `stockscreener.gate.kr` 뒤에 둔다. 시장마다 관문 하나다(2026-10-04 사용자 결정 「시장별 관문」). 첫 국내 관문 묶음이므로 `GATE-SETTINGS-PLAN.md` 「v3로 고칠 것」 중 이 묶음에 닿는 넷을 함께 한다.
  - `GateSettingGuard`가 키 목록(us · kr)을 같은 규칙(허용 출처 · 매 기동 INFO)으로 본다.
  - `batch_execution`에 실행 시점의 관문 상태 열(nullable · `scope` 선례 · 시험 전용 마이그레이션 `V70_2`에 함께)을 둔다. 국내 잡이 어느 상태로 돌았는지 SELECT로 답한다.
  - **판독기 · 계산기는 행의 시장으로 고른다** — 그 행 시장의 관문이 켜졌을 때만 새 컬럼을 읽고 쓴다. 시험: 「us 켬 · kr 끔에서 국내 행 무변화」 · 「kr 켬 · us 끔에서 미국 행 무변화」.
  - 수명: 시장 관문을 통과하면 그 시장의 설정과 꺼진 갈래를 걷는다. 두 시장이 모두 통과하기 전까지 판독기의 시장 분기는 남는다.
  - 국내 관문 절차(`reload-gate.md` 「순서」에 국내 몫)와, 「`--tickers=<국내>` 백필이 미국 파생층까지 다시 내는지」(reload-gate.md:114)는 국내 관문을 열기 전에 잰다. 이 묶음의 구현 · 배포(관문 꺼짐)는 그것을 기다리지 않는다.
- **문구**: 계산기 메타 설명 · 미표시 사유 카드의 「순이익」 → 「계속영업 순이익」 변경은 기본값을 뒤집은 jar와 같은 커밋에 넣는다. 설명은 정적 문자열이라 설정 뒤에 둘 수 없어, 관문 전에 바꾸면 꺼진 계산과 문구가 어긋난다.
- **재무 탭**: 관문이 켜지면 재무 탭에 중단영업 줄을 더한다(`StockFinancialsResponse.discontinuedIncome` · `wire-contract.json` · `types.ts`). 재무 탭 순이익과 PER · EPS가 설명 없이 어긋나지 않게 한다.
- **「무해한 선행 배포」**: 아래 순서로 넣는다. 꺼진 출력은 `main`과 바이트까지 같다.
  1. 계산기 · 판독기 · 시험 마이그레이션 · 관문 설정 둘(새 컬럼이 0행인 동안 값이 안 바뀐다)
  2. 미국 어댑터 · Writer · 별칭
  3. 국내 매퍼 · Writer
  4. 재무 탭 API(관문 켬에서만 필드를 채운다)

### ⑥ 기록

- 새 ADR-0184: 정의 · 컬럼 · 지배 미정 미표시 · Q4 · 버린 대안(1b 가정 · 1c 두 폴백 · v1 「분기 줄 없음 = 0」 Q4)
- ADR-0159 §지표표에 「계속영업」 열을 더한다.
- `dart-financial.md` 「계속영업손익 1/8」 절(전수 수로) · `sec-edgar-financial.md`(중단영업 컬럼 · GE 확장 태그)
- `reload-gate.md` F2b 행 · 국내 「계속+중단영업으로 나뉜 값」 행 · 국내 관문 절차

## 3. 드라이런

1. **미국 0콜 스윕**(`SecEdgarFacts` · `.factcache` + IFRS 130)
   - 꺼짐 = `main`(md5)
   - 켬 = `main` 켬 대비 새 두 컬럼만 채워지고 기존 컬럼은 0칸 변한다.
   - 새 판(비교열 재분류)이 몇 개 생기는지 센다.
   - 1c의 미표시 종목을 「Q4 재분류」와 「지배 분할」로 가른다.
   - 이름 종목(PAA · PAGP · MLM · KMB · GE · TFX · SWX · VEON)을 손으로 대조한다.
2. **국내 0콜 스윕**: 재료를 넓혀 받는다 — 편입 350 × 2025 Q1 · Q3 · 2024 사업 · 2024 Q1~Q3, 약 2,100콜(DART 일일 한도 안). 그 원응답을 매퍼 · Q4 유도에 먹이는 스윕을 만든다(지금 국내 전체 재무제표 스윕은 지배주주 자본만 본다). 보는 것은 셋이다.
   - 두 컬럼
   - 5보고서의 `net_income_controlling`
   - FSR이 안 닿은 행 수
3. **계산기**: 단위 시험과 작은 DB 하나로 본다.
   - 단위 시험: 지배 미정 미표시 · ROE 연결 짝 · Q4 NULL · 합계 묶음 불변 · 세율 부호 · 시장별 관문
   - 작은 DB: PAA · PAGP · MLM · GE · SWX · 솔브레인홀딩스 · 카카오의 행 · 봉만 담는다. 관문 켬 · 끔으로 지표를 뽑아 대조한다.

## 4. 순서

구현(계산기 먼저) → 드라이런 1~3 → 커밋 · 배포(관문 꺼짐 · 값 무변화).

## 5. 반증 v1 (두 렌즈 · 2026-10-23) — 둘 다 「고쳐서 간다」 · 지적을 재서 닫았다

| 지적 | 반증 근거 | 내 측정 · v1.1에서 |
|---|---|---|
| 창 폴백 「넷 다 연결 계속영업」이 비지배 있는 회사로 옮겨진다(폴백 근거의 이식) | 렌즈 안 A: SK 3.1배 · 두산 +64% · QSR +30% / 렌즈 밖 2: D · CARR · HON · C가 가능 구간 밖 | 폐기 → 지배 기저는 미표시 · ROE는 연결 짝(1c · ③ · ④) · 잃는 수 미국 14 · IFRS 6 · 국내 26보고서 상한 |
| Q4 「분기 줄 없음 = 0」이 재분류 몫을 Q4로 몬다 | 렌즈 안 B · 밖 1: MLM 94 대 46 · SWX −63.4 대 −1.5 · 솔브레인홀딩스 445.2B 대 424.4B | `FY − 9개월` · Q3 뒤 재분류는 NULL(최근 57 / 8 · 5년 333 / 113) · 뒤 판은 P3a |
| 갈래별로 검증되지 않았다(묶어서 판정) | 렌즈 안 C: 갈래 일치 92.6% · 85.6% · 88.2% | 같은 공시 대조로 다시 쟀다(1b 표) · 항등식과 가정을 어긋남의 성질로 가른다(자기모순 275/275 대 가정 오류 104/144) |
| 수가 운영 술어가 아니고 실행마다 바뀐다 | 렌즈 안 D: `PYTHONHASHSEED`에 따라 FY 154/155 | `measure2.py` — 운영 `noNoncontrollingIncomeBy` · 잔차 Q4 · (end, start) 정렬 · 두 씨앗 출력 diff 0 |
| 국내 재료가 두 보고서뿐이다 | 렌즈 안 E · 밖 | 1a를 하한으로 적었다 · 드라이런 2가 Q1 · Q3 · 과거 연도를 받는다 |
| 「NI는 재분류에 불변」은 일반 사실이 아니다 | 렌즈 안 F: 26칸 | 문장을 걷고, 「순이익과 같은 공시일 때만 중단을 받는다」로 바꿨다(② 판) |
| 부재 판정을 Writer가 못 지운다 | 렌즈 안 G · 밖 7 | 0 명시 저장 + `assertedAbsent`(① 뜻) · `annualOnly`인 기존 컬럼은 두고 새 컬럼으로 분기를 읽는다 |
| IFRS 모집단 · 별칭 | 렌즈 밖 3: VEON 지배 몫 버려짐 | IFRS 130을 받아 쟀다(1a · 1c) · 별칭 여섯(②) |
| 세율 계열 소비자 넷 | 렌즈 밖 4: 부호가 뒤집히는 행 9 | ③ 계속영업 묶음에 넣었다 |
| 화면 문구 · 재무 탭 | 렌즈 밖 5 | ⑤ 문구는 관문 jar 커밋 · 재무 탭 중단 줄 |
| `gate.kr` 신설이 설정 분리 요건과 안 맞물린다 | 렌즈 밖 6 | ⑤ — v3 넷을 이 묶음에 싣는다 · 국내 관문 절차는 국내 관문 전에 잰다 |
| 스냅숏 소비자 누락 | 렌즈 안 H: `q4v/02-snapshot.sh` | 1d에 더했다 |
| 순위 | 렌즈 밖: F2b가 반기보다 앞인 근거는 선다 | 그대로 · Q4 뒤 판 채움은 P3a와 함께 |
