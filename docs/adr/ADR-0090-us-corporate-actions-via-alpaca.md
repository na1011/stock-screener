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

```premise 사람이 안 본 효력일에 남은 불연속이 없다
psql -d stock_screener -At -c "
WITH applied AS (
  SELECT c.stock_id, s.ticker, c.effective_date
  FROM corporate_action c JOIN stock s ON s.id = c.stock_id
  WHERE s.market_code = 'US' AND c.status = 'APPLIED' AND c.note IS NULL),
b AS (SELECT p.stock_id, p.trade_date, p.adj_close,
             lag(p.adj_close) OVER (PARTITION BY p.stock_id ORDER BY p.trade_date) AS prev
        FROM price_daily p JOIN stock s ON s.id = p.stock_id WHERE s.market_code = 'US'),
resid AS (
  SELECT DISTINCT a.ticker, b.trade_date
  FROM applied a JOIN b ON b.stock_id = a.stock_id
   AND b.trade_date BETWEEN a.effective_date - 3 AND a.effective_date + 3
  WHERE b.prev IS NOT NULL AND b.prev > 0
    AND (b.adj_close / b.prev > 1.30 OR b.adj_close / b.prev < 0.70))
SELECT CASE
  WHEN (SELECT count(*) FROM applied) = 0
    THEN '잴 것이 없었다 — 계수가 반영된 미국 기업행위가 없다'
  WHEN (SELECT count(*) FROM resid) = 0
    THEN 'OK 사람이 아직 안 본 ' || (SELECT count(*) FROM applied) || '건의 효력일 언저리에 남은 불연속이 없다'
  ELSE (SELECT count(*) FROM resid) || '건이 효력일 언저리에 아직 불연속이다: '
       || (SELECT string_agg(ticker || ' ' || trade_date, ', ') FROM (SELECT * FROM resid LIMIT 12) x)
       || ' — 효력일이 우리 봉과 어긋났거나 소스가 준 비율을 가격이 안 받친다. '
       || '증거는 corporate-action-review가 관측 배수로 낸다. 재보고 계수가 맞으면 '
       || 'corporate-action-judge --ca-note 로 확인한 사실을 남긴다 — 그러면 여기서 빠진다.'
  END"
```

**MUST: 모집단을 「계수를 반영한 효력일의 언저리」로 좁힌다.** 처음에는 미국 봉 **전체**의
`adj_close` 불연속을 셌는데, 그 형태는 종목·구간이 늘어나면 **의도한 동작에 운다** —
1,000종목 × 7년에서 **771행**이 임계를 넘고 그 배수 분포가 1.3~1.9배 414 · 0.25~0.7배 260으로
**대부분이 분할이 아니라 큰 하루**다(2026-08-31 실측 · ADR-0100). 코로나 급락일과 밈 주식이
그대로 들어온다. 상시 붉은 펜스는 곧 무시되고, **그러면 이 전제가 잡으라고 만들어진
`extra` 방향까지 함께 묻힌다.**

**좁힌 뒤에 못 보게 되는 것도 적는다**: **원장이 통째로 빈 종목**은 이제 여기 안 걸린다.
그것을 재는 자리는 ADR-0088의 premise다. 이 전제는 아래 문장이 스스로 밝히는 것 하나만 잰다.

**이 전제가 묻는 것은 원장의 유무가 아니라 효력일의 정합이다.** `adj_close = close × adj_factor`
불변식도 · 원장이 `APPLIED`인 것도 · 재실행 0행도 · 사건 수와 불연속 수의 대조도 **효력일이
하루 밀린 것을 못 본다**(ADR-0050이 적어 둔 자리). **결과에서 재는 것이 이 전제뿐이다.**
임계는 `PriceDiscontinuity.LIMIT_UPPER`/`LOWER`와 같은 값이고, 그쪽이 바뀌면 여기도 바꾼다.

**MUST: `note`가 있는 행을 뺀다 — 이 전제가 묻는 것은 「이상한가」가 아니라 「아직 안 봤는가」다.**
가격만으로는 **효력일이 밀린 것과 그날의 실제 등락을 못 가른다.** 실측(2026-08-31)으로 `QXO`가
그 자리다 — 1:8 병합에 8배를 적용하고도 1.686배가 남는데, 주식수가 2024-03-31 5,315,581에서
2024-06-30 409,430,195로 뛴 리캡이라 **그 잔차는 실제 등락이고 계수는 맞다.** 뺄 방법이
없으면 이 펜스는 그 한 건 때문에 영구히 붉고, **상시 붉은 펜스는 곧 무시된다.**
`note`는 `judge`만 쓰는 사람의 필드라(ADR-0050) **그것이 「봤다」의 유일한 기록이다.**

**대조군으로 확인했다**(2026-08-31): 원장의 효력일만 하루 옮기면 **안 붉는다** — 이미 저장된
`adj_close`가 안 바뀌기 때문이고, 그것이 이 전제가 **결과에서** 재는 자리라는 증거다.
효력일 직전 봉의 `adj_close`를 조정 전 값으로 되돌리면(계수가 하루 밀려 적용된 모양) 그때
붉는다. 둘 다 롤백하는 트랜잭션 안에서 쟀다.

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

### P23 축 여섯의 판정 근거

**착수 전에 적은 여섯 축을 수로 답한다**(`docs/phase-criteria.md` P23). 원장의 지금 모양은
**미국 110행 — `APPLIED` 108(분할 88 · 병합 20) · `REJECTED` 2**이고 **전부 소스 공시 유래**다.

| 축 | 답 |
|---|---|
| ① 비율 방향이 왕복 검증됐는가 | `AlpacaCorporateActionAdapterTest`·`AdjustmentRecalculatorTest` 등 **60 시험 통과**. 병합 표본이 들어 있고, 비율을 못 내는 사건은 `UNKNOWN`으로 남아 종류 딱지를 안 받는다. 저장 스케일은 `ratio numeric(18,10)` |
| ② 요청 창과 필터 기준이 다른가 | 계약 문서가 잰다 — 조회는 `process_date`, 거르기는 `ex_date`이고 **3년 넘게 벌어진 사건이 실재한다**(T→WBD). 페이징 정렬이 **심볼 우선**인 것도 실측으로 적혔다. 멱등: 같은 (종목,효력일,종류)가 두 행인 경우 **0건** |
| ③ 안 담는 종류를 수로 적었는가 | **좁히지 않고 한 번 불러 분포를 남겼다** — 파일럿 80건(`cash_dividends` 78 · `forward_splits` 1 · `spin_offs` 1), 2024년 전량 773건(정방향 99 · 역방향 674). 안 담는 이유가 종류마다 따로 있고(분사의 `new_rate`는 배정 비율이지 가격 조정 계수가 아니다), 심볼 필드 이름이 타입마다 다르다는 것도 계약 문서에 있다 |
| ④ 국내를 안 건드렸는가 | 타임스탬프가 답한다 — 2026-08-31 02:41~08:29에 미국 원장 110건을 적재·판정·재구축했는데 **국내 지표·재무·원장·시세가 전부 0행** 변경이다. 국내 원장은 지금도 `APPLIED` 88 · `REJECTED` 3 · `UNSUPPORTED` 7이다 |
| ⑤ 효력일이 저장된 시세와 맞는가 | **§검증의 전제가 결과에서 잰다** — 사람이 안 본 `APPLIED` **107건**의 효력일 언저리에 남은 불연속이 0이다. 대조군으로 확인했다: 효력일만 옮기면 안 붉고, 효력일 직전 봉의 `adj_close`를 조정 전으로 되돌리면 붉는다 |
| ⑥ 저장 정밀도에서 0으로 접히는가 | `adj_close`가 `numeric(18,6)`이고 **미국 양수 최솟값이 0.051**이다 — 저장 바닥(1e-6)의 5만 배 위이고 0인 행도 0.01 미만도 **0행**이다. **막는 CHECK는 없다**(`ck_price_ohlc`·`ck_price_volume` 둘뿐) — 질의로 재는 자리가 이 표다 |

**휴리스틱이 미국에서 0건인 것은 결손이 아니라 가드다.** 같은 술어가 미국 이웃 쌍 1,652,647 중
**771건·317종목**을 올리는데(국내 543,002 중 72건의 3.5배) 그 배수 분포가 대부분 분할이 아니라
**큰 하루**다. `withoutUnjudgeable`이 `listed_shares`가 NULL인 후보를 걸러 내므로 미국은 0이 되고,
**축은 시장이 아니라 주식수의 유무다** — 시장 코드로 분기하면 안 고친 시장이 조용히 옛 임계를 쓴다.

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
