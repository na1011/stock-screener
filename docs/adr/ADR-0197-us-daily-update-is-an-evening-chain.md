---
id: ADR-0197
status: 채택됨
date: 2026-10-31
applies_to:
  - "src/main/java/**/pipeline/scheduler/PipelineScheduler.java"
  - "src/main/java/**/pipeline/batch/DailyUpdateJob.java"
  - "src/main/java/**/pipeline/batch/UsSessionTarget.java"
  - "ops/crons-done.sh"
---

# ADR-0197. 미국 일일 갱신은 평일 21:00 저녁 사슬이다

## 결정

사용자 결정(2026-10-21 · 원문): 선택지 「국내는 지금처럼 13:30 사슬, 미국은 저녁(예: KST 21시 · 뉴욕 개장 전) 사슬로 따로 돈다」를 골랐다 — 「시장별 분리」.

이 ADR이 정한 것(에이전트 결정): 시각은 **평일 21:00 KST**, 잡 이름은 시장마다 따로(`daily-update` 국내 · `us-daily-update` 미국), 미국 대상 세션은 **세션 달력이 정한다**(장 마감 + 14시간이 지난 마지막 NYSE 세션), 원칙 「**봉이 들어온 사슬이 그 봉의 기업 행위 계수까지 닫는다**」로 21:00 사슬이 미국 기업 행위 수집 · 계수 재구축 · 좁힌 화해 · 시세 무결성 점검까지 돈다. 14:00 사슬은 국내만 남는다.

## 규칙

- MUST: 미국 대상 세션을 「직전 평일」로 정하지 않는다 — `UsSessionTarget`이 `core_end + 14h <= 지금`인 마지막 세션을 고른다. 기계 수면으로 크론이 자정을 넘겨 늦게 돌아도(ADR-0116) 장중 세션의 잠정 봉을 안 받는다.
- MUST: 세션을 못 정하면(달력이 비었거나 낡았다) 수집하지 않고 `us-daily-update` FAILED 행 · ERROR 알림으로 끝낸다 — 0건 성공은 신선도 시계까지 초록으로 만든다.
- MUST: `DailyUpdateJob.Markets`의 합집합이 `CollectedMarkets.ALL`과 같다(`DailyUpdateMarketsTest`). 시장을 더하고 묶음에 안 넣으면 그 시장은 어느 일일 갱신도 안 받는데 두 실행 다 초록이다.
- MUST NOT: 한 시장의 일일 갱신이 다른 시장의 `PRICE_GAP` 실패를 닫지 않는다 — 구멍 구간을 그 실행의 시장으로 거르므로 다른 시장 종목은 언제나 「구멍 없음」으로 보인다.

## 검증

- `./gradlew test --tests "*DailyUpdateMarketsTest" --tests "*UsSessionTargetTest" --tests "*PipelineSchedulerTest" --tests "*PipelineSchedulerChainTest" --tests "*CronsDoneTableTest"` — 거름 · 세션 판정(조기폐장 · 휴장 · 서머타임 두 철 · 달력 낡음) · 21:00 크론 × 판정 × 15일 창이 4주 동안 모든 세션을 덮는지 · 사슬 순서.
- 첫 운영 실행의 수용 기준(값으로 잰다 — `PriceWriter`가 값이 같아도 `updated_at`을 찍으므로 시각 비교는 늘 거짓이다): 다음 평일 13:30 `daily-update` 대상에 미국 0 · 14:00 `corporate-action`·`adjustment-rebuild` 대상에 미국 0 · 21:00 `us-daily-update`가 그 세션을 대상으로 SUCCESS · 다음 21:00 실행의 확정 재확인 note에서 미국 「거래량 바뀜」 0.

## 배경

소스가 미국 일봉 거래량을 다음 날 뉴욕 새벽에 고친다 — 늦은 끝 뉴욕 05:40 = KST 18:40(EDT) · 19:40(EST)(`docs/api-spec/alpaca-bars.md` 「일봉 거래량은 다음 날 뉴욕 새벽까지 잠정이다」). 13:30 KST에 받는 미국 거래량은 잠정이었고 ADR-0167 확정 재확인이 하루 늦게 고쳤다 — 그 하루 동안 거래량 유래 지표와 워치리스트가 잠정값 위에서 나갔다. 21:00 KST는 두 철 모두 확정 뒤 1시간 20분 이상이고 뉴욕 개장 전이다.

## 버린 대안

- **잡 이름 하나(`daily-update`)로 두 시장을 다른 시각에 돌기** — 한 시장의 성공이 다른 시장의 연체를 신선도 시계에서 가린다. 재무 기준 판독기(`FinancialBasisReader`)도 그 시장의 쓰기 실행을 못 가른다.
- **기업 행위 · 재구축을 14:00에 두 시장 함께** — 21:00에 들어온 미국 봉과 그 원장 사건이 다음 날 14:00까지 17시간 재구축을 기다린다(순서에서 유도 · 사건 빈도는 재지 않았다).

## 결과

- 사정거리: `corporate-action`·`adjustment-rebuild`는 두 사슬이 같은 잡 이름으로 돈다. 미국 사슬만 며칠 안 돌면 국내 성공이 그 시계를 민다 — 다만 21:00 사슬 전체가 안 돈 경우는 맨 앞의 `market-calendar`(예산 5일)와 `us-daily-update`(예산 5일)가 따로 붉어 잡힌다. 남는 틈은 「21:00 사슬은 돌았는데 그 안의 미국 기업 행위 수집만 매일 실패」이고 그것은 그 실행의 FAILED와 알림이 잡는다.
- 확정 재확인(ADR-0167) 술어는 21:00에 처음 받은 미국 봉도 다음 날 한 번 더 받는다 — 확정값이라 같은 값이고 행이 안 바뀐다(`IS DISTINCT FROM`). 콜 수만 는다.
- 13:30 앞의 공시 목록 · 소비 · 실적 발표 감지는 그대로 두 시장을 돈다 — 재무 장부라 봉 시각과 무관하다.
