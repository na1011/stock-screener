# 후보 모드 손 FR — 계획 v1 (2026-10-16 오전)

관문 순서 10(`docs/reload-gate.md`)이 「순위 후보도 재적재한다 · 손 경로가 아직 없다 · 관문 전에 후보 모드 손 FR을 만든다」를 적어 두었다.
클래스 축(ADR-0179)의 진입 줄(`V`·`BRK.B`·`STZ`)은 전부 후보다. 관문에서 후보를 켠 채로 다시 적재하지 않으면 재료(`sec_share_class_input`)가 없고,
판독기는 그 줄을 지금 경로로 읽는다. 지금 경로는 주식수가 NULL이라 순위에 못 든다. 주식수 매핑을 바꾸는 다른 관문 항목도 같은 처지다
(`overwritable()`이 거짓이라 후보의 옛 행이 안 고쳐진다).

## 실측 (★ = 내가 잼 · 2026-10-16 · 운영 SELECT · SEC 1콜)

- ★ 미국 순위 후보(`StockRegistrar.activeMembershipExists`의 부정 · 미국 · 활성) 7,504종목. 그중 재무 행이 있는 것 4,128 · 주식수 행이 있는 것 3,963.
- ★ 재무 행이 있는 후보 4,128 중 SEC 티커 표(`company_tickers.json` · 10-16 판 · 점 → 하이픈)에 있는 것 4,073.
- ★ 코드:
  - `FinancialRefillJob`에 `Population.CANDIDATES`가 이미 있다. 그런데 공개 진입 둘(`runForCandidateConsumption` · `runForCandidates`)이 다 티커 필수이고,
    연수는 기본 창, 이른 판은 거짓, `TriggeredBy.GAP_FILL`로 고정이다.
  - `BootstrapRunner`의 `financial-refill`은 `MEMBERS`만 부른다. `KNOWN_OPTIONS`에 모집단 인자가 없다.
  - `CandidateIntake`가 SEC 매핑 있는 티커만 고른다(`SecTickerCikCache.freshSnapshot` · 판을 못 받으면 던진다).
  - **`FinancialRefillJob.scopeOf`가 「티커 없음 · 유니버스 없음 · 연수 ≥ 기본」을 `FULL`로 선언한다** — 후보 전량 실행을 그대로 태우면
    정기 FR 시계를 민다. 편입 1,000의 정기 실행이 안 돈 채로 시계가 「돌았다」가 된다.
  - 종목 표지 `stock_job_refresh`(ADR-0177)는 `regularReach`일 때 종목마다 찍는다. 소비자는 `StockRefreshStore` 하나다(grep · `src/main/java` · `ops` · 훅).
    따라잡기는 활성 편입만 고르고 `refreshed_at < joined_at`이면 다시 집는다 — 후보에 찍힌 표지는 그 종목이 나중에 편입되면 무효가 된다(해 없음).
    같은 파일의 `wideRunStartedOn`이 `scope = 'FULL'`을 「그날 넓은 실행이 돌았다」로 읽는다 — 결정 3이 막는 둘째 자리다.

## 결정

1. `BootstrapRunner`에 `--population=candidates`를 더한다.
   - `financial-refill`에서만 받는다. 다른 잡에 붙으면 거절한다(조용히 무시되면 준 사람이 후보가 적재됐다고 믿는다 — `insert-earlier-vintages`와 같은 사유).
   - `--universe`와 함께 오면 거절한다(두 모집단 갈래).
   - 값은 `candidates` 하나. 다른 값은 거절한다.
2. `FinancialRefillJob`에 공개 진입 하나를 더한다: `runForCandidatesManually(asOf, MANUAL, yearsBack, tickers, insertEarlierVintages)`.
   - 티커가 비면 **후보 전원 중 SEC 매핑에 있는 것**을 고른다. 티커가 있으면 지금의 `resolveCandidates` 그대로(편입이 있는 티커는 빠지고 WARN).
   - SEC 매핑 거르기는 `CandidateIntake`의 것을 한 자리로 옮겨 둘이 같이 쓴다(중복 구현 금지).
3. **범위 선언**: `scopeOf`가 모집단을 받는다. 후보면 언제나 `NARROW(ARGS)`다 — 후보 실행은 정기 FR의 범위가 아니다.
4. 무효화·알림·백필 이음은 지금 경로 그대로다. 후보는 창 밖이라 알림의 「창 밖」 줄로 나간다(ADR-0178 규칙).
   손 FR 뒤 `share-basis-derive` 재판정은 `REDERIVE_SHARE_BASIS_AFTER`의 `financial-refill`이 이미 덮는다.

## 버린 대안

- **`--universe=US_LISTED`**: `US_LISTED` 밖 후보(Alpaca 목록이 빠뜨린 상장 · 화해에서 편출된 종목)를 버린다. ADR-0178 §버린 대안이 같은 이유로 기각했다.
- **티커 목록을 손으로 뽑아 `--tickers`**: `MEMBERS` 갈래가 「창 편입과의 교집합」이라 후보가 전부 빠진다. 후보 갈래에 티커를 주는 길도
  4,073개 인자가 된다 — `BootstrapRunner`의 쉼표 인자 규칙상 가능하지만, 관문 날 손으로 목록을 뽑는 단계가 술어를 다시 짓는 자리가 된다.
- **`runForCandidates`를 그대로 BootstrapRunner에서 부름**: 연수(11년) · 이른 판 · MANUAL을 못 준다.

## 드라이런 (싼 것부터)

1. 단위: `BootstrapRunner` 인자 거절 시험(다른 잡 · `--universe` 동시 · 모르는 값) · `scopeOf` 후보 = NARROW.
2. 통합(빈 시험 DB 시드): 후보 셋(매핑 있음 둘 · 없음 하나) + 편입 하나 → 티커 없는 후보 실행이 매핑 있는 후보 둘만 대상, 편입 0, 실행 행 범위 NARROW.
3. 작은 DB(스키마 + `V`·`STZ`·편입 대조 하나 · 꺼진 상태): `--job=financial-refill --population=candidates --tickers=V,STZ --financial-years=11 --insert-earlier-vintages=true`
   한 판 → 실행 기록 · 콜 수 · `batch_execution.scope`가 NARROW인지. 켠 상태 한 판을 더해 `sec_share_class_input` 두 줄 · 인스턴스 받기가 도는지.

## 반증에 여는 공격로

- 후보 전량 실행이 시계·신선도·알림 표면(`JobFreshnessPolicy` · 관리 화면 미해결 건수) 중 무엇을 움직이는가.
- 후보 4,073 실행 중 편입이 생기면(토요일 리밸런싱과 겹침) 대상이 실행 시작 때 고정되는가.
- 관문 순서에서 후보 FR을 편입 FR 앞에 둘지 뒤에 둘지가 복원·보류에 영향을 주는가.

---

## v2 — 두 렌즈 반증 「고쳐서 간다」를 내가 재서 닫음 (2026-10-16 오전 · 다시 띄우지 않는다)

### 다시 잰 수 (★ 운영 SELECT · 코드가 읽는 `company_tickers_exchange.json` 10-16 판 1콜)
- ★ 후보 7,504 중 SEC 매핑 있음 **5,633**(v1의 4,073은 「재무 행 있음 ∩ 매핑」이었다 — 술어와 잰 집합이 달랐다) · 재무 행 있음 4,128 · 매핑 밖인데 재무 행 있음 **55** · 매핑 ∪ 재무 = **5,688** · 그중 SIC 없음 **433**.
- ★ 후보 ∩ 보존 창 편입 = **214** — 과거 편입은 전부 활성 종목이라(창 안 비활성 과거 편입 0) 후보 실행이 과거 편입 `--tickers` 실행을 통째로 덮는다.
- ★ 재무 행 없는 매핑 후보 1,560 중 표본 100(시드 20261016 · SEC 100콜 · 404 12) 앵커 후보 82건 → 약 1,300건(95% 860~1,750) — 0단계 합계는 약 18,000건.
- ★ `FinancialWriter:674`·`:971` — 양수 저장 주식수는 FILL_MISSING 재적재로 안 바뀐다(관문 갈래 예외 0곳). v1 머리말의 「주식수 매핑을 바꾸는 관문 항목도 같은 처지」는 거짓이었다.
- ★ `ShareClassCollector.fetchInstances` — 받기 실패는 `FAILED` 행 + 로그뿐이고 `RATE_LIMITED`면 남은 공시는 행도 없이 건너뛴다. FR note·시장 완료·소비 장부 어디에도 안 닿는다. 후보는 정기 FR이 없어 다음 공시까지(약 한 분기) 그 공시가 숨김으로 남는다 — **클래스 축 구현의 결함**(같은 축 · 이번에 고친다).

### 결정 (v1을 대체)
1. **모집단 = 후보 ∩ (SEC 매핑 ∪ 재무 행 있음)**. 매핑 밖 55는 어댑터를 태워 그 실패(NOT_FOUND 등)를 종목 이름으로 남긴다 — 거르면 옛 규칙의 행이 기록 없이 순위에 남는다. `CandidateIntake`도 같은 술어로 바꾼다(새 행은 재무가 없어 「매핑」과 같다 — 동작 불변).
2. **업종이 먼저다**: 후보 손 FR은 대상 중 SIC 없는 것에 `company-profile`(후보 갈래)을 먼저 돌린다 — 인입과 같은 순서. 순위 어댑터는 SIC 없으면 비사업회사 거름을 통과시킨다.
3. `--population=candidates`(financial-refill 전용 · `--universe`와 함께면 거절 · 다른 값 거절). 티커가 없으면 후보 전원, 있으면 그 티커 ∩ 후보. 실행 범위는 언제나 `NARROW(ARGS)`(`scopeOf`가 모집단을 받는다 — 3인자 판은 남긴다).
4. **클래스 축 미해결을 표면에 올린다**(관문 켬에서만 도는 갈래):
   - `RATE_LIMITED`로 멈추면 남은 앵커 후보를 `FAILED · RATE_LIMITED` 행으로 적는다(콜 없음). 그러면 「미해결 = 지금 읽기 판의 `FAILED` 중 `UNREADABLE` 아닌 것」이 표 하나로 선다.
   - 어댑터 → `FinancialFetch` → Writer `Outcome`에 클래스 축 미해결 종류를 싣고, FR·초기 적재가 시장 완료에 「클래스 축」 층으로 센다(보충과 같은 장치 · 다른 이름). 종목 표지(ADR-0177)는 둘 다 비어야 찍는다.
   - 미국 공시 소비가 그 공시의 클래스 축 행이 미해결이면 소비하지 않고 사유를 남긴다 — 후보는 이 길이 아니면 다음 공시까지 다시 안 받는다.
   - 관문 판정에 「클래스 축 미해결 0」을 클래스 축·기저 판정 앞에 둔다(SQL 한 줄).
5. **관문 순서(10단계 고쳐 씀)**: 편입 재적재(11년 · 이른 판) → **후보 손 FR**(`--population=candidates --financial-years=11 --insert-earlier-vintages=true` · 과거 편입 `--tickers` 실행은 뺀다 — 후보가 덮는다) → 보충·클래스 축 미해결 판정(재시도는 같은 갈래 `--population=candidates --tickers=…`) → 클래스 축·기저 판정 → 거버넌스 → 백필 → 복원. 후보 몫 실패 0을 복원 앞의 문으로 둔다.
6. **동기를 좁힌다**: 후보 재적재가 닫는 것 = 클래스 축 재료 · NULL 채우기 · 새 라벨·정정 행. 양수 주식수를 바꾸는 관문 항목은 재적재로 안 닫힌다 — reload-gate에 따로 연다(Writer 예외 · 관문 설정 뒤).

### 이 계획 밖으로 넘기는 것(같은 축 · 다음 차례)
- **후보 순위 효과 스윕**(반증 B-2): 관문 측정 도구(2단계)를 후보까지 넓혀 「후보 순위 주식수 전/후 · 숨김 수 · 문턱을 넘나드는 종목 · `guard-rebalance-on-dirty-rank.sh` 이상치」를 재적재 전에 잰다 — 재료가 후보 companyfacts 5,633 + 인스턴스 약 14,000이라 관문 2단계 「그날 새로 받기」에 싣는다.
- **후보 ⑧**(반증 B-3): 관문 사본 드라이런(9단계)에서 후보 표본에 후보 소비 인자(기본 창 · 이른 판 거짓)로 한 판 더 — 교정·회수·신규 0.
- `ops/apply-window/04-refill.sh`(세 번째 `run` · 재시도 · 기대 실패 · 대상 수)는 관문 2단계 「`ops/apply-window`를 이 관문 기준으로 고쳐 쓰기」에서 후보 갈래로 고친다 — reload-gate에 이름으로 싣는다.

### 드라이런 (v1을 대체)
1. 단위·통합: 인자 거절 · `scopeOf` 후보 = NARROW · 대상 술어(매핑 ∪ 재무 · 편입 제외) · SIC 없는 대상에 업종 먼저 · 클래스 축 `RATE_LIMITED` 남은 공시 행 · 소비가 미해결 클래스 축 공시를 안 먹음 · 끔에서 클래스 축 표를 안 부름.
2. 작은 DB(스키마 + 후보 넷(`V`·`STZ`·매핑 밖 재무 있음 하나·SIC 없음 하나) + 편입 하나 · 그 밖의 미국 활성 종목은 그 DB에서만 비활성): **관문 명령 그대로(티커 없음)** 켬으로 한 판 → 대상 수 · 실행 범위 · 업종 실행 · 클래스 축 재료 · 실패 이름 · 두 번째 판 계수 0 · 유도 시간(줄당).
