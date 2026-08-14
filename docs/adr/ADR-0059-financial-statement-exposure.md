---
id: ADR-0059
status: 채택됨
date: 2026-08-14
applies_to:
  - "src/main/java/com/stockscreener/stock/web/StockFinancialsResponse.java"
  - "src/main/java/com/stockscreener/stock/web/StockFinancialsAssembler.java"
  - "src/main/java/com/stockscreener/stock/StockReader.java"
  - "frontend/src/components/stock/FinancialsPanel.vue"
---

# ADR-0059. 실적 원수치를 화면에 올리며 무엇을 못 싣는가

## 결정

넷을 함께 정했다.

1. **재무제표 원수치를 `GET /api/stocks/{id}/financials`라는 별도 엔드포인트로 낸다.**
   종목 상세 응답에 싣지 않는다.
2. **소스가 준 값과 우리가 만든 값을 응답이 구분한다** — 4분기 손익에 `derived`가 붙고,
   `period_start`·`period_end`는 **내보내지 않는다.**
3. **조정 EPS·조정 EBITDA·조정 순손실을 넣지 않는다.**
4. **forward PER을 넣지 않는다**(2026-08-14 사용자 결정). 상대 밸류에이션은
   `PER_SECTOR_MEDIAN`(신규)과 `PER_SECTOR_PCTL`이 채운다.

## 규칙

- **MUST: `period_start`·`period_end`를 응답에 담지 않는다.** ADR-0056이 그 값을 합성 규약
  (`01-01 ~ 12-31`)으로 못박았고 **결산월이 12월이 아닌 종목도 예외가 아니다.** 실측 —
  신영증권(`accounting_month = 3`)의 FY2026 행이 `2026-01-01 ~ 2026-12-31`이고 공시일이
  `2026-06-11`이다. 그대로 그리면 **아직 오지 않은 기간**이 실적표에 뜬다.
  - **스키마 주석이 정반대를 적는다**(`V1__stock_and_price.sql` — *"기간의 근거는 이 두 컬럼이며
    `fiscal_year`는 라벨일 뿐이다"*). **국내 어댑터에서 그 관계가 뒤집혀 있다.** 주석을 믿고
    필드를 고르면 여기서 틀리므로 이 규칙을 코드 옆에도 둔다.
- **MUST: 4분기 행에 `derived`를 싣는다.** DART에 4분기 보고서가 없어
  `Q4(손익) = 사업보고서 − 3분기 누적`으로 우리가 만든다. 표시가 없으면 사용자는 공시된
  실적으로 읽고, 그것이 ADR-0039의 *"뜻이 다른 값을 같은 이름으로 부르지 않는다"* 에 걸린다.
  **연간/분기를 나누는 것으로는 막히지 않는 다른 축이다.**
  - **판정은 분기 라벨로 한다.** `report_code`가 사업보고서(`11011`)인 것이 흔적이지만
    (실측 Q4 2,136행 전부) **그것은 결과이고 소스가 코드 체계를 바꾸면 함께 흔들린다.**
- **MUST NOT: 증감률을 새로 짜지 않는다.** `YearOverYear.between`을 쓴다. **분모가 절댓값**이라는
  결정이 거기 있고, 다시 쓰면 적자 기업에서 **같은 사실이 한 화면의 두 곳에 반대 부호**로 나온다.
- **MUST: 분기는 전년 동기와 비교한다.** 직전 분기와 비교하면 계절성 있는 업종에서 뜻이 없다.
- **MUST: 고지 문구를 서버가 소유하고 항상 싣는다**(`PriceReference#disclaimer`와 같은 형태).
  프론트가 자기 문구를 들면 두 곳이 갈리고, 갈린 쪽은 화면에서 드러나지 않는다.
- **MUST: 행 수에 상한을 둔다.** 실측 종목당 평균 **30.9행 · 최대 37행**이다.

## 못 싣는 것 — 근거가 세 층이고 탐침이 뒤집을 수 있는 것은 하나뿐이다

**이 절의 요점은 "안 된다"가 아니라 무엇이 바뀌면 뒤집히는가다.** 뭉뚱그리면 다음 세션이
감가상각비가 열린 것을 보고 조정 EBITDA도 열렸다고 읽는다.

| 항목 | 막는 것 | 탐침이 뒤집는가 |
|---|---|---|
| **조정 EPS·조정 EBITDA·조정 순손실** | ① 한국 법정공시(K-IFRS XBRL)에 **자리가 없다.** 조정 항목을 무엇으로 잡을지가 **회사 재량**이라 종목 간 비교가 원리적으로 성립하지 않는다 ② **spec §7과 충돌한다** — *"외부 API가 계산해 주는 지표 값에 의존하지 않는다"*. 조정치는 정확히 **발행사가 스스로 정의한 계산값**이고, `RawFinancialReport`에 비율 필드를 두지 않은 것과 같은 이유로 타입에 담을 자리도 없다 | **아니다** |
| **EBITDA**(조정 아님) | 감가상각비가 비금융 기업에 표준계정코드로 오지 않는다 | **그렇다** — 다만 그때 열리는 것은 **EBITDA**이고 위 ①②는 그대로 남는다 |
| **forward PER** | 애널리스트 컨센서스가 DART·공공데이터포털 어디에도 없다. 스크래핑은 이용약관·안정성·spec §7 셋에 함께 걸린다 | **아니다** (다른 소스의 문제다) |

## 왜 별도 엔드포인트인가

`StockDetailResponse`가 지지·저항을 뺀 네 이유가 그대로 걸린다 — 오류 격리, "지표가 아님"의
URL 수준 표현, 실패·지연이 몰리는 자리, 모바일 지연 로드.

**ADR-0058이 상세 응답에 실으라고 한 것과 방향이 반대라는 점이 요점이다.** 그쪽은
`detail_default`, 즉 **빼는 정책**이라 요청이 실패하면 감춤이 통째로 무효가 되면서 고지까지
사라진다. 이쪽은 **덧붙이는 값**이라 실패해도 지표 카드와 설명이 그대로 남는다.

## 검증

```bash
./gradlew test --tests "*ReadApiTest*" --tests "*WireContractTest*"
```

`fourthQuarterIsMarkedAsDerived`가 유도 표시를, `doesNotCarrySyntheticPeriodBounds`가
합성 기간의 부재를, `growthComparesTheSameQuarterLastYear`가 비교 대상을 지킨다.
`detailDoesNotCarryFinancials`는 **나중에 누군가 편의로 상세 응답에 합치는 것**을 막는다 —
합치면 오류 격리가 조용히 사라진다.

```premise 실적 표가 그릴 재무가 남아 있다
psql -d stock_screener -At -c "SELECT CASE WHEN count(*) = 0 THEN '잴 것이 없었다 — is_latest 재무가 하나도 없다' WHEN count(*) FILTER (WHERE fiscal_period = 'Q4') = 0 THEN '4분기 행이 없다 — 유도 경로가 멈췄다. 실적 표의 분기 탭이 통째로 한 해씩 비어 보인다' WHEN count(*) FILTER (WHERE fiscal_period <> 'FY' AND operating_cash_flow IS NOT NULL) > 0 THEN '분기 영업활동현금흐름이 ' || count(*) FILTER (WHERE fiscal_period <> 'FY' AND operating_cash_flow IS NOT NULL) || '행 저장돼 있다 — ADR-0056이 금지한 값이고 3개월인지 누적인지 모른 채 화면에 나간다' ELSE 'OK ' || count(*) || '행(FY ' || count(*) FILTER (WHERE fiscal_period = 'FY') || ' · 분기 ' || count(*) FILTER (WHERE fiscal_period <> 'FY') || '), 분기 OCF는 저장되지 않았다' END FROM financial_statement WHERE is_latest"
```

**분기 OCF를 위반으로 세는 것이 요점이다.** 값이 채워지는 순간 실적 표가 그것을 그리는데,
**3개월인지 누적인지 미확인이라 4배나 1/4로 조용히 틀린다.** 고지 문구는 "비어 있는 것이
정상"이라고 말하고 있으므로, 채워지면 그 문구도 함께 거짓이 된다.

## 배경

이 저장소에서 화면에 도달하는 재무는 **계산된 지표뿐**이었다 — `financial_statement`를 읽는
경로가 지표 러너와 배치에만 있었고 `web` 계층에는 하나도 없었다. 사용자가 EPS·조정EPS·
조정EBITDA·조정순손실·지속사업순손실 같은 **실적발표 수치**를 요청한 것이 계기다.

**요청한 다섯 중 이번에 화면에 닿는 것은 EPS 하나이고, 그것도 지표(`EPS_TTM`)로 간다.**
조정 셋은 위 표의 이유로 못 넣고, 지속사업순손실(계속영업손익)은 **소스에서 오는지 잰 적이 없어**
탐침 뒤로 미뤘다. 그래서 실적 표가 실제로 그리는 것은 매출·영업이익·순이익·현금흐름·
자산·부채·자본이며 **요청 목록에 없던 항목들**이다. 그 어긋남을 여기 적어 둔다 —
"실적 원수치를 넣었다"가 "요청한 다섯을 넣었다"로 읽히면 다음 세션이 나머지를 찾지 않는다.

## 버린 대안

- **`StockDetailResponse`에 필드로 더한다** — 요청이 하나로 줄지만 재무 조회가 실패하면
  상세 전체가 500이 된다. 지지·저항을 뺀 이유와 같다.
- **`IndicatorDataReader.readQuarterlyFigures`를 재사용한다** — 같은 테이블이지만 답하는 질문이
  다르다. 저쪽은 지표 러너의 **읽기 창**이라 `LIMIT`으로 행 수만 맞춰 **연도 구멍이 보이지 않고**,
  `FinancialFigures`에 정정 회차가 없다.
- **기간을 `period_end`로 라벨링한다** — 합성값이라 위 규칙이 막는다.
- **소스 EPS(`financial_statement.eps`)를 표에 함께 그린다** — 대조가 이 컬럼의 존재 이유이지만
  **`is_latest` 10,963행이 전량 NULL**이라 열 하나가 통째로 `—`가 되어 수집 결손처럼 보인다.
  탐침이 기본·희석 주당이익을 열면 그때 되살린다.
- **`asOf`를 받는다** — 지표는 거래일마다 값이 다르지만 재무는 **공시된 기(期)** 단위라 기준일이
  뜻을 갖지 않는다. 받으면 "그날 기준 재무"라는 없는 개념이 생긴다.

## 결과

- **쉬워지는 것**: 지표가 왜 그 값인지 사용자가 원수치로 되짚을 수 있다. 정정본 여부와
  유도 여부가 화면에 있어 "왜 이 값이 바뀌었나"에 답할 자리가 생긴다.
- **어려워지는 것**: 재무를 읽는 경로가 둘이 됐다(지표 러너의 창 / 표시 이력). 새 컬럼을
  더할 때 양쪽을 함께 봐야 한다.
- **되돌리려면**: 엔드포인트·DTO·조립기·`StockReader.financialsOf`와 프론트 패널을 걷는다.
  `YearOverYear.between`은 남겨도 무해하다.
