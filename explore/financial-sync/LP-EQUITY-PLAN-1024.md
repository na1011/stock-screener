# LP·LLC 자본과 비지배 판정 — 계획 v1 (2026-10-24)

`docs/reload-gate.md` 「LP 자본 — 미국 자본 사다리가 `PartnersCapital…`을 안 읽는다」 행을 닫는다.
실측 중 같은 함수에서 **관문 꺼짐 운영 결함**이 하나 더 나왔고 둘이 얽혀 있어 한 계획으로 묶는다.
모든 값 변경은 `stockscreener.gate.us` 뒤(기본 꺼짐)에 둔다.

## 0. 재료와 도구

- 원장 원본: `.factcache` 1,218파일(+ LP 공시자 cf 보충 — 스윕 디렉터리 `lp/eq/cf` 1,271 링크).
- 0콜 스윕: `SecEdgarFactsSweepTest`(`REPLAY_SWEEP_DIR=lp/eq/cf` · `REPLAY_SUPPLEMENT_DIR=off` · `US_GATE=true` · `--rerun-tasks`).
  기준선 `base.tsv` = main `078164f5`(어댑터는 `88219786`과 같음) 관문 켬 91,959행.
- 판정 질의: 운영 SELECT(읽기 전용) — 현 편입 `universe_membership.effective_to is null` 1,000종목.
- 공시 단위(accn × end) 측정 스크립트 `m1~m7.py`(scratchpad). **어댑터의 판(이어받은 맵)과 다르다** — 공시 하나 안의 사실만 본다.

## 1. 실측 (★ = 원장·코드에서 직접 잼)

### 1-1. 관문 꺼짐 결함 — ADR-0159 「비지배 없음이면 총자본」이 뒤집혀 있다

코드: `SecEdgarFacts.controllingEquity` — `noNoncontrolling`(티커가 `MinorityInterest` 태그를 한 번도 안 냄) **이고** 직접 지배자본(`StockholdersEquity`)이
원시 총자본 1순위(`StockholdersEquityIncludingPortionAttributableToNoncontrollingInterest`)와 1% 넘게 갈리면 **원시 총자본을 지배자본으로 낸다.**

- ★ 전수(1,218): `MinorityInterest`가 없는 티커에서 같은 공시 안 두 태그가 1% 넘게 갈리는 칸 **659 · 20티커**(2020-09 뒤 378).
- ★ 가르는 축은 코드에 이미 있는 손익 비지배 술어다(`lastNoncontrollingIncome` 계열 세 컬럼 + 포괄손익 비지배 · **값이 0이 아닌 것만**):
  - **비지배 있음 8**: AMG 130 · ARES 89 · BKH 85 · BX 58 · UBER 51 · STEP 45 · TKO 25 · AXS 7 → 두 태그 다 맞고 **지배자본 = `StockholdersEquity`**.
    공시 단위 항등식(자산 − 부채 − 메자닌)이 비지배 포함 쪽을 확인: AMG 122 · BX 56 · UBER 51 · TKO 22 · STEP 25 · ARES 36(나머지는 항등식 불가/불일치).
  - **비지배 없음 12**: UNM 126 · NFG 10 · NKE 10 · PTCT 8 · KLIC 6 · R 6 · JPM 3 · TVTX 2 · CAKE · GSAT · MIDD · SOUN → 둘 중 하나가 쓰레기.
    항등식이 가른 칸은 **전부 `StockholdersEquity` 쪽**(UNM 87 · PTCT 8 · KLIC 6 · R 6 · JPM 2 · TVTX 2 · SOUN 1). 항등식이 없는 칸 62(NFG 10은 `StockholdersEquity`가 쓰레기 −76M · NKE·UNM·CAKE·GSAT·MIDD는 비지배 포함 태그가 쓰레기).
- ★ 손익 술어를 그대로 옮기면 틀린다(폴백 근거의 이식): 0인 태그를 존재로 세어 LPX·ICLR을 「비지배 있음」으로 읽었다(시안 2). 그리고 기존 세 컬럼만으로는 TKO(2026년 비지배를 포괄손익 태그로만 냄)를 못 본다(시안 4~5).
- ★ ADR-0159 §규칙이 근거로 든 사례가 거꾸로다: NKE 2017 Q1은 같은 공시가 `StockholdersEquity` **12,165M**과 비지배 포함 **85M**을 싣는데, 규칙이 **85M**을 지배자본으로 고른다(지금 기준선: 총자본 85M · 부채 21,071M 유도).
- ★ **운영 지금(10-23 지표 · 관문 꺼짐)**: ARES 지배자본 8,602M(원장 3,968M) PBR 4.50 · BX 21,062M(원장 9,015M) PBR 4.23 · TKO 8,331M(원장 3,396M) PBR 4.12 ·
  AMG 3,934M(3,038M) · BKH · UBER — **분모가 비지배 포함**. UNM 지배자본 **−1,870M**(원장 10,810M)이라 ROE·PBR 없음.

### 1-2. LP·LLC 자본 태그 — 뜻과 확인율

- ★ 우리 컬럼에 없는 태그: 총(비지배 포함) `PartnersCapitalIncludingPortionAttributableToNoncontrollingInterest` · `MembersEquityIncludingPortion…` ·
  `LimitedLiabilityCompanyLlcMembersEquityIncludingPortion…` / 지배 `PartnersCapital` · `MembersEquity` / 비지배 `PartnersCapitalAttributableToNoncontrollingInterest` · `MembersEquityAttributableToNoncontrollingInterest`.
- ★ 공시 단위 확인(전 기간): LP 총 태그 = 항등식 **445** 대 불일치 59(MPLX 55는 우리 메자닌 목록 밖 우선 단위 · HPP 2 · ARES 2).
  `PartnersCapital`(비지배 있음): 지배+비지배 = 총 **291** · 지배 = 총 **67**(CG 62 · MPLX 5 — 그 태그에 비지배를 실었다) · 둘 다 아님 74(MPLX 55 · BX 18).
- ★ **2020-09 뒤 SE 태그 없는 칸**: 총·지배가 함께 실린 칸은 지배 + 비지배 = 총이 **전부** 성립(EPD 46 · PAA 46 · IEP 46 · WES 44 · MPLX 46 · HESM 14 · TPG 4 · DTM · LGN · CNM · HGTY · ET 2).
  지배만 있는 칸(CQP 93 · GRAL · LB · SMR · KRMN · MDLN — 비지배 없음)은 항등식이 된 칸에서 지배 = 총.
- ★ 비지배가 표준 태그 밖으로 갈린다: BX 2017 `PartnersCapital` 6,636M + `PartnersCapitalAttributableToNoncontrollingInterest` 3,253M ≠ 총 13,519M(Blackstone Holdings 비지배는 회사 고유 태그). 그 자리에서 「총 − 비지배」는 10,265M을 지어낸다(시안 6).

### 1-3. 판 혼합 — 이어받은 맵에서는 「같은 공시 안의 모순」을 못 가른다

- ★ PANW 2018 Q1 판(2019-09-09): 2017-11 10-Q의 지배자본 665.5M과 2019 ASC 606 재작성 총자본 834.5M이 한 행에 섞였다.
- ★ DXCM 2020 FY 판(2022~2023): 재작성 총자본 1,826.5M(그 공시 자산 − 부채와 일치) + 옛 지배자본 1,551.9M.
- 그래서 「모순 + 증거 없음 → 값 없음」(시안 2)은 PANW·NKE·NFG 등 56칸을 지웠고, 「재무상태표가 비지배를 보여 준다」(시안 5)는 DXCM 7칸을 오발화했다. **둘 다 버렸다.** 판의 출처 추적은 [S](`S_ORIGIN`)의 일이다.

## 2. 계획 — 관문 켬에서만

### A. 지배자본의 비지배 판정 (`SecEdgarFacts`)
1. 관문 켬의 「비지배 없음」 = (티커가 `MinorityInterest`를 안 냄) ∧ (이 기간 시작 이후 **0이 아닌** 손익 비지배가 없음 — 세 컬럼 + 새 관문 재료 `COMPREHENSIVE_INCOME_NONCONTROLLING`) ∧ (이 판에 LP 비지배 없음).
2. 지배자본 판정(`controllingEquity`)만 이 술어를 쓴다. **부채 유도(`totalLiabilities`)는 기존 `MinorityInterest` 술어를 그대로 쓴다** — ★ 새 술어를 쓰면 ARMK(상환가능 비지배의 손익 = 이미 메자닌) 등 8종목 215칸의 부채가 지워졌다(시안 3 · 그중 창 안 ARMK 6 · FUN 6).
3. 「비지배 없음」인데 두 자본이 갈리면: ADR-0158 증거(자산 − 부채 − 메자닌)가 **지배자본을 고르면** 총자본 재료도 그 값으로 둔다(비지배 포함 태그가 쓰레기). 증거가 없거나 비지배 포함 쪽을 고르면 **지금 동작 그대로**(원시 총자본).

### B. LP·LLC 태그 (`SecEdgarColumn` 관문 재료 셋 + `gatedEquity`)
4. 새 관문 재료 컬럼(부재 사유·판 증거에 안 든다 — `gated()`):
   `PARTNERS_EQUITY_INCLUDING_NCI`(총 3태그) · `PARTNERS_EQUITY`(지배 2태그) · `PARTNERS_EQUITY_NONCONTROLLING`(비지배 2태그).
5. **SE 계열 총자본(`TOTAL_EQUITY`)이 없는 판에서만** 쓴다.
   - 총 태그 → `TOTAL_EQUITY_INCLUDING_NCI`·`TOTAL_EQUITY`.
   - LP 비지배 → `MINORITY_INTEREST`가 비었을 때만.
   - 지배 태그는 **확인될 때만** 지배자본: 비지배가 있으면 지배 + 비지배 = (총 태그, 없으면 항등식) · 비지배가 없고 「비지배 없음」이면 그대로 · 손익엔 비지배가 있는데 잔액이 없으면 총 태그와 같을 때만.
   - 확인이 안 되고 비지배가 있으면 **`MINORITY_INTEREST`를 지운다** — 「총 − 비지배」 유도를 막는다(1-2 BX).
   - 총 태그가 없을 때 지배 태그를 총자본으로: 항등식이 확인하거나, 비지배가 전혀 없을 때만(CG 2018 `PartnersCapital` 2,836.3M = 총).
6. 왜 기존 사다리에 후보로 안 넣나 — ★ 첫 시안(후보 추가)은 `reportEvidence` 컬럼이라 얕은 보고서가 판을 새로 내 행 집합이 **새 77 · 사라짐 47**로 흔들렸고, `MINORITY_INTEREST` 후보 추가가 티커 단위 `noNoncontrolling`을 뒤집어 ARES·BX의 값이 **우연히** 바뀌었다(기제가 아니라 부작용).

## 3. 드라이런 결과 (시안 7 · `proto7.patch` · 0콜 스윕)

기준선 대비(전 판):
- 총자본 채움 **2,251 · 41종목** · 지배자본 채움 **1,878 · 36** · 부채 채움(유도) **639 · 14** · **지우는 칸 0**.
- 지배자본 변경 **788 · 20**: 비지배 있음 12종목(BXP 105 · ARES 97 · AIV 95 · AMG 93 · BKH 81 · SLG 75 · BX 40 · UBER 37 · STEP 30 · TKO 23 · AXS · NPO) — 옛 값 = 총자본 → 새 값 = 원장 지배자본.
  비지배 없음 8종목(UNM 82 · PTCT · R · KLIC · UHAL · JSPR 3 · TEAM · TVTX) — 새 값 = 항등식이 확인한 총자본. JSPR 1칸은 옛 = 총.
- 다른 컬럼 변화 5칸: NRP 2016 Q4(2017-05-10 판) 순이익·영업비용·이자 · UNM 2020 Q4(2024-02-20 판) 보험비용 — FY 판 하나가 사라져 Q4 유도가 짝을 잃은 자리(ADR-0151).
- 행 집합: 새 41 · 사라짐 9. 새 판 40개 중 38이 **앞 판과 자본 컬럼만 다르다**(뒤 공시 비교열의 자본 재게재) · 2는 그 FY 판을 따라간 Q4 유도(IEP 2020 반올림 1M · SUN 2024 원가 새로 유도).
- 채운 총자본의 항등식: 1% 안 933 · 불일치 624 · 항등식 불가 694. 불일치 중 457은 같은 날짜 메자닌·우선 태그 값으로 설명(GHI 94 — 그중 2는 주식수 태그와의 우연 일치 · NRP 55 · KRP 55 · MPLX 49 · USAC 49 · BX 43 · GEL 33 · BSM 30 · KNOP 20 · KKR 16) · 남는 167은 상수 잔차(MPLX ≈968M · BSM 300.5M · GEL ≈1,05B — 목록 밖 우선 단위로 보이나 **열어 보지 않았다**).
- **지표 창(공시 2025-09-26 뒤)**: 총자본 채움 178 · 지배 채움 168 · 지배 변경 64 · 부채 채움 58.
- **현 편입 최신 행이 바뀌는 14종목**: AMG · ARES · BKH · BX · TKO · UBER(지배 → 원장) · UNM(−1,870M → 10,810M) · CQP · EPD · ET · MPLX · PAA · PAGP · WES(빈 자본 → 채움, EPD·ET·PAA·PAGP는 부채도).
- 채운 부채의 메자닌 누락: 창 안 58칸 중 EPD(`RedeemableNoncontrollingInterestEquityPreferredCarryingAmount` 44~50M · 부채의 0.1%) · ET(`…OtherCarryingAmount` 256M · 0.26%)만 목록 밖 메자닌이 있다.

## 4. 소비자 (오류 형태 2)

컬럼의 뜻은 안 바뀐다 — 값만 바뀐다. 읽는 쪽: `FinancialMath.roeBasis`(ROE · ROE_MIN_3Y) · `equityAttributableToShareholders`(PBR) ·
`controllingEquityThroughout`(PBR 5년 중앙값 — 창 안 한 행이라도 지배자본이 비면 연결로 통일) · `DebtRatioCalculator` · `AltmanZDoublePrimeCalculator` ·
`totalLiabilitiesOf`(NCAV 등) · `StockFinancialsAssembler`(화면) · `FinancialWriter`. 꺼진 갈래는 코드 경로가 그대로라 출력이 바이트까지 같아야 한다(구현 검증에서 관문 꺼짐 스윕 md5로 잰다).

## 5. 이 계획이 안 닫는 것 (사유와 수)

- **판 혼합이 만든 두 자본의 모순**(PANW · DXCM · NKE · NFG 등) — 출처 없이는 같은 공시의 모순과 못 가른다. [S] `S_ORIGIN` 뒤로. 지금 동작 유지.
- **SE 계열의 「총 − `MinorityInterest`」 유도가 갈린 비지배를 일부만 빼는 자리** — LP 쪽(BX 2017)에서만 쟀다. SE 계열은 **재지 않았다.**
- **부채 유도의 표준 태그 밖 비지배**(SANM `MinorityInterestInJointVentures` 200M · 2015~2023 51칸 · WEC `OtherMinorityInterests`) — 기존 결함. 창 안에는 없다(시안 3에서 잼).
- **우리 메자닌 목록 밖 태그**(EPD · ET · MPLX · BSM · GEL) — 유도 부채가 그만큼 크다. 기존 법인 유도와 같은 꼴.
- JSPR(옛 SPAC) 2020 총자본 101.79M = 자산(쓰레기) — 이 계획의 경로 밖.

## 6. 시험(구현 때)

- `PartnersEquityTest`(가칭): EPD꼴(총·지배·비지배 닫힘) · CQP꼴(지배만 · 비지배 없음) · CG꼴(지배 태그 = 총) · BX 2017꼴(비지배 갈림 → 지배 값 없음) ·
  ARES꼴(`MinorityInterest` 없음 + 손익 비지배 → 지배 = `StockholdersEquity`) · TKO꼴(포괄손익 비지배만) · LPX꼴(0인 비지배 태그 → 비지배 없음) ·
  UNM꼴(비지배 없음 + 항등식이 지배를 확인 → 총자본도 그 값) · NFG꼴(증거 없음 → 지금 동작).
- 관문 꺼짐 바이트 동일: 같은 재료의 꺼짐 스윕 md5 = HEAD.
- 대조군: 규칙 하나씩 되돌려 붉게(0 값 술어 · 포괄손익 컬럼 · 확인 실패 시 비지배 지우기 · 부채 술어 분리 · 증거 갈래).
