# 편입 원자료 신선도 — 최종 계획 (2026-10-14 · 묶음 2: F6 · 재시도 자리 · 후보 쪽)

재무 동기 2부(장부 · 소비)가 **새 공시가 있는 활성 편입**만 먹인 뒤에도 남는 두 빈칸을 닫는다.
판단 기준은 「언제 실행해도 정합성이 보장되는 스크리닝 애플리케이션」 하나다.

## 0. 무엇을 고치나

1. **F6 — 편입될 때 이미 원자료가 있는 종목은 아무도 갱신하지 않는다.**
   - 리밸런싱 꼬리(`MembershipGapFiller`)는 축마다 **0행**인 종목만 채운다(재무 0행 · 국내 영업현금흐름 결손 · 거버넌스 원문 0행 — `StockRegistrar.membershipGaps`).
   - 정기 FR·FSR·G는 활성 편입만 돈다. 그래서 재편입 종목과 08-30 일괄 적재로 행을 가진 종목은 편입 뒤 다음 정기 실행까지 그 시점의 원자료와 그 시점의 코드 규칙으로 남는다.
   - 세 잡 모두 최소 간격 25일이다(화 G · 수 FSR · 목 FR — `PipelineScheduler.HEAVY_JOB_MIN_INTERVAL_DAYS` · 402~404행). 그래서 편입 종목은 최대 약 한 달 낡는다.
2. **재시도 자리 — 종목별 실패를 다음 평일에 다시 집는 잡이 없다.**
   - `batch_execution_failure.resolved`는 해결 표시에만 쓰인다(`BatchExecutionContext.resolveIfPreviouslyFailed`). 실패한 종목은 그 잡의 다음 정기 실행까지 낡는다.
   - 해결 표시가 (종목, 단계) 단위다. 그런데 **FR·FSR·initial-load가 모두 `FINANCIAL`로 적는다.** 그래서 국내 FSR 실패를 다음 날 같은 종목의 FR 성공(0행 성공 포함)이 「해결」로 지운다 — `BatchExecutionRecorder.resolveFailures` javadoc이 대가로 적어 둔 동작이다. 미해결로 재시도를 고르려면 이것이 결손이 된다. 관리 화면의 「미해결 에러 건수」(spec §4)도 그만큼 거짓이다.

## 1. 실측 (★ 10-14 내가 잼)

- ★ **F6의 한 회분 크기 = 10-11 손 FR N=2**(10-10 편입 미국 22종목 · 실행 #775 · 24.8초 · SEC 56콜 · 실패 0).
  - 신규 21 · 정정 37 · 채움 189 · 교정 174 · 무효화 5종목.
  - 1부 보충 코드와 그 사이 규칙 변경이 그 종목들에 처음 닿은 몫이다. 주마다 같은 꼴로 생긴다.
- ★ **신규 편입은 매주 두 시장에 있다**(`universe_membership.effective_from`, 09-04~10-09).
  - 미국: 32 · 7 · 11 · 15 · 14 · 13 · 22
  - 국내: 8 · 3 · 3 · 5 · 4 · 4 · 2
- ★ **2차 소비는 F6를 일부만 덮는다.**
  - 장부는 편입 여부를 거르지 않고 `stock` 표 전 종목의 공시를 쌓는다(`FilingLedgerStore.stocksByTicker` · ADR-0176).
  - 비편입 종목의 행은 잠들었다가, 편입되면 `openForActiveMembers`가 집는다.
  - 그래서 편입 전 09-24 이후 공시가 있는 종목은 편입 다음 평일 14:16에 좁힌 FR을 받는다(미국은 기본 창이라 규칙 변경까지 따라간다).
  - **남는 몫은 09-24 이후 공시가 없는 편입 종목이다.** 3분기 시즌(10월 말~11월 중순) 전에는 그런 종목이 대부분이라고 본다(재지 않음 — 14:16 장부가 쌓인 뒤 지난 편입 무리로 센다 · §6).
  - 국내 소비 FR은 창을 좁힌다(대상 행의 bsns_year까지). 그래서 국내 편입 종목의 옛 연도 규칙 변경은 소비로 따라가지 않는다.
- ★ **꼬리의 G 갈래는 원문 0행만 잡는다**(`gap_governance = no_governance AND market_has_governance`). 재편입 종목의 낡은 원문은 대상이 아니다. G는 사업보고서(연 1회)라 낡음은 3~4월 시즌 직후 편입에만 생긴다. 국내는 종목당 표 24개, 미국은 4개다(`AUTO_GOVERNANCE_STOCK_LIMIT` javadoc 실측).
- ★ **활성 편입의 미해결 실패는 지금 0이다.** 미해결 2,054건은 전부 비편입이다(initial-load 08-30·08-31 2,021건 · financial-refill 09-04·09-22 33건). 재시도 자리는 지금 닫을 결손이 없다. 다음 실패가 생기는 순간 「다음 정기까지 최대 한 달 낡음」을 막는 장치다.
- ★ **`failed_phase` 소비자**(grep `failed_phase|failedPhase|CollectionPhase.FINANCIAL` · main · frontend · api-spec)
  - 코드: `CollectionPhase` · `StockRegistrar:285`(재무 0행 결손 술어 `failed_phase='FINANCIAL'`) · `BatchExecutionRecorder` · `InitialLoadJob` · `FullStatementRefillJob` · `FinancialRefillJob` · `BatchFailureResponse` · `BatchExecutionReader`
  - 프론트: `BatchDetailSheet.vue` 열 · `types.ts:818` `failedPhase: string`. 유니온이 아니라 단계를 더해도 `assertNever`가 없다(0부의 그 결함 꼴이 아니다).
  - 마이그레이션: `ck_failure_phase`(V68이 마지막으로 고쳤다).
- ★ **진입 하한은 날짜로 못 잰다.**
  - 리밸런싱은 `effective_from`을 기준일(금)로, `created_at`을 실행 시각(토 20:00)으로 찍는다. 10-09 편입 24행은 10-10 20:00:02~27이다.
  - 금요일 14:16 실행이 「effective_from > 직전 기준일」을 쓰면 토요일 편입을 놓친다.
  - → **열린 편입 행의 `created_at` > 직전 성공 실행의 `started_at`**으로 잰다.
- ★ **후보 쪽 재무 낡음(순위 재료)은 이 묶음에서 처방하지 않는다.**
  - 미국 순위는 `JdbcStoredShareCounts`가 `financial_statement`의 최신 기말 주식수를 읽는다.
  - 10-10 편입 22종목의 순위 주식수를 FR 전(`fr-rollback-20261011/fr2-1011/base_fs.copy`)과 후로 대조했다. 최대 차이는 MKC +0.04%이고 나머지 21은 0이다.
  - 큰 주식수 오류는 낡음이 아니라 **클래스 축 dei를 못 읽는 것**이다. ARES·XMTR(현 편입)은 차원 없는 `dei:EntityCommonStockSharesOutstanding`가 각각 2019·2024에서 끝나 순위 주식수가 2024-12-31에 멈췄다(SEC companyfacts 2콜). 같은 꼴(한 분기 넘게 밀림)이 현 편입 3(ARES·HSY·XMTR) · 비편입 135다.
  - → 미국 관문 주식수 축(`reload-gate.md` 「주식수 0」의 클래스별 dei)으로 보낸다. 가짜 한계다. 값을 바꾸는 어댑터 변경이라 설정 뒤에 둔다.

## 2. 설계

### A. `entrant-refresh` — 편입 종목의 원자료를 편입 때 한 번 정기 실행과 같게 맞춘다

- **대상**
  - 활성 편입 중 **열린 편입 행의 `created_at`이 직전 성공(SUCCESS · PARTIAL_SUCCESS) `entrant-refresh`의 `started_at`보다 늦은** 종목이다.
  - 첫 실행의 하한은 상수 2026-10-10T20:00:28+09(10-10 리밸런싱 끝 — 그 편입은 N=2 손 FR이 이미 맞췄다)다.
  - 축마다 0행인 종목도 넣는다. 꼬리의 결손 갈래가 같은 실행 안에서 먼저 채우고, 이 단계가 이어 돈다. 이미 맞춘 종목을 다시 돌려도 멱등이다(D4 두 번째 실행 0행의 꼴).
- **부르는 것**: 정기 실행과 같은 창으로 좁힌 실행을 부른다. 모두 `GAP_FILL`이라 [V] 시계에 안 센다(`JobFreshnessPolicy`가 GAP_FILL을 거른다 · V65).
  - FR `--tickers`: 두 시장
  - FSR `includeQuarters=true`: 국내
  - G `--tickers`: 두 시장
  - **대상이 0이면 셋 다 부르지 않는다.** 비면 전량이 된다(FR `resolveTargets` · G `resolveTargets`의 else).
- **부르는 자리 둘** — 같은 메서드이고, 하한이 멱등을 만든다.
  1. **리밸런싱 꼬리**(`MembershipGapFiller.fillAndAlert` 끝). 토 20:00 뒤라 월 13:30 일일 갱신이 새 원자료로 최신일을 낸다. 무효화된 과거 날짜는 월 14:20 화해 ①이 창 전체를 다시 쓴다(`IndicatorReconciler` — 「종목마다 asOf 한 판으로 원본층을 보존 창 전체에 다시 쓴다」).
     - 꼬리가 기존 종목을 refill하지 않던 사유(「꼬리에 백필이 없다」 · `MembershipGapFiller` javadoc 「축별로 갈라 부른다」)는 ADR-0166 매 평일 화해로 서지 않는다. **javadoc을 고친다.**
  2. **평일 14:16 사슬**(목록 → 소비 → **이 단계** → 재시도). 꼬리가 안 돌았거나(앱이 내려 있음) 통째로 실패했으면 다음 평일에 닫힌다. 평소에는 대상 0이다.
- **상한**: 꼬리의 `AUTO_LOAD_STOCK_LIMIT`(50)과 같은 사유로 50을 넘으면 돌리지 않고 알린다. 유니버스를 다시 그은 경우는 사람이 본다.
- **잠금**: 둘 다 이미 크론 토큰(`underWriteLock`) 아래다.

### B. 재시도 자리 — 14:16 사슬의 끝

- **대상**: 활성 편입 중 단계 `FINANCIAL` · `FULL_STATEMENT`(신설 · C) · `GOVERNANCE`에 미해결 실패가 있는 종목이다.
- 단계마다 좁힌 FR · FSR · G를 `GAP_FILL`로 부른다. 성공하면 기존 `resolveIfPreviouslyFailed`가 해결로 표시한다.
- **순서**: 소비 → A → B. 앞 단계의 FR 성공이 실행 시작 때 미해결을 읽어 먼저 해결하므로 B가 같은 종목을 두 번 부르지 않는다. **B의 대상은 앞 단계가 끝난 뒤 다시 읽는다.**
- **종류를 가리지 않는다**(NOT_FOUND · MALFORMED 포함). 원장이 뒤늦게 주거나 코드가 고쳐지면 닫힌다. 하루 몇 콜이다.
- **경보**: 미해결이 평일 5일을 넘으면 WARNING을 낸다(소비 경보와 같은 문턱 · 사유는 마지막 실패 메시지).
  - **사용자 원문(10-14)**: 「원장 자체가 이랬다 저랬다 하는 종목은 특수 로직으로 호흡기 붙여 연명시키느니 아예 빼버리는게 더 나을 수도 있음을 명심해.」
  - 내 해석: 경보가 같은 종목에서 거듭 울면, 그 종목에 특수 갈래를 덧대지 않는다. 원장이 판마다 다른지를 재고, 진짜 한계면 「유니버스 제외 · 미표시」를 선택지로 올린다. 이 계획은 제외를 자동화하지 않는다 — 경보까지다.

### C. FSR 실패 단계 분리 — `FULL_STATEMENT`

- `CollectionPhase.FULL_STATEMENT`를 신설하고, `FullStatementRefillJob`이 그것으로 적는다(170·185행).
- V69: `ck_failure_phase`에 `FULL_STATEMENT`를 더한다. `CollectionPhaseConstraintTest`가 enum과 대조한다.
- **옛 행은 옮기지 않는다.** 활성 편입의 미해결이 0이다(1절). 이미 해결 표시된 FSR 행의 뜻은 「그때 FINANCIAL로 적힘」 그대로다.
- **소비자 영향**
  - `StockRegistrar:285`(재무 0행 결손 술어)는 FSR 실패를 더 안 본다. FSR은 재무 행이 있는 종목에만 쓰므로(영업현금흐름 결손 갈래의 대상), 0행 술어에 닿지 않는다.
  - 관리 화면은 문자열로 그린다.
- **꼬리의 영업현금흐름 갈래**(`fullStatementRefillJob.run(..., cashFlowTickers, true, false)`)의 실패도 `FULL_STATEMENT`가 된다. 그 실패는 B가 다시 집는다.

### D. 손 실행

- `BootstrapRunner`에 `--job=entrant-refresh`를 더한다(하한 무시 · `--tickers` 필수 · MANUAL → NARROW).
- 훅 정규식·`batch-load` skill 표·`JobFreshnessPolicy` 예산을 고친다(2부 2차와 같은 자리 다섯 · `load-process.sh` · `guard-rebuild-without-dump.sh`).

## 3. 순서와 배포

1. 2부 2차 배포(10-14 저녁 · 1차 수용 뒤)와 **섞지 않는다.** 2차 수용(10-15 14:16·14:20)을 홀로 잰다.
2. 이 묶음은 main에 쌓고, 10-15 2차 수용 뒤 **10-16(금) 크론이 끝난 뒤** 배포한다(V69). 첫 꼬리는 10-17(토) 20:00이다.
3. 값을 바꾸는가: 이 묶음은 계산기·어댑터를 바꾸지 않는다. 정기 실행이 할 일을 그 종목에 앞당길 뿐이고, 결과 값은 다음 정기 실행 뒤와 같다. 그래서 관문 설정 밖이다(2부 소비와 같은 판정).

## 4. 드라이런 (전체 사본 금지 · 가장 싼 재료부터)

| 단계 | 재료 | 묻는 것 |
|---|---|---|
| D3 단위·통합 (`stock_screener_test`) | 꼴마다 최소 행 · `FakeSources` | 아래 갈래 전부(각각 대조군) |
| D4 작은 DB (스키마 + 참조 + 10-09 편입 미국 22 · 국내 2와 그 원자료 행) | 운영 원장 1회 | 하한 상수 직전으로 실행 → 24종목 대상 · 미국 FR 결과가 #775(신규 21 · 정정 37 · 채움 189 · 교정 174)와 같은 꼴인가 · 국내 FSR·G 콜 수 · **두 번째 실행 0종목** · GAP_FILL·NARROW 기록 · 시계 소비자 셋 불변 |

D3 갈래:
- 하한: `created_at`이 직전 성공 `started_at` 뒤인 것만 고른다. 대조군은 effective_from이 같은 날인 금요일 실행 뒤 토요일 편입이다.
- ABORTED 실행은 하한을 안 민다. PARTIAL_SUCCESS는 민다(실패 종목은 B가 받는다).
- 대상 0이면 FR·FSR·G를 부르지 않는다.
- 상한 50 초과면 돌리지 않고 알린다.
- B: FSR 실패가 같은 종목 FR 성공으로 해결되지 않는다(대조군: 단계 분리 전 코드는 해결된다 — 원래 결함으로 붉힌다).
- B: 앞 단계가 해결한 종목은 다시 안 부른다.
- B: 평일 5일 경보.
- 14:16 사슬: 목록이 BatchShutdownException이면 소비·A·B를 건너뛴다.
- 꼬리에서 A가 던져도 리밸런싱 실행의 상태와 결손 갈래는 그대로다.

D4 콜: SEC 약 60 · DART는 국내 2종목 × (FR · FSR 약 45 · G 24) ≈ 150. D4 DB는 판정 뒤 지운다.

## 5. 수용 기준

- **배포 뒤 첫 꼬리(10-17 20:00)**
  - 그 리밸런싱의 편입 전원이 대상이다(`created_at` 하한 질의 = 실행 대상 수).
  - FR·FSR·G 실행 셋이 `GAP_FILL`·NARROW로 기록된다.
  - 실패 종목은 미해결로 남는다.
  - **독립 대조**: 편입 종목마다 SEC submissions · DART list의 마지막 정기 공시가 DB의 최신 판과 같은 공시인지 본다. 같지 않은 종목은 줄마다 사유를 단다.
- **월(10-19) 13:30·14:16·14:20**
  - 14:16 A는 대상 0이다.
  - B는 꼬리 실패 종목만 부른다.
  - 14:20 뒤 편입 종목의 지표가 창 전체에 있다(표적 `indicator_value` SELECT).
- **시계**: 정기 FR·FSR·G의 `lastSuccessAt`·연체 순서가 전후로 같다.

## 6. 재지 않은 것

- 2차 소비가 F6를 실제로 얼마나 덮는지: 14:16 장부가 쌓인 뒤(10-14) 09-04~10-09 편입 무리 중 09-24 이후 공시가 있는 종목의 비율로 센다.
- 후보 쪽에서 「들어왔어야 했는데 낡은 주식수로 빠진 종목」: 리밸런싱 소스 종가를 다시 받아야 잰다. 쓰기 없는 `universe-restore --dates` 재생이 오라클인데 앱이 떠 있는 동안 JVM 잠금으로 못 띄운다.
- 국내 G·FSR 좁힌 실행의 종목당 실제 콜(D4가 잰다).

## 7. 범위 밖으로 보내는 것

- **클래스 축 dei 주식수**(ARES·HSY·XMTR · 비편입 135): `reload-gate.md` 「주식수 0」 줄에 사례로 단다.
- **ADR-0161 펜스 재발화(UHAL.B·XMTR Q4)**: [S] Q4 정책(progress에 적음 · 10-14).
