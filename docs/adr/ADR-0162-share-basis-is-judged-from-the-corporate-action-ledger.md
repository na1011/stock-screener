---
id: ADR-0162
status: 채택됨
date: 2026-09-23
applies_to:
  - "src/main/java/com/stockscreener/pipeline/collector/ShareBasisDeriver.java"
  - "src/main/java/com/stockscreener/pipeline/collector/JdbcStoredShareCounts.java"
  - "src/main/java/com/stockscreener/indicator/ShareBasis.java"
  - "src/main/java/com/stockscreener/indicator/calculator/FinancialMath.java"
  - "src/main/resources/db/migration/V57__financial_share_basis.sql"
---

# ADR-0162. 주식수의 기저를 기업행위 원장으로 판정하고, 크기를 모르면 값을 안 낸다

## 결정

**시가총액 = 주식수 × 종가의 두 기저가 어긋난 판을 원장으로 판정해 `financial_statement`에
싣고, `FinancialMath.sharesOf`와 `JdbcStoredShareCounts`가 둘 다 읽는다.** 어긋난 크기를 알면
곱해 맞추고(`SHIFTED`), **모르면 주식수를 안 낸다**(`UNRESOLVED`).

**ADS 비율 자체를 구하는 것은 진짜 한계다**(아래 §배경). 이 결정이 하는 것은 **틀린 값을
주장하지 않는 것**이지 맞는 값을 만드는 것이 아니다.

## 규칙

- **MUST: 선언된 주식 교환만 센다.** `ratio`를 약분했을 때 **분자·분모 중 작은 쪽이 10 이하**인
  사건만이 주식수를 바꾼다. 근거는 **주주가 온주로 교환받는다**는 것이다 — `1000-for-1973`은
  참여에 1,000주가 필요해 어떤 회사도 선언하지 않는다. 그런 값은 **분사·역합병의 가격계수**가
  분할 배열에 실린 것이다.
  - **임계가 가르는 것은 `[1.10/1.5, 1.5/1.10]`(약 0.733 ~ 1.364) 밖의 비율뿐이다.** 그 안의 사건은
    관측이 「불변 띠」(1.10) 안이면 「따라감 띠」(1.5)에도 들어 **계열 불변으로 판정될 수 없다** —
    들든 빠지든 `SHIFTED`·`UNRESOLVED`가 같고, 빠지면 `UNRESOLVED`의 시작일을 앞으로 끌지 않는다.
    **주식배당은 선언된 교환인데 작은 쪽이 10을 넘는다**(`CBSH` 5% = `21-for-20` · 작은 쪽 20) —
    「온주 교환」 근거는 이 띠 안에서는 서지 않지만 위 이유로 판정에 닿지 않는다.
  - **MUST: 임계라고 적는다.** 「소스의 선언이라 임계가 아니다」는 거짓이다 — `DELL`의
    `old_rate=1000`·`new_rate=1973`은 **둘 다 정수**다.
  - **MUST NOT: 「양쪽이 정수인가」로 재지 않는다.** 3-for-2가 `2→3`과 `1→1.5` **두 표기**로
    온다(실측: 전자 19건 · 후자 5건). 정수 시험은 정당한 분할 다섯을 죽인다.
- **MUST NOT: 「판정불가 사건이 있으면 UNRESOLVED」로 쓰지 않는다.** 판정불가만 있는 9종목이
  전부 정상이다(`CVNA` $48B · `IR` $28B · `QXO` $13B · `BMNR` $17B · ABTC · MODD · ORKA ·
  PRAX · VOR). **「계열 불변」이 기저가 어긋났다는 양성 증거**이고 판정불가는 그 크기를 못 세게
  만드는 것이라, **둘이 함께**일 때만 값을 안 낸다.
- **MUST: 배수를 판마다 단다 — 종목 상수로 두지 않는다.** 상수면 분자·분모에 같이 곱해져
  **약분되어 한 톨도 안 고쳐진다**(실측: `AMRN` `SHARES_GROWTH_3Y` `+1,958.36%` · 참값 약 `+2.9%`).
- **MUST NOT: 부분 보정으로 갈음하지 않는다.** `AKTX`에 정당화되는 사건 하나만 걸면 1,565B가
  **39.1B · 순위 279위**가 된다 — 여전히 약 1,000배 틀린데 8위일 때와 달리 **아무도 못 본다.**
- **MUST: 계열이 뒤에서 따라잡으면 이 축이 아니다.** 표지일이 공시일보다 앞선 한 판의 문제이고
  **ADR-0133이 그 축의 두 처방을 이미 순손실로 실측했다**(`from` 당일 포함은 108칸을 깨고,
  표지 기준일 앵커는 활성 창 6종목을 깬다). `LNZA`가 그 자리다 — 저장 시총이 2025-11-18
  `3,073,760,966` → 11-19 `30,116,404`이고, **셀 247 중 결손은 45뿐이고 202는 이미 정상**이라
  종목 상수를 걸면 45를 고치고 202를 깬다.
- **MUST: 두 읽기가 같은 컬럼을 읽는다.** `sharesOf`만 고치면 지표는 맞는데
  `JdbcStoredShareCounts`가 도는 **유니버스 순위는 그대로 오염된다.**
  - **MUST: `UNRESOLVED`를 바깥 SELECT에서 버린다.** 안쪽 `WHERE`면 `DISTINCT ON`이 **옛 판으로
    물러나는데** 그 판도 같은 기저다.
  - **MUST NOT: `sharesOf`에서 `continue`로 옛 판에 내려가지 않는다.** 같은 이유다.
- **MUST: 재무나 기업행위를 쓴 잡 뒤에는 다시 판정한다.** 새 판은 판정이 NULL로 들어오고
  NULL은 `OK`로 읽히므로, 안 돌리면 `UNRESOLVED` 종목이 **조용히 값을 되찾고** 다음 리밸런싱이
  그 시총으로 순위를 매긴다. **연쇄를 잡의 반환 지점이 아니라 호출 지점에 건다** — 세 잡이 각각
  여러 곳에서 return 한다. **두 경로에 다 건다**: `financial-refill`·`full-statement-refill`은
  실측으로 SCHEDULER 발화가 **0회**이고 89+39회가 전부 MANUAL이다.
- **MUST NOT: 두 컬럼을 `FinancialWriter.Amount`에 더하지 않는다.** 더하면 `managedColumns`가
  관할로 판정해 `fillMissing`의 `toClear`가 유도 결과를 지운다 — 2026-09-22에 같은 모양으로
  KOSDAQ 지표 975건이 날아갔다.
- **MUST NOT: `old_rate`/`new_rate`를 저장하려 하지 않는다.** 저장된 `ratio`가
  `numeric(18,10)`이라 **선언된 유리수가 정확히 복원된다**(실측: 탐침 rate와 148쌍 대조 ·
  판정 불일치 **0**). 그리고 원장에 `OBSERVED`가 **0행**이라 `CorporateActionLedger.record`가
  전부 `IGNORED`이므로 **재적재로는 한 셀도 안 채워진다.**

## 검증

```premise 기저를 확정 못 한 종목이 시가총액 값을 갖고 있지 않다
# MUST: 모집단을 편입이 아니라 **판정이 붙은 종목**으로 잡는다. 편입으로 잡으면 리밸런싱이
# 돌기 전까지 계속 붉고, 상시 붉은 펜스는 무시된다 — 이 결정이 거는 불변식은 「값을 안 낸다」이지
# 「명부에서 빠졌다」가 아니다(명부는 그 결과이고 주에 한 번 따라온다).
psql -d stock_screener -At -c "
WITH u AS (SELECT DISTINCT f.stock_id FROM financial_statement f
           WHERE f.share_basis_status = 'UNRESOLVED'),
     bad AS (SELECT DISTINCT s.ticker FROM indicator_value iv
             JOIN u ON u.stock_id = iv.stock_id JOIN stock s ON s.id = iv.stock_id
             WHERE iv.indicator_code = 'MARKET_CAP')
SELECT CASE
  WHEN (SELECT count(*) FROM financial_statement WHERE share_basis_status IS NOT NULL) = 0
    THEN '잴 것이 없었다 — 기저 판정이 한 행도 없다(share-basis-derive 를 안 돌렸다)'
  WHEN (SELECT count(*) FROM u) = 0
    THEN 'OK 기저 미확정 종목이 없다'
  WHEN (SELECT count(*) FROM bad) = 0
    THEN 'OK 기저 미확정 ' || (SELECT count(*) FROM u) || '종목이 시총 값을 안 낸다'
  ELSE (SELECT count(*) FROM bad) || '종목이 기저 미확정인데 시총 값이 남아 있다: '
       || (SELECT string_agg(ticker, ' ' ORDER BY ticker) FROM bad)
       || ' — 백필이 옛 행을 안 걷었거나 sharesOf 의 UNRESOLVED 갈래가 깨진 것이다' END"
```

```premise 클래스 축이 숨긴 판의 종목이 최신일 시가총액 값을 갖고 있지 않다
# 미국 관문(V70) 뒤 — 판정 표의 숨김도 「값을 안 낸다」의 한 갈래다(sharesOf가 상태로 막는다 · 앞 판으로 안 내려간다).
# 관문 전에는 판정 표가 없고 이 축이 없던 때와 같으므로 참이다.
cls=$(psql -d stock_screener -At -c "SELECT to_regclass('public.us_share_class_effective') IS NOT NULL")
if [ "$cls" != "t" ]; then echo "OK 관문 전 — 클래스 축 판정 표가 아직 없다(V70 미적용)"; exit 0; fi
psql -d stock_screener -At -c "
WITH t AS (SELECT max(trade_date) d FROM indicator_trade_date WHERE market_code = 'US'),
chosen AS (SELECT DISTINCT ON (f.stock_id) f.stock_id, e.hidden
           FROM financial_statement f JOIN us_share_class_effective e ON e.financial_statement_id = f.id
           JOIN stock s ON s.id = f.stock_id AND s.is_active
           WHERE f.published_at <= (SELECT d FROM t) AND (e.hidden OR e.shares > 0)
           ORDER BY f.stock_id, f.period_end DESC, f.published_at DESC, f.revision_no DESC),
hid AS (SELECT stock_id FROM chosen WHERE hidden),
bad AS (SELECT DISTINCT s.ticker FROM indicator_value iv JOIN hid ON hid.stock_id = iv.stock_id
        JOIN stock s ON s.id = iv.stock_id
        WHERE iv.indicator_code = 'MARKET_CAP' AND iv.trade_date = (SELECT d FROM t))
SELECT CASE
  WHEN (SELECT count(*) FROM financial_share_class_filing) = 0
    THEN '잴 것이 없었다 — 판정 표가 비었다(관문을 켠 실행으로 share-basis-derive를 안 돌렸다)'
  WHEN (SELECT count(*) FROM hid) = 0 THEN 'OK 최신일에 클래스 축이 숨긴 종목이 없다'
  WHEN (SELECT count(*) FROM bad) = 0 THEN 'OK 최신일 숨김 ' || (SELECT count(*) FROM hid) || '종목이 시총 값을 안 낸다'
  ELSE (SELECT count(*) FROM bad) || '종목이 숨긴 판인데 최신일 시총 값이 있다: '
       || (SELECT string_agg(ticker, ' ' ORDER BY ticker) FROM bad)
       || ' — sharesOf의 숨김 갈래가 깨졌거나 화해가 옛 행을 안 걷었다' END"
```
```premise 판별자의 임계가 아직 빈 구간에 앉아 있다
psql -d stock_screener -At -c "
WITH r AS (SELECT DISTINCT c.ratio FROM corporate_action c JOIN stock s ON s.id = c.stock_id
           WHERE c.status = 'APPLIED' AND s.market_code = 'US' AND c.ratio <> 1
             -- 판별자가 판정을 못 바꾸는 띠는 뺀다(§규칙 — 주식배당 CBSH 21-for-20이 여기 든다)
             AND NOT (c.ratio >= 1.10 / 1.5 AND c.ratio <= 1.5 / 1.10)),
     m AS (SELECT ratio, (SELECT min(k) FROM generate_series(1, 200) k
                          WHERE abs(ratio * k - round(ratio * k)) <= ratio * k * 1e-8
                             OR abs(k / ratio - round(k / ratio)) <= (k / ratio) * 1e-8) AS small
           FROM r)
SELECT CASE
  WHEN (SELECT count(*) FROM r) = 0 THEN '잴 것이 없었다 — 미국 APPLIED 기업행위가 없다'
  WHEN (SELECT count(*) FROM m WHERE small > 10 AND small <= 96) = 0
    THEN 'OK 작은 쪽이 3~96인 사건이 없다 / 통과 '
         || (SELECT count(*) FROM m WHERE small <= 10) || ' · 기각 '
         || (SELECT count(*) FROM m WHERE small IS NULL OR small > 10)
  ELSE '작은 쪽이 3~96인 사건이 ' || (SELECT count(*) FROM m WHERE small > 10 AND small <= 96)
       || '건 생겼다 — 임계 10이 더는 빈 구간에 안 앉는다. 분포를 다시 재고 이 ADR §결과를 고친다' END"
```

```bash
./gradlew test --tests "*ShareBasisDeriverTest*" --tests "*PipelineSchedulerChainTest*"
./gradlew bootRun --args='--spring.profiles.active=bootstrap --job=share-basis-derive --market=US'
```

## 배경

**시가총액 = 주식수 × 종가인데 그 둘의 기저가 다른 종목이 실재한다.** `AKTX`(Akari Therapeutics
plc ADS)는 SEC 표지가 **보통주** 155,758,529,533주를 주고 시세는 **ADS당** 가격이라, 저장 시총이
**1,565B로 미국 8위**에 앉았다(2026-09-22 · TSLA 1,482B와 MU 1,179B 사이. 회사가 밝힌
공개유동가치는 **$15.0M**이다). `AMRN`은 같은 모양의 20배이고, 둘 다 2025-09-12부터 `US_TOP1000`에
연속 편입돼 미국 백분위·업종상대의 모집단 전체를 그 위에서 돌렸다.

**어느 축도 이것을 안 신고했다.** ADR-0126의 하루 100배 펜스는 `AMRN`의 사건이 **보존 창 밖**이라
원리적으로 못 보고 `AKTX`는 **42.7배라 임계 미달**이다(그 문서의 *"AKTX를 원리적으로 못 본다"*는
거짓이다 — 창 안에 42.7배 계단이 실재한다). ADS 이름 펜스는 `AKTX`를 **이름으로 내면서 `OK`라고
말한다.** 순위 가드는 `net_income/eps` 함의값과 대조하는데 `AMRN`은 **그 둘이 일치한다** —
원장이 자기 안에서 정합하기 때문이다.

**ADS 비율 자체는 소스가 안 준다**(2026-09-23 전수). 전용 태그
`dei:EntityListingDepositoryReceiptRatio`가 팩트 캐시 **1,214종목 중 1종목**(`GTES`, 마지막 보고
2019)에만 있고, 종목명 텍스트는 ADS 표기 **273종목 중 74**만 비율을 적으며 표현이 네 형태
이상이고(`FORTY`는 회사명의 연도 `(1985)`가 비율로 뽑힌다) **`AMRN`은 ADS 표기조차 없다.**
Alpaca 자산 마스터에는 증권 유형 필드가 아예 없다. **그래서 「비율을 구해 고친다」는 일반 규칙이
원리적으로 안 선다** — 사용자 기준(*"특정 기업만을 위한 로직이 필요하면 진짜 한계"*)의 그 자리다.

## 버린 대안

| 대안 | 왜 버렸나 |
|---|---|
| **공개유동가치로 검산해 배수를 검증한다** | **앵커가 판정 대상의 93.4%에 없다** — `dei:EntityPublicFloat`는 10-K 표지 사실인데 편입 1,000 중 최신 판이 FY인 것은 **66**뿐이고 `AKTX`의 오염된 판은 Q2다. 게다가 **시총 < float라는 원리적 불가능이 편입 859 중 98종목(11.4%)**이고 `ONTO`·`TTMI`는 float가 **$4.8조·$4.2조**로 발행사가 단위를 1,000배 틀렸다 |
| **공개유동가치 비에 밴드를 씌워 결함을 고른다** | **단일 임계가 원리적으로 없다** — 정상인 `PTCT`(1,155)·`ARES`(164)가 결함인 `AMRN`(20.1) **위에** 있다 |
| **`spin_offs` 교차대조로 가격계수를 가른다** | **`DELL`의 분사가 `spin_offs`에 없다**(탐침 확인. 대조군 `T`→`WBD`는 뜬다) |
| **파생 `ratio`의 유리수 근사로 가른다** | **`IR`의 0.8824 = `15/17`**이라 작은 유리수 시험을 통과한다. 선언된 rate의 **작은 쪽**이라야 갈린다 |
| **기업행위 누적만 적용한다** | `AKTX`가 **39.1B · 순위 279위**로 세탁된다(§규칙) |
| **`old_rate`/`new_rate`를 컬럼으로 저장한다** | 저장 `ratio`로 **정확히 복원된다**(148쌍 불일치 0). 그리고 원장에 `OBSERVED`가 0행이라 **재적재로 채울 경로가 없다** |
| **기저 미확정 종목을 그대로 둔다** | 사용자 결정(2026-09-23): *"AKTX는 원장에 소스가 없거나 기준이 개판인 종목이고 이런건 미표시 되는게 맞아."* |

## 결과

**분류 실측 (2026-09-23 · 판별자를 건 뒤)**

| 갈래 | 건수 |
|---|---|
| A 계열이 비율대로 따라감 | **117** |
| B 계열 불변 | **3** (`AKTX` 2026-03-31 · `AMRN` · `LNZA`) |
| C 다른 배수로 움직임 | **9** |
| D 판정불가(사건 뒤 공시가 없음 등) | **14** |

**판별자의 분포** (미국 130종목 153사건 · 정규화 `min(분자,분모)`)

| 값 | 건수 | 정체 |
|---|---|---|
| 1 | **123** | N-for-1 · 1-for-N |
| 2 | **24** | 3-for-2 (`2→3`과 `1→1.5` 두 표기) |
| *3 ~ 96* | **0** | **빈 구간 — 임계를 어디 둬도 답이 같다** |
| 97 · 189 · 200 · 1000 · 1000 · 1103 | 6 | `QGEN`(합성 자사주) · `DD`(Corteva) · `FTV`(Vontier) · `DELL`(VMware) · `TT`+`IR`(같은 Reverse Morris Trust 양쪽) |

**재측정 (2026-10-21 · 미국 APPLIED 372사건 · 서로 다른 비율 63)**: 띠 밖 47개는 전부 작은 쪽 10 이하 —
빈 구간이 그대로다. 띠 안 16개(주식배당 `CBSH` ×1.05 · 작은 쪽 20 · 나머지는 97 이상)는 판정에 닿지 않는다(§규칙).

**유도 결과**: `SHIFTED` **`AMRN`**(7행 · ×0.05 · 2025-05-07~2026-07-29) · `UNRESOLVED`
**`AKTX`**(22행 · 2024-03-29~2026-08-13) · 나머지 **0행**. `DELL`·`LNZA`·`IR`·`CVNA`·`QXO`·`BMNR`
전부 안 건드린다.

**값의 변화**: `AMRN` 주식수 420,044,022 → **21,002,201**, 시총 5.78B → **$289M**(편입선
$4.87B 아래). `AKTX`는 순위 후보에서 빠진다. **새로 깨지는 셀 0.**

**수용 증거 — 전 프리셋·저장 화면을 네 기준일로 전후 대조했다**(`PresetResultSnapshotTest` ·
2026-09-22 최신일 · 2026-07-30 AMRN 공시 직후 · 2025-11-18 LNZA 경계 · 2025-09-17 창 앞).
**12,826줄 중 diff 26줄이고 삭제 13 · 추가 13이 정확히 짝을 이룬다 — 결과 집합에 들고 난 종목이
하나도 없고 점수·순서만 움직였다.** 움직인 종목은 `AMRN`(16줄, 제 값을 찾아 VALUE 4→7)과
백분위 모집단이 바뀐 다섯(`OKLO`·`HII`·`EOG`·`AIZ`·`AEP`, 각 2줄)뿐이다.

**`AKTX`의 점수는 전후가 같다** — 1,565B라는 값이 이미 그 조건들을 떨어뜨리고 있었기 때문이다.
바뀐 것은 **틀린 값을 더는 주장하지 않는다**는 것이고, 그 효과는 위 다섯 종목의 백분위 이동과
`MARKET_CAP` 순위에서 드러난다. 토요일 리밸런싱이 `US_TOP1000`에서 빼면 조회 대상에서도 사라진다
(`US_LISTED`는 `is_active=false`라 애초에 조회 대상이 아니다).

**백필 실측**: 원본층 2종목 × 1거래일 **1분 34초**(`AKTX` 8지표 2,040행 삭제 · 고아 백분위 254행
정리) · 파생층 255거래일 **5분 17초**.

**되돌리려면**: `UPDATE financial_statement SET share_basis_status = NULL,
share_basis_multiple = NULL`로 판정을 지우면 이 축이 생기기 전과 같아진다 — 읽기 둘이 NULL을
`OK`로 읽으므로 코드를 되돌릴 필요가 없다. 컬럼 자체를 빼려면 `V57`을 되돌리고 두 읽기와
`FinancialFigures`를 함께 고쳐야 한다.

**이 결정이 안 하는 것**: `AKTX`의 **옳은 시총은 여전히 못 만든다.** 그리고 **기업행위가 한 번도
없던 ADS**(비율이 애초에 1이 아니고 안 바뀐 종목)는 원리적으로 못 본다 — 지금 그 모집단은
**0이다**(편입 중 ADS 표기는 `AKTX`·`TEVA` 둘뿐이고 TEVA는 1:1이 확인됐다: 표지 1,165,296,084 ≈
가중평균 1,165,000,000).
