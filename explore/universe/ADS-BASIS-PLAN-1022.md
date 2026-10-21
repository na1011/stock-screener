# ADS 기저 — 이름 축 `DEPOSITARY`와 예탁증권 비율 (2026-10-22 · 실측 중)

progress 3번 항목 「이름 축 `DEPOSITARY`(ADS 기저 ADR-0162 판정 먼저)」의 실측과 계획이다.
★ = 내가 원장·코드에서 직접 쟀다. 재료는 scratchpad `dep/`(판정 뒤 지운다).

## 0. 질문

1. 이름 축 `\bDEPOSITARY\b`(`UsMarketCapUniverseAdapter.SECURITY_KIND_WORDS`)가 오늘 순위에서 무엇을 자르는가.
2. 자르는 것 중 사업회사 보통주(ADS)를 들이려면 시총 = 주식수 × ADS 종가의 기저(1 ADS = 보통주 k주)를
   원장이 주는가 — ADR-0162 §배경 「ADS 비율 자체는 소스가 안 준다(진짜 한계)」가 지금도 서는가.
3. 이름에 그 낱말이 없어 이미 순위에 든 ADS(ratio ≠ 1)는 몇이고 값이 얼마나 틀렸는가.

## 1. 실측

### 1a. 이름 축이 자르는 후보 ★
- 무엇에 물었나: 운영 SELECT(꺼진 순위 질의 `JdbcStoredShareCounts.SQL`의 술어 — 2년 창 · 양수 주식수 ·
  통화 · SIC 6798/6770 · 열린 행) ∩ 저장 이름 `\mDEPOSITARY\M`. 오늘 이름은 Alpaca `/v2/assets`(active · us_equity · 13,235건).
- 모집단: 저장 이름이 그 낱말인 미국 행 445 → 주식수가 있는 열린 행 172 → 통화·SIC 통과 **109**.
  오늘 Alpaca 이름 기준 그 낱말 자산 443 = ADS 꼴 253 + 그 밖(우선주 예탁증권 등) 190.
- 109의 두 부류:
  - **우선주 예탁증권**(「Depositary Shares each representing 1/40th interest in a share of … Preferred」 —
    HBANL · MCHPP · SMCIP · GOOGM/GOOGN · ACGLO …): 발행사 주식수를 그대로 써서 시총이 부푼다. **자르는 것이 맞다.**
  - **ADS 보통주 68**(이름에 American Depositary · ADS): ARM · BABA · NTES · SE · ONC · EDU · BEKE · LI · ZTO · ZLAB ·
    NICE · NIO … — 사업회사 보통주의 상장 형태다. **지금 순위에서 빠진다.**
- 대조: 순위 질의가 같은 날 편입 1,000을 냈다(1,000위 시총 약 $5.0B · 10-20 종가).

### 1b. 원장이 ADS 비율을 주는가 ★ (67 CIK · SEC 201콜 · 10-22 01:4x KST)
- 무엇에 물었나: submissions → 최신 연간 XBRL 공시(20-F · 10-K · 40-F) → 폴더 `index.json` → 인스턴스(`*_htm.xml`)의
  `dei:Security12bTitle` · `dei:TradingSymbol` · `dei:EntityCommonStockSharesOutstanding` · `dei:EntityListingDepositoryReceiptRatio`.
- 결과:
  - 표지 증권 이름(`Security12bTitle`)이 비율을 글로 싣는다 **59/67**(「each representing five ordinary shares」 ·
    「each ADS represents 15 ordinary shares」 · 「each six (6) ADSs representing one (1) Class A ordinary share」 ·
    「each ten (10) ADSs representing twenty-three (23) ordinary shares」 · 「0.4 Class A」 — 꼴이 여럿이고 수사·숫자 둘 다).
  - 비율 태그는 **8/67**(BABA 8 · HCM 5 · IH 5 · BLTE 1 · TUYA 1 · WB 1 · ZTO 1 · ADAG 1.25) — 그중 표지 글에 비율이
    없는 것은 ADAG 하나.
  - 글에도 태그에도 없는 7: LFS · MWC · NNDM · QNRX · TIGR · XNET · BEKE(표지에 ADS 줄 자체가 없다).
    TIGR · BEKE는 Alpaca 이름이 비율을 싣는다(fifteen · three). **표지 본문 각주 · F-6은 아직 안 물었다.**
  - DBVT는 「Depository」 철자다 — 이름 축 정규식도 판독 정규식도 철자 둘을 받아야 한다.
- **ADR-0162 §배경 · `sec-edgar-financial.md` §ADS 비율이 물은 것은 companyfacts의 비율 개념(16종목 중 BABA 1)과
  종목명 텍스트(273 중 74)다.** 공시 인스턴스의 표지 증권 이름은 안 물었다 — 엔드포인트 하나만 묻고 「없다」를 적은 꼴이다.

### 1c. 저장 주식수의 단위 ★
- 저장 `shares_outstanding` = 그 판 표지 `dei:EntityCommonStockSharesOutstanding`(보통주 · 클래스 합) — 25종목 대조 전부 같은
  공시의 같은 값(ONC · ZLAB · GPCR은 저장이 뒤 10-Q라 값이 다르다).
- **BABA FY2026 20-F(2026-05-20)는 태그가 10배 작다** — 본문 「18,580,374,278 Ordinary Shares」, 인스턴스 1,858,037,427.
  companyfacts 이력은 2020~2025 판 전부 184억~217억(보통주)이고 2015~2019는 24~26억(1:8 분할 전). **한 판의 발행사 태깅 오류다.**
  비율 8만 곱해 고치면 BABA가 실제의 1/10(약 $25B)로 간다 — 기저 고침은 이 꼴의 오류를 함께 다뤄야 한다.

### 1d. 이름 없이 순위에 드는 ADS ★ (후보 3,986 CIK · SEC 5,507콜 + Alpaca 10-20 봉)
- 무엇에 물었나: 후보(1a 술어 · 이름 축 앞) 4,402행의 CIK마다 submissions `stateOfIncorporation` → 미국 주 밖 · 빈칸 884 CIK는 최신 연간
  인스턴스 표지 → 표지에 ADS 줄(`Security12bTitle`이 depositary · ADS)이 있는 **83 CIK · 87행**, 그 행의 거래 기호가 ADS 줄인 **78행**.
- **편입 중 ADS는 TEVA 하나(비율 1 · 값 맞음)** — 지금 편입에 ADS 비율로 부푼 값은 없다(ADR-0126 펜스의 이름 표방 편입도 TEVA 하나).
- **10-19 정기 재무(후보 854 · 신규 838)가 해외 발행사에 처음 주식수를 넣었다.** 그 행들은 이름 축을 피한다 — HDB 「HDFC Bank Limited」 ·
  UXIN 「Uxin Limited ADS」 · BZ 「… American Depository Shares」. **다음 리밸런싱(10-24 토 20:00) 재현**(오늘 규칙 · 10-20 종가 · UNRESOLVED 뺌 ·
  CIK 대표 거래대금 — 재현 순위 ∩ 지금 편입 990/1,000):
  - 든다: UXIN(1 ADS = 300주 · 순시총 58.9B · 비율 반영 약 0.2B — **들 자리가 아니다**) · HDB(3 · 355.0B → 약 118B — 들 자리는 맞고 값이 3배) ·
    BZ(2 · 15.3B → 약 7.6B — 들 자리는 맞고 값이 2배). 나머지 넷(BB · MANE · NBIS · NXST · ORKA · PTCT)은 ADS가 아니다.
  - 드는 순간 시총 · PER · PBR · EPS 계열이 비율배로 틀린다(`FinancialMath.sharesOf`가 같은 주식수를 쓴다).
- **비율을 반영하면 들 자리인데 이름 축이 자르는 ADS**(재현 · 비율 반영): ARM 235.2B · NTES 79.9B · SE 51.3B · ONC 43.7B · BABA(1c) ·
  ZTO 16.0B · LI 12.1B · EDU 9.5B · NIO 8.5B · SIMO 7.2B · BLTE 6.1B · NICE 6.9B · BZ · HDB · TAL(1 ADS = 1/3주 → 약 6.6B · 지금은 1/3로 낮게 셈).

### 1e. 판정 판독기의 정확도 ★ (0콜 · `adsratio.py`)
- 최신 표지 78행(우선주 예탁증권 둘 뺌 76): 표지 글로 비율 67 · 태그만 2(ADAG · WB) · 없음 7(BZ · LFS · MWC · NNDM · QNRX · TIGR · XNET).
  태그 있는 7과 표지 글 판독이 전부 같다.
- 판독기 첫 판이 **TAL을 1로 읽었다**(원문 「each three representing one Class A common share」 = 1/3 · 태그 0.33). 고친 뒤 0 불일치 —
  **틀린 수는 빈칸보다 나쁘다**: 판독기는 꼴마다 시험을 둔다(「N ADSs representing M」 · 「each ADS represents」 · 붙어 쓴 「representingone」 ·
  「two hundred and forty」 · 「right to receive twenty (20)」 · 「Equity Shares」 · 「0.4」 · 「1/1,000th interest」(우선주 — 비율 아님)).
- 없음 7은 연간 보고서 본문이 비율을 싣는다(BZ 2 · QNRX 35 · TIGR 15 · XNET 5 …) — 다만 본문 전체에는 잡음이 있다(LFS 1과 10 · XNET 「1,370,285」).

### 1f. 과거 판 ★ (ADS 77 CIK · 2015~ 정기 공시 933 · SEC 1,912콜)
| 공시 연도 | 판 | 표지 이름 | 비율 |
|---|---|---|---|
| 2015~2018 | 138 | 0 | 7(태그) |
| 2019 | 57 | 11 | 7 |
| 2020 | 69 | 47 | 37 |
| 2021~2026 | 636 | 616 | 526 |
- **비율이 창 안에서 바뀐 발행사 22/77** — BABA 1→8(2020) · NTES 25→5 · EDU 1→10 · ZLAB 1→10 · ATHM 1→4 · UXIN 3→30→300 · AMRN 1→20 …
  비율은 **판마다** 단다(종목 상수 금지 — ADR-0162 §규칙과 같은 이유).
- 상장 줄(거래 기호 = 행 기호)과 ADS 줄이 맞물린 판 620/674 · 그중 비율 506.
- 표지 글 대 태그 불일치 7판 — NTES 3판(태그가 옛 25) · API(0.25 — 뒤집힘) · DBVT · AMBR · AHG. **1순위는 표지 글이다.**
- 비율로 나눈 ADS 환산 주식수가 비율 변경을 넘어 이어진다(ATHM 119 → 120 · NTES 2021~ 670 → 638 · AMRN 20배). 안 이어지는 판:
  EDU 2021(보통주 10:1 분할 뒤인데 표지 비율 1) · BABA 2026(태그 10배) · SLN 10-Q 2판(3 → 1 → 3) — **연속성 검사로 숨길 꼴**이다.
- **AMRN은 ADR-0162가 원장 사건으로 `SHIFTED`(20배)한 종목이다** — 판마다 비율을 넣으면 기저 판정(`ShareBasisDeriver`)의 입력 계열이
  ADS 환산이 되어 「따라감」이 된다. 두 고침을 겹치면 20배를 두 번 곱한다 → 비율을 기저 판정 **앞**에 둔다.

## 2. 계획 A′ — 관문 밖 리밸런싱이 공시 표지로 비율이 1 아닌 ADS를 뺀다 (10-24 전 · v2)

v1(이름 축에 ADS 표기 추가)은 두 렌즈 반증이 「고쳐서 간다」로 깼다 — 이름은 HDB(평범한 이름 · 3배)를 못 보고 값이 옳은 비율 1 ADS(CMPS)를 자르며,
「관문 밖에는 볼 재료가 없다」는 저장과 조회를 섞은 문장이었다. 그리고 그 안은 ADR-0126에서 사용자가 고르지 않은 A였다.
**사용자 결정(10-22)**: ADR-0126 C의 전제 둘(「비율을 얻을 경로가 없다」 · 「큰 ADR은 통화 게이트에 막힌다」)이 거짓이 됐음을 올리자
「claude.md의 판단기준과 드라이런 프로세스를 복기하여 스스로 최선의 판단을 해봐.」 → 아래가 그 판단이다(내 판단 · 사용자가 고른 안이 아니다).

**원인을 바로 적는다(v1의 거짓 서술 고침)** ★
- UXIN · BZ · HDB의 주식수 행은 08-30부터 있었다. 10-17까지 순위에 없던 것은 **통화 게이트**(종목에 비USD 행이 하나라도 있으면 제외) 때문이다.
- 10-19 정기 재무가 미국 「사라진 판 회수」(`completeVintageHistory`)로 비USD 정정판을 지웠다 — 10-18 덤프 대 지금: **비USD 행을 전부 잃은 발행사 48**
  (BABA CNY 7 · HDB INR 11 · UXIN CNY 5 · BZ CNY 4 · NIO · NTES · BEKE · BIDU · LI · ZTO · NBIS …) · 그중 순위 후보 44.
  `SecEdgarFacts.MARKET_CURRENCY` javadoc은 「이미 저장된 비USD 행을 지우지 않으므로 게이트는 닫혀 있다」고 적었다 — 회수가 「통화로 걸러 안 담은 판」을
  「소스가 거둔 판」으로 읽었다(폴백 근거의 이식 꼴). **이것은 통화 축의 별도 판정 대상이다(§4)** — A′는 그 결과로 순위에 닿는 ADS만 다룬다.

**판단 — 관문 밖은 ADS 비율을 지표까지 일관되게 쓸 수 없다**(비율 저장은 관문 뒤 B · `FinancialMath.sharesOf`가 같은 주식수를 쓴다).
원장이 「ADS인데 비율이 1이 아니다 · 비율을 못 읽는다」를 말하면 **순위에서 뺀다**(미표시 — ADR-0162 「모르면 값을 안 낸다」와 같은 방향).
비율 1이면 값이 옳으므로 그대로 둔다. 이름 축은 손대지 않는다(DEPOSITARY 이름의 비율 1 ADS — ARM · SE · ZTO … — 를 들이는 것은 B).

**무엇을**
1. `DepositaryRatio`(순수 함수 · `shareclass` 옆 `listing` 패키지): 증권 이름 → ADS인가 · 1 ADS당 보통주 수. 꼴은 1e의 시험 목록 전부
   (「N ADSs representing M」 · 「each N representing M」(TAL 1/3) · 「each ADS represents」 · 붙어 쓴 represent · 수사(two hundred and forty) ·
   「right to receive twenty (20)」 · Equity/Common/Ordinary · 0.4 · 우선주 「1/1,000th interest」는 ADS 아님 · Depository 철자).
2. 순위(`UsMarketCapUniverseAdapter.rank`): CIK 대표까지 고른 뒤 순시총 순으로 내려가며 상위 N을 채운다. **표지를 읽는 범위**는
   주식수 판이 해외 양식(20-F · 40-F · 6-K · 정정)이거나 이름(목록 · 저장)에 ADS · ADR · Depositary · Depository 표기가 있는 후보다(SLN 10-K 1:3이 이름으로 든다).
   표지는 `SecListingFormClient.lastCoverBefore(cik, asOf 다음날, asOf − 2년)` — 그 후보의 기호 줄(점·하이픈 정규화) 증권 이름 → ADS면 비율.
   기호 줄이 없으면 표지의 ADS 줄로 본다. **ADS이고 비율 ≠ 1이거나 못 읽으면 뺀다**(제외 내역에 `ADS 비율` 칸 · 뺀 기호와 비율을 로그).
   받기 실패는 부분 목록 금지(ADR-0008)와 같이 실행을 세운다. 표지를 못 찾으면(시도 안에 기호 있는 표지 없음) 그 후보는 **판정 불가로 뺀다**.
3. 경계 로그 · `Ranking`에 표지 판정 수를 싣는다. 관문 켬/끔 무관(B가 관문 뒤에서 이것을 대신하면 그때 걷는다).
4. 시험: `DepositaryRatio` 꼴마다 · 순위 시험(HDB 꼴 3 → 빠짐 · TEVA 꼴 1 → 남음 · 표지 ADS 줄 없음 → 남음 · 비율 못 읽음 → 빠짐 · 범위 밖 10-Q 평범 이름 → 표지 안 부름).
   대조군: 고치기 전 코드에서 HDB 꼴이 든다(붉음).

**0콜 재현(10-20 종가 · 1d 표지 재료)** ★: 상위 1,000을 채우며 표지를 읽은 해외 양식 후보 22 → 뺌 3(HDB 3 · UXIN 300 · BZ 못 읽음) · 남음 19(ADS 줄 없음).
지금 편입 변화 0. 10-24 재현의 들고 남: 든다 BB · MANE · NBIS · NXST · ORKA · PTCT · RGC / 빠진다 AROC · IESC · MTRN · OKLO · RIOT · RUSHA · SHC(일상 순위 이동).
이름 범위를 더하면 SLN(1:3) · CMPS(1)도 읽는다 — 둘 다 경계 아래라 결과 같음.

**드라이런(구현 뒤 · 배포 전)**: 실제 코드 경로 — 운영 DB 읽기 전용 + Alpaca 봉 + SEC 표지로 `rank(US_TOP1000, 오늘)`를 부르는 live 시험(쓰기 0)으로
위 재현과 대조한다(표지 콜 수 · 뺀 기호 · 편입 대조). 재현과 다르면 차이를 설명하기 전에는 배포하지 않는다.

**사정거리(이 고침이 서지 않는 곳 · B가 닫는다)**
- 이름이 평범한 10-K/10-Q ADS — 지금 후보에서 TEVA(1 · 옳음) · CRTO(1) · AMRN(20 · ADR-0162 `SHIFTED` 0.05가 이미 맞춤)뿐이다(1d · 78행 전수). 비율 ≠ 1이고 원장 사건도 없는 것은 0.
- 표지 비율이 낡은 판(EDU 2021 꼴) · 주식수 태그 오류(BABA 2026 꼴) — A′는 비율 ≠ 1이면 빼므로 이 둘은 이미 빠진다(BABA 8 · EDU 10).
- 과거 날짜 순위(`UsPastRanking` · 관문 경로)는 안 탄다.
- 관문 밖에서 비율 1 ADS 중 DEPOSITARY 이름은 계속 빠진다(오늘과 같다).

## 3. 계획 B — 관문 뒤 판마다 ADS 비율 (다음 계획 · 별도 반증)
- A′의 표지 판정과 이름 축 술어(`nonCommonName` 공유)를 관문 켬에서 대신하고 걷는다.
- 재료: ADS 발행사의 정기 공시 표지(`Security12bTitle` · `TradingSymbol` · 비율 태그) — 클래스 축 앵커 후보(복수 클래스 꼴)에 안 드는
  단일 클래스 ADS 발행사가 대부분이라 받기 집합을 넓혀야 한다(약 77 CIK × 판 · 1f가 그 재료의 꼴).
- 판정: 상장 줄의 표지 글 비율(1순위) · 태그(보조) · 어긋나면 숨김 · 앞 판과 ADS 환산 연속성(원장 사건으로 설명 안 되는 계단 → 숨김: EDU 2021 · BABA 2026 · SLN).
- 기저 판정(ADR-0162) **앞**에 둔다 — AMRN 이중 곱 금지. 이름 축은 관문 뒤에서 우선주 예탁증권만 자른다.
- 표지 이전 판(2015~2019)은 비율이 없다 → 숨김(미표시) · 지표 창(2025-09-26~)의 5년 소급이 2020-09 근처라 사정거리를 잰다.
- ADR-0162 §배경 「ADS 비율 자체는 소스가 안 준다」를 1b·1f의 수로 고친다(가짜 한계 — companyfacts 하나만 물었다).

## 4. 통화 축 — 10-19 회수가 비USD 정정판을 지웠다 (별도 판정 · 순서는 progress)
- 무엇에 물었나: 운영 덤프 `stock_screener-20261018T220759Z.dump`(10-19 07:08)의 `financial_statement` 표만 흘려 읽기(`pg_restore -a -t`) 대 지금 운영 SELECT.
- 비USD 행 보유 미국 종목 10-18 200 → 지금 152 · 전부 잃은 것 48(순위 후보 44). BABA는 FY마다 이듬해 비교열에서 온 CNY 정정판(`is_latest`)이 지워지고 USD 편의환산 원판만 남았다.
- 물을 것: ① 회수가 통화로 걸러 안 담은 판을 거둔 판으로 읽는 것이 맞나(javadoc 「지우지 않는다」와 어긋남) ② 남은 USD 편의환산 계열이 지표 값으로 서나(해마다 다른 환율 · 정정 없음 · 분기 6-K 비USD 없음)
  ③ 이것이 값을 바꾸는 코드였다면 관문 규칙 위반인지(정기 FR 갈래가 한 일). 순위 진입 영향: 10-24에 NBIS(비ADS) — 들 자리인지 안 잼.
