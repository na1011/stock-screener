with cur as (select stock_id from universe_membership where universe_code='US_TOP1000' and effective_to is null),
q2 as (
  select fs.stock_id, fs.report_code, fs.fiscal_year fy0, fs.fiscal_period fp0
  from financial_statement fs join cur using(stock_id)
  where fs.fiscal_period <> 'FY' and fs.period_end between '2026-05-15' and '2026-08-15' and fs.report_code like '10-Q:%'
)
select o.fiscal_period, (q2.fy0 - o.fiscal_year) as years_back, o.revision_no>0 as is_rev, count(*), count(distinct o.stock_id),
       string_agg(distinct s.ticker, ',' ) filter (where o.fiscal_period='FY') fy_tickers
from q2 join financial_statement o on o.stock_id=q2.stock_id and o.report_code=q2.report_code and o.period_end < '2026-05-15'
join stock s on s.id=o.stock_id
group by 1,2,3 order by 1,2,3;
