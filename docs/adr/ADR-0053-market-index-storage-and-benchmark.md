---
id: ADR-0053
status: 채택됨
date: 2026-08-13
applies_to:
  - "src/main/resources/db/migration/V23__market_index.sql"
  - "src/main/java/com/stockscreener/pipeline/datasource/MarketIndexDataSource.java"
  - "src/main/java/com/stockscreener/pipeline/datasource/RawIndexBar.java"
  - "src/main/java/com/stockscreener/pipeline/datasource/kr/DataGoKrMarketIndexAdapter.java"
  - "src/main/java/com/stockscreener/pipeline/collector/MarketIndexCollector.java"
  - "src/main/java/com/stockscreener/pipeline/collector/MarketIndexCalendar.java"
  - "src/main/java/com/stockscreener/pipeline/batch/MarketIndexJob.java"
---

# ADR-0053. 시장 지수는 별도 테이블에 담고, 기준지수는 종목의 시장으로 가른다

## 결정

`market_index_daily`를 새로 만들어 코스피·코스닥 지수의 일별 종가를 담는다. `price_daily`를
재사용하지 않는다.

**26주 초과 수익률(`EXCESS_RETURN_26W`, 원본 DRV-55)의 기준지수는 종목의 `market_code`가
정한다** — 코스피 종목은 코스피, 코스닥 종목은 코스닥. 원본 Pine은 통화만 보고
`KRX:KOSPI` 하나로 접지만 그것을 따르지 않는다. **ADR-0046 §승인된 이탈 4**이다.

## 규칙

- **MUST NOT: 지수를 `price_daily`에 담지 않는다.** `stock_id`가 NOT NULL이라 자리가 없고,
  `adj_close`·`adj_factor`·`listed_shares`는 지수에 뜻이 없다. 지수에는 분할이 없으므로
  수정주가라는 개념 자체가 성립하지 않는다.
- **MUST: `index_code`를 CHECK로 고정한다.** 조회표 테이블을 만들지 않는 대신이다.
  어댑터가 `'KOSPI'`로 쓰고 리더가 다른 문자열로 물으면 조회가 0행이고, **그 0행이 "그날 지수가
  없다"로 접혀 지수를 쓰는 지표가 전 종목에서 판정불가가 된다** — `EXCESS_RETURN_26W`·
  `EXCESS_RETURN_52W`·`BETA_104W` 셋이다(ADR-0046 §지수 정확 일치). 에러도 화면 흔적도 없는
  형태다. 값을 늘리려면 마이그레이션이 필요하고 그 강제가 목적이다.
- **MUST: 소스의 `idxNm` 정확 문자열은 어댑터가 갖는다**(`코스피`·`코스닥`). 벤더 사실이므로
  포트 밖으로 새지 않는다(ADR-0006). 정본은 `docs/api-spec/data-go-kr-market-index.md`이고
  **사본을 테이블로 늘리지 않는다.** 숫자가 있는 지수는 앞에 공백이 있다(`코스피 200`).
- **MUST NOT: 소스의 `fltRt`·`vs`를 저장하지 않는다.** 파생값이며 수익률은 언제나 종가에서
  자체 계산한다(spec §7).
- **MUST: `endBasDt`에 하루를 더한다.** 문서 원문이 "검색값보다 **작은** 데이터"다. 안 더하면
  구간 끝 하루가 조용히 빠진다.
- **MUST: 결손 판정을 `MarketIndexCalendar.countOn`으로 한다.** "이 실행이 쓴 행 수"로 물으면
  안 된다 — 수집이 15일 창으로 도는 이상(ADR-0042) 대상일이 비어 있어도 창 안 과거 날짜의
  재적재가 그 수를 채워 **알림이 원리적으로 발화하지 않는다.** `DailyUpdateJob`의
  `alertIfNothingArrived`가 시세에 대해 이미 같은 결론에 도달했다.
- **MUST NOT: `MARKET_INDEX` 단계를 `BatchExecutionContext.process`로 돌리지 않는다.**
  `batch_execution_failure.stock_id`가 NOT NULL + FK라 **종목 밖 실패에는 넣을 값이 없고**,
  시도하면 지수 수집 실패 하나가 일일 갱신 전체를 죽인다. `UNIVERSE`와 같은 자리이며 집계는
  `countExternally`, 통지는 `alertOperator`다.
- **MUST NOT: 지수 수집 실패를 조용히 넘기지 않는다.** 지수는 종목 밖 입력이라 **실패 격리의
  단위가 종목이라는 원칙이 원리적으로 성립하지 않는 첫 자리**다. 한 줄이 없으면 전 종목이 함께
  판정불가가 되므로, 조용하면 그날 스크리너가 통째로 빈 것을 아무도 모른다.
- **MUST NOT: API 키를 두 벌로 두지 않는다.** 지수 서비스는 시세와 base URL만 다르고 키는 같다.
  별도 `Source`로 나누면 한쪽만 비었을 때 `@ConditionalOnApiKey`가 지수 어댑터만 등록하지 않고,
  그러면 지수가 영영 안 들어오는데 **에러도 알림도 없다.**

## 검증

```bash
./gradlew test --tests "*MarketIndexCollectorTest*"
```

`upsertIsIdempotent`가 `IS DISTINCT FROM` 가드를, `rejectsUnknownIndexCode`가 CHECK를,
`calendarAnswersTargetDateNotWrittenRows`가 **결손을 무엇으로 판정하는가**를 지킨다.
마지막 것이 이 ADR 고유의 자리다 — 쓴 행 수로 물으면 초록인 채 알림이 죽는다.

```premise 시장 지수 — 시세가 있는데 지수만 없는 거래일이 있는가
[ "$(psql -d stock_screener -At -c "SELECT to_regclass('public.market_index_daily') IS NOT NULL")" = t ] || { echo "잴 것이 없었다 — market_index_daily 테이블이 아직 없다(V23 미적용)"; exit 0; }; psql -d stock_screener -At -c "WITH gap AS (SELECT c.index_code, p.trade_date FROM (SELECT DISTINCT trade_date FROM price_daily WHERE trade_date >= (SELECT min(trade_date) FROM market_index_daily)) p CROSS JOIN (SELECT DISTINCT index_code FROM market_index_daily) c WHERE NOT EXISTS (SELECT 1 FROM market_index_daily m WHERE m.index_code = c.index_code AND m.trade_date = p.trade_date)) SELECT CASE WHEN (SELECT count(*) FROM market_index_daily) = 0 THEN '잴 것이 없었다 — 지수가 한 행도 없다. --job=market-index를 아직 안 돌렸다' WHEN (SELECT count(*) FROM price_daily) = 0 THEN '잴 것이 없었다 — price_daily가 비어 있다' WHEN (SELECT count(*) FROM gap) = 0 THEN 'OK 시세가 있는 모든 거래일에 두 지수가 다 있다(지수 최초일 이후 기준)' ELSE (SELECT count(*) FROM gap)::text || '개의 (지수, 거래일)이 비어 있다 — 그 일자의 해당 시장 종목은 ICHI_* 가 통째로 없다. 가장 최근 ' || (SELECT max(trade_date)::text FROM gap) END"
```

**MUST: 테이블 존재를 별도 호출로 먼저 묻는다.** PostgreSQL은 `CASE`의 어느 가지도 실행하기
전에 문장 전체를 파싱하므로, 같은 쿼리 안에서 `information_schema`로 가드해도 **없는 테이블을
참조하는 순간 오류로 끝난다.** 그러면 침묵도 발견도 아닌 **판정 불가**가 되고, 판정 불가는
무엇이 잘못됐는지 말해 주지 않는다.

**모집단이 비는 경우를 셋으로 가른다** — 테이블이 없는 것(배포 전), 지수가 한 행도 없는 것
(적재 전), 시세가 없는 것은 원인이 전부 다르다. 비교 시작점을 지수의 최초 거래일로 잡는 것은
시세가 지수보다 먼저 쌓인 구간을 결손으로 읽지 않기 위해서다.

## 배경

`ICHIMOKU_SP_KR`(ADR-0046)의 D5가 ±3점이고 그것이 신뢰도 임계 8/15를 넘나들어 Tier를 실제로
뒤집는다. 지수 없이 이식하면 원본이 `nz(idx_close[130]) = 0`일 때 하는 대로 **상대 수익률이
절대 수익률로 조용히 바뀐다.** 그리고 T-08(2년 주간 베타)이 같은 원자료를 요구하므로,
이 phase가 두 개를 함께 연다.

**기준지수를 시장별로 가르는 근거는 원본의 그 자리가 판단이 아니라 한계라는 것이다.**
`market_idx_mode`의 선택지는 `["AUTO","KOSPI","SPX"]` 셋뿐이라 **코스닥이 아예 없고**, AUTO의
분기는 `syminfo.currency == "KRW"`라 **통화 검사로는 코스피/코스닥을 표현할 수 없다**(둘 다
KRW다). 게다가 `input.symbol`이라 사용자가 매 실행 바꾸라고 만들어 둔 자리다. 우리는
`stock.market_code`를 갖고 있고, 정보가 없어 생긴 폴백을 정보가 있는 쪽에서 재현하는 것은
충실이 아니라 결함 복제다. `drv_55`의 임계가 ±5%인데 두 지수의 26주 격차는 그 폭을 예사로
넘으므로, 그대로 두면 D5가 **국면마다 코스닥 전체에 일괄로 붙는 ±3점**이 된다.
**2026-08-13 실측**: 1,493거래일 중 **1,014일(67.9%)에서 |격차| > 5%**이고 중앙 절대격차가
9.2%, 최대가 121.0%다. 추정이 아니라 측정이다.

## 버린 대안

- **`price_daily`에 담고 `stock_id`를 nullable로 바꾼다** — 종목 시세의 모든 제약과 인덱스가
  지수 행에도 걸리고, `adj_factor`를 곱하는 코드가 언젠가 지수 행을 만난다. 그 곱셈은 아무
  에러도 내지 않는다.
- **`market_index` 조회표를 따로 만든다** — `idxNm` 문자열의 사본이 하나 더 생긴다. 정본은
  api-spec이고, 코드 쪽 방어로는 CHECK 하나면 충분하다.
- **코스피 하나로 전 종목을 잰다(원본 충실)** — 위 배경의 이유로 D5가 종목 신호가 아니게 된다.
  TradingView 대조에서 코스닥 종목이 전부 어긋나지만, 이번 phase는 대조를 하지 않기로 했고
  (사용자 판단) 나중에 대조할 때는 원본의 `market_idx_mode="KOSPI"` 강제 모드나 코스피 종목만
  으로 맞출 수 있다.
- **코스피 200·코스닥 150을 쓴다** — 원본의 SPX가 대형주 대표지수라는 점에서는 더 가까운
  대응이다. 그러나 우리 유니버스가 시총 상위 200·150이라 **거의 같은 구성종목의 지수와 견주게
  되고**, 그것은 이미 백분위(ADR-0036)가 맡은 "모집단 안 상대위치" 축과 중복된다.
- **지수도 종목처럼 `stock` 행을 만들어 담는다** — 유니버스·재무·기업행위가 전부 지수 행을
  만나게 된다. 편입 판정과 백분위 모집단이 조용히 오염된다.

## 결과

- **쉬워지는 것**: T-08이 계산기 하나만 남는다. 지표 계층이 종목 밖 시계열을 받는 길이
  ADR-0054로 함께 열린다.
- **어려워지는 것**
  - **일일 갱신에 외부 호출 2콜과 새 실패 지점이 하나 붙는다.** 실패가 시세 수집을 죽이지
    않게 격리했으므로, 그 대가로 **지수만 조용히 빠지는 상태가 가능하다** — 그것을 잡는 것이
    `alertIfIndexMissingWhilePricesArrived` 하나뿐이다.
  - **코스닥 종목의 값이 TradingView 원본과 다르다.** 결함이 아니라 이탈 4이며, 대조할 때
    파면 안 되는 자리로 `phase-criteria.md` §P12에 적혀 있다. 애초에 원본 대조를 한 적이
    없다(ADR-0046 §승인된 예외).
  - **휴장일 표현은 실측으로 풀렸다** — 적재된 거래일 집합이 `price_daily`와 정확히 같다
    (양쪽 다 1,623일, 어느 쪽에만 있는 날짜 0). 지수는 휴장일에 행을 아예 내지 않는다.
    **공개 지연은 여전히 미검증이다** — 시세와 같다고 가정하고 붙였고, 다르면 위 알림이
    거래일마다 떠서 드러난다.
- **되돌리려면**: `market_index_daily`와 어댑터·수집기·잡. 다만 T-08이 같은 테이블을
  이어받으므로 실제로 되돌릴 일은 지표 쪽뿐이다.
