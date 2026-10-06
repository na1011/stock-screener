with mem as (
  select distinct s.id, s.market_code from stock s join universe_membership m on m.stock_id=s.id and m.effective_to is null
  join universe u on u.code=m.universe_code and u.is_active where s.is_active and s.market_code in ('US','KOSPI','KOSDAQ')
), pub as (
  select distinct f.stock_id, f.published_at d from financial_statement f join mem on mem.id=f.stock_id
  where f.published_at between '2025-01-01' and '2026-09-30'
), seq as (
  select stock_id, d, lead(d) over (partition by stock_id order by d) nd from pub
)
select case when mem.market_code='US' then 'US' else 'KR' end mkt,
       count(distinct seq.stock_id) stocks,
       count(distinct seq.stock_id) filter (where nd - d > 90) stocks_with_gap_gt90,
       sum(greatest(0, (nd - d) - 90)) uncovered_days,
       round(sum(greatest(0, (nd - d) - 90))::numeric / nullif(count(distinct seq.stock_id),0) / ('2026-09-30'::date - '2025-04-01'::date) * 100, 2) pct_entry_days_uncovered
from seq join mem on mem.id=seq.stock_id where nd is not null and d >= '2025-01-01'
group by 1
