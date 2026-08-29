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

**단서일 뿐 판정 규칙이 아니다.** 커버리지를 재본 적이 없고, 한 종목이 여러 단서에 걸린다.

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

- **업종.** 응답에 없다. SEC의 SIC를 쓰더라도 `stock.industry_code`는 KSIC 전용이라
  **다른 컬럼이거나 다른 판정이어야 한다**(ADR-0086 §규칙).
- **상장일.** 없다. `attributes`의 `ipo`는 상태 표시이지 날짜가 아니다.
- **상장폐지·거래정지·무거래의 구분.** `status`는 활성 여부만 답한다. **마켓 캘린더가
  붙어 휴장은 갈렸지만**(ADR-0091) 나머지는 그대로다 — 거래정지 목록이 없고, `status`가
  **지금 상태**라 과거 어느 날의 활성 여부를 답하지 않는다. 게다가 우리는 `status=active`만
  받으므로 **비활성 종목은 애초에 `stock`에 들어오지도 않는다.**
- **지수 편입.** 없다. S&P 500 구성종목을 이 엔드포인트로 만들 수 없고, 그것이 유니버스를
  거래소 기준으로 정의한 이유다(ADR-0086).
