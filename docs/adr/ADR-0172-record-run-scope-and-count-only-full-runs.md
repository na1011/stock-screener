---
id: ADR-0172
status: 채택됨
date: 2026-10-06
applies_to:
  - "src/main/java/**/batch/BatchExecutionRecorder.java"
  - "src/main/java/**/batch/JobFreshnessPolicy.java"
  - "src/main/java/**/batch/FullStatementRefillJob.java"
  - "src/main/resources/db/migration/V63__batch_execution_scope.sql"
---

# ADR-0172. 실행마다 범위를 적고, 좁힌 실행은 신선도 시계에 세지 않는다

## 결정

`batch_execution.scope`(`FULL`·`NARROW` · NULL 허용) 컬럼을 둔다(V63 · ADD COLUMN만). 범위는 아래처럼 정한다.
- 잡이 자기 인자로 정한다(잡마다 `scopeOf`).
- 기록 계층(`BatchExecutionRecorder.recordedScope`)이 모든 잡에 두 규칙을 더한다.
  - `GAP_FILL`이면 `NARROW`다.
  - 기준일이 오늘(KST) 7일 전 ~ 내일 밖이면 `NARROW`다.
- 재실행(`startRetry`)은 `NARROW`다.

`JobFreshnessPolicy`는 **스케줄러 갈래와 폴백 둘 다** `scope IS DISTINCT FROM 'NARROW'`로 센다. 표지 전 이력(NULL)은 예전처럼 센다.

`full-statement-refill`의 기본 대상(티커 없음)은 **전체재무제표 소스가 있는 시장**으로 좁힌다(지금 국내).

| 잡 | `FULL`의 정의(인자) |
|---|---|
| `financial-refill` | 티커·유니버스 없음 ∧ 연수 ≥ 기본(8) |
| `full-statement-refill` | 티커 없음 ∧ `--all-quarters` ∧ 연수 ≥ 기본(8) |
| `governance-load` | 티커·연도·해 수 없음 ∧ 표 전부 |
| `corporate-action` | 창 시작·티커·유니버스 없음 |
| `adjustment-rebuild` · `company-profile` | 티커·유니버스 없음 |
| `governance-derive` · `indicator-origin-rewrite` | 티커 없음 |
| `indicator-derived-rebuild` · `daily-update` | 언제나(좁힐 인자가 없다) |
| `universe-rebalance` | 지수를 고르지 않음 |
| `market-calendar` · `market-index` | 창 시작 없음 |
| `initial-load` | spec 기본값(`LoadRange.defaults()`) |
| `indicator-backfill` | 티커·유니버스 없음 ∧ 보존 창 첫날 이전부터 |
| `corporate-action-judge` | 언제나 `NARROW`(원장 한 행의 판정 · 정기 실행이 없다) |

## 규칙

- MUST: 시작 호출은 범위를 **필수 인자**로 받는다(오버로드 없음). `null`은 기록 계층이 `requireNonNull`로 거부한다.
  - DB가 NULL을 받고 신선도 판정은 NULL을 전량으로 센다. 그래서 새 행에 NULL이 들어가면 실패가 **열리는 방향**이다.
- MUST: 정기 호출은 전부 `FULL`로 찍힌다. `NARROW`로 찍히면 그 SCHEDULER 행이 시계에서 빠져 같은 잡이 매 평일 다시 돈다.
  - `PipelineSchedulerChainTest.everyScheduledCallIsFullScope`가 스케줄러에서 잡은 인자를 잡의 `scopeOf`에 그대로 먹인다.
- MUST NOT: 이력을 UPDATE로 채우지 않는다(§버린 대안 1).
- MUST NOT: `scope`에 NOT NULL이나 기본값을 걸지 않는다.
  - NOT NULL이면 옛 jar(이 컬럼을 모른다)로 되돌릴 때 모든 배치 시작이 실패한다.
  - 기본값이면 원시 INSERT 픽스처가 조용히 그 값을 받는다.
- MUST: 「전량」은 `target_count`가 아니라 **인자로** 정한다. `financial-refill`은 시장을 나눠 도는 것이 정상 운용이라, 수로는 영영 전량이 안 된다(progress [A56]이 물은 것).
- MUST NOT: `full-statement-refill`의 기본 대상에 소스 없는 시장을 넣지 않는다. 티커로 고른 종목은 소스가 없어도 빼지 않는다.

## 검증

```bash
./gradlew test --tests "*BatchExecutionScopeTest" --tests "*JobFreshnessPolicyTest" \
               --tests "*PipelineSchedulerChainTest" --tests "*MembershipGapFillerTest" \
               --tests "*FullStatementRefillJobTest" --tests "*WireContractTest"
```

변이체 아홉으로 쟀다(2026-10-06). 각 변이체가 의도한 시험을 붉혔다.

| 변이체 | 붉은 시험 |
|---|---|
| 술어 없음(원래 결함) | 폴백 · 스케줄러 갈래 2건 |
| `<> 'NARROW'`(NULL 빠짐) | 표지 전 이력 + 기존 3건 |
| FSR 시장 거름 없음 | `defaultTargetsSkipMarketsWithoutTheSource` |
| 꼬리 규칙 없음 | `gapFillIsAlwaysNarrow` |
| UTC 날짜 | `todayIsTheKoreanDate` |
| G가 티커를 안 봄 | `narrowedHeavyRunsAreNarrow` |
| 정기 FR이 유니버스를 줌 | `everyScheduledCallIsFullScope` |
| 정기 FSR이 `--all-quarters`를 안 줌 | `everyScheduledCallIsFullScope` |
| 꼬리 업종이 티커를 줌 | `companyProfileFromTheTailIsFullScope` |

드라이런(2026-10-06 · DB 사본 · 순서는 탐색 브랜치 `explore/financial-sync/PLAN.md`의 대조 전 커밋).
- RD0 — 운영 실행 이력 738행에 V63을 적용하고, 코드의 질의 문자열을 그대로 옮겨 12잡의 시계를 잤다. 표지 전후 12/12가 같았다. NARROW 성공 행 하나를 넣은 음성 대조에서는 옛 술어만 그 행으로 밀렸다.
- RD1 — 실키로 손 실행 열두 번을 돌렸다(좁힌 것 아홉 · 인자 없는 것 셋).
  - 좁힌 실행 여덟은 전부 NARROW로 찍혔다. 옛 술어는 시계를 그 실행으로 밀고 새 술어는 그대로였다. 여덟은 재무 보정(티커 · 유니버스+티커) · 전체 재무제표(티커 · 인자 없이 FY만) · 거버넌스(티커 · DIVIDEND) · 유도(과거 기준일) · 시장 달력(창 시작)이다.
  - 전체 재무제표를 인자 없이 돌린 실행의 대상은 국내 350이었고 실패는 0이었다.
  - 시장 달력을 인자 없이 돌린 실행은 FULL로 찍혔고, 두 술어 모두 그 실행으로 시계를 밀었다 — 폴백 갈래의 양성 대조다.

운영에서는 배포 뒤 첫 실행부터 `scope`가 차는지 본다: `SELECT id, job_name, triggered_by, scope FROM batch_execution ORDER BY id DESC LIMIT 5`.

## 배경

[A56]은 신선도 시계가 범위를 안 보는 결함이다. 무거운 잡 셋(FR·FSR·G)은 스케줄러 성공이 0이라 폴백이 손 실행을 센다. 2026-10-06 그 셋의 시계는 전부 **좁힌 손 실행**이었다.
- G: #633(09-20 · 337종목)
- FSR: #664(09-23 · 129종목)
- FR: #710(10-03 · 1종목)

그 시계가 각 잡의 첫 정기 실행을 그만큼 밀었다. 이 결정 뒤 첫 정기 실행일은 아래와 같다. 2026-10-06에 쟀고, 표지 전 이력을 예전처럼 세며, 그 사이 다른 실행이 없다고 놓았다.
- G 10-19(월)
- FSR 10-20(화)
- FR 10-30(금)

FR 10-30은 #710의 산물이다. 그 대신 이력을 고치면 날짜가 아래처럼 바뀐다.
- #683(09-28 · 미국 1,000) 기준: 10-26
- 이력 재구성: 10-08

그래도 그 둘을 고르지 않은 이유는 §버린 대안 1이다.

## 버린 대안

1. **이력 재구성(과거 행에 범위를 UPDATE)** — 위상이 움직이는데 이득이 없고, 증거가 서지 않는다.
   - 무거운 잡의 위상이 앞으로 당겨진다. 국내 3분기 공시철과 맞물려 국내가 늦어진다.
     - FSR: 330종목이 14일 늦어진다.
     - FR: 334종목이 5일 늦어진다.
     - 그런데 FR을 미국까지 합한 지연 합은 중립이다(재구성 19,468 · 이 결정 19,426 라벨-일).
   - 행의 증거가 「요청」을 증명하지 못한다.
     - G는 upsert라 새 행이 안 생긴 표가 「안 받음」으로 보인다.
     - note가 빈 실행도 있다.
   - 해악은 「**앞으로** 좁힌 손 실행이 시계를 민다」이고, 그것은 이력 없이 막힌다.
2. **달성 규칙(종목 실패율로 `FULL`·`NARROW`를 가름)** — 단위와 임계가 서지 않는다.
   - 종목으로 셀 수 없는 잡이 있다(리밸런싱 2~3지수 · 파생층 272거래일).
   - 이력의 실패율이 정상 최대 1.4% · 한 번 9.9%(#261) · 시장 통째 100%로 갈려, 10~100% 사이 어느 경계든 같은 답을 낸다. 임계를 실측으로 고를 수 없다.
   - 한도 소진은 종목별 `RATE_LIMITED`로 남아, 그 꼬리가 경계 밑이면 반쯤 못 받은 실행이 `FULL`이 된다.
3. **`target_count`로 전량을 판정** — 잡마다 전량의 크기가 다르다(`company-profile`은 평소 한 자리 수). 정의를 잡마다 수로 다시 적으면 그 수가 낡는다.
4. **기준일 규칙을 잡마다 둔다** — 25곳 중 한 곳이 빠지면 그 자리가 [A56] 그대로다. 기록 계층 한 곳에 둔다.

## 결과

- **효과 창**: 표지 뒤의 좁힌 손 실행부터 막힌다.
  - (가) 공시 보충(1부)을 결손 종목에 좁힌 손 FR로 적용해도 FR 시계는 안 움직인다.
  - 위 첫 정기 실행이 SCHEDULER 행을 만들면 그 잡은 폴백을 안 탄다. 그 뒤에도 스케줄러 갈래의 `NARROW`(지난 기준일)는 안 센다.
- **FSR 기본 대상 = 소스 있는 시장.** 10-20 첫 정기 FSR이 미국 1,000을 `NOT_FOUND`로 남기지 않는다.
  - 2026-09-14 #410·#415가 대상 1,350 중 미국 1,000을 그렇게 남겼다.
  - 같은 기준(`hasFullStatementSource`)을 `FinancialRefillJob`의 무효화 갈래가 이미 쓴다.
  - `StockRegistrar.activeUniverseMemberIds()`는 그대로다. 그것을 쓰는 일일 갱신 · 거버넌스 · 초기 적재 · 백필의 「전 종목」은 안 바뀐다.
- **화면**:
  - 연체 배너의 「스케줄 실행이 아직 없어」 문구가 「범위 표지 전 실행은 좁힌 것인지 몰라 함께 셉니다」를 덧붙인다.
  - 실행 이력의 「실행 주체」 칸에 `NARROW`만 「좁힘」을 단다.
  - 이력 API는 `scope`를 싣는다. NULL은 「기록 전」이다.
- **사정거리 밖**:
  - ADR-0116 전제 「스케줄러 실행이 실제로 발화하고 있다」는 범위 없이 `corporate-action` SCHEDULER를 센다. 그 실행은 기준일이 오늘이고 좁힐 인자를 안 주므로 `NARROW`가 원리적으로 안 생긴다. 그래서 그대로 둔다(배포 전 운영 DB에는 컬럼이 없어, 고치면 그 전제가 판정 불가가 된다).
  - 「`PARTIAL_SUCCESS`가 시장 통째 실패를 안고 성공으로 셈해져 시계를 민다」는 별도 결함이다. 재고는 `docs/reload-gate.md`에 있다.
- 되돌리려면 술어에서 `scope` 조건을 빼면 된다. 컬럼은 남아도 무해하다.
