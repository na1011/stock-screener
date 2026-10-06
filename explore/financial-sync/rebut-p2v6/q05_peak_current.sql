-- 국내 공시 몰린 날: 현 편입(350)만 · published_at 기준 종목 수 (is_latest 무관 · 그날 공시일 행이 있는 종목)
with mem as (select distinct stock_id from universe_membership where universe_code in ('KOSPI_TOP200','KOSDAQ_TOP150') and effective_to is null)
select f.published_at, extract(isodow from f.published_at) dow, count(distinct f.stock_id) stocks
from financial_statement f join mem using(stock_id)
where f.published_at >= date '2025-01-01'
group by 1,2 having count(distinct f.stock_id) >= 30 order by 1;
