rebut-p2v16 — 2부 계획 v16 두 렌즈 반증 · 2026-10-07 01~02시 KST
코드: main 2253a5e3 읽기만 · 보류 브랜치 p0·p1은 diff 이름만
DB: 운영 stock_screener SELECT만 · 사본·시험 DB 안 만듦(stock_screener_rp16a_test 안 만듦 · 시험·gradle 안 돌림)
외부 콜: 0(.env 안 읽음 → data.go.kr 공개 시각은 재지 않음)

runs60.csv                          60일 batch_execution 731행(KST)
q01_overlap_pairs.sql/.out          60일 실행 겹침 쌍(트리거 짝별)
q02_daily_update_market_failures.*  daily-update 실행별·시장별 실패(시장 통째 실패 0 · #500 국내 TRANSIENT 12)
s01_lock_replay.py/.out             v16 잠금 재생(평일 42일): 대기 날·분 · 거절될 손 실행 17건 7일
s02_poll_starvation.py/.out         폴링 5초/60초로 대기 재생 · 앞지름(09-16 #521 — 그날 앱 안 뜸 · 가상)
pmset_sleepwake.raw · s03_sleep_spans.out   기계 수면(09-30~10-07): 5분 이상 22회
s04_runs_across_sleep.out           수면을 걸친 실행 0(그 6일 창)
s05_saturday.out                    토 19~23시 실행(크론 꼬리와 손 실행 간격)
s06_hand_gaps.out                   손 실행 연속 틈(< 60초 287 · 평일 13~23시 99/18일) · 09-16 SCHEDULER 0
