---
id: ADR-0127
status: 채택됨
date: 2026-09-06
applies_to:
  - "src/main/java/**/indicator/calculator/AltmanZ*.java"
  - "src/main/java/**/indicator/IndicatorUnavailabilityConfig.java"
---

# ADR-0127. 알트만 수정모델은 Z''로 두고, 두 알트만의 EBIT를 하나로 맞춘다

## 결정

`ALTMAN_Z_DOUBLE_PRIME`(Altman Z''-Score, 1995)을 지표로 더한다. 원본 `ALTMAN_Z`는 남기고
**두 지표의 EBIT를 `FinancialMath.operatingIncomeOf()`로 통일한다.** 프리셋에는 넣지 않는다 —
조건 빌더에서 고를 수 있는 것까지가 이번 범위다.

**사용자가 고른 문장**(2026-09-06): *"나는 하나의 지표로 비제조업+제조업을 파악하고 싶은데
그건 안돼? 비상장은 필요없어. 스크리닝 후 매수가 목적이니까."* · 프리셋은 *"조건 빌더까지만"* ·
EBIT는 *"둘 다 유도 경로로"* · PIT 저장본은 *"이번에 재적재한다"*.

**그에 대한 내 해석**(사용자 문장이 아니다): 그 요구가 정확히 Z''다. 알트만이 X5(매출/총자산)를
뺀 이유가 **업종 간 자산회전율 차이 제거**이고, 그래서 Z''는 제조업과 비제조업을 한 모델로
본다. 시가총액을 장부가로 바꾼 것은 비상장까지 덮으려던 것이라 우리 유니버스에서는
**부수적**이며, 그래서 Z'(비상장 제조업 전용)는 만들지 않았다.

```
Z'' = 6.56·(운전자본/총자산) + 3.26·(이익잉여금/총자산)
    + 6.72·(EBIT/총자산)     + 1.05·(자기자본 장부가/총부채)

안전 ≥ 2.60 · 회색지대 1.10~2.60 · 부실 위험 < 1.10
```

계수 넷과 구간 둘은 외부 출처로 따로 확인했다(creditguru의 비제조업·신흥시장 Z'' 문서 ·
Wikipedia "Altman Z-score" · wallstreetprep). 같은 출처가 이 모델을 **비제조업의 상장·비상장
모두에** 쓴다고 적는다.

## 규칙

- **MUST NOT: 두 알트만의 값을 같은 자로 비교하지 않는다.** 계수도 경계도 다르다
  (원본 3.0/1.8 · 수정모델 2.6/1.1). 이름이 비슷해서 생기는 오독이고, 각 지표의 `bands`가
  그 경계를 따로 들고 있다.
- **MUST: 업종 정의역을 `AltmanZCalculator.isInapplicable`로 판정한다.** 집합을 새 계산기로
  복사하지 않는다 — ADR-0092 §결과가 이미 그 집합이 계산기와 판정불가 설정 **둘**에 있다는 것을
  대가로 적어 뒀고, 세 번째 사본은 넓히는 날 조용히 갈린다.
- **MUST: 두 알트만의 EBIT 원천을 함께 바꾼다.** 하나만 유도 경로로 옮기면 같은 종목에서
  한 카드는 값이 있고 다른 카드는 없는데, **그 이유가 화면에 드러나지 않는다.**
- **MUST NOT: 자기자본을 지배주주지분으로 바꾸지 않는다** — 재료가 없다. `total_equity`는
  비지배지분을 포함한 **자본총계**이고(`SecEdgarColumn.TOTAL_EQUITY` javadoc이 KR의 `자본총계`와
  맞춘 이유를 적었다), 지배주주지분은 KR(`ifrs-full_EquityAttributableToOwnersOfParent`)도
  US(`StockholdersEquity`)도 **수집하지 않는다.** 바꾸려면 새 컬럼을 받는 별도 결정이다.
- **MUST: 값을 펼치기 전에 `FinancialMath.SCALE`로 접는다.** `spreadOverTradeDates`는 반올림을
  하지 않는다. 저장 컬럼이 `numeric(24,8)`이라 DB 값은 어차피 같지만, `IndicatorRunner.computeAt`이
  내는 **메모리 값이 시점 재현 저장본에 접히지 않은 채 실려** 두 경로가 갈린다.

## 검증

```premise 알트만 수정모델이 원본보다 좁지 않다
# 펜스 본문은 bash로 실행된다 — 여기 주석은 `#`다. `--`로 적으면 bash가 옵션으로 읽어
# rc=2로 죽고, 그 전제는 영영 무판정이 된다(실측).
#
# 모집단이 활성 유니버스로 좁혀져 있다. 유니버스 밖 종목은 이탈 前 일자의 ALTMAN_Z를
# 그대로 들고 있는데(ADR-0080) 지표 계산은 편입 종목만 도므로, 넓게 세면 리밸런싱이
# 종목을 뺄 때마다 이 펜스가 운다. 실측으로 2026-09-06에 그 형태로 8종목이 잡혔고
# 전부 전날 리밸런싱에서 빠진 종목이었다.
psql -d stock_screener -At -c "WITH d AS (
  SELECT max(trade_date) t FROM indicator_trade_date),
member AS (SELECT DISTINCT um.stock_id FROM universe_membership um
  JOIN universe u ON u.code = um.universe_code AND u.is_active
  WHERE um.effective_to IS NULL),
iv AS (SELECT iv.stock_id, iv.indicator_code FROM indicator_value iv
       CROSS JOIN d JOIN member m ON m.stock_id = iv.stock_id
       WHERE iv.trade_date = d.t AND iv.indicator_code IN ('ALTMAN_Z','ALTMAN_Z_DOUBLE_PRIME'))
SELECT CASE
  WHEN (SELECT count(*) FROM iv WHERE indicator_code='ALTMAN_Z') = 0
    THEN '잴 것이 없었다 - 최신 거래일에 편입 종목의 ALTMAN_Z가 한 행도 없다'
  WHEN (SELECT count(*) FROM iv WHERE indicator_code='ALTMAN_Z_DOUBLE_PRIME') = 0
    THEN '잴 것이 없었다 - 최신 거래일에 편입 종목의 ALTMAN_Z_DOUBLE_PRIME이 한 행도 없다'
  WHEN count(*) = 0 THEN 'OK 원본이 내는 편입 종목을 수정모델이 전부 낸다 ('
    || (SELECT count(*) FROM iv WHERE indicator_code='ALTMAN_Z') || ' -> '
    || (SELECT count(*) FROM iv WHERE indicator_code='ALTMAN_Z_DOUBLE_PRIME') || ')'
  ELSE '수정모델만 값이 없는 편입 종목이 ' || count(*) || '개다 - 장부가로 바꾼 판단의 전제가 깨졌다'
  END
FROM (SELECT stock_id FROM iv WHERE indicator_code='ALTMAN_Z'
      EXCEPT SELECT stock_id FROM iv WHERE indicator_code='ALTMAN_Z_DOUBLE_PRIME') gone"
```

이 전제가 이 결정의 근거다. 시가총액을 장부가로 바꾸면서 **잃는 종목이 0**이라는 것이 성립해야
교체가 정당하고, 뒤집히면 `total_equity` 커버리지가 무너졌다는 뜻이다.

계산 자체는 `SwingIndicatorsTest`의 `AltmanZ`·`AltmanZDoublePrime` 두 중첩 클래스가,
업종 사유 노출은 `ReadApiTest.explainsAltmanZExclusionForInsurers`가 **두 코드 모두**에 대해
단언한다.

```bash
./gradlew test --tests "*SwingIndicatorsTest*" --tests "*ReadApiTest*"
```

## 배경

지표가 원본 Z 하나뿐이었는데, 그 모델은 제조업 표본에서 나왔고 X5가 업종 자본집약도를 그대로
반영한다. 미국 1,000종목에 서비스·소프트웨어 비중이 커서 그 갈래가 자주 걸린다.

**미국 커버리지가 절반인 원인은 X1이 아니다.** 업종으로 거른 828종목 중 값을 못 내는 것의
내역(2026-09-03 실측 · 중복 포함): 총자산·총부채 결손 또는 ≤0이 **216** · 영업이익 TTM 결손
**111** · 이익잉여금 24 · 분기 재무 없음 15 · **유동/비유동 결손 11** · 자기자본 7.
유동항목은 작다. Z''도 X1을 그대로 쓰지만 그것이 병목이 아니다.

**여는 길이 재무 원자료 쪽이라고 적었는데, 그것이 틀렸다**(2026-09-07). 최대 병목인 216은
`us-gaap:Liabilities`를 안 내는 회사들이고, 대조군 60종목에서 그 태그로 값이 잡힌 종목이
**0**이라 **재적재로는 영영 안 열린다.** 열린 길은 지표 계층이었다 — `DebtRatioCalculator`가
이미 갖고 있던 `자산총계 − 자기자본` 유도를 `FinancialMath.totalLiabilitiesOf`로 올려 넷이
공유하게 했다. 그때까지 **같은 종목에서 부채비율 카드만 값이 나가고 알트만 둘·NCAV는 비었고,
그 이유가 화면에 없었다.**

## 결과

**커버리지**(활성 유니버스 · 2026-09-03 · 최신 지표 거래일 기준 실측)

| 유니버스 | 모집단 | 전 `ALTMAN_Z` | 후 `ALTMAN_Z` | `ALTMAN_Z_DOUBLE_PRIME` |
|---|---|---|---|---|
| `KOSPI_TOP200` | 200 | 169 | 169 | **169** |
| `KOSDAQ_TOP150` | 150 | 134 | 134 | **140** |
| `US_TOP1000` | 1000 | 495 | **510** | **522** |

배포·백필 뒤 실제로 잰 값이다. `ALTMAN_Z`의 미국 510은 착수 전 예측과 정확히 맞았고,
수정모델은 KOSPI·US에서 예측보다 **각각 하나 적다.** 원인은 계산기가 아니라 **최신
거래일에 시세 봉이 없는 편입 종목**이다 — 착수 전 예측이 재무 조건만 보고 봉 존재를 안
봤다. 그런 종목이 활성 유니버스에 11개 있고(KOSPI 3 · KOSDAQ 5 · US 3) 그들은 두 알트만
어느 쪽도 값을 못 낸다. **이 결정이 만든 상태가 아니라 신규 편입 종목의 이력 결손이다.**

- **잃는 종목이 0이다.** 편입 종목 기준 813 → 831이고, 원본이 값을 내는 종목을 수정모델이
  전부 낸다(위 premise 실측). 지금 원본이 값을 내는 종목 전부가 `total_equity`를 갖고 있다.
- `ALTMAN_Z`의 미국 +15는 EBIT 유도 경로 몫이고 **기존 값은 하나도 안 바뀐다** — 원시
  `operating_income`이 있으면 그것이 이기므로 새 행만 생긴다. 국내가 0인 것은 DART가 원시
  값을 100% 채우고 유도 재료 여덟을 안 채우기 때문이다.
- **통화 게이트가 둘을 뺀다.** `IndicatorDataReader.CURRENCY_GATE`가 재무 통화와 시장 통화가
  다른 종목의 재무를 **한 행도 안 주므로**(종목 단위다) 두산밥캣(`241560`)·
  코오롱티슈진(`950160`)이 재무상태표가 완비돼 있어도 빠진다. 게이트를 빼고 세면 171·141이
  나오는데 **그 수는 재현되지 않는다.**

**두 지표는 813종목 중 47(5.8%)에서 정반대를 말한다.** 원본이 「안전」(≥3.0)인데 수정모델이
「부실 위험」(<1.1)인 종목 수다 — KOSDAQ 11(8.2%) · US 35(6.9%) · KOSPI 1(0.6%).
반대 방향(원본 부실 → 수정모델 안전)은 14다. 화면에서 두 카드가 나란히 뜨므로 이 불일치는
**사용자가 반드시 마주친다.**

원인은 X4다. 원본은 `시가총액 ÷ 총부채 × 0.6`, 수정모델은 `자기자본 ÷ 총부채 × 1.05`라
**주가순자산배수가 높을수록 원본만 점수를 크게 받는다.** 47종목은 실측으로
자기자본/총부채 중앙값이 **0.661**(전체 0.914)이고 **34.0%가 운전자본 음수**(전체 19.3%)다 —
즉 레버리지가 높고 유동성이 얇은 쪽이며, 원본에서는 시장이 매긴 가격이 그것을 가린다.

**손계산 대조**: ServiceNow(2026 Q2) 운전자본 −3,639M · 총자산 31,666M · 이익잉여금 6,009M ·
EBIT TTM 1,680M · 자기자본 12,516M · 총부채 19,150M →
`6.56·(−0.1149) + 3.26·(0.1898) + 6.72·(0.0531) + 1.05·(0.6536) = 0.9075`. 저장값 0.91과 같다.
**선불 매출이 유동부채로 잡히는 구독형 기업에서 X1이 음수가 되는 것**이 이 모델의 알려진 성질이고,
계산기의 결함이 아니다.

**부피**: 실측 **149,024행**(수정모델 · 보존창 189거래일). 원본도 미국 15종목의 이력만큼
**2,685행** 늘었다. `indicator_value` 2,976만 행 대비 +0.5%다.
**부피를 근거로 지표를 지우는 것은 ADR-0077이 실측으로 기각했다** — 미사용 지표 57개가
저장 부피의 56.1%이고 「안 읽는데 부피를 쓴다」는 잣대는 그 전부에 똑같이 걸린다.
이 결정이 더하는 0.5%도 같은 잣대 아래 있다.

**시점 재현 저장본이 통째로 무효가 됐다.** `PitFingerprint.forCalculators`가 `registry.codes()`와
`com/stockscreener/indicator/**.class` 바이트 전부를 해시하므로, 계산기를 더하고 원본을 고친
것만으로 지문이 바뀐다. **싼 경로 `-Dpit.recheck=calculator`는 쓸 수 없다** — `PitLoadTest`가
저장본과 지금 값을 코드 **합집합**으로 대조하는데 새 코드가 저장본에 없어 표본 전부가 「차이」로
잡힌다. 사용자 결정으로 이 작업에서 전량 재적재한다(`caffeinate -is ./gradlew backtestLoad
-Dpit.days=255`).

**되돌리려면**: `AltmanZDoublePrimeCalculator`와 `IndicatorUnavailabilityConfig`의 규칙 하나를
지우고 `ProfitStreakYearsCalculator`의 `sortOrder`를 47로 되돌린다. `indicator` 행은
`is_active=false`로만 두고 지우지 않는다(ADR-0002). EBIT 원천을 되돌리면 미국 15종목의 행이
**남은 채로 갱신되지 않으므로**, 되돌릴 때는 `ALTMAN_Z`의 그 행을 함께 걷는다.

## 버린 대안

- **Z'(1983, 비상장 제조업)도 함께 만들기** — 우리 유니버스는 전부 상장사라 시가총액을 안다.
  Z'가 여는 것은 「시총을 모를 때」인데 그 상황이 없고, X5를 그대로 두므로 **사용자가 말한
  비제조업 왜곡을 못 고친다.** 지표만 하나 더 늘고 부피가 두 배가 된다.
- **원본 `ALTMAN_Z`를 Z''로 교체하기** — 원본의 시가총액 항은 시장의 평가를 담아 장부가가
  못 보는 것을 본다. 둘은 다른 질문이고, 교체하면 저장된 값의 뜻이 코드 이름 그대로인 채
  조용히 바뀐다. ADR-0039가 임계 2.6을 재보정하지 않기로 못박은 것도 그 코드에 붙어 있다.
- **제외 집합을 Z''용으로 좁히기** — Z''가 비제조업 모델이니 부동산·REIT는 넣자는 안이다.
  기각 근거는 ADR-0092 §버린 대안과 같다 — **좁힐 근거가 될 값이 없다.** 미국 지표가 생긴
  지금은 재볼 수 있게 됐으나, 그것은 **두 알트만에 함께 걸리는 별도 결정**이다.
- **Z''만 유도 경로로 갈아타기** — 기존 코드를 안 건드려 범위가 좁다. 그러나 같은 종목에서
  한 카드는 값이 있고 다른 카드는 없게 되고 그 이유가 화면에 없다. 사용자가 「둘 다」를 골랐다.
- **백분위(`*_PCTL`)를 함께 만들기** — 원본에도 없다. 맞춘다.
