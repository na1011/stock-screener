---
id: ADR-0178
status: 채택됨
date: 2026-10-15
applies_to:
  - "src/main/java/**/batch/UsCandidateFeed.java"
  - "src/main/java/**/batch/CandidateIntake.java"
  - "src/main/java/**/batch/CandidateSplitJob.java"
  - "src/main/java/**/batch/FilingConsumptionJob.java"
  - "src/main/java/**/collector/FilingLedgerStore.java"
---

# ADR-0178. 미국 순위 후보를 정기 경로로 먹인다

## 결정

미국 `US_TOP1000`의 순위 재료(저장 주식수 × 종가 · `JdbcStoredShareCounts`)를 편입 밖 종목에도 최신으로 둔다. 자동 실행이 도는 모집단을 둘로 이름 붙인다.

- **활성 편입** = 스크리닝 모집단(그대로).
- **미국 순위 후보** = 미국 · 활성 종목 · 활성 편입 없음(`StockRegistrar.usRankingCandidateIdsByTicker` · `FilingLedgerStore.openForCandidates`). 순위 어댑터의 판독기(미국 · `s.is_active`)와 같은 축이다.

후보에게 세 다리를 붙인다. 셋 다 `UsCandidateFeed`(`stockscreener.us-candidates.enabled` · 기본 꺼짐)가 켜졌을 때만 정기 경로에서 돈다.

| 자리 | 다리 | 하는 일 |
|---|---|---|
| 평일 14:16 사슬 넷째 단계 | `candidate-consumption` | 후보의 열린 장부 행을 편입 소비와 같은 판정으로 먹인다(좁힌 FR · `GAP_FILL`) |
| 평일 14:00 사슬 수집 뒤 · 재구축 앞 | `candidate-splits` | 시장 전체 분할·병합 → 후보의 최신 주식수 공시일 뒤 사건을 원장에 |
| 토 20:00 리밸런싱 | 후보 풀 `US_LISTED` 화해 + `CandidateIntake` | 새 상장에 `stock` 행 → 그 행에 업종 → 재무(SEC 매핑 있는 것만) |

## 규칙

- MUST: 후보 술어는 한 조각이다 — 「활성 편입」은 `StockRegistrar.activeMembershipExists`로 쓴다. 편입 소비와 후보 소비가 갈리면 한 종목이 두 다리에 다 들거나 어디에도 안 든다.
- MUST NOT: 후보 경로에 비활성 유니버스 갈래(`universeCode`)를 쓰지 않는다. 그 갈래는 손으로만 쓴다(ADR-0088·0090 — 「활성」의 뜻을 한 가지로). 후보는 이름 붙은 모드로 푼다.
- MUST: 후보 분할은 `CorporateActionCollector.recordAll`로만 적는다(근접중복·상태 규칙 하나). 공시는 `APPROVED`로 들어가고 재구축이 `APPLIED`로 올린다 — 재구축의 기본 대상에 「창 밖인데 적용 대기 행이 있는 종목」(`unappliedApprovalIdsOutsideWindow`)이 들어 있어야 순위가 그 행을 읽는다.
- MUST NOT: 후보에 분사를 적지 않는다. 원장의 분사 `ratio`는 주식 비율(1)이라 순위에 안 닿고, 봉이 없는 후보는 가치 판정이 미해결로 남아 판정 대기 알림만 는다.
- MUST: 후보 다리는 자기 try다 — 14:16에서는 편입 소비·따라잡기 뒤, 14:00에서는 편입 재구축 앞이지만 실패가 그것을 막지 않는다(처음 예외는 사슬 끝에서 다시 던진다).
- MUST: 후보 소비의 시도 상한(평일 5회 · 편입 지연 경보와 같은 수)에 막 닿은 행을 한 번 알린다. 상한에 닿은 종목은 새 공시(새 행)가 깨운다. 손 실행은 상한이 없다.
- MUST: 화해로 넘기는 무효화 티커는 보존 창 편입만이다(FR 알림의 「창 밖」 줄 · 재구축 `Invalidation`). 창 밖 종목의 지표 행은 그날 편입으로 거르는 읽기 경로(백분위·섹터 상대·화면·워치리스트) 어디에도 안 닿는다.
- MUST: 설정을 켜는 커밋에서 `JobFreshnessPolicy`에 `candidate-consumption`·`candidate-splits` 예산을 더한다 — 꺼진 동안 넣으면 신선도 점검이 「한 번도 안 돈 잡」으로 운다.

## 검증

- `rg -n "usRankingCandidateIdsByTicker|openForCandidates" src/main/java` — 후보를 푸는 자리가 이 둘뿐이다.
- `rg -n 'oneUniverseMemberIds\(' src/main/java/com/stockscreener/pipeline/batch/CandidateIntake.java src/main/java/com/stockscreener/pipeline/batch/CandidateSplitJob.java src/main/java/com/stockscreener/pipeline/batch/FilingConsumptionJob.java` 가 아무것도 반환하지 않아야 한다(비활성 유니버스 갈래를 안 쓴다).
- 시험: `FilingConsumptionJobTest`(후보 6) · `CandidateIntakeTest` · `CandidateSplitJobTest`(분할 → 재구축 → 순위 주식수까지) · `UniverseRebalanceTailTest`(설정 꺼짐·켬) · `PipelineSchedulerChainTest`(두 사슬의 자리와 예외).

## 배경

미국 `stock`은 08-29 부트스트랩 뒤로 안 자라고(새 상장 156 · 2026-10-15), 정기 FR·2부 소비는 활성 편입만 먹여 후보 1,427행 · 774종목의 공시가 장부에 쌓인 채 08-30 판에 멈춰 있었다. 원장도 보존 창 편입만 채워 후보 194종목이 공시 뒤 분할 전 주식수로 순위에 들어갔다(BYND 30배 오편입이 같은 꼴). 주식수 클래스 축 계획 v4 반증은 이 상태가 「판 숨김 → `noShares` 편출 → 다음 공시가 안 와 복귀 없음」을 만든다고 짚었다. 실측과 반증 판정은 탐색 브랜치 `explore/financial-sync/CANDIDATE-PLAN-V2-1015.md`에 있다.

`JdbcStoredShareCounts` javadoc의 「유니버스를 정하려고 유니버스 밖 전 종목의 원장을 채우는 것이 그 대가다」는 비용 근거였다 — 소스(Alpaca)는 시장 전체 분할을 1~3콜에 준다(2025-10-15~2026-10-14 · 1,345건 · 2콜).

## 버린 대안

- **`US_LISTED` 열린 편입을 후보 술어로**(v1): Alpaca 목록이 빠뜨린 상장(ADR-0171)과 화해에서 편출된 종목의 공시를 영영 안 먹인다 — 실측으로 열린 후보 행 중 `US_LISTED` 밖이 1종목(`OMH`)이었다. 순위 판독기와 같은 축(편입 조건 없음)으로 바꿨다.
- **FR에 `universeCode = US_LISTED` 갈래로 부르기**: 위와 같은 이유로 대상이 모자라고, 비활성 갈래를 스케줄러에 거는 것이 된다.
- **기업행위 잡의 모집단에 후보 전부를 더하기**: 종목마다 1콜 이상이라 하루 수천 콜이고, 수집기는 봉 없는 종목을 건너뛴다(그 거름은 수정주가라는 소비자의 것이고 순위 보정에는 안 선다). 시장 전체 조회가 순위에 필요한 사실(분할·병합)을 다 준다.
- **순위 판독기가 `APPROVED`도 읽게 하기**: 원장의 「`APPLIED` = 반영 끝」 불변식을 판독기마다 다르게 만든다. 재구축이 봉 없는 종목도 표시하므로(「갱신 0행이어도 표시」) 대상만 넓히면 된다.
- **후보 실패를 매 평일 다시 묻기**: #182의 후보 실패(companyfacts 404 등)는 같은 답이 반복되고 부분 실패 알림이 상시가 된다. 시도 상한 + 새 공시가 깨움으로 바꿨다.

## 결과

**D4 드라이런(2026-10-15 · 작은 DB `stock_screener_cand_d4` = 운영 스키마 + 참조 표 + 미국 전 종목 최신 주식수 판 + 고른 31종목 전 이력 · Alpaca 약 4콜 · SEC 약 300콜 · 근거는 탐색 `CANDIDATE-PLAN-V2-1015.md` §8)**:
- 분할 다리: 「공시 뒤 분할이 원장 `APPLIED`에 없는 후보」(Alpaca 1년치 대조 · 운영 194) → **0** — 첫 실행 176종목 193행 · 원장 근접중복을 봉 없는 종목에 평일로 세게 고친 뒤(`d8fe5f3f`) 남은 진짜 연속 병합 4행 · 셋째 실행 0행.
- 후보 소비: 31종목 53행 → 52행 닫힘(COMPANYFACTS 15 · NO_XBRL 36 · SUPPLEMENT 1). companyfacts 404 8곳의 XBRL 없는 6-K는 FR 실패와 무관하게 닫게 고쳤다(`60208074`). 남은 1행(`TGE` 6-K)은 XBRL이 있는데 companyfacts가 404다(아래). 최신 주식수 판이 새 공시로 바뀐 종목 12(`HAO`·`BTLN`의 큰 감소는 병합 1:128·1:20 · 1:8 — Alpaca). 창 밖 13종목의 지표 행이 지워지고 알림이 그 13을 「화해가 다시 쓰지 않는다」로 따로 적었다(`TLRY` 1,000 → 0) · 창 안 과거 편입(`AIR`·`RGC`)은 무효화 0.
- 토요일: `US_LISTED` 편입 165 · 제외 133 · 새 행 156 전부 업종 수집 · SEC 매핑 99 중 FR 성공 94 · 주식수 생긴 새 행 22 — 그중 1,000위 문턱을 넘는 것은 `NFEGP`(전환우선주 · 이름 거름) 하나라 **틀린 편입 0**.
- 손 리밸런싱이 ADR-0177 꼬리(따라잡기)도 탄다 — D4는 V69 표지가 비어 편입 48종목에 FR·FSR·G·화해가 돌았다(DART 실호출 421). 운영도 V69 배포 뒤 첫 토요일 꼬리가 같은 일을 한다(ADR-0177이 정한 것).

- 후보의 주식수가 최신 공시를 따라가고, 공시 뒤 분할이 순위에 실린다. 새 상장은 첫 토요일에 행이 생기고 그때까지 나간 공시를 한 번에 받는다.
- 비용: 14:16 후보 소비 — 비수기 하루 약 102행 · 최고점 하루 488종목 약 8분(14:18·14:20이 그만큼 밀린다) · 14:00 — 시장 전체 1~3콜 · 토요일 — 새 행 주 약 24.
- 관문: 주식수는 `overwritable()`이 거짓이라 규칙이 바뀌어도 후보의 옛 행이 안 고쳐진다 — 미국 관문 재적재 범위에 순위 후보를 넣는다(`docs/reload-gate.md`).
- 되돌리기: 설정을 끄면 정기 경로가 이 ADR 전과 같다. 이미 적힌 후보 재무·원장 행은 남는다(원자료는 되돌리지 않는다 · ADR-0071).
