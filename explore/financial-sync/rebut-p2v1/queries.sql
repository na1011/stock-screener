-- q1 미국 현 편입(us_members.tsv)
select s.id, s.ticker from universe_membership m join stock s on s.id=m.stock_id where m.universe_code='US_TOP1000' and m.effective_to is null and s.is_active order by 2;
-- q2 RF1 미국 대조용 DB 행(us_db_1114.tsv · us_db_1030.tsv) → rf1_us.py
select distinct fs.stock_id, fs.published_at, fs.report_code, fs.fiscal_year, fs.fiscal_period from financial_statement fs join universe_membership m on m.stock_id=fs.stock_id and m.universe_code='US_TOP1000' and m.effective_to is null where fs.published_at = '2025-10-30';
-- q3 SEC 휴일 뒤 공시 종목(2025 대리) — 70
with us as (select s.id from universe_membership m join stock s on s.id=m.stock_id where m.universe_code='US_TOP1000' and m.effective_to is null and s.is_active),
p as (select distinct fs.stock_id, fs.published_at from financial_statement fs join us on us.id=fs.stock_id where fs.published_at between '2025-10-01' and '2025-12-31')
select count(distinct a.stock_id) from p a where a.published_at between '2025-11-12' and '2025-11-25' and not exists (select 1 from p b where b.stock_id=a.stock_id and b.published_at between '2025-10-30' and '2025-11-10');
-- q4 시장 달력(NYSE)이 SEC 휴일에 거래일 · 성금요일 비거래 · 최신 2026-08-28
select exchange_code, session_date from market_calendar where session_date in ('2025-10-13','2025-11-11','2025-12-24','2025-12-26','2026-04-03');
select exchange_code, max(session_date) from market_calendar group by 1;
-- q5 성금요일 공시 종목(2024-03-29 5 · 2025-04-18 3)
-- q6 국내 2025 Q3 공시일 분포(256 @11-14 · 17종목이 11-18 이후 날짜 = 뒤 정정이 원본 날짜를 대체)
with kr as (select s.id from universe_membership m join stock s on s.id=m.stock_id where m.universe_code in ('KOSPI_TOP200','KOSDAQ_TOP150') and m.effective_to is null and s.is_active)
select fs.published_at, count(distinct fs.stock_id), count(*) filter (where fs.revision_no>0) from financial_statement fs join kr on kr.id=fs.stock_id where fs.fiscal_year=2025 and fs.fiscal_period='Q3' group by 1 order by 1;
-- q7 국내 최신 분기 지배주주 자본 < 0.9 × 총자본: 86/332 (<0.5: 14)
-- q8 국내 FR 실행 이력(#30 08-10 350 · #645/#646 09-21 200+150 · #658 09-22 150) · financial_column_absence 국내 0행(미국 1,221종목)
select s.market_code, count(distinct a.stock_id), max(a.observed_at) from financial_column_absence a join stock s on s.id=a.stock_id group by 1;
