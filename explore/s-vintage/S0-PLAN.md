# S0 — 순이익 기저 계열 (계획 v1 · 2026-10-04 · 반증 전)

[S]에서 떼어 먼저 닫는 묶음이다(FINDINGS 0절 — 사용자가 판단을 맡겼고 내 판단). 재적재·백필은 이 묶음 끝에 한 번.

## Context — 직접 잰 것(`.factcache` 0콜 · DB SELECT · 2026-10-04)

- **뿌리 둘**(`SecEdgarFacts`):
  1. `controllingIncomeQ4`의 마지막 갈래가 `fy.netIncome() − Σq.netIncome()`을 뺀다. ADR-0159 뒤로 `net_income`은 연결이라 **Q4 지배주주 = Q4 연결**이 된다.
     연결이 게이트에서 null이면 같은 갈래가 null을 낸다(CCL·KDP). 같은 함수의 「소스 연결만 있고 비지배 없음」 갈래도 연결을 그대로 낸다.
  2. `controllingIncome`(저장 지배주주)의 마지막 폴백 `v.get(NET_INCOME)`은 첫 후보 `NetIncomeLoss`가 없으면 `ProfitLoss`(연결)를 집는다 — 비지배를 내는 회사에서도(ARES 2024 원판).
     Q4만 고치면 이 분기들을 빼서 Q4가 음수가 된다.
- **크기**(미국 편입 · Q4 끝 ≥ 2020-07 · 지표 창 양 끝에 보이는 판): Q4 지배주주 ≠ FY − ΣQ **1,325라벨 · 421종목**(1,302가 정확히 연결) · 빈 칸 **197 · 95**.
  소비자(현 편입 · 창 255일 · TTM 창에 그 Q4가 낀 셀-일): TTM 1% 이상 오차 **32,193 · 161종목**(부호 반전 529) · 빈 분기 **5,857 · 35종목** ·
  DB 2026-10-01: `ACGL·CCL·GATX·KDP·MKTX·SEIC·TER`의 `EPS_TTM` 없음. 읽는 쪽: `sumControllingNetIncome`(PER·EPS·EPS 성장·PEG·PER 5년 중앙값·할인) · `roeBasis`(ROE TTM).
- **기각돼 있던 것의 재측정**: ADR-0154 §버린 대안 「`ProfitLoss`가 이긴 행을 버린다」(버리는 쪽이 더 크게 틀린다) — **지금도 선다**:
  `sumControllingNetIncome`은 분기 하나가 비면 네 분기 다 연결로 떨어진다. 그리고 「창에 비지배 팩트가 없으면 연결 = 지배주주」는 회사가 다른 기간에 비지배를 내면 78.7%만 맞다.
  → **null로 만들지 않는다. 더 나은 값이 있을 때만 갈아끼운다.**
- **더 나은 값**: 비지배를 내는 회사의 답 아는 11,964칸(`NetIncomeLoss`·`ProfitLoss`·보통주 귀속이 같은 창)에서 지배주주와 1% 안 — 보통주 귀속 68.6% · 연결 49.4% ·
  더 가까운 쪽 보통주 62.5% · 연결 28.7%. 대리 표본이다(폴백이 도는 창은 `NetIncomeLoss`가 없는 창).

## 수정

1. `controllingIncome` 마지막 폴백: 비지배 없음(`noNoncontrollingIncomeBy`) → 지금처럼 `NET_INCOME` 첫값 · 아니면 **보통주 귀속 → 없으면 `NET_INCOME` 첫값**.
   `consolidatedIncome`은 안 바꾼다(지배 + 비지배 합 갈래에 보통주 귀속을 넣으면 우선주 배당만큼 연결이 틀어진다).
2. `controllingIncomeQ4` 마지막 갈래: **지배주주끼리** 뺀다(`NET_INCOME_CONTROLLING` 소스 확인 → `fy.netIncomeControlling() − Σq.netIncomeControlling()`).
3. `controllingIncomeQ4` 「소스 연결만 · 비지배 없음」 갈래: 비지배 없음 게이트를 통과할 때만 연결을 지배주주로 · 아니면 2로.
4. 거짓이 된 javadoc(「마지막 갈래의 피감수는 이미 지배주주 기저」)과 `controllingIncome` javadoc(「어느 갈래에서도 값을 잃지 않는다」의 근거)을 고친다.
5. 후보 — 재고 넣는다: Q4 소스 `NetIncomeLoss`에 연결을 실은 오태깅(QSR형 약 6 — 분기용 `mistaggedControlling`이 Q4 소스에 안 걸린다).

**변형 스윕 결과(`S_NIFIX=v2` · 이 브랜치)**: 바뀐 칸 지배주주 순이익 4,376(그 밖 2 — 판 집합 변화) · 판 −32 / +58 ·
Q4 ≠ FY − ΣQ 1,325 → **123**(남은 것 대부분 [S]의 Q4 세대 혼합) · 빈 칸 197 → **5** · **값 → null 0**(Writer 층 전부 도달: 파생 덮음 · 소스 값 → 값 · null → 값) ·
소비자 TTM 1% 이상 오차 32,193 → **1,484** · 빈 분기 5,857 → **399**.

## 펜스 · 시험 · ADR

- 새 premise: 「Q4 지배주주 = Q4 연결인데 그해 FY 비지배가 1%를 넘는 행 0」(지금 1,302 근처 — 정확한 술어는 DB로 같은 수를 내도록 맞춘다) ·
  「소비자 TTM 지배주주 창에 빈 분기」 수(지금 DB로 센다).
- ADR-0159 §검증 4는 main에서 정정했다(1c7e7f2). 새 ADR-0165(S0) — [S]는 그다음 번호.
- 시험: NCI 회사 파생 Q4(ACM형) · 분기 직접 태그 없음 + 보통주 귀속(ARES형) · 연결 null(CCL형) · 비지배 없는 회사 무변화 · 소스 연결만(게이트 양쪽).

## 드라이런 · 재적재

- 스윕 diff(위 결과를 구현 코드로 재현 — 변형 코드는 탐색 브랜치에서 지운다) · Writer 층 분류 · 소비자 셀-일 · 판 +58/−32의 정체(이른 판이면 `--insert-earlier-vintages`가 필요).
- 기준선: 덤프 · 전 프리셋·저장 화면 `PresetResultSnapshotTest` · 재무 지표 스냅숏(월요일 10-05 평일 배치가 리밸런싱 일시 상태를 닫은 뒤).
- 재적재(사용자 승인): `financial-refill --universe=US_TOP1000`(연수·이른 판 플래그는 판 정체로 정한다) + 창 안 과거 편입 `--tickers`(파일로) · 그날 SEC에서 새로 받는다.
- 백필(사용자 승인): 무효화된 종목 — 자동 백필 상한 100을 넘으므로 손으로 · 드라이런 날짜 여럿(보존 창 경계 · Q4 공시일 전후 · 리밸런싱일).
- **값을 바꾸는 코드는 재적재 직전까지 main에 머지하지 않는다**(bootRun 수동 잡 · jar 재빌드로 샌다). 구현은 별도 브랜치에서 하고 시험·스윕을 돌린다.
