---
id: ADR-0188
status: 채택됨
date: 2026-10-24
applies_to:
  - "src/main/java/com/stockscreener/pipeline/datasource/us/SecEdgarFacts.java"
  - "src/main/java/com/stockscreener/pipeline/datasource/us/SecEdgarColumn.java"
---

# ADR-0188. 미국 LP · LLC 자본을 읽고, 지배자본의 비지배 판정을 손익 · 원장으로 한다(관문 뒤)

## 결정

관문 켬(`stockscreener.gate.us`)에서 `SecEdgarFacts`의 자본 세 컬럼(`total_equity` · `total_equity_controlling` · `total_liabilities` 유도)을 두 갈래로 고친다.

1. **LP · LLC 태그를 재료로 읽는다.** 새 관문 재료 컬럼:
   - `PARTNERS_EQUITY_INCLUDING_NCI`(총 3태그)
   - `PARTNERS_EQUITY`(`PartnersCapital` · `MembersEquity`)
   - `PARTNERS_EQUITY_NONCONTROLLING`(비지배 2태그)

   비어 있는 자리만 채운다. LP 지배 태그는 **같은 공시의 합(지배 + 비지배 = 총)이 확인될 때만** 지배자본이 된다.
2. **지배자본의 「비지배 없음」 판정**은 다음 셋이 모두 참일 때다.
   - `MinorityInterest`를 안 낸다.
   - 그 기간 뒤 **0이 아닌** 손익 · 포괄손익 비지배가 없다.
   - LP 비지배가 없다.

   이 판정은 **원장이 그 날짜에 두 자본 태그를 갈라 실었을 때만** 쓴다(판이 아니라 날짜 단위). 그 판정이 「비지배 없음」인데 두 자본이 갈리면, 자산 − 부채 − 메자닌이 지배 쪽을 고를 때만 비지배 포함 태그를 버린다.

관문 꺼짐은 코드 경로가 그대로다. 새 컬럼은 수집되지 않고 래퍼는 옛 값 · 옛 플래그를 넘긴다.
설계 · 실측 · 반증의 정본은 탐색 브랜치의 `explore/financial-sync/LP-EQUITY-PLAN-1024.md`(v1 · v1.1)다.

## 왜

- **가짜 한계 — LP 자본.**
  - 원장은 `PartnersCapital…`로 자본을 정직하게 준다. 2020-09 뒤 총 · 지배가 함께 실린 칸에서 지배 + 비지배 = 총이 **전부** 닫혔다(EPD · PAA · IEP · WES · MPLX · ET · HESM · TPG).
  - 그런데 사다리가 그 태그를 안 읽어, 현 편입 LP 7종목(CQP · EPD · ET · MPLX · PAA · PAGP · WES)의 PBR · ROE가 비었다.
- **관문 꺼짐 운영 결함 — ADR-0159 §규칙.**
  - 그 규칙은 「`noNoncontrolling`인데 두 자본이 갈리면 직접 태그를 버린다」다. `noNoncontrolling` = `MinorityInterest` 태그를 한 번도 안 냄이다.
  - 전수(팩트 캐시 1,218)에서 그 조건의 같은 공시 충돌은 **659칸 · 20티커**다. 그중 손익 비지배가 있는 8티커(ARES · BX · TKO · AMG · UBER · BKH · STEP · AXS)는 두 태그가 다 맞고, 지배자본이 `StockholdersEquity`다. 비지배를 다른 태그로 낼 뿐이다.
  - 10-23 운영 지표:
    - ARES PBR 4.50 — 원장 지배자본(3,968M)으로는 약 9.8
    - BX 4.23 — 9,015M으로 약 9.9
    - TKO 4.12 — 3,396M으로 약 10.1
  - UNM은 비지배 포함 태그 −1,870M(쓰레기)이 지배자본이 되어 ROE · PBR이 없다. 같은 공시의 자산 − 부채가 지배 10,810M을 확인한다.
  - 그 ADR이 근거로 든 NKE 2017 Q1도 거꾸로다. 같은 공시가 지배 12,165M과 비지배 포함 85M을 싣는데, 규칙이 85M을 고른다.
- 손익 축 술어(`lastNoncontrollingIncome`)는 이미 있었다(2026-09-21 실측). 그대로 옮기면 두 자리가 틀린다.
  - 0인 태그를 존재로 센다(LPX · ICLR).
  - 포괄손익으로만 비지배를 내는 회사를 못 본다(TKO 2026 — 비지배 49억 달러).
  - 그래서 0이 아닌 값과 포괄손익 비지배를 쓴다.

## 규칙

- MUST NOT: LP 태그를 `TOTAL_EQUITY` 등 사다리 후보로 넣지 않는다. 후보는 판 증거(`reportEvidence`)라, 자본변동표만 실은 얕은 보고서가 판을 새로 낸다(첫 시안: 새 판 77 · 사라짐 47).
- MUST NOT: `PartnersCapital`을 확인 없이 지배자본으로 쓰지 않는다. CG 2016~2019는 그 태그에 비지배를 실었다(= 자산 − 부채).
- MUST: LP 지배 + 비지배가 총과 안 닫히고 비지배가 있으면 `MINORITY_INTEREST`를 지워 「총 − 비지배」 유도를 막는다. 비지배가 표준 태그 밖으로 갈린 자리다 — BX 2017은 지배 6,636M인데 유도하면 10,265M이 된다.
- MUST: LP 팩트는 같은 공시가 그 날짜의 자산도 실은 것만 수집한다(`keepPartnersEquityOnBalanceSheets`). 자본변동표만 실은 뒤 공시의 재작성 자본이 앞 판의 자산 · 부채와 섞여, 「부채 + 자본 > 자산」 대차대조표가 10개 새로 났다(MPLX · WES — ADR-0158 펜스).
- MUST: LP 잔액 재료는 F2b의 「순이익 없는 공시 → 관문 재료 null」에서 뺀다(`SecEdgarColumn.partnersEquity()`). 뒤 해 10-Q의 비교 재무상태표가 순이익 없이 LP 자본을 싣는다(WES 2018 FY 판 혼합).
- MUST NOT: 비지배 판정을 판(앵커) 단위로 하지 않는다. 판마다 판정이 갈려 같은 라벨의 판끼리 지배자본이 오가고, 그 차이가 판을 새로 낸다(시안: 새 판 1,648). 날짜 단위 원장 사실(`equityPairsDiffer`)로 한다.
- MUST NOT: 이어받은 맵의 두 자본 차이만으로 판정하지 않는다. 판 혼합이다.
  - PANW 2018 Q1: 옛 지배 665.5M + ASC 606 재작성 총 834.5M
  - DXCM 2020 FY: 재작성 총 1,826.5M + 옛 지배 1,551.9M
  - UHAL: 같은 공시의 두 자본이 늘 같다
- MUST NOT: 새 술어를 모든 판에 걸지 않는다 — 원장이 그 날짜에 두 자본을 갈라 실었거나 LP 지배 태그를 실은 판에서만 쓰고, 그 밖은 옛 `MinorityInterest` 술어 그대로다(지배자본 · 부채 유도 둘 다). 모든 판에 걸면 부채 유도까지 바뀌어, 손익 비지배가 상환가능분(이미 메자닌으로 빠진 몫)뿐인 ARMK 등 8종목 215칸의 부채가 지워졌다. 이 제한 아래서 부채 유도만 따로 옛 술어로 묶는 갈래는 전수 0칸이라 두지 않았다.
- 0이 아닌 값 술어는 지금 원장에서 지배자본 판정을 가르는 칸이 **0**이다(날짜 단위 판정 뒤). 그래도 둔다. 0인 비지배 태그를 내는 회사가 실제로 있고(LPX · ICLR), 그것을 「비지배 있음」으로 읽는 것은 틀린 뜻이기 때문이다. 대조군은 없다.

## 버린 대안

| 대안 | 기각 근거 |
|---|---|
| LP 태그를 사다리 후보로(첫 시안) | 행 집합이 새 77 · 사라짐 47로 흔들렸다. `MINORITY_INTEREST` 후보 추가가 티커 단위 `noNoncontrolling`을 뒤집어 ARES · BX 값이 **부작용으로** 바뀌었다 |
| 비지배 없음 + 두 자본 모순 + 증거 없음 → 값 없음 | 그 「모순」 다수가 판 혼합이었다 — PANW · NKE · NFG 등 56칸을 지웠다 |
| 재무상태표가 비지배를 보여 준다(증거가 비지배 포함을 확인) → 비지배 있음 | 이어받은 맵에서 판 혼합에 속는다(DXCM 7칸 오발화) |
| 앵커 공시 자신의 사실로 판정 | 판마다 판정이 갈려 새 판 1,648 |
| 부채 유도도 새 술어로 | ARMK · FUN 등 8종목 215칸 지움(그중 창 안 12) — 상환가능 비지배는 이미 메자닌이다 |
| LP 지배자본을 `LimitedPartnersCapitalAccount`(상장 단위 몫)로(반증 밖 1) | 이 컬럼의 뜻은 모회사 귀속 자본이다 — 법인 `StockholdersEquity`가 우선주를 포함하는 것과 같다. 「주식 보유자 몫 자본」은 PBR · BPS 분모의 축이고 법인 우선주와 뿌리가 같아 `docs/reload-gate.md` 「주당 지표」 행으로 갔다 |

## 결과

**드라이런**(0콜 스윕 · 2026-10-24 · `SecEdgarFactsSweepTest` 관문 켬 · 팩트 캐시 + LP 공시자 1,271 · 기준선 main `078164f5`)

수량(전 판):
- 채움
  - 총자본 2,333 · 41종목
  - 지배자본 2,122 · 38
  - 부채 673 · 14
- 지배자본 변경 779 · 19
  - 비지배 있음 → 원장 지배자본: BXP 105 · ARES 97 · AMG 93 · AIV 92 · BKH 81 · SLG 74 · BX 40 · UBER 37 · STEP 30 · TKO 23 · AXS · XIFR · ET
  - 비지배 없음 + 항등식: UNM 82 · R · PTCT · KLIC · JSPR · TVTX
- 총자본 변경 23(IESC 18 · MPLX 5) — 전부 그 행의 자산 − 부채 쪽으로 갔다.
- 지배자본 비움 6(ET 2015 · CG 2019 — LP 합이 안 닫혀 유도를 막음).
- 다른 컬럼 1: UNM 2020 Q4 보험비용 → 빈칸. 지배자본이 흔들려 생긴 원천 판이 옛 값을 잇던 자리이고, LDTI 재작성 FY와 옛 분기의 잔차는 원리적으로 안 선다.
- 행 집합: 새 15 · 사라짐 34. XIFR 20이 사라졌다 — 원 10-K가 LP 지배를 이미 실었다.

ADR-0158 펜스 재현(현 편입 기간별 최신 판): **337 · 141 → 337 · 141 · 새 0.**

창 안 편입 이력 1,214 + SUN의 최신 행 변화 18종목:
- 현 편입 14: AMG · ARES · BKH · BX · TKO · UBER · UNM · CQP · EPD · ET · MPLX · PAA · PAGP · WES
- 그 밖: HESM · IEP · STEP · SUN

ET 최근 20분기 지배자본: 5 → 20.

**시험**: `SecEdgarPartnersEquityTest` 9건. 규칙을 하나씩 되돌린 대조군 여덟이 모두 붉다 — 포괄손익 비지배(TKO) · 확인 실패 시 비지배 지우기(BX 2017) · 비지배 없음의 항등식 갈래(UNM) · 날짜 단위 판정(UHAL · ARMK 함께) · F2b 무효화 제외(WES) · 재무상태표 공시만 수집(MPLX) · SE 총자본이 있어도 LP 지배(ET 2019 Q1) · 손익 술어 자체(BX 2026 · TKO). 관문 꺼짐 0콜 스윕은 HEAD와 md5가 같다(`40f2b377…`).

**넘긴 것**
- 「주식 보유자 몫 자본」: LP `PartnersCapital`에 GP · 우선 · Class D가 든다.
  - CQP 754M 대 LP 3,692M(4.90배)
  - IEP 1.80배
  - ET 우선 9.5%
  - SUN
  - 법인 우선주(재지 않음)
  - → `docs/reload-gate.md` 「주당 지표」 행(분자 · 분모)
- 같은 공시 안의 두 자본 모순 중 증거가 없는 자리: NKE 2013~2016 · CAKE 2023 Q3 · MIDD 2021-01. 부채 성분 합이 중재할 수 있고, 지금 동작이 유지된다. → [S]/[Z] 대차대조표 항등식 축(펜스 337이 그 축)
- 우리 메자닌 목록 밖 태그만큼 유도 부채가 크다. EPD 약 50M(0.1%) · ET 256M(0.26%) · MPLX · BSM · GEL 우선 단위. → 같은 축
- SE 계열의 「총 − `MinorityInterest`」 유도가 갈린 비지배를 일부만 빼는 자리 — **재지 않았다.**

## 검증

- `./gradlew test --tests '*SecEdgarPartnersEquityTest'`
- 저장 재료 스윕(관문 켬 · 꺼짐): `REPLAY_SWEEP_DIR=<재료> REPLAY_SUPPLEMENT_DIR=off US_GATE=true ./gradlew test --tests '*SecEdgarFactsSweepTest' --rerun-tasks`. 위 §결과의 수와 맞대 본다. 꺼짐 출력은 HEAD와 바이트까지 같아야 한다.
- 이 결정은 운영 자동 검증 대상이 아니다. 관문 뒤 경로다.
