# A2 — 미국 상장 목록이 Alpaca 「거래 가능 목록」에 묶였다 · 계획 v1 (2026-10-05 23시)

## 실측 (Alpaca 목록 2 · 단건 9 · 봉 6 · SEC frames 2 · companyfacts 2 · 운영 SELECT · 탐침 `a2/A2ListingProbeLiveTest.java` — 커밋 안 한 일회 시험)
- **Alpaca 목록이 상장 증권을 통째로 안 준다**: `/v2/assets?asset_class=us_equity`(상태·거래소 인자 없음) 33,550 · `status=inactive` 19,169 — **둘 다 ET·DDS·SUN·USAC·ASND가 없다**. 단건 `/v2/assets/{s}`는 다섯 다 `exchange NYSE|NASDAQ · status inactive · tradable false`로 준다(ET만 `ptp_no_exception` — 나머지는 그 속성도 없다) · 시세는 나온다(09-22~10-02 9봉 · 대조군 TAP은 목록·단건 둘 다 active).
- **SEC `company_tickers_exchange.json`(`SecTickerCikCache`가 이미 받는 파일)의 NYSE·Nasdaq 7,679 중 Alpaca NYSE/NASDAQ 목록에 없는 것 986**: 목록에 AMEX 273 · ARCA 145 · OTC 4로 있는 것(SEC는 NYSE American·Arca도 「NYSE」로 적는다 — UEC·SEB·PHYS) · **목록 어디에도 없는 것 564** — 그중 창 안 봉 있는 것 39.
- **우리 `stock`(미국 8,504 · 08-29 적재 그대로)에 있으면서 Alpaca 활성 목록에 없고 SEC가 NYSE·Nasdaq로 적는 것 43**(주식수 있는 것 31) — `a2/us-stock-sh.tsv`. 순위 경계(10-02 c 로그 1000위 약 52.4억 달러)를 넘는 것은 **ET 704.8억 · DDS 102억 둘**(지금 편입) · 경계 아래 근처 SUNC 39.1억 · USAC 36.8억 · DBRG 30.0억.
- **뿌리**: `UsMarketCapUniverseAdapter.fetchConstituents` → `AlpacaUniverseAdapter.fetchActiveAssets`(`status=active&exchange=NYSE,NASDAQ`)를 상장 목록으로 쓴다 — 그것은 브로커의 거래 허용 목록이지 상장 사실이 아니다(ADR-0086 §규칙은 「`tradable`로 거르지 않고 `status`로 거른다」였는데 **`status`도 거래 허용**이었다 — 가짜 한계). → 소스가 지금 그대로면 **토 10-10 리밸런싱이 ET·DDS를 규칙과 무관하게 뺀다**.
- **A2-b — `stock` 표가 08-29 뒤 안 자란다**: 미국 `stock.created_at > 09-01` 0행 · `US_LISTED` 편입 행 마지막 생성 08-29(비활성이라 리밸런싱 안 돎). Alpaca 활성 NYSE/NASDAQ인데 `stock`에 없는 것 **134**(창 안 봉 126 · 대부분 SPAC·ETF·워런트 · 큰 것 VYLR 하루 9.69억 달러 2봉 · ACCV 1.56억 3봉 — 상장 직후라 XBRL 주식수가 아직 없다). 새 상장은 첫 10-Q 뒤에도 **`stock` 행 · 재무 적재 길이 없어** 순위 후보가 원리적으로 못 된다.
- **A2-c — 주식수 없는 큰 종목**: SUN(Sunoco LP · 재무 37행 · 주식수 0행) — SEC는 `dei:EntityCommonStockSharesOutstanding` 없이 `us-gaap:LimitedPartnersCapitalAccountUnitsOutstanding` 51,517,198(2025-12-31 10-K)을 준다(대조군 ET는 dei 3,443,299,601 · LP 단위 태그도 함께) · ASND(재무 0행 — 20-F 발행사, 관문의 6-K 축).

## 결정 v1 — A2-a만 토요일 전(A와 같은 어댑터 · 같은 배포)
1. **후보 = Alpaca 활성 목록 ∪ 「숨은 상장」** — 숨은 상장 = `stock`(미국 · `is_active`)에 있고 저장 주식수가 있으며 **SEC 파일이 NYSE·Nasdaq로 적고** Alpaca 활성 목록에 없는 티커. 그 티커마다 **단건 `/v2/assets/{s}`로 거래소를 확인**(NYSE·NASDAQ만 · AMEX·ARCA 이전은 뺀다 — SEC 라벨은 그룹이라 못 가른다) · 이름도 단건 응답(증권 수준 — DDT가 `Capital Securities`로 이름 축에 걸린다). 살아 있음은 기존 「창 안 종가」 축이 판정(봉 없으면 「종가없음」).
   - 콜: 지금 43 · 상한을 둔다(넘으면 MALFORMED — 목록이 크게 비면 Alpaca 목록 자체가 깨진 것).
   - 로그: 숨은 상장마다 티커 · Alpaca 단건 상태 · 거래소를 INFO로(귀속 재료).
2. **`AlpacaUniverseAdapter`(US_LISTED)는 이번에 안 바꾼다** — 비활성이라 안 돈다(A2-b 몫).
3. **ADR-0086 §규칙 「`status`로 거른다」를 고친다**(ADR-0169 옆 새 ADR 또는 0086 개정 — 사용자 승인 필요 여부: 0086 결정 문장 「NYSE·NASDAQ 상장」은 그대로이고 바뀌는 것은 상장을 재는 소스다 → 에이전트 결정 · 기록만).

## 미룸이 아닌 순서 — A2-b · A2-c는 B 다음
- A2-b(새 상장 → `stock` 행 + 재무 적재 길)와 A2-c(LP 단위 주식수 · 20-F)는 **토요일에 새로 틀어지는 칸이 없다**(지금도 같은 상태 — 칸 수는 재지 않음). A2-a는 토요일에 ET·DDS 두 종목 × 지표 전 칸을 새로 지운다. 순서 근거는 「토요일에 새로 생기는 결손」이다(크기가 아니다).

## 드라이런 규칙 v1(대조 전 커밋)
- RA0: c(A + A2-a) 사본에서 `universe-restore --dates=2026-10-02` 쓰기 없는 재생 → ET·DDS가 후보(종가·주식수) · 숨은 상장 INFO 43 근처 · DDT는 이름 축 · c 대 A만 사본의 명부 차이 = ET·DDS + 경계 밀림(밀려난 둘을 경계 로그로).
- RA1: 단위 시험 — 숨은 상장 합류 · AMEX 이전 제외 · 단건 404 처리(제외 + 로그 · 던지지 않음? 반증으로 정한다) · 상한 초과 MALFORMED · SEC 파일에 없는 티커는 안 붙음.
- RA2: R0의 9날짜 재생을 A+A2로 다시 — 미귀속 0(새 귀속 갈래 「숨은 상장」).
