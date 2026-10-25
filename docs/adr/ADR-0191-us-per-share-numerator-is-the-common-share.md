---
id: ADR-0191
status: 채택됨
date: 2026-10-25
applies_to:
  - "src/main/java/com/stockscreener/pipeline/datasource/us/shareclass/CommonIncome.java"
  - "src/main/java/com/stockscreener/pipeline/datasource/us/shareclass/CommonIncomeRows.java"
  - "src/main/java/com/stockscreener/pipeline/datasource/us/shareclass/IssuerTimeline.java"
  - "src/main/java/com/stockscreener/pipeline/datasource/us/shareclass/IssuerFacts.java"
  - "src/main/java/com/stockscreener/pipeline/datasource/us/shareclass/ClassFactReader.java"
  - "src/main/java/com/stockscreener/pipeline/collector/ShareClassDeriver.java"
  - "src/main/java/com/stockscreener/indicator/FinancialFigures.java"
  - "src/main/java/com/stockscreener/indicator/calculator/FinancialMath.java"
---

# ADR-0191. 미국 주당 지표의 분자를 보통주 귀속 이익으로 한다(관문 뒤)

## 결정

관문 켬(`stockscreener.gate.us`)에서 주당 지표(`PER` · `EPS_TTM` · `PER_MEDIAN_5Y` · `EPS_CAGR_5Y` · `EPS_GROWTH_TTM_YOY` — 의존 `PEG` · `PER_DISCOUNT_5Y` · 백분위 · 업종 상대)의 분자를 지배주주 계속영업 순이익에서 **보통주 귀속 계속영업 순이익**으로 바꾼다. `DIVIDEND_PAYOUT`의 분모도 보통주 귀속 순이익(중단 포함)으로 바꾼다 — 미국 배당 총액이 보통주 DPS × 보통주 수라서다.

1. **(공시, 기간)마다 정한다** — 클래스 축 엔진(`IssuerTimeline.commonIncomes` → `CommonIncome`)이 분모 n과 같은 재료로 정한다. 사다리(첫 번째로 서는 것):
   - `decl` 선언 NIC(`NetIncomeLossAvailableToCommonStockholdersBasic` · IFRS `ProfitLossAttributableToOrdinaryEquityHoldersOfParentEntity`). EPS × W가 있으면 부호 · 자릿수가 맞아야 한다.
   - `adj` 선언 배분(우선주 배당 · 참가증권 · GP 배분 — 묶음마다 태그 하나)을 지배 NI에서 뺀 값이 EPS × W와 닫힘.
   - `ni` 배분 태그가 없고 지배 NI가 EPS × W와 닫힘 — 배분 0(값 대신 표지).
   - `lp` LP 배분이 LP 단위 EPS × W와 닫힘.
   - `eps` 클래스 축 앵커가 아니고 W가 표지와 10배 자릿수 안이면 EPS × W(확인 안 된 W는 증인 전용 `epsw`).
   - `cls` 축 없는 EPS가 없거나 클래스 축 앵커면 인스턴스의 클래스 · LP 클래스 구성원 Σ EPS × W.
   - 그 밖은 못 정함.
2. **재무 행마다 붙인다** — `CommonIncomeRows`가 행의 판에 맞춘다. 자기 공시 · 행 기간 → 그 공시가 그 기간 손익을 안 실었거나 못 정했으면 같은 기간을 정한 앞 공시의 배분(그 키 NI − 보통주 몫)을 옮긴다. Q4는 어댑터(`SecEdgarFacts.buildQ4`)와 같은 판으로 FY − 세 분기.
3. **저장** — 관문 시험 마이그레이션 `V70_5`의 `financial_common_income`(재무 행 id · 붙일 때의 `report_code` · 값 · 기저 T/C · 갈래). 유도(`ShareClassDeriver`)가 판정과 한 트랜잭션에 갈아 넣는다. 판독기 판 7(분자 재료 태그 · 총 EPS를 축 하나짜리 칸에) · 엔진 판 5.
4. **지표** — `FinancialFigures.commonContinuingIncome`(F2b 중단 갈래 그대로 · 계속영업 EPS에서 지은 C는 그대로) · 관문 켬 창은 연결 폴백을 안 타고 못 정한 분기가 있으면 미표시다.
5. **곁 소비자** — 배당 주식수 20% 게이트(`GovernanceFactDeriver.withClassAxisShares`)의 함의 분자를 보통주 몫으로 · 재무 탭의 「보통주 귀속」 칸 · 받기(`ShareClassCollector`)가 분자 후보(`IssuerTimeline.numeratorCandidates`)의 인스턴스도 받는다.

관문 꺼짐은 코드 경로가 그대로다(지표 읽기는 클래스 축 줄이 있을 때만 새 칸을 읽는다).
설계 · 실측 · 반증의 정본은 탐색 브랜치 `explore/s-vintage-coherence`의 `explore/financial-sync/PERSHARE-NUM-PLAN-1024.md`(v1 「다시 짠다」 → v2 두 렌즈 「고쳐서 간다」 → v2.1)다.

## 왜

- **가짜 한계.** 원장은 보통주 귀속 이익을 정직하게 준다(NIC 태그 · 주당이익 × 가중평균). 분모는 보통주(클래스 축 N)인데 분자에 우선주 배당 · 참가증권 · GP 몫이 들어 있었다.
- 실측(2026-10-24 · 팩트 캐시 · 현 편입 924종목의 최신 FY): NIC를 실은 334 중 지배 NI와 1% 넘게 다른 것 100 · 20% 넘음 17. HPE · APG · LYV · JXN은 보통주 몫이 적자인데 PER이 나왔다. TPG 0.33 · MCHP 0.48 · VST 0.20 · 대형은행 5~8%.
- NIC가 없다는 것은 「배분 0」이 아니다 — TDG · GWW · BAH · J는 원장 EPS가 배분을 말하는데 NIC를 축 없이 안 실었다.

## 규칙

- MUST: 값의 단위는 (공시, 기간)이다. 행의 `report_code`는 그 기간의 최신 판이라 한 접수번호가 여러 기간 행을 싣는다(GS 2024 Q1 행 = 2025 Q1 10-Q · 현 편입 직전 TTM 행 618/3,834). 접수번호 하나에 값 하나를 두면 1년 전 TTM이 올해 값을 받는다(v1 반증).
- MUST: 배분 0(`ni`)은 값이 아니라 표지다 — 읽을 때 행 지배 NI(어댑터가 mistag까지 고친 값)를 쓴다. 키의 `NetIncomeLoss`에서 빼면 AMRX 2022 Q3처럼 부호가 뒤집힌다.
- MUST: 배분 태그가 있는 키에서는 「NI가 닫힌다」를 배분 0의 증인으로 받지 않는다 — EPS가 작은 분기의 반올림 폭이 배분보다 크다(NIC를 답으로 둔 칸의 거짓 증인 164 중 89가 그 키다).
- MUST: 반올림 반폭은 끝자리 0으로 짐작하되 상한 50만(백만 단위의 반폭)이다 — 상한이 없으면 1,000,000,000의 반폭이 5억이 되어 배분이 있는 분기도 닫힌다(`CommonIncomeTest`가 그 자리에서 붉었다).
- MUST: 확인 안 된 W(표지와 10배 자릿수 밖 · 기준이 없음)로 낸 EPS × W는 값으로 안 쓴다 — 행 지배 NI가 그 반올림 안에서 닫힐 때만 배분 0의 증인이다.
- MUST NOT: 원장이 뒤틀린 자리를 종목 특정 수리로 메우지 않는다(2026-10-25 사용자 기준: 「원장 자체가 뒤틀려서 특정 종목만을 위한 로직이 들어가야 한다면 정합성 훼손으로 판단해. 원장에서 주는 정보로 조합이 되면 개발을 해.」). 가중평균을 천 단위로 잘못 실은 공시(TEM · ECHO · DDS)는 배율을 찾아 고치지 않고 그 키를 못 정함으로 둔다 — 드라이런 첫 판은 고쳐 썼다가 이 기준으로 걷었다. 남은 갈래(클래스 구성원 · LP 축 · 희석 증인 · 연결 축 없는 NI)는 us-gaap 표준 태그로 모든 종목에 같은 규칙이다.
- MUST NOT: 값 갈래(`eps` · `decl` · `cls`)가 단위 오태깅의 지문을 가진 값을 내지 않는다. 지문은 둘 중 하나다.
  - EPS · W · NI 중 하나를 10^k(k = ±2 · ±3 · ±6) 옮기면 EPS × W가 NI와 닫힌다. 옮긴 후보 자신의 허용폭보다 NI가 클 때만 센다 — 아니면 NI가 반 센트 × W보다 작은 키(CLSK 2012)에서 자명하게 닫힌다.
  - 그 값 × 10^k가 NI의 반올림 안에서 같다.

  그 키는 못 정함이다 — 고쳐 쓰지 않는다(2026-10-25 사용자 기준). 거듭제곱이 아닌 어긋남은 배분이라 그대로 둔다(CQP GP 몫 · FIVE · ELF 상장 전 우선주 · ALB · GSAT 우선배당). 증인 전용 `epsw`에는 안 건다.
- MUST NOT: 관문 켬 창에서 못 정한 분기를 지배 NI나 연결로 메우지 않는다 — 그럴듯한 틀린 값이다. 미표시와 펜스가 답이다.
- MUST NOT: `net_income` · `net_income_controlling`의 뜻을 바꾸지 않는다 — 원장 `eps`와 맞대는 소비자(순위 오염 훅 · ADR-0126 축척)가 그 뜻을 읽는다. 배당 게이트만 보통주 몫으로 옮긴다.

## 버린 대안

| 대안 | 기각 근거 |
|---|---|
| NIC가 없으면 배분 0(지배 NI) | TDG 10% · SNEX 3.6% · CHRD 3.8% · GWW — 원장 EPS가 배분을 말한다 |
| 접수번호 하나 = 값 하나(v1) | 비교열 · 정정 행이 그 공시의 당기 값을 받는다(위 §규칙 첫 줄) |
| Q4 = FY − 9개월 누계(v1) | 어댑터의 Q4(FY − 세 분기)와 NIC 실린 FY 954 중 20이 1% 넘게 갈렸다(TPG FY2025 +4.5%) |
| 표지 증인(±12.7%)으로 비교열 · 연간 W 확인 | 주식수가 그만큼 바뀐 발행사의 정당한 W를 버린다(481키 · 172종목) — 10배 자릿수로 |
| 지표 계층에서 EPS × n으로 폐합 | 10-K 판정의 n은 Q4라 연간 행에 못 쓰고, 클래스 축 · 인스턴스 재료가 거기 없다 |

## 결과

**드라이런**(0콜 재생 · 2026-10-25 · `SecShareClassSweepTest` 틀 켬 · 재료: 팩트 캐시 + LP 공시자 1,271 · 인스턴스 3,287 — 현 편입의 2020-09 뒤 앵커 · 분모 후보 전부와 분자 후보 · 재무 행은 `SecEdgarFactsSweepTest` 관문 켬 · 보충 `off`)

현 편입 1,000의 창마다(행의 최신 판 · Q4는 2절):

| 창 | 분자 1% 넘게 바뀜 | 그중 20% 넘음 | 새로 비는 창 |
|---|---|---|---|
| 최신 TTM | 113 | 16 | **5** = 2(GLXY — 재편 전 기간이라 행 지배 NI 0 · 원장 판 경계 · DOX는 20-F라 분기 계열이 안 서 연간으로 간다 — 분석 착시) + 3(TEM · ECHO · DDS — 단위 수리를 걷은 뒤) |
| 직전 TTM | 124 | 25 | **9** = 6 + 3(TEM · ECHO · DDS) |
| 20분기 전 TTM | 123 | 23 | 17(2020~21 공시 · Q4 판 · 앞 판 옮김 실패) |
| FY 2~6번째 | — | — | **7** / 4,635 = 3 + 4(DDS 2023~2025 · TEM 2024) |

- **단위 수리를 걷은 뒤 다시 잼**(2026-10-25 · `0495dd8e` 전후 코드 · 같은 재료 · 세 발행사 표적 — 팩트 캐시 + 인스턴스 60 · 재무 행은 운영 SELECT): 걷기 전에는 세 종목의 창이 다 정해졌고 1% 넘게 바뀐 창은 없었다(최신 · 직전 0.0~0.1%) → 걷은 뒤 최신 · 직전 TTM과 FY 넷(DDS 2023~2025 · TEM 2024)이 비고(행 판정 `epsw-open` · `q4-epsw-open` — TEM 10행 · ECHO 11 · DDS 8) 20분기 전은 그대로(ECHO 4.4%는 걷기 전과 같다 · TEM은 창이 짧다). 그래서 위 표의 「바뀜」 열은 그대로이고 「새로 비는 창」만 늘었다(TTM +3 · FY +4) — 펜스(reload-gate 미국 분자 행 ③)는 이 수를 쓴다.
- **지금 경로 분모의 배율 복원도 걷은 뒤(2026-10-25 · ADR-0189 §결과 · 분자 엔진이 같은 고친 재료를 읽는다)**: 분자 키 `ni → none` 1,220 · `eps → none` 103 · `adj → none` 4다(2020-09 뒤 435키 · 27종목). 뿌리는 단위가 틀린 W로는 「배분 0」 증인이 안 선다는 것이다. 잃은 1,327키 중 1,326은 희석 W도 같이 틀렸다. 남은 하나는 ONTO FY2023으로, 희석 W와 희석 EPS가 닫히는데 희석 증인이 기본 EPS가 없을 때만 선다. 재무 행(운영 SELECT · 행마다 맞댐)으로는 정함 → 못 정함이 408행이고, 5년 창에서 220행 · 24종목(현 편입 173 · 19)이다. 현 편입 최신 TTM이 새로 비는 종목은 7(BRKR · CYTK · LOAR · MCD · PSN · RGEN · WAT)이다 — 위 표와 행 재료가 달라(그 표는 관문 켬 재무 스윕 행) 더하지 않고 따로 적는다.
- **단위 오태깅 지문(2026-10-25 · 같은 재료 · 걷기 판 대비)**: 값 갈래에서 숨긴 키는 143이다(`eps` 134 · `cls` 6 HESM · `decl` 3 CELH · SITE · WEX). 그중 NI와 정확한 거듭제곱 비가 아닌 것은 SITE · WEX의 선언 NIC 100,000 둘이다. 표지 없는 공시의 n 확인(ADR-0189)으로 W가 비어 선언 NIC를 받게 된 키가 13이다(AMRN · LAZ · VCTR — 천 단위 W 대신 원장의 NIC).
  - 재무 행: 정함 → 못 정함 47(5년 창 29 — HAL 17 · HESM 4 · BE 3 · NVMI · RGC · UCTT · WIX · WSE). 못 정함 → 정함 6. 값이 틀린 수에서 NI와 맞는 수로 바뀜 18(BE −13.7B → `ni` · CELH Q4 · HALO · RGC · WIX). 현 편입 최신 TTM이 새로 비는 것은 0이다.
  - 5년 창에서 몫이 NI와 100배 넘게 갈리는 행은 42에서 10으로 줄었다. 남은 것:
    - WEX FY2021 4판 — EPS 0 · NIC 137,000을 함께 틀리게 실어 0 근처끼리 「맞다」로 통과하고 거듭제곱 지문도 안 선다. 일반 규칙으로 못 가르는 잔여다.
    - ALB · GSAT — 우선배당
    - FIS 2022 Q4 — 계속영업 EPS(기저 C)
    - MDLN FY2025 — 분사 뒤 EPS
    - PNW · UWMC Q4 — 차감
  - 버린 대안: 희석 증인을 기본 W가 없는 키로 넓혀 ONTO FY2023을 살리는 것. 바뀌는 키가 305였다(none → ni 242). 그리고 전환우선주(if-converted)면 희석 분자가 NI 그대로라, 희석 폐합은 기본 몫의 배분 0을 증명하지 않는다. 같은 전제(「희석은 몫을 낮추기만 한다」)에 선 기존 POR 갈래(기본 EPS 없음)는 재지 않았다.
- 큰 변화(최신 TTM): IVZ · CAI · LYV · APG(보통주 몫 적자 — PER 미표시로) · ALB −74% · CELH −50% · JXN −43% · TPG −26% · MCHP −23% · ARES −16% · CQP −15% · BA −14% · TDG −10% · OXY −10% · C −9% · VST −9% · 지방 · 대형은행 5~8% · PAA −8%.
- 분모 판정(`financial_share_class_filing`이 될 TSV)은 같은 재료에서 main과 바이트까지 같다(60,940줄 · md5 `974bceee…`) — 판독기 판 7의 태그 추가가 분모를 안 움직인다.
- 받기 확장(분자 후보): 이 재료에서 1,559공시(현 편입 973) — 그중 앵커 · 분모 후보와 안 겹치는 것만 관문 날 새로 받는다.
- 드라이런이 잡아 고친 것(이 판 안): 10-K가 다시 실은 분기 표가 Q4를 막음(HON · KMB) · 표지 기준 없음(HRL · MKC) · EPS를 클래스 · LP 축에만(BKR · PAA) · 반폭 부풂 · 클래스 구성원 NIC(GLXY Up-C) · 한 기간 두 키(LSCC) · 희석만(POR) · 계속영업 EPS가 Q4를 막음(VRSN) · 받기가 분자 재료를 안 받음.

**시험**: `CommonIncomeTest` 19 · `CommonIncomePerShareTest` 8 · `ShareClassAxisReadTest` 유도 1건 더함. 대조군(배분 태그 막기 · 선언 NIC 확인 · 옮김의 행 NI · 반폭 상한)이 각각 그 시험에서 붉다.

**재지 않았다**
- 공시 보충만으로 쌓인 행(운영 꺼짐 현 편입 110행 · 92종목) — 재생이 보충 `off`였다. 그 공시는 앵커라 인스턴스가 있다.
- 현 편입 밖 종목(SUN 등) — 인스턴스를 안 받아 companyfacts만으로 돌았다(SUN 분기는 못 정함으로 남았다).
- 프리셋 필수 조건(`EPS_CAGR_5Y` 린치 두 띠) 통과 집합 전후 — 관문 날 백필 전후 `PresetResultSnapshotTest`로.
- 배분 중 중단영업 몫 · 갈래 `eps`의 정밀도(EPS가 작은 발행사).
- 운영 관문 켬 모집단은 이 캐시보다 크다(후보 · 과거 편입).

## 검증

- `./gradlew test --tests '*CommonIncomeTest' --tests '*CommonIncomePerShareTest' --tests '*ShareClassAxisReadTest'`
- 0콜 스윕: `SHARE_CLASS_DIR=<재료> SHARE_CLASS_TODO=auto SHARE_CLASS_FRAME=on SHARE_CLASS_COMMON_OUT=<키> SHARE_CLASS_ROWS=<재무 스윕 TSV · US_GATE=true> SHARE_CLASS_ROWS_OUT=<행> ./gradlew test --tests '*SecShareClassSweepTest' --rerun-tasks`
- 이 결정은 운영 자동 검증 대상이 아니다. 관문 뒤 경로다.
