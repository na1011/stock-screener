# 반기 라벨 — 반기만 내는 발행사의 6개월 재무를 지표가 쓴다 (2026-10-23 · 계획 v1)

★ = 내가 원장 · 코드 · DB에서 직접 쟀다. ☆ = 탐색 에이전트가 코드에서 읽었다(`fiscal_period` 소비자 전수).

## 0. 질문과 판정

- 미국 해외 발행사 중 중간 공시를 **반기(6개월)로만** 내는 곳은 FY 행만 남는다 — 어댑터가 6개월 기간을 「라벨을 만들지 않는다 — 버리고 센다」(`SecEdgarFacts.labelOf:730-765` ☆). 그래서 TTM 지표가 FY 기말에 멈춰 **최대 열 달 낡는다.**
- **가짜 한계다.** ① 물은 곳: `.factcache` companyfacts(편입 1,218) · IFRS 182 companyfacts(10-22) ② 대조군: 같은 질의가 분기 제출자에서 3개월 팩트를 받았다 ③ 모집단: 2023 뒤 손익 3개월 팩트 0 · 6개월 팩트 있음 ④ 뿌리: `labelOf`의 기간 띠(연간 350~380 · 분기 80~100 · 111~112일)가 6개월을 안 받는다 · 0콜 재현: `.factcache/<T>.json`의 `NetIncomeLoss` 기간 길이. 기록 `ADR-0056:227` · `progress.md:1655`의 「GLNG — 소스가 안 준다」는 거짓이다(고친다).

## 1. 실측

- ★ 편입 중 반기만 내는 발행사 5: CAMT · CLBT · GLNG · MNDY · NVMI — 저장 행은 2024FY · 2025FY뿐. companyfacts의 6-K 6개월 손익: 2024-06-30 · 2025-06-30(GLNG는 2026-06-30도) — 2025FY(기말 2025-12-31 · 공시 2026-02~03)가 지금 TTM의 끝이다. 2026 상반기(공시 2026-08~09)가 들어오면 TTM 끝이 반년 앞당겨진다.
- ★ 관문 뒤 IFRS 182 중 반기만 33~34(2023 뒤 · HSBC · RIO · MT · AU · DEO · WDS · VOD …) — ADR-0182가 들일 행이다.
- ☆ 소비자:
  - **막는 것**: `IndicatorDataReader:537`의 `FiscalPeriod.valueOf` — 모르는 라벨에 던져 그 종목 지표 전체가 실패한다. 열거형 · `wire-contract.json:53-59` · `WireContractTest:110` · `frontend/src/api/types.ts:568` · `contract.spec.ts:73`.
  - **분기 제출자의 10-Q도 6개월 누계를 싣는다** — H1을 일반 규칙으로 만들면 Q2와 기말이 같은 행이 생겨 `IndicatorContext.latestPerPeriod:196-208`이 하나를 공시일 순으로 조용히 버리고, 펜스 ADR-0124:92(같은 기간 두 라벨)가 발화한다.
  - `deriveFourthQuarters:1888 · 1933`이 FY 아닌 행을 전부 분기로 센다 — H1이 섞이면 Q4 유도가 막힌다.
  - `FinancialMath.seriesOf:268-279` — 분기 연속 검사(75~125일)가 6개월 간격에 실패해 ANNUAL로 떨어진다(안전하지만 H1을 TTM에 못 쓴다). `pointRows:288-303`은 H1을 포함해 잔액 · 주식수가 H1에서 온다 — `roeBasis:311-320` · `balanceAt`(ANNUAL)은 FY 잔액을 읽어 분자 · 분모 짝이 갈린다(ADR-0153).
  - 펜스: ADR-0164:93(FY 아닌 위 넷을 분기로) · ADR-0110:51(`NOT IN ('FY','Q4')`) · ADR-0040:78 · 0059:79 · 0104:47 · 0143:74(`<> 'FY'`).
  - 화면 `FinancialsPanel.vue:45`(라벨 문자열 그대로) · `StockFinancialsResponse:38 · 52`.
  - 국내 반기보고서는 Q2 3개월로 저장한다(누계 미사용) — 전례가 아니다.

## 2. 설계 (관문 뒤 · 꺼진 출력은 `main`과 바이트까지 같다)

1. **라벨 `H1`** = 회계연도 첫 6개월(시작 = FY 시작 · 175~190일). **그 회계연도에 그 발행사의 3개월 분기 라벨이 하나도 없을 때만** 만든다 — 분기 제출자의 10-Q 6개월 누계가 H1이 되면 Q2와 겹친다. 자기 공시의 자기 기간만(`labelPeriods`의 같은 규칙).
2. **스키마**: `ck_fs_period`에 `H1` — 마이그레이션은 설정 뒤에 못 두므로 시험 전용 `V70_2`(관문 직전에 옮긴다). 꺼진 jar는 H1을 안 쓴다.
3. **판독**: `FinancialFigures.FiscalPeriod`에 `H1` · 화면 · 계약 넷을 함께 고친다. `deriveFourthQuarters`는 H1을 분기에서 뺀다.
4. **TTM 기저 `SEMIANNUAL`**(`FinancialMath.seriesOf`): 분기 연속이면 QUARTERLY(지금과 같음), 아니고 가장 늦게 공시된 판이 H1이며 같은 해 FY_prev · 전년 H1이 있으면 SEMIANNUAL — 유량 TTM = FY_prev + H1_cur − H1_prev(지배 · 연결 각각), 잔액은 H1 행. 셋 중 하나라도 없으면 ANNUAL(지금과 같음). YoY 성장(EPS TTM YoY)은 기저 안에서 같은 꼴끼리.
5. **ROE · PBR 짝(ADR-0153)**: SEMIANNUAL 기저의 분모 잔액은 TTM 끝(H1 기말)의 잔액 — 분자 끝과 같은 날.
6. **펜스**: ADR-0124 · 0164 · 0110 술어가 H1을 분기로 안 세게 고친다(`fiscal_period IN ('Q1','Q2','Q3','Q4')`).
7. **기록**: 새 ADR · `ADR-0056:227` · `progress.md` GLNG 줄 · `sec-edgar-financial.md:305`(「6개월 332 버림」).

## 3. 드라이런

1. **0콜 스윕**: `.factcache` · IFRS 182를 꺼짐 · 켬으로 — 꺼짐 = `main`(md5) · 켬은 H1 행만 새로 생기고(편입 5 · IFRS 34) 기존 행 0 변화 · 분기 제출자에게 H1 0.
2. **지표**: 작은 DB(편입 5종목 · 행 · 봉만) 또는 단위 시험 — SEMIANNUAL TTM이 FY_prev + H1_cur − H1_prev인지 · 반기가 없는 해는 ANNUAL로 떨어지는지 · ROE 분자 끝과 분모 날짜가 같은지.

## 4. 순서

반증(두 렌즈) → 드라이런 1 → 구현(설정 뒤) → 드라이런 2 → 커밋 · 배포.
