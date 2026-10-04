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
