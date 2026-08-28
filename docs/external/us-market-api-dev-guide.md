# 미국 증시 데이터 API 개발 가이드

**작성일** 2026-08-19
**대상** 설계·구현 세션
**관련 문서** [ADR-0086](../adr/ADR-0086-us-universe-by-listing.md) (범위·유니버스 정의·라이선스)

---

## 0. 이 문서의 위치

### 0.1 범위

미국 증시 스크리너가 사용하는 **3개 데이터 소스의 호출 방법과 함정**을 다룬다.
"왜 이 소스인가", "어디까지 만드는가"는 기획서 소관이며, **제약이 충돌하면 기획서가 우선한다.**

### 0.2 검증 상태 표기

문서 전체에서 아래 표기를 사용한다. **표기가 없는 서술은 신뢰하지 말 것.**

| 표기 | 의미 |
|---|---|
| ✅ | **실측** — 실제 API를 호출해 확인함. 7장에 기록 |
| 📄 | **문서 근거** — 공식 문서에 명시됨. 원문 링크 있음 |
| ⚠️ | **미검증** — 추론이거나 확인되지 않음 |

> 이 문서의 이전 버전에서 라이선스 관련 서술에 사실 오류가 있었다. 📄 표기 항목도 원문 확인을 권한다. 문서끼리 어긋나는 지점은 부록 C에 모아두었다.

### 0.3 원문을 확인하는 방법

Alpaca 문서는 **URL 끝에 `.md`를 붙이면 마크다운 원문**을 준다. 브라우저나 `curl` 어느 쪽이든 된다.

```
https://docs.alpaca.markets/us/docs/market-data-faq.md
https://docs.alpaca.markets/us/reference/stockbars.md
```

전체 문서 색인은 `https://docs.alpaca.markets/us/llms.txt` 에 있다. 이 문서에 없는 내용이 필요하면 여기서 페이지를 찾아 `.md`로 가져올 것.

SEC EDGAR는 이런 규약이 없다. 2장에 필요한 내용을 모두 실었다.

---

## 1. 전체 구성

### 1.1 소스와 역할

| 소스 | 역할 | 비용 | 인증 |
|---|---|---|---|
| **SEC EDGAR** | 재무제표 원본 | 무료 | 없음 (User-Agent만) |
| **Alpaca Market Data** | 시세, 기업행위 | 무료 | API 키 |
| **Alpaca Trading** | 유니버스, 마켓 캘린더 | 무료 | 동일 키 |
| **SEC 매핑 파일** | 티커 ↔ CIK | 무료 | 없음 |

### 1.2 Base URL — 계열에 따라 다름 📄

**Alpaca는 두 계열이 섞여 있다.** 혼동하면 404가 난다.

| 계열 | Base URL | 이 프로젝트에서 쓰는 엔드포인트 |
|---|---|---|
| Market Data API | `https://data.alpaca.markets` | `/v2/stocks/bars`<br>`/v1/corporate-actions`<br>`/v2/stocks/meta/exchanges` |
| Trading API | `https://paper-api.alpaca.markets` | `/v2/assets`<br>`/v3/calendar/{market}` |

인증 헤더는 두 계열 모두 동일하다. **캘린더만 `v3`**이라는 점도 주의.

SEC는 용도별로 호스트가 갈린다.

| 용도 | 호스트 |
|---|---|
| JSON API | `https://data.sec.gov` |
| 벌크 파일·매핑 파일 | `https://www.sec.gov` |

### 1.3 갱신 리듬

세 소스는 데이터가 바뀌는 주기가 근본적으로 다르다. **같은 배치로 묶으면 한쪽이 반드시 낭비된다.**

| 대상 | 변하는 시점 |
|---|---|
| 시세 | 매 영업일 (+ 정정·조정으로 과거 소급 변경) |
| 재무 | 공시가 발생한 날에만 |
| 유니버스 | 상장·폐지·리네임 발생 시 |

---

## 2. SEC EDGAR — 재무제표

📄 원문: `https://www.sec.gov/search-filings/edgar-application-programming-interfaces`

### 2.1 접근 조건

**인증도, API 키도, 가입도 없다.** 대신 두 가지를 지켜야 한다.

**① User-Agent 헤더 필수** 📄

```
User-Agent: <이름> <연락 가능한 이메일>
Accept-Encoding: gzip, deflate
Host: data.sec.gov
```

SEC는 신원을 밝히지 않는 자동화 도구를 "미분류 봇"으로 판정하고 차단한다. **HTTP 클라이언트의 기본 User-Agent를 반드시 덮어쓸 것.** 라이브러리를 쓰더라도 이 설정을 확인해야 한다.

**② 초당 10회 제한** 📄

사용하는 머신 수와 무관하게 사용자 단위로 적용된다. 초과 시 해당 IP가 일시 제한되며, 요청 속도가 임계값 아래로 떨어지면 자동 해제된다. 영구 차단은 아니다.

### 2.2 엔드포인트

모든 URL에서 **CIK는 10자리 zero-padding 필수**. Apple CIK 320193 → `CIK0000320193`.

#### submissions — 공시 이력

```
https://data.sec.gov/submissions/CIK##########.json
```

회사명(현재·이전), 거래소, 티커 등 메타데이터 + 공시 이력. 최소 1년치 또는 최근 1,000건 중 많은 쪽을 컬럼형 배열로 담고, 초과분은 추가 JSON 파일 배열로 분리된다.

**용도**: 재무 갱신 트리거 판단. 어떤 기업이 언제 10-K/10-Q를 냈는지.

#### companyfacts — 한 기업 전체 재무

```
https://data.sec.gov/api/xbrl/companyfacts/CIK##########.json
```

한 기업의 모든 XBRL 개념을 단일 호출로 반환. **응답이 무겁다.**

**용도**: 재무 적재의 주력.

#### companyconcept — 한 기업 × 한 항목

```
https://data.sec.gov/api/xbrl/companyconcept/CIK##########/us-gaap/{태그}.json
```

측정 단위별로 팩트 배열이 분리된다(예: 순이익을 USD와 CAD로 각각 보고한 경우).

**용도**: 특정 지표만 보정·재조회. companyfacts보다 훨씬 가볍다.

#### frames — 전 기업 × 한 항목 (횡단면)

```
https://data.sec.gov/api/xbrl/frames/us-gaap/{태그}/{단위}/CY{기간}.json
```

요청한 역법 기간에 가장 근접하게 최종 제출된 팩트를 기업별로 하나씩 집계한다.

**기간 포맷** 📄

| 형식 | 의미 | 해당 항목 |
|---|---|---|
| `CY2024` | 연간 (365일 ±30일) | 손익계산서, 현금흐름표 |
| `CY2024Q1` | 분기 (91일 ±30일) | 손익계산서, 현금흐름표 |
| `CY2024Q1I` | **시점(Instantaneous)** | **재무상태표** |

> 🔴 재무상태표 항목은 끝에 `I`가 필요하다. 누락하면 빈 결과가 온다.

**단위 표기**: 분자/분모가 있으면 `-per-`로 구분한다(`USD-per-shares`). XBRL 기본 단위는 `pure`.

> ⚠️ 기업마다 회계 달력이 다르므로, 하나의 프레임에 담긴 팩트들의 **보고 시작·종료일이 서로 다를 수 있다.** 횡단면 비교 시 정합성 검증이 필요하다.

### 2.3 티커 ↔ CIK 매핑

EDGAR는 티커로 조회할 수 없다.

```
https://www.sec.gov/files/company_tickers.json           (티커 + CIK + 회사명)
https://www.sec.gov/files/company_tickers_exchange.json  (+ 거래소)
```

`cik_str` 값을 문자열로 변환한 뒤 10자리 zero-padding해야 URL을 만들 수 있다.

### 2.4 벌크 파일

```
https://www.sec.gov/Archives/edgar/daily-index/xbrl/companyfacts.zip
https://www.sec.gov/Archives/edgar/daily-index/bulkdata/submissions.zip
```

매일 밤 재컴파일되며 **약 03:00 ET에 재발행**된다. companyfacts.zip은 Frame API와 Company Facts API의 모든 데이터를 포함한다.

유니버스 규모(수천 종목)를 고려하면 초기 백필에서 개별 호출보다 유리할 가능성이 크다. **판단은 설계 몫.**

### 2.5 태그 정규화 — 이 프로젝트 최대의 미지수

**모든 기업이 같은 XBRL 태그를 쓰지 않는다.** 매출을 `Revenues`로 쓰는 기업과 `RevenueFromContractWithCustomerExcludingAssessedTax`로 쓰는 기업이 공존하며, 같은 기업도 연도에 따라 바뀔 수 있다.

공식 택소노미 목록은 `https://www.sec.gov/info/edgar/edgartaxonomies.shtml` 에 있으나, **어떤 기업이 실제로 어떤 태그를 쓰는지는 그 기업의 companyfacts를 직접 열어야 안다.**

> 매핑은 문서를 읽어서가 아니라 실제 데이터를 훑어서 만들어진다. 9장에 이 공수를 가늠하는 항목을 남겨두었다.

**표준 태그 하나만 보고 파싱하면 결측이 대량 발생한다.** 폴백 태그 체인을 갖추는 것이 사실상 필수다.

### 2.6 EDGAR 함정

| 함정 | 내용 |
|---|---|
| 🔴 **CORS 미지원** 📄 | **프론트엔드에서 직접 호출 불가.** 반드시 서버 경유 |
| 🔴 **CIK padding** 📄 | 10자리 zero-padding 누락 시 404. 404의 최다 원인 |
| 🔴 **User-Agent** 📄 | 미설정 시 봇 판정 차단 (2.1) |
| 🔴 **frames의 `I`** 📄 | 재무상태표 조회 시 누락하면 빈 결과 |
| ⚠️ **filer API 혼동** 📄 | `api.edgarfiling.sec.gov`는 **공시 제출자용**. 토큰 발급 절차가 있어 혼동하기 쉬우나 무관 |
| 📄 **XBRL 시작 시점** | 2009년 대형 신속제출자 의무화, 2011년 전체 확대. 이전 공시는 XBRL API로 조회 불가 |
| 📄 **기술 지원 없음** | SEC는 스크립트 개발·디버깅 지원을 제공하지 않는다 |

**갱신 지연** 📄: submissions 1초 미만, XBRL API 1분 미만. 공시 폭주 시 증가.

---

## 3. Alpaca — 시세

📄 원문: [stockbars.md](https://docs.alpaca.markets/us/reference/stockbars.md) · [market-data-faq.md](https://docs.alpaca.markets/us/docs/market-data-faq.md) · [historical-stock-data-1.md](https://docs.alpaca.markets/us/docs/historical-stock-data-1.md)

### 3.1 인증

```
APCA-API-KEY-ID: <Key ID>
APCA-API-SECRET-KEY: <Secret Key>
```

**오류 구분** 📄

| 코드 | 원인 |
|---|---|
| **401** | 인증 헤더 누락·무효, **그리고 자격증명 오류** ✅ 실측 — 본문은 `{"message": "unauthorized."}` |
| **403** | 권한 부족(구독 등급 등) |

403 응답 본문에 `subscription does not permit querying recent SIP data`가 있으면 15분 규칙 위반이다(3.2 참조).

> 📄 **페이퍼 계정을 리셋하면 자격증명을 재발급해야 한다.** 기존 키가 무효화된다.

응답 헤더의 `X-Request-ID`는 Alpaca 지원 문의 시 필수다. 장애 조사용으로 최근 값을 보관해두면 좋다.

### 3.2 플랜 한도와 15분 규칙

📄 원문: [about-market-data-api.md](https://docs.alpaca.markets/us/docs/about-market-data-api.md)

| 항목 | Basic (현재 사용 중) | Algo Trader Plus ($99/월) |
|---|---|---|
| 과거 데이터 시작 시점 | **2016년~** | 2016년~ |
| 과거 데이터 제약 | **최근 15분** | 없음 |
| 과거 데이터 API 호출 | **200 / 분** | 10,000 / 분 |
| 실시간 커버리지 | IEX | 전 미국 거래소 |
| WebSocket 구독 | 30 종목 | 무제한 |

**과거 데이터 기간은 무료·유료가 같다.** 유료가 추가로 사는 것은 실시간성과 호출량이다.

**15분 규칙 — 이 프로젝트의 핵심 전제** 📄

> 모든 latest 계열 엔드포인트(snapshot 포함)는 SIP 피드 사용 시 구독이 필요하다. **과거 데이터 조회의 경우, 구독 없이 SIP를 조회하려면 `end` 파라미터가 최소 15분 이상 지난 시점이어야 한다.**

즉 **15분보다 오래된 데이터는 무료로 SIP 전체를 받을 수 있다.** ✅ 7장에서 실측 확인됨.

배치는 장 마감 15분 이후에 돌리면 되고, `end`를 명시하지 않으면 자동으로 15분 전으로 잡힌다(3.4).

### 3.3 피드 선택 — 반드시 명시할 것

| 값 | 커버리지 |
|---|---|
| `sip` | **전 미국 거래소 100%** ← 이 프로젝트가 쓸 것 |
| `iex` | 단일 거래소, 시장 거래량의 약 2.5% |
| `otc` | 장외. 브로커 파트너 전용 구독 필요 |
| `boats` | Blue Ocean ATS, 야간거래 |

> 🔴 **`feed=sip`를 항상 명시할 것.**
>
> OpenAPI 스키마의 기본값은 `sip`이지만, FAQ는 "기본값은 **항상 사용자의 구독에 기반한 최선의 피드**"라고 서술한다. 두 문서가 어긋난다(부록 C-1). 무료 계정에서 기본값에 의존하면 **오류 없이 조용히 IEX 데이터를 받을 수 있다.**

**IEX를 쓰면 실제로 값이 틀어진다** ✅ — 7.1의 실측에서 IEX 데이터가 일중 저점을 놓치는 사례가 확인됐다. 고가·저가를 쓰는 지표(ATR, 볼린저밴드, 지지선)가 오염된다.

### 3.4 일봉 조회

```
GET https://data.alpaca.markets/v2/stocks/bars
```

**파라미터** 📄

| 이름 | 필수 | 기본값 | 설명 |
|---|---|---|---|
| `symbols` | ✔ | — | 쉼표 구분 심볼 목록 |
| `timeframe` | ✔ | — | `1Day` / `1Week` / `[1-59]Min` / `[1-23]Hour` / `[1,2,3,4,6,12]Month` |
| `start` | | 당일 시작<br>(무료는 최소 15분 전) | RFC-3339 또는 `YYYY-MM-DD` |
| `end` | | 현재 시각<br>(**무료는 15분 전**) | RFC-3339 또는 `YYYY-MM-DD` |
| `feed` | | 구독 기반 (3.3) | `sip` 명시 필수 |
| `adjustment` | | `raw` | 3.6 참조 |
| `limit` | | **1000** (최대 10000) | 3.5 참조 |
| `page_token` | | — | 페이징 토큰 |
| `sort` | | `asc` | `asc` / `desc` |
| `asof` | | 활성 | 3.8 참조 |
| `currency` | | `USD` | ISO 4217 |

**응답 구조** 📄

```json
{
  "bars": {
    "AAPL": [ { "t":"...", "o":0, "h":0, "l":0, "c":0, "v":0, "n":0, "vw":0 } ]
  },
  "currency": "USD",
  "next_page_token": null
}
```

| 필드 | 의미 |
|---|---|
| `t` | 봉 시각. 일봉은 **뉴욕 기준 자정을 UTC로 표현** |
| `o` `h` `l` `c` | 시가·고가·저가·종가 |
| `v` | 거래량 |
| `n` | **체결 건수** |
| `vw` | VWAP (3.7 주의) |

> ⚠️ **일봉 `t`의 시각 부분은 서머타임에 따라 바뀐다.** ✅ EDT 기간은 `T04:00:00Z`, EST 기간은 `T05:00:00Z`. UTC 날짜만 취하면 결과는 같지만, 시각을 파싱하는 코드는 이를 가정하면 안 된다.

### 3.5 페이징 — `limit`의 함정

> 🔴 📄 **`limit`은 심볼별이 아니라 전체 데이터 포인트 수에 적용된다.** 기본값 **1000**, 최대 10000.

다종목 백필에서 가장 위험한 항목이다.

- `limit`을 지정하지 않으면 **1000개에서 잘린다**
- 결과는 **심볼 우선, 그다음 타임스탬프 순**으로 정렬된다
- 따라서 한 종목의 봉만으로 limit에 도달하면 **첫 응답에 그 종목 하나만 담긴다**
- `next_page_token`이 `null`이 될 때까지 순회해야 나머지 종목에 도달한다

**중간에 멈추면 데이터가 조용히 누락된다.** 오류가 아니라 정상 응답으로 오기 때문에 발견이 늦다.

### 3.6 수정주가

| 값 | 반영 대상 |
|---|---|
| `raw` | 없음 (**기본값**) |
| `split` | 정·역 분할 (가격 + 거래량) |
| `dividend` | 현금배당 (가격) |
| `spin-off` | 분사 (가격) |
| `all` | 위 전부 |

> 📄 쉼표로 조합 가능: `split,spin-off`

**동작 확인됨** ✅ — 7.2에서 분할·배당 모두 정상 반영을 실측했다.

> 🔴 **조정가는 소급해서 변한다.**
>
> 오늘 받은 과거 조정 종가는 영구불변이 아니다. 배당이 지급되거나 분할이 일어나면 **같은 날짜의 조정값이 다시 바뀐다.** ✅ 7.2에서 배당 계수 약 0.9983이 소급 적용된 것을 확인했다.
>
> | | 성격 |
> |---|---|
> | `raw` | 거래소가 실제 체결한 값 — **불변** |
> | `all` | 오늘 시점의 조정 결과 — **가변** |
>
> raw를 저장하면 조정은 언제든 다시 계산·재조회할 수 있지만, 조정가만 저장하면 raw를 복원할 수 없다. **무엇을 저장할지는 설계 판단이나, 이 성질 자체는 전제로 삼을 것.**

> ⚠️ **조정값은 소수점 2자리로 반올림된다** ✅ — raw의 `l = 812.5456`이 all에서 `81.11`이 된다. VWAP도 마찬가지. 일봉 스크리너에서는 무해하나 알아둘 것.

### 3.7 봉 집계 규칙

📄 원문(전체 조건코드 표 35행): [market-data-faq.md](https://docs.alpaca.markets/us/docs/market-data-faq.md)

**기본 규칙** 📄

- 분봉·일봉은 **체결로부터 집계**된다. 일봉은 SIP 타임스탬프를 **뉴욕 기준 날짜로 절삭**
- 그 외 봉(주봉, 시간봉 등)은 **분봉·일봉을 재집계**한 것이다. 실제 체결을 다시 참조하지 않는다

**우리에게 영향 있는 조건코드만 요약** 📄

일봉 기준이며, 분봉 전용 행은 생략했다.

| 분류 | 조건코드 | 시/종가 | 고/저 | 거래량 |
|---|---|---|---|---|
| **거래량만 갱신** | `B`(평균가·AB) `C`(현금결제) `H`(가격변동) `I`(**단주**) `N`(익일) `R`(매도자지정) `T`(**시간외**) `U`(연장거래) `V`(조건부) `W`(평균가·CO) `7`(적격조건부) | ❌ | ❌ | ✅ |
| **아무것도 갱신 안 함** | `M`(공식종가) `Q`(공식시가) | ❌ | ❌ | ❌ |
| **첫 체결일 때만 시/종가** | `G` `P`(선행참조가) `Z`(순서외) `4`(파생가격) | 🟡 | ✅ | ✅ |
| **가격만, 거래량 제외** | `9`(정정 통합종가) | ✅ | ✅ | ❌ |
| 그 외 정규 체결 | `@` `A` `D` `E` `F` `K` `L` `O` `X` `Y` `5` `6` | ✅ | ✅ | ✅ |

체결에 조건이 여러 개면 **가장 엄격한 규칙이 적용된다.**

**여기서 나오는 실무적 결과 3가지**

> 🔴 **① 거래량과 가격이 서로 다른 모집단을 본다.**
> 시간외 거래(`T`)와 단주(`I`)는 **일봉 거래량에는 더해지지만 가격에는 반영되지 않는다.** 거래대금(가격×거래량)을 계산하면 미묘한 부정합이 생긴다.

> 🔴 **② 거래가 없으면 봉 자체가 생성되지 않는다.** 📄
> "봉은 필드(시가·고가·저가·종가·거래량) 중 하나라도 0이면 생성되지 않는다. 따라서 해당 구간에 체결이 없거나, `I` 조건 체결 하나만 있으면 봉이 만들어지지 않는다."
>
> **결측이 정상 동작이다.** 소형주에서 자주 발생한다. "휴장일 / 거래 없음 / 수집 실패"를 구분하려면 **마켓 캘린더(6장)가 반드시 필요하다.**

> ⚠️ **③ VWAP의 내부 거래량은 봉의 `v`와 다를 수 있다.** 📄
> VWAP은 고저 갱신 규칙과 거래량 갱신 규칙이 **둘 다** 참인 체결만 누적한다. `R` 같은 조건이 섞이면 어긋난다. VWAP을 거래대금 대용으로 쓰지 말 것.

### 3.8 티커 리네임과 `asof`

> 📄 **`asof`는 기본적으로 활성이다.** 지정하지 않아도 리네임 이후 새 심볼로 조회하면 **구 심볼 데이터까지 함께** 반환된다.

FB → META(2022-06-09) 사례에서, META로 조회하면 리네임 전 FB 시절 봉도 META 라벨로 붙어 온다.

| 값 | 동작 |
|---|---|
| (미지정) | 당일 기준 매핑. 구 심볼 데이터 포함 |
| `-` | **매핑 비활성.** 심볼 그대로만 조회 |
| `YYYY-MM-DD` | 해당 날짜 기준 매핑. 구 티커로도 조회 가능 |

**두 가지 함정**

> 🔴 **리네임 당일에는 매핑이 안 된다.** 📄
> "`asof` 매핑은 리네임 **다음 날부터만** 사용 가능하다. FB-META 사례에서는 2022-06-10부터 가능했고, 당일(06-09)에 같은 쿼리를 돌리면 FB 봉이 반환되지 않았다."
>
> 일일 배치가 리네임 당일에 돌면 그 종목의 과거 데이터가 빠진다.

> ⚠️ **원자료 관점의 손실**
> 기본 동작에서는 "이 봉이 실제로 어느 티커로 체결됐는지"가 사라진다. 기획서의 원자료 보존 원칙과 상충할 수 있다. `asof=-`로 순수 데이터를 받을지, 기본 동작을 쓸지는 설계 판단.

### 3.9 시세 함정 요약

| 함정 | 심각도 |
|---|---|
| `feed` 미지정 시 IEX로 조용히 하락 (3.3) | 🔴 |
| `limit` 기본 1000 · 전체 기준 (3.5) | 🔴 |
| `adjustment` 기본값이 `raw` (3.6) | 🔴 |
| 조정가의 소급 변동 (3.6) | 🔴 |
| 거래 없는 날 봉 미생성 (3.7) | 🔴 |
| 리네임 당일 `asof` 미동작 (3.8) | 🔴 |
| 시간외·단주가 거래량만 갱신 (3.7) | ⚠️ |
| `latest` 계열은 15분 규칙에 걸림 (3.2) | ⚠️ |
| 일봉 `t`의 서머타임 이동 (3.4) | ⚠️ |
| VWAP 내부 거래량 불일치 (3.7) | ⚠️ |

---

## 4. Alpaca — 유니버스

📄 원문: [get-v2-assets-1.md](https://docs.alpaca.markets/us/reference/get-v2-assets-1.md) · [working-with-assets.md](https://docs.alpaca.markets/us/docs/working-with-assets.md)

### 4.1 자산 목록 조회

```
GET https://paper-api.alpaca.markets/v2/assets
```

Alpaca에서 **거래 및 데이터 조회가 가능한 자산의 마스터 리스트**다. 자산 클래스 → 거래소 → 심볼 순으로 정렬된다.

**파라미터** 📄

| 이름 | 기본값 | 설명 |
|---|---|---|
| `status` | **전체 포함** | `active` 명시 필요 |
| `asset_class` | `us_equity` | |
| `exchange` | 전체 | `AMEX` `ARCA` `BATS` `NYSE` `NASDAQ` `NYSEARCA` `OTC` `CRYPTO` |
| `attributes` | — | 쉼표 구분. 해당 속성을 **하나라도** 가진 자산 포함 |

> 🔴 **`status` 기본값이 "모든 상태 포함"이다.** 명시하지 않으면 `inactive` 종목이 섞인다.

> ⚠️ **페이징 파라미터가 없다.** 전체를 한 번에 반환한다. 응답 크기를 감안할 것.

**응답 필드** 📄

```json
{
  "id": "b0b6dd9d-8b9b-48a9-ba46-b9d54906e415",
  "class": "us_equity",
  "exchange": "NASDAQ",
  "symbol": "AAPL",
  "name": "Apple Inc. Common Stock",
  "cusip": "037833100",
  "status": "active",
  "tradable": true,
  "marginable": true,
  "shortable": true,
  "fractionable": true,
  "attributes": []
}
```

| 필드 | 이 프로젝트에서의 쓸모 |
|---|---|
| `exchange` | **유니버스 필터의 기준** |
| `status` | `active` / `inactive` |
| `symbol` `name` | 식별 |
| `cusip` | **EDGAR 매핑의 보조 키** (US 주식만) |
| `tradable` | 아래 주의 참조 |

> ⚠️ **`tradable=false`인 자산이 존재한다.** 📄 "일부 자산은 데이터 조회 전용이며 Alpaca에서 거래할 수 없다. 이런 자산은 `tradable=false`로 표시된다."
>
> 우리는 거래를 하지 않으므로 `tradable`로 거르면 안 된다. **`status`로 걸러야 한다.**

### 4.2 유니버스 구성 방침

기획서가 정한 범위는 **NYSE·NASDAQ 정규 상장**이다.

```
exchange IN (NYSE, NASDAQ) AND status = 'active'
```

⚠️ 예상 규모 약 5,600종목. 여기에는 ETF, REIT, 폐쇄형펀드, 우선주, 워런트, 복수 주식 클래스가 섞여 있다.

**기획서 방침: 적재는 넓게, 노출은 좁게.** 적재 단계에서 거르지 말고 종목 유형을 태깅해 노출 단계에서 제어한다. 유형 식별 로직은 설계 판단 영역이다.

`OTC`는 제외한다 — 📄 OTC 심볼의 시장 데이터는 브로커 파트너 전용 구독이 필요하다.

### 4.3 SEC 매핑과의 교집합

EDGAR는 CIK로만 조회되므로 티커→CIK 매핑이 필요하다(2.3). 두 소스의 교집합을 쓰면 "시세는 있는데 재무가 없는" 반쪽 데이터가 애초에 들어오지 않는다.

⚠️ 다만 ETF·REIT처럼 CIK는 있으나 `us-gaap` 재무를 내지 않는 경우가 있다. 교집합만으로 완전히 걸러지지는 않는다.

### 4.4 거래소 코드 매핑

```
GET https://data.alpaca.markets/v2/stocks/meta/exchanges
```

체결 데이터의 거래소 코드와 이름의 매핑을 반환한다(예: `N` → New York Stock Exchange, `V` → IEX).

일봉만 쓰는 경우 직접 필요하지는 않으나, 데이터 검증 시 유용하다.

### 4.5 임박한 deprecation ⚠️

```
easy_to_borrow                 → borrow_status 사용
maintenance_margin_requirement → margin_requirement_long / margin_requirement_short 사용

sunset: 2026-09-22
```

우리가 쓰는 필드는 아니지만, 예제 코드나 SDK 구버전을 참고할 때 걸릴 수 있다.

### 4.6 심볼 데이터가 없을 때 📄

FAQ가 세 가지 원인을 든다.

| 원인 | 확인 방법 |
|---|---|
| **OTC** | `/v2/assets/{symbol}`의 `exchange` 필드 |
| **비활성** | 같은 엔드포인트의 `status` 필드 |
| **거래정지** | Nasdaq Trader 웹사이트의 거래정지 목록 |

여기에 3.7의 "거래 없음" 케이스가 추가된다. **네 가지를 구분할 수 있어야 결측 판정이 성립한다.**

---

## 5. Alpaca — 기업행위

📄 원문: [corporateactions-1.md](https://docs.alpaca.markets/us/reference/corporateactions-1.md)

```
GET https://data.alpaca.markets/v1/corporate-actions
```

> ⚠️ 구 엔드포인트 `/v2/corporate_actions/announcements`는 **deprecated**다. 오래된 예제 코드 주의.

### 5.1 파라미터 📄

| 이름 | 기본값 | 설명 |
|---|---|---|
| `symbols` | — | 쉼표 구분 |
| `types` | 전체 | 5.2 참조 |
| `start` | **당일** | `process_date` 기준. `YYYY-MM-DD` |
| `end` | **당일** | 동일 |
| `region` | `us` | `us` / `non_us` / `all` |
| `data_quality` | `complete` | 5.3 참조 |
| `limit` | **100** (최대 1000) | **전체 기준, 심볼별 아님** |
| `page_token` | — | |
| `sort` | `asc` | |
| `cusips` | — | 쉼표 구분 |
| `ids` | — | **다른 모든 필터와 상호 배타적** |

> 🔴 **`start`/`end` 기본값이 둘 다 "당일"이다.** 범위를 지정하지 않으면 오늘 처리된 것만 나온다.

> 🔴 `limit`도 시세와 같은 함정을 가진다 — **심볼별이 아니라 전체 기준**이며 기본값이 100으로 낮다.

정렬 기준은 `process_date`다. 이벤트 발생일(`ex_date`)이 아님에 주의.

### 5.2 타입 16종 📄

```
forward_split      reverse_split       unit_split
cash_dividend      stock_dividend      spin_off
cash_merger        stock_merger        stock_and_cash_merger
redemption         name_change         worthless_removal
rights_distribution  partial_call      reorganization
capital_gains_distribution
```

**이 프로젝트가 쓸 것**

| 타입 | 용도 | 주요 필드 |
|---|---|---|
| `forward_split` `reverse_split` | 분할 검증·자체 조정 | `old_rate` `new_rate` `ex_date` `process_date` |
| `cash_dividend` | 배당 조정 검증 | `rate` `ex_date` `payable_date` `special` `foreign` |
| `spin_off` | 분사 조정 | — |
| **`name_change`** | **티커 리네임 감지** | `old_symbol` `new_symbol` `old_cusip` `new_cusip` |
| **`worthless_removal`** | **상장폐지 감지** | — |

> ⭐ 뒤의 둘은 **유니버스 변경 이력 보강**에 쓸 수 있다. `/v2/assets`는 현재 상태만 알려주지만, 여기서는 **변경 사유와 시점**을 얻는다.
>
> 다만 ADR-0086의 「유니버스 시점 스냅샷을 보존한다」를 대체하지는 못한다. 아래 신뢰성 문제 때문이다.

### 5.3 데이터 신뢰성 — 공식 경고 🔴

> 📄 **현재 Alpaca는 기업행위의 생성 시점을 보장하지 않는다.** 데이터 제공사로부터 기업행위를 수신하는 데 지연이 있을 수 있고, 처리해 API로 제공하기까지도 지연이 있을 수 있다. 따라서 기업행위가 공시 직후 즉시 제공되지 않을 수 있다.

**이 경고의 무게를 이해할 것.** 기업행위 데이터는 두 가지 용도가 있는데, 경고가 양쪽에 다 걸린다.

1. `adjustment=all` 결과의 **검증** — 검증 기준 자체가 늦게 도착할 수 있다
2. 자체 조정 계산의 **입력** — 입력이 늦으면 계산이 틀린다

`data_quality` 파라미터가 관련 있다 📄:

| 값 | 동작 |
|---|---|
| `complete` (기본) | 필수 필드(ex-date, CUSIP/ISIN 등)가 아직 없고 미처리인 건을 제외. **단, 이미 처리된 건은 불완전해도 항상 포함** |
| `all` | 완전성과 무관하게 전부 반환 |

조기 감지가 필요하면 `all`, 정확성이 우선이면 `complete`다.

---

## 6. Alpaca — 마켓 캘린더

📄 원문: [calendar-2.md](https://docs.alpaca.markets/us/reference/calendar-2.md)

```
GET https://paper-api.alpaca.markets/v3/calendar/{market}
```

> ⚠️ **Trading API 소속이고 경로가 `v3`다.** 시세와 Base URL이 다르다(1.2).

### 6.1 파라미터 📄

| 이름 | 필수 | 기본값 | 설명 |
|---|---|---|---|
| `market` | ✔ (경로) | — | `NYSE` `NASDAQ` `XNYS` `XNAS` 등 |
| `start` | | 당일 | `YYYY-MM-DD` |
| `end` | | **start + 1주** | `YYYY-MM-DD` |
| `timezone` | | 시장 시간대 | `UTC` 지정 가능 |

> ⚠️ **`end` 기본값이 1주**다. 긴 범위가 필요하면 반드시 지정할 것.

우리 유니버스가 NYSE·NASDAQ 양쪽이므로 **두 시장을 각각 조회**해야 한다. 정규장 일정은 대체로 동일하나, 코드가 분리되어 있는 이상 가정하지 말 것.

### 6.2 응답 필드 📄

```json
{
  "market": { "acronym": "NYSE", "name": "New York Stock Exchange",
              "mic": "XNYS", "timezone": "America/New_York" },
  "calendar": [
    {
      "date": "2025-01-02",
      "core_start": "2025-01-02T09:30:00-05:00",
      "core_end":   "2025-01-02T16:00:00-05:00",
      "pre_start":  "2025-01-02T04:00:00-05:00",
      "post_end":   "2025-01-02T20:00:00-05:00",
      "settlement_date": "2025-01-03"
    }
  ]
}
```

`date`, `core_start`, `core_end`가 필수 필드다. 조기 폐장일에는 `core_end`가 앞당겨진다.

### 6.3 이 프로젝트에서의 역할

**필수 요소다.** 3.7의 "거래 없으면 봉 미생성" 때문에, 캘린더 없이는 결측을 판정할 수 없다.

| 봉이 없다 | 캘린더로 판별 |
|---|---|
| 휴장일 | 해당 날짜가 `calendar`에 없음 → **정상** |
| 거래 없음 | 개장일인데 봉 없음 → **정상** (4.6과 교차 확인) |
| 수집 실패 | 개장일이고 다른 종목은 있는데 이 종목만 없음 → **재시도 대상** |

두 번째와 세 번째의 구분에는 `/v2/assets`의 `status`와 거래정지 여부가 함께 필요하다.

**배치 실행 시각 산정에도 쓴다.** `core_end` + 15분(3.2) 이후에 돌려야 당일 종가가 나온다. 조기 폐장일에는 `core_end`가 다르다.

---

## 7. 실측 검증 기록

**2026-08-19, 무료 페이퍼 계정 API 키로 직접 호출한 결과다.** 재현 가능하다.

### 7.1 G-1 — SIP 과거 데이터 조회 ✅ 합격

**요청**

```bash
curl -s -H "APCA-API-KEY-ID: $APCA_API_KEY_ID" \
        -H "APCA-API-SECRET-KEY: $APCA_API_SECRET_KEY" \
  "https://data.alpaca.markets/v2/stocks/bars?symbols=AAPL&timeframe=1Day&start=2024-01-02&end=2024-01-31&feed=sip"
```

`feed=iex`로 한 번 더 호출해 비교.

**결과 (AAPL 일봉)**

| 날짜 | SIP 거래량 | IEX 거래량 | IEX 비중 |
|---|---|---|---|
| 2024-01-02 | 82,496,943 | 1,795,262 | 2.18% |
| 2024-01-18 | 78,031,784 | 1,405,733 | 1.80% |
| 2024-01-31 | 55,467,803 | 1,019,028 | 1.84% |

체결 건수(`n`)도 1,009,074 대 18,557로 같은 비율.

**판정**: 문서가 명시한 IEX 약 2.5% 점유율과 일치. **SIP가 전체 시장 데이터를 반환하고 있다.**

**부수 확인 — IEX의 데이터 왜곡**

2024-01-31 저가: SIP `184.35` vs IEX `184.41`.
**IEX가 실제 일중 저점을 놓쳤다.** IEX에서 거래되지 않은 순간에 시장 저점이 형성됐기 때문이다. 종가도 2024-01-04에 SIP `181.91` vs IEX `182.00`으로 갈린다.

→ 3.3의 "SIP 명시 필수"의 실증 근거.

### 7.2 G-2 — 수정주가 ✅ 합격

**요청** — NVDA 2024-06-10 10:1 분할 이전 구간

```bash
curl -s -H "APCA-API-KEY-ID: $APCA_API_KEY_ID" \
        -H "APCA-API-SECRET-KEY: $APCA_API_SECRET_KEY" \
  "https://data.alpaca.markets/v2/stocks/bars?symbols=NVDA&timeframe=1Day&start=2024-05-01&end=2024-05-31&feed=sip&adjustment=raw"
```

`adjustment=all`로 한 번 더.

**결과**

| 항목 | raw | all | 비율 |
|---|---|---|---|
| 거래량 (05-01) | 55,986,317 | 559,863,170 | **정확히 ×10** |
| 체결 건수 | 1,087,885 | 1,087,885 | 동일 (조정 대상 아님) |
| 종가 (05-01) | 830.41 | 82.90 | ÷10 후 **0.99830** |
| 종가 (05-23) | 1,037.99 | 103.62 | ÷10 후 **0.99828** |
| 종가 (05-31) | 1,096.33 | 109.44 | ÷10 후 **0.99824** |

**판정**

- 거래량 정확히 10배 → **분할 정상 반영**
- 가격은 10배로 나눈 값보다 일정하게 약 0.17% 낮음, 계수가 전 구간 일정 → **배당 조정도 정상 반영**

→ 3.6의 근거. 그리고 **조정가가 소급 변동한다**는 사실의 실증.

**부수 확인**: 조정값은 소수점 2자리로 반올림된다. raw `l=812.5456` → all `81.11`.

### 7.3 미수행

`next_page_token`은 두 테스트 모두 `null`로 정상 반환됐으나, **실제 페이징 순회는 검증하지 않았다.** 9장 참조.

---

## 8. 함정 통합 체크리스트

구현 중 참조용. 상세는 각 절 참조.

### 공통
- [ ] Base URL이 API 계열에 맞는가 — Market Data vs Trading (1.2)
- [ ] 캘린더는 `v3` 경로인가 (6.1)

### SEC EDGAR
- [ ] User-Agent를 명시적으로 설정했는가 (2.1)
- [ ] 초당 10회를 넘지 않는가 (2.1)
- [ ] CIK를 10자리 zero-padding했는가 (2.2)
- [ ] frames 시점 조회에 `I`를 붙였는가 (2.2)
- [ ] 프론트엔드에서 직접 호출하지 않는가 — CORS 미지원 (2.6)
- [ ] 태그 폴백 체인이 있는가 (2.5)

### Alpaca 시세
- [ ] `feed=sip`를 명시했는가 (3.3)
- [ ] `limit`을 명시했는가 — 기본 1000, 전체 기준 (3.5)
- [ ] `next_page_token`이 null이 될 때까지 순회하는가 (3.5)
- [ ] `adjustment`를 명시했는가 — 기본 `raw` (3.6)
- [ ] 조정가의 소급 변동을 반영하는 갱신 전략이 있는가 (3.6)
- [ ] `end`가 15분 이상 과거인가 (3.2)
- [ ] `latest` 계열을 쓰고 있지 않은가 (3.2)
- [ ] 봉 결측을 실패로 오판하지 않는가 (3.7 + 6.3)
- [ ] 거래대금 계산에 시간외·단주 영향을 인지했는가 (3.7)
- [ ] 리네임 당일 처리 방침이 있는가 (3.8)

### Alpaca 유니버스
- [ ] `status=active`를 명시했는가 — 기본은 전체 포함 (4.1)
- [ ] `tradable`로 거르고 있지 않은가 (4.1)
- [ ] OTC를 제외했는가 (4.2)
- [ ] 유니버스 시점 스냅샷을 보존하는가 — ADR-0086

### Alpaca 기업행위
- [ ] `start`/`end`를 지정했는가 — 기본은 당일 (5.1)
- [ ] `limit`을 명시했는가 — 기본 100, 전체 기준 (5.1)
- [ ] 데이터 지연 가능성을 전제한 설계인가 (5.3)
- [ ] 구 announcements 엔드포인트를 쓰고 있지 않은가 (5장)

---

## 9. 미검증 항목

| 항목 | 확인 방법 | 실패 시 영향 |
|---|---|---|
| **EDGAR 태그 편차의 실제 규모** | 업종·규모가 다른 기업 10~20곳의 companyfacts에서 매출·순이익·자산 태그가 몇 종류로 갈리는지 표본 조사 | 🔴 **최대 미지수.** 공수가 예상을 크게 초과하면 기획 에스컬레이션. 단 정제 데이터 전환은 spec §7(지표는 원자료에서 자체 계산)이 금지 |
| **다종목 페이징 실동작** | 심볼 다수 + 작은 `limit`으로 `next_page_token` 순회 확인 | 🔴 백필 전략 재설계 |
| **과거 데이터 실제 시작 시점** | 상장 시기가 다른 종목 몇 개로 최초 봉 날짜 확인 | ⚠️ 소급 2년 요구엔 여유 |
| **companyfacts.zip 실제 크기·처리 시간** | HEAD 요청으로 크기 확인 후 시범 다운로드 | ⚠️ 백필 방식 선택에 영향 |
| **`/v2/assets` 응답 크기** | 전체 조회 1회 | ⚠️ 페이징이 없으므로 확인 필요 |
| **캘린더 NYSE/NASDAQ 차이** | 같은 기간을 두 market으로 조회해 비교 | ○ 동일하면 하나만 써도 됨 |

---

## 부록 A. 참조 문서

`.md`를 붙인 URL이다. 이 문서에 없는 내용이 필요하면 여기서 원문을 가져올 것.

### Alpaca — 개념·제약

| 문서 | 담긴 내용 |
|---|---|
| [about-market-data-api.md](https://docs.alpaca.markets/us/docs/about-market-data-api.md) | 플랜 한도표, 구독 등급 |
| [historical-stock-data-1.md](https://docs.alpaca.markets/us/docs/historical-stock-data-1.md) | 피드 4종 설명 |
| [market-data-faq.md](https://docs.alpaca.markets/us/docs/market-data-faq.md) | **403 원인, IEX/SIP 차이, 심볼 미조회, 리네임, 봉 집계 전체 표** |
| [paper-trading.md](https://docs.alpaca.markets/us/docs/paper-trading.md) | 페이퍼 계정 특성 |
| [working-with-assets.md](https://docs.alpaca.markets/us/docs/working-with-assets.md) | `/assets` SDK 예제 |
| [historical-api.md](https://docs.alpaca.markets/us/docs/historical-api.md) | Base URL |

### Alpaca — 엔드포인트 스펙

| 문서 | 엔드포인트 |
|---|---|
| [stockbars.md](https://docs.alpaca.markets/us/reference/stockbars.md) | `/v2/stocks/bars` |
| [stockbarsingle-1.md](https://docs.alpaca.markets/us/reference/stockbarsingle-1.md) | `/v2/stocks/{symbol}/bars` |
| [get-v2-assets-1.md](https://docs.alpaca.markets/us/reference/get-v2-assets-1.md) | `/v2/assets` |
| [get-v2-assets-symbol_or_asset_id.md](https://docs.alpaca.markets/us/reference/get-v2-assets-symbol_or_asset_id.md) | `/v2/assets/{symbol}` |
| [corporateactions-1.md](https://docs.alpaca.markets/us/reference/corporateactions-1.md) | `/v1/corporate-actions` |
| [calendar-2.md](https://docs.alpaca.markets/us/reference/calendar-2.md) | `/v3/calendar/{market}` |
| [stockmetaexchanges-1.md](https://docs.alpaca.markets/us/reference/stockmetaexchanges-1.md) | `/v2/stocks/meta/exchanges` |

전체 색인: `https://docs.alpaca.markets/us/llms.txt`

### SEC EDGAR

| URL | 내용 |
|---|---|
| `https://www.sec.gov/search-filings/edgar-application-programming-interfaces` | **엔드포인트 명세 (주 참조처)** |
| `https://www.sec.gov/edgar/searchedgar/accessing-edgar-data.htm` | 접근 규칙 |
| `https://www.sec.gov/developer` | 벌크 파일, RSS |
| `https://www.sec.gov/info/edgar/edgartaxonomies.shtml` | 택소노미 목록 |

---

## 부록 B. 검증용 curl

환경변수 `APCA_API_KEY_ID`, `APCA_API_SECRET_KEY` 설정 전제.

```bash
# --- Alpaca 공통 헤더 ---
AH=(-H "APCA-API-KEY-ID: $APCA_API_KEY_ID" -H "APCA-API-SECRET-KEY: $APCA_API_SECRET_KEY")

# 일봉 (SIP + 전체조정 + 최대 limit)
curl -s "${AH[@]}" "https://data.alpaca.markets/v2/stocks/bars?\
symbols=AAPL,MSFT&timeframe=1Day&start=2024-01-02&end=2024-01-31\
&feed=sip&adjustment=all&limit=10000" | jq .

# 유니버스 (NASDAQ 활성 종목 수)
curl -s "${AH[@]}" \
  "https://paper-api.alpaca.markets/v2/assets?status=active&asset_class=us_equity&exchange=NASDAQ" \
  | jq 'length'

# 기업행위 (분할·배당)
curl -s "${AH[@]}" "https://data.alpaca.markets/v1/corporate-actions?\
symbols=NVDA&types=forward_split,cash_dividend&start=2024-01-01&end=2024-12-31&limit=1000" | jq .

# 마켓 캘린더
curl -s "${AH[@]}" \
  "https://paper-api.alpaca.markets/v3/calendar/NYSE?start=2026-01-01&end=2026-12-31" \
  | jq '.calendar | length'

# --- SEC EDGAR ---
UA="YourName your@email.com"

# 공시 이력
curl -s -H "User-Agent: $UA" -H "Accept-Encoding: gzip, deflate" \
  "https://data.sec.gov/submissions/CIK0000320193.json" --compressed | jq '.name, .tickers'

# 전체 재무 팩트
curl -s -H "User-Agent: $UA" -H "Accept-Encoding: gzip, deflate" \
  "https://data.sec.gov/api/xbrl/companyfacts/CIK0000320193.json" --compressed \
  | jq '.facts["us-gaap"] | keys | length'

# 단일 개념
curl -s -H "User-Agent: $UA" -H "Accept-Encoding: gzip, deflate" \
  "https://data.sec.gov/api/xbrl/companyconcept/CIK0000320193/us-gaap/Revenues.json" --compressed | jq .

# 횡단면 (2024년 전 기업 자산 — 시점형이므로 I 필수)
curl -s -H "User-Agent: $UA" -H "Accept-Encoding: gzip, deflate" \
  "https://data.sec.gov/api/xbrl/frames/us-gaap/Assets/USD/CY2024Q1I.json" --compressed \
  | jq '.data | length'

# 티커-CIK 매핑
curl -s -H "User-Agent: $UA" \
  "https://www.sec.gov/files/company_tickers_exchange.json" | jq '.fields'

# 벌크 파일 크기만 확인
curl -sI -H "User-Agent: $UA" \
  "https://www.sec.gov/Archives/edgar/daily-index/xbrl/companyfacts.zip" | grep -i content-length
```

---

## 부록 C. 문서 간 모순 기록

Alpaca 문서는 같은 사실을 페이지마다 다르게 서술하는 경우가 있다. **원문을 재확인할 때 여기를 먼저 볼 것.**

### C-1. `feed` 기본값 🔴

| 출처 | 서술 |
|---|---|
| stockbars OpenAPI 스키마 | `"default": "sip"` |
| market-data-faq | "기본값은 항상 사용자의 **구독에 기반한 최선의** 피드" |

**대응**: 항상 명시. 기본값에 의존하지 않는다. (3.3)

### C-2. 무료 계정의 SIP 접근 ✅ 해소됨

| 출처 | 서술 |
|---|---|
| historical-stock-data-1 | IEX가 "구독 없이 사용할 수 있는 **유일한** 피드" |
| paper-trading | Paper Only 계정은 "IEX 시장 데이터만 수신 자격" |
| about-market-data-api | Basic의 과거 데이터 제약은 "최근 15분" |
| market-data-faq | "과거 조회의 경우, `end`가 최소 15분 이상 지나면 구독 없이 SIP 조회 가능" |

**해소**: 7.1의 실측으로 **마지막 서술이 실제 동작과 일치**함을 확인. 앞의 두 서술은 실시간·latest 계열에 대한 것으로 읽어야 한다.

### C-3. 라이선스 범위 ⚠️ 미해소

Alpaca 고객 계약과 편입된 거래소 계약(NASDAQ OMX, NYSE)은 시장 데이터를 제3자에게 제공하는 것을 금지한다. ADR-0086의 「라이선스 — 조건이 붙은 결정이다」 절이 그 조건을 규정한다: 허용목록이 본인 계정만으로 유지되는 동안 성립한다.

**이 문서에는 해당 제약이 기술적으로 강제되는 지점이 없다.** API는 정상 동작한다. **준수는 서비스 설계 차원의 책임이다.**

---

**문서 끝.** 이 문서에 없는 내용은 부록 A의 `.md` URL에서 원문을 가져올 것.
