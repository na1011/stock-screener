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

## v1 반증 판정 — 계획 밖 렌즈(2026-10-05 23시 · 「수정 후 진행」 · `refute-a2/`)
1. 「시세는 나온다」는 전환 뒤를 잰 적이 없다(오류 4) — 전환은 10-03 21:14(#703 · ET·DDS 담음) ~ 10-05 21시 · 기업행위·배당도 같은 질문 → 배포 관문으로.
2. 숨은 상장이 폐지 방어(ADR-0086 `status=active`가 막던 2,163)를 잃는다 — 43 중 개명 전 옛 티커 7(AIXC·DOMO·FGNX·HVII·HWH·VRME·BRR · SEC 파일이 옛·새 티커 둘 다 싣는다) · 마지막 봉 이른 것 DOMO·TBPH 09-23 · DBRG 09-29(SEC 라벨이 적어도 12일 늦다) · 10-10 실행에서 낡은 종가 후보 다섯(이번엔 전부 경계 아래 — 명부 칸 0 · 기제는 산다) → **창의 마지막 세션(후보 전체 최대 봉 날짜)에 봉이 있을 때만** · **같은 CIK의 다른 티커가 Alpaca 활성이면 개명으로 보고 제외 + 로그** · RA1에 둘 · RA0(창 09-22~)은 DOMO류가 들어와도 초록이라 도달 증거가 못 된다.
3. A2-c 순서 근거가 크기가 아니다 — SUN 단위 수 역산(분배금 ÷ 주당분배 · 같은 companyfacts) 6M 2026 199.8M · FY2025 179.6M → 시총 약 149억 달러(경계 2.8배) · LP 태그 51,517,198은 약 1/4(받으면 38.5억 — 경계 아래의 「그럴듯한 숫자」) · 대조군 ET는 LP 태그 = dei · 「dei 하나에 묶였다」는 SUN에만 맞음(ASND는 재무 0행 · 실패 0 — XOM 모양) · 그 축 전체 = 재무 있는데 2년 안 주식수 0인 활성 미국 250(V·BRK·STZ·ERIE·PLNT·RYAN·WMG … `q1-noshares.out`) — ADR-0099 §미룬 문(09-14 사용자 결정 「닫는다 + 재개 조건」)이 걸려 있다.
4. A2-b 「새로 틀어지는 칸 없음」은 쟀다 — 새 상장 134 중 실질 최대 HOS 11억 · 08-29 뒤 개명 8 CIK 중 현 편입 0 → 지금 0칸 · 기제(편입 종목이 개명하면 리밸런싱이 뺀다)는 있다.
5. 안 센 소비자: `SecTickerCikCache`는 `Map<String,Long>`만 들고 거래소를 버린다(`:67` — 고치면 main 3 · 시험 9) · 단건은 `AlpacaSecurityMasterAdapter:112`(trading base)가 이미 부른다 — 재사용 · 복원 평면의 과거 숨은 상장은 실행 시각 SEC 상태에 따라 달라진다(관문 복원 귀속 때 부류별로).
6. ADR-0086은 선다 — 사용자 문장(§결정)은 「NYSE·NASDAQ 상장 · 시세 Alpaca · 재무 SEC」이고 `status=active`는 §규칙(에이전트 판단) → 「에이전트 결정 · 기록만」은 선다 · 새 ADR에 대체 규칙과 사정거리.
- A와 만나는 자리 회귀 없음(쟀다): 숨은 상장의 CIK 그룹에서 대표가 바뀌는 쌍 0(DDS 13.2억 대 DDT 0.019억 · SUN·SUNC는 CIK 다름) · 탐침 봉 461심볼 4,037봉 전부 `v` 있음.

## 전환 뒤 실측 (2026-10-05 23시 30분 · Alpaca 4콜 · `a2/A2PostFlipProbeLiveTest.java`)
- **시세**: 오늘(10-05 · 전환 뒤) IEX 시간봉이 ET 20.47·20.515 · DDS 657.23 · SUN 74.15로 나온다(대조군 TAP 36.12·36.28 · USAC는 아직 0봉) · SIP 일봉은 10-02까지(10-03·04 주말 · 「최근 SIP」는 무료 구독이 막는다 — 오늘 일봉 확인은 10-06 13:30 일일 갱신 뒤 `price_daily`의 10-05 행으로 콜 0).
- **기업행위**: `/v1/corporate-actions?symbols=…`가 전환 뒤에도 ET(02-06 · 05-08 · 08-07 …) · DDS(12-12 특별 30 · 분기 0.3) · SUN · USAC 배당을 준다(대조군 TAP 같음).

## 계획 v2 (2026-10-05 23시 30분)
1. **숨은 상장** = 아래 전부:
   - `stock`(미국)에 있고 저장 주식수가 있다(기존 후보 조건 그대로).
   - `SecTickerCikCache`의 거래소가 NYSE·Nasdaq(캐시가 거래소 칸을 들게 고친다 · 티커 `.`→`-` 정규화).
   - Alpaca 활성 NYSE/NASDAQ 목록에 없다.
   - **같은 CIK의 다른 티커가 Alpaca 활성 목록에 없다**(있으면 개명 · 제외 + INFO).
   - **단건 `/v2/assets/{s}`**(`AlpacaSecurityMasterAdapter`의 trading 호출 재사용 · 캐시 안 거침): 거래소 NYSE·NASDAQ ∧ 이름 비지 않음. 404 → 제외 + INFO · 이름 빔 → 제외 + INFO · 5xx·429·타임아웃 → **던진다**(리밸런싱이 이전 구성을 지킨다 · ADR-0008).
   - **창의 마지막 세션(후보 전체 봉의 최대 날짜)에 봉이 있다** — 없으면 제외 + INFO(폐지·정지 방어).
   - 상한 100(넘으면 MALFORMED — 목록이 크게 깨진 것).
2. 단건 콜은 숨은 후보 수만큼(10-02 기준 정의대로 약 25 이하) · 복원은 같은 실행 안에서 단건 결과를 심볼마다 한 번만 받는다(격자점마다 다시 안 부른다).
3. `SecTickerCikCache` 하루 갱신(B v6 결정 10과 같은 변경 — 한 번만 구현).
4. 새 ADR(ADR-0171 「미국 상장 = Alpaca 목록 ∪ 숨은 상장」): 뿌리 = 「목록이 단건이 아는 자산 일부를 상태와 무관하게 뺀다」 · ADR-0086 §규칙 `status=active` 문장 고침 · 사정거리(창 마지막 세션 봉 · 개명 제외 · 과거 날짜 복원은 실행 시각 SEC 상태).
5. **배포 관문**: 10-06 13:30 뒤 `price_daily`에 ET·DDS의 `2026-10-05` 행 · 14:00 기업행위가 ET·DDS를 실패 없이 돎 — 둘 중 하나라도 아니면 진짜 한계 갈래(시세가 끊긴 종목을 명부에 둘 수 없다)로 다시 짠다.
6. **A2-b · A2-c 순서**: A2-b는 지금 0칸(쟀다). A2-c는 SUN 하나로도 하루 약 2×131칸이고 더 큰 축(주식수 0 활성 250 — V·BRK)이 ADR-0099 사용자 결정에 걸려 있다. → (가)와 묶어 「SEC 원자료 경로를 공시 XBRL 인스턴스까지 넓힌다」 루프로 본다 — 인스턴스는 축이 붙은 팩트(클래스별 dei)를 준다는 것이 ADR-0099 재개 조건 2(「결손의 다른 원인이 열린다」)에 닿는지를 그 루프가 먼저 잰다(사용자 결정 위의 일이라 재개 전에 보고한다).

### 드라이런 규칙 v2(대조 전 커밋)
- RA0(종목별 기대 · 10-02 재생 c 대 A만 사본): **들어옴** ET·DDS(경계 위 → 명부) · GWH·SUNC·USAC·WALD(새 봉 · 경계 아래) / **빠짐 + INFO** AIXC·DBRG·DOMO·FGNX·FSEA·NSTS·TBPH·VRME(마지막 세션 봉 없음 또는 개명) · GETY·DRCT·TWOD(단건 OTC) · DDT는 들어오되 이름 축(`Capital Securities`)에서 빠짐 · 명부 차이 = ET·DDS 둘 + 경계 밀림 둘.
- RA1(단위): 숨은 상장 합류 · 마지막 세션 봉 없음 제외(DBRG 모양 — v1 코드에선 들어옴 · 대조군) · 같은 CIK 활성 티커 → 제외 · 단건 404 제외 · 5xx 던짐(잡 수준 명부 불변) · 이름 빔 제외 · 거래소 AMEX 제외 · `.`/`-` 정규화 · 상한 초과 MALFORMED.
- RA2: 9날짜 재생을 A + A2로 — 미귀속 0(갈래 하나 더: 「숨은 상장」).

## v2 반증 판정 — 렌즈 A·B 한 반증자(2026-10-06 00시 · 둘 다 「수정 후 진행」 · `review-a2v2/`)
- **A1 개명 규칙은 뺀다**(오류 4): 지금 숨은 29(SIC 전) 중 그 규칙에 걸리는 11(AIXC·DOMO·FGNX·FGNXP·HWH·JSPRW·KLXER·NTRBW·PDYNW·RILYN·VRME)은 **전부 10-02 봉이 없다** — 마지막 세션 규칙이 이미 다 뺀다(이 규칙만의 몫 0) · 위험: 현 편입 45(CHTR·CMCSA·T·TMUS·DUK·SO·MSTR·KKR·PRU·HBAN …)는 같은 CIK의 활성 기호가 baby bond·우선주·워런트뿐 — Alpaca가 보통주만 빼면 원래 결함 재현 · 클래스 쌍 17은 A의 대표 선택을 덮음 · 복원의 개명 전 날짜엔 옛 티커가 실제 상장(AIXC 09-22~29 6봉) · 뿌리 = 「활성 목록 ≠ 상장」이라 해 놓고 개명 판정에 그 목록을 다시 씀 → 귀속용 INFO 라벨로만.
- **A2** 단건을 봉 판정 뒤로 → 25콜이 10콜(DDS·DDT·DRCT·ET·GETY·GWH·SUNC·USAC·WALD·WALDW) · 상한은 근거 없는 100 대신 **생존자 수**에(필터 전 수는 SEC에 늦게 남는 폐지 티커로 떠다님).
- **A3** 이름 빔 → 제외는 원래 결함을 다시 만든다 → `stock.name`(08-29 같은 벤더 목록) 폴백 0콜 · 생존 10 전부 이름 있음 · DDT는 「…Capital Securities」로 저장 · `AlpacaSecurityMasterAdapter.fetchOne`(:94 private)은 `!isUsable()`이면 MALFORMED(:136-138) — 재사용하면 반대 계약 → 새 메서드 · `StockRegistrar.describe`(:91-104)는 안 건드림.
- **A4** 복원의 「실행 안에서 단건 한 번」을 받칠 기제가 없다(포트 `fetchConstituents(code, asOf)`에 실행 범위 없음 · 어댑터 싱글턴 · 필드 메모면 앱 JVM 토요일 실행이 지난주 결과를 씀) → 시각 경계 메모(TTL) 또는 포트에 세션 · 시험 「같은 실행 2회째 0콜 · TTL 뒤 다시」 · 한 호출 안 SEC 맵 참조 한 번.
- **A5** 「마지막 세션 = 후보 봉 최대 날짜」는 대부분 맞다(`clampEnd` :522-526 · 토 20:00 실행 asOf = 금 · 복원 격자는 `indicator_trade_date`) — 다만 이상봉 하나가 전체를 떨군다 → **후보 과반이 가진 최신 날짜**(0콜 · 이상봉 빈도 재지 않음).
- **A6** RA0 미등록 8(FGNXP·HWH·JSPRW·KLXER·NTRBW·PDYNW·RILYN·WALDW) · TWOD(SIC 6798)·COLA 셋(6770)은 단건 전에 SIC로 빠짐 — 「단건 OTC」 귀속은 틀림 · 종목마다 축 하나 · GETY·DRCT OTC와 DDT 이름은 실행 때 관측값 기록 · RA2에 외부 오라클(관측 원장 대비 ET·DDS가 9날짜 전부 돌아오는가 — A의 R1이 「모든 날짜에 빠진다」를 잼).
- **A7** 「활성 목록 = 상장」 전제 문장 15파일(main 3 · 시험 6 · 문서 6 — ADR-0135·0071 · `reload-gate.md` · `api-spec/alpaca-universe.md` · `UsDelistedUniverseGapProbeLiveTest` …) — v1 반증 #5 목록이 v2에서 빠짐.
- **B1 결정 6은 묶어서 판정(오류 1) · 사용자 결정 범위를 넘김**: ADR-0099 §미룬 문(:334-375)이 닫은 것은 「주식수 폴백」뿐 · MUST NOT(:366-368)은 ②복수 클래스 · ③ADS가 그 문에 안 걸린다고 적음 · 250의 원인(이름 패턴 대략): 비보통주 101 · 외국/ADS 78 · 클래스 25(BRK.B·ERIE·WMG) · LP 6(SUN·KRP·BSM) · 기타 39(V·BAM·STZ) · 같은 묶음 안 모양도 다름(ARGX 재무 1행 2019 · V 37행 주식수만 NULL) · 순서 근거 없음 → 구성원마다 원인 · 경계 위 수로 순서.
- **B2 하류는 선다**: 13:30 · 14:00 · 배당은 현상 유지 · 시세가 끊기면 마지막 세션 규칙이 스스로 뺀다 · ADR-0099 premise는 ET를 대조군으로 든다(A2 없이 토요일을 넘기면 붉어지는 카나리) · SEC 거래소 라벨 기준은 선다(상장 pool 커버 4,821/4,822 · 활성 목록의 현 편입 998 전부 NYSE/Nasdaq 라벨).
- **B3 결합**: 24시간 갱신은 A2가 B보다 먼저 나가므로 구현·시험을 A2가 든다 · A의 토요일 오라클은 jar가 A+A2라 차이에 ET·DDS + 경계 밀림이 섞임 → `r0_diff`에 「숨은 상장」 갈래 또는 jar 셋 · 복원 평면을 다시 쓸 땐 A2 jar(main jar는 전환 뒤 ET·DDS를 과거 전 날짜에서 지운다).

## 계획 v3 (2026-10-06 00시 30분 · v2 반증 반영 · 계획 밖 렌즈 별도 반증 대기)
1. **숨은 풀** = `activeByMarket` 행(주식수 있음 · 통화·SIC 필터 뒤) ∧ SEC 스냅숏 거래소 NYSE·Nasdaq(`.`→`-` 정규화) ∧ Alpaca 활성 목록 밖. 이름은 `stock.name`(0콜)으로 이름 축을 먼저 태운다.
2. 종가 조회에 숨은 풀을 함께 싣고 `Quote`에 **마지막 봉 날짜**를 더한다. **마지막 세션 = 후보 과반이 닿은 최신 날짜**(이상봉 하나에 안 흔들림 · 0콜).
3. **숨은 생존자** = 마지막 세션에 봉이 있는 숨은 풀 — 없으면 제외 + INFO(폐지·정지·개명 전 티커 방어).
4. **단건은 생존자에만**(10-02 기준 10 → 이름 축 뒤 약 8) · `AlpacaUniverseAdapter`(trading 클라이언트 · 리미터 공유)에 새 메서드 — `fetchOne`의 MALFORMED 계약을 안 탄다 · 404 → 제외 + INFO · 2xx 아닌 응답·전송 실패 → **던진다**(리밸런싱이 이전 구성을 지킨다 · ADR-0008) · 거래소 NYSE·NASDAQ 아니면 제외 + INFO · 이름 = 단건 이름(비면 `stock.name`)으로 이름 축 다시 · **시각 경계 메모(6시간)** — 복원 격자 2회째 0콜 · 다음 주 실행은 다시 부름.
5. **생존자 상한 200**(10-02 생존 10의 20배 — 넘으면 목록이 2% 넘게 깨진 것 · MALFORMED).
6. **개명 판정은 하지 않는다** — 같은 CIK 활성 티커는 INFO 라벨로만(v2 반증 A1).
7. **SEC 스냅숏을 호출마다 하나로 고정**(숨은 풀 거래소와 `dropCikDuplicates`가 같은 판) · `SecTickerCikCache`가 거래소 칸을 들고 **받은 지 24시간 넘으면 다시 받는다** · 다시 받기 실패 → 옛 판 + WARN(나이) — 첫 적재 실패는 지금처럼 던진다. B가 같은 변경을 쓴다(A2가 먼저 나가므로 A2가 구현·시험).
8. ADR-0171 + 「활성 목록 = 상장」 전제 문장 15파일(main 3 · 시험 6 · 문서 6) 고침.
9. **배포 관문**: 10-06 13:30 뒤 `price_daily`에 ET·DDS `2026-10-05` · 14:00 기업행위가 ET·DDS를 실패 없이 돎.
10. **주식수 0 활성 250은 별도 측정 루프** — 구성원마다 원인(비보통주 101 · 외국/ADS 78 · 클래스 25 · LP 6 · 기타 39 — 이름 패턴 대략)과 경계 위 칸 수를 재서 순서를 정한다. ADR-0099 사용자 결정이 닫은 것은 「주식수 폴백」뿐(클래스·ADS는 그 문 밖) — 폴백을 다시 여는 갈래가 생기면 그때 보고한다.
11. **토요일 A 오라클**: jar가 A+A2라 차이에 ET·DDS + 경계 밀림이 섞인다 → `r0_diff.py`에 「숨은 상장」 갈래(INFO 줄로 귀속).

### 드라이런 규칙 v3(대조 전 커밋 · 10-02 재생 · c = A + A2 · a = A만 · 같은 사본)
- **RA0 종목별 기대**(SIC·10-02 봉 = `review-a2v2/hidden25-axes.tsv`):
  - SIC 축에서 빠짐(숨은 풀 전): COLA·COLAR·COLAU(6770) · TWOD(6798).
  - 마지막 세션 봉 없음 → 제외 + INFO: AIXC · DBRG · DOMO · FGNX · FGNXP · FSEA · HWH · JSPRW · KLXER · NSTS · NTRBW · PDYNW · RILYN · TBPH · VRME(15) — **DBRG가 생존 축 대조군**(v1 코드면 들어온다).
  - 이름 축(`stock.name`)에서 빠짐 예상: DDT(Capital Securities) · WALDW(워런트) — 단건 안 부름.
  - 단건: DDS · ET → NYSE(전환 뒤 실측 inactive · NYSE) → 후보 · 경계 위 → **명부에 듦** / DRCT · GETY → 단건 거래소 관측값 기록(활성 OTC로 목록에 있음 → OTC면 제외) / GWH · SUNC · USAC · WALD → 관측값 기록(NYSE·NASDAQ면 후보 · 전부 경계 아래).
  - 명부 차이 c − a = {ET, DDS} · a − c = 경계 밑 두 종목(경계 로그로 이름까지).
- **RA1(단위)**: 숨은 생존자 합류 · 마지막 세션 봉 없음 제외(DBRG 모양 — v1이면 들어옴) · 과반 날짜(이상봉 하나 무시) · 단건 404 제외 · 5xx 던짐 + 잡 수준 명부 불변 · 단건 이름 빔 → `stock.name` · 거래소 AMEX·OTC 제외 · `.`/`-` 정규화 · 생존자 상한 초과 MALFORMED · 메모(같은 실행 2회째 0콜 · 6시간 뒤 다시) · SEC 스냅숏 고정(호출 도중 갱신 안 섞임) · 24시간 갱신 · 갱신 실패 → 옛 판 + WARN · 첫 적재 실패는 던짐.
- **RA2**: 9날짜 재생(A + A2 대 A) — 미귀속 0 · 외부 오라클: 관측 원장에 있던 ET·DDS가 9날짜 전부 c 재생에 돌아온다(a는 R1에서 전부 빠짐).

## v2 반증 — 계획 밖 렌즈 별도 반증자(2026-10-06 00시 30분 · 「수정 후 진행」 · `refute-a2v2/`) → v3에 더함
1. **토요일 근거는 칸보다 원장**: 10-02 최신일 ET 123칸 · DDS 120칸 — 빠지면 10-12부터 거래일마다 243칸이 안 생긴다 · **기존 칸은 안 지워진다**(`IndicatorRetentionPolicy:150-153`은 날짜로만 지움 — v1의 「전 칸을 새로 지운다」는 틀림) · 밀려 들어올 것 OLLI·SHC(10-02 경계 999·1000위 · 둘 다 지금 편입 아님) · **OBSERVED 원장에 거짓 편출 2 · 거짓 편입 2행이 남고 되돌리려면 원시 SQL 행 수정(사용자 승인)** — 이것이 토요일 전의 근거다.
2. 「250」은 249 = 주식수 전량 NULL 165 · 2년 넘은 양수만 72 · 0 행 16(겹침) · V·BRK.A/B·SUN·STZ·PLNT·IMKTA·WTTR·GRDN·GEF·BELFA는 전량 NULL 쪽 · 165는 ADR-0099가 잰 「전량 NULL 165」와 수가 같다(구성원 같음은 재지 않음) · 재개 조건 1은 `companyconcept` 탐침 · 조건 2는 구성원마다 인스턴스가 다른 원인을 보일 때만 · ②복수 클래스는 애초에 문 밖(`ADR-0099:357-360`) · 보고할 것: 구성원별 원인 표 · 조건 2가 선 구성원과 근거 · 대가의 현재 크기(SIC 6770 116은 SIC 축이 이미 뺌 · 이름 축 약 26) · ADR 21은 판별축 뒤 · 「실질 여섯」 각각 · 0 행 16은 `reload-gate.md` 「주식수 0」 행과 겹침 → 한 행으로.
3. **감시 공백**: ADR-0099 premise는 손 목록 65(ET 있음 · DDS 없음) · DB엔 Alpaca 상태가 없어 「활성 목록 밖 편입」 펜스 불가 → **성질 질의**(「OBSERVED 편출 중 DB 시총 > 남은 편입 하위 5% × 1.5」 · 이력 6날짜 12회: CIK 교대 9(A가 없앰) · 폐지 2(CRNX·APGE · 마지막 봉으로 설명) · AKTX 1(ADR 배수)) · DDS 102억 > 문턱 약 85억 → 잡힘 → A2의 실패 갈래를 손 목록 없이 보는 유일한 자리 — premise로.
4. **오라클 분석기**: `r0_diff.py:52-55`는 경계 창 안 티커를 「다(경계)」로 귀속 → OLLI·SHC가 A의 경계 갈래로 조용히 들고 ET·DDS는 미귀속 → 숨은 상장 INFO 정규식 · 규칙 「경계 밀림 수 = 경계 위 숨은 진입 수」 · 고친 분석기를 A만 로그(`dry-a`)에 다시 돌려 미귀속 0 유지(대조군) · 같은 시각 두 jar 사이에 「소스 표류」 갈래를 두지 않는다 · 「만들었다」 줄 형식 유지.
5. **순서**: 「마지막 세션 봉 없음」 제외는 `dropCikDuplicates` 앞(뒤면 거래대금 큰 낡은 숨은 티커가 대표를 차지한 뒤 빠져 산 클래스까지 사라짐) — 지금 사례 0 · RA1 시험.
6. **24시간 갱신 실패는 던지고 알린다**(ADR-0008 — 낡은 맵 폴백은 라벨 지연을 조용히 남김) · 소비자에 `SecEdgarFinancialAdapter:113` · `SecEdgarCompanyProfileAdapter:100`(토요일 꼬리).
7. B 사본(RB7)은 A + A2 jar로(A2 없이 지으면 10-10 신규에 OLLI·SHC 모양 둘이 더 든다).

### v3 고침(위 반영)
- 결정 7: 갱신 실패 → **던진다**(리밸런싱이 이전 구성을 지키고 알림) — 옛 판 폴백 안 함 · 첫 적재 실패와 같은 계약.
- 결정 12(새): ADR-0171에 premise 「OBSERVED 편출 중 DB 시총 > 남은 편입 하위 5% × 1.5」(설명 갈래: CIK 교대 · 마지막 봉 · ADR 배수).
- 결정 11: `r0_diff.py`에 숨은 상장 갈래 + 「경계 밀림 수 = 경계 위 숨은 진입 수」 + A만 로그 대조군.
- RA1에 「마지막 세션 제외가 `dropCikDuplicates` 앞」 시험.

## A2 드라이런 결과 (2026-10-06 01시 · 사본 `_sc_a` · jar a = main(A만) · h = `wt-a2`(A + A2) · 결과 `dry-a2/`)
- **RA1 단위 · 변이체**: 새 시험 15건 + 기존(`UsMarketCap` · `SecTickerCikCache` · `AlpacaUniverse`) 합 56건 초록 · 「마지막 세션 무시」 변이 → `dropsHiddenListingWithoutTheLastSessionBar` · `staleHiddenTickerDoesNotTakeTheCikSeat` 붉음 · 「최댓값」 변이 → `lastSessionIsTheMajorityDate` 붉음(쟀다 · 되돌림).
- **RA0(10-02 재생 · 같은 사본에서 jar만 바꿈)** — `숨은 상장: 풀=17 마지막 세션=2026-10-02 받음=[DDS(inactive · NYSE), ET(inactive · NYSE), GWH, SUNC, USAC(inactive · NYSE), WALD(inactive · NASDAQ)] 마지막 세션 봉 없음=[AIXC(09-29), DBRG(09-29), DOMO(09-23), FGNX(09-25), FSEA(09-30), HWH(봉없음), NSTS(09-30), TBPH(09-23), VRME(09-30)] 단건 없음=[] 거래소 밖=[DRCT:OTC, GETY:OTC]` · 명부 차이(`r2_diff.py`): **+ DDS·ET(라 숨은 상장) · − OLLI·SHC(다 경계 — 숨은 상장이 밀어냄) · 미귀속 0**.
  - 사전등록 대비: SIC 축 넷(COLA·COLAR·COLAU·TWOD) ✓ · DDT·WALDW 이름 축 ✓(이름 제외 399 → 407 · DDT는 혼성 목록에) · 단건 DDS·ET NYSE ✓ · DRCT·GETY 관측값 OTC → 제외 · GWH·SUNC·USAC NYSE · WALD NASDAQ → 경계 아래 후보 · **불일치 6: FGNXP·JSPRW·KLXER·NTRBW·PDYNW·RILYN은 「마지막 세션 봉 없음」으로 등록했는데 그보다 앞의 이름 축(저장 이름의 우선·워런트·권리·노트)에서 빠졌다** — 결과(제외)는 같고 갈래만 다르다(등록 때 저장 이름을 안 열었다).
  - 단건 콜 8(생존자 중 이름 축 통과분) · DBRG가 생존 축 대조군으로 빠짐.
- **분석기 대조군**: `r2_diff.py`(숨은 상장 갈래 + 「경계 밀림 수 ≤ 숨은 진입 수」)를 A의 R0 로그(`r0-a` · `r0-c`)에 다시 → 미귀속 0 유지.
- RA2(9날짜) 진행 중.
