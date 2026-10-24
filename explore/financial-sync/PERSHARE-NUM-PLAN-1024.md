# 주당 지표의 분자를 보통주 몫으로 — 클래스 축 엔진이 공시마다 정한다 (계획 v1 · 2026-10-24)

`docs/reload-gate.md` 「주당 지표의 분자 · 분모가 보통주 몫이 아니다」 행의 **분자** 부분. 분모(PBR · BPS의 우선주 · GP 자본)는 다음 계획이다.
★ = 내가 이 세션에 쟀다. 모든 수는 0콜(팩트 캐시 · 운영 SELECT) 또는 표시한 콜 수다.

## 0. 질문

미국 주당 지표의 분자는 `FinancialMath.PeriodSeries.controllingTtm` 한 자리다 — 지배주주 귀속 계속영업 순이익(`NetIncomeLoss` 계열 · F2b 뒤 계속영업)의 4분기 합.
분모는 보통주(클래스 축 N · ADR-0179)다. 원장의 주당순이익 정의(ASC 260 · IAS 33)는 **보통주 귀속 이익**(지배주주 − 우선주 배당 − 참가증권 배분 · LP는 GP · IDR 배분을 뺀 LP 몫)이고, 원장은 그 값을 준다.

- 그 짝이 안 맞는다 — 분자에 우선주 · 참가증권 · GP 몫이 들어 있다. 가짜 한계 후보(원장이 정직하게 주는데 우리가 안 읽는다).

## 1. 실측 ★

**모집단**: 운영 현 편입 `US_TOP1000` 1,000(탐색 `lp/cur.txt` · 10-23 SELECT) · 팩트 캐시 + LP 공시자 링크 디렉터리 1,271(캐시 날짜 2026-09-15 전후) — 그중 캐시 994.

### 1a. 원장이 보통주 귀속을 따로 주는가 · 크기

물음: companyfacts `NetIncomeLossAvailableToCommonStockholdersBasic`(NIC) · `NetIncomeLossAllocatedToLimitedPartners` · `EarningsPerShareBasic` · LP 단위 EPS 태그 · `WeightedAverageNumberOfSharesOutstandingBasic` 계열 — 같은 접수번호 · 같은 기간 맞대기(10-K · 10-Q만, 기간마다 처음 낸 공시).

- 최신 FY(2024-06 뒤 기말) NI가 있는 924종목 중 **NIC를 실은 것 334**. 그중 NI와 다른 것:
  - 1% 미만 72 · 1~5% 55 · 5~20% 28 · **20% 넘음 17**(태그 일치 162).
  - 부호가 뒤집힌다(지배 NI 양수 · 보통주 몫 음수): **HPE** 57M 대 −59M · **APG** 302M 대 −288M · **LYV** 496M 대 −55M(상환가능 비지배 가산) · **JXN** 27M 대 −17M — 지금 PER이 나오는데 원장 기준으로는 적자다.
  - 크기 순(FY): CAI · BLLN 0.61 · FIGR 0.54(참가증권) · PRI · MCHP 0.48(우선주 111M) · CELH · QXO · **TPG 0.33**(참가증권 61M · 우선 −44M) · ALB 0.33 · BOX · VST 0.20 · ARES 0.19 · GM 0.18 · BA 0.15 · COF · BEN · MSTR · C 0.09 · 지방은행 · 대형은행 5~8%(BAC · WFC · GS · MS · SCHW · MET · ET 6%).
- NIC가 없는 588종목은 EPS × 가중평균이 NI와 반올림 안에서 닫힌다(EPS 반 센트 + 가중평균 끝자리 0 반폭): **닫힘 497 · 1% 미만 60** · 그 밖은 단위 오태깅(천 단위 W — MCD · RGEN · NTNX) 또는 진짜 배분(TDG 참가증권 10% · COGT · SNEX 3.6% · CHRD 3.8% · AA 1.5%).
  - 즉 **NIC가 없다는 것은 「배분 0」이 아니다** — 원장의 EPS가 배분을 말하는데 NIC를 축 없이 안 실은 발행사가 있다(TDG · GWW · BAH · J — 참가증권 태그만 따로).

### 1b. 최근 TTM 창(최근 4분기 + Q4는 FY에서)에서 사다리가 서는가

사다리(공시마다 · 기간마다): ① NIC(또는 LP 배분) 선언 → ② NI가 EPS × 가중평균과 닫힘 → ③ NI − 선언 배분(우선주 배당 · 참가증권 태그 합)이 닫힘 → 못 정함.
- 994종목 중 **941 섬** · 못 정함 53: 「안 닫힘」 28(단위 오태깅 W를 이 도구가 못 고친 것 MCD · RGEN · NTNX · LOAR · CYTK · DDS 포함 — 엔진은 `unitChecked`가 고친다) · 「EPS 또는 W 없음」 24(클래스 축에만 싣는 TSN · HSY · DUOL · CBSH · GLXY · CWEN · PAGP · BKR · SYRE · VRSN · 10-K에 Q4 3개월을 EPS 없이 실은 칸) · 둘 다 1.
- 그래서 **companyfacts 축 없는 값만으로 지으면 클래스 축 발행사가 빠진다** — 엔진은 그 발행사의 n을 인스턴스 클래스 축에서 이미 정한다(ADR-0179 · 0189).

### 1c. LP

- SUN 2025-09 10-Q: NI 133M · LP 단위 EPS 0.64 · `PreferredStockDividendsIncomeStatementImpact` 4M · `IncentiveDistributionDistributionPerYear` 43M · NIC는 2025-12까지만.
- CQP 2026-06 10-Q: NI 1,161M · 「LP · GP 단위당」 EPS 2.14 × 단위 484M = 1,036M(NI의 0.89) · NIC 없음 · 참가증권 미배분 650M — NI − 650M = 511M(선언 분배)이라 ③은 안 닫힌다. **EPS × n만 원장의 보통 단위 몫을 말한다.**
- ET · WES: `NetIncomeLossAllocatedToLimitedPartners` 현재 · MPLX · EPD: NIC 현재 · PAA · KRP: LP 단위 EPS만.
- LP 단위 EPS 태그(`NetIncomeLossPerOutstandingLimitedPartnershipUnit…`)는 클래스 축 EPS 목록 밖이다(ADR-0187 §버린 대안 — 분모 판정에 넣으면 KRP 15판이 숨는다). **분자 확인 전용 재료**로만 받는다.

### 1d. 엔진에 재료가 이미 있다(코드)

- `IssuerFacts`(관문 켬 클래스 축 재료): (접수번호, 기간)마다 `eps`(기본) · `declaredNumerator`(NIC — IFRS는 `TaxonomyFrame`이 `ProfitLossAttributableToOrdinaryEquityHoldersOfParentEntity`를 별칭) · `proxyNumerator`(NI · ProfitLoss) · `weighted`(단위 확인 뒤).
- `IssuerTimeline`이 공시마다 분모 n을 정한다(10-Q 3개월 · 10-K는 Q4 · 20-F · 40-F 연간) — `strictCloses`가 EPS 반 센트 + 분자 끝자리 반폭으로 이미 폐합을 잰다.
- 행 → 공시: `ShareClassLine.read(report_code)`가 행의 자기 공시 판정을 읽어 `FinancialFigures.weightedShares`에 n을 싣는다. 분자를 같은 자리로 실으면 **분자 · 분모 기저를 한 계산에서 정한다**(ADR-0159 §규칙).

### 1e. 소비자(grep ★)

- `controllingTtm` → `PER` · `EPS_TTM` · `PER_MEDIAN_5Y` · `EPS_CAGR_5Y` · `EPS_GROWTH_TTM_YOY`. 의존: `PEG` · `PER_DISCOUNT_5Y` · 백분위 · 업종 상대.
- `controllingNetIncomeOf(annual)` → `DIVIDEND_PAYOUT`(미국 분자 = 보통주 DPS × 보통주 수 — `AlpacaGovernanceFactMapper` — 그래서 분모도 보통주 몫이어야 짝) · `EXEC_PAY_TO_NET_INCOME`(주당 지표 아님 — 안 바꾼다).
- `net_income` · `net_income_controlling` 컬럼의 뜻은 안 바꾼다 — 원장 `eps`와 맞대는 소비자 셋(배당 주식수 20% 게이트 · 순위 오염 훅 · ADR-0126 축척)이 그대로다.
- `financial_share_class_filing` 소비자: `ShareClassStore` · `ShareClassDeriver` · `StockFold` · 시험 둘(`ShareClassAxisReadTest` · `ShareClassHoldReplayLiveTest`) · 표는 관문 시험 마이그레이션 `V70`(운영에 아직 없다).

## 2. 계획 — 관문 뒤(`stockscreener.gate.us`)

### 2a. 엔진(`IssuerTimeline` · ENGINE_VERSION 4 → 5)

**판정이 있든 없든 그 발행사 companyfacts의 모든 공시**(정기 · 정정 10-K/A · 10-Q/A · 6-K)마다, 그 공시의 **행 기간**(10-Q 3개월 · 10-K Q4 · 20-F · 40-F 연간 · 6-K는 그 공시의 당기 기간)과 10-K는 **연간**에 대해 보통주 귀속 이익 하나씩을 정한다. 사다리(첫 번째로 서는 것):
1. **선언**: NIC(축 없음 · IFRS 별칭 포함).
2. **NI 폐합**: `NetIncomeLoss`(ProfitLoss 아님)가 그 기간 기본 EPS × n과 `strictCloses` 안에서 닫힘 → NI(배분 0의 증인).
3. **LP 배분 폐합**: `NetIncomeLossAllocatedToLimitedPartners`가 LP 단위 기본 EPS × n과 닫힘.
4. **원장 주당이익**: 그 공시의 판정이 지금 경로(d · 앵커 아닌 T)이고 기본 EPS(LP는 LP 단위 기본 EPS)와 그 판정의 n이 있으면 EPS × n — 같은 공시 축 없는 값 둘이라 범위가 같다. 클래스 축 앵커(T 클래스) · 판정 없는 공시는 이 갈래를 안 탄다(n이 검증되지 않았거나 축 없는 EPS가 어느 클래스인지 모른다).
5. 못 정함 → 비움.

- 재료: 그 (공시, 기간)의 companyfacts 값(`IssuerFacts`) → 없으면 그 공시 인스턴스의 축 없는 값(`ClassFacts` — `instanceCloses`가 이미 읽는 NIC · NI · EPS). 보충만으로 쌓인 발행사(AUGO · AERO · MAKO · SCZM · TGE — ADR-0190)는 companyfacts가 비어 인스턴스만 있다.
  - 수집(`ShareClassCollector`)은 지금 정기 공시 앵커 · 분모 후보의 인스턴스만 받는다. 판정이 없는 공시(정정 · 6-K · 20-F/A)는 companyfacts에 그 기간 NI가 없을 때만 인스턴스를 더 받는다(관문 날 한 번 · 수는 드라이런 1에서 센다 — AUGO는 유일한 XBRL 공시가 20-F/A다).
- n: 판정이 있으면 행 기간은 판정의 분모(10-K는 Q4 n), 연간은 그 공시 연간 가중평균(단위 확인 뒤). 판정이 없는 공시(정정 · 6-K)는 그 공시 같은 기간 가중평균(단위 확인 뒤)을 **폐합(2 · 3)에만** 쓴다.
- Q4는 `FY − 9개월`(Q3 10-Q의 9개월 · 같은 사다리) → 없으면 `FY − 3개월 셋`. 하나라도 비면 Q4도 비운다.
- 숨김 판정(status X)의 공시도 분자를 정한다 — 주식수가 숨으면 어차피 지표가 안 나온다.
- 갈래를 남긴다(decl · ni · lp · eps · none).

### 2b. 저장

- 판정 표(`financial_share_class_filing`)에 안 싣는다 — 그 표는 정기 공시의 T · d 판정만 담고(`ck_…_kind`), `ShareClassLine.read`가 자기 판정을 찾으면 그 판정을 행에 입힌다. 정정 · 6-K를 그 표에 넣으면 주식수 판정이 바뀐다.
- 관문 시험 마이그레이션 `V70_5__common_income.sql`: 새 표 `financial_common_income(stock_id, accession, scope ∈ {ROW, ANNUAL}, value numeric NULL, branch varchar(8), engine_version, derived_at · PK (stock_id, accession, scope))`. V70 · V70_1~4와 같이 먼저 여는 관문 직전에 main으로 옮긴다.
- 유도(`ShareClassDeriver`)가 판정과 같은 판에 같은 발행사 단위로 갈아 넣는다. `StockFold`는 판정 표처럼 같이 옮긴다.
- 지표 읽기(`IndicatorDataReader`)가 판정 줄과 함께 그 종목의 이 표를 읽어 행의 `report_code` 접수번호로 붙인다.

### 2c. 지표

- `FinancialFigures`에 `commonIncome`(행 기간 · FY 행은 연간 값)과 「이 규칙으로 읽었다」 표지(클래스 축 줄이 있는 행 = 관문 켬 미국)를 더한다.
- `continuingIncomeCommon()`: 표지가 없으면 `continuingIncomeControlling()` 그대로(관문 꺼짐 · 국내 — 바이트 같음). 있으면 `commonIncome − 지배 중단`(F2b 재료 · 중단 규칙이 없으면 `commonIncome`) · `commonIncome`이 비면 비움.
- `controllingTtm`이 그것을 합한다. 창 단위 연결 폴백(`sumControllingContinuingIncome`의 둘째 갈래)은 표지가 있는 창에서는 안 탄다 — 연결 몫이 보통주 몫이라는 근거가 없다.
- `DIVIDEND_PAYOUT`: 표지가 있는 연간 행은 `commonIncome`(중단 포함 합계 — ADR-0184의 이 지표 기저).

### 2d. 꺼진 출력

- 관문 꺼짐은 클래스 축 줄이 없어(`IndicatorDataReader.mapFigures`의 `line == null`) 새 표지가 안 선다 → 지표 바이트 같음.
- 엔진 · 저장은 관문 켬에서만 돈다.

## 3. 드라이런(가장 싼 재료부터 · DB 생성 없음)

1. **0콜 스윕** `SecShareClassSweepTest`(캐시 1,271 + IFRS USD 130 · 틀 켬 · 보충 목록): 공시마다 `common_income` · `common_branch`를 TSV에 더 찍는다. 기존 열은 바이트 같음(md5).
2. 같은 원자료로 `SecEdgarFactsSweepTest` 관문 켬 TSV(행 · `report_code` · 지배 NI · 중단 지배)와 이어 붙여, 현 편입 최신 TTM 분자 변화와 새로 비는 창을 센다(파이썬 · 0콜).
   - 수용: 1a의 큰 변화가 재현된다(TPG · MCHP · VST · 은행 · HPE · APG 부호) · 「원장이 말하는데 우리가 비움」이 0 또는 이유가 적힌 목록.
3. 단위 시험: 갈래 다섯 · Q4 두 길 · 꺼짐 무변화 · 대조군(사다리 갈래 하나씩 되돌림)이 붉다.

## 4. 재지 않았다 · 사정거리

- ★ 운영(관문 꺼짐) 현 편입 최근 분기 4행 중 정기 10-Q · 10-K가 아닌 행: 6-K 20행 · 6종목(AER · BNT · CAMT · DSGX · TSEM — 반기라 분기 계열이 안 서 연간 계열로 간다 · ICLR은 6-K 분기) · 10-Q/A 2(AVAV · BWXT) · 6-K/A 1(ICLR) · 20-F 7(DOX · JHX Q4). 그래서 분자를 정기 판정 표가 아니라 공시마다 따로 정한다(2a · 2b). 관문 켬의 행 집합에서 그 수는 드라이런 2에서 다시 센다.
- 클래스 축 앵커(T 클래스)에서 NIC도 NI 폐합도 없는 공시 수 — 있으면 클래스별 EPS × W 합 갈래를 더할지 그때 정한다.
- 분모(PBR · BPS)의 우선주 · GP 자본 — 다음 계획.
- **국내** — 같은 성격이 더 크다(아래 5절). 사용자 결정(2026-08-30 · ADR-0098 「우선주 분모」)의 근거 문장이 걸려 있어 이 계획에 안 넣는다.

## 5. 같이 찾음 — 국내 우선주(사용자 판단)

- 국내 주당 지표 분자는 지배주주 순이익 전체이고 분모는 보통주다(ADR-0098). 지배주주 순이익에는 우선주 몫이 들어 있다.
- ★ DART `fnlttSinglAcntAll` FY2025 6콜(CFS · `ifrs-full_BasicEarningsLossPerShare` 보통주 · `dart_BasicEarningsLossPerSharePreferredStock`) 대 지배 NI ÷ 보통주 유통주식수(`SHARE_TOTALS` 2025-12-31):
  - 삼성전자 6,605 대 7,595(1.15배) · 현대차 36,088 대 46,507(1.29) · 미래에셋증권 2,680 대 3,600(1.34) · 두산 4,230 대 5,588(1.32) · 한화 4,072 대 5,369(1.32) · 아모레퍼시픽 3,416 대 4,033(1.18).
- ★ 현 편입 국내 350 중 보통주 밖 주식이 있는 것 51(운영 SELECT · `SHARE_TOTALS` 최신) · 그 비중 1% 이상 38 · 20% 이상 4(미래에셋 · 두산 · 현대차 · 한화). `SHARE_TOTALS`가 없는 편입 종목 수는 재지 않았다(하한).
- ADR-0098 §버린 대안의 「우리 시가총액이 보통주 기준이라 내부 일관성이 지금 쪽이 낫다」는 분자가 우선주 몫을 포함하는 한 서지 않는다 — PER = 보통주 시총 ÷ (보통주 + 우선주 몫 이익).
