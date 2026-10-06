rebut-p2v10 — 재무 동기 2부 계획 v10 「계획 안」 렌즈 반증 산출물(2026-10-06 밤)
외부 호출: DART 0콜 · SEC 0콜 · Alpaca 0콜
DB: 운영 stock_screener SELECT만 · 사본·시험 DB 안 만듦 · DB·파일 삭제 0 · .env 안 읽음 · gradle 안 씀
코드 기준: main ac038201(읽기만) · 보류 브랜치는 이번 판정에 필요한 자리가 없어 안 엶
띄운 프로세스: javac·java(시뮬레이터) 각 수 회 — 전부 끝남
사고 1건: 첫 sim 재실행이 cwd(저장소 루트)에 sim.out(오류 두 줄)을 만들었다 → 내용 그대로 이 폴더 stray-sim-out-moved-from-repo.txt로 옮김(지우지 않음) · git status 깨끗 확인

notes.txt                               지적별 근거 메모 N1~N10 + 선다
cronsim/CronSleepSimV10.java            Spring 7.0.8 실제 CronTrigger·ConcurrentTaskScheduler · 가상 단조 시계(rebut-p2v9 V9 시계 그대로)
                                        v10 꼴(13:30·15:00·17:00 + 「오늘 시작」 문 + ⑨ 15:30 + 토 20:00) · v9 대조 · 처방 변형(13:00 하한)
cronsim/sim.out                         대조(수면 없음) · 예 1(월 18:00 15시간) · 예 3(사슬 도중 2시간) · 스윕 1,264칸 세 판
cronsim/cp.txt                          클래스패스(rebut-p2v9에서 복사)
e01_edge_order.py/.out                  항목 단위 보류 행을 늦게 판정할 때 엣지 알림이 잃고·늘고·밀리는 열거(448)
q01_dart_jobs_cross_midnight.sql/.out   DART 가능 잡 중 자정을 건넌 실행 — 실제 0(ABORTED 유령 셋뿐) / 296
q02_dart_job_overlaps.sql/.out          DART 가능 잡끼리 시간 겹침 — 같은 JVM 0 · JVM 건넘 2(09-14)
q03_late_filled_bar_holes.sql/.out      편입 종목 봉 D가 다음 봉보다 늦게 들어온 (종목, D) — 3건(183300 · 000880 · TEVA)
q04_member_days_without_bar.sql/.out    지금 현 편입 · 편입 구간 안 ADJ_CLOSE 없는 (종목, 날짜) — US 4
q05_manual_ghost_running.sql/.out       MANUAL 유령 RUNNING 12건(DART 가능 initial-load 셋 76시간)
q06_dart_jobs_aborted.sql/.out          DART 가능 잡 끝 상태별 — MANUAL ABORTED 6(평일 5)
q07_kr_listed_shares_null_by_year.*     국내 봉 listed_shares NULL — 2020~2026 전 연도 0
q08_missed_weekdays_any_app_trace.*     SCHEDULER daily-update 없던 평일 4의 앱 흔적 — 08-17 · 09-16은 앱 행 0
q09_admin_daily_update_vs_scheduler.*   관리자 daily-update 7회 · 사슬과 겹침 0
q10_exited_members_adjclose.*           08-10 뒤 편출돼 지금 밖인 101종목 · 편출 뒤 ADJ_CLOSE 25% · 워치리스트 2(알림 1)
