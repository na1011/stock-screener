refute17 — 1부 v12(hold/p1 a8d137cf) + main 운영 676a58e6·ae714d38 계획 밖 렌즈 (2026-10-06 밤)
외부 API 0콜 · 운영 DB SELECT만 · 운영 JVM(15406)에 jcmd·신호 0 · 시험 DB stock_screener_r17b_test(createdb -T stock_screener_test · 21:1x)
  merge-sim.sh merge-sim.out q.sh rg-*.md  main 3f071166 + p0 f6a2ecb8(M1 Q 0) + p1 a8d137cf(M2 Q 2: reload-gate 「행마다 바꾼 쪽」으로 풀어 커밋 · IndicatorBackfillParallelTest 양쪽 같음 · 위반 0 · 표지 0)
  sim/ sim-full.*        합친 트리 clean build -PskipFrontend · r17b_test → 1,839 완료 · 실패 1(SpaFallbackTest initializationError = -PskipFrontend 번들 없음) · 건너뜀 5
  p1only/ p1only-full.*  p1 a8d137cf 단독 같은 조건 → 1,838 완료 · 실패 1(같은 SpaFallback) · 5 — SpaFallbackTest 9케이스를 더하면 1,846/0/5(progress 수 재현)
  m1/ m1-full.*          M1(main+p0 = 0부 배포 트리)을 지금 stock_screener_test 사본(r17b_test · V63·V64 적용됨)에 → 1,762 완료 · 750 실패 「Detected applied migration not resolved locally: 64」
  mainonly/ mainonly-full.*  main 3f071166 단독 같은 DB → 1,746 · 736 실패 「…: 63」「…: 64」
  bootline/run.sh run.out  crons-done.sh:81 줄 대 기존 .claude/hooks/lib/load-process.sh stray_load_pids — 더미 java(Sleep.java) 넷 · zsh 하나 · 전부 끔
  sim-fe.* (sim/에 main의 frontend/node_modules 복사 · -x frontendInstall)  합친 트리 배포와 같은 꼴 → rc 0 · JUnit 1,847(1,842/0/5) · vitest 429/429 · r17b_test(V63·V64 든 사본)
  m1-fresh.*             M1을 빈 새 DB stock_screener_r17c_test에 → 1,762 완료 · 실패 1(SpaFallback = -PskipFrontend뿐) — 처방(새 시험 DB) 대조군
  (v13 7dc1260e)         M1 대비 Q = 같은 둘 · reload-gate 같은 충돌(git merge-tree · ref 무변경)

오염 근거: stock_screener_test flyway_schema_history V63·V64 installed_on 2026-10-06 19:41:35.097/.115 ·
  scratchpad/r16/mrl.log 끝 19:41:38 · r16/mrl/build/test-results의 SecFilingSupplement*Test XML이 jdbc:postgresql://localhost:5432/stock_screener_test를 적음
  (rebut16의 p1 코드 사본 시험이 TEST_DB_URL 없이 돌았다) · progress ae714d38(20:08)의 MUST는 그 뒤에 적힌 문장.

쓴 DB: stock_screener_r17b_test(복제 · p1/합친 트리 시험이 씀) · stock_screener_r17c_test(빈 DB · M1 시험이 V1~V63+V900 적용) — 지우지 않았다.
띄운 프로세스: gradle --no-daemon 빌드 여섯(끝남) · 더미 java Sleep.java 넷 · zsh 하나(전부 끔 — run.out K5). 87372와 그 워커는 안 건드림.
