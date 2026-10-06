refute-p2v9 — 재무 동기 2부 계획 v9 계획 밖 반증 산출물(2026-10-06 밤 · 외부 API 호출 0 · 운영 DB stock_screener SELECT만 · 시험 DB 안 만듦 · gradle 안 띄움 · 파일·DB 삭제 0)

notes.txt                         지적별 중간 메모(근거 줄 번호 · 처방 후보)
g01_cron_refs_main.txt            main 전수 grep — 크론 시각(14:00·14:15·14:18·14:20·15:30·13:30~15:4x)·사라질 메서드 이름 209줄(progress 제외)
g02_indicator_runner_callers.txt  IndicatorRunner 호출자(미표시가 닿아야 할 계산 경로 — DailyUpdateJob · IndicatorBackfillJob · 탐침 PointInTimeScreener)
g03_watchlist_in_daily_update_consumers.txt  워치리스트 판정이 DailyUpdateJob 안이라는 것에 기대는 자리
g04_hold_branches_cron_refs.txt   보류 브랜치 p0·p1 diff의 크론 소비자(0부 수용 시험이 사라질 메서드 넷을 부름)
q01_late_start_days.*             09-02~10-06 평일별 SCHEDULER 첫 시작(13:30 없이 뒤 크론만 돈 날 0/25)
q02_daily_update_paths.*          daily-update ADMIN 이력 · SCHEDULER 없는 평일(08-11·08-17·08-21·09-16 — 08-11은 13:30 크론이 그날 15:51 커밋으로 생김)
q03_watchlist_population.*        알림 항목 1(005930 KOSPI · 화면 1 · 사용자 1)
q04_reconcile_status.*            SCHEDULER 화해·사슬 안 백필 상태(실패 0/7)
q05_daily_update_stock_failures.* 종목 단위 실패가 있던 일일 갱신(SCHEDULER 38 중 1 · #500 12종목)
q06_scheduler_aborted.*           앱 JVM 실행의 ABORTED·FAILED(SCHEDULER 0 · ADMIN 1)
q07_other_jvm_dart_days.*         9월 이후 평일 중 MANUAL(bootRun)이 DART를 쓸 수 있는 잡을 돌린 날 15
