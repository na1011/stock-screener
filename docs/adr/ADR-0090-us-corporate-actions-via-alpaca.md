---
id: ADR-0090
status: 채택됨
date: 2026-08-29
applies_to:
  - "src/main/java/com/stockscreener/pipeline/datasource/us/AlpacaCorporateActionAdapter.java"
  - "src/main/java/com/stockscreener/pipeline/datasource/us/AlpacaResponse.java"
  - "src/main/java/com/stockscreener/pipeline/batch/CorporateActionJob.java"
  - "src/main/java/com/stockscreener/pipeline/batch/AdjustmentRebuildJob.java"
  - "src/main/java/com/stockscreener/pipeline/batch/BootstrapRunner.java"
  - "docs/api-spec/alpaca-corporate-action.md"
---

# ADR-0090. 미국 기업행위를 Alpaca로 받되 분할·병합만 값으로 내고, 비활성 유니버스 경로를 잡 둘에 낸다

## 결정

미국 기업행위를 Alpaca `/v1/corporate-actions`로 받는다. 결정은 셋이다.

1. **분할·병합만 값으로 낸다.** 배당·분사·합병류는 **안 담고 세어서 로그로 남긴다.**
2. **`types`로 좁혀 부르지 않는다.** 좁히면 안 담기로 한 것의 건수가 아예 안 온다.
3. **비활성 유니버스를 손으로 고르는 경로를 `corporate-action`과 `adjustment-rebuild`
   양쪽에 낸다.** 원장만 열면 계수는 안 바뀌는데 둘 다 성공으로 끝난다.

> **사용자가 고른 문장(2026-08-29):** *"탐침 뒤에 다시 묻는다"* · *"파일럿 11종목"* ·
> *"분할·병합만 + 새 타입 경보"*.
> **그에 대한 내 해석 — 갈라 적는다:** 「어댑터를 쓰기 전에 계약을 재고, 실측 타입 분포를
> 들고 돌아와 범위를 정한다」 · 「`--universe`는 비활성 유니버스를 여는 수단이지 전량을
> 뜻하지 않는다」 · 「16종 밖의 타입이 오면 WARN, 16종 안이지만 안 담는 것은 건수로 INFO」.
> 사용자가 닫은 것은 *언제 정하는가* · *범위* · *담는 타입*이고, **배당 조정을 도입할지는
> 닫히지 않았다**(§버린 대안).

## 규칙

- **MUST: `ratio`를 `new_rate / old_rate`로 내고 스케일을 지정해 나눈다.**
  실측으로 NVDA 10:1 분할이 `old=1 / new=10`, ATRA 25:1 병합이 `old=25 / new=1`이라 분할이
  1보다 크고 병합이 작다 — `CorporateAction.ratio`의 계약과 같은 방향이다.
  **스케일 없는 `divide`는 3:1 병합에서 `ArithmeticException`이고, 그것은 종목 실패가 아니라
  배치 전체의 죽음이다**(`failUnexpectedly` 뒤 되던져진다).
  - **MUST NOT: 부호가 어긋난 응답을 컴팩트 생성자에 넘기지 않는다.** 같은 이유로 배치가 죽는다.
    종목 실패(`MALFORMED`)로 접고 값을 지어내지 않는다.
- **MUST: `process_date` 창으로 부르고 `ex_date`로 거른다.** 소스의 `start`/`end`는
  `process_date` 기준인데 포트는 `ex_date` 기준 필터를 MUST로 요구하고, **둘이 3년 넘게 벌어진
  사건이 실재한다**(T→WBD 분사: `ex_date` 2022-04-11 / `process_date` 2025-07-18).
  요청 창을 앞으로 1년 넓힌다 — **지연 폭은 쟀고 선행 폭은 재지 않았다.**
- **MUST NOT: `types`로 좁혀 부르지 않는다.** 좁히면 담지 않기로 한 종류의 건수가 안 와서
  **「그런 사건이 없다」와 「우리가 안 담는다」가 갈리지 않고**(phase 기준 P23 축③),
  16종 밖의 타입이 와도 **경보가 영영 안 뜬다.** 값은 실측으로 작다 — 파일럿 11종목 2.7년치가
  80건이다.
- **MUST NOT: 배당을 원장에 담지 않는다.** `ratio`의 계약이 *"1주가 몇 주가 되었는가"*이고
  적용부가 `1/ratio`만 쓰는데 **배당의 조정은 차감(`(P−D)/P`)이라 표현할 자리가 없다.**
  국내에 배당 데이터가 없어서가 아니다 — `governance_fact.CASH_DIVIDEND_TOTAL`에 이미 있고,
  배당액의 자리는 그쪽이다. **`UNKNOWN`으로 흘리는 것은 더 나쁘다** — `findUnadjusted`가 잡아
  ADR-0055가 배당 있는 미국 종목을 전부 프리셋에서 지운다.
- **MUST: 응답의 심볼 필드가 타입마다 다르다는 것을 안다.** 분할·병합·배당은 `symbol` 하나인데
  분사는 `source_symbol`(발행사)과 `new_symbol`(분사된 회사) 둘이다. **요청한 심볼과 다르면
  붙이지 않는다** — 붙이면 엉뚱한 종목의 계수가 아무 에러 없이 틀린다.
- **MUST: 비활성 유니버스 경로의 0건 throw를 잡 안에 직접 둔다.**
  `StockRegistrar.oneUniverseMemberIds`는 활성 여부를 안 보는 대신 **예외를 던지지 않고 빈
  목록을 낸다.** 안 채우면 두 잡 다 `targets.isEmpty()` 갈래로 빠져 **SUCCESS로 끝난다**
  (ADR-0088 §규칙의 *"조용한 0건은 「적재할 것이 없다」와 구별되지 않는다"*).
  - **MUST: `BootstrapRunner.UNIVERSE_JOBS`를 함께 넓힌다.** `--universe`는 조용히 무시되는 것이
    아니라 `rejectMisplacedLoadOptions`가 **거절한다** — 안 넓히면 명령이 첫 줄에서 죽는다.
    적는 것은 `JOB_NAME`이 아니라 **`--job=`의 값**이다.
  - **MUST NOT: 이 갈래를 스케줄러·`AdminJobLauncher`에 걸지 않는다.** 런처의 호출부는
    `universeCode`에 **`null`을 명시**한다(ADR-0088·0089 승계).
- **MUST: 적재 명령에 `--server.port=0`을 붙인다.** 운영 앱이 8080을 물고 있으면 컨텍스트가
  죽는데 **러너는 아예 시작되지 않고 gradle이 `BUILD SUCCESSFUL` + 종료코드 0을 낸다.**
- **MUST NOT: `ShareRatioSnap`을 미국에 쓰지 않는다.** 그것은 *관측된* 배수를 유리수에 붙이는
  도구이고 여기서는 소스가 유리수를 그대로 준다.

## 검증

```bash
./gradlew test --tests "*AlpacaCorporateAction*" --tests "*CorporateActionJob*" \
               --tests "*AdjustmentRebuildJob*" --tests "*BootstrapArgumentGuard*"
```

비율 방향이 분할·병합 왕복에서 반대인지 · `ex_date`로 거르는지 · 요청 창이 더 넓은지 ·
`types`를 안 주는지 · 배당을 한 건도 안 만드는지 · 403이 `AUTH`인지 · 비활성 유니버스를
고르는지 · 0건이 예외인지가 여기서 걸린다.

```bash
./gradlew liveTest --tests "*AlpacaCorporateActionProbe*"
```

소스가 아직 그대로인지를 지킨다. 실측값의 정본은 `docs/api-spec/alpaca-corporate-action.md`다.

```premise 미국 수정종가에 남은 불연속이 없다
psql -d stock_screener -At -c "
WITH b AS (SELECT s.ticker, p.trade_date, p.adj_close,
       lag(p.adj_close) OVER (PARTITION BY p.stock_id ORDER BY p.trade_date) AS prev
   FROM price_daily p JOIN stock s ON s.id = p.stock_id WHERE s.market_code = 'US'),
d AS (SELECT * FROM b WHERE prev IS NOT NULL AND prev > 0
       AND (adj_close / prev > 1.30 OR adj_close / prev < 0.70))
SELECT CASE
  WHEN (SELECT count(*) FROM b WHERE prev IS NOT NULL) = 0
    THEN '잴 것이 없었다 — 미국 봉이 둘 이상인 종목이 없다'
  WHEN (SELECT count(*) FROM d) = 0
    THEN 'OK 미국 수정종가에 임계를 넘는 불연속이 없다 (' || (SELECT count(*) FROM b) || '행)'
  ELSE (SELECT count(*) FROM d) || '행이 아직 불연속이다: '
       || (SELECT string_agg(ticker || ' ' || trade_date, ', ') FROM d)
       || ' — 원장이 비었거나 효력일이 우리 봉과 어긋난다. 계수는 효력일 *이전* 봉에 걸리므로 '
       || '하루만 밀려도 이미 조정된 봉이 한 번 더 나뉜다.'
  END"
```

**이 전제가 묻는 것은 원장의 유무가 아니라 효력일의 정합이다.** `adj_close = close × adj_factor`
불변식도 · 원장이 `APPLIED`인 것도 · 재실행 0행도 · 사건 수와 불연속 수의 대조도 **효력일이
하루 밀린 것을 못 본다**(ADR-0050이 적어 둔 자리). **결과에서 재는 것이 이 전제뿐이다.**
임계는 `PriceDiscontinuity.LIMIT_UPPER`/`LOWER`와 같은 값이고, 그쪽이 바뀌면 여기도 바꾼다.

## 배경

ADR-0088이 미국 시세를 붙이며 `adj_factor`를 전 구간 1로 두고 *"미국 지표를 만들기 전에
닫는다"*를 MUST로 걸었다. 그 부채는 실물이었다 — 저장된 11종목 2,772행에서 임계를 넘는
불연속이 **정확히 하나**, NVDA 2024-06-10(1208.88 → 121.79)이다.

**어댑터를 쓰기 전에 탐침으로 잰 것 중 계획을 실제로 바꾼 것이 넷이다.**

**첫째, 심볼 필드의 이름이 타입마다 다르다.** 분사만 `source_symbol`/`new_symbol` 둘이라,
뒤쪽을 집으면 **묻지도 않은 종목이 응답에 있는 것처럼 보인다** — 실제로 그렇게 오독했다.

**둘째, `process_date`와 `ex_date`가 3년 넘게 벌어진다.** 공급자의 *"생성 시점을 보장하지
않는다"*는 경고가 그 모양이다. 요청 창을 좁게 잡으면 사건이 빠지고, 빠진 것은 0건과 같다.

**셋째, 정렬이 문서 서술과 다르다.** 가이드는 `process_date`라 적었는데 실측은 **심볼 우선**
이다 — 시세와 같은 함정이고, 중간에 멈추면 뒤쪽 심볼이 통째로 빈다.

**넷째, 없는 타입은 키 자체가 없다.** `{"corporate_actions":{}}`가 「사건이 없다」이고
`start`/`end`를 빠뜨렸을 때와 **같은 모양**이라, 파라미터 오타가 0건으로 저장된다.

**적재 결과**: 파일럿 11종목 11/11 성공, 미국 원장 1행(NVDA `SPLIT` 10), 재구축 110행,
`adj_close` 불연속 **0행**. 국내 원장(88/3/7)·보존 경계(2025-12-01)·미국 지표(0)·
ADR-0071 premise(7) 전부 불변이다.

## 버린 대안

- **배당을 담아 총수익 기준 수정주가를 만든다** — 계수 모델이 곱셈이라 차감을 표현할 자리가
  없고, 하려면 **양 시장에 함께** 해야 한다. 그런데 **국내에는 배당 권리락일이 없다** —
  `alotMatter`가 주당 현금배당금·총액을 주지만 **회계연도 단위이고 날짜 필드가 없다**.
  미국만 하면 두 시장의 `adj_close`가 다른 뜻이 되어 시장 간 비교가 깨진다(spec §7).
  **이 문은 닫히지 않았다** — 열려면 국내 권리락일 소스를 먼저 찾아야 하고, 그때 계수 모델과
  저장된 지표 전체가 바뀐다.
- **어댑터가 `ex_date`를 저장된 시세의 불연속으로 교차확인하고 안 맞으면 안 낸다** —
  국내가 무상증자에 하는 것이다(`explainsPriceGap`). **미국에 쓰면 실제 사건을 버린다**:
  우리 미국 봉이 2024년 1년뿐이라 그 밖 구간에는 대조할 불연속이 **원리적으로 없다.**
  그래서 게이트가 아니라 위 premise로 뒀다.
- **`types`를 분할·병합으로 좁힌다** — 콜 크기가 준다. 버린 이유는 그러면 §규칙의 두 가지가
  동시에 죽기 때문이다: 안 담은 것의 건수와 새 타입 경보.
- **`symbols`를 묶어 콜을 줄인다** — ADR-0088이 시세에서 기각했다(실패 격리의 단위).
  여기서는 이유가 하나 더 있다: `limit`이 전체 기준이라 묶으면 **한 종목의 결측과 묶음 전체의
  절단이 같은 모양**이 된다.
- **`AlpacaResponse`에 봉투 검증 메서드를 새로 쓴다** — `requireBars`가 이미 상태코드를 먼저
  보고 `sourceName`을 인자로 받으며 빈 결과를 안 던진다. 새로 쓰면 같은 판정이 세 벌이 된다.
  키를 인자로 뺐다.
- **`ShareRatioSnap`으로 비율을 스냅한다** — 관측이 아니라 소스가 준 유리수라 붙일 것이 없다.

## 결과

**쉬워지는 것**: 미국 기업행위를 종목당 1콜로 받는다. 포트가 하나라
`CorporateActionCollector`·`CorporateActionLedger`·`AdjustmentRecalculator`가 그대로 미국을
처리하고 **마이그레이션이 없다.** 그리고 `US_LISTED`를 켤 때 **배치를 죽이던 관문이 닫혔다** —
`requireSources`는 이제 미국 소스를 찾는다.

**어려워지는 것**

- **미국이 정례 경로에 없다.** `--universe` 없이 도는 `corporate-action`·`adjustment-rebuild`는
  여전히 활성 유니버스만 본다. **새 미국 분할은 손으로 유니버스를 줄 때만 들어오고**,
  `AdjustmentRebuildJob`의 *"전량이 곧 검증"*이 그만큼 갈렸다.
- **재구축 알림이 미국에서 거짓말을 한다.** `alertOnInvalidation`이 **계수가 바뀐 종목 수**로
  문장을 짓는다(`IndicatorInvalidationPort.invalidate`가 `void`라 실제 삭제 행을 못 센다).
  그래서 *"지표가 보존 창 전체에서 지워졌다 … 백필하라"*가 **미국 지표가 0행인데도 나간다.**
  그 말을 따르면 미국에 아무 일도 안 하면서 **한 시간 넘게 도는 백필**이 걸린다.
  포트를 안 고친 이유는 국내 경로를 건드리는 변경이기 때문이다.
- **판정 증거가 반쪽이다.** 미국 `price_daily.listed_shares`가 전 행 NULL이라
  `CorporateActionReader`의 `sameDayShareRatio`도 「효력일 이후 주식수 점프」도 비어 있다.
  사람이 판정할 일이 생기면 볼 것이 **가격 배수 하나**다.
- **비활성 유니버스 경로가 잡 셋에 걸렸다.** ADR-0087이 「유일한 자리」로 적어 둔 것이 셋이 됐다.

**되돌리려면**: `AlpacaCorporateActionAdapter`를 지우고 두 잡의 `universeCode` 파라미터와
`UNIVERSE_JOBS` 항목을 되돌린다. 적재된 원장은 `corporate_action`에서 시장으로 지우고
`adjustment-rebuild`를 다시 돌린다 — **원장이 비면 계수가 1로 돌아간다**(ADR-0049의 설계다).
