refute-p2v10 — 재무 동기 2부 계획 v10 「계획 밖」 렌즈 반증 산출물(2026-10-06 밤)
외부 호출: 0(DART 0 · SEC 0 · Alpaca 0)
DB: 운영 stock_screener SELECT만 · 사본·시험 DB 안 만듦(stock_screener_rp10b_test 안 씀) · 파일·DB 삭제 0 · .env 안 읽음 · gradle 안 씀
코드 기준: main ac038201(읽기만)
띄운 프로세스: 시뮬레이터 JVM 1회(cronsim · 끝남) · 앱·gradle 데몬 87372 안 건드림

notes.txt                              지적별 근거 메모 A~J + 선다
q01_manual_ghosts.sql/.out             MANUAL 유령(스위퍼가 닫은 행) 12 · DART 가능 3(initial-load 76.1~76.2시간) · 08-06~09-08 · 그 뒤 0
q02_manual_start_windows.sql/.out      최근 60일 MANUAL 시작 창 — 평일 00:00-13:30 249/463(54%)
q03_sched_start_clock.sql/.out         SCHEDULER daily-update 시작 시각 이상 3행 · 13:00 전 시작 0/38
q04_kr_filings_before_peak.sql/.out    2025 같은 창 국내 현 편입 재무 행 — 10-26~11-13 Q3 74 · 10-01~10-25 2 · 피크 256
q04b_kr_q3_by_week.sql/.out            그 74가 11-11 5 · 11-12 12 · 11-13 57뿐(10월 0)
q05_dart_jobs_cross_midnight.sql/.out  DART 가능 잡 자정 넘김 0/273
q06_admin_sched_overlap.sql/.out       ADMIN 실행과 SCHEDULER·GAP_FILL 실행의 시간 겹침 0(ADMIN 11 · daily-update 7)
cronsim/CronSleepSimV10.java           rebut-p2v9 CronSleepSimV9 하네스(Spring 7.0.8 실제 CronTrigger·ConcurrentTaskScheduler · 가상 단조 시계 · 풀 1)를
                                       v10 꼴로: 같은 사슬 메서드 13:30·15:00·17:00 + 「오늘(KST) 시작했나」 문 · 15:30 점검 · 토 20:00
cronsim/sim.out                        대조(손 창 계산 근거) · 예 1(수 04:30 사슬이 그날 유일 · 수 13:30·15:00·17:00 문에 걸림) · 예 2(토 22:30 + 일 00:14 사슬 둘)
                                       · 예 3 · 스윕 1,264칸: 13:00 전 유일 사슬 518칸 · 15:30 점검이 같은 날 사슬보다 먼저 0 · 평일 사슬 0회 556 · 겹침 0(칸 수 — 빈도 아님)
cronsim/cp.txt                         클래스패스(rebut-p2v9에서 복사)
