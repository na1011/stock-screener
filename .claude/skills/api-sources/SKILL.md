---
name: api-sources
description: 새 조건·지표·프리셋을 구현할 원자료가 외부 소스에 실제로 있는지 판단할 때. 어느 시장의 어느 소스가 무엇을 주고 어느 문서에 그 계약이 있는지, 「막힌 것이 원자료인가 계산기인가 요구 자체인가」를 가르는 다섯 단계, 새 소스를 붙일 때 처음 만나는 벽(소스마다 다른 실패 계약과 서비스별 승인), 쿼터를 API별로 세는 이유와 착수 판단에 쓰는 값, 엔드포인트마다 지위가 다르다는 것(사용 중·탐침만·미착수)과 받았지만 아직 안 쓰는 넷, 어떤 소스든 조용히 틀린 값을 주는 자리(0건 응답·공개 지연·회사가 비워서 낸 표·이미 계산된 비율), 착수 전에 반드시 재야 하는 값.
---

# 외부 소스로 무엇이 되는가

**적재를 *돌리는* 절차는 여기 없다** — `batch-load` skill이 답한다. 여기는 **아직 만들지 않은
것을 만들 수 있는지**를 판단하는 자리다.

## 소스 색인 — 무엇이 어디에 계약되어 있는가

| 시장 | 축 | 소스 | 환경변수 | 어댑터 | 계약 |
|---|---|---|---|---|---|
| KR | 시세·상장주식수·시가총액 | 공공데이터포털 주식시세정보 | `DATA_GO_KR_SERVICE_KEY` | `datasource/kr/DataGoKrPriceAdapter` | [`data-go-kr-stock-price.md`](../../../docs/api-spec/data-go-kr-stock-price.md) |
| KR | 주가지수 시계열 | 공공데이터포털 지수시세정보 | 같은 키, **승인됨** | `datasource/kr/DataGoKrMarketIndexAdapter` | [`data-go-kr-market-index.md`](../../../docs/api-spec/data-go-kr-market-index.md) |
| KR | 재무 계정 — 무엇이 실제로 오는가 | DART Open API | `DART_API_KEY`(40자리) | `datasource/kr/Dart*Adapter` | [`dart-financial.md`](../../../docs/api-spec/dart-financial.md) |
| KR | 기업 행위 — 분할·병합·증자·합병 | 〃 | 〃 | 〃 | [`dart-corporate-action.md`](../../../docs/api-spec/dart-corporate-action.md) |
| KR | 거버넌스 여덟 · 감사의견 · 주식 총수 | 〃 | 〃 | 〃 | [`dart-governance.md`](../../../docs/api-spec/dart-governance.md) |
| KR | 기업개황 — 업종·설립일·결산월 | 〃 | 〃 | 〃 | [`dart-company-profile.md`](../../../docs/api-spec/dart-company-profile.md) |
| US | 자산 마스터 — 유니버스·종목명·거래소 | Alpaca Trading API | `APCA_API_KEY_ID` + `APCA_API_SECRET_KEY`(**키가 둘이다**) | `datasource/us/Alpaca*Adapter` | [`alpaca-universe.md`](../../../docs/api-spec/alpaca-universe.md) |
| US | 일별 시세 — OHLC·거래량 | Alpaca **Market Data** API(base URL이 다르다) | 위와 같은 키 | `datasource/us/AlpacaPriceAdapter` | [`alpaca-bars.md`](../../../docs/api-spec/alpaca-bars.md) |
| US | 재무 계정 — XBRL 팩트 | SEC EDGAR | `SEC_EDGAR_USER_AGENT`(**키가 아니라 신원 표시다**) | `datasource/us/SecEdgar*` | [`sec-edgar-financial.md`](../../../docs/api-spec/sec-edgar-financial.md) |
| US | 기업 행위 — 분할·병합 | Alpaca **Market Data** API | Alpaca와 같은 키 둘 | `datasource/us/AlpacaCorporateActionAdapter` | [`alpaca-corporate-action.md`](../../../docs/api-spec/alpaca-corporate-action.md) |
| US | 세션 달력 — 봉 결측 판정의 근거 | Alpaca **Trading** API | Alpaca와 같은 키 둘 | `datasource/us/AlpacaMarketCalendarAdapter` | [`alpaca-calendar.md`](../../../docs/api-spec/alpaca-calendar.md) |

**미국은 유니버스·시세·재무·기업행위 넷이 열려 있고 거버넌스·업종이 남았다**(ADR-0086·0088·0089·0090).
**그래서 `US_LISTED`는 아직 못 켠다** — 다만 **이유가 바뀌었다**. 배치를 죽이던 관문
(`CorporateActionJob.requireSources`)은 ADR-0090이 닫았고, 지금 남은 것은 **거버넌스·업종
어댑터가 없어 그 두 잡이 매 실행 8,504건 부분 실패를 남기는 것**이다. 무엇이 비었는지는
ADR-0086의 premise가 축별로 수로 답한다.
소스가 늘면 이 표에 **행을 더하고** `docs/api-spec/`에 파일을 만든다 — **파일명이 말하는 것은 소스가 아니라 축이다.**
한 소스가 여러 축을 주면 축마다 파일이 하나다(DART가 넷이다). 그래야 한 파일을 열었을 때 그 축의
계약이 거기 다 있다.

**DART의 인증·`status`·`corp_code` 계약은 문서가 아니라 코드가 정본이다** —
`DartResponse.kindOf`(status 13코드 → `Kind`)와 `DartCorpCodeCache`(종목코드 → `corp_code`,
ZIP 계약)가 각각 유일한 해석 지점이다. **HTTP 200으로 오는 인증 실패**가 그 판정에 걸린다.

**「KRX상장종목정보」는 승인했지만 쓰지 않는다** — 상장일이 없고 전부 겹쳤다
(`data-go-kr-krx-listed.md`).

## 엔드포인트 색인 — 무엇을 어디까지 믿을 수 있나

**지위가 곧 신뢰도다.** "사용 중"은 코드가 매일 부르는 것이고, "미착수"는 스펙만 읽은 것이다.
**공통 요청 인자**는 `crtfc_key`(STRING(40), 필수)이고, 각 계약 문서의 표는 그 외의 것만 적는다.

| 엔드포인트 | 지위 | 필드 계약 | 콜 수 |
|---|---|---|---|
| `getStockPriceInfo` (공공데이터포털) | **사용 중** | `data-go-kr-stock-price.md` | 종목당 1(기간조회) |
| `corpCode.xml` | **사용 중** | `DartCorpCodeCache` javadoc | 전체 1 |
| `company.json` | **사용 중** | `dart-company-profile.md` | 종목당 1 = 350 |
| `fnlttSinglAcnt.json` | **사용 중** | `dart-financial.md` | 종목·연도당 4 |
| `fnlttSinglAcntAll.json` | **사용 중** | 〃 | 종목·연도당 1(연간 전용), 폴백 시 2 |
| `fnlttMultiAcnt.json` | **탐침만** (정정 탐지) | 〃 | **100종목 묶음** = 64 |
| `xbrlTaxonomy.json` | 미착수 (계정 사전) | 〃 | `sj_div`당 1 |
| `irdsSttus.json` | **사용 중** | `dart-corporate-action.md` | 창이 정한다 — 2020~2026 백필이 **종목당 2** |
| `cmpMgDecsn.json` · `stkExtrDecsn.json` | 미착수 (U-06) | 〃 | 각 종목당 1 = 350 |
| **거버넌스 여덟**(`alotMatter`·`hyslrSttus`·`outcmpnyDrctrNdChangeSttus`·`mrhlSttus`·`hyslrChgSttus`·`hmvAuditAllSttus`·`stockTotqySttus`·`accnutAdtorNmNdAdtOpinion`) | **사용 중**(ADR-0081) | `dart-governance.md` | 종목·연도당 1. 지금 배치가 **7,700** |
| `elestock`·`majorstock`·`tesstkAcqsDspsSttus`·`list` | **미착수** — 넷의 사정이 다르다 | 아래 | — |
| `/v2/assets` (Alpaca Trading) | **사용 중** | `alpaca-universe.md` | **전체 1** — 페이징이 없고 `exchange` 콤마 목록이 동작한다 |
| `/v2/stocks/bars` (Alpaca Market Data) | **사용 중** | `alpaca-bars.md` | 종목당 1(구간조회). **묶지 않는다** — 실패 격리의 단위가 종목이 아니게 된다 |
| `/api/xbrl/companyfacts` (SEC EDGAR) | **사용 중** | `sec-edgar-financial.md` | **종목당 1** — 전 기간이 한 응답에 온다. 대신 **한 종목이 78KB~7.9MB**이고 전량이 약 29GB다 |
| `company_tickers_exchange.json` (SEC) | **사용 중** | `SecTickerCikCache` javadoc | 실행당 1 |
| `/v1/corporate-actions` (Alpaca) | **사용 중** | `alpaca-corporate-action.md` | 종목당 1(구간조회). **묶지 않는다** — `limit`이 전체 기준이라 묶으면 한 종목의 결측과 묶음 전체의 절단이 같은 모양이 된다 |
| `/v3/calendar` (Alpaca **Trading**) | **사용 중** | `alpaca-calendar.md` | **거래소당 1 = 2**. 창과 무관하다 — 11년치가 한 응답이고 페이징이 없다 |
| `submissions` (SEC) | **미착수** | — | 미국에 남은 축이다 |

### 받았지만 아직 안 쓰는 넷

**「미착수」의 뜻이 넷에서 갈린다.** `elestock`·`tesstkAcqsDspsSttus`는 **쓸 자리가 정해졌고
필드 표만 안 옮긴 것**이다. `majorstock`은 소비처가 아직 없고, `list.json`은 증자(감자) 현황이
열려 필요가 줄었다.

| 엔드포인트 | 요청 인자 | 쓸 곳 |
|---|---|---|
| `elestock.json` (임원·주요주주 소유보고) | `corp_code`. **기간 파라미터가 없다** | **거버넌스 축의 내부자 순매수** — 린치 §4.4가 이 소스를 이름으로 지목한다 |
| `majorstock.json` (대량보유 상황보고) | `corp_code`. **기간 파라미터가 없다** | 거버넌스 축의 후보. 소비처가 아직 정해지지 않았다 |
| `tesstkAcqsDspsSttus.json` (자기주식 취득·처분) | `corp_code` · `bsns_year` · `reprt_code` | `SHARES_GROWTH_3Y`의 교란 요인(소각) |
| `list.json` (공시검색) | `corp_code` · `bgn_de` · `end_de` 등 | **응답 결과 표가 원문에 빠져 있었다** |

### 개발가이드 원문을 꺼내는 법

**받은 개발가이드 원문 13종과 카탈로그 101줄은 저장소에서 지웠다.** 관리되지 않는 임시 txt가
포인터로 남는 비용이 보관 이득보다 컸다. **필요하면 git 이력에서 꺼낸다:**

```bash
git show 6bc3d09:docs/api-spec/dart-api-개발가이드.txt   # 13종 요청·응답 필드 표
git show 6bc3d09:docs/api-spec/dart-api-카탈로그.txt     # 엔드포인트 목록
```

**개발가이드는 이미 있는 API의 사용법이라 없는 API를 증명하지 못한다** — 두 자료는 답하는
질문이 다르다.

**MUST NOT: 「다른 파일이 덮는다」를 확인 없이 쓰지 않는다.** 덮는지는 그 파일을 열어 **필드 표가
있는지**로 판정한다 — **실측 발견을 적은 자리와 필드 계약을 적은 자리는 다르다.** 덮는다고 보고
건너뛰었다가 전체 재무제표의 "분/반기 손익은 [3개월] 금액"과 택사노미의 `sj_div` 26코드표가
통째로 빠진 적이 있다.

## 막힌 것이 무엇인지 먼저 가른다

**「원자료가 없다」의 근거가 한 종류가 아니다.** 아래 다섯 중 어디인지를 정하는 것이 이
판정의 전부다 — **각 항목이 지금 어느 단계인가의 정본은 ADR-0039의 조건 대조표**이고,
여기에는 분류만 둔다.

| 단계 | 뜻 | 무엇을 하면 되는가 |
|---|---|---|
| **A** | 추가 수집 0. 원자료가 있고 **계산기만** 없다 | 계산기를 만든다 |
| **B** | 이미 열려 있는데 **안 쓴다**(매퍼가 버리거나 컬럼이 없다) | 매퍼·컬럼을 잇는다 |
| **C** | 같은 소스의 **다른 엔드포인트**가 필요하다 | 그 엔드포인트를 실측한다 |
| **D** | **그 소스 밖의 소스**가 필요하다 | 소스를 찾고 승인받는다 |
| **E** | **실측이 닫았다** — 재봤는데 안 온다 | 후보를 넓혀 다시 잴 여지가 있는지 먼저 본다 |

- **MUST NOT: 단계 A를 E로 오분류하지 않는다.** 그 오분류 하나가 열려 있는 길을 영구
  폐쇄로 만든다. **지표 보존 정책이 자르는 것은 *지표*이고 원자료는 전량 보관된다**(ADR-0011) —
  「보존이 막는다」로 적으면 계산기만 있으면 되는 일이 소스 부재로 기록된다.
- **MUST NOT: 「목록에 없다」를 「얻을 수 없다」로 읽지 않는다.** 전용 엔드포인트가 없어도 다른
  API의 한 필드가 그것을 준다. **카탈로그·목록은 엔드포인트의 존재만 답하고 실측을 대신하지
  못한다.**
- **MUST NOT: 공급자가 스스로 문서화한 것을 실측으로만 알아내려 하지 않는다.** 위와 반대
  방향의 실수다. **실측은 안내가 없거나 안내를 못 믿을 때, 그리고 「안내가 지금도 참인지」를
  잴 때 쓴다.**
- **MUST: 결손을 소스 부재로 판정하기 전에 원본 명세의 *가공법*을 먼저 읽는다.**
  요구가 소스의 필드를 이름으로 지목하지 않고 **다른 방법을 명시**하는 경우가 있다
  (예: 상장 경과를 상장일이 아니라 **가격 시계열 길이**로 판정하라). **막힌 것이 원자료인지
  계산기인지 요구 자체인지를 가른다.**
- **MUST NOT: 결손으로 적기 전에 재봤는지 묻지 않고 적지 않는다.** 「그 하나가 안 왔다」와
  「그 계정이 없다」는 다르다 — **후보를 넓혀 다시 재면 판정이 뒤집히는 자리가 실재한다.**

**MUST NOT: 원자료 콜 수로 착수 순서를 매기지 않는다.** 지표 계층에 **그 원자료를 받을 채널이
있는가**가 대개 더 큰 비용이다. 원자료가 몇 콜로 끝나면서 코어 넷을 고치게 만든 축이 둘 있다
(시장 지수 ADR-0053 · 거버넌스 ADR-0081). **채널을 여는 것은 「지표 추가 비용 1파일」을 깨지
않는다** — 그것은 *지표* 추가의 비용이고, 축을 연 뒤에는 그 축의 지표가 다시 파일 하나다.

**IMPORTANT — 「소스가 있다」와 「원본으로 돌아간다」는 다르다.** 업종이 그것을 실증했다 —
코드는 350/350 들어왔는데 **표본 부족으로 43종목(12%)이 판정불가**다. **커버리지를 성립으로
읽지 않는다.**

## 새 소스를 붙일 때 처음 만나는 벽 — 실패 계약이 소스마다 반대다

**MUST: 새 소스의 실패 응답을 정상 경로 파서로 열어 보기 전에 그 계약부터 읽는다.**

| 소스 | 인증 실패 | 왜 조용한가 |
|---|---|---|
| DART | **HTTP 200** + `status` 필드 | HTTP 상태만 보면 잘못된 키로 조회한 결과가 정상으로 보인다 |
| 공공데이터포털 | **HTTP 403** + `SERVICE_KEY_IS_NOT_REGISTERED_ERROR` | **봉투가 `response`가 아니라 `OpenAPI_ServiceResponse`라** 정상 경로 파서로는 「빈 응답」처럼 보인다 |
| Alpaca | **HTTP 401**(자격증명) · **403**(권한·15분 규칙) | 코드를 제대로 쓴다. 다만 가이드가 401을 403으로 적어 뒀다 |
| SEC EDGAR | **HTTP 404 + XML 본문** | 오류가 JSON이 아니라 XML이라 **정상 경로 파서에 넣으면 상태코드를 보기도 전에 터진다** — 그 예외가 404를 「일시 장애」로 만든다 |

**IMPORTANT — 스로틀·차단 시의 코드는 소스마다 재봐야 하는데, SEC는 재면 안 된다.**
유발하면 IP가 일시 제한되어 그동안 아무것도 못 받는다. **모르는 코드는 재시도 쪽으로 접는다** —
`AUTH`로 접으면 `fatal()`이라 스로틀 한 번이 배치 전체를 죽인다.

**IMPORTANT — 공공데이터포털은 서비스마다 따로 승인한다.** 키 문자열이 같아도 활용신청하지
않은 서비스는 위 403이고, **신청은 사람만 할 수 있다.**

## 쿼터

**IMPORTANT — 한도는 계정 전체가 아니라 API별이다. 그래서 잡끼리 경쟁하지 않는다.**
제공자가 달라서만이 아니라 **같은 제공자 안에서도 엔드포인트마다 카운터가 따로**다.

- **MUST NOT: 쿼터를 하나로 뭉뚱그려 착수 시점을 미루지 않는다.** 시세 배치는 DART를 한 콜도
  부르지 않으므로 DART 잡이 그날 아무리 돌아도 배치의 한도에 닿을 수 없다.
- **MUST: 착수 판단은 공표값의 절반으로 한다.** 두 값 다 미실측이므로 그대로 맞춰 짠 계획은
  한도가 절반이면 깨진다. 절반에서 넘치면 그때가 **날짜를 나누거나 폴백 비율을 재야 하는 시점**이다.

| 제공자 | 공표값(미실측) | 계획에 쓰는 값 |
|---|---|---|
| DART | API당 일 20,000 | **10,000** |
| 공공데이터포털 | 서비스당 일 10,000 규모(계정마다 다름) | **5,000** |
| Alpaca | 분당 200(일일 총량 공표 없음) | **분당 100 · 일일 무제한** |
| SEC EDGAR | **초당 10**(일일 총량 없음) | **초당 5**(`permits-per-minute: 300`) |

**SEC의 한도는 사용자 단위라 호스트가 둘이어도 하나로 센다** — `data.sec.gov`와
`www.sec.gov`가 같은 리미터를 공유한다.

**DART의 20,000은 2차 출처 다수와 사용자가 대는 값이고 공식 문서에서 확인하지 못했다**
(`status 020` 설명에 "일반적으로는 20,000건 이상의 요청에 대하여"가 있을 뿐이다).
**하루 17,000콜에서도 한도에 안 걸린 관측이 있다** — **MUST NOT: 그것을 「20,000이 맞다」로
읽지 않는다.** 관측은 **17,000까지 안 걸렸다**까지이고, 보수 예산 10,000은 그대로 선다.
그것도 한 엔드포인트에 쏠린 잡이 아니라 여러 잡의 합이었다.

**우리 리미터는 `dartRateLimiter` 빈 하나로 DART 전체를 센다** — 소스 쪽 한도는 안 겹쳐도
**우리 쪽에서 먼저 막힌다.**

**진짜 위험은 합산이 아니라 쏠림이다** — 한 엔드포인트에 몰리는 잡 하나가 자기 한도를 혼자
태운다. **그 자리에서 예산을 정하는 것은 콜 수가 아니라 「어느 보고서 코드가 필요한가」다** —
순서를 뒤집어 폴백 비율부터 재면 **존재하지도 않는 예산 초과를 관리하느라 4배를 태운다.**
잡별 콜 수는 `batch-load` skill의 「각 잡이 하는 일」 절과 `data-go-kr-stock-price.md`에 있다.

- **공공데이터포털 초당 30 TPS**, 평균 응답 500ms, 갱신주기 일 1회.
  **DART의 분당 제한은 문서에 없다.**
- **어느 날짜가 소스에 있는지 묻는 가장 싼 방법은 `basDt`만 주는 전종목 조회 1콜**이다 —
  응답의 `totalCount`가 곧 답이고 배치를 돌리지 않아도 된다(`data-go-kr-stock-price.md`의
  「전종목 조회」 절).

## 조용히 틀리는 자리 — 소스가 달라도 형태가 같다

**공통점: 에러가 나지 않고 값이 그럴듯하다.**

- **0건 응답은 「없다」의 증거가 아니다.** 없는 종목·휴장일·미공개·파라미터 오타가 **전부 같은
  응답**으로 오는 소스가 있다. 탐침 한 번의 0건으로 「그 엔드포인트는 안 준다」고 판정하면
  틀린다. **어댑터 코드를 고칠 때의 방어는 `.claude/rules/data-pipeline.md`가, 적재 중의 판정은
  `batch-load` skill이 자동으로 든다** — 구체 계약은 `data-go-kr-stock-price.md`가 정본이다.
- **소스는 당일 종가를 그날 안에 내주지 않는다.** 거래일 T는 **T의 다음 영업일 13시 이후**에
  나온다(공급자 공식 안내). 대상 일자를 달력으로 정하면 빈 날을 긁는다 — **「영업일」이라 연휴를
  건너뛰므로 직전 평일로는 따라갈 수 없다.** **지연은 경과 일수가 아니라 경과 *영업일*로 센다.**
- **회사가 표를 비워서 낼 수 있다.** 오류도 「데이터 없음」도 아닌 **모든 필드가 `-`인 1행**이
  온다. **행 수만 세면 「1건 있음」으로 읽힌다.**
- **이미 계산된 비율을 주는 엔드포인트를 쓰지 않는다.** spec §7이 「지표는 원자료를 수집한 뒤
  자체 계산」을 MUST로 걸므로 정면 충돌한다. **원자료로 담는 것까지는 하되 지표 계산에 쓰지
  않는다** — 분자·분모가 같은 응답에 함께 오는 경우가 많다.
- **거래정지 종목은 「행 없음」이 아니라 0으로 채운 행으로 온다.**

## 착수 전에 재야 하는 값

**「더 정확히 재면 좋은 값」이 아니라 phase가 성립하는지를 정하는 값**이 따로 있다. 표본 몇
개사로는 답이 나오지 않는다. **MUST: 각 phase의 첫 단계가 그것을 재는 것이어야 한다** —
나중에 재면 이미 구조를 짠 뒤라 결과에 맞춰 기준이 기운다. 지금 남아 있는 것은
`dart-corporate-action.md`의 「표본으로는 원리적으로 못 재는 것」 절에 있다(둘 다 분할·병합이다).

## 이 소스들이 무엇을 위해 필요한가

[`docs/external/screener-spec-midterm-swing.md`](../../../docs/external/screener-spec-midterm-swing.md)은
**더 이상 프리셋 조건의 정본이 아니다.** 그 지위는 후계 프리셋에 승계되지 않았고(ADR-0041의
머리말), 지금 프리셋 조건의 출처는 문헌이다(ADR-0061). 그 파일이 지금 지배하는 것은
「에이전트가 고치지 않는다」와 「사본을 만들지 않는다」 둘뿐이다.

**원본 26개 조건 중 무엇을 옮기고 무엇을 버렸는지의 정본은 ADR-0039의 조건 대조표**다.
새 소스를 붙일 때 그 표를 **먼저** 본다 — 무엇이 살아나는지가 거기 적혀 있다.
