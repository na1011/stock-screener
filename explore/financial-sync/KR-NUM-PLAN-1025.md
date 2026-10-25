# 국내 주당 지표 분자를 보통주 몫으로 — DART XBRL 원본의 보통주 귀속 이익 (계획 v1 · 2026-10-25)

`docs/reload-gate.md` 국내 표 「주당 지표 분자가 우선주 몫을 포함한다」. 사용자 결정(2026-10-25): 「분자를 보통주 몫으로」(선택지 설명: 분모 보통주 그대로 · 원장의 보통주 몫 · 국내 관문 뒤 · 배당성향 그대로). 미국 짝은 ADR-0191. ★ = 내가 이 세션에 쟀다.

## 0. 질문

국내 PER · EPS 계열의 분자는 지배주주 순이익 전체(`net_income_controlling` · F2b 계속영업)이고 분모는 보통주다(ADR-0098 · 분모 결정은 그대로). 지배 NI에는 우선주 몫이 들어 있다.

## 1. 실측 ★

- **크기**(DART `fnlttSinglAcntAll` FY2025 6콜): 지배 NI ÷ 보통주 유통주식수 대 원장 보통주 기본주당이익 — 삼성전자 1.15배 · 현대차 1.29 · 미래에셋증권 1.34 · 두산 1.32 · 한화 1.32 · 아모레퍼시픽 1.18.
- **모집단**(운영 SELECT · `SHARE_TOTALS` 최신 · 연간 보고서만 받는다 — 2024 · 2025 `11011`): 현 편입 350 중 보통주 밖 주식이 있는 것 51 · 그 비중 1% 이상 38 · 20% 이상 4.
- **원장이 주는 것**
  - `fnlttSinglAcntAll`(본표)은 분기에도 3개월 · 누계 보통주 기본주당이익(`ifrs-full_BasicEarningsLossPerShare`)과 우선주 EPS(`dart_BasicEarningsLossPerSharePreferredStock` · 1우선주만 — 현대차 2026 H1)를 준다. **가중평균 보통주식수와 보통주 귀속 이익은 본표에 없다.**
  - **`fnlttXbrl.xml`(보고서 XBRL 원본 ZIP · 접수번호마다 1콜 · 약 0.7MB)은 주석까지 준다**: 현대차 2026 2분기 3개월 `ifrs-full:ProfitLossAttributableToOwnersOfParent` × `OrdinarySharesMember` 1,947,889백만 · `WeightedAverageShares` × 보통주 202,454,061 → 9,621원 = 보통주 EPS(정확히) · 우선주 1 · 2 · 3우마다 배분과 가중평균(`WeightedAverageNumberOfInstrumentsUsedInCalculatingBasicEarningsLossPerInstrumentParticipatingEquityInstrumentsOtherThanOrdinaryShares`).
  - 보통주 밖 주식 51종목의 2026 H1 보고서(102콜): 보통주 EPS 51 · 보통주 귀속 이익 직접(`ProfitLossAttributableToOrdinaryEquityHoldersOfParentEntity` 29 · 보통주 구성원의 지배 NI 44) 둘 중 하나 45 · 보통주 가중평균 34 · **직접값도 가중평균도 없는 것 6**(090430 아모레퍼시픽 · 010950 S-Oil · 226950 · 115180 · 122640 · 010140 — 보통주 · 우선주 EPS와 지배 NI만).
- **2024 보고서부터만 주석 태그가 있다**(20콜 · 5종목 × 2020 사업 · 2022 반기 · 2023 3분기 · 2024 1분기): 2024 1분기에 현대차 · 한화가 보통주 구성원 지배 NI + 가중평균, 삼성전자가 보통주 귀속 이익(가중평균 없음) — 2020~2023은 다섯 다 그 태그가 없고 본표 EPS뿐(현대차는 2023까지 `ifrs-full` EPS 태그도 없다 · 미래에셋 2020 · 2022는 연결 본표가 `013`).
- **근사는 못 쓴다**: 연말 유통주식수(`SHARE_TOTALS` 2025)로 EPS 가중 비를 지어 정확값 17과 맞대면 |오차| 중앙 1.8% · 삼성전자 +13.9%(자사주 매입) · LG생활건강 −12.5% · 금호석유화학 −11.9% · 085660 150%.

## 2. 계획 — 국내 관문(`stockscreener.gate.kr`) 뒤

### 2a. 받기

- 새 외부 연동 `fnlttXbrl.xml`(ADR 새로) — 보통주 밖 주식이 있는 종목(그 보고서 연도나 앞뒤 연간 `SHARE_TOTALS`에 보통주 밖 행) 의 정기 보고서(1Q · 반기 · 3Q · 사업)마다 1콜. 지금 모집단 51종목 × 2020~2026 보고서 약 26 = 약 1,300콜(관문 날 한 번 · 그 뒤 새 보고서마다 1콜).
- 읽는 것: 연결(`ConsolidatedMember` — 없으면 별도) 3개월 · 누계 기간마다 ① 보통주 귀속 이익(`…OrdinaryEquityHolders…` · 없으면 지배 NI × 보통주 구성원) ② 보통주 가중평균 ③ 보통주 기본 EPS.
- 저장: 원본 ZIP은 안 둔다 — 읽은 값만 (종목, 접수번호, 기간)마다.

### 2b. 행마다 보통주 몫

- 보통주 밖 주식이 없는 종목 · 보고서: 배분 0(`ni`) — 지배 NI 그대로.
- 있으면 사다리: ① 직접 보통주 귀속 이익 → ② 보통주 EPS × 보통주 가중평균 → ③ 못 정함(그 창 미표시).
- **2024 앞 보고서는 원장이 보통주 몫도 가중평균도 안 준다** — 그 분기를 낀 창(5년 창 · 2025 앞 TTM)은 보통주 밖 주식이 있는 종목에서 미표시다. 지금 값(우선주 몫 포함)으로 떨어뜨리지 않는다 — 그 값이 이 결함이다. 크기: 현 편입 51종목의 `PER_MEDIAN_5Y` · `EPS_CAGR_5Y`가 2029년까지 빈다(재지 않음 — 드라이런이 센다).
- 버린 대안 후보(반증이 다시 잰다): (가) 연말 · 분기말 유통주식수 × 보통주 EPS — 위 근사 오차 · 소스가 안 주는 값의 합성 · (나) PER 분자를 Σ 분기 보통주 EPS(원장 주당값)로 — 분자 정의가 미국 · 국내 무우선주 종목(지금 주식수로 나눈 NI)과 갈린다.
- Q4: 국내 어댑터의 Q4(사업 − 3분기 누계)와 같은 길 — 누계 보통주 몫의 차.
- 저장: 미국과 같은 `financial_common_income`(재무 행 id) — 관문 시험 마이그레이션 V70_5는 먼저 여는 관문 직전에 옮긴다.

### 2c. 지표

- `FinancialFigures.common`이 국내 관문 켬 행에도 실린다 — 미국과 같은 함수(`commonContinuingIncome`)를 탄다. 국내 `DIVIDEND_PAYOUT`은 지배 NI 그대로(총액에 우선주 배당 포함 · ADR-0098 premise) — 미국과 다르게 갈리는 근거는 분자(배당 총액)의 범위다.

## 3. 드라이런

- XBRL 원본 0콜 재생: 51종목 × 최근 8분기 보고서를 받아 둔 재료(약 400콜)로 행마다 보통주 몫을 지어 운영 행(SELECT)과 잇는다 — 최신 TTM · 직전 TTM 분자 변화 · 새로 비는 창.
- 수용: 삼성전자 · 현대차 · 미래에셋증권의 TTM EPS가 원장 보통주 EPS 합과 반올림 안 · 직접값 없는 6종목은 미표시(이유: 원장이 가중평균을 안 준다 — 진짜 한계 판정의 넷을 적는다).

## 4. 재지 않았다

- 2020~2025 보고서에서 XBRL 원본 주석 태그 커버리지(2026 H1만 셌다).
- 보통주 밖 주식이 보고서 중간에 생기고 사라진 종목(연간 `SHARE_TOTALS`만 있다).
- 6종목을 미표시로 두는 대신 보통주 EPS의 TTM 합으로 PER을 낼지 — 분자 정의가 갈린다(NI ÷ 지금 주식수 대 Σ 분기 EPS).

## 5. v1 반증 판정 (2026-10-25 · 안 「다시 짠다」 · 밖 「다시 짠다」) — v2에서 다시 짠다

| 지적 | 근거(반증자 실측) | v2 방향 |
|---|---|---|
| 모집단 — 신종자본증권 배당도 보통주 몫에서 빠진다 | KB금융 지배 NI ÷ 보통주 귀속 1.022 · 신한 1.032 · 하나 1.038 · 우리 1.040 · 기업은행 1.080(2026 2Q) · KB FY2025 `DividendsPaidToHybridBond` 202,392M · `SHARE_TOTALS`는 2021~2025 연간이 있다(이력 속 보통주 밖 15종목) | 주식 클래스로 미리 고르지 않는다 — 전 종목 · 보고서마다 원장이 배분을 말하는가로 |
| 「2024 앞은 원장이 안 준다」 거짓 | `document.xml` 주당이익 주석표(현대차 2022 반기 · 아모레 2020 사업) · 2023 사업보고서 XBRL에 FY2023 · FY2022 값 · 2024 1Q XBRL의 2023 1Q 비교열 · 현대차 EPS는 회사 확장 접두어(`entity00164742:udf_…`)였다 · 반대로 미래에셋 · 파마리서치는 2023 · 2024 1Q XBRL에 주석 태그 0 | 원천 사다리: XBRL(컨텍스트 단위 · 확장 요소) → `document.xml` 주석표 → 분기 주식총수(`stockTotqySttus` 분기도 답함) + EPS 폐합 → 못 정함 · 커버리지를 (종목, 보고서)마다 |
| 「직접값 없는 6」은 문자열 분류 | 컨텍스트 단위로 16(CJ · 아모레G · 아모레 · CJ제일제당 · 현대건설 …) | 판독기를 컨텍스트 단위로 |
| 직접 태그가 오염된다 | 호텔신라 1000배 · 롯데지주 EPS × W와 1.30~1.66배 · 차바이오텍 축 없는 값과 보통주 값 공존 · 「지배 NI × 보통주 구성원」이 9종목에서 조정 전 줄 | 모든 갈래에 EPS 항등식(몫 ÷ W = EPS · 클래스 합 = 지배 NI) |
| 계속영업 기저 | 두산 H1 +0.6% · 원장이 보통주 귀속 계속영업 태그를 11종목에 준다 | 그 태그를 C로 1순위 · 없으면 보통주 몫 중단을 뺀다 |
| 미국 규칙의 이식 | 연결 폴백 끔이 국내 지배 NI NULL 44종목(최근 4분기)을 비운다 · `commonNetIncomeOf`가 국내 배당성향을 바꾼다 · `report_code`가 국내에서 판 열쇠가 아니다 · 연결/별도 기저 | 국내 갈래를 따로 — `ni`는 기존 창 폴백 · 배당성향 지배 NI 고정 · 열쇠는 접수번호 · 행의 `fs_div`를 따른다 |
| 지표를 묶어 비움 | 수준 지표는 1/f, 비율 지표는 f 변화만 틀린다 · 린치 띠 15종목 | 남는 구멍은 수준 · 비율을 갈라 판정 |
| 재료 · 절차 | 원본을 안 두면 0콜 스윕이 없다 · 잡 · 순서 · 실패 계약 · 일일 경로 | 추출 팩트를 판독기 판과 함께 저장 · 관문 절차 행 |
| 근사 | (다) NI − Σ 우선주 EPS × 분기 우선주 수: 정확값 35칸 중 0.1% 안 26 · 1% 넘음 5 | 조건부 갈래 후보 — 다시 잰다 |

## 6. v2 실측(진행 중 · 2026-10-25) ★

- **편입 350 전 종목 2026 H1 XBRL**(`fnlttXbrl` · 약 700콜 · 받음 346 — 연결 본표 없음 1 · 매핑 없음 3) · 컨텍스트 단위 · 2분기 3개월 · 연결:
  - EPS(ifrs 또는 확장 `…_BasicEarningsLossPerShare`) + 보통주 가중평균 둘 다 177 — 지배 NI와 EPS 반올림 안에서 닫힘 139 · 안 닫힘 38(우선주 발행사 · 신종자본증권 금융지주 — 신한 3.1% · KB 2.1% · 기업은행 7.4% · JB 3.1% · 그리고 가중평균 단위가 틀린 KCC 92% · 247540 264%).
  - EPS만(가중평균 없음) 115 · 연결 축 없는 지배 NI가 그 기간에 없음 36 · 재료 없음 13.
- **EPS만 115의 `document.xml`**(115콜): 표 안에서 「이익 × 단위 ÷ 주식수 = 그 분기 EPS」 짝(주식수는 NI ÷ EPS의 0.5~1.5배 · 이익은 NI의 0.5~1.05배)이 선 것 46 · 안 선 것 69. 선 46 중 배분 0.2% 넘음 15 — 롯데지주 24% · 003670 37% · 103590 25% 등은 원장 안의 자기모순 의심(반증 안 3: 롯데지주 직접값 ÷ NI 0.9895 대 EPS × W ÷ NI 0.759) — 열어서 가르기 전에는 수로 안 쓴다.
- 함의: 국내 원장은 같은 개념을 회사마다 다른 이름 · 축 · 단위 · 문서 위치로 주고, 한 보고서 안에서도 서로 어긋나는 값이 있다. v2의 일은 (종목, 보고서)마다 원천을 고르는 판독기와 항등식 교차검증이고, 남는 자리는 진짜 한계(원장 자기모순 · 재료 없음)로 가른다.

## 7. 계획 v2 (2026-10-25 · v1 「다시 짠다」 두 렌즈 → 새로 쓴다)

**중재자는 원장 EPS 항등식이다** — 롯데지주 2026 2분기: 원본 주석표가 지배 NI 35,572 → 우선주 몫 372 → 보통주 귀속 35,200 → 신종자본증권 이자 8,179 → **보통주 순이익 27,021** · 가중평균 76,050,761주 → 355원 = 원장 EPS. XBRL의 「보통주 귀속」 직접 태그(0.9895)는 신종자본증권 차감 **전** 줄이었다 — 원장 자기모순이 아니라 태그의 뜻이 회사마다 다르다. 그래서 어느 갈래의 값이든 「값 ÷ 그 기간 보통주 가중평균 = 원장 보통주 EPS(반올림 0.5원)」로만 받는다.

### 7a. (종목, 보고서, 기간)마다 — 국내 전 종목(과거 편입 포함) · 행의 연결/별도 기저

재료(그 보고서 · 그 기간 · 행의 `fs_div`와 같은 기저):
- **E** 원장 보통주 기본 EPS(본표 `fnlttSinglAcntAll` · XBRL `BasicEarningsLossPerShare` · 확장 `…_BasicEarningsLossPerShare`).
- **W** 보통주 가중평균: XBRL `WeightedAverageShares`(축 없음 · 보통주 구성원) → 원본 주석표의 「가중평균유통보통주식수」 → 없음.
- **후보 값**: XBRL `ProfitLossAttributableToOrdinaryEquityHoldersOfParentEntity` · 보통주 구성원 지배 NI · 원본 주석표의 보통주 순이익 행(가장 아래 · 「보통주순이익」 · 「보통주에 귀속되는」) · E × W.

사다리:
1. E와 W가 있으면 **E × W와 반올림 안에서 맞는 후보**(값이 더 정밀) → 없으면 E × W(정밀도 ± 0.5 × W).
   - 지배 NI가 E × W와 닫히면 배분 0(`ni`).
2. W가 없으면 **분기 주식총수**(`stockTotqySttus` · 그 보고서) · 자기주식 변동(`tesstkAcqsDspsSttus`)으로 그 기간 보통주 수의 경계 [lo, hi] → 지배 NI가 E × [lo, hi] 안이면 `ni` · 아니면 후보 중 E × [lo, hi] 안의 하나 · 없으면 못 정함.
3. E가 없으면 못 정함.
- 계속영업: 원장 보통주 귀속 계속영업 태그(`ProfitLossFromContinuingOperationsAttributableToOrdinaryEquityHoldersOfParentEntity` · 9830 확장)가 E_계속 × W와 맞으면 C로 그것 · 아니면 T(지표가 지배 중단을 뺀다 — 보통주 몫 중단이 없을 때의 근사는 재지 않음 · 드라이런이 센다).

### 7b. 국내 갈래(미국 규칙을 옮기지 않는다)

- `ni` 행은 지금의 창 단위 폴백을 그대로 탄다(지배 NI NULL 44종목 · 국내 NULL은 대개 비지배 없음 — 미국 「연결 폴백 끔」의 근거가 국내에 안 선다).
- `DIVIDEND_PAYOUT`은 국내에서 지배 NI 고정(총액에 우선주 배당 포함 · 신종자본증권 이자는 배당 총액 밖 — 사정거리: 배당성향 분모에 신종자본증권 몫을 남기는 것이 짝인지는 재지 않음).
- 낡음 열쇠: 국내 `report_code`는 보고서 코드라 판을 안 가른다 — 접수번호와 붙일 때의 지배 NI를 함께 저장하고 읽을 때 맞댄다.
- 행이 아직 유도되지 않았으면(일일 갱신 뒤 유도 전) 그 창 미표시 — 유도를 국내 일일 사슬(갱신 뒤 · 화해 앞)에 둔다.
- Q4: 국내 어댑터의 지배 NI Q4와 같은 식(FY − Q1 − Q2 − Q3 — `DartFullStatementAdapter` 598-628).

### 7c. 저장 · 재료

- 추출 재료를 판독기 판과 함께 둔다(미국 `sec_filing_class_fact` 꼴) — 0콜 스윕을 짓는다. ZIP은 안 두고 재료 행만.
- 받기: 정기 보고서마다 XBRL 1콜 · 주석표가 필요한 보고서만 `document.xml` 1콜 · 주식총수 필요한 보고서만 1~2콜. 실패는 종목 단위 격리 · FAILED 행 · 다음 받기가 다시.

### 7d. 남는 구멍

- 수준 지표(PER · EPS_TTM · PER_MEDIAN_5Y)와 비율 지표(EPS_CAGR_5Y · EPS_GROWTH_TTM_YOY · PER_DISCOUNT_5Y)를 갈라 판정 — 비율 지표는 양 끝이 같은 기저일 때만.
- 드라이런이 (종목, 보고서)마다 갈래를 센다 — 받기 전에 「못 정함」 수를 적지 않는다.

### 7e. 이력 커버리지 ★ (표본 20종목 × 보고서 8 · 2021 사업 ~ 2025 사업 · 약 480콜)

- 표본: 배분이 있는 종목(우선주 · 신종자본증권 — 두산 · 현대차 · 삼성전자 · 미래에셋 · 한화 · LG생건 · 금호석유 · DL이앤씨 · LG전자 · 한국금융 · 코오롱인더 · LG화학 · 신한 · KB · 기업은행 · JB · iM · 롯데지주 · 포스코퓨처엠 · 하나).
- W가 선 칸(EPS × W 항등식으로 확인): **92 / 106**(XBRL 47 · 원본 주석표 45) · 못 섬 14(삼성전자 2021~2023 다섯 보고서 · 2021 사업 다수).
- 판독기가 못 닿은 54: 2021~2023 XBRL에서 EPS 태그를 이 판독기가 못 찾음 22(본표 JSON EPS를 E로 쓰면 닫힐 자리 — 재지 않음) · 연결 본표 `013` 32(미래에셋 · 기업은행 · KB · 신한 · 하나 · JB · iM 2021~2023 — 국내 어댑터가 그 기간 행을 어디서 짓는지 재지 않음).
- 배분은 회사마다 해마다 안정적이다(현대차 0.223~0.230 · 한화 0.217~0.251 · KB 0.031~0.040 · 신한 0.030~0.043 · 금호석유 0.111~0.118) · 코오롱인더는 0.092 → 0.838(신종자본증권 이자 고정 · 이익 축소).

### 7f. 재지 않았다(드라이런 전)

- 편입 전 종목 · 전 보고서의 커버리지(표본만 쟀다).
- 신종자본증권 발행사 전체 수.
- 계속영업 보통주 몫의 정밀도.

## 8. v2 반증 판정(안 「고쳐서 간다」 · 밖 「고쳐서 간다」) → v2.1 — 사용자 기준으로 다시 정함

**사용자 기준(2026-10-25 · 끼어듦)**: 「claude.md의 판단기준에 근거해서 다시 결정해. 원장 자체가 뒤틀려서 특정 종목만을 위한 로직이 들어가야 한다면 정합성 훼손으로 판단해. 원장에서 주는 정보로 조합이 되면 개발을 해.」

**사용자 결정(2026-10-25)**: 배당성향 분모 「지배 NI − 신종자본증권 몫」(선택지 설명: 분모를 배당 총액의 범위(보통주 + 우선주 몫)로 · 원장이 주는 신종자본증권 몫을 뺀다 · 국내 관문 뒤 · 무보통주밖 종목은 값 그대로). 앞 결정 「배당성향 그대로」의 승인 설명(「총액에 우선주 배당이 들어 있어 지배 NI가 짝」)이 신종자본증권 발행사에서 거짓이라 다시 물었다(반증 밖 3).

### 8a. 갈래 판정 — 이 기준으로

| 재료 · 갈래 | 판정 | 근거 |
|---|---|---|
| 보통주 EPS(`ifrs-full:BasicEarningsLossPerShare` · 축 없음 또는 `OrdinarySharesMember`) × 보통주 가중평균(`ifrs-full:WeightedAverageShares` · 같은 축) | **개발** | 표준 태그 · 모든 종목에 같은 규칙 |
| 우선주 EPS(`dart:BasicEarningsLossPerSharePreferredStock`) × 기타 지분상품 가중평균(`ifrs-full:WeightedAverageNumberOfInstrumentsUsedInCalculating…OtherThanOrdinaryShares`) | **개발** — 배당성향 분모 = 보통주 몫 + 우선주 몫 · 지배 NI − 그 합 = 신종자본증권 등 몫(검산) | DART 표준 분류 · 모든 종목에 같은 규칙 |
| 자본 구조에 보통주 밖 지분상품이 없음(주식총수 연간에 보통주 밖 행 없음 · 본표 자본에 신종자본증권 계정 없음 · 우선주 EPS 태그 없음) → 보통주 몫 = 지배 NI | **개발** | 원장 사실의 조합 · 같은 규칙 |
| 같은 보고서 안에서 EPS가 상충(축 없음 대 보통주 구성원 — KCC 13배 · 454910 부호) | **정합성 훼손 → 못 정함** | 원장 자기모순 |
| 회사 고유 확장 태그(`entity…:udf_…` — 현대차 2022) | **정합성 훼손 → 못 정함** | 종목 특정 |
| 단위 오태깅 수리(호텔신라 1000배 · W 천 단위) | **정합성 훼손 → 못 정함** | 종목 특정 수리 |
| 원장 보통주 가중평균이 원장 보통주 수(주식총수)와 우선주 포함 비로 맞음(NH투자증권 — 구형우선주를 보통주로) | **정합성 훼손 → 못 정함** | 회사마다 보통주 정의가 갈림(우리 분모는 상장 보통주) — 판정은 일반 규칙(W ÷ 보통주 수가 1 ± τ 밖이면) |
| `document.xml` 주석표 파싱 | **버림** | 짜임 · 단위 · 열 · 연결 구획이 회사마다 다르고 고른 수를 독립으로 검증할 길이 없다(반증 밖 8 — 선택 기준이 곧 확인 기준) |
| 분기 주식총수 경계 | **버림** | 1Q · 3Q는 수량이 「-」(반증 안 1) · 원장이 가중평균을 안 주는 자리의 근사 |
| XBRL 「보통주 귀속」 직접 태그 | **증인 전용** — E × W와 반올림 안에서 맞을 때만 그 값(더 정밀) | 뜻이 회사마다 다르다(롯데지주 신종자본증권 차감 전 줄) |

### 8b. 사다리 v2.1 — (종목, 보고서, 기간) · 행의 연결/별도 기저

1. 그 보고서 연도의 자본 구조에 보통주 밖 지분상품이 없으면 `ni`(지배 NI 그대로 · 지배 NI가 있을 때만 — 반증 밖 9).
2. 아니면 표준 E · W가 상충 없이 있으면 E × W(직접 태그가 그 반올림 안이면 그 값). 지배 NI가 E × W와 닫혀도 배분 0의 증인으로 안 받는다(배분 도구가 있다 — ADR-0191 §규칙과 같다). W ÷ 보통주 수 검사를 건다.
3. 그 밖 못 정함(미표시) — 이유 갈래를 남긴다(상충 · 확장 태그만 · W 없음 · 보통주 정의 갈림).
- 계속영업: 본표 · XBRL 계속영업 보통주 EPS × W가 있으면 C로(반증 안 9) · 없으면 T(지표가 지배 중단을 뺀다).
- 2024 앞 보고서(주석 태그가 없다)는 1번 갈래만 선다 — 보통주 밖 지분상품이 있는 종목의 그 기간은 못 정함. 수준 지표와 비율 지표를 갈라 판정한다(비율 지표는 양 끝이 다 정해질 때만).

### 8c. 남은 v2 지적 — v2.1에서 닫는 방법

- 연결/별도 기저(반증 안 6 · 밖 6): 받기가 그 보고서의 CFS/OFS를 재료 행에 저장한다 · 행 기저는 같은 보고서의 FSR 경로가 쓰는 규칙(CFS → OFS 폴백)으로 유도 때 재현한다.
- 낡음 · 판 열쇠(안 10 · 밖 6): 국내 재료 행에 접수번호 · 붙일 때의 지배 NI · 성분(보통주 · 우선주 몫)을 둔다 — 새 관문 시험 마이그레이션(V70_5는 안 고친다).
- `ni` 창 폴백(안 7 · 밖 9): 1번 갈래는 행 자신의 지배 NI가 있을 때만 · 국내는 `readsCommonIncome`의 미국 전제(연결 폴백 끔)를 안 탄다 — 국내 갈래 메서드를 따로.
- 배당성향(안 8 · 밖 3): 국내는 `commonNetIncomeOf`를 안 타고 「보통주 + 우선주 몫」(위 사용자 결정) — 그 성분이 안 서면 미표시.
- 소비자(안 8 · 밖 10): `PEG` · `PER_PCTL` · `PER_SECTOR_*` · `StockReader` 국내 재무 탭 · 워치리스트 — 구현 계획에 grep 전수.
- 일일 경로(밖 5): 1번 갈래는 FSR 본표 · 연간 주식총수 · 본표 자본만으로 정한다(추가 콜 0) — XBRL은 2번 갈래가 필요한 보고서만. 받기는 FSR 안(미국 `ShareClassCollector`처럼).
- 원본 저장(밖 7): XBRL 인스턴스 파일 캐시(판독기 판 함께) — 무해한 선행 배포.
- 국내 PBR · BPS 분모(밖 4): 같은 뿌리 — 국내 관문 재고에 행을 더한다(신종자본증권 자본은 `dart:HybridBonds` 표준 태그로 원장이 준다).
- 관문 절차(밖 6): 국내 순서 절 — 마이그레이션 → FSR 전 창 → 받기 → 유도 → 미해결 판정 → 화해 → `PresetResultSnapshotTest` 전후 → 뒤집은 jar.

### 8d. 같은 성격 — 이미 들어간 갈래(보고)

- 미국 ADR-0191의 단위 오태깅 수리(TEM · ECHO · DDS)는 이 기준으로 걷었다(main `0495dd8e`).
- ADR-0189(지금 경로 분모)의 `unitChecked` ×10^±3 · ±6 수리 · 뒤 태그 대체도 같은 성격이다 — 사용자에게 보고한다(분모 n 368칸 · 단위 복원).

### 8e. v2.1 사다리의 지금 단면 ★ (0콜 · 받아 둔 2026 H1 XBRL 346 · 2분기 3개월 · 연결)

- 자본 구조가 보통주뿐 → 배분 0: **256**.
- 표준 E × W로 정함: **46**(현대차 0.227 · 한화 0.215 · 미래에셋 0.249 · 삼성전자 0.122 · 기업은행 0.074 · KB 0.021 · JB 0.031 · 247540 2.64는 따로 연다).
- 못 정함: **44** — 표준 보통주 가중평균이 그 보고서에 아예 없음 32(하나금융 · 롯데지주 · LG · SK텔레콤 · 한국가스공사 · 한화생명 · CJ · 아모레퍼시픽 · 아모레G · CJ제일제당 · S-Oil · 현대건설 …) · 누계에만 4 · EPS 없음 4 · 보통주에 구형우선주를 섞음 2 · 가중평균이 보통주 수와 어긋남 2(신한 0.49배 · 차바이오텍).
- 신종자본증권 몫을 직접 주는 재료는 이름이 회사마다 다르고(`DividendToHybridBond` · `DividendsPaidToHybridBond` · `InterestPaidToHybridBond` · `InterestExpenseForHybridBond` · 회사 확장 이름) 대부분 그 기간 귀속액이 아니라 현금 지급액이다 — 조합하지 않는다(8a).
- 귀결: 국내 관문 켬에서 최신 TTM PER · EPS 계열이 약 44종목(편입의 약 13%) 미표시 — 지금은 우선주 · 신종자본증권 몫만큼 틀린 값이 나가는 자리다. 2024 앞 보고서는 보통주 밖 지분상품이 있는 약 90종목에서 못 정함(5년 창).

### 8f. 8e 정정 — 측정 도구가 표준 구성원을 버렸다 ★ (0콜 · 같은 346 · `kr3/v22.py`)

- 8e의 판독기는 W · E를 축 없음 또는 `ClassesOfShareCapitalAxis` × `OrdinarySharesMember`만 받았다. 346 파일의 W · E 축 · 구성원을 전수로 세면(`kr3/mem.py`) 표준 분류 구성원이 셋 더 있다 — `dart:ReportedAmountMember`(W 34 · E 24 파일 · DART 표준 「공시금액」 구성원) · `ifrs-full:ClassesOfOrdinarySharesAxis` × 보통주 · 공시금액(W 11) · `ComponentsOfEquityAxis` × `EquityAttributableToOwnersOfParentMember`(W 3 · 하나금융 · 한화생명) · `ParentMember`(1).
- **판정 규칙(일반)**: 연결 축을 뺀 모든 구성원이 허용 집합 {`OrdinarySharesMember` · `dart:ReportedAmountMember` · `EquityAttributableToOwnersOfParentMember` · `ParentMember`} 안인 사실만 후보 · 같은 (개념, 기간, 기저)의 후보가 둘 이상 값이면 상충 → 못 정함. 우선주 · 자기주식 · 중단영업 · 회사 확장 구성원은 후보 밖(회사 확장 구성원만 있는 003670 포스코퓨처엠은 못 정함).
- **다시 잰 단면**: 배분 0 **256** · E × W **55**(+9 — LG 1.95% · 롯데지주 24.1% · 하나금융 3.6% · 한화생명 15.3% · SK텔레콤 1.06% · BNK 3.0% · 우리 3.8% · 000990 0.22% · 011200 −0.02%) · 못 정함 **35** = W가 어떤 축으로도 없음 25(유한양행 · CJ대한통운 · 현대건설 · CJ · 아모레G · 효성 · DB손보 · GS건설 · S-Oil · HLB · 가스공사 · GS · 아모레퍼시픽 · CJ제일제당 · 이마트 · 한진칼 · HL만도 …) · 3개월 W 없이 누계만 2(039490 · 078600 — 누계 E × 누계 W − 1분기 보통주 몫으로 조합된다 · 1분기 보고서가 있어야 함) · EPS 없음 4 · 구형우선주 섞음 2 · W ÷ 보통주 수 어긋남 2(신한 0.49 · 차바이오텍 1.17).
- 귀결 정정: 국내 관문 켬에서 최신 TTM 미표시 약 **33종목**(편입의 약 9.5%) · 2024 앞은 8b 그대로(재지 않음 — 드라이런이 센다).

## 9. 구현 계획 v1 (2026-10-25 · 최종 — 반증 대상)

### 9a. 실측 ★ (이 계획을 받치는 것 · 내가 쟀다)

- **판마다 자기 XBRL**(DART 5콜): 고려아연(00102858) `list.json`이 FY2024 판 셋(20250320001542 원 · 20250331003521 정정 · 20260813001711 정정)을 주고, `fnlttXbrl.xml?rcept_no=…`가 판마다 다른 인스턴스를 준다(md5 셋 다름) — 원 판 `ProfitLoss` 194,781,564,506 = 우리 revision 0 행 `net_income`. 대조: 기아 2026 반기 원 · 정정은 같은 인스턴스(재무 안 바뀐 정정).
- **판 열쇠**: `financial_statement`에 접수번호가 없다 · `published_at` = 접수번호 앞 8자리(`DartReportPeriods.publishedAt`) · 국내 재무 행 20,383 · 정정 판 74 · 최신 아님 59(2020 뒤 · Q4 빼고).
- **옛 형식**: 2019 · 2020 사업 · 2020 1분기(기아) 인스턴스는 2019 분류체계 · 기본 이름공간 · 주석 태그 없음(EPS 본표만 · W 없음).
- **구조 재료(표준 이름 · 346 파일 전수)**: `dart:BasicEarningsLossPerSharePreferredStock` 33 · `dart:HybridBonds` 31 · `ifrs-full:WeightedAverageNumberOfInstruments…OtherThanOrdinaryShares` 27 · `ifrs-full:ProfitLossAttributableToParticipatingEquityInstrumentsOtherThanOrdinaryShares…` 21 · `ifrs-full:BasicEarningsLossPerInstrument…OtherThanOrdinaryShares` 16 · `dart:IssuedCapitalOfPreferredStock` 7.
- **구조만으로 배분 0인 256 중 E × W 검산이 서는 142**: 반올림(±0.5 × W) 안 136 · 밖 6(030200 0.09% · 035250 0.16% · 038500 1.7% · 043260 −0.37% · 082740 0.27% · 267270 99.9%).
- **쿼터**: DART는 경로마다 하루 계획 10,000(`DartDailyQuota` 경로당 18,000 · api-sources skill) · `fnlttXbrl.xml`은 자기 경로 · 인스턴스 zip 0.2~0.5MB.
- **국내 소비자**: `GovernanceFactDeriver.withClassAxisShares`는 국내 행 `eps`가 늘 NULL이라 국내에 안 닿는다 · 국내 지배 NI NULL 행은 연결 NI로 폴백(OFS · 비지배 없음 — `FinancialMath.controllingNetIncomeOf` javadoc 2026-09-22 판정).

### 9b. 받기 — 국내 관문 켬 FSR 안(`FullStatementRefillJob` 종목 루프 · 미국 `ShareClassCollector` 꼴)

1. 종목마다 `list.json`(corp_code · `pblntf_ty=A` · FSR 창 시작 ~ 기준일 · 페이지 끝까지) → 정기 공시 (접수번호, 보고서 코드, 사업연도). 보고서 코드 산식은 공시 목록 장부(`filing_consumption.reprt_code` 주석)와 같은 것을 재사용한다.
2. 그 종목 재무 행 중 Q4가 아닌 판마다 `published_at` = 접수일인 접수번호를 고른다(같은 (사업연도, 보고서 코드) · 같은 날 둘이면 큰 번호). 못 찾으면 그 판은 `nokey`.
3. 고른 접수번호 중 재료 표에 지금 판독기 판의 OK · NO_XBRL 행이 없는 것만 `fnlttXbrl.xml` 1콜 → zip의 `.xbrl` 하나를 판독기에 먹여 재료 행을 쓴다. ZIP · 인스턴스는 안 둔다.
4. 실패는 접수번호 단위 FAILED(종류 · 메시지) · 한도(`020` · 우리 쿼터)는 FAILED가 아니라 그 실행의 미완료로 남기고 다음 실행이 잇는다. 종목 실패는 종목 단위 격리(`StockTaskExecutor`).
- 일일: 공시 소비의 좁힌 FSR이 새 공시를 받으면 같은 길로 1~2콜.

### 9c. 판독기 `DartXbrlFactReader`(판 1 · 이름공간 인지 · 두 형식)

재료 개념만 맥락과 함께 남긴다 — (개념, 기간 시작 · 끝/시점, 연결 축 구성원, 나머지 축 · 구성원 목록, 값, decimals):
- E `ifrs-full:BasicEarningsLossPerShare` · E_계속 `…FromContinuingOperations` · W `ifrs-full:WeightedAverageShares`
- 직접 `ifrs-full:ProfitLossAttributableToOrdinaryEquityHoldersOfParentEntity` (+계속영업 판)
- 앵커 `ifrs-full:ProfitLossAttributableToOwnersOfParent` · `ifrs-full:ProfitLoss`
- 우선 `dart:BasicEarningsLossPerSharePreferredStock` · `ifrs-full:BasicEarningsLossPerInstrument…OtherThanOrdinaryShares` · `ifrs-full:WeightedAverageNumberOfInstruments…OtherThanOrdinaryShares` · `ifrs-full:ProfitLossAttributableToParticipatingEquityInstrumentsOtherThanOrdinaryShares…`
- 구조 `dart:HybridBonds` · `dart:IssuedCapitalOfPreferredStock`
- 그 밖 개념 · 회사 확장 이름공간 개념은 버린다(확장 구성원은 맥락에 남겨 판정이 거른다).

### 9d. 판정 — (종목, 판) · 행 기간 · 순수 함수 `KrCommonIncome`

- **기저(앵커)**: 행 기간과 같은 기간 · 연결 구성원(또는 축 없음)의 `ProfitLossAttributableToOwnersOfParent`가 행 `net_income_controlling`과 같으면(1원 · 상대 1e−9) 연결, 별도 구성원의 `ProfitLoss`가 행 `net_income`과 같으면 별도(지배 NULL 행) · 둘 다 아니면 `none:anchor`. 이후 재료는 그 기저 맥락만.
- **후보 사실**: 연결 축을 뺀 구성원이 전부 허용 집합 {`OrdinarySharesMember` · `dart:ReportedAmountMember` · `EquityAttributableToOwnersOfParentMember` · `ParentMember`} 안 · 같은 (개념, 기간)의 후보 값이 둘 이상이면 상충.
- **구조**: 보통주 밖 지분상품 있음 = 그 판 인스턴스에 우선 개념 하나라도(값 ≠ 0) · `HybridBonds` ≠ 0(기말) · `IssuedCapitalOfPreferredStock` ≠ 0 · 또는 주식총수(`governance_report_row` `SHARE_TOTALS` 사업연도 Y · Y−1의 11011) 합계 발행 > 보통주 발행.
- 사다리(행 기간 = 3개월 분기 · 연간):
  1. 구조 없음 → E · W가 후보로 서면 |지배 NI − E × W| ≤ 0.5 × W 검산 · 밖이면 `none:eps-closure` · 안이거나 검산 재료 없음 → `kr-ni`(값 = 행 지배 NI · 지배 NULL이고 기저가 별도면 행 NI · 지배 NULL인데 연결이면 `none:no-controlling`).
  2. 구조 있음 → E 상충 · W 상충 → `none:conflict` · E 없음 → `none:no-eps` · W 없음 → 누계 갈래(아래) 아니면 `none:no-w` · W ÷ 보통주 수(같은 해 `ISSUED_SHARES_COMMON − TREASURY_SHARES_COMMON`)가 0.9~1.1 밖 → `none:w-vs-common` · W가 보통주 + 우선주 수와 3% 안이고 보통주 수와 3% 밖 → `none:w-includes-pref` · 그 밖 → `exw`(값 = 직접 태그가 E × W ± 0.5 × W 안이면 그 값, 아니면 E × W).
  - 누계 갈래: 3개월 E · W가 없고 누계(사업연도 시작 ~ 행 끝) E · W가 서면 누계 몫 − 같은 사업연도 앞 분기들의 누계 몫(앞 판 재료) — 앞이 하나라도 못 정하면 `none:cum`.
  - 계속영업: 중단영업이 있는 행(국내 F2b 갈래)에서 E_계속이 후보로 서면 C(E_계속 × W) · 아니면 T.
  - **배당성향 성분** `preferred`: 구조 없음 → 0 · 있음 → 우선 직접 태그가 E_p × W_p ± 0.5 × W_p 안이면 그 값, 아니면 E_p × W_p(`dart:` · `ifrs-full:` EPS 둘이 다르면 상충) · 재료 없고 우선주 수가 0이면(신종자본증권만) 0 · 그 밖 NULL(배당성향 미표시).
- **Q4**: FY 몫 − Q1~Q3 몫(각 분기는 `published_at` ≤ Q4 행 `published_at`인 마지막 판) · 그 세 판의 지배 NI 합 + Q4 행 지배 NI = FY 행 지배 NI(1원)이 아니면 `none:q4-vintage` · 하나라도 못 정하면 `none:q4` · 다 `kr-ni`면 `kr-ni`.
- 2024 앞 판(주석 태그 없음): 구조 있음이면 W가 없어 `none:no-w` — 그대로 미표시(8b).

### 9e. 저장 — 시험 마이그레이션 `V70_6__kr_common_income.sql`(국내 관문 직전에 main으로)

- `dart_report_xbrl_fact`(corp_code, rcept_no PK · stock_id · bsns_year · reprt_code · reader_version · status OK/NO_XBRL/FAILED · facts jsonb · failure_kind · failure_message · fetched_at) — `sec_filing_class_fact` 꼴 · 평생 한 행 · 판독기 판이 바뀌면 다시 받는다.
- `financial_common_income`(V70_5)에 열 셋 — `preferred numeric(24,2)` · `income_snapshot numeric(24,2)`(붙일 때 행 `coalesce(net_income_controlling, net_income)`) · `published_snapshot date`. 미국 행은 셋 다 NULL. 국내 갈래 이름 `kr-ni` · `exw` · `cum` · `q4` · `none:*` · `nokey`(값 제약은 `kr-ni`에 값을 허용 — 미국 `ni`와 달리 값을 박는다: 국내 지배 NULL 행의 폴백을 판정 때 정해 두려고).
- V70_5 · V70_6은 먼저 여는 관문이 함께 옮긴다(reload-gate의 V70_2~4 규칙과 같다).

### 9f. 유도 — `IndicatorReconciler.deriveShareBases`(국내 관문 켬) → `KrCommonIncomeDeriver`

- 대상: 국내 재무 행이 있는 종목 전원(편입 · 과거 편입) · 외부 0콜 · 종목마다 트랜잭션 · 그 종목 국내 행의 `financial_common_income`을 통째 바꾼다.
- 시각: 평일 14:18(`deriveReconcileInputs`) · 수동 FR · FSR · 공시 소비 뒤(`REDERIVE_SHARE_BASIS_AFTER`) — 미국과 같은 걸이.

### 9g. 읽기 — 국내 관문 켬 · 국내 시장만

- `IndicatorDataReader`: 국내 켬이면 `*_COMMON_KR` 질의 — `ci.report_code = f.report_code AND ci.published_snapshot = f.published_at AND ci.income_snapshot IS NOT DISTINCT FROM coalesce(f.net_income_controlling, f.net_income)` · 안 맞거나 행이 없으면 `nokey`(미표시).
- `CommonIncomeRead`에 `preferred` · `FinancialFigures.commonNetIncome`은 그대로(보통주 몫) · 새 `dividendIncome()` = 보통주 몫(중단 포함) + `preferred`(미국 행은 `preferred` 0으로 읽는다 — 미국 결정 「분모는 보통주 몫」 그대로) · `DividendPayoutCalculator`가 그것을 읽는다.
- `FinancialMath`: 국내 켬 행은 `readsCommonIncome` 참 → 창 단위 연결 폴백 안 탐(폴백은 판정 때 행마다 정해졌다 — 9d 1번).
- `StockReader` · 재무 탭: `withCommon`을 국내 켬에도.
- 소비자 전수(grep `financial_common_income|CommonIncomeRead|readsCommonIncome|commonNetIncome|commonContinuingIncome|commonIncome` — 22파일): PER · EPS_TTM · PER_MEDIAN_5Y · EPS_CAGR_5Y · EPS_GROWTH_TTM_YOY → `controllingTtm` · PEG · PER_DISCOUNT_5Y · PER_PCTL · PER_SECTOR_* → PER에서 유도 · DIVIDEND_PAYOUT → `dividendIncome` · EXEC_PAY_TO_NET_INCOME → 지배 NI 그대로(안 바뀐다) · `GovernanceFactDeriver` → 국내 안 닿음 · 재무 탭 · 워치리스트(PER 열 — 같은 저장 지표).

### 9h. 검증 · 드라이런 (싼 재료부터)

1. 단위 시험: 판독기(두 형식 픽스처 — 받아 둔 2026 H1 하나 · 2020 사업 하나를 잘라서) · 판정 갈래마다 · Q4 · 누계 · 앵커.
2. **0콜 스윕** `DartCommonIncomeSweepTest`(`REPLAY_KR_XBRL_DIR` · 행은 운영 SELECT TSV) — 346 단면이 파이썬 `v22.py`의 256 / 55 / 35와 같은지(구현 둘의 맞대기) · 갈래별 수.
3. 이력 표본(20종목 × 2019~2026 판 · 약 600콜 · 탐색 재료 폴더 · 판정 뒤 지움): 판마다 갈래 · 5년 창 지표가 서는 종목 수.
4. 관문 꺼짐 = main 바이트(스윕 · 지표 스냅숏).
5. 작은 DB(의심 종목 10 · 스키마 + 그 행만): FSR 켬 받기 → 유도 → `computeAt` 여러 날(공시일 전후 · 정정 판 경계).

### 9i. 관문 절차(국내 순서 절에 더함)

V70_5 · V70_6 옮김 → FSR 전 창 켬(받기 포함 · `fnlttXbrl` 약 11,000콜 → 이틀에 나뉠 수 있다 — 미해결 0까지) → 재료 미해결 판정(`dart_report_xbrl_fact` FAILED · 옛 판 0) → 유도 → 화해 → `PresetResultSnapshotTest` 전후 → 뒤집은 jar.

### 9j. 재지 않았다

- 이력(2019~2025) 판의 갈래 분포 · 5년 창 커버리지(9h-3이 잰다).
- 앵커 일치율(행 지배 NI 대 XBRL — 국내 매퍼의 비표준 계정 행이 어긋날 수 있다).
- `list.json` 판 ↔ 행 `published_at` 짝 실패 수.
- 다중 우선주 종류(1우 · 2우B)가 회사 확장 구성원으로만 오면 `preferred` NULL — 수.
- 0.9~1.1 · 3% 문턱의 근처 수.
