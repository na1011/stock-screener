---
id: ADR-0183
status: 채택됨
date: 2026-10-23
applies_to:
  - "src/main/java/com/stockscreener/pipeline/datasource/us/listing/DepositaryRatioJudge.java"
  - "src/main/java/com/stockscreener/pipeline/datasource/us/DepositaryCover*.java"
  - "src/main/java/com/stockscreener/pipeline/collector/ShareBasisDeriver.java"
  - "src/main/java/com/stockscreener/indicator/ShareBasis.java"
  - "src/test/resources/db/testmigration/V70_1__depositary_cover.sql"
---

# ADR-0183. 미국 ADS는 공시마다 표지 비율로 행 배수 1/k를 짓는다(관문 뒤)

## 결정

미국 예탁증권(ADS) 줄 발행사는 **저장 주식수를 보통주 그대로 두고**, 공시마다 표지의 「1 ADS = k주」로 그 행의
`share_basis_multiple` = 1/k · 상태 `DEPOSITARY`를 짓는다 — `stockscreener.gate.us` 뒤에서. k는 공시마다 한 번 정해
`sec_depositary_ratio`에 두고 다시 안 짓는다. 관문 켬 순위는 판 비율이 선 ADS를 두 평면에 들인다.

설계 · 실측 · 반증의 정본은 탐색 브랜치 `explore/s-vintage-coherence`의 `explore/universe/ADS-B2-PLAN-1022.md` v2.1이다(v1 반증 「다시
짠다」 → v2 두 렌즈 「고쳐서 간다」 · 절 「반증 v2 판정 표」 · 절 「드라이런 결과」).

## 왜

- 시총 · 지표가 **ADS 종가 · 거래량 · Alpaca 배당에 보통주 주식수**를 곱해 k배 틀렸고, 관문 밖은 그것을 **빼서** 막았다(이름 축 · A′) —
  들어와야 할 USD 보고 ADS(ONC · EDU · SIMO · TAL · IFRS의 이름이 평범한 ADS)가 두 평면 모두 빠진 결손이다.
- 「ADS 비율은 소스가 안 준다」(ADR-0162 · ADR-0126 C의 전제)는 거짓이었다 — 공시 표지 증권 이름이 판마다 준다(77 CIK 2019 뒤 747공시 중
  자기 글 552 · 태그 6).
- **저장 단위를 ADS로 바꾸지 않는 이유**(v1이 무너진 자리): EPS가 보통주당이라 EPS 함의와 맞대는 소비자(배당 20% 게이트 · 순위 가드 훅 ·
  분모 판정)가 깨진다. `share_basis_multiple`은 이미 가격과 만나는 자리(지표 주식수 · 순위)에만 곱해지고 그 소비자들에는 안 곱해진다 —
  원하는 경계가 코드에 있었다.

## 규칙

- MUST: k 판정 순서 — 자기 표지(글 1순위 · 태그 · 2% 넘게 갈리면 미정) → 이웃 자기 표지에서 잇기(**사이에 ADS 원장 사건이 없고 보통주가
  1.5배 안일 때만** — BABA 2019 보통주 8:1 · ADS 사건 없음) → k ≥ 2인데 그 판 주식수가 앞 보통주 판의 1/k ±5%면 그 판은 ADS 단위(k = 1 ·
  CMMB 10-Q). 이름 줄이 없는 표지(10-K · 10-Q)는 「예탁 아님」이 아니라 잇기 대상이다.
- MUST: 원장 이력(2019-01-01) 앞 공시는 잇지 않는다 — Alpaca 분할 이력이 그 무렵부터다(대조군 NFLX 2015-07 7:1도 0건). 그 앞의 「사건
  없음」은 소스가 사건을 안 준 것이다.
- MUST: 정한 k는 다시 안 짓는다(미정만 채운다) — 기저 판정이 매 평일 전 행을 다시 짓는 동안 뒤에 온 표지 · 사건이 지난 판을 소급해
  뒤집지 않게.
- MUST: `ShareBasis.of`는 모르는 상태에 던진다 — 「모르는 값 = OK」였다면 `DEPOSITARY`가 지표에서 조용히 무시되어(지표는 SHIFTED만
  곱했다) 순위와 k배로 갈렸다.
- MUST: 클래스 축의 연속성 비교 · 줄 주식수 옮기기는 **보통주 계수**(ADS 사건 배수 × k_뒤/k_앞), 이미 1/k를 곱한 행 값을 순위 기준일로
  옮기는 자리는 ADS 계수다. 그 k가 클래스 판정 전에 있어야 해서 공시마다 k를 정하는 단계가 화해 입력 유도의 맨 앞이다.
- MUST: 배당 총액(ADS당 배당 × 주식수)은 20% 게이트(보통주끼리)를 지난 주식수에 그 판의 배수를 곱한다.
- MUST: 관문 켬 순위 — 판 비율이 선 종목은 표지 판정(A′)에 다시 안 넘긴다. 판 비율이 없는 예탁증권은 관측 평면에서 A′가 가리고 과거
  평면에서 뺀다(닫힌 쪽).
- MUST: 20-F · 40-F 클래스 앵커는 10-K와 같게 판정한다(Q4 유도만 없음) — 숨기던 근거 「ADS 비율을 원장이 안 준다」가 거짓이고 ADS 아닌
  20-F · 40-F 보통주(TRI · NU)에는 처음부터 안 서던 규칙이다. 클래스 판독기는 IFRS 클래스 축과 IFRS EPS · 가중평균 · 분자를 읽는다.

## 버린 대안

- **저장 단위 ADS(v1)**: 위 「왜」.
- **종목 상수 비율**: 비율이 창 안에서 바뀐 발행사 21자리(UXIN 3 → 30 · AKTX 100 → 2,000 → 80,000).
- **계단 숨김(1.5배 넘으면 숨김)**: 실제 희석까지 숨긴다(AKTX · AUTL · ZLAB).
- **매일 다시 짓는 k**: 소급 판정(v2 반증).
- **순위 시점에만 표지 읽기(A′ 확장)**: 지표 · 과거 평면 · 배당이 안 고쳐진다.

## 결과

- 드라이런(0콜 + Alpaca 9콜): 747공시 미정 93 — 최신 판 미정 중 순위 문턱($5.06B · 10-21)에 닿는 것 0(상한 TIGR 11.8B → k 15로 약
  0.8B). B 판독으로 문턱을 넘는 USD ADS: B0 몫(ARM · TEVA · NICE · BLTE) 밖에 ONC 43.9B · EDU 9.2B · SIMO 7.5B · TAL 6.1B. AKTX
  약 $17.1M(지금 UNRESOLVED 숨김 · 2026-10-22 사용자 결정 「B로 제값 표시」 · ADR-0162).
- 클래스 축(받아 둔 인스턴스 5): TRI · VALE · AZN · NU 숨김 → 보임, BIP는 LP 단위 가중평균이 표준 태그에 없어 숨김. TRI P-c 합에 우선주
  6M이 섞여 1.3% 크다(표지 전 클래스 합이라는 기존 규칙).
- 관문 꺼짐 순위 주식수: 이 결정 전(`487c65dd`)과 운영 DB 읽기 전용 스냅숏 266,247행 md5 같음.
- 클래스 표지 · 비율 변경을 함께 가진 ADS 여섯(ICLK · ZLAB · UXIN · YI · ZH · AMRN)에서만 보통주 계수가 ADS 계수와 갈린다 — 지금 순위 ·
  편입에 닿는 칸 0.

## 검증

- `./gradlew test --tests '*DepositaryRatioJudgeTest*' --tests '*DepositaryBasisDeriveTest*' --tests '*DepositaryBasisTest*' --tests '*DepositaryDividendSharesTest*' --tests '*UsMarketCapUniverseAdapterTest*' --tests '*CandidateSplitJobTest*'`
- 관문 꺼짐 동일성: `RANK_SNAPSHOT_DB=stock_screener ./gradlew test --tests '*RankShareCountSnapshotTest*'` 출력을 이 결정 전 커밋의 같은 실행과 대조한다.
