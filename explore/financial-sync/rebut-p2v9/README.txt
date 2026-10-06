rebut-p2v9 — 재무 동기 2부 계획 v9 「계획 안」 렌즈 반증 산출물(2026-10-06 밤)
외부 호출: DART 0콜 · SEC 0콜(crons-done.sh 20:12:07 rc 0 · 앱 PID 15406 · bootstrap 없음 — 실키는 안 씀: 결정 4는 저장 응답 65라벨로 판정)
DB: 운영 stock_screener SELECT만 · 사본·시험 DB 안 만듦 · 파일·DB 삭제 0 · .env 안 읽음 · gradle 안 씀
코드 기준: main 8f36451b(읽기만) · origin/hold/p0-run-scope f6a2ecb8(scopeOf 위치만 확인)
띄운 프로세스: 시뮬레이터 JVM 1회(끝남)

notes.txt                          지적별 근거 메모 N1~N8 + 선다
q01_sched_status_window.sql/.out   SCHEDULER·GAP_FILL 실행 중 SUCCESS 아닌 행 — 전 기간 2행(#278 · #500) · ABORTED·FAILED 0
q02_listed_shares_null.sql/.out    봉 listed_shares NULL — KOSPI 0 · KOSDAQ 0 · US 전부(400일) → 주식수 넷은 미국에서만 재무 유래
q03_fire_times.sql/.out            SCHEDULER 첫 잡 발화 지연(±2분 밖) — 38회 중 08-10 · 08-19 +8 · 10-06 +115
q04_weekdays_without_run.sql/.out  08-10~10-06 평일 42 중 SCHEDULER daily-update 0회 4일(08-11 · 08-17 · 08-21 · 09-16)
q05_earliest_bar.out               시장별 최초 봉(국내 2020-01-02 · 미국 2019-09-03) — 사슬 창 시작
q06_ofs_candidates.sql/.out        DB상 OFS 전용 국내 현 편입 14(실키 탐침 후보 — 안 씀)
q07_manual_dart_weekday_morning.*  최근 60일 평일 00:00~13:30에 시작한 MANUAL DART 잡 79실행
c01_calculators_raw.tsv            계산기 99개 · 재무 낱말 수 · dependsOn
c02_covering_size.py/.out          DartIrdsReports.covering 이식 — 국내 창(2020-01-02~)에서 2026~2027 매일 2콜/종목 → 950 선다
c03_stage_capacity.py/.out         결정 3 단계 짝 상한(비강제 210 · G 강제 111 · FR 강제 54 · FSR 강제 14) · 287은 하루에 못 듦
cronsim/CronSleepSimV9.java        Spring 7.0.8 실제 CronTrigger·ConcurrentTaskScheduler · v9 꼴(평일 13:30 메서드 하나 + 토 20:00) · 가상 단조 시계 · 풀 1
cronsim/sim.out                    대조(수면 없음) · 예 1(월 18:00 15시간 → 화 발화가 수 04:30 · 화 날짜 0회) · 예 2(금 12:00 33시간 → 토 22:30 · 리밸런싱 월 05:00)
                                   · 스윕 1,264칸: 12:30 전 시작 441 · 같은 날 두 번 353 · 주말 시작 110 · 평일 0회 594 · 겹침 0(칸 수 — 실제 빈도 아님)
cronsim/cp.txt                     클래스패스(refute-p2v8에서 복사)
