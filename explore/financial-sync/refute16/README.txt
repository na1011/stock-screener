refute16 — 1부 v11(hold/p1 56a97d3d) + main 운영 고침(ab4356ca · d023cd9f · 70e10d57) 계획 밖 렌즈 (2026-10-06 밤)
외부 API 0콜 · 운영 DB SELECT만 · 운영 JVM 15406에 jcmd·신호 0 · 시험 DB stock_screener_r16b_test · 운영 문 연습 DB stock_screener_r16b_ops(r16b_test 복제 · 유령 행 2 삽입)
  sim/                 --shared 클론: main 70e10d57 + p0 f6a2ecb8(M1) + p1 56a97d3d(M2 · reload-gate 충돌을 「행마다 바꾼 쪽」으로 풀어 커밋)
  merge-sim.sh merge-sim.out q.sh m2-assert.txt m2-rowdiff.txt rg-*.md   병합 단언(M1 Q 0 · M2 Q 2 · 위반 0 · 표지 0 · 표 72행 = main에서 (가)만 p1판)
  sim-full.log sim-full.start     합친 트리 ./gradlew build --no-daemon(-x frontendInstall · node_modules 복사) rc=0 · JUnit 1,838/0/건너뜀 5
  p1only/ p1only-full.*          p1 56a97d3d 단독 --shared 클론 빌드(progress 「1,837/0」 재현)
  parse/                         companyfacts 1,218파일 Jackson 1회 파싱 합 7.0초(최대 19ms MET) — v11의 이중 파싱 비용
  ops/                           main 70e10d57 ops 사본 · 가짜 ps/jcmd/date · 가짜 홈 · run-base.out(열세 갈래) · run-rows.out(유령 행)
  ops/shim-nojcmd/               진짜 crons-done을 운영 JVM에 jcmd 없이(19:29 rc 0 · 다음 명목 10-07 13:30)
  m2-test-results/               M2 JUnit XML 사본
  m1-full-v64db.log              M1(main+p0) 빌드를 V64가 적용된 시험 DB(r16b_test)에 → 758 실패 「Detected applied migration not resolved locally: 64」
  m1b-full-badname.log           (내 실수) DB 이름 r16b_m1test에 「_test」가 없어 TestDatabaseGuard가 막음 — 결과로 쓰지 않음
  m1c-full.*                     M1을 V62 사본 stock_screener_r16m1_test에 → rc 0 · 1,770/0/5 (대조군)
  ops/run-rows.out               유령 GAP_FILL(10-03 · 꼬리) + 유령 MANUAL(09-08) → 현 main crons-done rc 1 「앱이 돌리는 배치가 1개다」
  ops/opsfix/ ops/run-fix.out    처방 사본(triggered_by <> 'MANUAL' and started_at >= JVM 기동) → 유령만이면 rc 0 · 기동 뒤 GAP_FILL이면 rc 1
  ops/deploy-noenv/deploy.out    가짜 홈 배포 · 저장소 .env 없음 → 종료 뒤 stage 실패 → 앱 내려간 채 rc 1 · 배포 폴더 그대로 · 재기동 없음
  ops/stop-flags/app-tunnel.out  stop.sh --app --tunnel → rc 0 · 아무것도 안 내림(ab4356ca 앞은 용법 오류 2)
쓴 DB: stock_screener_r16b_test · stock_screener_r16b_ops(유령 행 3 삽입) · stock_screener_r16b_m1test(이름 실수 · V63 적용됨) · stock_screener_r16m1_test — 지우지 않았다.
띄운 프로세스: 더미 sleep 셋(전부 끔) · gradle --no-daemon 빌드 넷(끝남).
