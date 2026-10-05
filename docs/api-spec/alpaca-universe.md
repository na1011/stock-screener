# Alpaca — 자산 마스터(유니버스)

미국 유니버스의 원자료. **NYSE·NASDAQ에 상장된 자산의 현재 목록**이며, 종목의 이름·거래소·
상태를 함께 준다. 지수 편입이 아니라 거래소 상장으로 유니버스를 정의한 근거는 ADR-0086에 있다.

- 어댑터는 `pipeline/datasource/us/AlpacaUniverseAdapter`·`AlpacaSecurityMasterAdapter`,
  포트는 `UniverseDataSource`·`SecurityMasterDataSource`, 적재는 `UniverseRebalanceJob`이다.
- **HTTP 코드 → `Kind` 매핑의 정본은 `AlpacaResponse`의 javadoc이다.** 문서로 떼어 두면
  갈려도 아무도 모른다(`DartResponse.kindOf`와 같은 취급).
- 쿼터와 엔드포인트 색인은 `api-sources` skill이 답한다.
- 탐침: `AlpacaAssetProbeLiveTest` — **아래 실측이 그 출력 그대로다.**
- 호출 방법과 함정의 원문은 [`../external/us-market-api-dev-guide.md`](../external/us-market-api-dev-guide.md).

여기는 **이 축의 필드 계약과, 그 문서가 적은 것 중 실측에서 달랐던 자리**다.

---

## 엔드포인트

```
GET https://paper-api.alpaca.markets/v2/assets
```

**Trading API다.** 시세(`https://data.alpaca.markets`)와 base URL이 다르고 섞으면 404다.
인증은 헤더 둘 — `APCA-API-KEY-ID` · `APCA-API-SECRET-KEY`.

| 파라미터 | 기본값 | 쓰는 값 |
|---|---|---|
| `status` | **전체 포함** | `active` — **명시 필수** |
| `asset_class` | `us_equity` | `us_equity` |
| `exchange` | 전체 | `NYSE,NASDAQ` — **콤마 목록이 실제로 동작한다(실측)** |

**페이징 파라미터가 없다.** 전체를 한 번에 반환한다.

---

## 실측 (2026-08-29, 무료 페이퍼 계정)

`./gradlew liveTest --tests "*AlpacaAssetProbe*"`

| 값 | 실측 |
|---|---|
| NYSE `status=active` | **2,920종목** · 1,346,212바이트 |
| NASDAQ `status=active` | **5,584종목** · 2,537,223바이트 |
| **합계** | **8,504종목 · 약 3.88MB** |
| `exchange=NYSE,NASDAQ` 한 번에 | 8,504 — 합계와 일치. **1콜로 끝난다** |
| `status` 생략 | 10,667 (**+2,163**) — 기본값이 「전체 포함」이라는 문서 서술이 참이다 |
| 심볼 최대 길이 | **8자** (`stock.ticker varchar(20)`에 여유) |
| 이름 최대 길이 | **229자** — `stock.name varchar(200)`을 **넘긴다** |
| 중복 심볼 | **0** |
| 빈 이름 | **0** |
| `tradable=false` | 34종목 |
| `cusip` | **8,504건 전부 없음** |
| `class` | `us_equity` 하나뿐 |
| `exchange` | `NYSE` · `NASDAQ` 둘뿐 |
| 틀린 자격증명 | **HTTP 401** + `{"message": "unauthorized."}` — **이 엔드포인트의 본문 형식이다.** 같은 벤더 안에서 갈린다(`alpaca-calendar.md`의 「함정」) |

**`attributes`에 실제로 오는 값 8종** — 전부 거래 속성이다:
`fractional_eh_enabled` · `has_options` · `ipo` · `options_late_close` · `overnight_halted` ·
`overnight_tradable` · `ptp_no_exception` · `ptp_with_exception`

### 이름 접미 분포 (8,504 기준)

**단서일 뿐 판정 규칙이 아니다.** 한 종목이 여러 단서에 걸린다.

**IMPORTANT — 이 표를 만든 규칙은 부분문자열이고, 그것을 판정으로 쓰면 사업회사를 지운다.**
`upper(name)`에 `ETF`가 있는지로 재면 **NETFLIX**가 걸리고 `RIGHT`로 재면
**Curtiss-Wright**·**Bright*** 계열이 걸린다. `% UNIT%`가 아닌 `%UNIT%`로 재면
COMMUNITY·OPPORTUNITY·BANKUNITED 계열 **133종목**이 Unit으로 분류된다(2026-08-31 실측).
**판정은 토큰 경계로 한다** — 규칙과 그 근거는 ADR-0099 §규칙에 있다.

| 단서 | 건수 | 비율 |
|---|---|---|
| Common Stock | 2,476 | 29.1% |
| Fund / Trust | 1,500 | 17.6% |
| ETF | 1,250 | 14.7% |
| Warrant | 457 | 5.4% |
| Depositary | 442 | 5.2% |
| Preferred | 435 | 5.1% |
| Unit | 337 | 4.0% |

**보통주로 보이는 것이 29%뿐이다.** 「적재는 넓게, 노출은 좁게」가 방침이 아니라 필수라는 뜻이다.

### 티커 접미 분포 — 이름과 **다른 축**이다 (2026-08-30 실측)

티커에 점이 있는 것이 **507건**이다. 이름 접미와 겹치지만 같지 않다 — 이쪽은 거래소가 붙인
증권 종류 코드다.

| 접미 | 건수 | 뜻 |
|---|---|---|
| `PRA`~`PRZ` 계열 | 약 340 | 우선주 |
| `WS` | 51 | 워런트 |
| `U` | 47 | 유닛 |
| `RT` | 18 | 권리 |
| `A`·`B`·`C` | **23** | **보통주의 의결권 클래스** |

**IMPORTANT — 「티커에 점이 있으면 뺀다」는 규칙을 세우지 마라.** 그 23건이 정확히 그 규칙에
걸려 사라지는데, 거기에 **BRK.A·BRK.B**가 들어 있다(그 밖에 BF.A/B · HEI.A · LEN.B ·
MOG.A/B · TAP.A · UHAL.B · WSO.B · CRD.A/B · AKO.A/B · GEF.B · BIO.B · GTN.A · HVT.A ·
AGM.A · CIG.C · PBR.A · BH.A). **반대로 안 빼면 같은 회사가 두 번 들어온다** —
`AKO.A`/`AKO.B`처럼 두 클래스가 다 상장된 쌍이 있어 국내의 우선주 배제가 막던 중복이
그대로 재현된다. **점은 이 판정의 축이 아니다.**

### 이름 × CIK 교차표 — ADR-0086이 「재본 적 없다」로 열어 둔 커버리지 (2026-08-30)

**세는 규칙**: 이름 라벨은 대문자에서 **낱말 경계**로 찾는다(`\bPREFERRED\b|\bPFD\b` →
Preferred, `WARRANTS?`, `UNITS?`, `RIGHTS?`, `DEPOSITARY`, `NOTES?|DEBENTURES?|BONDS?`,
`ETFS?|FUNDS?|TRUSTS?`, `COMMON STOCK|ORDINARY SHARES`, 나머지 미분류).
CIK는 `company_tickers_exchange.json`에 **그대로 또는 점→하이픈**으로 붙는가.
모수 8,495(`UsUniverseAxisProbeLiveTest`).

**IMPORTANT — 부분문자열로 세면 회사명 안에서 우연히 맞는다.** `ETF`로 재면 **NETFLIX**가,
`RIGHT`로 재면 **Curtiss-Wright**·**Bright\*** 계열이, `%UNIT%`로 재면
COMMUNITY·OPPORTUNITY·BANKUNITED 계열 **133종목**이 걸린다(2026-08-31 실측).
**아래 수는 낱말 경계로 센 것이다** — 부분문자열 규칙으로 세면 `Unit` 단독이 24가 아니라 110,
`Right` 단독이 3이 아니라 12가 된다.

| 이름 라벨 | CIK 단독 | CIK 중복 | CIK 없음 |
|---|---|---|---|
| (미분류) | 1,813 | **153** | 22 |
| Common Stock | 2,554 | 510 | 12 |
| Depositary | 239 | 10 | 10 |
| Fund/Trust | 354 | 13 | 1,160 |
| Note/Bond | 19 | 148 | 168 |
| Preferred | 18 | 110 | 309 |
| Right | 3 | 103 | 20 |
| Unit | 24 | 240 | 26 |
| Warrant | 2 | 367 | 88 |

**두 축의 구멍이 이 표의 답이다.**

- **CIK 축의 구멍 = 66** — `단독 × (Preferred·Warrant·Unit·Right·Note)`. 발행사와 CIK를
  공유하지 않아 중복 배제에 안 걸리는 비보통주다.
- **이름 축의 구멍 = 153** — `중복 × (미분류)`. 같은 CIK를 나눠 쓰는데 이름으로는 종류를
  못 가르는 것들이다. **ADR-0086이 물은 수가 이것이고, 이름만으로는 못 닫는다.**
- **`중복 × Common Stock` 510**은 정당한 이중 클래스(`BRK.A/B`·`GOOGL/GOOG`)와 진짜 중복이
  섞여 있다 — **가르려면 다른 축이 필요하다.** 그 축이 같은 CIK 안의 순위 창 거래대금이다
  (ADR-0169 — 같은 CIK의 티커는 주식수가 같아 시총 비교가 가격 비교가 된다).
- **`Fund/Trust × CIK 없음` 1,160**이 ETF·펀드의 대부분이다.

**둘 중 하나만 쓰면 안 닫힌다.** 상위 1,000을 실제로 뽑아 보면 CIK 중복 98 · 우선주류 이름
52가 들어오는데(`alpaca-bars.md` §시가총액 분포) **그 둘은 겹치지 않는다.**

**「ETF·펀드가 몇인가」는 세는 규칙이 정한다.** 위 이름 단서로 세면 ETF 1,250 · Fund/Trust
1,500이고, CIK에 안 붙는 것으로 세면 1,815(그중 ETF·펀드 1,332), 이름을
`%ETF%|%fund%|%trust%|%index%`로 훑으면 1,677이다. **셋 다 다른 것을 세고 있다** — 수를
인용하기 전에 어느 규칙인지 함께 적는다.

---

## 함정

- **`status`를 명시하지 않으면 상장폐지 종목 2,163개가 섞인다.** 에러가 아니라 정상 응답이다.
- **`tradable`로 거르면 안 된다.** 데이터 조회 전용 자산이 `tradable=false`로 오고(34건),
  우리는 거래를 하지 않으므로 그것으로 거르면 **볼 수 있는 종목을 스스로 지운다.** 거르는 것은
  `status`다.
- **`cusip`이 오지 않는다.** 개발가이드의 응답 예시에는 `"cusip": "037833100"`이 있고 그것을
  「EDGAR 매핑의 보조 키」로 적었으나, **무료 페이퍼 계정에서는 8,504건 전부 비어 있다.**
  티커↔CIK 매핑은 SEC의 `company_tickers_exchange.json`으로 따로 만들어야 한다.
- **`attributes`로 증권 유형을 가를 수 없다.** `class`도 `us_equity` 하나뿐이라 응답 어디에도
  ETF·REIT·우선주·워런트를 구분하는 필드가 없다. **이름 문자열이 유일한 단서이고 그 커버리지는
  미실측이다** — 위 분포는 세어 둔 것이지 판정 규칙이 아니다.
- **긴 이름은 회사명이 아니라 증권 조건문이다.** 229자짜리는
  `KIM.PRL — Kimco Realty Corporation Class L Depositary Shares, each of which represents a
  one-one thousandth fractional interest in …` 형태다. 이름을 잘라 저장하면 **서로 다른 우선주가
  같은 이름이 된다.**
- **이름이 티커와 같은 종목이 실재한다.** 8,504종목 중 `RH`(Restoration Hardware)와 `VTEX`
  둘이다. `StockRegistrar`가 마스터 조회에 실패했을 때 만드는 행과 **구분되지 않는 모양**이라,
  「이름=티커인 행 수」를 결손 신호로 쓰면 이 둘이 늘 걸린다. 판정은 수가 아니라 **집합**으로
  한다(`AlpacaUniverseLiveTest`가 그 목록을 들고 있다).
- **응답이 3.88MB다.** 페이징이 없으므로 스트리밍 없이 통째로 메모리에 올라온다.
- **종목 수가 같은 날 안에서도 움직인다.** 위 8,504는 이 축의 탐침이 잰 값이고, 같은 날
  시세 탐침(`alpaca-bars.md`)이 같은 파라미터로 **8,495**를 받았다. 둘 다 맞다 — 상장·폐지가
  반영되는 실시간 목록이다. **그래서 이 수를 상수로 박지 않는다**: 하한
  `MIN_EXPECTED_ASSETS`는 절반이고, 두 문서의 수가 다른 것은 갈림이 아니라 드리프트다.
- **기획서의 추정치(~5,600)는 실제의 66%였다.** 추정치에서 뽑은 상수는 소스가 반쯤 준 날 울지
  않는다 — 바닥값은 위 실측에서 뽑고 측정일을 함께 적는다.

---

## 이 소스가 답하지 못하는 것

**「없다」와 「아직 안 물었다」를 가르기 위해 적는다.**

- **업종.** 응답에 없다. **SEC `submissions`가 그 자리를 채웠다**(ADR-0092) —
  담기는 컬럼은 `stock.sic_code`이고 `industry_code`는 여전히 KSIC 전용이다(ADR-0086 §규칙).
- **상장일.** 없다. `attributes`의 `ipo`는 상태 표시이지 날짜가 아니다.
- **상장폐지·거래정지·무거래의 구분.** `status`는 활성 여부만 답한다. **마켓 캘린더가
  붙어 휴장은 갈렸지만**(ADR-0091) 나머지는 그대로다 — 거래정지 목록이 없고, `status`가
  **지금 상태**라 과거 어느 날의 활성 여부를 답하지 않는다. 게다가 우리는 `status=active`만
  받으므로 **비활성 종목은 애초에 `stock`에 들어오지도 않는다.**
  - **다만 「과거 어느 날 상장이었나」는 유도된다 — 봉의 존재가 그 증거다.** 아래
    §비활성 종목의 봉이 그 경로와 실측이다.
- **지수 편입.** 없다. S&P 500 구성종목을 이 엔드포인트로 만들 수 없고, 그것이 유니버스를
  거래소 기준으로 정의한 이유다(ADR-0086).

## 비활성 종목의 봉 — 과거 명부가 못 담는 것의 크기 (2026-09-09 실측)

`status`를 빼면 비활성이 함께 오고, 그 심볼의 **봉이 어느 창에 있다는 것이 그날 거래됐다는
증거**다. 「상장폐지 날짜가 없다」가 「과거 상장 여부를 못 안다」를 뜻하지 않는 이유가 이것이다.

| 잰 것 | 값 |
|---|---|
| 자산 전체 / 활성 / 비활성 | 10,677 / 8,513 / **2,164** |
| 그중 봉을 물어볼 수 있는 심볼 | **1,522** (자리표 491 · 지금 활성인 같은 이름 140 제외) |
| 보존 창(`2025-09-03~`) 안에 **실제로 거래된** 것 | **22** |
| 그중 `stock`에 이미 있는 것 | **0** |
| 거래대금 상위 | `CADE` 14.7B · `IAC` 7.4B · `CIVI` 4.8B · 나머지는 1B 미만 |
| 비용 | 자산 1콜 + 월봉 묶음 8콜 = **9콜** |

### 함정

- **모르는 심볼 하나가 묶음 전체를 400으로 죽인다.** `/v2/stocks/bars`는 모르는 심볼을 조용히
  빼는 것이 **아니다** — 요청을 통째로 거절하고 본문에 `invalid symbol: X`로 이름을 말한다.
  비활성 목록에는 `550CVR021`·`286992540`·`5326esc96` 같은 **CVR·권리·CUSIP 자리표가 491개**
  섞여 있어, 거르지 않고 물으면 **전 배치가 실패하고 결과가 0이 된다.** 그 0은 「없다」가 아니라
  **「못 쟀다」**이므로 못 센 배치 수를 함께 신고한다.
- **MUST: `_DELISTED` 접미를 뗀 이름이 지금 활성인 심볼과 겹치면 뺀다.** 소스가 폐지 뒤 심볼을
  `HYAC_DELISTED`로 바꿔 두고 봉은 원래 이름으로만 있는데, **그 이름이 지금 다른(또는 같은)
  상장 종목일 수 있다.** 안 빼면 그 봉이 **활성 종목의 것**이라 「폐지됐는데 거래됐다」로 세인다 —
  실측으로 140개가 그 상태였고, 거르기 전에는 `LHX`·`COHR` 같은 대형주가 구멍에 들어와
  **22가 162로 일곱 배 부풀었다.**
- **월봉으로 묻는다.** 「거래했는가」만 물으면 되므로 200심볼 × 12개월 = 2,400점이라 `limit`
  10,000 안에 들어 배치당 한 페이지다. 일봉이면 배치당 여섯 페이지가 된다.
