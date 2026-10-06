refute-p2v7 — 재무 동기 2부 계획 v7 계획 밖 반증 산출물(2026-10-06 · 외부 API 호출 0 · 운영 DB SELECT만 · 파일·DB 삭제 0 · 띄운 프로세스는 시뮬레이터 JVM뿐이고 각자 끝남)

앞 에이전트(세션 한도로 끊김)가 남긴 것 — 아래는 다시 잰 뒤 씀
cronsim/                          Spring 7.0.8 실제 ConcurrentTaskScheduler·CronTrigger를 가상 단조 시계로 돌림(V6 = 14:15·14:17 · V7 = 12:30 한 메서드)
cronsim/sim.out                   앞 에이전트 출력
pmset.log                         pmset -g log 사본(09-29 ~ 10-06 · 주중 덮개 배터리 수면은 10-05 18:55 하나)
q02_us_revisers_per_day.*         2025 10-Q 철 현 편입 미국 일별 공시 종목·정정 종목(q02_rerun.out = 다시 돌린 것 · 같음)
q03_share_basis_flagged.*         주식수 기저 표지(앞 판 · 유니버스 코드 확인 안 됨 → q06로 대체)
q04_sec_index_margin.out          2025 QTR4 일일 색인 last-modified(저장본 rebut-p2v1/sec/index-2025QTR4.json) → KST · 12:30까지 여유(EDT 최소 69분 · EST 최소 8분 17초 = 11-25 · 12:30 뒤 0/62)

내가 잰 것
s01_sim_rerun.out                 cronsim V7 다시 돌림 — midday(월 13:40 +2h 수면) → 화 13:30 워치리스트 13:30:00 · 12:30 단계 14:30:00(역전) · 14:18 유도·14:20 화해도 단계 앞 · night(18:55 +2h) 대조군은 고르게 밀려 순서 보존 · none 정상
q05_corp_action_runs.*            14:00 기업 행위 실행(대상 1,350 → 10-05부터 1,683 · 콜 수는 실행 기록에 없음)
q06_share_basis_now.*             주식수 기저 표지 종목 = AKTX(UNRESOLVED 22행) · AMRN(SHIFTED 7행) · 둘 다 현 편입 아님(US_TOP1000 · KOSPI_TOP200 · KOSDAQ_TOP150)
q07_watchlist_conditions.*        알림 항목 1(005930) 조건 8 중 셋이 백분위(PER_PCTL·PBR_PCTL·GP_TO_ASSETS_PCTL — 모집단 = 유니버스 · PercentileCalculator:44-49)
g01_wallclock_consumers.txt       벽시계 창·크론 목록·14:15 소비자 grep(main · hold/p1) + PipelineSchedulerTest 순서 단언
