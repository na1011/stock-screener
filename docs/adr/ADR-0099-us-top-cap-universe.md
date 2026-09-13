---
id: ADR-0099
status: 채택됨
date: 2026-08-31
applies_to:
  - "src/main/java/com/stockscreener/pipeline/datasource/us/UsMarketCapUniverseAdapter.java"
  - "src/main/java/com/stockscreener/pipeline/datasource/StoredShareCounts.java"
  - "src/main/java/com/stockscreener/pipeline/collector/JdbcStoredShareCounts.java"
  - "src/main/resources/db/migration/V39__us_top1000_universe.sql"
  - "src/test/java/com/stockscreener/pipeline/datasource/us/UsMarketCapUniverseAdapterTest.java"
  - "src/test/java/com/stockscreener/pipeline/collector/JdbcStoredShareCountsTest.java"
---

# ADR-0099. 미국 노출 유니버스를 시총 상위 1,000으로 만들고, 시총을 두 소스에서 합성한다

## 결정

미국에서 **스크리닝에 노출할** 유니버스를 `US_TOP1000`으로 새로 만든다. 결정은 다섯이다.

1. **`US_LISTED`를 좁히지 않고 새 코드를 만든다.** 저쪽은 「적재는 넓게」의 쪽으로 남는다.
2. **N을 유니버스 코드에 담는다**(`US_TOP1000`).
3. **시총 = 우리 DB의 주식수 × Alpaca의 최근 종가**다. 주식수는 새 포트
   `StoredShareCounts`로 읽고, 종가는 **200심볼 묶음 조회**로 받는다.
4. **배제축이 넷**이다 — 통화 · SIC(REIT·SPAC) · 이름(토큰 경계) · CIK 중복.
5. **처음에는 비활성이다.** 켜는 것은 이 결정의 범위가 아니다(§결과).

> **사용자가 고른 문장(2026-08-30):** *"국내증시는 '코스피 300, 코스닥 150'으로 우량주만
> 선별했기에 미국 증시도 대칭있게 가자는 제안에 내가 동의를 했었어 … 이후에 백필만
> 1,000종목을 선별해서 하는게 시간 절약도 되고 좋지 않아?"*
> **그에 대한 내 해석 — 갈라 적는다:** 미국 유니버스를 **시총 상위 1,000**으로 두고 적재는
> 전량, 노출과 백필은 그 1,000만 돈다. **사용자가 닫은 것은 N과 방향이고**, 배제축·포트·
> 코드 이름은 내 판단이다(§규칙·§버린 대안).
>
> **2026-08-31에 사용자가 닫은 것 셋**: 하한을 **순위**로 둔다 · 주식수 결손 145를
> **미룬다**(여는 조건은 §결과) · 통화 불일치를 **뺀다**.
> **그중 하나는 내가 준 근거가 거짓이었고 다시 승인받았다** — §버린 대안의 마지막 항목.

## 규칙

- **MUST: 통화 판정을 `IndicatorDataReader.CURRENCY_GATE`와 같은 술어로 한다**(ADR-0094 §규칙).
  그쪽이 `is_latest`를 **안 보고** 그 종목의 **어느 행이든** 불일치면 막으므로 여기도 그렇게
  한다. 최신 기의 행만 보는 술어로 좁히면 **134종목이 새어 들어오고**(2026-08-31 실측)
  그 종목은 **유니버스에는 담기는데 재무 지표가 한 행도 안 난다.**
  - **MUST NOT: 통화 리터럴(`'USD'`)로 바꾸지 않는다.** 판정 축은 「USD인가」가 아니라
    「그 종목이 상장된 시장의 통화와 같은가」다.
- **MUST: 이름축을 토큰 경계로 쓴다.** 부분문자열로 재면 **사업회사를 조용히 지운다** —
  실측으로 후보에서 11종목이 그렇게 잘렸고 거기에 `NFLX`(N-**ETF**-LIX)·
  `CW`(Curtiss-W**RIGHT**)가 있다. **넷플릭스가 빠지고 아무 에러도 안 난다.**
- **MUST: 이름축에 낱말을 더하기 전에 그 낱말의 한계 기여를 잰다** — 「SIC 축이 이미 자른 것을
  빼고 무엇이 남는가」와 「그중 사업회사가 무엇인가」 둘이다.
- **MUST: REIT·SPAC을 이름이 아니라 `sic_code`로 뺀다.** 이름으로 재면 `TRUST`가
  `NTRS`(Northern Trust — 은행)를, `UNIT`이 `ET`·`PAA`(MLP)를 함께 자른다.
- **MUST NOT: 제외 집합을 `67xx` 전체로 넓히지 않는다.** ADR-0092가 **지표 판정불가**를
  Division H 전체로 넓힌 것과 **다른 판단이고 그것이 의도다** — 저쪽은 「틀린 값보다 답 없음」
  이지만 여기서 자르면 **화면에 아예 안 뜬다.** 실측으로 그 나머지에 사업회사 18종목이 있다.
- **MUST NOT: 티커의 점을 배제축으로 쓰지 않는다.** 의결권 클래스 23건이 사라지고 거기에
  `BRK.A`·`BRK.B`가 들어 있다. 중복은 **CIK로** 가른다.
- **MUST: 주식수에 최신성 경계를 둔다(2년).** 없으면 십수 년 전 값이 오늘 종가에 곱해진다 —
  `companyconcept`에서 `V`가 2010-01-27, `BRK`가 2011-04-29로 멈춰 있던 것과 같은 병이다.
- **MUST: 기(期)를 `period_end`로 고르고 개정본을 `revision_no`로 고른다.** `is_latest`는
  「최신 기」가 아니라 **「그 기의 최신 개정본」**이라 `max(shares_outstanding)`으로 뽑으면
  옛 기의 값이 이긴다 — AAPL 실측으로 **약 1.165배** 크게 나온다.
- **MUST: 어댑터가 우리 DB를 볼 때 포트를 거친다**(`StoredShareCounts`). `JdbcTemplate`을 직접
  주입하면 `SecurityRef` javadoc이 못박은 둘(영속성 컨텍스트에 안 닿는다 · 어댑터 테스트가
  DB 없이 성립한다)이 함께 깨진다. `StoredPriceSeries`가 같은 형태다.
- **MUST: 묶음 조회에 커버리지 하한을 건다.** 모르는 심볼은 **응답에서 조용히 빠진다** —
  그 하한이 「한 종목의 결측」과 「묶음 전체의 실패」를 가르는 유일한 자리다.
- **MUST NOT: `US_LISTED` 편입 원장을 후보 목록으로 쓰지 않는다.** 그 유니버스는 비활성이라
  주간 스케줄러가 돌지 않고, 그러면 **그 스냅샷이 마지막으로 갱신된 날짜가 이 유니버스의
  신선도를 조용히 정한다.** 상장 목록은 실행마다 소스에서 받는다(1콜).
- **MUST NOT: `US_TOP1000`과 `US_LISTED`를 함께 켜지 않는다.** 부분집합이라 한 종목이 활성
  유니버스 둘에 동시에 편입되고, `PercentileCalculator`의 UPSERT가 같은 행을 두 번 건드려
  PostgreSQL이 배치를 죽인다(ADR-0036 §규칙). **그 시끄러움이 안전장치다.**

## 검증

```bash
./gradlew test --tests "*UsMarketCapUniverseAdapterTest*" --tests "*JdbcStoredShareCountsTest*" \
               --tests "*CurrencyGateTest*"
```

배제축 넷 · 커버리지 하한 · 부분 목록 거부 · `end`가 맨 날짜가 아닌지 · 기와 개정본 선택 ·
통화 술어가 게이트와 안 갈리는지가 여기서 걸린다.

**대조군으로 확인했다.** 이름축을 부분문자열로 되돌리면 **정확히 한 시험**이 붉고
(「Netflix·Curtiss-Wright가 남는다」), 통화 술어를 `is_latest`로 좁히면 **정확히 한 시험**이
붉는다(「개정 전 판에만 통화가 다르면 여기서도 걸린다」). 나머지는 초록이라 대조가 된다.

```premise 미국 대형주가 설명 없이 유니버스에서 빠지지 않았다
psql -d stock_screener -At -c "
WITH ctrl(t, namepat) AS (VALUES
 ('AAPL','APPLE INC'),('MSFT','MICROSOFT'),('NVDA','NVIDIA'),('GOOGL','ALPHABET'),('AMZN','AMAZON.COM'),
 ('META','META PLATFORMS'),('BRK.B','BERKSHIRE HATHAWAY'),('LLY','ELI LILLY'),('AVGO','BROADCOM'),
 ('JPM','JPMORGAN'),('TSLA','TESLA'),('WMT','WALMART'),('V','VISA INC'),('XOM','EXXON'),
 ('UNH','UNITEDHEALTH'),('MA','MASTERCARD'),('PG','PROCTER'),('JNJ','JOHNSON & JOHNSON'),
 ('COST','COSTCO'),('HD','HOME DEPOT'),('ORCL','ORACLE'),('ABBV','ABBVIE'),('KO','COCA-COLA CO'),
 ('MRK','MERCK'),('BAC','BANK OF AMERICA'),('CVX','CHEVRON'),('PEP','PEPSICO'),('AMD','ADVANCED MICRO'),
 ('ADBE','ADOBE'),('CRM','SALESFORCE'),('NFLX','NETFLIX'),('TMO','THERMO FISHER'),('LIN','LINDE'),
 ('ACN','ACCENTURE'),('MCD','MCDONALD'),('CSCO','CISCO'),('ABT','ABBOTT'),('WFC','WELLS FARGO'),
 ('DIS','WALT DISNEY'),('INTC','INTEL'),('QCOM','QUALCOMM'),('TXN','TEXAS INSTRUMENTS'),
 ('CAT','CATERPILLAR'),('GE','GE AEROSPACE'),('UNP','UNION PACIFIC'),('NKE','NIKE'),('RTX','RTX'),
 ('LOW','LOWE'),('HON','HONEYWELL'),('GS','GOLDMAN SACHS'),('BLK','BLACKROCK'),('DE','DEERE'),
 ('PFE','PFIZER'),('C','CITIGROUP'),('MS','MORGAN STANLEY'),('LMT','LOCKHEED'),('MMC','MARSH'),
 ('PLD','PROLOGIS'),('EQIX','EQUINIX'),('NTRS','NORTHERN TRUST'),('ET','ENERGY TRANSFER'),
 ('TPL','TEXAS PACIFIC LAND'),('CW','CURTISS-WRIGHT'),('RGLD','ROYAL GOLD'),('DLB','DOLBY')),
mem AS (SELECT s.ticker FROM universe_membership m JOIN stock s ON s.id = m.stock_id
        WHERE m.universe_code = 'US_TOP1000' AND m.effective_to IS NULL),
hit AS (SELECT c.t, c.namepat,
          coalesce((SELECT string_agg(s2.ticker, '/') FROM stock s2
                     WHERE s2.market_code = 'US' AND upper(s2.name) LIKE '%' || c.namepat || '%'
                       AND EXISTS (SELECT 1 FROM mem WHERE mem.ticker = s2.ticker)),
                   CASE WHEN EXISTS (SELECT 1 FROM mem WHERE mem.ticker = c.t) THEN c.t END) AS found
        FROM ctrl c),
why AS (SELECT h.t,
          CASE
            WHEN NOT EXISTS (SELECT 1 FROM stock s WHERE s.market_code = 'US' AND s.ticker = h.t)
              THEN '소스에 그 티커가 없다'
            WHEN NOT EXISTS (SELECT 1 FROM financial_statement f JOIN stock s ON s.id = f.stock_id
                              WHERE s.market_code = 'US' AND s.ticker = h.t AND f.is_latest
                                AND f.shares_outstanding IS NOT NULL AND f.shares_outstanding > 0
                                AND f.period_end >= current_date - interval '2 years')
              THEN '주식수결손'
            WHEN (SELECT s.sic_code FROM stock s WHERE s.market_code = 'US' AND s.ticker = h.t)
                 IN ('6798','6770') THEN 'REIT/SPAC'
            ELSE '설명되지 않음'
          END AS reason
        FROM hit h WHERE h.found IS NULL)
SELECT CASE
  WHEN (SELECT count(*) FROM mem) = 0
    THEN '잴 것이 없었다 — US_TOP1000에 편입 종목이 없다'
  WHEN (SELECT count(*) FROM why WHERE reason = '설명되지 않음') > 0
    THEN '대형주 ' || (SELECT count(*) FROM why WHERE reason = '설명되지 않음')
         || '종목이 설명 없이 빠졌다: '
         || (SELECT string_agg(t, ', ' ORDER BY t) FROM why WHERE reason = '설명되지 않음')
         || ' — 배제축(통화·이름·CIK중복·종가)이 사업회사를 먹고 있다. 이 결함은 화면에도 '
         || '로그에도 안 나타나므로 여기가 유일한 신호다'
  ELSE 'OK 대조군 ' || (SELECT count(*) FROM ctrl) || ' 중 ' || (SELECT count(*) FROM hit WHERE found IS NOT NULL)
       || '이 편입됐고, 빠진 ' || (SELECT count(*) FROM why) || '은 전부 설명된다 ('
       || (SELECT string_agg(reason || ' ' || n, ', ' ORDER BY reason)
           FROM (SELECT reason, count(*) n FROM why GROUP BY reason) g) || ')'
END"
```

**이 전제가 「없는 것」을 잰다.** 확정된 상위 30을 눈으로 훑는 절차는 **있는 것**만 보므로
이 결함을 원리적으로 못 잡는다 — 넷플릭스가 빠져도 상위 30은 멀쩡해 보인다. 그래서
**대형주 목록을 박아 두고 「그중 몇이 빠졌고 그 이유가 설명되는가」**를 묻는다.
빠짐 자체는 발화 조건이 아니다 — **설명되지 않는 빠짐**만이 발화한다.

**대조군으로 확인했다**: 1,000위 밖의 정상 종목을 목록에 넣으면 실제로 붉는다(실측).

**MUST: 대조군 티커를 기억에서 만들지 않는다.** 처음에 `MMC`(Marsh &amp; McLennan)를 넣고
「소스에 없다」는 결론을 냈는데, **소스는 그 회사를 `MRSH`로 싣는다**(실측 — SIC 6411 ·
주식수 477,211,268 · 분기 매출 7.4B). 그래서 이 전제는 **이름 패턴을 함께** 들고, 티커가
안 맞으면 이름으로 다시 찾는다. 그러지 않으면 **티커 변경이 「빠졌다」로 신고된다.**

## 배경

미국 축은 재료가 다 붙어 있었고(ADR-0086·0088~0092·0097) 남은 것이 **무엇을 스크리닝
대상으로 삼을 것인가**였다. `US_LISTED`는 8,495종목이라 켜면 지표 하나 없는 종목이 프리셋
결과에 실린다(ADR-0087).

**국내와 대칭이 결정의 근거다.** 국내가 시총 상위 350/2,872 = 12.2%이고 미국이
1,000/8,504 = 11.8%로 두 모수가 같은 층이다.

### 착수 전에 잰 것 중 계획을 실제로 바꾼 것이 셋 (2026-08-31)

**첫째, 이름축의 커버리지.** `alpaca-universe.md`가 이름 접미 분포를 세어 두고
*"단서일 뿐 판정 규칙이 아니다 — 커버리지를 재본 적이 없다"*라고 적어 두었는데, 처음에
그것을 **부분문자열 판정으로 승격**했다. 재보니 후보에서 **11종목**이 잘렸고 7이 매출 1B
이상이었다: `NFLX`·`CW`·`BFAM`·`BHF`·`BRSP`·`BTSG`·`BV`·`BRSL`·`BTLN`·`EDHL`·`FUNC`.
토큰 경계로 바꾸면 셋 다 안 걸린다.

**그 오분류가 소스 계약 문서의 교차표에도 실려 있었다.** 그 표를 만든 탐침
(`UsUniverseAxisProbeLiveTest.labelOf`)이 같은 부분문자열 규칙이라, **탐침을 낱말 경계로 고쳐
다시 뽑았다**(2026-08-31). **CIK 축의 구멍이 161이 아니라 66**이고(`Unit` 단독 110→24 ·
`Right` 단독 12→3) 이름 축의 구멍 153은 그대로다. 상위 1,000 안의 오염도 CIK 중복 124→**98** ·
우선주류 이름 62→**52**로 바뀐다. **수의 정본은 `alpaca-universe.md`이고 그 문서가 세는 규칙을
함께 적는다** — 규칙이 다르면 같은 낱말로 센 표라도 다른 것을 센 것이다.

**둘째, `TRUST`와 `UNIT`의 한계 기여.** 토큰 경계로 고친 뒤에도 그 둘이 남았는데, SIC 축을
먼저 적용하고 다시 세니 **`TRUST`에 걸리는 후보 91 중 74가 SIC 6798**이고 **`UNIT`에 걸리는
60 중 48이 SIC 6770**이었다. 두 낱말이 자르려던 것은 SIC가 이미 자르고, **남는 것이
`NTRS`(은행)와 `ET`·`PAA`·`PAGP`(MLP)뿐**이라 이름축에서 뺐다.

**셋째, `67xx`를 통째로 자를 뻔했다.** SIC 축을 넓히면 간단해 보였는데, `6798`·`6770`을 뺀
나머지 18종목에 `TPL`(Texas Pacific Land)·`RGLD`(Royal Gold)·`DLB`(Dolby)·
`IDCC`(InterDigital)가 들어 있었다.

### 실측 — 만든 뒤 (2026-08-31)

| 무엇 | 값 |
|---|---|
| 상장 목록 | 8,495 (1콜 · 같은 날 US_LISTED에서 9종목 제외) |
| 후보 | **3,737** |
| 편입 | **1,000** · 소요 **17초**(종가 묶음 조회 19콜) |
| 축별 제외 | 통화 172 · REIT/SPAC 358 · 주식수없음 4,359 · 이름 399 · 종가없음 1 · CIK중복 43 |
| 편입 중 `sic_code` 결손 | **5** |
| 대조군 | 65 중 **57 편입** · 빠진 8 = 주식수결손 6 + REIT/SPAC 2 |

**축별 제외 수의 모수가 서로 다르다.** 통화·REIT/SPAC은 **주식수가 있는 행 전체**를 세고,
이름·CIK중복은 **그 뒤에 남은 것**을 센다. 합계로 읽으면 안 된다.

## 버린 대안

- **`US_LISTED`를 상위 1,000으로 좁힌다** — 셋이 막는다. ADR-0086 §규칙의 「적재는 넓게,
  노출은 좁게」가 방침이고, `UniverseReconciler`가 **첫 실행에서 거부하며**(current 8,495에
  제외 88% > 임계 30%), 8,495 편입 원장이 **비가역으로 닫힌다.**
- **N을 코드에서 뺀다**(`US_TOP_CAP`) — 처음에 골랐고 근거가 *"국내는 실제 지수명(200·150)에
  맞춘 것"*이었는데 **재보니 거짓이다.** ADR-0012 §규칙이 금지한 것은 **지수명 차용**이고
  (*"`KOSPI200`이라는 이름에 시총 상위 200을 담지 않는다"*), V8 주석이 *"시총 상위 200은 실제
  코스피200과 80~90%만 겹친다"*로 그 이유를 적는다 — **`KOSPI_TOP200`의 200도 우리가 고른
  값이고 코드에 넣은 것이 의도된 선례다.** 그리고 N을 빼면 N이 바뀌는 날 **같은 코드의 편입
  원장이 두 정의를 담는데** 사후에 구분할 수 없다(ADR-0086이 `SP500` 재사용을 기각한 그
  형태다). 넣으면 N 변경이 새 코드를 강제한다.
- **종가를 종목당 1콜로 받는다** — ADR-0088 §버린 대안이 묶음 조회를 기각해 뒀고 그 근거가
  둘인데 **여기에는 둘 다 안 걸린다.** ① *실패 격리의 단위가 종목이 아니게 된다* →
  `UniverseRebalanceJob`은 **일부러** 종목 격리를 안 쓴다(유니버스는 하나의 정합적 스냅샷).
  ② *모르는 심볼이 조용히 빠진다* → 커버리지 하한이 막는다. 그 기각은 `PriceDataSource`에
  걸린 것이고 이 어댑터는 `UniverseDataSource`다. 묶지 않으면 실행당 **3,737콜**이다.
- **어댑터에 `JdbcTemplate`을 직접 준다** — 더 짧지만 `SecurityRef` javadoc의 둘이 깨진다.
- **`AlpacaUniverseAdapter.fetchConstituents`를 불러 목록을 얻는다** — 티커만 담은
  `UniverseSnapshot`이 와서 **이름으로 증권 종류를 가를 수 없다.** 그래서 자산 목록 자체를
  패키지 접근으로 냈다.
- **`companyconcept`로 주식수 결손 145를 메운다** — **2026-09-14에 닫혔다(사용자 결정).**
  경위와 재개 조건은 §미룬 문에 있다. 아래는 그때까지의 근거이고, **마지막 문장의
  「남는 실질」이 이번 전수로 0이 됐다.** 2026-08-31에는 사용자 결정으로 미뤄 뒀다:
  처음 올린 근거가 *"메워도 큰 이름이 안 돌아온다"*였는데 **거짓이었다** — 문서 실측은
  145 중 **107이 2년 이내 값 있음**이고 낡은 것은 10뿐이며 `V`·`BRK`가 그 10에 속하는 **두
  사례**다. **근거를 갈아 다시 승인받았다.** 새 근거는 잰 값이다: 돌아오는 107이 두 무리로
  갈리는데, **재무 0행 무리**(`AMZN`·`JNJ`·`XOM`·`CSCO`·`TSM` …)는 유니버스 자리만 얻고 재무
  지표는 전부 판정불가이고, **「재무는 있는데 주식수만 없는」 164종목**은 매출 1B 이상이
  12뿐이며 그 위쪽이 `KOF`(통화 게이트)·`BRK.A/B`(2011로 낡음)·`BIDU`(ADR)·`V`(2010으로
  낡음)라 **남는 실질이 `STZ`·`IMKTA`·`GEF` 정도**다. 그리고 **ADR 판별축을 아직 안 쟀다** —
  없이 열면 `TSM`이 10배로 들어온다. 여는 조건은 §결과에 있다.
- **통화 불일치를 환산해 담는다** — ADR-0094 §버린 대안이 이미 기각했다(환율 시계열이 없고,
  새 외부 소스·저장 축·시점별 환율 선택 규칙 셋이 함께 붙는다).

## 결과

**쉬워지는 것**: 미국에 노출 모집단이 생겼고 국내와 축이 같다(top-N). 실행당 **20콜**이고
17초다. 잡을 새로 만들지 않았다 — `--job=universe --universe=US_TOP1000`이 그 명령이다.

**어려워지는 것**

- **이 유니버스는 「시총 상위 1,000」이 아니라 「우리가 재무를 파싱해 낸 종목 중 시총 상위
  1,000」이다.** 후보가 우리 DB의 주식수에서만 나온다 — **`AMZN`·`JNJ`·`CSCO`의 재무 0행은
  ADR-0124가 닫았고**, 남은 것은 `XOM`(재무 0행인데 실패 기록조차 없어 원인이 다르다)과
  `V`·`BRK.A/B`(재무는 있고 주식수가 전량 NULL)뿐이다. 국내에도 같은 형태의 배제가 있지만
  (`MarketCapUniverseAdapter.toCandidate`) **국내는 상위권에 안 걸렸고 미국은 걸린다.**
  ADR-0012 §배경의 IMPORTANT(이름에 다른 내용을 담지 않는다)가 걸리는 자리이며,
  **화면이 이 유니버스를 설명할 때 그 조건을 말해야 한다.**
- **주식수와 종가의 기준 시점이 다른 것은 닫았다 — 재보니 편입을 실제로 바꿨다.**
  주식수는 보고 종료일, 종가는 오늘이라 그 사이 분할이 있으면 시총이 배수만큼 틀린다.
  실측(2026-08-31)으로 편입 1,000 중 **5종목**이 그 상태였고, **`BYND`는 1:30 병합 전
  주식수라 시총이 7.15B로 30배 잡혀 상위 1,000에 잘못 들어왔다**(실제 약 0.24B ·
  경계가 5.8B 언저리다). 나머지 넷(`IESC`·`MLI`·`MNST`·`SNEX`)은 반대 방향이라 편입 자체는
  옳았고 순위만 낮았다. **`JdbcStoredShareCounts`가 `APPLIED` 사건의 비율을 곱해 옮긴다** —
  ADR-0096이 지표 계층에 세운 것과 같은 원리이고 자리만 다르다.
  - **순환이 하나 남는다 — 원장이 유니버스보다 먼저다.** 원장은 편입 종목을 대상으로
    채우므로 **새로 편입될 종목은 그 시점에 원장이 비어** 보정을 못 받는다. 창이
    「최신 보고 종료일 이후」라 최대 한 분기이고 다음 리밸런싱이 수렴시킨다.
    **유니버스를 정하려고 유니버스 밖 전 종목의 원장을 채우는 것이 그 대가라 안 푼다.**
- **ADR 비율 오차가 남아 있다.** 발행사 주식수를 ADS 종가에 곱하면 비율만큼 틀리는데, 지금
  가진 유일한 신호가 이름의 `DEPOSITARY`다 — `TSM`·`HSBC`·`NVS`에는 그 낱말이 없다
  (`sec-edgar-financial.md`). 방향은 안전한 쪽(배제)이지만 **잡히는 것은 일부다.**
- **SIC가 없는 종목은 어느 축도 못 본다.** 편입 중 `sic_code` 결손이 **5종목**인데 전부
  BDC·비상장은행 계열이다(`ARCC`·`MAIN`·`OBDC`·`OTF`·`CBC`). **투자회사인데 이름에도 걸리는
  낱말이 없어** 사업회사로 스크리닝에 올라온다 — ADR-0086이 *"잘못 분류된 ETF는 사업회사로
  스크리닝에 올라오고 어떤 에러도 내지 않는다"*로 적은 것과 같은 자리다. **지금은 유니버스가
  비활성이라 안 닿는다.** **`company-profile`로는 못 채운다 — 재봤다**: 다섯 전부
  `submissions`가 `sic`을 **빈 값으로 준다**(2026-08-31 실측, 어댑터가
  *"submissions에 sic이 비어 있다"*로 종목마다 남긴다). ADR-0092가 「`sic`이 빈 값으로 옴
  423」으로 세어 둔 집합에 이 다섯이 들어 있다. **그러므로 SIC 축은 이 다섯을 영원히 못
  본다** — 여는 길은 다른 축(예: `entityType`)이고, 그것은 ADR 판별축과 같은 자리라
  **미룬 문 1번을 열 때 함께 잰다.**

- **같은 날 두 번째로 다시 만들면 제약에 걸린다.** `UniverseReconciler`가 제외를
  `effective_to = 기준일`로 닫는데 그날 편입된 행은 `effective_from`이 같아
  `ck_membership_period`(0일 구간 금지)에 걸려 **그 실행이 통째로 되돌아간다**
  (실측 — 잡은 `PARTIAL_SUCCESS`로 끝난다). 계산을 고쳐 같은 날 다시 만들어야 하면
  **빠질 행을 먼저 지운 뒤 돌린다** — 그러면 제외가 0이 되어 제약에 안 걸린다.
  **MUST NOT: 그것을 「편입됐다가 빠졌다」로 원장에 남기지 않는다** — 계산이 틀렸던 것이지
  일어난 사실이 아니고, 원장은 그날 무엇을 믿었는가가 아니라 **무엇이 편입이었는가**를 적는다.

- **주간 리밸런싱이 이 유니버스를 돌기 시작한다**(켠 뒤). 그때 신규 편입 종목은 봉 0·업종
  NULL로 들어오고 배치는 SUCCESS다 — `--job=universe` 뒤에 `--tickers` 적재와
  `company-profile`을 잇는다(`batch-load` skill).

### 켜기 전에 닫아야 하는 것 — 다섯

**MUST: 이 유니버스를 활성으로 올리는 것은 별도 작업이다.** 아래 다섯이 **지금은 안 닿아서
조용한 것**이고, `is_active = true` UPDATE 하나가 다섯을 동시에 연다. 각각 무엇이 읽고
무엇이 틀리는지를 적는다.

1. **프리셋에 시장 축이 없다.** `ScreeningRepository`가 `universeCode`가 null이면
   `inAnyActiveUniverse`로 떨어지고 활성 프리셋 여덟이 전부 그 상태다. `MARKET_CAP` 임계가
   **원화 리터럴**인데(`LYNCH_STALWART` 6.773e12 · `LYNCH_FAST_GROWER` 2.616e12 ·
   `BUFFETT_QUALITY` 1e12, 셋 다 필수) **ADR-0097의 재무 폴백이 미국에 USD 원값
   `MARKET_CAP`을 실제로 만든다.** `TURNOVER_60` 부재가 둘을 우연히 배제하지만
   **`LYNCH_STALWART`에는 그 완충이 없다.** **ADR-0072의 premise가 이것을 이름으로 막는다** —
   *"시장별 프리셋을 가르기 전에는 실행하지 않는다"*. `ScreenPreset.universeCode`가 코드
   하나라 「국내」(유니버스 둘)를 표현할 수 없다.

   **축은 시장 목록이 아니라 「임계의 통화」다(2026-08-31 결정).** 프리셋이 깨지는 원인이
   `MARKET_CAP` 임계가 **원화 리터럴**이라는 것이므로 원인과 같은 축으로 묶는다.
   `screen`에 임계 통화를 두고 `ScreeningRepository`가 **그 통화를 쓰는 시장의 활성
   유니버스로** 좁힌다(`market.currency_code`가 이미 KOSPI·KOSDAQ = KRW, US = USD다).
   **시장 코드로 하면 국내를 둘로 나열해야 하고 시장이 늘 때마다 그 목록을 고쳐야 한다** —
   고치지 않은 프리셋은 조용히 새 시장까지 판정한다. 통화로 하면 **임계가 없는 프리셋은
   비워 두어 양쪽에서 돈다**는 것도 같은 필드로 표현된다.
   닿는 곳: `screen` 마이그레이션 · `ScreenPreset` · `PresetCatalog`(활성 여덟에 `KRW`) ·
   `ScreeningRepository` · `wire-contract.json`(ADR-0020) · 프론트.
2. **「전체」 화면의 기준일이 미국 날짜가 된다.** `MarketScope.forScreening(null)`이 활성
   유니버스의 시장 집합을 쓰고 `IndicatorCalendar.latestIndicatorDate`가 **시장이 여럿이면
   최댓값**이다(그 javadoc이 *"여러 시장을 한 기준일로 묶는 호출부는 그 시장의 종목을
   판정하지 않아야 한다"*로 적어 뒀다). 보존 창 안에 국내 휴장·미국 개장이 14일이라 그 14일에
   **국내 프리셋이 전부 0건**이고 HTTP 200이다. **1번으로는 안 닫힌다** —
   `ScreeningService.resolveSource`가 즉석 조건을 **언제나 `universeCode = null`**로 만든다.
   범위는 ADR-0095 §결과가 예약해 뒀다.
3. **미국에 시장지수가 없다.** `ck_market_index_code CHECK (index_code IN ('KOSPI','KOSDAQ'))`라
   **마이그레이션 없이는 한 행도 못 넣는다.** `BETA_104W`·`EXCESS_RETURN_26W`·
   `EXCESS_RETURN_52W`가 미국에서 값이 안 난다 — **적재로 해결되지 않는 축이다**
   (`alpaca-bars.md`가 그 넷을 센다).
4. **미국 일일 갱신에 스케줄이 없다.** `PipelineScheduler.runDailyUpdate` javadoc:
   *"미국 시장은 이 시각을 쓸 수 없다 — 미국 장은 한국 시각 다음 날 오전 5~6시에 닫는다.
   붙일 때 별도 스케줄이 필요하다."* 리밸런싱의 `asOf`도 국내 달력에서 나온다.
5. **손으로 돌리는 잡 둘이 부분 실패를 쌓는다.** 거버넌스·전체재무제표 어댑터가 미국에 없어
   `governance-load`·`full-statement-refill`이 편입 종목 수만큼 실패를 남긴다. **스케줄러에는
   없으므로 켜는 것 자체가 배치를 죽이지는 않는다**(`@Scheduled`는 일일 갱신·주간 리밸런싱
   둘뿐, 실측). 그리고 `company-profile`의 기본 대상이 넓어진다 — 이 유니버스 안의
   `sic_code` 결손은 **5종목**이다(실측).

### 미룬 문 — 2026-09-14에 닫혔다 (사용자 결정)

> **사용자가 고른 문장:** *"닫는다 + 재개 조건을 적는다"*.
> **그에 대한 내 해석 — 갈라 적는다:** 「지금 잰 근거로는 안 연다」이고 **「영원히 안 한다」가
> 아니다.** 재개 조건과 재는 명령이 아래에 있다.

**닫는 근거는 「얻는 것이 0」이다**(2026-09-14 · 660콜 전수). 결손 165종목에 후보 태그 넷을
전부 물으면 200이 오는 것은 39인데 **2년 이내 값은 16뿐이고 그 열여섯이 전부
SPAC·유닛·워런트**다(`Acquisition Corp` · 매출 없음 · **`US_TOP1000` 편입 이력 0**).
아래가 든 **「실질 여섯」은 하나도 안 메워진다** — `V` 최신 2010-01-27 · `PLNT` 2015-06-30 ·
`WTTR` 2018-03-31이고 `STZ`·`IMKTA`·`GRDN`은 네 태그 전부 404다. 수는
`sec-edgar-financial.md` §주식수 결손 165종목에 전수로 물었다가 정본이다.

**여는 쪽의 대가도 함께 적는다** — 그 열여섯이 주식수를 얻으면 **SPAC·워런트·유닛이 시총
후보가 된다.** ADR-0086이 *"잘못 분류된 ETF는 사업회사로 스크리닝에 올라오고 어떤 에러도
내지 않는다"*로 경고한 자리와 같다.

**재개 조건 — 둘 중 하나가 서면 다시 연다.**
1. **소스가 채운다.** 위 16이 사업회사 쪽으로 바뀌거나 「실질 여섯」에 2년 이내 값이 생기면
   경제가 달라진다. **재는 것은 한 명령이고 약 6분이다**(660콜):
   `./gradlew liveTest --tests "*SecEdgarConceptProbeLiveTest.probeConceptVersusFacts*"
   -Dprobe.tickers=<결손 티커> -Dprobe.compareFacts=false`
   — `compareFacts=false`가 전수의 조건이다(`companyfacts`가 종목당 평균 3.4MB).
2. **결손의 다른 원인이 열린다.** 이 문은 **주식수 폴백**만 닫은 것이다 — `GEF`처럼 네 태그가
   전부 404인 무리는 **소스가 개념 자체를 안 내는** 별개 갈래이고 여기서 안 다룬다.

**MUST NOT: 이 결정을 「②복수 클래스·③ADS 비율도 닫혔다」로 읽지 않는다** — 그 둘은 애초에
이 문에 안 걸린다는 것이 같은 날 실측으로 확정됐다(`companyconcept`가 `companyfacts`와 같은
팩트를 주고 축이 붙은 팩트를 안 준다 · ADS 비율 개념은 16종목 중 `BABA` 하나만 낸다).

**2026-08-31에 절반이 열렸다.** ADR-0101이 「같은 기간을 달리 적은 창」을 접어 **88종목이
회수**됐고(`CSCO`·`UPS`·`NTAP`·`BKR`·`FITB` 등, 거래대금 1억 달러 이상 14) 그중 **17이 상위
1,000에 들었다.** 남은 문은 하나다.

**남은 문이 2026-09-04에 닫혔다 — ADR-0124.** 재무 0행 145종목의 원인이
**`labelOf`의 회계연도 배정 하나가 아니라 셋**이었고, 그중 116종목은 **저장되지도 않을
옛 연도의 충돌로 죽고 있었다**(판정이 적재 창 밖에까지 걸려 있었다).

- **`companyconcept` 주식수 폴백과 ADR 판별축이 필요 없어졌다** — 그 종목들이 주식수만이
  아니라 재무째 돌아온다. **다만 그 폴백은 원래 사용자 결정으로 미뤄 둔 것**이므로
  (§버린 대안) 되살릴 이유가 생겨도 그 결정부터 다시 연다.
- **`V`·`BRK.B`는 이 문과 무관하다.** 그 둘은 재무가 있고 `shares_outstanding`이
  **전량 NULL**이라 A 145에 없다 — 여기 「주식수 결손 145」와 ADR-0101의 「A 145」는
  **수만 같고 집합이 다르다.**

**2026-09-07 재측정 — 여는 값과 대가를 지금 수로 놓는다.** 미국 재무 보유 5,128종목 중
주식수가 전량 NULL인 것이 **165**다(문서의 「145」와 다른 집합이고 수도 늘었다).

| 축 | 수 |
|---|---|
| 금융업 SIC(60~67) — 들어와도 재무 지표가 **판정불가** | **114 (69%)** |
| 매출 1B 이상 | **21** (이 문서가 적은 12에서 늘었다) |
| 매출 10B 이상 | 9 |
| 이름에 `ADS`·`Depositary` | 4 |
| 클래스주(`.` 티커) | 6 |

**21을 실명으로 열면 실질이 여섯이다.** `BRK.A`/`BRK.B`는 `SIC:63`이라 들어와도 지표가 없고,
`KOF`는 통화 게이트가 이미 막으며, `BIDU`·`GHG`·`FENG`는 ADR이고, `GEF`/`CRD`/`HVT`/`FWON`은
클래스 중복이며 `SUN`은 MLP다. 남는 것은 **`V`·`STZ`·`PLNT`·`IMKTA`·`WTTR`·`GRDN`**이고
그중 대형주는 `V` 하나다. 유니버스 진입선은 **시총 0.32B**, 중앙값 17.29B, 편입 980이다.

**SEC EDGAR는 키를 발급하지 않으므로 자격증명은 막는 것이 아니다** — 이미 연결돼 있고
`daily-quota: 0`이다. 막는 것은 **ADR 판별축**이고, 그것 없이 열면 이 문서가 경고한 대로
`TSM`이 10배로 들어와 **ADR-0126의 출력 축 오염이 늘어난다.** 즉 순서는 판별축이 먼저다.

**화면이 조건을 말해야 한다는 위 요구는 아직 못 지켰는데, 안 지킨 것이 아니라 담을 자리가
없어서다**(2026-09-07 실측). 유니버스 정보가 지나가는 네 층에 설명 필드가 하나도 없다 —
`universe` 테이블(`code`·`name`·`market_code`·`is_active`·`created_at`) ·
`StockReader.UniverseRow` · `ScreeningResponse.UniverseInfo` · 프론트 `ScreeningUniverseInfo`.
**화면이 말할 수 있는 것은 이름과 편입 수뿐이다.** 이름에 담는 것은 ADR-0012가 막는다.

**여는 값이 지금 싼 이유**: 유니버스가 비활성이라 잃는 것은 **편입 이력 며칠**뿐이고
지표·화면·프리셋에 안 걸린다. 나중에 열고 `--job=universe`를 다시 돌리면 편입된다.
**비가역인 것은 하나** — 여는 날까지의 편입 이력에 그 종목들이 없다.
**MUST NOT: 그것을 「사후 복원이 불가능하다」로 읽지 않는다 — 2026-09-10에 거짓으로 판명됐다.**
ADR-0135가 보존 창의 편입을 소스에서 복원했고(US 재현율 0.987) 미국이 못 담는 것은
**폐지 종목 22**뿐이다. 비가역인 것은 **관측 원장의 그 며칠**이지 「그날의 상위 N」이 아니다.

**되돌리려면**: `UsMarketCapUniverseAdapter`·`StoredShareCounts`·`JdbcStoredShareCounts`를
지우고 V39를 되돌린다. 다만 `universe_membership`에 쌓인 구성은 **되돌리는 순간 영구히
사라진다.**
