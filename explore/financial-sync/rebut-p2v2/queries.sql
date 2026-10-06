-- 2부 v2 계획 안 반증 — 운영 DB SELECT만(q.sh로 실행) · 2026-10-06 09시
-- a1 국내 현 편입 결산월: 12월 349 · 3월 1(001720 신영증권) · 결산월 NULL 국내 종목 2(056080·399720 — 과거 편입 · 재편입 잦음)
select m.universe_code, s.accounting_month, count(*) from universe_membership m join stock s on s.id=m.stock_id where m.universe_code in ('KOSPI_TOP200','KOSDAQ_TOP150') and m.effective_to is null and s.is_active group by 1,2;
select market_code, coalesce(accounting_month::text,'NULL'), count(*) from stock where market_code in ('KOSPI','KOSDAQ') group by 1,2;
-- a2 90일 근거 실행: #645·#646·#658(FR) · #647·#648(FSR) 실패 0 · #683 미국 FR 1,000 실패 0
select id, job_name, triggered_by, status, started_at, target_count, success_count, failure_count from batch_execution where id in (645,646,647,648,658,683);
-- a3 그 실행 뒤 편입: 국내 09-25 4 · 10-02 4 / 미국 10-02 13
select m.universe_code, m.effective_from, count(*) from universe_membership m join stock s on s.id=m.stock_id where m.effective_to is null and s.is_active group by 1,2 order by 2 desc;
-- a4 90일 창에 공시 0인 날(종목-일): 현 편입 2025-06~2026-09 연속 공시 간격 > 90일 — 국내 347/350 종목 · 14,284 종목-일 / 미국 984/1,000 · 22,688
--   (with mem ... lead(published_at) ... sum(greatest(0, gap-90)))
-- a5 미국 후보(US_LISTED − TOP1000) 7,495 · 재무 있음 4,119 · 마지막 created_at 중앙 2026-08-30 · 최신 공시 120일 넘음 769
-- a6 국내 2026 공시 날짜별 종목 수(짝 24콜 가정 콜) — 03-18 102 · 05-15 256 · 08-14 287 …
-- a7 국내 현 편입 2026 Q2 최신 행에 FSR 전용 칸(영업CF·지배자본·capex·매출원가) 전부 NULL: 100790 · 279570 · 323410 · 476060 · 476830 (원장 013인지는 재지 않음)
-- a8 일일 갱신 소요(분): 09-01 13.6 → 10-05 23.9
select to_char(started_at,'MM-DD'), round(extract(epoch from finished_at-started_at)/60.0,1) from batch_execution where job_name='daily-update' and triggered_by='SCHEDULER' and started_at > '2026-08-25' order by started_at;
