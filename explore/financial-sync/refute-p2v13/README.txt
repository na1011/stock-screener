refute-p2v13 — 재무 동기 2부 계획 v13 「계획 밖」 렌즈 반증 산출물(2026-10-07 00시대)
외부 호출: 0(DART 0 · SEC 0 · Alpaca 0 · data.go.kr 0)
DB: 운영 stock_screener SELECT만(batch_execution · batch_execution_failure · price_daily · universe_membership · stock · indicator_trade_date · \d)
    · 사본 접속 0 · 시험 안 돌림(stock_screener_rp13b_test 안 만듦·안 씀) · gradle 안 씀 · 파일·DB 삭제 0 · .env 안 읽음 · main 작업 트리 파일 안 씀
코드 기준: main 52dd4f20(읽기만) · hold/p0-run-scope(JobFreshnessPolicy.lastSuccessAt 읽기) · 탐색 CIK-REP-PLAN.md(읽기)
띄운 프로세스: 없음(psql 단발)

notes.txt                               지적 A~K 근거 메모
q01_final_holes_runs                    지금 상태로 「시장 절반 이상 D 봉 · 이 종목만 없음」 구멍의 연속 길이 · 뒤 봉 유무(영구 구멍 6 = 전부 편출 꼬리)
q02_v13_failrow_replay                  (가) ③ 봉 부재 실패 행 재생 — 08-31 뒤 정기 daily-update 25회 중 22회에 새 행 · SUCCESS→PARTIAL 21회
q03_failrow_replay_after_cikrep         같은 재생에서 CIK 대표 계획(10-06 배포 · 10-10 첫 리밸런싱) 대상 11티커를 뺀 것 — 10/25 · 미국 5일(그중 꼬리 3일)
g01_dart_limiter_bypass.txt             장부 팩토리를 안 거치는 DART 리미터 직접 생성(src/test) · liveTest의 Spring 0 · 분당 한도 주석
g02_failure_row_contract.txt            실패 행 upsert · 마감 상태 규칙 · PARTIAL 알림 · 0부 시계 술어 · 무거운 잡 FAILED 갈래
