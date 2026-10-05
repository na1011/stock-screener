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

## v1 반증 판정 — 계획 안 렌즈(2026-10-05 23시 · 「수정 후 진행」 · `rebut-a2/`)
1. **생존 판정이 없다**(오류 4): 「창 안 종가」는 휴장 완충 10일 창(`UsMarketCapUniverseAdapter:121,469`) · 단건 `status`는 ET와 폐지 종목 둘 다 `inactive` · SEC 파일도 늦게 빠짐(DBRG 마지막 봉 09-29인데 SEC엔 NYSE) · 43 중 10-02 봉 있음 14 · 없음 29(`hidden43.tsv`) · 실제 토요일 asOf 10-09 → DBRG·AIXC·FSEA·NSTS·VRME가 낡은 종가로 후보 · 이력 사례 CRNX(마지막 봉 08-31)·APGE(09-02) → **숨은 상장엔 「asOf 이하 마지막 세션에 봉」을 요구 · 빠진 것 로그.**
2. **뿌리 문장이 틀렸다**: 상태 인자 없는 목록에도 없다 — 43 중 40은 모든 목록에 없음 · 3은 활성 OTC · 원인은 `status=active`가 아니다(빼도 ET는 안 돌아옴) · ET·DDS는 10-03 21:13(#703) 뒤에도 편입 — 목록에서 빠진 시점은 10-03~10-05 · 같은 PTP EPD·MPLX·PAA는 활성 목록에 있음 · **「시세는 나온다」는 그 변화 전 봉(≤ 10-02)만 잰 것** — 10-06 13:30 뒤 `price_daily`의 ET·DDS `2026-10-05`로 콜 0으로 잰다.
3. **실패 갈래**: 5xx·429·타임아웃은 **던진다**(리밸런싱 catch `UniverseRebalanceJob:126-138`이 이전 구성을 지킨다 — 빼고 넘어가면 원래 결함 재현) · 404는 빼고 로그(`AlpacaSecurityMasterAdapter:124`와 같은 계약) · 비활성 기록 2,166 중 이름 빈 것 386 → `isUsable()` MALFORMED면 매주 리밸런싱·복원이 멈춘다 · 기록 둘 이상 심볼 251(단건이 어느 것을 주는지 재지 않음) · `findByTicker` 재사용 금지(JVM 수명 캐시가 ET·DDS를 옛 거래소로 담음 `:86`·`UsMarketCap:359`) · 상한값 없음 · `is_active`는 거르는 것이 없다(미국 8,504 전부 참 · `delisted_on` 0) · 콜 수 43 → 정의대로면 31 → 29(`activeByMarket`) → SIC 뒤 25.
4. **SEC 라벨 실제 어긋남**: GETY·DRCT·TWOD = SEC NYSE/Nasdaq · Alpaca 활성 OTC · DOMO = SEC 이름 Huckleberry.ai · Alpaca엔 HUCK 활성 · DOMO 봉 09-23 끊김 · `SecTickerCikCache.load()` JVM당 한 번(운영 라벨 = JVM 뜬 날 파일 · 사본 드라이런은 새 파일) · 거래소 조회에도 `.`→`-` 정규화.
5. **파급**: 복원은 격자점마다 단건(약 53점 × 25) — 5xx 한 번이면 복원 전체 정지 · 과거 명부가 복원 시점의 SEC 지연에 따라 달라짐(ADR-0135:89 「폐지 종목이 안 들어온다」가 일부 바뀜) · 같이 고칠 곳 `UsMarketCap:282-285,365` · `AlpacaUniverseAdapter:162-172` · ADR-0135:89·195 · ADR-0071:353 · `UsDelistedUniverseGapProbeLiveTest:30` · `reload-gate.md:60` · `MarketCapUniverseAdapter:81` · 영향 없음: 시세·CA·배당은 심볼로 묻는다.
6. **RA**: 「43 근처」는 사전등록이 아니다 — 종목별 기대(10-02 재생: 새 봉 있는 DDS·ET·GWH·SUNC·USAC·WALD · 낡은 종가 AIXC·DBRG·DOMO·FGNX·FSEA·NSTS·TBPH·VRME) · 생존 축 대조군 = DBRG(v1 들어옴 → 고친 뒤 빠짐) · RA1에 5xx 시 명부 불변 · 빈 이름 · 다중 기록 · 정규화 · 상한.
- 재지 않음(반증자): 단건 38의 이름·거래소·404 · 상태 변화 뒤 ET·DDS 봉과 CA · 09-04·09-07 SEC 파일 · `stock`에 있고 활성 목록 밖이며 SEC 라벨 None/OTC인 60(GBTG 등) · 상태 변화가 일시적인지.
