rebut-p2v7 — 재무 동기 2부 계획 v7 「계획 안」 렌즈 반증 산출물(2026-10-06 · 외부 호출 0 — DART 0콜 · SEC 0콜)
DB: 운영 stock_screener SELECT만 · 사본 stock_screener_q4v_rs1c SELECT만 · 사본을 만들지 않음 · 파일·DB 삭제 0
코드 기준: main 0d389eb7(읽기만) · 띄운 프로세스: 시뮬레이터 JVM 3회(각각 스스로 끝남)
앞 에이전트(세션 한도로 끊김)의 중간 산출물을 이어 썼다 — 사실로 옮긴 것은 아래처럼 다시 쟀다.

[앞 에이전트 산출물 · 다시 잼]
cronsim/CronSleepSimV7.java · sim.out   Spring 7.0.8 실제 CronTrigger·ReschedulingRunnable을 가상 단조 시계로(풀 1) — 12:30 단계·13:30 갱신 순서
cronsim/rerun.out                        내가 JDK 21로 A·B·D를 다시 돌림(sim.out과 같음 · C는 안 돌림) — B(13:20부터 70분 수면)에서 다음 날 13:30이 12:30 단계보다 먼저
q01_pbr_pctl_contamination.sql/.out      2026-08-14 PBR과 그날 Q2를 낸 종목의 Q2·Q1 자본(0콜) — 내가 다시 돌린 q01_rerun.out과 바이트 같음
q01_pbr_pctl_sim.py/.out                 보류 안 된 종목의 PBR_PCTL<=0.30 뒤집힘 모의(PERCENT_RANK · 유니버스 분할 = PercentileCalculator RANKED)
                                         ※ 입력 파일명을 q01_rerun.out으로 내가 바꿈(sed) — 결과는 .out과 같음
sec_index_kst.py/.out                    EDGAR daily-index 2025 QTR4 last-modified를 KST로(rebut-p2v1/sec 저장본 · 0콜) — 내가 다시 돌려 같음

[내가 더한 것]
q01_rerun.out                            q01 질의 재실행
q02_cron_differential.sql/.out           평일 SCHEDULER 첫 발화(13:30·14:00)의 명목 대비 지연과 그 차(크론 사이 수면의 실례 찾기) — 37일 중 지연 2(08-19 +8분 · 10-06 +115분) · 차 0
ca_calls_per_stock.py                    DartIrdsReports.covering을 옮겨 14:00 기업 행위 사슬의 국내 종목당 irdsSttus 콜 = 2 → ×350 = 700(10-06 · 11-17 · 2027-02-01 같음)
notes.txt                                지적별 근거 메모(코드 줄 · 질의 · 수)
