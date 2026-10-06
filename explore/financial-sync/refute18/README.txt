refute18 — 1부 v14(hold/p1 e341c70c) + main 운영 676a58e6·3cdca953·072e0bdc 계획 밖 렌즈 (2026-10-06 밤)
외부 API 0콜 · 운영 DB SELECT만 · 운영 JVM 무접촉 · 시험 DB stock_screener_r18b_test
  merge-sim.sh merge-sim.out  main 072e0bdc + p0 f6a2ecb8(M1 Q 0 · 위반 0) + p1 e341c70c(M2 충돌 = reload-gate 한 덩어리 · (가) 행은 p1 · 나머지 main으로 풀어 커밋 · Q 2 = reload-gate · IndicatorBackfillParallelTest(양쪽 같음) · 위반 0 · 표지 0) — refute17과 같은 충돌
  builds.sh p1only-full.* sim-full.*  TEST_DB_URL=r18b · clean build -x frontendInstall(main node_modules 복사)
     p1only e341c70c → JUnit 1,848/0/5(러너 XML · ElementTree) · vitest 429 · rc 0 → progress 「1,848/0/5」 재현
     sim(합친 트리) → 1,849/0/5 · vitest 429 · rc 0 · XML의 jdbc는 전부 r18b
  guard/  deploy.sh 시험 DB 가드 연습 — 가짜 홈(앱 PID 파일 없음 → 크론 문·종료·스테이징 안 탐) · 가짜 gradlew(호출만 gradle-calls.log에 적고 rc 1)
     트리 tm=main 072e0bdc · t0=main+p0(953598b7 · V63) · t1=main+p0+p1(eb792189 · V64) — sim에서 --shared 클론
     DB(이력 표만 지은 것) stock_screener_r18g_{v62,v63,v64,empty}_test · 실물 사본 r18g_old64_test(= p1v9_test 복제 · V64 체크섬 -478494443 ≠ 지금 2105007020)
     drill.sh drill.out: tm·v63/v64/old64 멈춤 · t0·v64/old64 멈춤 · t0·v62/v63/empty 통과 · t1 다섯 다 통과(old64 포함) — 계획 순서(0부 → 1부)에서 가드는 맞게 선다
     old64-test.log: 합친 트리(sim)의 StockScreenerApplicationTests를 r18g_old64에 → 「Migration checksum mismatch for migration version 64」 · BUILD FAILED — 가드가 통과시킨 DB에서 빌드가 선다(체크섬 틈)
     nolib.out: .claude/hooks/lib 없는 트리의 crons-done.sh → line 84 No such file · rc 1(계약상 2여야 함 · 「크론이 남았다」로 읽힘)
     fut-tm.log fut-t0.log + zz 탐침: Flyway 12.4.0 · classpath:db/migration만 · V1~64+V900 사본(r18g_fut_test = r18b 복제) → 옛 코드(V62·V63) validate valid · migrate 0 · 경고 0 (*:future 기본) — Spring 배선은 안 잼(yaml은 enabled·locations뿐)
  mainonly/ mainonly-full.*  main 072e0bdc 단독 · 새 빈 DB stock_screener_r18c_test → JUnit 1,754/0/5 · vitest 427 · rc 0 (3cdca953 본문 수 재현)
  staleness-{mainonly,sim}.out  check-doc-staleness.sh를 두 트리에서 — 차이는 크기 줄(ADR-0173 · data-pipeline · sec-edgar-financial)뿐 · §5c 잡 이름 어긋남 0
  progress:93 「그 출력의 bootstrap 프로파일 프로세스가 없을 때」 — crons-done 출력은 3cdca953부터 「적재 JVM(bootRun …)」 줄(progress:306만 고쳐짐)
  p1v14 실키 연습 산출(scratchpad/p1v14-cache) 확인: calls=7 = companyfacts 2 + submissions 2 + 페이지 1 + 색인 1 + 인스턴스 1 · 인스턴스 1,850,578바이트 = 색인 size
