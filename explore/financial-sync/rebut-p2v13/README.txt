rebut-p2v13 — 2부 계획 v13 계획 안 렌즈(기제·수·파급) · 2026-10-07 00시~01시대 KST
코드: main 52dd4f20(읽기만) · DB: 운영 stock_screener SELECT만(사본·시험 DB 안 만듦 · 시험·gradle 안 돌림)
외부 콜: DART 0(00:40 KST — 오늘 13:30 사슬 발화 전이라 실키 조건 불성립) · SEC 0 · Alpaca 0

s01_minute_lease_sim.py/.out   결정 1 「분 버킷 임대」를 TokenBucketRateLimiter(로컬 균등 간격 100ms=600/분)와 맞물려 모의
                                count 임대 합 1,000: 42초 창 최대 820~840콜 · 순간 1,200rpm (09-22 차단 = 765콜/42초 · ≈1,093rpm)
                                count 임대 합 600(여유 유지)도 순간 1,200rpm · rate 임대(간격=60000/몫) 합 600이면 순간 600rpm
q01_half_rule_replay.sql/.out  결정 4 「시장 절반 이상이 D 봉」 문턱을 정기 daily-update 25회 × 3시장 실행 끝 시각으로 재생
                                시장 D = PriceCalendar 꼴(그 시각까지 적재된 max) · 75칸 중 pct_have 최저 92.0(#500 · 이미 실패 행) · 0~50% 칸 0
                                휴장(미 09-07 · 국내 추석 09-24·25 · 10-05) 칸은 D가 앞 거래일로 물러나 문턱이 안 걸림
q02_missing_detail.sql/.out    q01의 「D 봉 없음」 85행을 펼침(편입 행 · 봉 도착 시각 · 그 실행의 실패 행)
                                → 41행이 「③ 실행 전에 편입이 닫힌 D 편입」(09-03 US 33+CRNX · 09-23 KR 4 · 052400 넷=사후 손 SQL)
                                → 나머지는 점 티커(TAP.A·WSO.B·MOG.B)·BTSGU 다음 날 도착 · CRNX·APGE 영영 없음 · #500 KOSDAQ 12(실패 행 있음)
notes.txt                      판정 메모
