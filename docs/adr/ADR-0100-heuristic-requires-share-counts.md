---
id: ADR-0100
status: 채택됨
date: 2026-08-31
applies_to:
  - "src/main/java/com/stockscreener/pipeline/collector/AdjustmentRecalculator.java"
  - "src/main/java/com/stockscreener/pipeline/datasource/PriceDiscontinuity.java"
  - "src/test/java/com/stockscreener/pipeline/collector/AdjustmentRecalculatorTest.java"
---

# ADR-0100. 가격 급변 휴리스틱은 상장주식수가 있을 때만 후보를 올린다

## 결정

`AdjustmentRecalculator.detectCandidates`가 **그 봉과 직전 봉에 `listed_shares`가 둘 다 있을
때만** 후보를 올린다. 없으면 올리지 않고 **그 수를 로그로 남긴다.**

**`PriceDiscontinuity`의 임계(1.30/0.70)는 안 바꾼다.** 그것은 KRX 가격제한폭이고, 바꿀 값을
정할 근거가 없다 — 일일 가격제한이 없는 시장에서 「이 폭을 넘으면 기업행위」라고 말할
숫자가 애초에 없다.

## 규칙

- **MUST: 축을 시장 코드가 아니라 `listed_shares`의 유무로 둔다.** 시장으로 분기하면 새 시장마다
  그 목록을 고쳐야 하고, **고치지 않은 시장은 조용히 옛 임계를 쓴다.** 데이터로 판정하면
  그 시장이 주식수를 주기 시작하는 날 자동으로 그물이 살아난다.
- **MUST: 건너뛴 수를 남긴다.** 조용히 건너뛰면 이 저장소가 가장 싫어하는 형태가 된다.
  국내에서는 결손이 0행이라 그 줄이 안 뜨고, **뜨면 V9 이전 형태의 행이 다시 들어왔다는 신호다.**
- **MUST NOT: `parValueRatio`를 건드리지 않는다.** 바뀐 것은 **올릴지**이고, 「주식수가 없으면
  가격으로 비율을 대신 내지 않는다」는 그대로다(그 6% 오차는 ADR-0049가 실측으로 잡았다).
- **MUST NOT: 이것을 「그 시장에는 기업행위 탐지가 없다」로 읽지 않는다.** 소스가 분할·병합을
  직접 주는 시장에서는 **그쪽이 정본**이고(ADR-0090), 이 휴리스틱은 **소스가 액면병합을 안 싣는
  국내를 위한 두 번째 그물**이다(ADR-0049 §배경의 실측).

## 검증

```bash
./gradlew test --tests "*AdjustmentRecalculatorTest*" --tests "*CorporateActionCollector*"
```

「주식수가 없으면 후보로 올리지 않는다」가 새 계약을, 「주식수가 있는데 비율이 가격을 설명하지
못하면 여전히 UNKNOWN이다」가 **안 바뀐 쪽**을 잠근다. 둘을 짝으로 둔 이유는 앞엣것만 두면
`parValueRatio`를 함께 비활성화해도 초록이기 때문이다.

```premise 판정할 수 없는 후보가 원장에 쌓이지 않았다
psql -d stock_screener -At -c "
WITH bad AS (
  SELECT s.market_code, count(*) AS n, count(DISTINCT c.stock_id) AS stocks
  FROM corporate_action c
  JOIN stock s ON s.id = c.stock_id
  WHERE c.detection_source = 'HEURISTIC' AND c.type = 'UNKNOWN'
    AND c.status IN ('OBSERVED', 'UNSUPPORTED')
    AND NOT EXISTS (SELECT 1 FROM price_daily p
                     WHERE p.stock_id = c.stock_id AND p.listed_shares IS NOT NULL)
  GROUP BY s.market_code)
SELECT CASE
  WHEN (SELECT count(*) FROM corporate_action) = 0 THEN '잴 것이 없었다 — 원장이 비어 있다'
  WHEN (SELECT count(*) FROM bad) = 0
    THEN 'OK 상장주식수가 없는 종목의 판정불가 후보가 원장에 없다'
  ELSE (SELECT string_agg(market_code || ' ' || n || '건/' || stocks || '종목', ', ') FROM bad)
       || '이 원장에 있다 — 종류도 비율도 못 내는 후보이고, findUnadjusted가 그것을 돌려주면 '
       || 'UnadjustedActionGuard가 그 효력일을 창에 삼키는 지표를 통째로 억제한다'
END"
```

**이 전제가 묻는 것은 코드가 아니라 원장이다.** 술어를 되돌려도 **이미 쌓인 행은 안 사라지므로**,
코드를 고친 것과 원장이 깨끗한 것을 따로 잰다.

## 배경

미국 시세를 1,000종목 × 7년 적재한 뒤 `corporate-action`을 돌리기 전에 그 술어가 몇 건을
올리는지 셌다(승인된 계획의 게이트).

| 시장 | 이웃 쌍 | 급변 | 종목 | 비율 |
|---|---|---|---|---|
| 국내 | 543,002 | **72** | 67 / 350 | 0.0133% |
| 미국 | 1,652,647 | **771** | **317 / 1,005** | 0.0467% |

**3.5배이고, 그 771의 배수 분포가 결정적이다**: 1.3~1.9배 **414** · 0.25~0.7배 **260** ·
1.9~4배 38 · 4배 초과 27 · 0.25배 미만 32. **대부분이 분할이 아니라 큰 하루다.**

**그 행은 무해하지 않다.** `listed_shares`가 없으면 `parValueRatio`가 언제나 비어 후보가 전부
`UNKNOWN`으로 쌓이고, `CorporateActionLedger.findUnadjusted`가 `OBSERVED`를 그대로 돌려주며
`UnadjustedActionGuard`가 **그 효력일을 창에 삼키는 산출 일자를 억제한다** — 워밍업이 1,261봉인
지표(`EPS_CAGR_5Y`·`PER_MEDIAN_5Y`·`RETAINED_TO_MARKET_4Y`)는 **한 건만 걸려도 보존 창 전체가
빈다.** 배치는 SUCCESS이고 화면은 「값 없음」이라 **조건 미달과 구별되지 않는다.**

**축이 시장이 아니라 주식수인 것은 실측이 정했다.** 국내는 `listed_shares` 결손이 **0행**이라
이 술어로 후보가 **72 → 72로 한 건도 안 줄고**, 미국은 전 행이 NULL이라 **771 → 0**이다.

## 버린 대안

- **임계를 시장별로 가른다** — 처음 계획이 그것이었다. 버린 이유는 **바꿀 값을 정할 근거가
  없다**는 것이다. 1.30/0.70은 KRX 제도에서 나온 값인데, 미국에는 대응하는 제도가 없어
  어떤 수를 넣어도 우리가 지어낸 값이다. 그리고 임계를 아무리 올려도 **주식수가 없으면 종류도
  비율도 못 내므로** 올라오는 것은 여전히 판정불가 후보다.
- **가격 배수를 `ShareRatioSnap`에 걸어 단순 유리수만 남긴다** — ADR-0049가 이미 기각한
  형태다(*"가격으로 대신 내면 이 메서드가 없애려는 바로 그 6% 오차가 돌아온다"* —
  대한전선 실측 0.094073 대 0.1).
- **`UnadjustedActionGuard`가 `UNKNOWN`을 무시하게 한다** — 억제를 푸는 방향이라 **국내에서
  실재하는 인적분할 후보**(주식수는 바뀌었는데 가격이 설명 안 되는 경우)까지 함께 풀린다.
  그쪽이 정확히 이 가드가 잡으라고 만들어진 것이다(ADR-0055).
- **미국에 `corporate-action`을 안 돌린다** — 그러면 `adj_factor`가 1로 남아 ADR-0088의 부채가
  그대로다. 미루는 것은 **지표를 만들기 전까지**만 유효하고, 다음 세션의 첫 일이 그것이다.
- **SQL 술어로 거른다** — 한 줄이면 되지만 **건너뛴 수를 셀 수 없다.** 조용한 스킵과 시끄러운
  스킵의 차이가 이 결정의 절반이라 Java 쪽으로 옮겼다.

## 결과

**쉬워지는 것**: 상장주식수를 주지 않는 시장에 `corporate-action`을 돌려도 판정불가 후보가
쌓이지 않는다. 국내 동작은 한 건도 안 바뀐다(실측).

**2026-09-13 — 「건너뛴 수를 남긴다」의 자리를 종목에서 시장으로 옮겼다.** 종목마다 INFO로
찍던 것이 한 판에 **312줄 · 380 B/줄**이었고 **그 312가 전부 US**라(국내 `listed_shares` 결손
0행) **국내 신호가 원리적으로 묻혀 있었다.** 시장별 한 줄로 접어 이 §규칙의 의도를 살렸다 —
국내가 1건이라도 내는 날 그 줄이 따로 뜬다. **0인 시장은 안 찍는다**: 이 신호의 형태가
「부재 → 출현」이라 0을 늘 찍으면 늘 있는 줄 안의 숫자 변화가 되어 눈에 안 띈다.

**판 대가는 기본 레벨의 종목 귀속이다.** 어느 종목이었는지가 INFO에서 사라진다. 복구 경로는
`.env`의 `LOG_ADJUSTMENT=DEBUG` + 재기동이고, **`detectCandidates`는 외부 호출이 없어 재실행이
쿼터를 안 태운다**(실측). **MUST NOT: 이것을 「수를 지웠다」로 읽지 않는다** — 수는 시장 축과
함께 INFO에 그대로 있고, 사라진 것은 stockId뿐이다.

**어려워지는 것**

- **그 시장에는 두 번째 그물이 없다.** 소스가 놓친 분할은 휴리스틱도 안 잡는다. 방향은
  안전한 쪽이지만(안 잡는 것이지 틀리게 잡는 것이 아니다) **잡히던 것이 안 잡히는 것도 사실이다.**
  드러나는 자리는 ADR-0090의 premise(`adj_close`)와 봉의 불연속이다.
- **국내에서 `listed_shares`가 결손이 되면 그물이 그만큼 줄어든다.** 지금 0행이라 무해하고,
  줄어들면 위 로그가 뜬다 — **그 로그가 유일한 신호다.**
- **원장에 이미 쌓인 판정불가 후보는 이 결정이 안 지운다.** 위 premise가 그것을 따로 잰다.

**되돌리려면**: `detectCandidates`의 null 갈래와 `withoutUnjudgeable`를 지운다. 그러면
`AdjustmentRecalculatorTest`의 「주식수가 없으면 후보로 올리지 않는다」가 붉는다 —
**되돌린 것이 조용하지 않다는 것이 그 시험의 값이다.**
