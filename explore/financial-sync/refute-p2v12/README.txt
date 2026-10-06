refute-p2v12 — 재무 동기 2부 계획 v12 「계획 밖」 렌즈 반증 산출물(2026-10-07)
외부 호출: 0(DART 0 · SEC 0 · Alpaca 0)
DB: 운영 stock_screener SELECT만(batch_execution · pg_constraint · \d) · 사본 접속 0 · 시험 안 돌림(stock_screener_rp12b_test 안 만듦·안 씀)
    · gradle 안 씀 · 파일·DB 삭제 0 · .env 안 읽음 · main 작업 트리 파일 안 씀
코드 기준: main 15f51e86(읽기만) · spring-boot-jdbc 4.1.0 jar(gradle 캐시 · 클래스 두 개를 scratchpad/rp12/jdbc에 풀어 javap)
띄운 프로세스: 없음(psql·javap 단발) · 그 시각 남의 적재 JVM 43822·44077이 돌고 있었다(훅 알림 · 안 건드림)

notes.txt                         지적 A~H 근거 메모
q01_manual_dart_jobs_by_daytype   08-10 뒤 DART 계열 잡 시작의 요일(주말/평일)·시각대(13시 전/뒤) 분포 — 주말 MANUAL governance-load 16회 등
q02_daily_update_partial          08-31 뒤 정기 일일 갱신 26 중 실패 남긴 것 1(#500) — 재시도 ⑧ 빈도의 상한
c01_datasource_backoff.txt        DataSourceConfiguration$Hikari @ConditionalOnMissingBean(DataSource) — 장부 DataSource를 빈으로 내면 주 DataSource가 물러난다
g01_status_consumers.txt          BatchStatus·PARTIAL_SUCCESS를 읽는 파일 33(백엔드 · ops · 프런트)
g02_status_contract.txt           ck_batch_status(다섯 값) · BatchExecutionReader:246 valueOf · 프런트 BATCH_STATUSES
d01_dart_live_paths.txt           DART liveTest 23파일 중 리미터 없이 DartProbeSupport만 쓰는 것 19
