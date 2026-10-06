refute12 — 1부 v7 · 첫 손 FR 절차 · 합치는 절차 — 계획 밖 렌즈 (2026-10-06 13:45~ · 0콜 · DB SELECT만)

q01_cron_shift.txt        앱 JVM 지연(벽시계 − jcmd VM.uptime) 6,889초와 오늘 크론의 예측 발화 시각(예측 — 15:25에 확인 가능)
q02_battery_sleeps.txt    pmset 로그의 배터리 수면(18:55:52 Clamshell Sleep · Batt) — caffeinate -s는 AC에서만 막는다(ops/start.sh 주석)
                          근거: Spring 7.0.8 ReschedulingRunnable.schedule()이 Duration.between(clock.instant(), 예정시각)을
                          NANOSECONDS로 ScheduledExecutorService.schedule에 넘긴다(javap) — 단조 시계라 잠든 만큼 밀린다
                          측정: batch_execution 10-06 행 0(13:49) · stdout.log 마지막 줄 12:48
sim.git                   --shared bare 클론(원 저장소 ref 무변경). M1 = main 544f82e0 + hold/p0 f6a2ecb8 (깨끗) ·
                          M2 = M1 + hold/p1 f9a1d4c7 → docs/reload-gate.md 하나 충돌
P.txt · M1side.txt        merge-base(M1,p1)=4d0d2a47 기준 양쪽 변경 경로 — 교집합: docs/reload-gate.md · IndicatorBackfillParallelTest.java
reload-gate.conflicted.md · reload-gate.resolved-by-row-rule.md · reload-gate.p1.md
                          행 규칙((가)=p1 · (나)·관측 평면=main)으로 푼 결과가 HEAD^2(p1)와 4행 다르다 → 「P 안 = HEAD^2」 단언과 모순
ga-base.txt · ga-main.txt main은 base 뒤로 (가) 행을 안 바꿨다(동일) — 행 규칙은 지금 선다
