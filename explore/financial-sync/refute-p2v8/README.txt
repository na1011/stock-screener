refute-p2v8 — 재무 동기 2부 계획 v8 계획 밖 반증 산출물(2026-10-06 밤 · 외부 API 호출 0 · 운영 DB stock_screener SELECT만 · 시험 DB 안 만듦 · 파일·DB 삭제 0 · 띄운 프로세스는 시뮬레이터 JVM뿐이고 각자 끝남)

g01_1415_consumers.txt        「14:15·14:1x·runHeavyJobOfTheDay」 grep — main · origin/hold/p0-run-scope · origin/hold/p1-filing-supplement
g02_v8_list_vs_grep.txt       v8 결정 8 목록에 없는 소비자(코드·시험·문서·화면) + 「13:30~15:40 밖」 문장 소비자
m01_endtime_model.py/.out     끝 시각 모형 — 13:30 메서드 뒤 사슬·유도·화해까지 · 단계 짝을 결정 3 예산에 묶음(G 날 화해 끝 15:50 · main 15:31)
q01_sched_durations.*         SCHEDULER 잡 소요(60일)
q02_daily_and_heavy.*         일일 갱신 실행별 소요(13:35 → 23:51) · 기업 행위(1,683 대상 15:56) · 무거운 잡 전량 실행
q03_watchlist_edges.*         알림 항목 화면(「저평가 가치주」 조건 8)을 KOSPI 255거래일에 다시 판정 → 참 런 길이 분포(53 중 1일 20)
q03b_one_day_runs.* · q03c_*  1일 런 목록 · 고립(앞뒤 7일 안에 다른 참 런 없음) 8 / 깜빡임 11
q04_daily_status.out          daily-update 상태(08-10 뒤 SCHEDULER FAILED·ABORTED 0)
d01_decision6.txt             결정 6 — 따라잡기 없는 보류는 알림을 잃음 · 「재판정 불가」 근거가 보류 항목엔 안 섬(10분 메모리 억제 · 기록 없음) · 끝 상태 공백(미국 UNREADABLE · 국내 (a))
d02_decision2_vs_p0_scope.txt 결정 2의 시장별 FR × 0부 FULL/NARROW 시계
s01_heavy_decision_instants.* 예약 시점(13:30:05)과 선택 시점의 「그날 무거운 잡」이 갈리는 꼴(Duration.toDays 자름)
s02_heavy_decision_montecarlo.* 씨앗 500 · 1년 — 갈리는 날 평균 1.07/년 · 전부 (없음 → FR) · 단계가 무거운 날과 겹침 0
cronsim/CronSleepSimV8.java · s03_*  실제 Spring 7.0.8 스케줄러 · v8 크론(13:30 메서드 100분/30분) · 월 수면 스윕 → 화요일 순서 어긋남 25/438(사슬<메서드 35분↑ · 화해<메서드 55분↑)
cronsim/CronSleepSimMain.java · s04_*  같은 스윕 main(14:15 G 55분) — 화해<무거운 잡 18/438(10분↑)
