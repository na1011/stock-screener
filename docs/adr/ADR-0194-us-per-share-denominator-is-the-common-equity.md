---
id: ADR-0194
status: 채택됨
date: 2026-10-26
applies_to:
  - "src/main/java/com/stockscreener/pipeline/datasource/us/shareclass/CommonEquity.java"
  - "src/main/java/com/stockscreener/pipeline/datasource/us/shareclass/CommonEquityRows.java"
  - "src/main/java/com/stockscreener/pipeline/datasource/us/shareclass/ClassFactReader.java"
  - "src/main/java/com/stockscreener/pipeline/datasource/us/shareclass/ClassFacts.java"
  - "src/main/java/com/stockscreener/pipeline/datasource/us/shareclass/IssuerFacts.java"
  - "src/main/java/com/stockscreener/pipeline/collector/ShareClassDeriver.java"
  - "src/main/java/com/stockscreener/indicator/calculator/FinancialMath.java"
  - "src/main/java/com/stockscreener/indicator/calculator/Pbr*.java"
  - "src/main/java/com/stockscreener/indicator/calculator/NcavToMarketCalculator.java"
---

# ADR-0194. 미국 PBR의 자본을 보통주 몫으로 한다 — 지배 자본 − 비보통 청구권(관문 뒤)

## 결정

관문 켬(`stockscreener.gate.us`)에서 `PBR` · `PBR_MEDIAN_5Y`(의존 `PBR_DISCOUNT_5Y` · `PBR_PCTL`)의 분모를 지배 자본에서 **보통주 자본** = 지배 자본 − 비보통 청구권 N으로 바꾼다. `NCAV_TO_MARKET`은 유동자산에서 총부채와 함께 **보통주보다 앞서는 청구권**(영구자본 우선주 · 우선 단위 · 메자닌)을 뺀다. 분자가 둘 다 보통주 시가총액이라서다.

1. **(공시, 대차대조표 날짜)마다 정한다** — 클래스 축 엔진(`CommonEquity`)이 공시 인스턴스 자본 재료(판독기 판 8)로 정한다. 사다리는 첫 번째로 서는 것이다.
   - `lp` GP 자본 + 우선 단위 + 보통 아닌 LP 클래스(LP 단위 가중평균이 안 실린 구성원)
   - `conv` 전환 · 참가형(전환 조건 · 선순위 조건 없음 · 우선 배당 없음) → 못 정함
   - `zero` 액면 값 또는 자본변동표 우선주 성분 명시 0 · 주식수 없음 · 배당 없음
   - `stmt` 자본변동표 우선주 성분(액면 지문이 아닐 때)
   - `tag` · `sertag` 장부가 태그(축 없음 → 중복을 걷은 시리즈 합)
   - `liq` 청산가(유효성 · 모회사 증거 · 메자닌 아님)
   - `mezz` · `zero` · `subsid` · `retired` · `none` — 뺄 것 0
   - `nominal` 경제 조건이 발행사 이력 어디에도 없는 우선주(액면 그대로)
   - `carry` 우선주 주식수가 같은 다른 날짜의 값 · `immat` 이웃 값이 보통주 자본의 2% 안
   - 그 밖은 못 정함(`open` · `divonly` · `liqconf` · `nokey`)
2. **받기** — 우선주 · LP 신호(`CommonEquity.SIGNAL` — 원장에서 센 표준 개념 목록)가 2020년 뒤 공시에 0 아닌 값으로 있는 발행사의 행 폼 공시 인스턴스(`IssuerTimeline.equityCandidates`).
3. **행에 붙이기**(`CommonEquityRows`) — 행 공시 · 행 끝 날짜 → 같은 날짜를 정한 앞 공시 → 신호 없는 발행사의 companyfacts 공시는 `none`, 그 밖은 `nokey`.
4. **저장** — 관문 시험 마이그레이션 `V70_7`이 `financial_common_income`에 `non_common` · `ncav_non_common` · `equity_branch`를 더한다. 분자와 같은 행 · 같은 `report_code` 맞춤으로 유도가 한 트랜잭션에 넣는다. 엔진 판 7.
5. **읽기** — `CommonIncomeRead` 줄 없음은 「이 규칙으로 안 읽음」(지금 경로), 갈래가 못 정함이면 미표시, N = 0이면 지금 기저 그대로, N ≠ 0이면 지배 자본 − N(지배 자본이 없으면 미표시).

관문 꺼짐은 코드 경로가 그대로다(지표는 클래스 축 줄이 있을 때만 새 칸을 읽는다). 설계 · 실측 · 반증의 정본은 탐색 브랜치 `explore/s-vintage-coherence`의 `explore/financial-sync/PERSHARE-DEN-PLAN-1026.md`(v1 → 두 렌즈 「고쳐서 간다」 → v1.1)다.

## 왜

- **가짜 한계.** 미국 GAAP `StockholdersEquity`는 정의상 우선주를, LP `PartnersCapital`은 GP 자본 · 우선 단위를 품는다. 원장은 그 몫을 공시 인스턴스 자본변동표 성분 · 장부가 · 청산가 태그로 정직하게 준다.
- 운영(관문 꺼짐) PBR 예(2026-10-23):
  - VST 8.62 — 우선주 2,476M을 빼면 약 1.8배.
  - C 0.97 → 약 1.07.
  - BA 24.3 — 보통주 자본이 약 3.5억 달러라 수백 배.
  - CQP — 분모 754M 대 보통 단위 몫 3,692M(GP −2,938M).
- **companyfacts만으로는 안 된다.**
  - 우선주를 축 맥락에만 싣는 발행사: MS 9,750M · STT · NTRS · DUK.
  - `PreferredStockValue`가 정의대로 액면만인 발행사: AXP · PNC · MCHP · COF 0.
  - 축 없는 값이 자회사(비지배) 우선주인 발행사: ES.

## 규칙

- MUST: 첫 칸은 공시 인스턴스 자본변동표의 우선주 성분이다. companyfacts 축 없는 태그를 정본으로 쓰지 않는다(위 「왜」).
- MUST: 성분은 같은 공시의 다른 우선주 증거(주식수 · 값 · 청산가 · 배당)가 받칠 때만 받는다. ZETA는 우선주가 없는데 APIC 열을 `PreferredStockIncludingAdditionalPaidInCapitalMember`로 실었다(1,997M · 자본 926M보다 크다).
- MUST: 같은 날짜에 다른 공시가 우선주 주식수 > 0을 실었으면, 그 날짜의 「뺄 것 0」은 받지 않는다. 주식수를 실은 공시의 값을 옮기고, 없으면 못 정함이다. 우선주를 APIC에 싣는 발행사(FLR)는 성분이 늘 0이라, 주식수가 없는 뒤 공시 비교열에서 「명시 0」이 나온다.
- MUST: 같은 (개념, 맥락, 기간, 값) 팩트는 한 번만 센다. STT 시리즈 합이 7,118 대 3,559, NTRS가 1,378 대 885였다.
- MUST NOT: 액면을 장부가로 쓰지 않는다. 지문은 둘이다.
  - 액면 × 주식수 ≈ 값(`decimals` 반올림 폭 안).
  - 그 기간 우선 배당의 하한이 값 상한의 25%를 넘음.
- MUST NOT: 청산가를 첫 칸으로 쓰지 않는다. FCNCA 10-Q 시리즈 청산가 합 3,540M이 성분 1,765M을 덮었다.
  - 청산가는 다음을 모두 넘을 때만 받는다.
    - 그 기간 배당 이상 · 액면 × 주식수 이상(NTRS 시리즈 청산가 0.125M은 주당 금액을 총액 태그에 실은 꼴이다).
    - 연 배당이 그 값의 25% 이하(FLR 2022-06 시리즈 청산가 96M 대 반기 배당 20M — 60만 주 × 1,000달러 우선주).
    - 부채 · 자본 합계 이하(MS 2024-09 주당 × 주식수 곱 9.8 × 10^15).
  - 주당 청산가 × 주식수는 시리즈 장부가를 거부하지 못한다 — 상환된 시리즈의 남은 주식수까지 곱한다(STT 2024-06 곱 3,500M 대 시리즈 장부가 = 성분 2,468M).
  - 메자닌 간극(합계 − 부채 − 비지배 포함 자본)의 절반 이상이면 자본 밖이다(CELH 1,760M).
- MUST NOT: 참가 · 전환형을 자본 안분이나 주식수 더하기로 메우지 않는다. 앞은 원장이 안 주는 비율이고, 뒤는 시가총액 정의(ADR-0098 사용자 결정)를 바꾼다. 못 정함이다.
- MUST NOT: 확장 태그(`ms:PreferredStockCarryingValue`)를 이름으로 읽지 않는다(2026-10-25 사용자 기준 — 종목 특정). 10-Q가 잔액을 안 실으면 우선주 주식수가 같은 날짜의 값을 잇는다. 우선주 장부가는 발행 · 상환 · 전환으로만 움직인다.
- MUST NOT: 「신호 없음 = 0」을 companyfacts가 덮지 않는 행(보충 공시 · 404)에 쓰지 않는다 — `nokey`(못 정함)다.
- MUST NOT: 지배 자본이 없는 행에 총자본 − N을 내지 않는다. 비지배가 섞인다.
- MUST: PBR 5년 중앙값은 못 정한 봉만 표본에서 뺀다(자본잠식 봉과 같다 · 최소 표본 `MIN_SAMPLES`가 창을 지킨다). 창 전체를 지우면 못 정한 행 하나가 5년을 지운다.
  - 계획 v1.1 5절의 「하나라도 못 정함이면 미표시」를 구현에서 바꿨다.
- MUST: NCAV는 메자닌을 재무 행의 「자산 − 부채 − 자본」 간극으로 뺀다(원장 태그와 같은 값 — CELH · MSTR · BOX · MP).
  - 총부채가 「자산 − 자본」 폴백이면 이미 들어 있어 다시 안 뺀다.
  - GP 자본은 보통주보다 앞서는 청구권이 아니라 NCAV에서는 안 뺀다.
- MUST NOT: `total_equity_controlling`의 뜻을 바꾸지 않는다. N을 따로 싣고 읽을 때 뺀다 — `ROE`(실체 단위 짝) · `DEBT_RATIO` · `ALTMAN_Z''`가 그 뜻을 읽는다.

## 버린 대안

| 대안 | 근거(실측) |
|---|---|
| companyfacts 축 없는 태그만 | MS 9,750M · STT · NTRS · DUK가 「우선주 없음」 · AXP · PNC 액면 0 |
| `PreferredStockValue` 하나 | 정의가 par or stated다 — 액면 발행사 9곳 이상 |
| 청산가를 첫 칸으로 | FCNCA 시리즈 청산가 겹침 · CELH · NI는 청산가가 메자닌 · 날짜만 남은 옛 시리즈 값 |
| LP 계정(`LimitedPartnersCapitalAccount`)을 보통 몫으로 | EPD: 자기 단위 · AOCI를 빼 30,700 대 `PartnersCapital` 29,739 · ET 82M |
| 우선 배당만 있으면 못 정함 | AEE · ES(배당 = 비지배 손익) · COHR · SRE · APLD(기중 상환 · 전환)의 정직한 PBR을 버린다 |
| 원장 상한(액면 × 주식수)으로 정함 | 액면은 장부가 상한이 아니다 — MDGL · RKLB가 거짓 정함이 됐다 |
| 메자닌 우선주를 `TemporaryEquity*` 태그로만 | CELH는 그 태그가 없다. 재무 행 간극이 같은 값을 모든 발행사에 준다 |
| PBR 5년 중앙값 창 전체 미표시 | 못 정한 행 하나가 5년을 지운다(반증 안 7) |

## 결과

드라이런(0콜 엔진 · 2026-10-26)의 재료는 셋이다. 인스턴스는 SEC Archives에서 약 3,700콜로 받았다. DB 생성 없음.
- **팩트 캐시**: 현 편입 990.
- **공시 인스턴스 2,083**:
  - 신호 발행사 최신 10-K · 10-Q 398
  - 신호 발행사의 2021~2025년 연도마다 하나 1,284
  - 새 신호 발행사 최신 203
  - 비신호 층화 199
- **운영 SELECT**: 읽기만.

| 지표 | 수 |
|---|---|
| 신호 발행사(넓힌 술어 `CommonEquity.SIGNAL`) | 현 편입 캐시 990 중 282(메자닌만 있는 82 제외) |
| 비신호 층화 표집(연도 × 무작위 · 199공시)에서 우선주 · GP | **0**(`none` 104 · `zero` 95) |
| 정함 대조군 — `stmt` 공시에서 성분을 지웠을 때 나머지 칸 | 422 중 정함 392 · 그중 보통주 자본 오차 2% 밖 **0** · 못 정함 30 |
| 신호 표집 1,284공시의 못 정함 | 81(6.3%) — 연도마다 6~7% |
| 최신 대차대조표 N ≠ 0(278발행사) | 82 · PBR 1% 넘게 바뀜 72 · 10% 넘음 28 · 보통주 자본 음수 1(EQH — 지배 자본부터 음수) |
| 최신이 못 정함이라 PBR이 새로 빔 | 19(6.8%) — 전환 · 참가형 6(COGT · IMVT · LITE · MDGL · MRVL · ORKA) · 배당만 6(BA 10-Q · ES · LEN · TXNM · VG · WDC) · 값 없는 주식수 7(AM · FTAI · HGTY · JEF · RKLB · SW · VCTR) |
| 자바 엔진 대 파이썬 시안(같은 2,081 키) | 2,072 같음 · 9 다름(자바가 비교열 날짜까지 판정해 이어받기 · 미미한 이웃의 원천이 많다 — BDX · GE · PG · HL) |
| 관문 꺼짐 0콜 재무 스윕(`.factcache` 1,218) | HEAD와 바이트 같음(md5 `121a06ab…` · 87,771줄 · ADR-0193과 같은 값) |

- 큰 변화(최신 PBR 배수): GSAT 2.04 · VST 1.82 · ARES 1.58 · SMCI 1.41 · BRKR 1.41 · MCHP 1.30 · ALB 1.28 · IVZ 1.25 · OXY 1.25 · PAA 1.23 · NRG 1.16 · VOYA 1.15 · 대형 · 지방은행 1.06~1.15 · ET 1.11.
- 드라이런이 잡아 고친 것: 시리즈 합 중복(STT · NTRS) · 주당 금액을 총액에 실은 청산가(NTRS) · 성분 명시 0(NI) · 증거 없는 성분(ZETA) · 연 배당과 안 맞는 청산가(FLR) · 곱의 거부권(STT) · 곱의 단위(MS) · 비교열의 0(FLR) · 국내 관문 켬 행이 「못 정함」으로 비던 것(`CommonIncomeRead.equityRead`).
- 재지 않았다:
  - 5년 창 전 공시의 결과 — 표집은 연도마다 하나다. 관문 날 받기가 전 행 폼 공시를 받는다(신호 발행사 약 6,200공시 · 앵커 · 분모 · 분자 후보와 겹치는 것은 이미 받는다).
  - 관문 켬 재무 행 기저(ADR-0188 LP 자본 · 지배 null) — 위 배수의 분모는 인스턴스 축 없는 지배 자본이다.
  - 과거 편입 · 후보 발행사.
  - IFRS 우선주 사다리(현 편입 캐시 신호 BNT 1곳 · 그 행은 못 정함).

## 검증

- `./gradlew test --tests '*CommonEquityTest' --tests '*CommonEquityBasisTest'`
- 대조군: 이어받기 · 메자닌 · 성분 명시 0 · 전환형 · 액면 지문 · LP 클래스 · 자회사 배당 일곱을 하나씩 끄면 각자의 시험 하나가 붉다(2026-10-26 실제로 바꿔 확인).
- 0콜 스윕: `EQUITY_INST_DIRS=<인스턴스 폴더> EQUITY_OUT=<tsv> ./gradlew test --tests '*CommonEquitySweepTest' --rerun-tasks`
- 이 결정은 운영 자동 검증 대상이 아니다. 관문 뒤 경로다.
