rebut-p2v8 — 재무 동기 2부 계획 v8 「계획 안」 렌즈 반증 산출물(2026-10-06 밤)
외부 호출: DART 0콜 · SEC 0콜 · DB: 운영 stock_screener SELECT만(사본·시험 DB를 만들지 않음) · 파일·DB 삭제 0
코드 기준: main 70e10d57(읽기만) · origin/hold/p0-run-scope f6a2ecb8 · origin/hold/p1-filing-supplement 56a97d3d
띄운 프로세스: 시뮬레이터 JVM 6회(각자 끝남) · gradle 안 씀 · .env 안 읽음

notes.txt                         지적별 근거 메모(코드 줄 · 질의 · 수)
q01_job_durations.sql/.out        SCHEDULER 잡별 소요(60일) — daily-update 최대 26.6분(정상 최대 23.9) · corporate-action 최대 15.9분
q02_daily_update_trend.sql/.out   daily-update 소요 추세(09-01 13.6 → 10-05 23.9)
q03_window_members_by_market.sql/.out  기업 행위 사슬 대상(windowMemberIds 술어) 시장별 — 국내 475(현 350 + 과거 125) · 미국 1,208
d5_fsr_only_columns.out           두 매퍼(RawFinancialReport 생성자 인자)에서 FSR 전용 칸 유도 — 9칸 · FR 전용 0
cronsim/CronSleepSimV8.java       Spring 7.0.8 실제 CronTrigger·ConcurrentTaskScheduler를 가상 단조 시계에(풀 1) — v8 13:30 한 메서드 + 14:00·14:18·14:20·15:30
cronsim/sim.out                   시나리오 none · night(대조군) · after-method(2h) · after-method-55 · after-method-45 · heavy-day(목 15:34 2h)
cronsim/cp.txt                    클래스패스(앞 반증에서 복사 · gradle 캐시의 spring 7.0.8 jar)
