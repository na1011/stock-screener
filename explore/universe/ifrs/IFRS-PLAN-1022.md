# IFRS 발행사 재무 적재 — 관문 뒤 (2026-10-22 · 계획 v1)

★ = 내가 원장·코드·DB에서 직접 쟀다. 재료는 scratchpad `ifrs/cf/`(companyfacts 182 · 10-22 받음)와 `.factcache`(편입 1,218).

## 0. 질문과 판정

- 미국 재무 어댑터가 `us-gaap`·`dei`만 읽는다(`SecEdgarFacts:305 · 393 · 418`). IFRS로만 보고하는 발행사는 재무 0행 → 주식수 0 → 순위 후보에서 `excluded.noShares`(`UsMarketCapUniverseAdapter:441-444`).
- **가짜 한계다.** ① 물은 곳: `companyfacts`(182 CIK · 10-22) · `companyconcept`(`ifrs-full/ProfitLoss` 등 · 386 CIK · 10-22 앞 판). ② 대조군: 같은 질의가 `us-gaap` 발행사 15곳에 값을 받았다. ③ 모집단: 재무 0행 · 공시 장부 행 있는 미국 활성 종목 → CIK 386 → `ifrs-full`만 329. ④ 뿌리 한 줄: `facts.path(dei ? "dei" : "us-gaap")` · 0콜 재현: `ifrs/cf/*.json`의 `facts` 최상위 키.
- 기록 `ADR-0125:374-375`의 *"TSM은 재무 0행이 정상이며 어떤 태그를 더해도 안 열린다"*는 `us-gaap` 태그에만 서는 문장이다 — 같은 응답에 `ifrs-full`이 있다. 고친다.
- **새 외부 연동이 아니다.** 같은 `companyfacts` 응답의 다른 키다(`CompanyFactsAccessions:9`가 이미 택소노미를 안 가린다). ADR-0099가 미룬 `companyconcept` 연동과 무관하다.

## 1. 실측 ★

### 1a. 모집단
- 182 = `ifrs.tsv`에서 손익 단위에 USD가 있는 CIK(USD만 130 + 다통화 51 + 1). 비USD만 보고하는 148은 받아도 통화 게이트(`MARKET_CURRENCY` SEF:102 · 판 보류)가 뺀다 — **지금 결정(ADR-0094)의 몫이고 이 계획은 그 결정을 안 바꾼다.**
- 2024 뒤 20-F·40-F의 컬럼별 태그 보유(182 중): 매출 161(`Revenue` 133 · `RevenueFromContractsWithCustomers` 27) · 원가 119 · 총이익 99 · 영업이익(`ProfitLossFromOperatingActivities`) 138 · 순이익 총(`ProfitLoss`) 179 · 지배(`ProfitLossAttributableToOwnersOfParent`) 113 · 세전 167 · 법인세 163 · 자산 175 · 부채 163 · 자본(`Equity`) 180 · 지배자본 102 · 유동자산 166 · 유동부채 167 · 이익잉여금 151 · 현금 178 · 재고 131 · 영업현금흐름 175 · 설비투자 147 · 기본 EPS 168 · 표지 주식수(2025 뒤 `dei`) 149.
- 중간 공시 주기(2023 뒤 손익 기간 길이): **분기 48 · 반기만 34 · 연간만 100.** 6-K는 이미 `PERIODIC_FORMS`·`INTERIM_FORMS`에 든다(SEF:71-84) — 분기 6-K는 지금 경로가 라벨을 만든다. **반기(6개월)는 버리고 센다**(SEF:717-723).
- 한 공시 안 다통화: **125/182 발행사**에 금액 단위가 둘 이상인 공시가 있다 — 편의환산(TSM TWD 361 · USD 110 · ASX TWD 695 · USD 263 · KB KRW 327 · USD 117)과 주석의 외화 몇 개(AGI USD 264 · CAD 3 · TTE USD 615 · EUR 7).
- 나이브 시총(표지 주식수 × 10-21 Alpaca 종가 · ADS 비율 미반영): USD · 이름에 예탁 낱말 없음 · ≥ $4B가 39(AEM 93.7B · WPM 60.0B · TRI 56.0B · MT 48.3B · FNV 46.9B · AU 46.2B · NTR · KGC · GFS · AS …) — 그중 HSBC · TTE · RIO · SNN · VALE · BVN · IHG는 이름이 평범한 ADS다.

### 1b. 이미 서 있는 것
- 예탁증권 표지 판정 A′의 범위가 `FOREIGN_FORMS`(20-F · 40-F · 6-K)를 포함한다(`UsMarketCapUniverseAdapter:571 · 607`) — 이름이 평범한 ADS도 표지로 가린다. 비율 ≠ 1은 B 전까지 뺀다.
- 관문 10단계 후보 재적재가 「후보 전원 ∩ (SEC 매핑 ∪ 재무 행 있음)」이라 IFRS 발행사(CIK 있음)가 든다(`reload-gate.md:129`).
- 통화: 비USD 판은 저장하지 않고 보류로 적는다(SEF:864-871). 지표 `CURRENCY_GATE`·순위 `CURRENCY_MISMATCHED`는 저장 행 중 하나라도 비USD면 그 종목을 가린다.

### 1c. 이미 편입된 종목에 닿는 몫 — 관문 뒤에 두는 근거
- `.factcache` 1,218 중 `ifrs-full` 키가 있는 것 8: BNT · CLS · CRH · FLUT · IREN · SMMT · SSRM · TEAM. 20-F·40-F 손익이 있는 연도: CRH 2017~2019 EUR · 2020~2022 USD · SSRM 2017~2020 USD · IREN 2022~2024 USD · BNT 2021~2022 USD · FLUT 2017~2019 USD · SMMT 2018~2019 GBP · CLS · TEAM 0. 지금 저장 행은 CRH 2023~ · SSRM 2021~ · IREN 2025~ 등 — **읽으면 이 종목들의 과거 FY가 새로 생기고 3년·5년 지표가 바뀐다.**
- FLUT의 2017~2019 40-F는 The Stars Group 시절 공시로 보인다(같은 CIK를 이어받음 — **재지 않음**). 등록인 시대(ADR-0180)가 그 판을 가르는지 드라이런에서 본다.
- 그래서 **이 축은 `stockscreener.gate.us` 뒤다**(CLAUDE.md 「값을 바꾸는 어댑터 코드는 꺼진 설정 뒤」 · 「제품 상태는 전부 끔 · 전부 켬 둘뿐」 — IFRS 전용 발행사만 관문 밖에서 켜면 셋째 상태가 생긴다).

### 1d. 소비자가 IFRS 개념을 어떻게 읽는가
- `interest_expense`의 소비자는 `FinancialMath.operatingIncomeOf`의 **은행형 영업이익**(:1062-1064) 하나다. IFRS `FinanceCosts`는 리스 이자 · 충당부채 할인 상각까지 담는 비은행 개념이라 그 자리에 넣으면 은행형 갈래가 일반 기업에 발화할 수 있다 → **`ifrs-full:InterestExpense`만 받는다**(오류 형태 4).
- `controllingIncome`(SEF:1477-1545)은 `NetIncomeLoss` = 지배 · `ProfitLoss` = 총을 전제한다. IFRS도 `ProfitLoss` = 총 · `ProfitLossAttributableToOwnersOfParent` = 지배라 컬럼 뜻이 같다. **다만 「지배 태그 부재」의 뜻이 다르다** — IFRS 182 중 69가 지배 태그 없이 총만 낸다. 비지배 행이 없으면 지배 = 총이라는 뜻일 수 있다 → 행마다 `ProfitLossAttributableToNoncontrollingInterests`·`NoncontrollingInterests`의 부재를 함께 보고 판정한다(드라이런 D1에서 잰다).
- 자본: `Equity` = 비지배 포함 총 · `EquityAttributableToOwnersOfParent` = 지배. IFRS에 임시 자본(mezzanine)이 없다 — `equityByBalanceEvidence`(SEF:1692-1741)가 그 컬럼이 빈 것을 「IFRS라 없음」으로 읽는지 D1에서 본다.

## 2. 고침 (전부 `stockscreener.gate.us` 뒤 · 꺼진 출력은 `main`과 바이트까지 같다)

1. **택소노미를 후보 이름에 싣는다.** `dei:` 접두와 같은 방식으로 `ifrs-full:` 후보를 컬럼마다 둔다. 이름 해석 한 곳(`namespaceOf`)으로 모아 `collect` · `absencesOf` · `hasAny`가 함께 쓴다. 관문 꺼짐이면 `ifrs-full:` 후보를 안 본다.
2. **컬럼별 IFRS 후보**(뜻이 같은 것만 — 표 §5):
   매출 `Revenue` · `RevenueFromContractsWithCustomers`(최댓값 경쟁) · 원가 `CostOfSales` · 총이익 `GrossProfit` · 영업이익 `ProfitLossFromOperatingActivities` · 총순이익 `ProfitLoss` · 지배 `ProfitLossAttributableToOwnersOfParent` · 비지배 `ProfitLossAttributableToNoncontrollingInterests` · 세전 `ProfitLossBeforeTax` · 법인세 `IncomeTaxExpenseContinuingOperations` · 자산 `Assets` · 부채 `Liabilities` · 총자본 `Equity` · 지배자본 `EquityAttributableToOwnersOfParent` · 비지배자본 `NoncontrollingInterests` · 유동 `CurrentAssets` · `CurrentLiabilities` · 이익잉여금 `RetainedEarnings` · 현금 `CashAndCashEquivalents` · 재고 `Inventories` · 영업현금흐름 `CashFlowsFromUsedInOperatingActivities` · 설비투자 `PurchaseOfPropertyPlantAndEquipmentClassifiedAsInvestingActivities` · EPS `BasicEarningsLossPerShare` · `DilutedEarningsLossPerShare` · 주식수 표지 `dei` 다음 `NumberOfSharesOutstanding` · `WeightedAverageShares` · 은행 이자수익 `RevenueFromInterest` · 이자비용 `InterestExpense`.
3. **공시마다 표시 통화 하나**(넘긴 P3 `readUnits` 결함을 이 축에서 닫는다 — IFRS가 그 결함을 125/182 발행사로 넓힌다): 공시(accn)마다 3자 금액 단위의 팩트 수가 가장 많은 단위를 표시 통화로 정하고, 그 공시의 다른 금액 단위 팩트를 안 받는다. EPS는 `<표시 통화>/shares`만. 지금 `Slot(accn, start, end)`이 단위를 안 가려 마지막 단위가 덮는다(SEF:229 · 434-471).
4. **공시 보충(ADR-0173)**: 「companyfacts가 덮은 공시」 판정(`SecFilingSupplement.usGaapFilings:407-409`)과 인스턴스 해석(`XbrlInstanceReader:73 · 134` · `SecFilingSupplementStore:98`)이 `ifrs-full`을 같이 센다. 안 고치면 IFRS 공시가 전부 「덮이지 않음」으로 보충을 다시 받는다.
5. 기록: `ADR-0125:374-375`의 TSM 문장 · `sec-edgar-financial.md` 「이 소스가 답하지 못하는 것」의 IFRS 항목을 고친다. `reload-gate.md` 미국 「목록에 없던 것」에 행을 더한다. 새 ADR(택소노미 · 표시 통화 규칙)을 쓴다.

## 3. 이 계획이 안 고치는 것과 그 순서

- **반기 라벨**(6개월 팩트): 스키마가 FY · Q1~Q4뿐이다(`ck_fs_period`). 반기만 내는 발행사는 FY만 남아 지표가 연간 기저로 돈다(`FinancialMath.seriesOf` :268-279 — 틀린 값이 아니라 낡은 값). 이미 편입 us-gaap 5종목(CAMT · CLBT · GLNG · MNDY · NVMI — `reload-gate.md` 「6-K 드리프트」 행의 넷)에도 같은 결손이 있다. **이 축의 2부로 바로 다음에 loop를 돈다** — 1부가 만든 행이 2부의 입력이고, 1부가 「종목이 통째로 없음」이라 더 크게 틀어졌다.
- 비USD 보고 발행사(TD · TSM · NVO …)의 순위 제외: 통화 게이트의 결정(ADR-0094)이다. 이 계획 밖.
- ADS 비율 ≠ 1(HSBC 1:5 · SHEL 1:2 …): B v2.

## 4. 드라이런 (가장 싼 재료부터)

- **D1 · 0콜 스윕**(`SecEdgarFactsSweepTest` · gradle 한 판씩 · 앱 배치와 겹치지 않게):
  - `.factcache` 1,218 · `US_GATE=false`: 고친 코드 = `main` TSV · EXTRA 바이트 같음.
  - `.factcache` · `US_GATE=true`: `main` 켬과의 차이가 1c의 8종목 + 표시 통화 규칙이 바꾸는 칸뿐인가. 통화 규칙 몫을 종목·칸으로 센다.
  - `ifrs/cf` 182 · `US_GATE=true`: 행 수 · 저장 통화 · 보류 판 · 컬럼별 채움 · 항등식(자산 = 부채 + 자본 · 총 = 지배 + 비지배 · 총이익 = 매출 − 원가) 불성립 · 주식수 출처. 아는 꼴 손 대조: AEM(40-F 연간) · FNV(6-K 분기) · HSBC(은행 · 반기) · TSM(TWD 표시 → 보류).
- **D2 · 순위 기대**: D1 주식수 × 10-21 종가로 IFRS 후보의 시총을 내고 운영 10-21 문턱과 맞대 관문 수용 기대(들 종목 · A′가 뺄 ADS)를 적는다. 표지 판정은 운영 읽기 전용 실제 경로로 표본만.
- 재지 않음(드라이런 밖): 클래스 축(`shareclass/IssuerFacts`는 us-gaap만 읽는다 · :46 · 80)이 IFRS 발행사에 어떤 `share_basis_status`를 내는지 — 관문 사본 리허설(9단계)에서 잰다. 지금 저장 미국 행 `UNRESOLVED`는 15행뿐이다.

## 5. 뜻 대조표 (오류 형태 4 — 부재의 뜻을 소스마다)

| 컬럼 | us-gaap | ifrs-full | 같은가 | 다른 점 |
|---|---|---|---|---|
| 순이익 총 | `ProfitLoss` | `ProfitLoss` | 같음 | — |
| 순이익 지배 | `NetIncomeLoss` | `ProfitLossAttributableToOwnersOfParent` | 같음 | IFRS는 비지배 없으면 지배 태그를 안 내는 발행사가 많다(69/182) |
| 영업이익 | `OperatingIncomeLoss` | `ProfitLossFromOperatingActivities` | 「표시된 대로」인 점이 같음 | IFRS는 정의가 없다 — us-gaap도 표시 관행이라 같은 층(SEC:324) |
| 총자본 | `…IncludingPortionAttributableToNoncontrollingInterest` | `Equity` | 같음 | IFRS에 임시 자본 없음 |
| 이자비용 | `InterestExpense`(은행) | `InterestExpense` | 같음 | `FinanceCosts`는 받지 않는다 |
| 설비투자 | `PaymentsToAcquirePropertyPlantAndEquipment` | `PurchaseOfPropertyPlantAndEquipmentClassifiedAsInvestingActivities` | 같음 | 182 중 35가 확장 태그 — 빈 칸 |
| 주식수 | `dei` 표지 → `CommonStockSharesOutstanding` → 가중평균 | `dei` 표지 → `NumberOfSharesOutstanding` → `WeightedAverageShares` | 같은 순서 | 표지가 낡은 발행사(TRI 2022 · NTR 2021 · VALE 2013) — 표지 판의 주식수만 그 판에 단다(SEF:1154-1160) |

## 6. 순서

반증(두 렌즈 · 이 판 하나) → D1 · D2 → 구현(시험: 꼴마다 · 대조군은 고치기 전 코드에서 IFRS 꼴이 붉다) → D1 다시 → 커밋 · 배포(꺼짐이라 값 무변화) → 2부(반기) loop.
