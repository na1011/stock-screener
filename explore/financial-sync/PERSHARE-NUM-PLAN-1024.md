# 주당 지표의 분자를 보통주 몫으로 — 클래스 축 엔진이 공시마다 정한다 (계획 v2 · 2026-10-24 — v1은 반증 「다시 짠다」 · 6절)

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

## 2. 계획 v2 — 관문 뒤(`stockscreener.gate.us`)

### 2a. 값의 단위는 (공시, 기간)이고 저장하는 것은 「배분」이다

- ★ 행의 `report_code`는 그 기간의 최신 판 공시라, 한 접수번호가 여러 기간 행을 싣는다. GS 2024 Q1 행 = 2025 Q1 10-Q(`…25-000009` · 개정 1 · 공시일 2025-05-02). 현 편입 직전 TTM 행의 618/3,834 · 5년 창 2,620/18,792가 그 꼴이다(반증 안 1 · 밖 1).
- 그래서 엔진은 **IssuerFacts의 모든 (접수번호, 기간)**(당기 · 비교열 · 정정 · 6-K · 보충 공시)마다 보통주 귀속 이익 `common`과 **배분** `allocation = NetIncomeLoss − common`(그 키에 NI가 없으면 null)을 낸다. NI는 `NetIncomeLoss`(IFRS는 `ProfitLossAttributableToOwnersOfParent` 별칭)만 — `ProfitLoss`로 안 떨어진다(`strictCloses`를 그대로 안 쓴다).
- 행은 자기 (접수번호, period_start, period_end)로 붙인다. 행의 보통주 몫 = **행 지배 NI − allocation**(행 값의 판 · F2b 갈래를 그대로 쓴다). allocation이 null이고 common만 있으면 common.

### 2b. 사다리(키마다 · 첫 번째로 서는 것)

재료: 그 키의 companyfacts 값 → 없으면 그 공시 인스턴스의 축 없는 값(보충 공시 · 앵커 — 엔진이 이미 받는 것). EPS는 총 EPS 태그(`EarningsPerShareBasic` · `…BasicAndDiluted` · LP 단위 기본 EPS)만 — 계속영업 EPS(`IncomeLossFromContinuingOperationsPerBasicShare`)는 분자 사다리에서 안 쓴다(반증 안 7). W: 그 공시 당기 기간이고 판정이 지금 경로면 판정 n, 아니면 그 키의 가중평균이 그 공시 표지와 자릿수(`COVER_WITNESS_LOG`)로 맞을 때만(반증 안 8 — 비교열 · 연간 · 9개월에는 판정이 검증한 n이 없다).

1. **선언 NIC**(축 없음 · IFRS 별칭). EPS × W가 있으면 부호와 자릿수(10배)가 맞아야 받는다 — CTVA 2023 Q1 NIC 0.84 · PCVX 2023 부호 반대 · OKLO · AIG Q4'23 · VMI Q2'25(반증 안 6). EPS가 없으면 그대로 받는다(KKR · ARES · MGY · ASTS — 확인 못 함으로 갈래에 남긴다).
2. **선언 배분**: 그 키에 우선주 배당(`PreferredStockDividendsAndOtherAdjustments` · `DividendsPreferredStock` · `PreferredStockDividendsIncomeStatementImpact`) · 참가증권(`ParticipatingSecuritiesDistributedAndUndistributedEarningsLossBasic` · `UndistributedEarningsLossAllocatedToParticipatingSecuritiesBasic`) · GP 배분(`NetIncomeLossAllocatedToGeneralPartners`) 태그가 있으면 NI − 그 합이 EPS × W와 닫힐 때 그 값.
3. **NI 폐합**: 2의 태그가 그 키에 하나도 없을 때만 — NI가 EPS × W와 닫히면 NI(배분 0). 허용폭은 EPS 반 센트 × W + NI 끝자리 반폭. 반증 안 5의 거짓 증인 164 중 89는 2의 태그가 있는 키라 2가 먼저 받는다 · 남는 것은 원장 정밀도의 한계로 갈래에 남긴다.
4. **LP 배분 폐합**: `NetIncomeLossAllocatedToLimitedPartners`가 EPS × W와 닫힘.
5. **원장 주당이익**: 클래스 축 앵커가 아니고 EPS · W가 있으면 EPS × W(GWW · TDG 특별배당 분기 · CQP).
6. **클래스 합**: 클래스 축 앵커면 인스턴스의 클래스 구성원 Σ EPS_m × W_m(`IssuerTimeline`이 이미 읽는 em · wv) — NI와 닫히면 NI, 아니면 그 합(반증 안 3 — TSN · HSY · BKR · CWEN · PAA · PAGP · SYRE).
7. 못 정함 → 비움.

### 2c. Q4

- 어댑터와 같은 길(`SecEdgarFacts.buildQ4` — 원천 3개월, 없으면 FY − Q1 − Q2 − Q3):
  - 그 10-K 접수번호에 Q4 3개월 키가 있으면 그 키의 allocation.
  - 없으면 **allocation_Q4 = allocation_FY − Σ allocation_Q1..Q3**(같은 회계연도 세 분기 행의 키) — 배분은 더해진다. 하나라도 비면 비움.
- v1의 「FY − 9개월」은 버린다 — NIC 실린 FY 954 중 20이 FY의 1% 넘게 갈렸다(반증 안 4 · TPG FY2025 +4.5% · CELH −8%).
- Q4 계산은 지표 읽기에서 한다(행 단위 · 그 판의 세 분기 행). 엔진은 키 값만 낸다.

### 2d. 저장

- 관문 시험 마이그레이션 `V70_5__common_income.sql`: 새 표 `financial_common_income(stock_id, accession, period_start, period_end, common numeric, allocation numeric, branch varchar(8), engine_version, derived_at · PK (stock_id, accession, period_start, period_end))`. V70 · V70_1~4와 함께 먼저 여는 관문 직전에 main으로 옮긴다.
- 유도(`ShareClassDeriver`)가 판정과 같은 판 · 같은 발행사 단위로 갈아 넣는다. 판정 표(`financial_share_class_filing`)는 안 건드린다. `StockFold`가 같이 옮긴다.
- ENGINE_VERSION 4 → 5(관문 날 클래스 축 재유도가 함께 채운다).

### 2e. 지표

- `FinancialFigures`: `commonAllocation` · `commonIncome`(행 키에 붙은 값) · 표지 = 관문 켬 미국 행(`line != null`).
- 보통주 계속영업 = **`continuingIncomeControlling()`(F2b 네 갈래 그대로) − allocation** · allocation이 없고 common만 있으면 `common`에 같은 중단 갈래 · 둘 다 없으면 비움(반증 밖 7 — 중단 갈래를 다시 쓰지 않는다).
- `controllingTtm` · 연간 `controllingContinuingIncomeOf` · `DIVIDEND_PAYOUT`(연간 · 중단 포함 합 = 행 지배 NI − allocation)이 그것을 쓴다. 표지가 있는 창은 연결 폴백을 안 탄다.
- 표지가 있는데 값이 비는 행 → 그 창 미표시. 유도가 안 돈 발행사도 같다(숨김이 맞다 — 지배 NI로 떨어지면 그럴듯한 틀린 값). 그 수를 펜스가 센다(2g).
- 관문 꺼짐 · 국내는 표지가 없어 바이트 같음.

### 2f. 화면 · 문구(기본값을 뒤집는 jar 커밋에 · F2b ③과 같이)

- `IndicatorUnavailabilityConfig`의 PER 「최근 4개 분기 순이익이 적자」 → 「보통주 귀속 순이익이 적자」(HPE · APG · LYV · JXN — 순이익 양수 · 보통주 몫 음수).
- `EPS_TTM` · `PER` 설명의 「순이익」 → 「보통주 귀속 순이익」.
- 재무 탭: 「보통주 귀속 순이익」 줄(관문 켬 미국만 값 · 꺼짐은 없음 — F2b 중단영업 줄과 같은 이유).

### 2g. 펜스 · 관문 절차

- premise(새 ADR): 관문 켬 현 편입 최신 TTM 행 중 표지가 있는데 보통주 몫이 빈 행 수와 그 종목 · 갈래 분포 — 드라이런 수 이하.
- ADR-0085 premise(`COALESCE(net_income_controlling, net_income) <= 0`)는 미국 배당성향 분모가 바뀌면 적자 집합이 바뀐다 — 그 premise의 미국 몫을 보통주 몫으로 고친다(구현 때 그 premise 본문을 읽고 정한다).
- `docs/reload-gate.md`: V70_5 이동 · 엔진 판 5 재유도 뒤 백필 · 보충 · 정정 공시 인스턴스 받기 실패를 10단계 미해결 질의에 · 위 premise · 59행은 분모가 남아 안 닫는다.

## 3. 드라이런(가장 싼 재료부터 · DB 생성 없음)

1. **0콜 스윕** `SecShareClassSweepTest`(캐시 1,271 + IFRS USD 130 · 틀 켬 · 보충 목록): 키마다 common · allocation · branch TSV. 기존 판정 열은 md5 같음.
2. 같은 원자료의 `SecEdgarFactsSweepTest` 관문 켬 행 TSV와 (접수번호, 기간)으로 잇고 Q4는 2c로 지어(파이썬 · 0콜):
   - 단언: 붙은 값의 기간 = 행의 기간(전부).
   - 창: 최신 TTM · 직전 TTM · 20분기 전 TTM · FY 2~6번째 — 창마다 분자 변화 분포와 새로 비는 창 수 · 종목 · 갈래(가짜/진짜 분류).
   - 수용: 1a의 큰 변화 재현(TPG · MCHP · VST · 은행 · HPE · APG 부호) · 「원장이 말하는데 비움」 0 또는 이유가 적힌 목록.
3. 단위 시험: 갈래 일곱 · Q4 두 길 · 비교열 행 붙이기 · 꺼짐 무변화 · 대조군(갈래 · 키 하나씩 되돌림)이 붉다.

## 4. 재지 않았다 · 사정거리

- 배분 중 중단영업 몫(참가증권이 중단영업에도 배분된 칸) — 원장은 중단영업 주당이익을 따로 주지만 크기는 재지 않았다(반증 안 10).
- 갈래 5의 정밀도: NIC를 답으로 둔 5,848기간에서 중앙값 0.16% · p90 1.4% · p99 33%(반증 안 10 · EPS가 작은 발행사 — 원장 정밀도).
- 보충 · 정정 공시 중 인스턴스를 엔진이 아직 안 받는 것(AUGO 20-F/A 등)의 수 — 드라이런 1에서 센다. ★ 운영(꺼짐) 현 편입 행 중 보충 OK 공시에서 온 것 110행 · 92종목.
- 분모(PBR · BPS)는 다음 계획(탐색 메모 `scratchpad/pb/NOTES.md`의 실측 — 우선주가 있는 61 중 51이 자본 구성 합으로 닫힘).

## 5. 국내 — 같은 항목에서 이어서 고친다(관문 `gate.kr` 뒤)

- ★ DART 6콜: 지배 NI ÷ 보통주 수 대 원장 보통주 기본주당이익 — 삼성전자 1.15배 · 현대차 1.29 · 미래에셋증권 1.34 · 두산 1.32 · 한화 1.32 · 아모레퍼시픽 1.18. 현 편입 350 중 보통주 밖 주식 51 · 그 비중 1% 이상 38(`SHARE_TOTALS`가 없는 종목 수는 하한 밖).
- 사용자 결정(2026-08-30)의 사정거리는 **분모에 우선주를 넣지 않는다**뿐이다(ADR-0098 §버린 대안 · `docs/progress.md` F2b 줄). 분자를 보통주 몫으로 바꾸면 분모는 그대로라 그 결정을 안 바꾸고, 그 결정의 근거(K-IFRS 1033 · 「시총이 보통주라 일관」)가 처음으로 선다. 그래서 묻지 않고 고친다(반증 밖 2 · v1의 「사용자 판단」은 사정거리를 잘못 읽었다).
- spec §7(시장 간 계산식이 갈라지지 않게)이 두 시장을 함께 요구한다 — F2b처럼 미국 · 국내 두 관문 갈래로. 국내 `DIVIDEND_PAYOUT`은 배당 총액에 우선주 배당이 들어 있어 지배 NI가 짝이다(ADR-0098 premise 삼성 25.10%) — 국내는 PER · EPS 계열만.
- 국내 설계(원장: `ifrs-full_BasicEarningsLossPerShare` 보통주 · `dart_BasicEarningsLossPerSharePreferredStock` · 우선주 수)는 이 계획 구현 뒤 다음 계획으로 잰다.

## 6. v1 반증 판정 (2026-10-24 · 안 「다시 짠다」 · 밖 「고쳐서 간다」)

| 렌즈 · 번호 | 지적 | v2 |
|---|---|---|
| 안 1 · 밖 1 | 접수번호 하나 = 값 하나 키 — 비교열 · 정정 행이 그 공시 당기 값을 받는다(직전 TTM 618행 · 298종목) | 키를 (접수번호, 기간)으로 · 모든 키에 사다리(2a) |
| 안 2 | 1b의 941/994는 계획의 사다리가 아니다(③ · 갈래 4 · 희석 EPS · 첫 공시) | 1b 수를 계획 근거로 안 쓴다 — 드라이런 2가 행의 키 집합으로 다시 잰다 |
| 안 3 | 클래스 축 발행사가 「못 정함」(TSN · HSY · BKR · CWEN · PAA · PAGP · SYRE) · 표지 정의 | 클래스 합 갈래 6 · 표지는 관문 켬 미국 행 · 빈 값은 숨김 + 펜스(2e · 2g) |
| 안 4 | Q4 「FY − 9개월」이 어댑터와 갈림(20/954) | 어댑터와 같은 길 · 배분 차감(2c) |
| 안 5 | NI 폐합 거짓 증인 164(89는 배분 태그 있음) · `strictCloses`의 ProfitLoss 떨어짐 | 선언 배분을 앞에 · NI 폐합은 배분 태그 없는 키만 · NI는 `NetIncomeLoss`만(2a · 2b) |
| 안 6 | NIC 무검증(CTVA · PCVX · OKLO · AIG · VMI) | 부호 · 자릿수 확인(2b-1) |
| 안 7 | 계속영업 EPS로 중단 이중 차감 | 분자 사다리에서 계속영업 EPS 제외 |
| 안 8 | 연간 · 9개월 n은 판정이 검증한 n이 아님 | W 규칙을 키 종류별로(2b 머리) · 9개월 길 삭제 |
| 안 9 | 정정 행 갈래 4 막음 → AVAV · BWXT 숨김 | 갈래 5가 정정 키에도 선다(표지 자릿수 확인 W) |
| 안 10 | 중단 몫 배분 · 갈래 5 정밀도 | 4절에 수로 |
| 안 11 · 밖 7 | 소비자 셈 누락 · F2b 갈래 재작성 | 시험 `ContinuingIncomeTest` · `SecEdgarIncomeBasisTest` · ADR-0098 · 0159 갱신 · F2b 함수 재사용(2e) |
| 밖 2 | 국내를 빼면 spec §7 위반 · 사용자 결정은 분모뿐 | 5절 — 같은 항목에서 국내 관문 뒤로 |
| 밖 3 | 미표시 사유 · 설명 · 재무 탭 | 2f |
| 밖 4 | 오프셋 창 커버리지(옛 창 못 정함 5.8% 근사) | 드라이런 2 창 넷 |
| 밖 5 | ADR-0085 premise 침묵 · 분자 펜스 없음 | 2g |
| 밖 6 | 관문 절차 | 2g |
| 밖 따로 | CREX FY2023 행 공시일 2022-11-14(기말보다 앞) | 이 계획 밖 — 보고 |

## 7. v2 반증 판정 (2026-10-24~25 · 안 「고쳐서 간다」 · 밖 「고쳐서 간다」) → v2.1

v2.1은 2~3절을 아래로 고친다(뼈대 — 엔진 사다리 · 관문 뒤 · 새 표 — 는 그대로).

**v2.1 저장 단위: 재무 행.** 유도(`ShareClassDeriver`)가 키 값(공시 × 기간 · 사다리)을 낸 뒤 그 종목의 **`financial_statement` 행마다**(전 판 · `uq_fs_revision` = 종목 · 연도 · 기간 · 개정) 보통주 몫을 정해 `financial_common_income(stock_id, fiscal_year, fiscal_period, revision_no, report_code, common, basis T|C, branch)`에 갈아 넣는다. 지표 · 재무 탭 · 배당 게이트가 같은 행 값을 읽는다(밖 7 — Q4를 두 번 구현하지 않는다).
- 행 → 키: `(report_code 접수번호, 행 기간)` 키에 그 기간 손익이 있으면 그것, 없으면 같은 기간 키 중 공시일 ≤ 행 공시일의 최신(안 2 — ACA 2025 FY 행 = 2026 Q1 10-Q는 그 기간 손익 0팩트).
- `ni` 갈래(배분 0)는 값 대신 표지만 — 읽을 때 **행 지배 NI**(어댑터가 mistag까지 고친 값)를 쓴다. 키의 NetIncomeLoss에서 배분을 빼지 않는다(안 1 · 4 — AMRX 2022 Q3 부호 · ES 이중 차감 · CAT · MA는 키에 NetIncomeLoss가 없다).
- Q4 행: 그 10-K에 Q4 3개월 키가 있으면 그 키, 없으면 **FY 행 몫 − 어댑터가 쓴 세 분기 판**(`buildQ4`의 `vintageOf(Qi, FY 공시일)` = 공시일 ≤ FY 공시일인 최신 판 행)의 몫(안 8 — 819행이 뒤 판을 가진다).
- 기저: 키 값이 계속영업 EPS에서 왔으면 `C`(지표가 그대로 계속영업 몫으로 · 중단을 다시 안 뺀다), 그 밖 `T`(F2b 네 갈래 `continuingIncomeControlling`의 기저만 바꿔 태운다 · 배당성향은 `T` 그대로, `C`면 + 지배 중단)(안 7 · 밖 7).

**사다리 고침**
- NI 폐합 후보: 키 `NetIncomeLoss` → 없으면 `ProfitLoss − NetIncomeLossAttributableToNoncontrollingInterest`(둘 다 그 키) — 없으면 폐합 안 함(안 1 · 10).
- 갈래 2 태그는 묶음마다 하나: 우선주(`PreferredStockDividendsAndOtherAdjustments` → `PreferredStockDividendsIncomeStatementImpact` → `DividendsPreferredStock` 순 첫 값) · 참가증권(총액 S1 → 없으면 미배분 S2) · GP 배분(안 5 — AIG [8M, 8M] · ALB 11.584M × 2).
- W: 그 공시 당기 + 지금 경로 판정이면 판정 n · 그 밖(비교열 · 연간 · 9개월)은 그 키의 가중평균이 표지와 **10배 자릿수**(|log10| < 1) 안일 때 · 표지가 없는 공시(6-K)는 `referenceCovers`(공시일 기준 중앙값)와(안 3 — ±12.7%는 비교열 481키 · 172종목을 버렸다 · 6-K AER · CAMT · DSGX · ICLR · TSEM).
- EPS는 총 EPS 태그를 첫 태그 우선으로 따로 읽고, 계속영업 EPS는 그것이 없을 때만 · 기저 `C` 표지(안 6b · 7 — `IssuerFacts.eps`는 `putIfAbsent`라 계속영업이 섞인다 · 280키).
- 클래스 합(갈래 6): 키 기간마다 인스턴스의 클래스 구성원 총 EPS × W(첫 태그 우선) · EPS 구성원이 없으면 축 없는 총 EPS × Σ W_m(안 6a · 6c — 앵커 비교열 89).
- 인스턴스 재료: `ClassFactReader` DURATION에 우선주 · 참가증권 · GP · LP 배분 · LP 단위 EPS · `ProfitLoss` NCI 태그를 더하고 `IssuerFacts.prune` 허용 목록도 · `READER_VERSION` 6 → 7(안 9 — 지금은 인스턴스 키에서 갈래 3이 늘 참).

**하류**
- 배당 주식수 20% 게이트(`GovernanceFactDeriver.withClassAxisShares`)의 함의 분자를 관문 켬에서 행 보통주 몫(T)으로(밖 1 — HPE · VST 등 19종목 총액이 기각돼 앵커가 앞 해로 물러나 낡은 배당성향). 꺼짐 SQL은 그대로.
- 문구(밖 4): 미표시 사유는 두 시장에 다 참인 말로 바꾼다(「주당순이익(EPS)이 0 이하」) — 미국 관문 jar 커밋. 설명의 「보통주 귀속」은 두 시장 분자가 다 고쳐진 뒤의 관문 jar 커밋.
- 펜스(밖 8): 새 premise는 지표 출력으로 센다(재무 행은 있는데 PER · EPS_TTM이 함께 없는 관문 켬 미국 현 편입 수 ≤ 드라이런). ADR-0085 premise의 미국 몫은 보통주 몫으로 — 배당 게이트를 고친 뒤.
- 드라이런 수용(밖 6): 프리셋 필수 조건(`EPS_CAGR_5Y` 린치 두 띠) 통과 집합 전후 diff · 창 넷(최신 · 직전 TTM · 20분기 전 · FY 2~6) · 숨김을 원인(NI 정의 · 키 출처 · W · 태그 · 클래스 합 · 계속영업 기저)으로 나눠 센다.
- 관문 재고: 국내 표에 「주당 지표 분자 — 우선주 몫」 행(밖 3) · 미국 B 항목 「성장률 과거 끝점이 그날 최신보다 옛 공시를 고른다」(밖 5 — −1년 108/981 · −5년 175/893 · GS 3.1% · 관문 켬 · 꺼짐 둘 다 · 이 계획 밖).

**국내(밖 2)**: v2 5절의 「묻지 않는다」를 거둔다. ADR-0098 §배경 · §결과가 「네이버와 12% 차이는 우선주 분모 하나로 설명된다 — 결함이 아니다」라 적었고 사용자는 그 설명 위에서 골랐다. 고친 분자로는 삼성전자 EPS 약 22,262(계산 · 안 잼) 대 네이버 22,292 — 12%는 우리 분자의 결함이었다. 승인 설명이 거짓이므로 사용자에게 다시 묻는다. 미국 일은 기다리지 않는다.
