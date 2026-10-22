# B — 관문 뒤 판마다 ADS 비율 (2026-10-22 · 계획 v1)

`ADS-BASIS-PLAN-1022.md` §3의 계획이다. 실측 재료는 그 문서 §1(1b · 1e · 1f)과 아래 §1.
★ = 내가 원장·코드·DB에서 직접 쟀다. ☆ = 탐색 에이전트가 코드에서 읽었고 핵심 줄은 내가 다시 읽었다.

## 0. 질문과 범위

- 시총 = 주식수 × 종가인데 미국 순위·지표는 **ADS 종가에 보통주 주식수**를 곱한다. 1 ADS = k주이면 k배 틀린다.
- 지금(관문 밖)은 이름 축(`DEPOSITARY`)과 A′(`screenDepositary` · 표지 비율 ≠ 1 · 못 읽음 → 뺌)가 **빼서** 막는다. 들어와야 할 ADS가 못 든다.
- B는 관문 뒤에서 **판마다 비율로 주식수를 ADS 단위로 바꿔** 순위·지표·기저 판정·배당 총액이 같은 단위를 쓰게 한다. 그리고 관문 켬에서 이름 축의 ADS 몫과 A′를 걷는다.
- **범위 밖(실측해 넘긴 것)**: IFRS만 내는 발행사 329 CIK(`explore/universe/ifrs/`)는 재무 0행이라 B가 비율을 줘도 주식수가 없다 — 다음 항목. 비USD 보고사(NTES · BABA · ZTO …)는 B 뒤에도 통화 게이트(ADR-0094)에서 빠진다.
- **B가 순위에 들일 몫**(1d 재현 · 비율 반영 · USD 보고): ARM 235.2B · SE 51.3B · ONC 43.7B · EDU 9.5B · SIMO 7.2B · NICE 6.9B · TAL 약 6.6B · BLTE 6.1B. 1,000위 시총 약 $5.0B(10-20 종가).

## 1. 실측

### 1a. 코드 경로 ☆
- 클래스 축 앵커 후보 = 2020-09-01 뒤 정기 공시(10-K · 10-Q · 20-F · 40-F) 중 companyfacts에 축 없는 표지 주식수가 없는 것(`IssuerFacts.anchorCandidates`). **ADS 발행사는 거의 다 축 없는 표지가 있어 `d`(지금 경로)이고 인스턴스를 안 받는다** — 표지 증권 이름이 저장되지 않는다.
- `ClassFactReader.COVER`는 `Security12bTitle`을 담고 `EntityListingDepositoryReceiptRatio`는 안 담는다(그 태그는 `LISTING_COVER` · A′ 전용). `READER_VERSION = 3` · `okFacts`는 지금 판만 읽는다.
- 판독 한 자리 `ShareClassLine.read`(★ 읽음): 숨김 · 지금 경로(행의 `shares_outstanding` 그대로) · 클래스 축(N_L × 분할). 판정 없는 정기 공시는 첫 앵커 뒤 · 2020-09 뒤면 미판정 숨김, 아니면 지금 경로.
- 소비자: 순위 `JdbcStoredShareCounts.classAxisCounts`(× `share_basis_multiple` × 분할) · 보류 `classHolds`(`lastShown.lineShares` × 분할 — 배수 안 곱함) · 지표 `IndicatorDataReader` → `FinancialMath.sharesOf`(시총 · PBR · 회전율 · PER/PSR/PBR 중앙값) · `endpointShares`(★ 읽음 — 관문 켬 미국 행은 두 끝 다 그 공시의 분모 n을 `shiftToBar`로 봉에 옮긴다) · 기저 판정 `ShareBasisDeriver.classAxisFilingsOf` · 배당 총액 `GovernanceFactDeriver`. 뷰 `us_share_class_effective`는 펜스·시험만 쓴다.
- 유도 순서: `IndicatorReconciler.deriveShareBases` — 클래스 축 → 기저 판정(ADR-0179 §규칙).
- 순위 `rank()`: 통화·SIC → (관문) 보류 → 이름 축 → 풀 → 시총 정렬 → CIK 중복 → `screenDepositary` → 크기. 이름 축은 관문과 무관하게 돈다. `UsPastRanking`은 이름 축은 타고 A′는 안 탄다.

### 1b. 왜 분모 n까지 ADS 단위여야 하나 ★
- `endpointShares`는 n을 `shiftToBar`로 봉 시점에 옮기고 그 배수는 **ADS 원장 사건**(Alpaca 봉의 분할·병합)이다. 비율 변경(ADS 병합)은 원장에 ADS 사건으로 오고 보통주 주식수는 그대로다 — n이 보통주 단위면 UXIN 3→30에서 한쪽 끝만 10배 옮겨져 `SHARES_GROWTH_3Y` · EPS 성장 둘이 가짜 계단을 낸다. ADS 단위면 사건과 계열이 같은 단위라 상쇄된다.
- 거꾸로 BABA 2019 보통주 8:1 분할(ADS 사건 없음 · 비율 1→8)은 ADS 단위로 연속이다.

### 1c. 판마다 비율의 연속성 ★ (`dep/hist.json` · 77 CIK · 2020-09 뒤 정기 공시 · 0콜)
- 읽힘 506 · 표지 글은 있는데 비율 못 읽음 90 · 표지 없음 20 · 받기 오류 7 · 글≠태그 1.
- ADS 단위 계열이 앞 판과 1.5배 넘게 갈린 자리 48(원장 사건으로 설명 못 함 — 77 CIK의 원장 사건은 12뿐이다. 원장은 보존 창 편입만 모은다):
  - 비율이 바뀐 자리 21 — 보통주는 그대로이고 ADS 단위가 k_prev/k로 간다(UXIN · YI · TKLF · PDC · AMRN …). **ADS 병합이라 시세도 같은 배수로 간다 — 고칠 것이 아니다.**
  - 비율 그대로 · 정수배 10 — 무상증자(HDB ×2 · IH ×2) · 한 판짜리 오류(CMMB ×20 → ÷20 · ONC ×2 → ÷2) · 태그 오류(BABA 2026 ÷10).
  - 비율 그대로 · 비정수 19 — 실제 희석(AHG · AKTX · AUTL · ZLAB …)과 EDU 2021(보통주 10:1 분할 뒤 낡은 표지 비율 1).
- **계단 숨김은 실제 희석까지 숨긴다** → 안 쓴다. **왕복 규칙**(한 판이 양옆과 반대 방향으로 1.5배 넘게 갈리고 순 변화 10% 안)이 잡는 것: EDU 2021 · ONC 2023-02 · CMMB 2022-03(맞음) · CMMB 2022-05(틀림 — 마이크로캡의 연속 계단). 허용 25%도 같은 4. SLN(두 판짜리 비율 뒤집힘)은 못 잡는다.

### 1d. 표지 이전 판 ★
- 2019-01 ~ 2020-08 정기 공시: 20-F 70 중 표지 비율 20 · 10-K 12 중 3 · 10-Q 31 중 11. 2015~2018은 0(1f).
- 소비자: 5년 끝점(`EPS_CAGR_5Y`)이 지표 창 첫날(2025-09-26)에 2020-09-26 봉의 판을 읽는다 — 연간 20-F 발행사는 2020년 봄 20-F(대개 표지 없음)다. 지금 날짜의 5년 전(2021-10)은 표지가 있다. **창 앞부분(약 2026-04까지)의 5년 지표에만 닿는다.**

### 1e. 탐지 범위 ★
- A′ 범위 = 해외 양식(20-F · 40-F · 6-K) **또는** 이름이 예탁증권. 77 CIK 중 10-K/10-Q만 내는 ADS 8 · 섞인 것 11(AMRN 20 · ONC 13 · DBVT 5 …). AMRN의 이름은 `Amarin Corporation plc`라 이름 범위 밖이다.
- 순위 후보 상위 1,300(10-20 종가 × 저장 주식수) 중 A′ 범위 밖 1,181 CIK의 최신 10-K/10-Q 인스턴스 표지(3,542콜 · `ads/k10_cover.py`): 예탁증권 줄 **1(AMRN · 20주)** · 예탁증권 없음 1,176 · 표지 없음 2 · 받기 오류 2. 대조군: 같은 판독이 77 CIK에서 506판 비율을 읽었다(1c).

## 2. 설계

**B1 재료.** `ClassFactReader.COVER`에 `EntityListingDepositoryReceiptRatio`를 더하고 `READER_VERSION` 4. 저장 표는 관문 마이그레이션(시험 전용)이라 운영 재받기 비용이 없다.

**B2 받기 범위.** 줄이 예탁증권인 발행사는 2019-01-01 뒤 정기 공시 **전부**를 앵커 후보에 넣는다(축 없는 표지가 있어도). 예탁증권인지는:
- (a) companyfacts 공시 목록에 해외 양식이 있거나 (b) 저장 이름이 `DepositaryRatio.isDepositary`이거나 (c) **최신 정기 공시 인스턴스의 표지가 그 기호를 예탁증권 줄로 싣는다.** (c)를 위해 발행사마다 최신 정기 공시 하나는 늘 앵커 후보다(평생 한 번 받기 — 새 공시마다 하나).
- (c)가 없으면 AMRN 꼴(10-K · 평범한 이름)이 빠진다. 지금 그 꼴은 ADR-0162의 `SHIFTED`가 사건 자리만 고친다 — 사건 없는 상수 비율 k ≠ 1은 아무것도 못 잡는다.

**B3 판정(판마다).** 그 줄의 기호를 `TradingSymbol`로 찾아 표지 글 비율(1순위 · `DepositaryRatio.sharesPerDepositary`)과 태그(보조).
- 글과 태그가 2% 넘게 다르면 숨김(`LEDGER`). 기호의 줄이 예탁증권인데 비율을 못 읽으면 숨김(`ABSENT`). 기호의 줄이 보통주면 비율 없음(지금과 같다).
- 표지가 없는 판(2019 이전 · 표지 없는 2019~2020): **뒤 판의 첫 비율을 앞으로 잇는다 — 보통주 주식수가 이웃 판과 1.5배 안이고 그 사이 원장 사건이 없을 때만.** 끊기면 그 앞은 숨김(`ABSENT`). BABA 2019(보통주 ×8)는 끊긴다.
- 왕복 규칙(1c)은 예탁증권 줄에만 건다 — 한 판이 양옆과 반대로 1.5배 넘게 갈리고 순 변화 10% 안이면 그 판을 숨김(`LEDGER`). 오탐 1(CMMB 2022-05)을 함께 적는다.

**B4 저장.** `financial_share_class_filing`에 `depositary_ratio numeric`(보통주 / ADS 1 · null = 예탁증권 아님 · 양수). **`line_shares`와 `denominator`는 ADS 단위로 저장한다** — 보류(`classHolds`)와 기저 판정이 단위를 따로 알 필요가 없다. 지금 경로 행은 판독이 행의 주식수를 그 판 비율로 나눈다. V70을 고친다(시험 전용 · 적용된 시험 DB는 새로 만든다 — 파일 머리의 규칙).

**B5 판독.** `ShareClassLine.apply`: 지금 경로면 `currentShares / ratio`. 판정 없는 정기 공시가 예탁증권 줄의 첫 판정 앞이면 숨김(「예탁 비율 모름」). 정기 공시 아닌 행은 서 있던 판정의 비율. 뷰 `us_share_class_effective`의 `ELSE f.shares_outstanding`도 같은 규칙.

**B6 순위.** 관문 켬에서 이름 축의 `DEPOSITARY`를 우선주 예탁증권(`1/1,000th interest` 꼴 — A′가 이미 「ADS 아님」으로 가른다)만 자르게 바꾸고 `screenDepositary`를 건너뛴다. `UsPastRanking`도 같은 이름 축. 관문 끔은 바이트까지 그대로.

**B7 기저 판정.** 고치지 않는다 — 계열이 ADS 단위로 들어오므로 AMRN의 20배 `SHIFTED`는 사건과 계열이 함께 가 사라진다(이중 곱 없음). 드라이런에서 AMRN을 확인한다.

**B8 기록.** ADR-0162 §배경 「ADS 비율 자체는 소스가 안 준다」를 1b · 1f의 수로 고친다. 새 ADR(판 비율 · 저장 단위 · 버린 대안: 종목 상수 비율 · 계단 숨김 · 순위 시점에만 표지 읽기).

## 3. 드라이런

1. **0콜 스윕**: 77 CIK의 companyfacts(77콜)와 `hist.json`의 표지를 파일 저장소(`ShareClassFileStore`)에 넣고 `SecShareClassSweepTest`로 판정 표를 찍는다. 기대: 비율 506판 · 숨김의 갈래별 수 · EDU 2021 · ONC 2023-02 숨김 · AMRN 계열 연속 · BABA 2019 앞 숨김.
2. **기저 판정**: 같은 재료로 `ShareBasisDeriver`를 돌려 AMRN이 `SHIFTED`에서 빠지는지.
3. **순위 영향**: 운영 SELECT + 10-21 종가로 B 판독의 시총을 지어 상위 1,000의 들고 남을 센다(쓰기 0).
4. **관문 끔 바이트 동일**: `RankShareCountSnapshotTest`를 관문 끔으로 고치기 전·후.

## 4. 순서

드라이런 1 → 구현(설정 뒤 · `main`) → 시험 → 드라이런 2·3·4 → 커밋. 운영 배포는 관문 끔 바이트 동일이 서면 정기 배포에 실린다(켤 때까지 값 변화 0).

## 5. 반증 v1 (두 렌즈) — 계획 안 「다시 짠다」 · 계획 밖 「고쳐서 간다」

뼈대가 뒤집혔다 — **v2를 새로 쓴다.** 지적과 내가 다시 볼 자리:

| 지적 | 근거(반증자 실측) | v2에서 |
|---|---|---|
| 1a 「ADS는 거의 `d`」가 거짓 — 주 경로는 `T`이고 20-F 클래스 앵커는 전부 숨김(`IssuerTimeline:172-176 · 228 · 257 · 266-273` · 사유는 V5 §1c 「ADS 비율을 원장이 안 준다」) | `hist.json` 2019~ 778판: 축 없는 표지만 298 · 구성원 표지만 186 · 못 가림 274 · SE · TAL 최신 둘이 ClassA/B 구성원만 | fpi 배제를 비율 있는 판에서 무엇으로 대신하나 |
| B2가 지금 보이는 `d`를 `T`로 바꿔 숨김으로 보낸다 · (c)가 전 발행사의 `firstAnchor`와 `noinst` 숨김을 바꾼다 | CHA · TUYA · AMBR(축 없는 표지 + 구성원 표지) | 비율 재료를 판정 종류를 안 바꾸고 받는 길 |
| EPS 함의(보통주)와 맞대는 소비자 셋: 배당 총액 20% 게이트(`GovernanceFactDeriver:303-316` → 배당성향까지 숨음) · 순위 가드 훅(`guard-rebalance-on-dirty-rank.sh:95-109` → TDTH 254배 · STKH 1,654배로 관문 뒤 리밸런싱 거부) · `DenominatorJudge`의 n 검산 | 운영 `순이익/eps` ÷ 저장 주식수 = HDB 1.004 · SIMO 0.996 · ONC 1.005 · TAL 1.014(eps는 보통주당) | 비율로 나누는 자리마다 EPS 함의도 같은 비율로 · grep 전수 |
| 왕복 규칙의 시점성 — 최신 판(BABA 2026 ÷10)을 못 잡고 다음 판이 오면 과거 판정을 소급해 바꾼다(그날까지 공시된 판만 본다는 V5 §1e 위반) | AMBR ×4.97 · AHG ×3.79 등 최신 판 계단 | 시점 판정으로 다시 |
| 소급 규칙의 「원장 사건 없음」은 후보에서 원장이 설계상 비어 있어 뜻이 다르다(편입만 모음 · APPLIED 최소 발효일 2020-01-27) | UXIN · YI · TKLF · PDC · DBVT … 원장 0행 | 편입·후보를 갈라 다시 묻는다 |
| AKTX의 사용자 결정 미표시(ADR-0162 · 09-23)의 전제가 B에서 거짓이 된다 — ADS 단위면 원장 ×0.025와 맞아 「따라감」 | 원장 REVERSE 0.05 · 0.025 ↔ 표지 100 → 2000 → 80000 | **사용자에게 다시 묻는다**(B 구현 전) |
| V70을 고치면 공유 시험 DB 체크섬(203162657)이 어긋나 무관한 배포 빌드까지 선다 | `flyway_schema_history` · `application-test.yaml` | 시험 전용 후속 마이그레이션(V70_1)으로 |
| §1b 「비율 변경은 원장에 ADS 사건으로 온다」 — 확인은 AMRN · AKTX 둘 · HDB 넷이 일치, 계획이 이름 댄 여섯은 원장 0행 | `corporate_action` | 편입 예정 ADS의 비율 변경 자리를 Alpaca 사건과 대조 |
| **순서** — 비율 1 ADS(ARM 235.2B · SE · NICE · BLTE)는 판 비율이 필요 없다 · 77 CIK 중 「최신 1 · 과거 ≠ 1」 0 | `hist.json` | **B0로 가른다: 관문 밖 이름 축을 ADS에서 풀고 A′가 비율 ≠ 1을 뺀다** |
| IFRS 순서 근거 「IFRS ADS가 B 비율을 쓴다」는 ADS에만 선다(비ADS 28엔 안 선다) — 묶어서 판정 | | 순서 문장을 고친다 |
