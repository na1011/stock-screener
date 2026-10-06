refute-p2v11 — 재무 동기 2부 계획 v11 「계획 밖」 렌즈 반증 산출물(2026-10-06 밤)
외부 호출: 0(DART 0 · SEC 0 · Alpaca 0)
DB: 운영 stock_screener와 사본 10개(q4v_r13a·r13b·rs1a~e·r15b·sc_a·sc_c) SELECT만 · 시험 안 돌림(stock_screener_rp11b_test 안 만듦·안 씀)
    · 파일·DB 삭제 0 · .env 안 읽음 · gradle 안 씀 · 저장소(main) 파일 안 씀
코드 기준: main 072e0bdc(3cdca953 포함) · 보류 origin/hold/p0-run-scope f6a2ecb8 · origin/hold/p1-filing-supplement e341c70c(읽기만)
띄운 프로세스: ph/PH.java JVM 1회(ProcessHandle 열거 · 끝남) · 앱 15406 · gradle 데몬 87372 · gradle 워커 안 건드림(argv를 읽기만)

notes.txt                                 지적별 근거 메모 A~F
q01_copy_only_exec_rows.out               사본 10개의 batch_execution 중 운영에 없는 행(사본에서 돈 드라이런) — 전부 10-05 22:06~23:21 ·
                                          사본은 다시 떠서 이력이 남지 않는다 → 사본 드라이런 빈도는 DB로 못 센다(하한조차 없음)
ops_exec.tsv                              비교용 운영 (id, job, trigger, started) 744행
q02_manual_overlap_chain_window.sql/.out  최근 60일 평일 운영 MANUAL이 [13:30,17:00]과 겹친 날 18 · 13:30을 덮은 날 6 · 17:00을 덮은 날 3 · 09-17 뒤 덮음 0
ph/PH.java · ph/ph.out                    이 기계에서 ProcessHandle이 같은 사용자 JVM 16개의 argv를 전부 읽음(빈 arguments 0 · 명령 못 읽은 것 229는 java 아님)
                                          — 결정 2의 「판정 불가」 갈래는 여기서 안 생기고, 문제는 사본 드라이런을 운영 적재와 못 가르는 쪽(argv 동일 · DB는 환경변수)
