rebut16 — 1부 v11(hold/p1-filing-supplement 56a97d3d) + main ab4356ca·d023cd9f 확인 반증 · 계획 안 렌즈 · 2026-10-06 밤
외부 API 0콜 · 운영 DB(stock_screener)·사본(sc_a)은 SELECT만 · 진짜 홈·앱(15406)·.env 안 건드림 · gradle은 --no-daemon(단발 데몬은 끝남)

DB: 쓴 것은 stock_screener_r16a_test 하나(createdb -T stock_screener_test) — batch_execution에 표지 행 둘(id 95313 MANUAL 유령 ·
    95314 GAP_FILL 유령, note 'rebut16 sim …')을 넣고 그대로 둠. stock_screener_r16b_* 는 내 것이 아니다(손 안 댐).
코드 사본: scratchpad/r16/v10(git archive 9eb16f28) · r16/v11(56a97d3d) · r16/pm(판 고정 변이) · r16/mrl(429·403 재시도 변이)

adr0/      gate4-asof.sql/.out — 0단계 4(N=2 리밸런싱 관문)를 지난 토요일 13:00으로 되감아 두 읽기로 평가(created_at 기준 · SELECT만)
           natural(「그 리밸런싱」 = 그 시점 마지막 SCHEDULER 행)은 09-19 · 09-26 · 10-03에 지난 명부로 통과(3/6) · target_date=토-1로 박으면 6/6 막음
ops-sim/   run-gapfill.sh/.out — 가짜 홈 · 가짜 앱 · r16a_test: K0 0 · K1 MANUAL 유령 0(고침 섬) · K2 bootstrap 꼬리 GAP_FILL 유령 1 →
           crons-done rc 1 「앱이 돌리는 배치가 1개다」 · K3 stop.sh --app rc 1(가짜 앱 생존) · K4 웹·bootstrap 스윕 술어 둘 다 0행(아무도 안 닫음)
           · K5 「이 JVM 기동 뒤 시작」 술어는 0
ops-sim2/  run-deploy.sh/.out/.out2 — main d023cd9f ops 사본(start.sh·gradlew 자리표시 · lsof shim은 진짜 앱이 8080을 잡아서):
           E0 대조: 빌드→판정→종료→스테이징→기동 · E2 빌드 중 커밋: commit=unknown captured=build(손 FR 0-1이 멈춤) ·
           E1 종료 45초 초과: rc 1 · 배포 폴더 OLD 그대로 · 옛 JVM 생존
probe/     R16ProbeTest(v10·v11 같은 글자) · probe-v10/v11.txt · byteeq-v10/v11.tsv(실물 companyfacts 61개 · 61/61 같음) ·
           R16SpacingTest/spacing-v11.txt(운영 리미터 분당 300: 시도 간격 204·209ms · 414ms 안에 세 번)
pin-mutants/ pm_step.py · R16Evasions.java · R16PmBehaviorTest.java · pm-a/b/c.log · pm-a/c-behavior.txt
           a) isLinkbase 본문을 면제 형 SecTickerCikCache로 옮김 → 소스 해시 붉음 · b) 판 6 + 해시 덧붙임 → 7 ok/1 skip ·
           c) 옮긴 도우미만 바꿈 + 범위 형 구현 네 꼴 → 7 ok/1 skip인데 pickInstance가 UnreadableFiling → OK로 바뀜
mutants-code/ mrl.log(companyfacts·색인 둘 다 403·429 재시도: 판 소스 해시 + 「403을 AUTH로 접지 않는다」 붉음) ·
           mrl429.log(companyfacts만 429 재시도: datasource.us 235/0 초록 — 429 미재시도는 시험이 안 잠금)
