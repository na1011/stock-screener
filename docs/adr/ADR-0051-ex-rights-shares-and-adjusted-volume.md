---
id: ADR-0051
status: 채택됨
date: 2026-08-13
applies_to:
  - "src/main/java/**/indicator/ShareAdjustment.java"
  - "src/main/java/**/indicator/IndicatorContext.java"
  - "src/main/java/**/indicator/IndicatorDataReader.java"
  - "src/main/java/**/indicator/IndicatorRunner.java"
  - "src/main/java/**/indicator/PriceBar.java"
  - "src/main/java/**/indicator/calculator/PriceScaling.java"
  - "src/main/java/**/indicator/calculator/FinancialMath.java"
  - "src/main/java/**/indicator/calculator/MarketCapCalculator.java"
  - "src/main/java/**/indicator/calculator/SharesGrowth3yCalculator.java"
  - "src/main/java/**/pipeline/collector/CorporateActionLedger.java"
---

# ADR-0051. 지표 계층이 원장을 읽고, 권리락 구간의 주식수와 수정거래량을 저장하지 않고 유도한다

## 결정

`IndicatorContext`가 **원장의 적용 대상 사건을 함께 싣고**, 그것으로 두 가지를 유도한다.

```
sharesOn(d)                    = listed_shares(d) × Π { a : a.effective_date <= d < a.listing_date } ratio(a)
corporateActionMultiple(f, t)  =                    Π { a : f < a.effective_date <= t }             ratio(a)
adjustedVolume(bar)            = volume × close / adj_close                     ( = volume ÷ adj_factor )
```

- **`MARKET_CAP`·`PER`·`PBR`은 `sharesOn`을 거친다.** 권리락 구간의 밸류에이션 절벽을 없앤다.
- **`SHARES_GROWTH_3Y`는 `corporateActionMultiple`로 나눈다.** 분할·무상증자는 희석이 아니다.
- **수정거래량은 `PriceScaling`에 둔다.** 착수 시점에는 소비자가 없었고 지금은 다섯이다 —
  `OBV_T_13`·`MFI_14`·`VOLUME_RATIO_20`·`CMF_21`·주봉 OBV(`ICHI_OBV_T`).
- **무상증자 승인에 `listing_date`를 요구한다.** 유도의 입력이 사람의 판정에서만 빌 수 있다.

## 규칙

- **MUST: 종가와 곱하거나 나누는 자리는 `IndicatorContext#sharesOn`을 거친다.** `PriceBar#listedShares`를
  직접 쓰면 무상증자 권리락~신주상장 14~16거래일이 **이미 희석된 가격 × 희석 전 주식수**가 된다.
  루닛 2026-05-04의 저장값이 그것이다 — 시가총액 1.214조 → **0.609조**, PBR 9.73 → **4.88**.
  **결측이 아니라 그럴듯한 숫자라 화면에 그대로 나가고 어디에도 흔적이 없다.**
- **MUST: 두 파생 조회의 기준일을 `effective_date` 하나로 유지한다.** `sharesOn`이 권리락에서
  계단을 올리므로 `corporateActionMultiple`도 같은 날을 봐야 정확히 상쇄된다. 한쪽만
  `listing_date`로 바꾸면 권리락 구간에서 배수가 **한 번 더 붙거나 빠진다.**
  경계의 등호도 그래서 정해진다 — `from` 당일 사건은 빼고(`sharesOn(from)`이 이미 곱했다)
  `to` 당일 사건은 넣는다.
- **MUST: `listing_date`가 없으면 `effective_date`로 접는다.** 접는 자리는
  `IndicatorDataReader.readShareAdjustments` **하나**이고 `ShareAdjustment`의 컴팩트 생성자가
  null을 거부해 그것을 강제한다. 액면분할·병합은 두 날짜가 실제로 같아 접어도 정확하고,
  모르는 사건은 구간이 공집합이 되어 **보정하지 않는 쪽**으로 떨어진다.
- **MUST: 적용 대상의 정의를 `findApplicable` 한 곳에 둔다.** 지표 계층이
  `status IN ('APPROVED','APPLIED') AND type <> 'UNKNOWN'`을 다시 쓰면 계수와 주식수가
  **다른 사건 집합**을 보게 되는데 어느 쪽도 에러를 내지 않는다.
- **MUST: 무상증자를 승인할 때 `listing_date`를 요구하고 `effective_date`보다 빠르면 거부한다.**
  없으면 계수만 들어가고 그 구간의 시총·PER·PBR은 틀린 채 남는데, **ADR-0049의 불변식은 그
  어긋남을 보지 않는다** — `adj_close = close × adj_factor`는 주식수를 묻지 않는다.
  날짜 역전은 **같은 실패의 조용한 판**이다: 구간이 공집합이 되어 보정이 사라진다.
  V21이 그 순서를 `CHECK`로 걸지 않았으므로 `judge`가 유일한 문이다.
- **MUST: 그 게이트를 *합쳐진* 상태로 판정한다.** `judge`는 상태를 비운 채 값만 고치는 것을
  정당한 경로로 열어 뒀고 `UPDATE`가 `coalesce(?, status)`이므로, 판정값만 보면 **이미
  `APPROVED`인 행의 종류를 `BONUS_ISSUE`로 바꾸는 경로**가 게이트를 통째로 비껴간다.
- **MUST: `adjustedVolume`이 가격과 반대 방향임을 왕복으로 잰다.** `adjusted()`는
  `adjClose/close`를 곱하고 이쪽은 그 역수다 — 분할 **전** 거래량이 늘어나야 한다.
  두 함수가 나란히 있어 부호를 베껴 쓰기 쉽고 **뒤집혀도 값은 그럴듯하다.**
  병합 표본(`ratio < 1`)을 반드시 넣는다(ADR-0049의 같은 규칙이 `ratio`의 새 소비자에게 승계된다).
- **MUST: 권리락 구간을 재는 테스트의 픽스처를 751봉 이상으로 만든다.**
  `SharesGrowth3yCalculator`는 `bars.size() <= 750`이면 빈 목록을 내므로, 짧은 픽스처에서
  그 지표에 대한 단언은 **공집합 단언**이 되어 구현이 무엇이든 통과한다.
- **MUST NOT: `MarketCapCalculator`를 `FinancialMath.sharesOf`로 갈아타지 않는다.**
  그쪽 재무 폴백은 이 지표가 **일부러 값을 내지 않는 봉**에 값을 만든다.
- **MUST NOT: 재무 폴백 주식수에 `sharesOn`을 곱하지 않는다.** 분기 재무의 주식수는 공시 시점의
  값이라 권리락 구간과 무관하고, 곱하면 이미 신주가 반영된 수에 한 번 더 곱한다.
- **MUST NOT: 유도한 값을 컬럼이나 `PriceBar`의 컴포넌트로 저장하지 않는다**(ADR-0049 §규칙).
  `close`·`adjClose`·원장으로 나오므로, 저장하면 계수와 lockstep으로 지켜야 할 불변식이 늘고
  `PriceWriter`가 이미 정확히 그 형태의 버그를 냈다.
- **MUST NOT: `price_daily.listed_shares`를 소급해 덮지 않는다.** 소스가 준 값은 그날의 사실이다.
  덮어도 `PriceWriter`가 `EXCLUDED`로 되돌린다.

## 검증

```bash
./gradlew test --tests "*PriceScaling*" --tests "*ExRightsContinuity*" \
               --tests "*IndicatorRunner*" --tests "*CorporateAction*" \
               --tests "*SwingIndicators*" --tests "*Pbr*" --tests "*Per*"
```

지표 계층이 적용 대상 판정을 **다시 쓰지 않는지** — 낱말이 아니라 이 자리로 묻는다:

```bash
rg -n "APPROVED|APPLIED|corporate_action" src/main/java/com/stockscreener/indicator/
```

`IndicatorDataReader`가 `CorporateActionLedger.findApplicable`을 부르는 줄 하나 말고
**SQL이 나오면 정의가 두 곳이 된 것이다.**

```premise 승인된 무상증자에 신주상장일이 있고 권리락보다 뒤다
psql -d stock_screener -At -c "SELECT CASE
  WHEN to_regclass('public.corporate_action') IS NULL THEN 'OK 원장 테이블이 아직 없다'
  ELSE (SELECT CASE
    WHEN count(*) = 0 THEN '잴 것이 없었다 — 적용 대상 무상증자가 없다'
    WHEN count(*) FILTER (WHERE listing_date IS NULL OR listing_date < effective_date) = 0
      THEN 'OK 적용 대상 무상증자 ' || count(*) || '건 전부 신주상장일이 있고 권리락보다 뒤다'
    ELSE count(*) FILTER (WHERE listing_date IS NULL OR listing_date < effective_date)
         || '/' || count(*) || '건의 신주상장일이 비었거나 권리락보다 빠르다 — 그 종목의 권리락 구간 시총·PER·PBR이 배수만큼 틀린 채 남고, judge 게이트는 앞으로 들어올 행만 막는다.'
    END FROM corporate_action
    WHERE type = 'BONUS_ISSUE' AND status IN ('APPROVED','APPLIED'))
  END"
```

```premise 권리락 구간에 실제로 봉이 있다
psql -d stock_screener -At -c "SELECT CASE
  WHEN to_regclass('public.corporate_action') IS NULL THEN 'OK 원장 테이블이 아직 없다'
  ELSE (SELECT CASE
    WHEN count(*) = 0 THEN '잴 것이 없었다 — 두 날짜가 벌어진 적용 대상 사건이 없다'
    WHEN sum(bars) = 0
      THEN count(*) || '건의 권리락 구간에 봉이 하나도 없다 — 보정이 어느 행도 건드리지 않으므로 이 결정이 실데이터에서 검증되지 않았다.'
    ELSE 'OK ' || count(*) || '건 / 보정 대상 ' || sum(bars) || '봉'
    END FROM (
      SELECT c.id, (SELECT count(*) FROM price_daily p
                    WHERE p.stock_id = c.stock_id
                      AND p.trade_date >= c.effective_date
                      AND p.trade_date < c.listing_date) AS bars
      FROM corporate_action c
      WHERE c.status IN ('APPROVED','APPLIED') AND c.type <> 'UNKNOWN'
        AND c.listing_date > c.effective_date) w)
  END"
```

보정은 **곱셈**이므로 `indicator_value.value`의 `numeric(24,8)`(정수부 16자리)를 넘길 수 있다.
넘기면 그 종목이 통째로 롤백되고 지표가 사라진다. 저장된 시총 최대가 이미 16자리라
(삼성전자 2,119조) **여유가 얼마인지가 아니라 실제 보정 대상이 넘기는지**를 잰다:

```premise 권리락 구간 보정이 지표 저장 타입의 자릿수를 넘기지 않는다
psql -d stock_screener -At -c "SELECT CASE
  WHEN to_regclass('public.corporate_action') IS NULL THEN 'OK 원장 테이블이 아직 없다'
  ELSE (SELECT CASE
    WHEN max(p.close * p.listed_shares * c.ratio) IS NULL
      THEN '잴 것이 없었다 — 보정 대상 봉이 없다'
    WHEN max(p.close * p.listed_shares * c.ratio) < 1e16
      THEN 'OK 보정 후 최대 시가총액 ' || round(max(p.close * p.listed_shares * c.ratio) / 1e12)
           || '조 — numeric(24,8) 정수부 16자리 안이다'
    ELSE '보정 후 시가총액이 ' || round(max(p.close * p.listed_shares * c.ratio) / 1e12)
         || '조로 numeric(24,8) 정수부 16자리를 넘는다 — 그 종목의 지표 저장이 overflow로 통째로 롤백되고, 화면에서 종목이 사라지는데 배치는 실패로만 남는다.'
    END
    FROM corporate_action c
    JOIN price_daily p ON p.stock_id = c.stock_id
     AND p.trade_date >= c.effective_date AND p.trade_date < c.listing_date
    WHERE c.status IN ('APPROVED','APPLIED') AND c.type <> 'UNKNOWN'
      AND c.listing_date > c.effective_date AND p.listed_shares > 0)
  END"
```

## 배경

ADR-0049가 `adj_factor`를 원장에서 다시 짓는 계층을 세웠고 ADR-0050이 사람의 판정 경로를 열었다.
남은 것은 **한 사건이 바꾸는 것 중 가격 말고 나머지 둘**이다(`phase-criteria.md` §P9).

**권리락 구간은 `adj_factor`로 원리적으로 표현할 수 없다.** 계수는 `effective_date`보다 **뒤**의
사건만 보므로 권리락 당일과 신주상장 이후가 **둘 다 1**이다. 두 구간을 가르는 정보는
`listing_date`뿐이고 그것은 원장에만 있다. 시계열의 주식수 점프로 유추하는 길은 ADR-0050이 이미
막았다 — 루닛 2026-05-04 이후 75일 창에 점프가 셋이다(전환권행사 ×1.27 · 진짜 신주상장 ×2.0 ·
잡음 ×1.001). **그래서 지표 계층이 원장을 읽는 것이 이 결정의 핵심이며**, 원자료(시세·재무) 밖을
읽는 두 번째 자리다(첫째는 ADR-0044의 업종).

경계의 등호는 실측이다. `effective_date` 당일이 희석 가격의 **첫날**이고(루닛 2023-11-09
170,500 → 90,200) `listing_date` 당일이 새 주식수의 **첫날**이다(2023-12-01
14,238,154 → 28,476,308 · 현대글로비스 2024-08-02 37,500,000 → 75,000,000 ·
루닛 2026-05-26 37,191,318 → 74,382,636).

**이 변경은 오늘 화면을 바꾼다.** `SHARES_GROWTH_3Y <= 5`는 `SWING_MIDTERM_KR`의 필수 게이트이고
2026-08-11 기준 227/316이 통과했다. 원장의 승인된 세 행이 그 값을 틀리게 만들고 있었다 —
리노공업(5:1 분할) 400% · 현대글로비스(1:1 무상증자) 100% · 루닛 503%. 앞 둘은 희석이 아니므로
정규화하면 0이 되어 게이트를 넘는다. **모집단이 227 → 229가 되는 것이 이 결정의 의도된 결과다.**

**이 결정을 내릴 때 수정거래량에는 소비자가 없었다.** 거래량을 읽는 계산기가 하나도 없었기
때문이다(`TURNOVER_60`조차 소스가 준 `trade_value`를 쓴다). 자리를 미리 만든 이유는 부호를
**소비자가 오기 전에** 고정하기 위해서였고, **그 판단은 실현됐다** — 지금 `adjustedVolume`을
부르는 계산기가 다섯이고 그중 넷은 일목과 무관하다(`OBV_T_13`·`MFI_14`·`VOLUME_RATIO_20`·
`CMF_21`). 보정하지 않으면 분할 배수가 OBV·MFI의 **누적**에 영구히 남는데, 값은 여전히
그럴듯해서 어떤 테스트도 깨지지 않는다.

## 버린 대안

- **`IndicatorDataReader`가 `corporate_action`을 직접 질의한다** — `CorporateActionLedger`를
  주입하지 않아도 되고 계층 방향도 깔끔하다. 그러나 적용 대상 판정이 **두 곳**이 되고,
  두 곳이 갈리면 계수와 주식수가 다른 사건 집합을 보는데 **어느 쪽도 에러를 내지 않는다.**
  쓰기 메서드가 있는 컴포넌트를 읽기 계층에 주입하는 비용을 그 대가로 받아들였다.
- **`PriceBar`에 보정된 주식수와 수정거래량을 컴포넌트로 넣는다** — 계산기가 빠뜨릴 수 없어진다.
  그러나 `PriceBar`는 *저장된 것을 지표 계층의 말로 옮긴* 원자료이고, 원장에서 유도한 값을
  거기 섞으면 그 경계가 사라진다. 더 결정적으로 **`SHARES_GROWTH_3Y`는 그 보정을 원하지 않는다** —
  양 끝에 그냥 곱하면 거짓 희석이 14거래일 앞당겨질 뿐이다. 값이 두 뜻을 가지면 안 된다.
- **`sharesOn`을 `FinancialMath.sharesOf` 안에만 두고 `MARKET_CAP`도 그리로 보낸다** —
  호출부가 하나가 된다. 그러나 그쪽 재무 폴백이 `MARKET_CAP`이 일부러 값을 내지 않는 봉에
  값을 만든다. 지금은 `DataRequirement`가 재무를 안 읽어 무증상이지만, 그 결합은 조용하다.
- **`price_daily.adj_volume` 컬럼을 만든다** — ADR-0049가 이미 버렸다. 그 판단이 유지된다.
- **`PriceScaling`을 지금 `public`으로 올린다** — 하위 패키지가 부를 것이므로 언젠가 필요하다.
  그러나 소비자가 없는 채로 여는 것은 추측이라 미뤘다. (그 뒤 실제로 열렸다.)
- **`listing_date`가 빈 무상증자를 승인하게 두고 `/summary`가 세게 한다** — 화면이 없어 아무도
  안 본다. 승인 시점에 막는 편이 그 행을 `UNSUPPORTED`("있었는데 못 고친다")로 보낸다.

## 결과

- **쉬워지는 것**
  - 권리락 구간의 밸류에이션이 연속이 된다. 루닛 2026-05-04 시총 0.609조 → 1.218조.
  - 분할·무상증자 종목이 "3년간 400% 희석"으로 읽히지 않는다.
  - P12가 거래량 보정을 **다시 설계하지 않는다.** 부호도 이미 왕복으로 고정돼 있다.
  - 원장 커버리지가 늘 때마다 보정이 저절로 넓어진다 — 코드를 다시 안 고친다.
- **어려워지는 것**
  - **종목당 조회가 하나 는다.** 백필은 날짜 × 종목 루프라 그만큼 곱해진다. 실측으로
    `findApplicable`이 `idx_ca_stock` 인덱스 스캔이고 751봉 조회의 5% 미만이다.
  - **화면에 눈에 보이는 불일치가 하나 생긴다.** 종목 상세는 `listed_shares` 원값을 그대로
    내는데(`StockReader.priceOn` → `StockHeader.vue`) 시가총액은 보정된 값이 된다 —
    권리락 구간에서 둘이 안 맞는다. `UNADJUSTED_PRICE_NOTICE`는 가격에만 붙어 있다.
    ADR-0049가 화면을 범위 밖으로 뒀으므로 **고치지 않는다.**
  - **원장 커버리지가 곧 보정 커버리지다.** 수집되지 않았거나 판정되지 않은 분할은 여전히
    거짓 양성으로 남는다 — **"보정한다"를 "전량 보정됐다"로 읽지 않는다.**
  - **저장된 값이 코드보다 낡는다.** 이 변경 뒤 재백필하지 않으면 `indicator_value`가 옛 값을
    들고 있고, 스크리닝은 그것을 읽는다.
- **되돌리려면**: `sharesOn`·`corporateActionMultiple` 호출을 걷고 `Entry`에서 `listingDate`를
  뺀 뒤 전 구간을 재백필한다. **재백필하지 않으면 코드는 옛날로 돌아가고 저장된 값은 새 값인
  상태가 남는다.**
