# B v2 — 관문 뒤 판마다 ADS 비율 (2026-10-22 · 계획 v2)

v1(`ADS-B-PLAN-1022.md`)은 반증 「다시 짠다」였다(그 문서 §5). ★ = 내가 원장·코드·DB에서 직접 쟀다. ☆ = 탐색 에이전트가 코드에서 읽었다(소비자 전수).

## 0. 질문과 뼈대

- 미국 시총·지표는 **ADS 종가(·거래량·Alpaca 배당·ADS 원장 사건)에 보통주 주식수**를 맞댄다. 1 ADS = k주이면 k배 틀린다. 관문 밖에서는 A′(표지 비율 ≠ 1 · 못 읽음 → 뺌)와 과거 평면의 이름 축이 **빼서** 막는다 — 들어와야 할 ADS(ONC · EDU · SIMO · HDB · TAL · IFRS의 HSBC · SHEL · BHP · DEO · BP …)가 두 평면 모두 빠진 결손이다.
- **v1이 무너진 자리**: 저장 단위를 ADS로 바꿔 EPS 함의(보통주)와 맞대는 소비자 셋이 깨지고, 비율 재료를 받으려고 클래스 축 판정 종류를 바꿨다.
- **v2 뼈대: 저장 단위는 보통주 그대로. 그 행의 `share_basis_multiple` = 1/k(그 판 표지 비율)를 짓는다.** ☆ `share_basis_multiple`은 가격과 만나는 세 자리에만 곱해지고(`FinancialMath:694-698` 지표 주식수 · `JdbcStoredShareCounts:168`·`:275-276` 순위 두 경로) EPS 함의와 맞대는 자리(배당 20% 게이트 `GovernanceFactDeriver:225-237`·`:312-315` · 가드 훅 `guard-rebalance-on-dirty-rank.sh:94-112` · `DenominatorJudge:346` · ADR-0125·0113 premise)에는 안 곱해진다 — **v2가 원하는 경계가 이미 코드에 있다.** AMRN의 SHIFTED ×0.05(ADR-0162)가 이미 이 모양이다(1:20 이후 행이 사실상 ADS 단위).
- 비율 재료는 클래스 축 판정과 **별개의 표**에 받는다 — 판정 종류(T/d · O/X)를 안 바꾼다.

## 1. 실측

### 1a. 비율 재료 ★
- 표지 증권 이름이 판마다 비율을 준다: `hist.json`(77 CIK · 2019 뒤 정기 공시) 읽힘 506 · 글은 있는데 비율 못 읽음 90 · 표지 없음 20 · 받기 오류 7 · 글≠태그 1(v1 §1c). IFRS 인스턴스(10-22): VALE 「each representing one common share」 · AZN은 2026년부터 ADS가 아니라 보통주 직접 상장(표지 「Ordinary Shares」) — **판마다 읽어야 하는 이유.**
- 2015~2018 표지 비율 0 · 2019-01~2020-08: 20-F 70 중 20 · 10-K 12 중 3 · 10-Q 31 중 11(v1 §1d).

### 1b. 원장 사건 ★
- Alpaca `v1/corporate-actions`가 비편입 ADS의 비율 변경을 분할 사건으로 준다(10-22 · 13기호 · 2019~2026): UXIN 2022-10 · 2024-01 · YI 2025-01 · TKLF 2024-11 · DBVT 2024-06 · 2024-11 · AMRN 2025-04 · AKTX 2023-08 · 2026-03 · EDU 2021-03(1→10) · 2022-04 · HDB 2019-09 · 2025-09. PDC 0(대조군 아님 — 기호 확인 안 함). **우리 `corporate_action`에 후보 몫이 0행인 것은 소스가 안 줘서가 아니다** — `CandidateSplitJob`이 공시 뒤 분할만 모은다(가짜 한계).
- 이력 시작: 2015~2018은 대조군 NFLX(2015-07 7:1)까지 0건 — 소스 이력은 2019 무렵부터다. 지표 창이 닿는 가장 이른 끝점(5년 · 2020-09)보다 앞이다.

### 1c. 소비자 전수 ☆ (탐색 에이전트 · 코드 읽기)
- **배수를 거치는 ADS 단위 소비자(고칠 것 없음)**: 시총 `MarketCapCalculator:102-106`(→ AltmanZ · FcfYield · NcavToMarket) · 회전율 둘 · PER · PBR · PSR · 중앙값 5년 · `RetainedToMarket4y` · `EPS_TTM` · 성장 셋(`SharesGrowth3y` · `EpsGrowthTtmYoy` · `EpsCagr5y` — 과거 끝 × 원장 배수, 행 배수가 있으면 상쇄) · 순위 두 경로.
- **배수를 안 거치는 ADS 단위 소비자(고친다)**: 배당 총액 `AlpacaGovernanceFactMapper:149-154` = Alpaca ADS당 배당 × 보통주 주식수(배수 · 시점 이동 없음) → ×k · `DividendPayoutCalculator:72-81`가 물려받는다.
- **보통주 n을 ADS 원장 배수와 맞대는 자리(고친다)**: `IssuerTimeline:274`(앞 판 n × ADS 분할 계수와 비교 — 비율 변경이 가짜 `jump` 숨김) · `:315` · `:325`(Q4 n) · `ShareClassLine.apply:103 · 119-131`(줄 주식수 × ADS 계수 — 순위 클래스 경로가 뒤에서 배수를 곱하므로 배수가 1/k_공시면 상쇄).
- **경보(값 무관)**: `PriceIntegrityAlerter.offsetJumps:206-232` · `PriceRefutation.shareLens:187-250` — 비율 변경이 가짜 경보를 낼 수 있다(재지 않음 · 드라이런에서 센다).
- **상태 비대칭**: 지표는 원장 APPROVED+APPLIED, 순위 · `ShareClassLine` · `ShareBasisDeriver`는 APPLIED만 — 후보 사건을 APPLIED로 넣어야 순위가 본다.

### 1d. 20-F · 40-F 클래스 앵커 ★
- `IssuerTimeline:170-176`: 20-F · 40-F 클래스 앵커는 B 갈래 n을 버리고(`20F-class`) 시점 합(`P-ab` · `P-c`)도 `!fpi`로 막아 사실상 전부 X(숨김)다. 근거는 V5 §1c 한 줄 「ADS 비율을 원장이 안 준다」 — 1a로 무너졌고, **ADS가 아닌 20-F · 40-F 보통주(TRI · NU · BIP)에는 처음부터 안 서던 규칙**이다.
- 그 숨김에 걸린 IFRS 발행사(ADR-0182 · 2년 창 안 표지 주식수 없음): TRI(`Common Shares`) · VALE(ADS 1:1) · BIP(LP units) · NU(Class A) · AZN — 인스턴스(10-22 · 6콜) 표지가 클래스 구성원(`ifrs-full:ClassesOfShareCapitalAxis` 포함)에만 주식수를 싣는다. ★ 클래스 판독기는 축 `StatementClassOfStockAxis` 하나 · 네임스페이스 us-gaap · dei만 읽는다(`ClassFactReader:78 · 86 · 145`) — IFRS 앵커는 클래스 축도 EPS · 가중평균 태그(`DenominatorJudge`의 n 재료)도 못 읽는다. `share_basis_status`에 CHECK 제약은 없다(마이그레이션 grep 0).

### 1e. 사용자 결정과 맞닿는 것
- AKTX 미표시(2026-09-23 사용자 결정 · ADR-0162 · 지금 UNRESOLVED)의 전제 「ADS 비율 근거가 원장에 없다」가 1a로 거짓이 된다 — 표지 100 → 2,000 → 80,000이 원장 REVERSE 0.05 · 0.025와 맞는다. **B 구현 전에 사용자에게 다시 묻는다.**

## 2. 설계 (전부 관문 뒤 · 꺼진 출력은 `main`과 바이트까지 같다)

1. **재료 표** `sec_depositary_cover`(관문 마이그레이션 · 시험 전용 `V70_1` — V70을 고치면 공유 시험 DB 체크섬이 어긋난다): (접수번호 · CIK · 공시일 · 폼 · 그 줄 기호 · 증권 이름 글 · 글 비율 · 태그 비율 · 판정 · 판독 판). 받는 범위 = 예탁증권 줄이 있는 발행사(해외 양식 ∪ 이름 ∪ 최신 표지의 예탁증권 줄 — AMRN)의 2019 뒤 정기 공시 전부. 받기는 클래스 축 인스턴스와 같은 자리(`ShareClassCollector`)에서 하되 **앵커 후보 판정과 무관하게** — 판정 종류가 안 바뀐다.
2. **행 비율 k_row**: 그 행의 공시 표지 비율. 표지가 없는 행(6-K · 2019~2020 표지 없음)은 이웃 표지 공시의 비율을 잇는다 — **그 사이 ADS 원장 사건이 없고 보통주 주식수가 1.5배 안일 때만.** 끊기면 미정(UNRESOLVED → 숨김). 글과 태그가 2% 넘게 갈리면 미정. 줄이 보통주면 k = 1(지금과 같다).
   - 시점: 뒤 공시의 표지로 앞 행을 잇는 것은 정정이 아니라 **증권의 고정 속성**(예탁 계약 · F-6)을 그때 XBRL로 안 실었을 뿐이라 받는다 — 근거를 ADR에 적는다. 한 번 이어진 값은 뒤 공시가 와도 안 바뀐다(이은 공시가 고정이면 값이 고정).
3. **배수**: `ShareBasisDeriver`가 예탁증권 줄 행에 `share_basis_multiple` = 1/k_row · 상태 `DEPOSITARY`를 쓴다. 같은 행의 SHIFTED 추론을 대신한다(AMRN: SHIFTED ×0.05 = 1/20 — 이중 곱 없음). 사건 · 보통주 계열 비교(SHIFTED)는 예탁증권 줄이 아닌 행에만 남긴다.
4. **배수를 안 거치던 ADS 단위 소비자**: 배당 총액에 그 행의 배수를 곱한다(`AlpacaGovernanceFactMapper` · `GovernanceFactDeriver`의 주식수). 20% 게이트는 주식수 대 EPS 함의(둘 다 보통주)라 그대로.
5. **클래스 축**: `IssuerTimeline`의 연속성 · Q4 비교에서 ADS 분할 계수를 보통주 계수로 바꾼다(계수 × k_뒤/k_앞). 20-F · 40-F 앵커의 `20F-class`와 `!fpi`를 걷고 10-K 갈래(Q4 유도 제외)와 같게 판정한다. 클래스 판독기가 `ifrs-full:ClassesOfShareCapitalAxis`를 클래스 축으로 읽고, IFRS EPS · 가중평균(`BasicEarningsLossPerShare` · `WeightedAverageShares` …)을 ADR-0182 별칭과 같은 뜻의 us-gaap 이름으로 싣는다(판독 판 올림).
6. **후보 원장**: 예탁증권 줄 후보의 ADS 사건을 2019 뒤 전부 APPLIED로 받는다(`CandidateSplitJob` 범위를 공시 뒤 → 2019 뒤로 · 관문 10단계 전).
7. **순위**: 관문 켬에서 이름 축의 ADS 몫과 `screenDepositary`를 걷는다 — 두 평면이 같은 배수를 읽는다(`UsPastRanking`도 `JdbcStoredShareCounts`). k 미정은 주식수 없음처럼 뺀다(사유 「예탁 비율 미정」).
8. **기록**: 새 ADR(판 비율 · 저장 단위 보통주 · 배수 경계 · 시점 근거 · 버린 대안: 저장 단위 ADS(v1) · 종목 상수 비율 · 계단 숨김 · 순위 시점에만 표지). ADR-0162 「ADS 비율 자체는 소스가 안 준다」 고침. `reload-gate.md` B 행.

## 3. 드라이런 (가장 싼 재료부터)

1. **0콜 재료 판정**: `hist.json`(77 CIK) + IFRS ADS 표본의 표지를 재료 표 판정기에 먹여 행 k를 짓는다 — 행마다 k · 미정 갈래별 수. 기대: UXIN 3 → 30이 원장 사건과 같은 날 · EDU 2021(낡은 표지 비율 1 · 보통주 10:1)이 미정 또는 사건으로 설명 · BABA 2019 보통주 ×8 앞 끊김.
2. **배수 · 소비자**: 운영 SELECT(읽기 전용) + 1의 k로 B 판독의 시총을 지어 10-21 상위 1,000의 들고 남을 센다 · 성장 셋의 두 끝 단위가 같은지(UXIN · AMRN · HDB) · 배당 총액이 ×k에서 풀리는지(HDB · TAL).
3. **클래스 축**: TRI · VALE · NU · BIP · AZN · SE · TAL 인스턴스로 `IssuerTimeline` 판정 표(0콜 · 받은 인스턴스).
4. **관문 끔 바이트 동일**: 스윕 · `RankShareCountSnapshotTest`를 끔으로 고치기 전·후.

## 4. 순서

반증(두 렌즈 · 이 판) → AKTX 사용자 질문 → 드라이런 1~3 → 구현(설정 뒤 · `main` · 시험 전용 V70_1) → 드라이런 4 → 커밋 · 배포(꺼짐이라 값 무변화).
