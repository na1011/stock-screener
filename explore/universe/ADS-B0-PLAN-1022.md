# B0 — 관문 밖 순위가 비율 1 ADS를 들인다 (2026-10-22 · 계획 v1)

`ADS-B-PLAN-1022.md` §5(반증 v1 계획 밖 렌즈)가 가른 몫이다. ★ = 내가 원장·코드·DB에서 직접 쟀다.

## 0. 질문

- 이름 축 `\bDEPOSITARY\b`(`UsMarketCapUniverseAdapter.SECURITY_KIND_WORDS`)가 ADS 보통주를 비율과 무관하게 자른다. 비율이 1이면 ADS 종가 × 보통주 주식수가 옳은 시총이다 — **잘리는 것이 결손이다**(ARM 시총 235.2B가 미국 상위 1,000에 없다).
- 비율 ≠ 1 · 못 읽음은 이미 배포된 A′(`screenDepositary` · 10-22 03:14)가 표지로 뺀다. A′는 이름이 예탁증권인 후보를 이미 범위에 넣고 있다(`nameSaysDepositary`).
- 그래서 **이름 축이 ADS를 안 자르고 A′에 넘기면** 비율 1만 남는다. 판 비율 · V70 · 관문이 필요 없다.

## 1. 실측 ★

- 이름 축 술어의 소비자(`grep nonCommonName|excludedByName|NON_COMMON_NAME`): `rank()` 안 세 자리(목록 후보 `:433` · 숨은 상장 `:734` · `acceptHidden` `:797`) · `UsPastRanking:123 · :176`(관문 켬 전용 · A′ 없음) · 시험 `ListingEventsDryRunLiveTest:99`.
- 활성 미국 이름에 `DEPOSITARY` 445: ADS(`DepositaryRatio.isDepositary`) 253 · 비ADS인데 다른 낱말(`PREFERRED` …)에도 걸림 185 · **`DEPOSITARY`에만 걸리는 비ADS 7**(`FCNCP` · `USB.PRH` · `MNSBP` · `GS.PRA` · `WAFDP` · `CDZIP` — 우선주 예탁증권 · `IRS` — Global Depositary Shares). 이 7은 계속 잘려야 한다.
- 비율 1 · USD 보고 · 10-20 종가 기준 1,000위 문턱(약 $5.0B) 위: **ARM 235.2B · SE 51.3B · NICE 6.9B(join 비율 없음 — 표지 이력 「each representing one Ordinary Share」) · BLTE 6.1B.** 비율 1이지만 비USD: ZTO · NIO(통화 게이트가 뺀다). `TEVA`(비율 1)는 이미 편입.
- 77 CIK 이력에서 「최신 비율 1인데 과거 ≠ 1」 발행사 0(반증자 0콜 재현) — 비율 1 ADS는 지표 이력에서도 단위가 같다.
- 비율 1 ADS의 지표 소비자: `sharesOf` · `endpointShares` · 배당 총액 20% 게이트(EPS 함의 보통주와 비 = 1) · 순위 가드 훅 — k = 1이면 단위가 같아 어느 것도 안 움직인다.

## 2. 고침

1. `rank()`의 이름 축(세 자리)만: `DEPOSITARY` 낱말 하나에만 걸리고 `DepositaryRatio.isDepositary(name)`가 참이면 자르지 않는다 — 다른 낱말(`PREFERRED` · `WARRANTS` …)이 함께 걸리면 지금처럼 자른다.
2. `UsPastRanking`과 `nonCommonName`(상장 판정 공용)은 그대로 — 관문 켬의 과거 명부는 A′를 안 타므로 ADS를 들이면 비율 ≠ 1이 섞인다(B가 닫는다).
3. 시험: 이름 축 꼴마다 — ARM 꼴(ADS · 비율 1 표지) 남음 · HDB 꼴 이름 ADS · 비율 3 → A′가 뺌 · `GS.PRA` 꼴 「Depositary shares」 단독 → 이름 축이 자름 · `USB.PRH` 꼴(우선주 낱말 축약 `Prfrd.`) → 자름 · `IRS` 꼴 GDS → 자름 · ADS + `WARRANTS` → 자름. 대조군: 고치기 전 코드에서 ARM 꼴이 붉다.
4. ADR-0099 §ADR 비율 오차 문단과 `SECURITY_KIND_WORDS` javadoc을 고친다(「이름에 `DEPOSITARY`가 든 비율 1 ADS는 관문 밖에서 계속 빠진다」가 거짓이 된다).

## 3. 드라이런

- 10-22 3시 A′ 드라이런과 같은 실제 경로: 운영 DB 읽기 전용 + Alpaca 봉 + SEC 표지로 `rank(US_TOP1000, 오늘)`(쓰기 0)를 고치기 전·후로 부른다. 기대: 든다 ARM · SE · NICE · BLTE(문턱 근처 둘은 그날 종가로 갈릴 수 있다) · 빠진다 그만큼의 문턱 종목 · 「예탁증권 표지 판정」의 표지 수가 는다(이름 ADS가 범위에 들어오는 수) · 뺌에 비율 ≠ 1 ADS(ONC 13 · EDU 10 · SIMO 4 …).
- 10-24 수용 기대(progress 항목 3)를 그 드라이런 출력으로 다시 뜬다.

## 4. 순서

드라이런 → 구현 → 시험 → 드라이런 다시(구현 경로) → 커밋 → 배포(10-24 토 20:00 리밸런싱 전 · 크론 밖).
