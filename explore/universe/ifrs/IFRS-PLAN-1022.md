# IFRS 발행사 재무 적재 — 관문 뒤 (2026-10-22 · 계획 v2)

★ = 내가 원장·코드·DB에서 직접 쟀다. 재료: scratchpad `ifrs/cf/`(companyfacts 182 · 10-22) · `ifrs/inst/`(인스턴스 4) · `.factcache`(편입 1,218).
v1 → v2: 반증 두 렌즈 모두 「고쳐서 간다」(§7). 지적은 내가 재서 닫았다 — 뼈대는 그대로라 반증을 다시 받지 않는다.

## 0. 질문과 판정

- 미국 재무 어댑터가 `us-gaap`·`dei`만 읽는다(`SecEdgarFacts:305 · 393 · 418`). IFRS로만 보고하는 발행사는 재무 0행 → 주식수 0 → 순위 후보에서 `excluded.noShares`(`UsMarketCapUniverseAdapter:441-444`).
- **가짜 한계다.** ① 물은 곳: `companyfacts`(182 CIK · 10-22) · `companyconcept`(`ifrs-full/ProfitLoss`·`Revenue` · 386 CIK). ② 대조군: 같은 질의가 `us-gaap` 발행사 15곳에 값을 받았다. ③ 모집단: 재무 0행 · 공시 장부 행 있는 미국 활성 종목 → CIK 386 → `ifrs-full`만 329(**하한** — 재무 0행 활성 3,480 중 장부 행 있는 것은 443뿐이고 NVS · UBS · SAP · SPOT · CNQ · MFC는 장부 밖이다 — 반증 밖 렌즈 6). ④ 뿌리 한 줄: `facts.path(dei ? "dei" : "us-gaap")` · 0콜 재현: `ifrs/cf/*.json`의 `facts` 최상위 키.
- 기록 `ADR-0125:374-375`의 *"TSM은 재무 0행이 정상이며 어떤 태그를 더해도 안 열린다"*는 `us-gaap` 태그에만 서는 문장이다 — 같은 응답에 `ifrs-full`이 있다. 고친다.
- **새 외부 연동이 아니다.** 같은 `companyfacts` 응답의 다른 키다(`CompanyFactsAccessions:9`가 이미 택소노미를 안 가린다).

## 1. 실측 ★

### 1a. 모집단 · 커버리지
- 182 = 손익 단위에 USD가 있는 CIK(USD만 130 · 다통화 51 · TD). 비USD만 보고하는 148은 통화 게이트(ADR-0094)의 몫이고 이 계획은 그 결정을 안 바꾼다.
- 2024 뒤 연차 공시의 태그 보유(182 중 · 「그 태그 팩트가 기말 2024 뒤 20-F·40-F에 하나라도」): 매출 161 · 원가 119 · 총이익 99 · 영업이익 138 · `ProfitLoss` 179 · `…OwnersOfParent` 113 · 세전 167 · 법인세 163 · 자산 175 · 부채 163 · `Equity` 180 · 지배자본 102 · 유동 166·167 · 이익잉여금 151 · 현금 178 · 재고 131 · 영업현금흐름 175 · 설비투자 147 · EPS 168 · 표지 주식수(2025 뒤 `dei`) 149. (반증 안 렌즈는 기준 「filed 2024 뒤」로 181 · 114 — 기준 차이다.)
- 중간 공시 주기(2023 뒤 손익 기간): 분기 48 · 반기만 34 · 연간만 100. 반기(6개월)는 지금 경로가 버리고 센다(SEF:717-723).
- 한 공시 안 다통화: 125/182 발행사. 다수 단위로 고르면 동률 0 · 핵심 태그 기간 수로 고른 통화와 어긋남 0(반증 안 렌즈 8 · 가장 가까운 VCIG MYR 262 대 USD 211). 편입 1,218에서 그 규칙이 바꾸는 슬롯 3(PLUG 법인세 EUR 2 · SOFI `USN` 1).

### 1b. 공시 하나 = 택소노미 하나가 아니다
- 182 중 6곳이 2015 뒤 IFRS 공시 안에 `us-gaap` 후보 태그를 싣고 값이 IFRS 값과 다르다(LTM `Assets` 2018 2.70B 대 20.08B · KMDA · CAAP · KEN · ELVA · WF — 반증 밖 렌즈 3).
- VALE · WFG는 2020-09 뒤 IFRS 공시에 섞인 `us-gaap` 팩트 때문에 클래스 축 앵커를 받고, 그 뒤 IFRS 공시가 판정 목록에 없어 `ShareClassLine.read:85-87`의 「미판정 = 숨김」에 걸린다(반증 밖 렌즈 1).

### 1c. 주식수
- 6-K 유량 공시(2024 뒤) 209건 중 `dei` 표지 주식수가 있는 것 0(반증 안 렌즈 3). 순위는 주식수가 양수인 행 중 기말이 가장 늦은 것을 쓴다(`JdbcStoredShareCounts:153-164` · `shares_outstanding IS NOT NULL AND > 0`) — 6-K 행에 주식수가 없으면 연차 표지 행이 쓰인다.
- 같은 연차 공시 안 `WeightedAverageShares` 대 `dei` 표지 636쌍: 0.9~1.1배 482 · 0.5~2배 108 · 기타 24 · **10배 넘게 22쌍 · 13발행사**(천 주 단위 0.001배 — EMBJ · KEN · HUBC …). `NumberOfSharesOutstanding`은 2배 넘게 38/279쌍 · 20발행사. → **IFRS의 주식수 폴백은 표지와 같은 뜻의 재료가 아니다(오류 형태 3).**
- 표지가 낡아 보였던 발행사는 **표지를 클래스 축(차원)으로만 싣는다** — 인스턴스 4건(10-22 · 4콜): TRI 40-F 2026-03-05 `dei:EntityCommonStockSharesOutstanding` 444,962,490(맥락 3개) · VALE 20-F 2026-03-27 `OrdinarySharesMember` 4,268,780,141 · BIP 20-F 2026-03-17 460,488,788 외 5. NTR 40-F 2026-02-27은 인스턴스에도 `dei` 주식수가 없다. companyfacts는 차원 팩트를 빼므로 클래스 축(ADR-0179)이 읽을 몫인데, **`shareclass/IssuerFacts:46 · 80`가 공시 목록을 `us-gaap`에서만 지어 IFRS 공시가 앵커가 못 된다.**

### 1d. 비지배 부재의 뜻 (IAS 1 · 공시 단위)
- 2021 뒤 IFRS 공시(손익 또는 자본 있음)를 비지배 팩트(`NoncontrollingInterests` · `ProfitLossAttributableToNoncontrollingInterests` · 포괄손익 비지배) 유무로 가르면: 비지배 없음 616 · 있음 613.
  비지배 없음 616 중 지배 손익 태그도 지배 자본 태그도 없는 것 279(AEM · AG · AGI …) — IAS 1이 비지배를 본문 한 줄로 요구하므로 **그 공시의 `ProfitLoss`·`Equity`가 곧 지배 귀속**이다.
  비지배 있음 613 중 지배 손익 태그 없는 것 26(BIP · RADX · AZ · VEON …) — 이들은 위 추론이 안 선다.
- 보통주 귀속 `ProfitLossAttributableToOrdinaryEquityHoldersOfParentEntity`를 내는 공시 151(HSBC · FNV · TD · PAAS …) — us-gaap `…AvailableToCommonStockholdersBasic`과 같은 뜻.

### 1e. 이미 편입된 종목에 닿는 몫 — 관문 뒤 근거
- `.factcache` 중 `ifrs-full` 키 8: BNT · CLS · CRH · FLUT · IREN · SMMT · SSRM · TEAM. IFRS 연차 기말: CLS 2015~2023 · TEAM 2015~2022 · CRH 2015~2022 · SSRM 2016~2020 · IREN 2020~2024 · BNT 2019~2022 · SMMT 2016~2019 · FLUT 2015~2019. 저장 FY는 CLS 2024~ · TEAM 2023~ · CRH 2023~ 등 — **읽으면 과거 FY가 새로 생기고 3년·5년 지표가 바뀐다.** (v1의 「CLS · TEAM 0」은 `ProfitLoss` 하나로 센 오계수였다.)
- 전환기 비교값: CLS FY2022 지배순이익 IFRS 145.5M 대 10-K 재작성 180.1M · TEAM FY2021 −696M 대 −579M · FY2022 −614M 대 −520M. 지금 `chooseTags`는 라벨 앵커 공시의 태그에 고정해(SEF:900-930) 뒤 10-K의 us-gaap 재작성 판을 못 읽는다(반증 안 렌즈 2).
- **FLUT의 2015~2019 40-F는 다른 실체다**: submissions `formerNames` = Stars Group Inc.(2017-08 ~ 2020-05-06) · Amaya Inc.(2015-05 ~ 2017-07). 2020~2023 연차 공시가 없다. `stock.era_from` NULL이고 `CompanyFactsAccessions`가 택소노미를 안 가려 그 접수번호도 「이 등록인 것」으로 센다.
- 그래서 **이 축은 `stockscreener.gate.us` 뒤다**(CLAUDE.md 「값을 바꾸는 어댑터 코드는 꺼진 설정 뒤」 · 「제품 상태는 전부 끔 · 전부 켬 둘뿐」).

### 1f. 이미 서 있는 것
- A′ 범위가 `FOREIGN_FORMS`(20-F · 40-F · 6-K)를 포함한다(`UsMarketCapUniverseAdapter:571 · 607`) — 관측 평면은 이름이 평범한 ADS도 표지로 가린다.
- 관문 10단계 후보 재적재가 IFRS 발행사에 닿는다(`StockRegistrar.usRefillWindowTickers:700-711` — 활성 · 편입 없음).
- `ShareBasisDeriver`는 택소노미와 무관하다 · 비사업 SIC 거름은 6798 · 6770뿐이다(반증 밖 렌즈 「섰던 것」).

## 2. 고침 (전부 `stockscreener.gate.us` 뒤 · 꺼진 출력은 `main`과 바이트까지 같다)

1. **공시마다 택소노미 하나.** 접수번호마다 비`dei` 팩트 수가 많은 쪽(`us-gaap` 또는 `ifrs-full`)을 그 공시의 택소노미로 정하고 다른 쪽 팩트를 안 받는다(1b).
2. **IFRS 개념을 같은 뜻의 `us-gaap` 후보 열쇠로 싣는다(별칭).** 컬럼별 후보 목록 대신 **슬롯 단계에서** IFRS 팩트를 같은 뜻의 us-gaap 후보 이름 아래 넣는다 — 그래야 `chooseTags`가 앵커 공시의 태그를 고정해도 전환기 뒤 재작성 판이 같은 열쇠로 읽히고(1e), 유도 함수(`controllingIncome` · `profitBeforeTax` · `controllingEquity` · `operatingIncome` · Q4)가 그대로 돈다. 별칭은 **유도 함수가 읽는 컬럼마다** 정한다(§5 표 · 반증 안 렌즈 1이 센 유도 재료 17을 전부 표에 싣는다). 같은 슬롯에 두 택소노미가 오면 1이 이미 하나를 버렸다.
3. **비지배 부재의 조건부 별칭**(1d): 그 공시에 비지배 팩트가 하나도 없을 때만 IFRS `ProfitLoss` → `NetIncomeLoss` 열쇠 · `Equity` → `StockholdersEquity` 열쇠에도 싣는다. 비지배가 있는 공시는 안 한다(26은 지금 경로의 총 − 비지배 유도에 맡긴다). ADR-0153 펜스(「지배자본 NULL · 총자본 있음」)의 뜻이 그대로 선다(반증 밖 렌즈 9).
4. **매출**: IFRS `Revenue` → `Revenues` 열쇠. `RevenueFromContractsWithCustomers`는 **최댓값 경쟁에 넣지 않고 마지막 수단 목록**에 싣는다 — 광산사 잠정가격 조정이 음수라 계약 매출이 IAS 1 합계보다 큰 칸이 21 · 6발행사(BHP · RIO · BVN · HMY · EGO · CGAU — 반증 안 렌즈 5). us-gaap의 「계약 매출은 부분집합」 근거가 IFRS에 안 선다(오류 형태 4).
5. **이자비용은 매핑하지 않는다.** 은행형 영업이익은 재료 넷이 다 있어야 켜지고(`FinancialMath:1062-1064` · null 전파 :1111-1118) IFRS에 비이자 수익·비용 표준 개념이 없다 — 매핑해도 값이 안 바뀐다(반증 안 렌즈 6). IFRS 은행의 영업이익은 `ProfitLossFromOperatingActivities`를 낼 때만 선다 — 은행 몇이 그 태그를 안 내는지는 D1에서 센다.
6. **주식수는 IFRS 폴백을 두지 않는다**(1c): 표지(`dei` 축 없음) 또는 클래스 축(인스턴스의 차원 표지)만. **`shareclass/IssuerFacts`가 공시 목록을 `ifrs-full`에서도 짓는다**(1b · 1c — TRI · VALE · BIP · WFG가 클래스 축으로 주식수를 받는다). 표지가 어디에도 없는 발행사(NTR)는 순위에서 미표시 — ① 물은 곳 companyfacts `dei` + 인스턴스 `dei`(NTR 40-F 2026-02-27) ② 대조군 TRI · VALE · BIP 인스턴스가 값을 줬다 ③ 모집단은 D1에서 센다 ④ 갈림길: 원장이 주는 것은 가중평균·주석 주식수뿐이고 같은 공시 안 표지와 10배 넘게 갈리는 쌍이 22/636.
7. **표시 통화**(넘긴 P3 `readUnits` 결함을 이 축에서 닫는다): 공시마다 3자 금액 단위의 다수를 표시 통화로 정하고 다른 금액 단위 팩트를 안 받는다 · EPS는 `<표시 통화>/shares`만.
8. **통화 게이트를 종목 단위로**(반증 밖 렌즈 4 · ADR-0094 「종목 단위」): 발행사의 **가장 늦은 연차 공시**가 비USD면 그 발행사의 USD 판도 저장하지 않는다(BDRX GBP · DPRO CAD · HMY ZAR · IMRN AUD가 옛 USD 판으로 게이트를 통과하던 길). 편입에 닿는 수는 D1에서 센다.
9. **P1 회수 보호와의 맞물림**(반증 밖 렌즈 5): 새 규칙(7 · 8)이 보류로 돌리는 판 중 `main`이 이미 저장한 (라벨, 공시일)은 `FinancialWriter:263-267 · 444-450`가 회수에서 지킨다 → 관문 재적재가 새 규칙으로 수렴하지 못한다. D1에서 「main 저장 판 ∩ 고친 코드 보류」를 세고, 0이 아니면 보호 술어가 「그 판이 그 통화로 저장된 적 있음」이 아니라 「지금 규칙이 비USD로 판정」을 보도록 고친다.
10. **등록인 시대 — 택소노미 전환의 다리**(1e · FLUT): 새 기준의 가장 이른 자산 기말이 옛 기준의 마지막 자산 기말보다 뒤면(새 기준 첫 공시의 비교 재무상태표가 옛 기준 마지막 해에 안 닿는다) 옛 기준 공시를 버린다. companyfacts만으로 가른다. 처음 적은 술어(「연차 공백 ≥ 24개월 ∧ 그 안에 `formerNames` 경계」)는 submissions 18콜로 쟀다 — 공백만으로는 편입 9 · IFRS 9이 걸리고 대부분 FPI XBRL 의무화(2018) 앞 공백이었으며, 경계를 더하면 FLUT 하나였다. 다리 규칙도 전환기 8종목 중 FLUT 하나만 자른다.
11. **공시 보충(ADR-0173)**: 「companyfacts가 덮은 공시」(`SecFilingSupplement.usGaapFilings:407-409` · `SecEdgarFinancialAdapter:167` 소비 근거 · `settle :246-260`)와 인스턴스 해석(`XbrlInstanceReader:73 · 134` · `SecFilingSupplementStore:98`)이 `ifrs-full`을 같이 센다. **저장분**: v8 OK 행 중 us-gaap 0인 해외 폼이 3,597행 · 426 CIK(반증 안 렌즈 4) — `READER_VERSION`을 관문 켬일 때만 새 판으로 보게 해(꺼짐은 지금 판 그대로 재사용) 꺼진 동작이 `main`과 같게 둔다.
12. 기록: `ADR-0125:374-375`의 TSM 문장 · `sec-edgar-financial.md` IFRS 항목을 고친다. 새 ADR(택소노미 · 별칭 · 표시 통화 · 종목 단위 통화 게이트 · 주식수 정본). `reload-gate.md` 미국 「목록에 없던 것」에 행을 더하고 **의존 칸에 「B 없이 IFRS 켬 관문 금지」**를 적는다(§3).

## 3. 안 고치는 것과 순서

- **과거 평면의 비율 ≠ 1 ADS**: `UsPastRanking:126 · 179`는 표지 판정이 없어 이름 축만 쓴다. IFRS가 HSBC(1:5) · SNN · BP 같은 이름이 평범한 ADS에 주식수를 주면 관문 복원이 비율배로 부푼 시총으로 과거 평면에 들인다(반증 밖 렌즈 2). → **B v2가 관문 전 선행이다.** B v2 모집단과 수용에 IFRS 평이름 ADS를 넣는다. 순서는 IFRS(이 계획 · 구현은 관문 뒤라 무해) → B v2 → 반기.
- **반기 라벨**(6개월): 스키마가 FY · Q1~Q4뿐이다(`ck_fs_period`). 반기만 내는 발행사는 FY만 남아 연간 기저로 돈다(`FinancialMath.seriesOf` :268-279 — 틀린 값이 아니라 낡은 값). 편입 us-gaap 5종목(CAMT · CLBT · GLNG · MNDY · NVMI)도 같다. B v2가 두 평면의 명부를 틀리게 하는 쪽이라 더 크게 틀어졌다 — **B v2 다음.**
- 비USD 보고 발행사의 순위 제외: ADR-0094의 결정이다. 이 계획 밖.

## 4. 드라이런 (가장 싼 재료부터)

- **D1 · 0콜 스윕**(`SecEdgarFactsSweepTest` · gradle 한 판씩 · 크론과 겹치지 않게):
  - `.factcache` · `US_GATE=false`: 고친 코드 = `main` TSV · EXTRA 바이트 같음.
  - `.factcache` · `US_GATE=true`: `main` 켬과의 차이가 1e의 8종목 + 7 · 8 · 10이 바꾸는 칸뿐인가 — 갈래마다 종목·칸으로 센다. 9의 교집합을 센다.
  - `ifrs/cf` 182 · `US_GATE=true`: 행 수 · 저장 통화 · 보류 판 · 컬럼별 채움 · 항등식(자산 = 부채 + 자본 · 총 = 지배 + 비지배 · 총이익 = 매출 − 원가 · 세전 = 계속영업 + 법인세) 불성립 · 주식수 출처 · IFRS 은행 영업이익. 손 대조: AEM(40-F 연간 · 비지배 없음) · FNV(6-K 분기 · 보통주 귀속) · HSBC(은행 · 반기 · 비지배) · TSM(TWD → 보류) · HMY(통화 종목 단위) · BHP(매출 마지막 수단) · CLS · TEAM(전환기 재작성) · FLUT(등록인 시대).
- **D1b · 클래스 축**: `IssuerFacts` 고친 판을 TRI · VALE · BIP · WFG · NTR 인스턴스(받은 4 + 1콜)에 먹여 표지 판정이 주식수를 내는지 단위 시험으로 본다.
- **D2 · 순위 기대**: D1 주식수 × 10-21 종가로 IFRS 후보 시총을 내고 운영 10-21 문턱과 맞대 관문 수용 기대를 적는다. 모집단은 장부 밖까지(재무 0행 활성 3,480 중 SEC 매핑 있는 것 — 1a ③의 하한을 넓힌다).

## 5. 별칭 표 (오류 형태 4 — 부재의 뜻을 소스마다)

| us-gaap 열쇠(컬럼) | ifrs-full | 같은 뜻인가 · 조건 |
|---|---|---|
| `Revenues`(매출 · 최댓값) | `Revenue` | 같음 |
| 매출 마지막 수단 | `RevenueFromContractsWithCustomers` | `Revenue` 없을 때만(§2-4) |
| `CostOfSales` · `GrossProfit` · `Assets` · `Liabilities` · `ProfitLoss` | 같은 이름 | 같음 |
| `OperatingIncomeLoss` | `ProfitLossFromOperatingActivities` | 「표시된 대로」인 층이 같다(SEC:324) |
| `NetIncomeLoss`(지배) | `ProfitLossAttributableToOwnersOfParent` | 같음 · 비지배 없는 공시는 `ProfitLoss`도(§2-3) |
| `NetIncomeLossAvailableToCommonStockholdersBasic` | `ProfitLossAttributableToOrdinaryEquityHoldersOfParentEntity` | 같음 |
| `NetIncomeLossAttributableToNoncontrollingInterest` | `ProfitLossAttributableToNoncontrollingInterests` | 같음 |
| `IncomeLossFromContinuingOperationsBeforeIncomeTaxesExtraordinaryItemsNoncontrollingInterest`(세전 · 지분법 포함) | `ProfitLossBeforeTax` | IAS 1 세전은 지분법 손익을 포함 |
| `IncomeLossFromEquityMethodInvestments` | `ShareOfProfitLossOfAssociatesAndJointVenturesAccountedForUsingEquityMethod` | 같음 |
| `IncomeLossFromContinuingOperationsIncludingPortionAttributableToNoncontrollingInterest` | `ProfitLossFromContinuingOperations` | 같음 |
| `IncomeLossFromDiscontinuedOperationsNetOfTax` | `ProfitLossFromDiscontinuedOperations` | 같음 — 없으면 `noDiscontinued`가 서서 「총순이익 + 세」 유도가 원장 세전을 이겼다(78칸 · 25발행사 · 반증 안 렌즈 1) |
| `IncomeTaxExpenseBenefit` | `IncomeTaxExpenseContinuingOperations` | 같음 |
| `NetCashProvidedByUsedInOperatingActivities` | `CashFlowsFromUsedInOperatingActivities` | 같음 |
| `…ContinuingOperations` · `CashProvidedByUsedInOperatingActivitiesDiscontinuedOperations` | `CashFlowsFromUsedInOperatingActivitiesContinuingOperations` · `…DiscontinuedOperations` | 같음 |
| `PaymentsToAcquirePropertyPlantAndEquipment` | `PurchaseOfPropertyPlantAndEquipmentClassifiedAsInvestingActivities` | 같음 |
| `StockholdersEquityIncludingPortionAttributableToNoncontrollingInterest` | `Equity` | 같음 |
| `StockholdersEquity`(지배) | `EquityAttributableToOwnersOfParent` | 같음 · 비지배 없는 공시는 `Equity`도 |
| `MinorityInterest` | `NoncontrollingInterests` | 같음 |
| 임시 자본(mezzanine) 넷 | — | IFRS에 개념이 없다 — 빈 것이 옳다 |
| `AssetsCurrent` · `LiabilitiesCurrent` · `RetainedEarningsAccumulatedDeficit` · 현금 · `InventoryNet` | `CurrentAssets` · `CurrentLiabilities` · `RetainedEarnings` · `CashAndCashEquivalents` · `Inventories` | 같음 |
| `EarningsPerShareBasic` · `…Diluted` | `BasicEarningsLossPerShare` · `DilutedEarningsLossPerShare` | 같음 |
| 주식수 폴백 셋 | — | 받지 않는다(§2-6) |
| 이자비용 · 은행 재료 | — | 받지 않는다(§2-5) |

## 6. 결과 (2026-10-22 · 구현 `cd0235a4` · `fb76d216` · 기록 `7150ea5d` · ADR-0182)

- D1 0콜 스윕: 꺼짐 TSV · EXTRA = `main`(md5) · 켬은 `main` 켬 대비 바뀐 행 0 · 새 행 86(전환기 7종목 · FLUT 0) · EXTRA 차이도 그 종목뿐 · 새 보류 판(CRH EUR 시절)은 `main`이 저장한 적 없어 §2-9 교집합 0. IFRS 182: 꺼짐 1행(대조군 `main` 켬도 같은 1행 · md5) → 켬 150발행사 1,786행 · 전부 USD · TSM 0(TWD) · HMY 0(종목 단위).
- 구현 중 찾은 것: AEM FY2024 · FY2025는 연간 실적 6-K(표지 없음)가 라벨 앵커라 주식수가 비고, 같은 값을 실은 40-F는 「주식수는 판 차이로 안 본다」로 새 판이 안 됐다 → 관문 켬에서 라벨을 자기 기간으로 낸 공시의 표지로 새 판을 낸다(비교열로 다시 실은 뒤 해 공시는 제외 — 첫 구현이 FY2024에 2026 40-F 표지를 달아 잡았다).
- 항등식 불성립: IFRS 대차 1.0% · 총이익 9.9% 대 us-gaap 편입 7.2% · 7.4%.
- 표지 주식수가 2년 창 안에 없는 29: 인스턴스로 TRI · VALE · BIP · AZN(`ifrs-full:ClassesOfShareCapitalAxis`) · NU(자체 클래스 멤버)는 클래스로만 싣고 NTR · BN은 표지 주식수가 없다 → 클래스 판독기의 IFRS 축과 20-F · 40-F 클래스 앵커 규칙은 B v2.
- 공시 보충: companyfacts가 2023 뒤 IFRS 공시 6-K 97/375 · 20-F 27/567 · 40-F 5/145를 빠뜨린다(submissions 182콜) → 판독기 판 9가 `ifrs-full`을 읽는다.
- D2: 표지 주식수 × 10-21 종가 $5B 이상 51(ADS 비율 미반영 — A′가 관측 평면에서 가린다).

## 7. 순서

D1 · D1b · D2 → 구현(시험: 꼴마다 · 대조군은 고치기 전 코드에서 IFRS 꼴이 붉다) → D1 다시 → 커밋 · 배포(꺼짐이라 값 무변화) → B v2 loop → 반기 loop.

## 8. 반증 v1 판정 표 (2026-10-22 · 두 렌즈 「고쳐서 간다」)

| 렌즈 · 번호 | 지적 | 내 판정 · 반영 |
|---|---|---|
| 안 1 | 유도 함수 우선순위를 안 보고 컬럼에 태그를 붙임 — 세전 78칸 · 25발행사 | 섬 → §2-2 · §5(유도 재료마다 별칭) |
| 안 2 | 전환기 앵커 태그 고정 · CLS · TEAM 오계수 | 섬 → §2-2(슬롯 단계 별칭) · §1e 고침 |
| 안 3 | 6-K 주식수가 폴백에서 옴 · 폴백 배율 오류 | 섬 · 내가 연차 636쌍을 다시 쟀다 → §2-6(폴백 없음 · 클래스 축) |
| 안 4 | 보충 저장분 3,597행 재사용 | 섬 → §2-11 |
| 안 5 | 매출 최댓값이 광산사에서 합계 아닌 값 | 섬 → §2-4 |
| 안 6 | 이자비용 매핑 무효과 | 섬 → §2-5 |
| 안 7 | 지배 부재 69를 묶어 판정 · 보통주 귀속 매핑 없음 | 섬 · 내가 공시 단위로 다시 쟀다 → §1d · §2-3 · §5 |
| 안 8 | 표시 통화 다수결 | 섬(규칙 유지) |
| 밖 1 | 클래스 축 IssuerFacts us-gaap 전용 → VALE · WFG 숨김 | 섬 · 인스턴스로 TRI · VALE · BIP 차원 표지 확인 → §2-6 |
| 밖 2 | 과거 평면에 평이름 ADS | 섬 → §3(B v2 선행 · reload-gate 의존) |
| 밖 3 | IFRS 공시 안 us-gaap 태그가 이김 | 섬 → §2-1 |
| 밖 4 | 통화 게이트 우회 4발행사 | 섬 → §2-8 |
| 밖 5 | P1 보호와 맞물림 | 섬 · 수는 D1 → §2-9 |
| 밖 6 | 모집단이 장부에 묶임 | 섬 → §0 ③ 하한 · D2 |
| 밖 7 | FLUT 다른 실체 | 섬 · submissions 1콜로 확인 → §2-10 |
| 밖 8 | 반기를 B v2 앞에 둔 근거가 크기 아님 | 섬 → §3(B v2 다음) |
| 밖 9 | ADR-0153 펜스 뜻 | 섬 → §2-3이 닫는다 |
