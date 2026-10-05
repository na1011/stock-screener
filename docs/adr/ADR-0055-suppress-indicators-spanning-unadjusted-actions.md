---
id: ADR-0055
status: 채택됨
date: 2026-08-13
applies_to:
  - "src/main/java/**/indicator/**"
  - "src/main/java/**/pipeline/collector/CorporateActionLedger.java"
---

# ADR-0055. 창이 미보정 기업 행위를 삼키면 그 지표 값을 내지 않는다

## 결정

지표의 읽기 창(`IndicatorRegistry.effectiveLookback`) 안에 **계수에 반영되지 않은 기업 행위**가
있으면 그 산출 일자의 값을 쓰지 않고, **이미 저장된 행은 지운다.**

대상은 원장의 `OBSERVED`와 `UNSUPPORTED`다. `REJECTED`는 "그런 일이 없었다"이므로 그 종목의
지표는 보정 없이 맞고, `APPROVED`·`APPLIED`는 `adj_factor`가 이미 축척을 하나로 만들었다.

## 규칙

- MUST: 억제 판정을 러너 한 곳에서 끝낸다(`IndicatorRunner.run` → `UnadjustedActionGuard`).
  계산기마다 다시 판단하게 하면 지표를 하나 더할 때 그 판단이 빠지고, **빠진 쪽의 증상은
  "값이 나온다"라 아무도 못 본다.**
- MUST: 경계는 반열림이다 — `windowStart.date < effectiveDate <= tradeDate`.
  창 시작이 정확히 효력일이면 창 안 모든 봉이 사건 이후라 축척이 하나뿐이므로 **빼지 않는다.**
  닫힘으로 바꾸면 지표마다 하루씩 더 지워지는데, **값이 사라지는 방향이라 조용하다.**
- MUST: 인덱스가 아니라 **날짜**로 비교한다. 007340은 2024-09-25~10-07이 매매거래정지라 봉이
  통째로 비고, 인덱스 산술로 재면 그 공백만큼 창이 어긋난다.
- MUST: 억제로 결과가 **빈 목록이 되어도** 저장된 행을 지운다. `IndicatorValueWriter.write`는
  UPSERT뿐이라 "쓰지 않는다"로 끝내면 옛 행이 그대로 남고, 남은 값은 그럴듯하다.
  구현상 이것은 `points.isEmpty()`로 `continue`하기 **전에** `deleteAt`을 부르는 것이다.
- MUST: 파생 백분위·업종 상대값도 함께 정리한다(`DerivedIndicatorCleanup`). 두 계산기는
  저장된 값을 다시 읽는 `INSERT … SELECT`뿐이라 **원본이 사라져도 파생 행은 남는다.**
- MUST: `CorporateActionLedger.findUnadjusted`를 `findApplicable` **옆에** 둔다. 둘의 합집합이
  원장 전체여야 하고, 흩어지면 겹치거나 비는데 어느 쪽도 에러를 내지 않는다.
- MUST NOT: `findUnadjusted`에 `type <> 'UNKNOWN'`을 걸지 않는다. `findApplicable`이 그것을
  거르는 이유("종류를 모르는 비율은 계수가 아니다")가 여기서는 정반대다 — 실측된 5종목의
  사건이 **전부 `UNKNOWN`**이라, 걸면 이 결정이 통째로 아무 일도 하지 않는다.

## 승인된 한계

- **축척에 불변인 지표도 함께 빠진다.** `TRADING_DAYS_750`은 봉 개수만 세고 `TURNOVER_60`은
  거래소가 집계한 `trade_value`를 쓰므로 분할이 있어도 값이 맞는다. 그래도 빼는 이유는
  지표마다 "축척에 민감한가"를 선언하게 하면 **그 선언이 빠진 지표가 조용히 통과**하기
  때문이고, 방향이 안전한 쪽이다. 실측 영향은 007340·207940의 `TRADING_DAYS_750` 두 종목뿐이며
  **결과는 바뀌지 않는다** — 둘 다 같은 프리셋의 다른 필수 지표(`SHARES_GROWTH_3Y`)가 함께
  빠진다.
- **재귀 지표의 꼬리는 창 계약으로 자른다.** `RSI_14`·`ATR_14`·`MACD`는 창 앞쪽이 지수적으로
  감쇠하며 계속 이어지므로 원리적으로는 **읽기 창 전체**에 의존한다. 176봉 뒤의 가중치가
  `(13/14)^176 ≈ 2e-6`이라 **선언한 창을 계약으로 삼는다.**
- **원장에 없는 사건은 여전히 거짓 양성으로 남는다.** 이 결정이 답하는 것은 "원장이 못 고친다고
  말한 것"뿐이다. 수집되지 않은 분할은 여기서도 안 걸린다(ADR-0051 §결과가 남긴 공백).
- **판정 큐가 비어 있어야 이 결정이 종목을 잃지 않는다.** 새 `OBSERVED` 하나가 그 종목의
  장기창 지표를 즉시 지운다. 그래서 `CorporateActionJob`이 새 후보를 알림으로 내보낸다.

## 검증

**MUST: 최신 거래일만 잰다.** 읽기 창은 산출 일자가 아니라 **그 실행의 `asOf`가 정한다** —
러너가 `asOf` 기준으로 읽기 창만큼을 한 번 읽어 창 전체의 값을 계산하므로, 창 앞쪽 산출
일자의 실제 입력은 "그 날짜에서 창만큼 뒤"가 아니라 **읽어온 가장 오래된 봉**이다. 그래서 옛 일자에는
사건 이후 봉만으로 낸 정상 값이 남을 수 있고, 날짜 기준으로 재면 그것이 위반으로 보인다
(실제로 017670의 128일자가 그 모양이다). 최신 거래일은 `asOf`와 일치해 창이 명확하고,
**화면이 읽는 것도 그 한 장이다.**

```premise 미보정 기업행위 창 안에서 지표 값이 나오는 종목
psql -d stock_screener -At -c "
WITH lk(code,n) AS (VALUES
 ('SMA_200',200),('DISPARITY_200',200),('SMA_200_MOM_21',221),('PCT_FROM_52W_HIGH',252),
 ('PCT_FROM_52W_LOW',252),('SMA_200_SLOPE_60',260),('SHARES_GROWTH_3Y',751)),
ca AS (SELECT stock_id, effective_date FROM corporate_action WHERE status IN ('UNSUPPORTED','OBSERVED')),
latest AS (SELECT market_code, max(trade_date) d FROM indicator_trade_date GROUP BY market_code),
bad AS (SELECT iv.stock_id, iv.indicator_code
        FROM indicator_value iv JOIN stock s ON s.id = iv.stock_id
        JOIN latest l ON l.market_code = s.market_code AND iv.trade_date = l.d
        JOIN ca ON ca.stock_id=iv.stock_id
        JOIN lk ON lk.code=iv.indicator_code
        WHERE iv.trade_date >= ca.effective_date
          AND (SELECT count(*) FROM price_daily p WHERE p.stock_id=iv.stock_id
                 AND p.trade_date >= ca.effective_date AND p.trade_date <= iv.trade_date) < lk.n)
SELECT CASE
  WHEN (SELECT count(*) FROM ca) = 0 THEN '잴 것이 없었다 — 미보정 기업행위가 원장에 하나도 없다'
  WHEN NOT EXISTS (SELECT 1 FROM latest) THEN '잴 것이 없었다 — indicator_value가 비어 있다'
  WHEN count(*) = 0 THEN 'OK 시장별 최신 거래일에 보정 못 한 창으로 낸 값 0행'
  ELSE '보정 안 된 계단을 넘는 창으로 낸 값이 ' || count(*) || '행 남아 있다'
  END FROM bad"
```

**시장별 최신일로 잰다.** 국내로만 좁혀 두었던 것은 미국 지표가 0행이라 미국 쪽을 검증할 수 없어서였다.
미국 지표가 생겼고, 분사 설계(ADR-0168)부터 미국에도 미해석 분사가 `OBSERVED`로 들어와 억제가 걸린다
(반증이 「미국 억제는 아무도 안 본다」를 짚었다).


착수 시점 실측(2026-08-13, `max(trade_date)=2026-08-12`, 보존창 180일자). 이 결정이 지우는 양이며,
반영 백필의 로그 합계가 **정확히 20,750행**으로 아래 표와 일치했다.

| 종목 | 효력일 | 상태 | 억제 행 | 지표 | 일자 |
|---|---|---|---|---|---|
| 207940 삼성바이오로직스 | 2025-11-24 | UNSUPPORTED | 5,788 | 49 | 176 |
| 007340 DN오토모티브 | 2024-10-08 | UNSUPPORTED | 4,238 | 31 | 180 |
| 010060 OCI홀딩스 | 2023-05-30 | UNSUPPORTED | 4,195 | 31 | 180 |
| 005440 현대지에프홀딩스 | 2023-04-10 | UNSUPPORTED | 4,151 | 31 | 180 |
| 017670 SK텔레콤 | 2021-11-29 | UNSUPPORTED | 2,378 | 29 | 82 |
| **합** | | | **20,750** | | |

`020560 아시아나항공`(2021-01-15)·`036830 솔브레인홀딩스`(2020-08-06)는 저장된 모든 일자에서
창 밖이라 0행이다.

**IMPORTANT — 어느 종목이 대상인지를 최신 일자 하나로 가르면 틀린다.** 017670은 최신 일자
기준 1,151봉이라 **당시 창(1,100)** 밖으로 보였지만, 보존창에서 가장 오래된 저장 일자는
971봉이라 82일자가 대상이었다. 판정은 **(종목, 지표, 산출 일자)마다** 한다.

**반영 뒤 확인**: `ICHIMOKU_SP_KR` 통과가 **20 → 19**가 됐고 빠진 것은 007340 하나다
(다른 종목은 들지도 나지도 않았다 — 그때 조건이던 `ICHI_TIER <= 3`은 순위가 아니라 절대
임계였다. 그 지표는 2026-08-14에 폐기됐다).
`SWING_MIDTERM_KR`은 0건 그대로다.

## 배경

007340이 `ICHIMOKU_SP_KR`을 통과하고 있었다. 2024-10-08에 종가가 102,100 → 19,200으로 5.32배
떨어졌는데 `adj_factor`가 전 구간 1이다 — 액면분할인 것은 확정이나 거래정지 중 증자가 섞여
비율이 갈리지 않아 `UNSUPPORTED`로 남았고(`corporate_action.note`), 그 계단을 넘는 **당시
창(1,100봉)**이 Tier 2를 냈다. 통과 20종목 중 하나가 근거 없는 값이었다.

같은 자리가 넷 더 있고, **일목만의 문제가 아니었다.** 207940은 2025-11-24 인적분할로 1.465배
계단이 있는데 `SWING_MIDTERM_KR`의 필수 게이트 16개 중 15개를 통과한다 — `SHARES_GROWTH_3Y`가
−34.96%로 자사주 매입처럼 읽히고 `PCT_FROM_52W_HIGH`가 −22.0으로 기준 −25를 3pt 차로 넘는다.
반대 방향도 있다. 007340의 `SHARES_GROWTH_3Y`는 +485.46%로 나와 희석 게이트에서 **근거 없이
탈락**한다(분할은 희석이 아니다).

증상이 결측이 아니라 **그럴듯한 숫자**라는 것이 요점이다. 에러도 경고도 없고 어떤 테스트도
깨지지 않는다.

## 버린 대안

- **화면에 경고만 붙이고 값은 그대로 낸다.** 문제는 값이 나온다는 것 자체다. 경고를 읽는 것과
  값이 조건 판정에 들어가는 것은 별개이고, 워치리스트 알림은 화면을 거치지 않는다.
- **종목 단위로 통째로 억제한다.** 207940의 `PER`·`MARKET_CAP`·`RSI_14`는 창이 사건을 삼키지
  않아 값이 맞는다. 통째로 빼면 멀쩡한 값을 버리면서 코스피200 대형주가 모든 화면에서 사라진다.
- **`UNSUPPORTED`만 억제하고 `OBSERVED`는 통과시킨다.** `OBSERVED`는 "아직 안 봤다"이지
  "보정됐다"가 아니다. 통과시키면 새 후보가 잡힌 종목이 판정 전까지 오늘과 똑같이 근거 없는
  값을 내고, 그 상태가 정확히 이 ADR을 쓰게 만든 상태다.
- **지표마다 "축척 민감" 플래그를 선언하게 한다.** 선언이 빠진 지표가 조용히 통과하고, 그
  누락은 값이 나오는 형태라 드러나지 않는다. 초과 억제를 승인된 한계로 두는 편이 안전하다.
- **`AdjustmentRecalculator`가 `UNSUPPORTED`에도 관측 비율을 넣는다.** 007340은 가격이 5를,
  주식수가 6을 가리켜 어느 쪽도 확정되지 않는다. 근사로 메우면 2020~2024 전 구간이 20%
  어긋나는데 값은 여전히 그럴듯하다(2026-08-13 사용자 판단).

## 결과

- **원장 커버리지가 곧 지표 신뢰도가 된다.** ADR-0051 §결과가 남긴 공백 — *"원장 커버리지가 곧
  보정 커버리지다"* — 이 그 공백을 메운다. 다만 방향은 한쪽이다: 원장이 **말한** 결손만 막고,
  원장에 없는 사건은 여전히 통과한다.
- **판정을 미루는 비용이 생겼다.** 이전에는 `OBSERVED`가 쌓여도 아무 일이 없었고 그것이 문제였다.
  이제는 종목이 화면에서 빠지므로 판정이 운영 작업이 된다. 관리자 API는 있고 화면은 없다
  (ADR-0049 §버린 대안). 알림이 그 사이를 잇는다.
- **"왜 이 종목이 없는가"의 답이 종목 상세에만 있다.** FILTER 프리셋에서는 결과에 아예 없고,
  SCORING이라도 필수 조건이면 `ConditionEvaluator`가 종목을 통째로 뺀다 — 조건별 "데이터 없음"이
  구분해 주는 것은 필수 조건이 0개인 프리셋뿐이다. 그래서 `ScreeningResponse.Row`에는 자리를
  만들지 않았다.
- **되돌리려면**: `IndicatorRunner.run`의 억제 블록을 걷고 백필을 다시 돌린다. `deleteAt`과
  `DerivedIndicatorCleanup`은 남겨 둔다 — 그 둘은 이 결정 없이도 옳은 정리이며, 실제로
  `DerivedIndicatorCleanup`은 모집단 부족으로 파생 값을 못 쓴 날의 낡은 행도 함께 걷는다.
