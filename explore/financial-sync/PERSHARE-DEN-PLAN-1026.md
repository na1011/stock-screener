# 주당 지표 분모 — PBR의 자본을 보통주 몫으로 (미국 · 관문 뒤) — 계획 v1 (2026-10-26)

`docs/reload-gate.md` 「주당 지표의 분자 · 분모가 보통주 몫이 아니다」 행의 분모 절반이다. 분자는 ADR-0191로 끝났다.
국내(같은 문서 「PBR · BPS 분모에 우선주 · 신종자본증권 자본이 든다」 행)는 이 계획 밖이다. 국내는 관문이 따로이고, 국내 우선주는 상장된 참가형이라 질문이 다르다(아래 7절).

## 0. 문제

`PBR` = 종가 ÷ (자본 ÷ 보통주 주식수)다. 분자 쪽은 보통주 시가총액이다. 그런데 분모 자본 `total_equity_controlling`(모회사 귀속 자본)에는 보통주가 아닌 청구권이 들어 있다.
- 법인: 영구자본 안의 우선주 장부가.
- LP: GP 자본, 우선 단위, 보통 단위가 아닌 LP 클래스.

미국 GAAP의 `StockholdersEquity`는 정의상 우선주를 포함한다. 그래서 짝이 어긋난다 — 시가총액은 보통주 몫인데 자본은 우선주까지 든 몫이다.

운영(관문 꺼짐, 2026-10-23 지표) PBR과 보통주 자본으로 다시 잰 값:

| 종목 | 지금 | 보통주 자본으로 |
|---|---|---|
| VST | 8.62 | 약 1.8배(최신 BS 지배 5,482M · 우선주 2,476M) |
| C | 0.97 | 약 1.07 |
| BA | 24.3 | 보통주 자본이 약 3.5억 달러라 수백 배 |

LP 셋(CQP · ET · PAA)은 관문 꺼짐에서 PBR이 없다. ADR-0188 관문 켬에서 처음 생기는데, 그 분모가 `PartnersCapital`이다. CQP는 754M인데 보통 단위 몫은 3,692M이다(GP −2,938M).

## 1. 실측 (2026-10-26)

### 1-1. 무엇에 물었나

- **팩트 캐시**: `.factcache` companyfacts. 현 편입 1,000 중 캐시에 있는 990, 그중 자본 태그가 있는 939발행사.
  - 범위: 2020-09-01 뒤 10-K · 10-Q 20,624공시. 0콜.
  - 태그: `PreferredStockValue` · `PreferredStockValueOutstanding` · `PreferredStockIncludingAdditionalPaidInCapital(NetOfDiscount)` · `PreferredStockLiquidationPreferenceValue` · `PreferredStockSharesOutstanding` · `GeneralPartnersCapitalAccount` · `PreferredUnitsPreferredPartnersCapitalAccounts` · 우선주 배당 세 태그(ADR-0191 `PREFERRED_DIVIDENDS`).
- **공시 인스턴스**: SEC Archives `index.json` + 인스턴스 1개. 합계 456콜.
  - 표본 A(우선주 · LP 신호 발행사 179)의 최신 10-K · 최신 10-Q: 358공시.
  - 표본 B(신호 없는 발행사 760 중 무작위 40, seed 1026)의 최신 10-K: 40공시.
  - 앞선 탐침 59공시.
- **운영 SELECT**: PBR 최신값만 읽었다.

### 1-2. companyfacts만으로는 안 된다

- **신호 발행사는 179다.**
  - 축 없는 우선주 값 · 주식수 · GP · 우선 단위가 있는 발행사 91+23+17+4.
  - 우선주 배당 태그만 있는 발행사 149(겹침 포함). 그중 상당수는 자회사 우선주(비지배) 배당이거나 기중 상환이다 — 아래 1-4.
- **축 없는 태그가 장부가를 못 주는 꼴이 셋이다.**
  - `PreferredStockValue`는 정의가 「par or stated value」다. 액면만 싣는 발행사가 있다: AXP 0(1,600주 · 장부가 1,584M은 APIC 안) · PNC 0 · MCHP 0 · COF 0 · MET 0 · AUB 173k · HL 39k · FTAI 68k · VCTR 199k.
  - 우선주를 축 맥락에만 싣는 발행사가 있다. companyfacts로는 우선주가 없는 것처럼 보인다: MS(9,750M — 축 없는 `PreferredStockValue`는 2013이 마지막) · STT 3,559M · NTRS 885M · DUK 973M · BA · KKR · QXO.
  - 축 없는 값이 자회사 우선주인 꼴도 있다. ES `PreferredStockValueOutstanding` 155.6M = `RedeemableNoncontrollingInterestEquityPreferredCarryingAmount` 155.6M(비지배 · 메자닌).
- **대조군(표본 B 40)**: 인스턴스로도 40/40 우선주가 없다(성분 0 · 값 0 · 주식수 0이거나 없음). 신호 없음을 「우선주 0」으로 읽는 근거다(40건이라 상한은 약 7%).

### 1-3. 인스턴스가 주는 것

- **자본변동표 성분이 장부가다.** `StockholdersEquity`류 × `StatementEquityComponentsAxis` = `PreferredStockMember` | `PreferredStockIncludingAdditionalPaidInCapitalMember`가 그것이다.
  - 값: MS 9,750 · PNC 5,758 · STT 3,559 · JPM 20,045 · C 20,050 · GS 15,153 · LNC 986 · WTFC 425 · VST 2,476 · DUK 973.
  - 표본 A 최신 공시 중 이 성분이 장부가로 선 발행사는 58이다.
- **성분이 액면인 발행사도 있다** — 우선주를 APIC에 합쳐 싣는 꼴이다(AXP 0 · AUB · FTAI · VCTR · JEF · HL). 이때 주석이 시리즈 값을 준다.
  - AXP: 시리즈 축 `PreferredStockValueOutstanding` 1,584M · 청산가 1,600M.
  - COF: 축 없는 APIC 포함 태그 5,407M.
  - MCHP · MET · VOYA · BRKR · BA: 청산가(`PreferredStockLiquidationPreferenceValue`)만.
- **LP**
  - CQP: `GeneralPartnersCapitalAccount` −2,742M.
  - ET: 우선 단위 3,356M + GP −2M.
  - WES: GP 4.6M.
  - PAA: `LimitedPartnersCapitalAccountByClassAxis`에만 실린다 — 보통 단위 7,801M · 우선 A 1,248M · 우선 B 787M. 합이 `PartnersCapital` 9,836M과 닫힌다.
    - 보통 단위 구성원을 무엇으로 고르나: LP 단위 가중평균이 실린 구성원이다(`paa:CommonUnitsMember` — ADR-0187 엔진이 이미 쓰는 그 칸).
  - EPD · MPLX: 비보통 청구권 0. EPD의 LP 계정 30,700은 자기 단위(−1,297) · AOCI(336)를 빼고 있어, `PartnersCapital` 29,739가 보통 몫이다.
  - 그래서 LP는 「LP 계정」이 아니라 **「지배 자본 − 비보통 청구권」**으로 짓는다.
- **10-Q는 잔액을 자주 안 싣는다.**
  - MS 10-Q: 자체 확장 태그(`ms:PreferredStockCarryingValue`)에만 싣는다.
  - MET · AXP 10-Q: 값이 없다.
  - 세 곳 모두 우선주 주식수(축 없는 값 또는 시리즈 합)가 직전 10-K와 같다 — MS 1,267,382 · AXP 1,600 · MET 24,572,200.
- **메자닌**: CELH는 부채 2,178 + 자본 1,181 = 3,359인데 부채 · 자본 합계가 5,120이다. 1,760만큼 모자라는데 이것이 메자닌 우선주다(청산가 1,135M). 자본 안이 아니므로 뺄 것이 0이다. MSTR(메자닌 6,920M · `TemporaryEquity` 태그)도 같다.

### 1-4. 규칙 시안 v4로 잰 분류 (표본 A 358공시 · 171발행사의 최신 대차대조표)

| 갈래 | 뜻 | 최신 발행사 |
|---|---|---|
| `stmt` | 자본변동표 우선주 성분 | 58 |
| `tag` | 축 없는(없으면 시리즈 합) 장부가 태그 · 비지배 값과 같지 않음 | 5 |
| `liq` | 청산가 · 모회사 우선주 증거 있음 · 메자닌 간극 없음 | 5 |
| `lp` | GP + 우선 단위 + 보통 아닌 LP 클래스 | 4 |
| `zero` · `none` · `retired` · `subsid` · `mezz` | 뺄 것 0(명시 0 · 신호 없음 · 기중 상환 · 배당 = 비지배 손익 · 메자닌) | 79 |
| `nominal` | 주식수만 있고 경제 조건(배당 · 청산가 · 상환가 · 전환 조건)이 원장에 없음 → 액면 그대로 | 3(UAL 2주 · BX 2주 · LITE) |
| 못 정함 `open` · `divonly` | | 17 |

- 대조군 B 40공시: `none` 23 · `zero` 16. 하나도 안 갈렸다.
- **이어받기(`carry`) 증인**: 못 정한 BS의 우선주 주식수가 같은 발행사의 앞 BS(정한 값)와 같으면, 장부가가 그대로다. 우선주 장부가는 발행 · 상환 · 전환으로만 움직인다. 이 증인으로 AXP · MET · MS 10-Q가 정해진다. MRVL 2026-08은 주식수가 0 → 2,000,000이라 안 잇는다(새 발행).
- **이어받은 뒤 남는 못 정함 14**:
  - 정말 못 정함:
    - AUB · FTAI · JEF — 액면만 있고 배당이 있다.
    - HPE — 의무전환 우선주 30M주 · 값 0.
    - VCTR · MDGL — 참가형 전환우선주.
    - APG · AM · MRVL.
  - 증인이 모자람: ES 10-Q · LEN · TXNM · VG · WDC 10-Q — 배당만 있고 BS에 우선주가 없다. 자회사 몫인지 기중 상환인지를 그 공시가 안 가른다.
- **최신 PBR 배수**(지배 자본 ÷ 보통주 자본, 정한 발행사):
  - 1% 넘음 62 · 10% 넘음 24.
  - BA 17.4 · VST 1.82 · ARES 1.58 · SMCI 1.41 · BRKR 1.39 · MCHP 1.30 · ALB 1.28 · IVZ 1.25 · OXY 1.25 · PAA 1.23 · VOYA 1.15 · 대형 · 지방은행 1.06~1.15 · ET 1.11 · KKR 1.09 · ORCL 1.08.
- **표본의 한계**: 연차 1 + 분기 1이다. 5년 창(20분기)의 수는 드라이런이 낸다(5절).

## 2. 결정 (관문 켬 `stockscreener.gate.us`)

1. **새 판정 `CommonEquity`** — (공시, 대차대조표 날짜)마다 비보통 청구권 N과 갈래를 낸다. 클래스 축 엔진(`IssuerTimeline`)이 분자(ADR-0191)와 같은 재료 · 같은 실행으로 정한다.
2. **사다리** — 첫 번째로 서는 것을 쓴다. 맥락은 축 없음 또는 축 하나다. 법인 · 연결실체 · 재작성 · 소유 축 맥락은 버린다.
   1. LP: N = GP 자본(축 없는 값 · 또는 `PartnerTypeOfPartnersCapitalAccountAxis`/`PartnerCapitalComponentsAxis` = `GeneralPartnerMember`) + 우선 단위(`PreferredUnitsPreferredPartnersCapitalAccounts`) + 보통 아닌 LP 클래스(`LimitedPartnersCapitalAccountByClassAxis` 중 LP 단위 가중평균이 안 실린 구성원) → `lp`.
   2. 자본변동표 우선주 성분 > 0이고 액면이 아님 → `stmt`.
      - 액면 판정: 그 기간 우선주 배당(축 없음)이 성분의 25%를 넘으면 액면이다. 연 배당률이 25%를 넘는 우선주는 없다.
   3. 장부가 태그(축 없음 → 없으면 시리즈 합) > 0. 비지배 · 메자닌 우선주 태그 값과 1% 안에서 같지 않고 액면이 아님 → `tag`.
   4. 청산가(축 없음 → 없으면 시리즈 합)가 있음. 비지배 · 메자닌 값과 같지 않음. 모회사 우선주 증거가 있음(주식수 · 성분 · 값 태그). `부채 · 자본 합계 − 부채 − 비지배 포함 자본`(메자닌 간극)이 청산가의 절반에 못 미침 → `liq`.
   5. 뺄 것 0:
      - `mezz` — 청산가가 메자닌 간극 안 또는 비지배 · 메자닌 태그와 같음.
      - `zero` — 우선주 주식수 명시 0.
      - `subsid` — 우선주 배당 = 비지배 손익.
      - `retired` — 그 기간 우선주 상환 · 재매입 · 전환 흐름이 있고 BS에 우선주가 없음.
      - `none` — 신호 없음.
   6. 주식수 > 0인데 값이 없음:
      - 경제 조건(배당 · 청산가 · 상환가 · 전환 조건)이 원장에 없음 → `nominal`(액면 그대로).
      - 있음 → 앞 BS 이어받기를 묻는다.
   7. 이어받기 `carry` — 같은 발행사의 앞 BS(정한 값)와 우선주 주식수가 같다(둘 다 실렸을 때만) → 그 N.
   8. 그 밖은 못 정함 `open`.
3. **받기** — `IssuerTimeline.equityCandidates(cf)`.
   - 신호 발행사(companyfacts 1-1 태그 중 하나 · LP 자본 태그)의 행 폼 공시가 대상이다(`DENOMINATOR_FROM` 뒤).
   - 판독기 판 8(`ClassFactReader`)이 자본 재료를 싣는다:
     - 시점: 성분 · 장부가 · 청산가 · GP · 우선 단위 · LP 클래스 · 주식수 · 비지배 · 메자닌 · 부채 · 합계.
     - 기간: 배당 · 상환 흐름.
   - 표본 추산으로 179발행사 · 2020-09 뒤 3,880공시다. 앵커 · 분모 · 분자 후보와 겹치는 것은 이미 받는다.
4. **행에 붙이기** — `CommonEquityRows`.
   - 행의 `report_code` 공시 · 행 끝 날짜의 값을 쓴다.
   - 그 공시가 그 날짜 BS를 안 실었으면, 같은 날짜를 정한 공시 중 제출일 ≤ 행 공시일인 최신 공시의 값을 쓴다.
   - Q4 행은 FY 끝 날짜라 FY와 같다.
   - 저장: 관문 시험 마이그레이션 `V70_7__common_equity.sql`의 `financial_common_equity`(재무 행 id · 붙일 때의 `report_code` · N · 갈래 · 엔진 판). 유도(`ShareClassDeriver`)가 분자와 한 트랜잭션에 갈아 넣는다.
   - 신호 없는 발행사도 행마다 `none`을 쓴다 — 읽는 쪽이 「붙은 것 없음」을 못 정함으로 읽기 때문이다.
5. **지표** — `FinancialMath.commonEquity(figures)` = 관문 켬 · 붙은 갈래가 정함이면 `total_equity_controlling − N`, 못 정함 · 붙은 것 없음이면 null(미표시). 관문 꺼짐은 지금 경로 그대로다.
   - `PBR`: 최신 판 하나.
   - `PBR_MEDIAN_5Y`: 창 안 모든 행이 정함일 때만 보통주 자본으로 낸다. 하나라도 못 정함이면 미표시다(`controllingEquityThroughout`와 같은 「창 단위」 원칙 · ADR-0191 §규칙).
   - `PBR_DISCOUNT_5Y` · `PBR_PCTL`: 의존이 따라간다.
   - **`NCAV_TO_MARKET`도 우선주를 뺀다.** 그레이엄 NCAV는 「유동자산 − 보통주보다 앞서는 모든 청구권(우선주 포함)」이고 분자가 보통주 시가총액이다 — 같은 어긋남이다. LP의 GP · 우선 단위도 같다.
6. **곁 소비자** — 재무 탭에 「보통주 자본」 칸을 둔다(관문 켬). 분자의 「보통주 귀속」 칸과 같은 자리다.

## 3. 바꾸지 않는 것 (사정거리)

- `ROE` · `ROE_MIN_3Y`: 분자(지배 NI — 우선주 배당 전) · 분모(지배 자본 — 우선주 포함)가 둘 다 실체 단위라 짝이 맞다. 주당 지표가 아니다.
  - 「보통주 ROE」(NIC ÷ 보통주 자본)로 바꾸는 것은 지표 정의 변경이라 이 계획 밖이다. 재지 않았다.
- `DEBT_RATIO` · `ALTMAN_Z''` · `ROIC_PRETAX`: 양변이 연결 재무상태표다(ADR-0159).
- `total_equity_controlling`의 뜻: 그대로다. N을 따로 싣고 읽을 때 뺀다 — 분자 `net_income_controlling`을 안 바꾼 것과 같은 이유다(ADR-0191 §규칙 마지막).

## 4. 버린 대안

| 대안 | 근거(실측) |
|---|---|
| companyfacts 축 없는 태그만 | MS 9,750M · STT · NTRS · DUK가 「우선주 없음」으로 보이고, AXP · PNC는 액면 0이다(1-2) |
| `PreferredStockValue` 하나 | 정의가 par or stated다 — 액면 발행사 9곳 이상 |
| 청산가를 첫 칸으로 | FCNCA 10-Q의 시리즈 청산가 합 3,540M이 성분 1,765M을 덮었다(시리즈 합의 겹침) · CELH · NI는 청산가가 메자닌 · 자회사 몫 |
| LP 계정(`LimitedPartnersCapitalAccount`)을 보통 몫으로 | EPD: 자기 단위 · AOCI를 빼 30,700 대 `PartnersCapital` 29,739. ET도 82M 갈린다 |
| 우선주 배당만 있으면 못 정함 | AEE · ES(배당 = 비지배 손익) · COHR · SRE · APLD(기중 상환 · 전환)의 정직한 PBR을 버린다 |
| 참가형 전환우선주를 전환 기준 지분으로 안분 | 안분 비율을 원장이 자본에 대해 주지 않는다 — 지어낸 값이다. 못 정함으로 둔다 |
| 확장 태그(`ms:PreferredStockCarryingValue`) 이름으로 읽기 | 종목 특정 · 낱말 분류(2026-10-25 사용자 기준) — 주식수 이어받기로 정한다 |

## 5. 드라이런 (반증 뒤)

- **재료**
  - 팩트 캐시. 받기 대상은 3절의 후보로 잰다(표본이 아니라 모집단).
  - 그 인스턴스를 받는다(약 3,900공시 · 7,800콜 · CPU는 파싱뿐).
  - 재무 행은 `SecEdgarFactsSweepTest` 관문 켬 TSV.
  - DB 생성 없음.
- **스윕**: `SecShareClassSweepTest`에 `SHARE_CLASS_EQUITY_OUT`를 더해 행마다 N · 갈래를 TSV로 낸다.
- **잴 것**
  - ① 현 편입 PBR 최신 · PBR_MEDIAN_5Y 창의 바뀐 수 · 새로 비는 수.
  - ② 갈래 분포.
  - ③ 대조군 — 정한 N의 정밀도. 10-K `stmt`가 있는 공시에서 `tag` · `liq` · `carry`가 낸 값과 맞대어 몇 %가 2% 안인지.
  - ④ 신호 없음 대조군을 표본 B 40에서 200으로 넓힌다(최신 10-K 200공시 · 400콜).
  - ⑤ 관문 꺼짐 TSV = main(md5).
  - ⑥ 분모 판정 TSV(`financial_share_class_filing`이 될 것)가 main과 바이트 같음 — 판독기 판 8이 분모를 안 움직이는지.
- **수용**: ③에서 2% 밖이 있으면 그 갈래를 막고 다시 짠다. ①의 새로 비는 창은 목록으로 내고 하나씩 연다.

## 6. 관문 날

- `V70_7`을 V70~V70_6와 번호 순서대로 main 마이그레이션으로 옮긴다.
- 판독기 판 8 · 엔진 판 7이다. 받기가 자본 후보를 받은 뒤 유도 → 백필한다.
- 「재무 행은 있는데 PBR이 없는 관문 켬 미국 현 편입 수 ≤ 드라이런」을 펜스로 둔다.

## 7. 재지 않은 것 · 밖

- **국내**: 신종자본증권(`dart:HybridBonds` · 반증 보고 한화오션 31% 등 — 재지 않음)과 상장 우선주 자본이 남는다.
  - 국내 우선주는 참가형이다. 우선주 몫 자본을 원장이 따로 주는지 안 물었다.
  - 시가총액이 보통주만이라(ADR-0098 사용자 결정 2026-08-30 — 주식수 분모) 같은 어긋남이 있다. 다만 고치는 길이 「안분」이면 지어낸 값이다. 별도 계획이다.
- 과거 편입 · 후보 발행사(모집단 밖) · 공시 보충만으로 쌓인 행.
- 참가형 전환우선주의 경제적 몫(VCTR · MDGL · CME 2025).
