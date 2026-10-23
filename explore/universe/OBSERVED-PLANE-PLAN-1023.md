# 관측 평면을 관문 켬 기저로 다시 짓는다 — 계획 v1 (2026-10-23)

관문 재고 `docs/reload-gate.md` 「관측 평면이 옛 순위 기저로 남음」 행 · 실행기 `ops/apply-window/05-roster.sh` ③ 주석
(「그 잡이 생기면 ③을 그것으로 바꾼다. 지금 ③만으로는 관문이 닫히지 않는다」)이 기다리는 잡의 설계다.

## 0. 실측 (★ = 내가 잼 · 운영 SELECT · 코드 읽기 · 외부 호출 0)

- ★ **관측 평면**(`US_TOP1000` `OBSERVED`): `effective_from` 아홉 날 — 08-31 1,000행(열림 908) · 09-04 32(29) · 09-07 7(2) ·
  09-11 11(4) · 09-18 15(7) · 09-25 14(7) · 10-02 13(7) · 10-09 22(19) · 10-16 17(17). 전부 꺼진 순위 판독기가 고른 명부다.
  `RESTORED`는 2025-09-24~08-28 주간 격자이고 08-31(= `OBSERVED`의 `min(effective_from)`)에서 끊긴다(`UniverseLedgerRestorer.observedFloor`).
- ★ **크기**(재고 표 · 10-16 0콜 재생): 여덟 기준일 모두 켬−꺼짐 1,000위 들어옴 · 나감 13~14. 들어옴 `V` · `BRK.B` · `STZ` · `COKE` ·
  `WMG` · 재무 0행 후보, 나감 Up-C · 숨김 · 문턱. 그 뒤 들어간 IFRS(ADR-0182 · 보통주 USD $5B 넘는 것 28) · B v2(ADR-0183)는
  재지 않았다 — 늘어난다. **크기는 설계를 안 바꾼다**(0이 아닌 한 다시 지어야 한다) — 관문 날 격자 대조가 잰다(§3 ③-0).
- ★ **제약**: `ck_restored_is_closed`(RESTORED는 닫힘) · `ex_membership_no_overlap`(GiST · 즉시 검사) · `uq_membership`
  (유니버스, 종목, 시작일) · `ck_membership_period`. 열린 행은 `OBSERVED`만 가능하다.
- ★ **복원 잡**(`UniverseRestoreJob`): 관문 켬 + `US_TOP1000`이면 격자 명부를 `UsPastRanking.rosters(grid)`로 짓는다(행 후보 ·
  그날 거래소 범주 · 닻 봉 · 클래스 보류의 앞 편입은 앞 격자 명부로 사슬). R-D4a(10-18 · 47 격자일)에서 하한 둘을 다 통과했다.
  쓰기는 `restorer.apply(plan, replace)` 한 트랜잭션 · 꼬리 `fillRestoredPriceGaps`(닫힌 행만 본다).
- ★ **오늘 순위**(`UniverseRebalanceJob.rebalanceOne`): 신원 판정 → `fetchConstituents(code, asOf, 지금 열린 편입 표지)` →
  막힌 새 기호 빼기 → `resolveOrCreate` → `UniverseReconciler.reconcile`. 꼬리: `fillAndAlert`(열린 편입 결손) ·
  `freshnessCatchUp`(ADR-0177). `--as-of`로 지난 날짜를 준다(손 리밸런싱이 쓰던 길).
- ★ **같은 기준일 재실행**(`UniverseReconciler.reconcile`): 빠지는 열린 행 중 그날 연 것은 지우고 그 전에 연 것은 그날 닫는다 ·
  들어오는 것은 그날 연다.
- ★ **`previous`가 순위를 바꾸는 자리는 하나다** — `applyClassHolds`의 `HELD`: 클래스 판정이 「보류」인 종목은 **앞 편입일 때만**
  보류 주식수로 남는다. 그래서 오늘 순위는 경로에 따른다.

### 소비자 전수(오류 형태 2) — `universe_membership`을 읽는 자리

`grep -rln universe_membership src/main .claude/hooks ops` + ADR 전제 펜스(`docs/adr/*.md`)에서 `origin`·`created_at`을 읽는 것.

| 읽는 것 | 자리 | 다시 짓기가 바꾸는 것 |
|---|---|---|
| 지금 편입(`effective_to IS NULL`) | `BatchExecutionReader` · `RetirementEvidence` · `StockRefreshStore` · `StockReader` · `MarketScope` · `ScreenStore` · 훅 `guard-rebalance-on-dirty-rank` · ops `02b` · `08` · `00-precheck` CUR/CAND | 열린 명부가 켬 기저가 된다 — 그것이 목적이다 |
| 날짜 술어 | `PercentileCalculator` · `SectorRelativeCalculator` · `IndicatorBackfillJob` · `PriceBoundaryJob` · `PriceIntegrityAlerter` · `ScreeningRepository` · `StockRegistrar`(보존 창 편입 · 시세 구멍 구간 · 확정 재확인) | 창 안 날짜의 모집단이 바뀐다 → 관문 화해(06)가 파생층을 다시 낸다 · 새 편입의 시세는 두 꼬리가 받는다 |
| 구간 구조 | `StockFold`(이음매 · 겹침) · `UniverseReconciler` · `UniverseLedgerRestorer` · `UniverseRestoreJob` | 이 계획의 대상 |
| `created_at` | `FilingLedgerStore` `due_from` = max(열린 편입 `created_at`, 처음 본 시각) — 소비 지연 경보의 시계 | **이어지는 열린 행은 지우지 않고 시작일만 옮긴다**(아래 결정 2) — 지우고 다시 넣으면 지금 편입 전원의 경보 시계가 관문 날로 되감긴다 |
| `origin` | `UniverseLedgerRestorer.observedFloor` · ADR-0135 펜스 둘(「보존 창 모든 거래일에 편입」 RESTORED 수 · 「복원이 낡지 않았다」 RESTORED `created_at` · `max(effective_to)`) · ADR-0171 펜스 「큰 종목이 관측 편출되면 설명된다」(닫힌 `OBSERVED` · 10-08 뒤 35일) | 하한이 L로 옮겨간다(결정 1) · ADR-0135 둘은 그대로 선다 · **ADR-0171 펜스는 관문 전 관측 편출을 잃는다** — 그 편출은 꺼진 기저의 것이라 설명할 대상이 아니다. 관문 뒤 첫 토요일부터 다시 쌓인다 |
| 내보내기 · 날짜 | ops `02-snapshot`(전 행 TSV — 되돌리기 재료) · `00-precheck` `reb`(달마다 첫 편입일 = 백필 날짜 고르기) | 02는 다시 짓기 **앞**에 돈다 · `reb`는 관문 앞 계획에서만 읽는다 |

## 1. 결정

1. **닫힌 이력 전부(보존 창 첫날 ~ L)를 과거 날짜 순위로 짓는다. L = 마지막 관측 리밸런싱의 기준일(`OBSERVED`의 `max(effective_from)`).**
   관측 하한이 08-31에서 L로 옮겨간다 — 다시 지은 뒤 `observedFloor`가 저절로 L을 낸다.
   - 왜 과거 날짜 순위인가: 과거 날짜에 오늘 순위를 다시 돌리면 **오늘 목록 · 오늘 기호**로 과거를 고른다(D4가 막은 것 — 그 사이
     피인수 · 개명 행). 과거 순위는 행 · 그날 범주 · 닻 봉으로 고르고, 이미 복원 평면 전체가 그것으로 지어진다 — 닫힌 이력이 한
     방법으로 선다.
2. **열린 편입은 지우지 않는다.** 한 트랜잭션에서:
   (a) `RESTORED` 전부와 닫힌 `OBSERVED` 전부를 지운다 ·
   (b) 열린 `OBSERVED` 중 L의 과거 순위 명부에 있는 것은 `effective_from = L`로 옮긴다(`id` · `created_at` 유지) · 없는 것은 지운다 ·
   (c) 명부에 있는데 열린 행이 없는 것은 `[L, NULL)` `OBSERVED`로 넣는다 ·
   (d) 격자(보존 창 첫날 + 주마다 마지막 거래일, L 미만)의 과거 순위 구간을 L에서 닫아 `RESTORED`로 넣는다.
   - 그래서 L에 이어지는 종목은 `RESTORED [x, L)` + `OBSERVED [L, NULL)`로 붙는다(지금 08-31 이음매와 같은 모양).
3. **그 직후 L에 오늘 순위를 같은 기준일로 다시 돈다**(`--job=universe --universe=US_TOP1000 --as-of=L` · 관문 켬).
   - 왜: 지금 편입은 오늘 순위가 고르는 것이다(숨은 상장 ADR-0171 · 예탁 표지 A′ — 과거 순위에 없다). 결정 2의 L 명부는 이 실행의
     **앞 편입**이 되므로 클래스 보류(`HELD`)가 켬 기저의 사슬을 받는다 — 꺼진 기저의 열린 편입을 앞 편입으로 넘기지 않는다.
   - 결정 2가 열린 행을 전부 L에서 시작하게 했으므로 이 실행에서 빠지는 행은 **닫히지 않고 지워진다**(`[L, L)`이 안 생긴다).
   - 신원 판정 · 막힌 새 기호 · `resolveOrCreate` · 두 꼬리(열린 편입 결손 · ADR-0177 따라잡기)를 **지금 잡 그대로** 탄다 — 다시 짓지 않는다.
4. **새 인자 `--rebuild-observed=true`** — `universe-restore`에서 관문 켬 · `US_TOP1000` · `--apply=true` · `--replace=true`가 모두
   있을 때만 받고, 하나라도 없으면 쓰기 전에 거절한다. `--dates`와 함께 못 쓴다(지금 규칙 그대로). **관문 꺼짐 갈래는 손대지 않는다** —
   꺼짐 출력은 `main`과 같다(새 인자 없이는 새 코드가 안 돈다).
5. **비율 가드**: `restorer.plan`의 격자 단계 가드(30%)를 L까지 그대로 탄다 · 결정 3은 `UniverseReconciler`의 가드를 탄다.
6. **꼬리**: 결정 2 뒤 `fillRestoredPriceGaps`(닫힌 행 · 지금 꼬리 그대로). 열린 새 편입의 결손은 결정 3의 리밸런싱 꼬리가 받는다.

### 버린 대안
- **열린 편입을 다음 토요일 정기 리밸런싱에 맡긴다**: 관문 날 ~ 토요일의 날짜가 꺼진 기저 명부로 파생층에 들어가고, 토요일
  교체가 시장 변화가 아니라 기저 변화가 된다.
- **관측 날짜마다 오늘 순위를 다시 돈다**(재생 리밸런싱 아홉 판): 과거 날짜를 오늘 목록으로 고른다(결정 1의 이유).
- **열린 행을 지우고 다시 넣는다**: `FilingLedgerStore`의 지연 경보 시계가 지금 편입 전원에서 관문 날로 되감긴다.
- **결정 3 없이 L 명부를 열린 편입으로 둔다**: 숨은 상장 · 예탁 표지 판정이 빠진 명부가 토요일까지 지금 편입이 된다.
- **결정 3만**(꺼진 열린 편입을 앞 편입으로 둔 같은 기준일 재실행): 클래스 보류가 꺼진 기저 명부를 앞 편입으로 읽는다.

## 2. 구현

- `UniverseLedgerRestorer`: `rebuildObserved(Plan closedPlan, LocalDate L, Set<Long> rosterAtL)` — 결정 2 (a)~(d) 한 트랜잭션.
  `Plan`은 지금 `plan(...)`이 L을 하한으로 낸 것 · L 명부는 격자 마지막 원소에서 떼어 받는다.
- `UniverseRestoreJob.run`: `rebuildObserved` 인자 · 결정 4 검사 · 격자 = `weeklyGrid(US, L)` + L · 과거 순위 한 판(사슬) ·
  L 명부와 그 앞 격자 명부의 차이도 가드 · 쓰기 뒤 `fillRestoredPriceGaps`.
- `BootstrapRunner`: `flag(args, "rebuild-observed")`.
- `ops/apply-window/05-roster.sh` ③: ③-0 격자 대조(쓰기 없는 `--dates=<관측 아홉 날>` · 관문 켬 — 다시 짓기 전 귀속 재료로 남긴다) →
  ③a 다시 짓기 → ③b `--job=universe --universe=US_TOP1000 --as-of=L` → ④ 창 결손 0(그대로).
- `docs/reload-gate.md` 해당 행 · `ops/apply-window/README.md` · ADR-0135에 「관문 날 관측 하한이 옮겨간다」 절.

## 3. 드라이런 (가장 싼 재료부터)

작은 DB에서는 과거 순위가 「후보 < 1,000」 하한에 걸려 원리적으로 못 돈다(`05-roster.sh` 주석 · R-D4). 그래서:

1. **단위 · 통합 시험**(빈 시험 DB · 꼴마다 최소 행) — `UniverseLedgerRestorerTest`에 넣는다:
   - 이어지는 열린 행: `id` · `created_at`이 같고 시작일만 L.
   - L 명부에 없는 열린 행 · 닫힌 `OBSERVED` · 옛 `RESTORED` → 0행.
   - 새 열린 행 `[L, NULL)` · `RESTORED`는 전부 닫힘 · L에서 이음매.
   - 다시 지은 뒤 `observedFloor` = L.
   - 국내 유니버스 · `US_LISTED` 행 무변화.
   - 가드에 걸리면 한 행도 안 바뀐다(롤백).
   - 다시 지은 뒤 같은 기준일 `reconcile`이 빠지는 행을 지우고 `[L, L)`을 안 만든다.
2. **잡 인자 시험** — 결정 4의 거절 다섯(관문 꺼짐 · 다른 유니버스 · `apply` 없음 · `replace` 없음 · `--dates` 함께) · 쓰기 0.
3. **크기는 관문 날 ③-0이 잰다**(운영 재적재 뒤에만 켬 재료가 있다 — 지금 운영에 클래스 축 · IFRS · B 재료가 없다).

## 4. 재지 않은 것

- 켬 기저의 관측 날짜 차이 크기(IFRS · B 뒤) — §3-3.
- L에서 오늘 순위가 결정 2의 L 명부와 몇이 갈리는지(숨은 상장 · 예탁 표지 · 방법 차이) — 관문 날 ③b 로그.
- 결정 3의 신원 판정 재실행이 새 판정을 내는지 — 지금 손 리밸런싱과 같은 길이라 새 위험은 아니다.
