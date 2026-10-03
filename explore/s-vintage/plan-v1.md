# [S] 영업이익 유도가 못 덮는 자리 — 계획 v1 (2026-10-03)

지배 ADR: ADR-0104(영업이익 유도). 관련: ADR-0157(적재/지표 경계), ADR-0151(Q4 세대), ADR-0150(부분 원가).
도구: `.factcache/`(companyfacts 1,217종목, 파일 날짜 09-15~09-27, 0콜) + SEC Archives 6콜(AMG·DVN·GLXY 10-K R페이지).
스크래치 스크립트: `$SP/s/{ledger,recipes,r3,pairs}.py`, `$SP/s/q4mix.sql`.

## 0. 실측 — progress의 [S] 서술이 맞았나

| progress가 적은 것 | 지금 잰 것 |
|---|---|
| ROK·GOSS 2칸이 ROIC_MIN_3Y는 내고 ROIC_PRETAX를 못 낸다 | **둘의 뿌리가 다르고 둘 다 영업이익 유도가 아니다.** GOSS = 최근 분기 자산 76.9 − 유동부채 137.8 < 0 → `RoicPretaxCalculator`가 정의상 null(투하자본 ≤ 0). **펜스가 정의역에 운 것**이다(GOSS는 10-02 편출). ROK = **Q4 세대 혼합**(아래 4절). |
| 후보 16종목(Rev+OpEx, COGS 없음) | 원장 기준 모집단이라 **기존 갈래가 이미 덮는 AFL·CACC·GATX·PRU 4가 섞였다.** 코드 갈래 조건으로 세면(유도 NULL ∧ revenue ∧ operating_expenses) **13**: AER AMG APO ARES **ASTS** BGC BRO CASY DVN EVR GLXY HOOD TPG |
| 모집단 183(최신 FY에 OI 한 행도 없음) | 182(리밸런싱 뒤). **유도까지 NULL인 것은 87종목**(FY≥2023 `is_latest` FY 중 한 행이라도 NULL) — 이것이 [S]의 진짜 모집단이다 |

87의 재료 조합(최신 FY 행, DB 컬럼): RC 13 · RCG 10 · R 9 · RCG 8 · RC+IE 8 · **RO 6+3+2+1** · 은행 부분재료 **R+nex 5 · RC+nex 1 · R+nex(pbt 없음) 1 · inx 1 · iex 1 · ex 1 · x 1** · O만 2(IMVT·SMMT) · T만 1(RKT) · 기타.

## 1. 은행형 — 순이자손익을 재료로 연다 (가짜 한계)

- ① **물은 곳**: companyfacts(캐시). JPM·FHN·IBKR·RJF·SOFI·SYF는 총이자수익을 `InterestIncomeOperating`(IIO)로만 내고 `InterestAndDividendIncomeOperating`(IDIO)가 없다. OMF는 `InterestExpense*`가 없다. 일곱 다 `InterestIncomeExpenseNet`(NetII)·`NoninterestIncome`·`NoninterestExpense`를 낸다.
- ② **대조군**: IDIO − IE = NetII 항등식이 **59종목 5,586칸 중 99.0%가 1% 이내**(어긋난 곳 MS 10·CVSA 8·NTRS 3 …). JPM 2024 유도 85,759M = 세전 약 75B + 대손충당금 약 10.7B(은행형 정의가 충당금을 안 뺀다 — ADR-0104 기존 정의 그대로).
- ③ **모집단**: 위 87 중 은행 재료 보유. NetII+NII+NIE가 다 오는 것 **7종목 · FY 21행**(2023~2025).
- ④ **뿌리**: 은행형 갈래가 재료를 IDIO·IE 두 태그로만 받는다. 어댑터는 NetII를 이미 읽는다(`NET_INTEREST_INCOME`, 은행 매출 유도용 — ADR-0157) 저장만 안 한다.
- **버린 것 — IIO를 IDIO의 둘째 후보로**: IIO·IDIO 둘 다 오는 463칸 중 **38.7%만 1% 이내** — KEY(중앙 0.6%)·HOMB·MS·CFG·WAL이 IIO에 **이자수익의 조각**을 담는다. 후보 순서로는 못 가른다.
- **처방**: `financial_statement.net_interest_income`(원자료 그대로) + `operatingIncomeOf` 은행형 둘째 식 `NetII + NII − NIE`(첫째 식이 null일 때만). 남는 은행 부분재료(ALLY·SCHW·SNEX·SF·JEF·KKR)는 재료가 하나 더 모자라 이번에 안 열린다 — 종목별로 무엇이 없는지는 위 표.

## 2. 총비용 후보 + 세전 대사 — 새 갈래

### 2-1. 가드 없는 `Revenues − OperatingExpenses`는 발화 칸에서 틀린다
- **답 아는 칸(코드 갈래 조건 그대로: 앞 갈래 넷이 다 null ∧ 완전 원가 없음 ∧ OI 신고)**: 5,090칸 · 128종목 · **1% 이내 88.4% · 10% 이내 91.0% · 부호 반전 1.3%** — ADR-0104 기준(83.3% · 88.6% · 3.4%)보다 낫다.
  - api-spec의 「28% 나쁨」은 **코드가 이 갈래로 안 보내는 칸**(매출총이익이 있어 GP−OpEx가 먼저 받는 칸 등)을 섞은 모집단의 수다 — `data-pipeline.md` 「코드가 안 타는 모집단에서 재면 멀쩡한 레시피가 46%로 보인다」의 그 자리.
- **그러나 발화 칸(현 편입 13종목)을 원 공시에 대 보니 셋이 오답이다**:
  - `DVN` — `OperatingExpenses` 12,998 = 총비용 12,228 + **법인세 770**. 유도값 2,942 = **순이익**(10-K R2 확인).
  - `GLXY` — 회사의 영업수익은 「Revenues and gains/(losses) from operations」 43,758M인데 우리 매출(`Revenues`)은 42,597M(영업 이익 1,161M 빠짐). 유도 −802M vs 회사 자신의 영업 결과 **+359M — 부호 반전**(10-K R3 확인).
  - `CASY` — 부분 원가(`CostOfGoodsAndServiceExcludingD&A`, 2018~)가 따로 있고 OpEx가 그 아래 항목이라 유도 12,574M vs 순이익 502M. **종목 단위 PARTIAL_ONLY로는 못 잡는다** — CASY는 2009~2020에 완전 원가 태그를 냈다.
  - 나머지: AMG 533 = 회사 「총 연결비용」 줄과 정확히 일치(이자비용 133 포함), AER·BRO·HOOD ≈ 세전, ARES·ASTS·BGC·TPG는 유도 + 영업외손익 = 세전이 **정확히** 선다.
- **기존 갈래에는 DVN 모양이 거의 없다**: 현 편입 FY≥2019 유도 행 726 중 「유도 = 순이익(법인세 ≠ 0)」이 **1행(ACGL 보험형)**. 비대칭의 근거다.

### 2-2. 처방 — 세전 대사가 설 때만 쓴다
- **가드**: 유도값 X에 대해 같은 기간의 세전이익 P(저장된 `profit_before_tax`)가 없으면 **안 낸다**. 있으면 영업외손익 합계 N(`NonoperatingIncomeExpense`)이 있는 칸은 `|X + N − P| ≤ 1%·|P|`, 없는 칸은 `|X − P| ≤ 1%·|P|`(N이 없는 것을 0으로 두는 게 아니라 **X = P를 관측**하는 것). 1%는 저장소 표준(`BALANCE_TOLERANCE`, ADR-0104 정확도 단위)이다.
- **통과 칸의 정확도(답 아는 칸)**: 대사형 1,652칸 · 57종목 **99.2%** · 세전일치형 134칸 · 25종목 89.6%. 가드가 거른 칸의 정확도도 높다(세전일치 실패 1,539칸의 93.7%가 옳다) — **가드는 정밀도를 사고 재현율을 버린다**.
- **갈래 둘**(ADR-0104 갈래 순서 뒤에 붙인다 — 앞 넷이 다 null일 때만):
  1. `Revenues − OperatingExpenses` (완전 원가 null ∧ 매출총이익 null) + 가드
  2. `Revenues − OperatingCostsAndExpenses` + 가드 — 가드 없이는 18.9~35.6%로 기각감이지만 가드 통과 377칸은 **98.9%**. WRB가 열린다(83칸 통과).
- **발화 결과(현 편입, FY+분기 2023~)**: 통과 AER(6/9) · BRO(11) · ARES(17/21) · ASTS(14/15) · BGC(21) · HOOD(12/21) · TPG(3, 나머지 세전 없음) · APO(2/21) · WRB · GLXY(1/16 — 확인 필요). 거름 DVN · AMG · EVR · CASY(세전 없음).
- **재료**: `nonoperating_income_expense`·`operating_costs_and_expenses` 새 컬럼(원자료). `profit_before_tax`는 이미 있다.

### 2-3. 버린 것 (실측)
- **매출 없는 −OpEx**(IMVT·SMMT 같은 매출 전 바이오): 답 아는 1,626칸 57.2% · **부호 반전 515** — 매출을 다른 태그로 내는 회사가 섞인다. ADR-0104 §규칙 「없는 것을 0으로 두지 않는다」가 실측으로 선다.
- **매출총이익 − SG&A − R&D + 같은 가드**(ADR-0104가 무가드로 기각): 통과 2,758칸 정확도 **90.1%**(셋 중 최저)이고 결손 41종목 중 통과가 9종목에 **21칸 중 1~9칸씩 드문드문**이다(NUE 9 · ETN 5 · NKE 3 …). 재료 컬럼 둘(SG&A·R&D)이 더 든다.
- **IIO 후보**(1절) · **종목 단위 PARTIAL_ONLY 가드**(2-1 CASY).

## 3. 펜스

- **ADR-0104 첫 펜스가 정의역에 운다**(GOSS). 모집단에서 최신 점이 `자산 − 유동부채 ≤ 0`이거나 유동부채 null인 종목을 뺀다 — `RoicPretaxCalculator`의 정의역과 같은 술어.
- **새 전제**: 새 갈래가 낸 값이 가드를 지났는가 — 코드가 보장하므로 펜스로는 「새 재료 컬럼 세 개가 US에서 0행이 아니다」(적재가 안 붙으면 갈래가 소리 없이 죽는다 — 이자비용 태그 퇴역 때 그랬다).

## 4. ROK — Q4 세대 혼합은 ADR-0151 축이다 (이 묶음에서 안 고친다, 크기만 잰다)

- ROK FY2025 10-K(2025-11-12)가 원가 분류를 바꿨다(FY2024 매출총이익 3,193 → 3,851, 영업이익 첫 태그). Q4 유도는 `vintageOf(≤ FY 공시일)`로 **옛 기준 분기**를 빼서 Q4 GP 1,608(마진 69%) · FY2024 Q4 GP 1,428(70%) — **그럴듯한 오답**. 영업이익은 옛 분기에 없어 Q4 null → TTM null → ROIC_PRETAX 없음.
- **크기(현 편입 · 오늘 TTM 창의 Q4 vs FY최신 − 최신 Q1~Q3)**: 영업이익 22/771 · 매출 19/944 · 매출총이익 11/402 · 순이익 11/942가 1% 넘게 어긋남. Q4가 null인데 재계산 가능 6(ROK·WULF·GLXY·MKTX·SEIC·AUR).
- **왜 따로 가나**: 고칠 자리가 `SecEdgarFacts.deriveFourthQuarters`(세대 선택)이고 지배 ADR이 0151이며 검증이 「어느 세대가 같은 기준인가」라는 다른 질문이다. **크기 때문이 아니다.** 오답(Q4 혼합)이 결손(이 묶음)보다 나쁘다는 판단 기준상 **[S] 다음 차례로 바로 연다** — 사용자가 [S]를 골랐으므로 순서를 뒤집지 않고 보고한다.

## 5. 순서 (무해한 선행 → 드라이런 → 재적재·백필 한 번)

1. **선행 배포(값 변화 0)**: V58 — 컬럼 셋(`net_interest_income`·`nonoperating_income_expense`·`operating_costs_and_expenses`). `IndicatorDataReader`·`FinancialFigures`·`FinancialWriter`(insert·`ManagedColumn`)·`RawFinancialReport`·DART 쪽 null·픽스처. `operatingIncomeOf`에 갈래 셋(은행 둘째 식 · 대사 가드 둘). 새 컬럼이 0행이라 **어떤 지표 값도 안 바뀐다.** 시험: `OperatingIncomeDerivationTest`에 DVN·GLXY·CASY 모양의 거름 · ARES 모양의 통과 · 은행 둘째 식.
2. **어댑터**: `SecEdgarColumn`에 `NonoperatingIncomeExpense`·`OperatingCostsAndExpenses` 매핑, NetII를 저장 컬럼으로. **Q4 유도가 세 유량을 뺀다**(`buildQ4`·`nonNegativeFlow` 판정 — NonOp은 음수 가능, OCaE는 음수 불가). 스윕 헤더에 셋.
3. **드라이런**: ① 리플레이 JPM·OMF·WRB·ARES·DVN·GLXY·CASY·AER + 대조군 AAPL·MSFT·BAC·AIG ② `SecEdgarFactsSweepTest` 전/후 diff — **기존 컬럼 0 변화, 새 컬럼만 채워짐** ③ 새 갈래가 탈 (종목, 기간)을 SQL로(새 컬럼은 스윕 TSV에서) ④ 캐시 결손 5(IOVA·KOD·APGE·CRNX·EBC)는 그 전에 받는다(5콜).
4. **재적재(승인 필요)**: `financial-refill --universe=US_TOP1000` + 보존 창 안 과거 편입 중 영향 종목 `--tickers`. **수동 refill이 25일 시계를 되돌려 정기 적재(10-24)가 다시 밀린다**([V] 결함) — 승인 요청에 함께 적는다.
5. **백필(승인 필요)**: 원본층 날짜 하나(창 전체 재기록) + 파생층 보존 창 전체(A46). 백필 드라이런 날짜: 보존 창 경계 · 공시일 전후(JPM 10-K) · 미국만 거래일 · 리밸런싱 10-02.
6. **판정**: ADR §결과에 수 — 영업이익 유도 NULL 87 → ?, 갈래별 발화 칸, 가드가 거른 칸. phase-criteria 판정. progress [S] 교체.

## 6. ADR

새 ADR(0165): 「영업이익 유도에 순이자손익 식과 세전 대사 갈래를 더한다」 — 컬럼 셋(저장 구조) · 가드 · 버린 대안 다섯(IIO 후보 · 무가드 Rev−OpEx · 매출 없는 −OpEx · 가드 SG&A · 종목 단위 부분 원가 가드). ADR-0104 §버린 대안의 「매출총이익 − SG&A − R&D」 항에 가드 측정 한 줄. api-spec 「재료 자체가 없다」 절의 28%를 모집단 차이로 정정.
