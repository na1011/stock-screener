---
id: ADR-0185
status: 채택됨
date: 2026-10-23
applies_to:
  - "src/main/java/com/stockscreener/pipeline/batch/UniverseRestoreJob.java"
  - "src/main/java/com/stockscreener/pipeline/collector/UniverseLedgerRestorer.java"
  - "ops/apply-window/05-roster.sh"
  - "ops/apply-window/04b-depositary.sh"
---

# ADR-0185. 미국 관문 날 관측 평면까지 켬 기저로 다시 짓고, 관측 하한을 L로 옮긴다

## 결정

미국 관문 날 명부 단계(`ops/apply-window/05-roster.sh` ③)는
`--job=universe-restore --universe=US_TOP1000 --as-of=L --apply=true --replace=true --rebuild-observed=true`(관문 켬)로 돈다.
L은 관문 날 미국 마지막 마감 세션이다.

- 보존 창 첫날부터 L까지는 과거 날짜 순위 한 사슬로 짓는다.
- 열린 행은 지우지 않고 시작일만 L로 옮긴다.
- 같은 실행 안에서 L 같은 기준일 리밸런싱이 지금 편입을 고른다. 앞 편입은 과거 순위의 L 명부다.
- 관측 하한(`OBSERVED`의 `min(effective_from)`)이 08-31에서 L로 옮겨가고, 꺼진 기저로 고른 관측 이력은 지워진다.

설계 · 실측 · 반증의 정본은 탐색 브랜치 `explore/s-vintage-coherence`의 `explore/universe/OBSERVED-PLANE-PLAN-1023.md`다.
v1을 두 렌즈로 반증받았고 둘 다 「고쳐서 간다」였다. 고친 판이 §5 v1.1이다.

## 왜

- 관측 평면(08-31 ~ 10-16 · 아홉 날짜)은 꺼진 순위 판독기가 고른 명부다.
  - 관문을 켜면 날짜마다 1,000위 안이 13~14씩 바뀐다(10-16 0콜 재생). 이 측정은 IFRS · B 뒤로는 다시 재지 않았다.
  - 지금 복원은 관측 하한 앞만 다시 짓는다. 그래서 창 안에 기저가 둘 섞인다.
- `origin`을 읽는 코드는 `UniverseLedgerRestorer.observedFloor` 하나다. 관측 평면을 「그때 보였던 기록」으로 읽는 소비자는 없다(계획 §0 소비자 표).

## 규칙

- MUST: 닫힌 이력은 과거 날짜 순위(`UsPastRanking`)로 짓는다. 지난 날짜에 오늘 순위를 돌리면 오늘 목록 · 오늘 기호로 과거를 고른다.
- MUST: L은 관문 날 미국 마지막 마감 세션이다(실행기가 ② 달력으로 정한다).
  - 오늘 순위와 그 꼬리(시세 적재 `asOf`)가 L에 선다.
  - L이 지난 날짜면 결정 1의 사유가 결정 3에서 깨진다.
- MUST: 열린 행은 하나도 안 지운다. 그 행의 `created_at`이 두 시계다.
  - 공시 소비 지연 시계: `FilingLedgerStore` `due_from`.
  - 따라잡기 표지: `StockRefreshStore.behind` `joined_at`.
  - 오늘 순위가 빼는 행은 같은 기준일 재실행이 지운다. 시작일이 L이라 `[L, L)`은 안 생긴다.
- MUST: 한 트랜잭션 안의 순서를 지킨다.
  1. 복원 · 닫힌 관측 행을 지운다.
  2. 열린 행을 L로 옮긴다.
  3. L 명부의 새 행을 연다.
  4. 복원 구간을 넣는다.
  - 복원 구간을 2보다 먼저 넣으면 GiST가 거절한다.
- MUST NOT: L 점을 `plan()` 격자에 넣지 않는다. L에서 처음 든 종목이 `[L, L)`이 되어 트랜잭션째 되돌아간다. `planThrough`를 쓴다.
- MUST: 앞 편입을 명시해서 넘긴다. 열린 집합(꺼진 명부 ∪ L 명부)을 넘기면 클래스 보류(`HELD`)가 옛 기저를 잇는다.
- MUST: 같은 기준일 리밸런싱이 `SUCCESS`가 아니면 던진다. 같은 인자로 다시 돌리면 멱등이다.
- MUST: 05 앞에 04b(예탁 판 비율)가 돈다. 두 순위가 DEPOSITARY 기저를 읽는다.
- MUST: 복원 꼬리는 둘이다 — 닫힌 창 구성원의 시세(`fillRestoredPriceGaps`)와 거버넌스 원문(`fillRestoredGovernanceGaps`).
  - `governance-load`의 기본 모집단은 지금 편입뿐이다. 그래서 복원이 처음 넣은 종목은 원문을 받을 길이 없었다.
- 되돌리기 재료는 01 덤프뿐이다(02 `membership.tsv`에는 `id` · `stock_id` · `created_at`이 없다).

## 버린 대안

- **열린 편입을 다음 토요일 정기 리밸런싱에 맡긴다**:
  - 관문 날부터 토요일까지가 꺼진 기저 명부로 파생층에 들어간다.
  - 토요일의 교체가 시장 변화가 아니라 기저 변화가 된다.
- **관측 날짜마다 오늘 순위를 다시 돈다**: 과거 날짜를 오늘 목록으로 고른다.
- **열린 행을 지우고 다시 넣는다**: 지금 편입 전원의 두 시계가 관문 날로 되감긴다.
- **L = 마지막 관측 리밸런싱 기준일**(v1): 오늘 순위가 며칠 전 날짜를 오늘 목록으로 고른다. 시세 꼬리도 그 날짜에서 끊긴다(반증 안 1).
- **`--dates` 재생을 귀속 재료로 쓴다**: 앞 편입이 빈 집합에서 시작해 사슬이 다르다. 그래서 같은 실행 안에서 지울 관측 평면과 견준다.

## 결과

- 시험 `ObservedPlaneRebuildTest` 9건 초록.
- 대조군으로 `planThrough`가 L을 격자에 넣는 변이를 만들면 2건이 붉다(`[L, L)` · 멱등).
- 관문 꺼짐: 새 인자 없이는 새 코드가 안 돈다.
  - 복원 쓰기 갈래에 거버넌스 꼬리가 더해졌다(손 잡 · 관문 무관).
  - 그 대상이 지금은 0이다. 반증 밖 6에 따르면 닫힌 행만 가진 창 구성원 211의 원문 결손이 0이다.
- 관문 날에 잴 것은 둘이다.
  - 지운 관측 평면 대 과거 순위의 날짜별 놓침 · 더든것.
  - L 오늘 순위 대 과거 순위 L 명부의 뺌 · 넣음.
  - 둘 다 `roster-attribution-<db>.txt`에 남는다.
- ADR-0135 펜스 「복원이 재무·원장보다 먼저 돌아 낡지 않았다」는 관문 뒤에 뜻이 넓어진다.
  - `grid_to`가 08-28에서 L로 옮겨간다. 그래서 늦게 실린 재무 행(10-17 518 · 10-19 1,907 · 10-22 283 · 10-23 222 꼴)이 펜스를 붉힌다.
  - 오보가 아니다 — 08-31부터 L까지의 명부가 그 행을 못 본 채 지어졌다는 참이다.
  - 관문 뒤 복원을 언제 다시 돌릴지는 `docs/reload-gate.md` 관문 뒤 항목이다. 다시 돌리면 명부가 몇이 바뀌는지는 재지 않았다.
- ADR-0171 펜스 「큰 종목이 관측 편출되면 설명된다」는 관문 전 관측 편출을 잃는다.
  - 그 편출은 꺼진 기저의 것이다.
  - 관문 뒤 첫 토요일부터 다시 쌓인다.

## 검증

- `./gradlew test --tests '*ObservedPlaneRebuildTest*' --tests '*UniverseLedgerRestorerTest*' --tests '*UniverseReconcilerTest*'`
- 이 결정은 운영 자동 검증 대상이 아니다. 관문 날 `05-roster.sh`가 관측 하한 = L · 지금 편입 1,000 · 창 결손 0(봉 · 거버넌스) · 리밸런싱 실행 행 SUCCESS를 단언한다.
