rebut-p2v15 — 2부 계획 v15 두 렌즈 반증(계획 안 · 계획 밖) · 2026-10-07 01~02시 KST
코드: main 3a02d857 읽기만 · DB: 운영 stock_screener SELECT만(사본·시험 DB 안 만듦 · stock_screener_rp15a_test 안 만듦 · 시험·gradle 안 돌림)
외부 콜: DART 0 · SEC 0 · Alpaca 0 · data.go.kr 0(.env를 안 읽어 실키 갈래는 재지 않음)

runs60.csv                       08-08 뒤 batch_execution 731행(id·잡·주체·상태·시작·끝 KST) — 아래 재생의 입력
q01_next_regular_rule.sql/.out   결정 1 재생: 두 시장 · 모집단 = D 편입(닫힌 편입 포함) · 늦은 봉이 「다음 정기 ③ 실행 끝」까지 왔나
s04_quiet_window_replay.py/.out  결정 4 재생: 조용한 창 10분 · 23:00 상한 · 평일 42일 · 사슬 시작 시각과 사슬 도중 시작한 손 실행
s04b_reverse_collisions.out      반대 방향: SCHEDULER 실행이 살아 있는 동안 시작한 MANUAL·ADMIN(5건 · 2일)
s04c_window_sweep.py/.out        조용한 창 10·30·60분 비교
q02_gaps_over_10m.out            손 실행 연속 틈 10~60분 65쌍(FR→governance-derive 13~15분 ×4 · 09-15)
s02v15_lease_wait.out            결정 3: 손 DART 잡 길이(중앙 1.8분 · 최대 55분)와 사슬 DART 실행과의 겹침(1일)
s02v15_halfopen.py/.out          v14 s02를 반열린 창으로(부동소수 누적 때문에 여전히 601 — 측정 도구 결함)
s02v15_int.py/.out               정수 ms 모의: 축소가 이미 잡아 둔 콜에 적용되면 600 · 안 되면 601
g_3a02.diff                      progress 커밋 3a02d857 원문(회귀 확인용)
