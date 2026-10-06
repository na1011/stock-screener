rebut-p2v12 — 2부 계획 v12 계획 안 렌즈(기제·수·파급) · 2026-10-07 00시대 KST
코드: main 15f51e86(읽기만) · DB: 운영 stock_screener SELECT만(사본·시험 DB 안 만듦 · 시험 안 돌림)
DART 0콜(00:2x KST — 오늘 앱의 DART 사슬 발화(13:30)가 아직 안 끝나 실키 조건 불충족) · SEC 0콜 · 그 밖 외부 0콜

q01_v12_hole_predicate_asof.*   결정 4 「영구 구멍」 술어를 실제 정기 실행 시각(as-of created_at)에 적용 · 늦은/없는 봉 분류
                                 permanent_by_later(「D 뒤 봉이 이미 있음」) 0건 — 그 갈래 오분류는 실물 0
                                 KOSDAQ 052400: 09-07~09-10 지금 편입·거래 중(거래량 2.8~3.9만)인데 정기 ③ 넷이 실패 행 없이 못 받음 → 09-17 손 initial-load
                                 CRNX·APGE: 지금 is_active · US_LISTED 열림 · 보존 창 과거 편입 → priceGapRuns 모집단(=수집 대상)
q02_market_d_delay_replay.*     결정 4 재생: 시장 D가 완료되는 첫 정기 실행까지 지연(실행 수) · 「수집 대상 아님」은 관대한 근사(그 날 편입 아님)
                                 (첫 판은 bool_and가 NULL을 건너뛰어 틀림 — coalesce로 고친 판이 이 출력)
                                 US: 25회 중 지연 ≥1이 17일(+09-04 접두 · 10-05 미정) · 2회 지연 2일(09-01 · 09-03) · KOSDAQ 09-07 3회(시장 전체)
q03_manual_end_before_fire.*    결정 2(A) 「끝난 JVM이 5분간 산 것으로」 칸: 08-01~10-06 평일 발화 직전 5분에 끝난 MANUAL DART 잡 1(09-01 #234 governance-load 14:56 → 15:00)
notes.txt                       판정 메모
