# 파생층 날짜 격리 — 한 거래일의 실패가 나머지 날짜를 막는다 · 계획 v1

작성 2026-10-05 06시. 운영 DB SELECT만.

## 실측
- `IndicatorBackfillJob.rebuildDerived`(화해 ②)와 `run`(날짜 루프 백필)이 날짜마다 `PercentileCalculator.calculate` → `SectorRelativeCalculator.calculate`를 직렬로 부르고, **둘 중 하나가 던지면 잡 전체가 `failUnexpectedly`로 끝난다** — 그 뒤 날짜·고아 정리·거래일 색인이 그날 안 돈다.
- **실제로 있었다**: 실행 318(2026-09-08 01:42 · `indicator-backfill`)이 업종 상대값 SQL의 `PessimisticLockingFailureException`(교착) 하나로 FAILED. 같은 무렵 JVM을 건넌 겹침이 있었다(실행 305가 13:30과 교착 — ADR-0166 §규칙).
- 계산기는 지표마다 SQL 한 문장(UPSERT · DELETE — 자동 커밋)이다. 한 날짜가 중간에 던지면 그 날짜의 일부 지표만 새 값이고, 다른 날짜와는 독립이다.
- 신선도: `JobFreshnessPolicy`는 `PARTIAL_SUCCESS`를 신선으로 센다(:219) — 날짜 실패가 쌓여도 연체로는 안 뜨고, `finish`의 「부분 실패」 경고가 매번 간다.

## 계획 v1
- 두 루프의 파생 계산을 한 자리(`deriveDay`)로 모으고 **날짜마다 격리**한다: `RuntimeException`을 그 날짜에서 잡아 ERROR 로그(날짜 · 예외) + `countExternally(INDICATOR, 0, 1, 0)` + 실패 날짜 목록 → 다음 날짜로 간다. 루프 뒤 고아 정리·색인은 그대로 돈다.
- 실행 note에 「실패 거래일 N(앞 다섯)」을 더한다(② · `run`).
- 종료 신호(`BatchShutdownException`)는 계산기 안에서 안 난다 — `stage()`가 낸다 — 그래서 격리 블록에 안 든다.
- **catch-all이 아닌 이유**: 종목 격리(ADR-0007 `StockTaskRunner`)와 같은 단위 격리이고, 삼키지 않는다(ERROR · 실패 수 · 부분 실패 경고). 그 날짜는 다음 평일 ②가 다시 쓴다.

## 시험
- 계산기 하나를 특정 날짜에서 던지게 한 가짜로: 그 날짜만 실패 · 나머지 날짜 쓴 행 > 0 · 색인 갱신됨 · 상태 PARTIAL_SUCCESS · note에 날짜.
- 대조군: 격리를 빼면 위 시험이 붉는다(지금 main의 모양).

## v1 반증 판정 (계획 안 렌즈 · 「수정 후 진행」)
1. **전 날짜 실패도 `PARTIAL_SUCCESS`(신선)** — `BatchExecutionRecorder:176` · `JobFreshnessPolicy:219` → 성공 0이면 첫 예외로 FAILED 마감 · 「부분 실패」 알림 본문에 실패 날짜.
2. **종료 신호를 되던지는 갈래가 필수**(`data-pipeline.md` MUST) — 종료 중 `CannotGetJdbcConnectionException`이 계산기에서 날 수 있다 → catch 첫 줄에 `BatchShutdownException` 되던지기 + `abandonedOnShutdown()`이면 종료 신호로(`process()`의 `asShutdownSignalIfAbandoned`와 같은 모양).
3. **교착은 상한 있는 재시도가 먼저** — 날짜 격리만 하면 그날 나머지 spec과 업종 상대값을 버린다 · `PessimisticLockingFailureException`은 일시적. **실행 318 인용 정정**: 한 종목 189거래일 백필이 100일째 업종 상대값 SQL에서 교착 · 2분 뒤 같은 모양 319가 189/189 성공(일시적) · 상대는 재지 않았다(305는 다른 날).
4. **셋째 호출부** `DailyUpdateJob`(시장별 기준일 파생) — 던지면 워치리스트 판정·보존 정리·색인이 그날 안 돈다.
5. 관문 대조군(`reload-gate.md` 순서 10 「두 번째 화해 쓴·지운 0」)에 `failure_count = 0`을 더한다 — 결정적 실패 날짜가 두 번 다 실패하면 쓴·지운 0으로 통과한다.
6. `run`은 INDICATOR 계획이 (거래일 × 종목)이라 날짜 실패를 같은 단계에 세면 단위가 섞인다 → 다른 단위로(새 enum이면 `ck_failure_phase` 마이그레이션 · `CollectionPhaseConstraintTest`).
- 통과: 커밋 단위(호출 경로에 트랜잭션 없음 · 자동 커밋) — 날짜를 건넌 섞임 없음 · 고아 정리·색인에 새 해악 없음.
