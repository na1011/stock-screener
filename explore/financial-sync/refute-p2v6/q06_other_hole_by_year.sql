with cur as (select distinct m.stock_id from universe_membership m join universe u on u.code=m.universe_code
             where m.effective_to is null and u.market_code in ('KOSPI','KOSDAQ')),
l as (select fs.stock_id, fs.fiscal_year, fs.fiscal_period, fs.period_end, fs.total_equity_controlling teqc
      from financial_statement fs where fs.is_latest and fs.stock_id in (select stock_id from cur) and fs.fiscal_year>=2018),
o as (select l.*, count(teqc) over (partition by stock_id) n_teqc,
             min(period_end) filter (where teqc is not null) over (partition by stock_id) first_teqc,
             max(period_end) filter (where teqc is not null) over (partition by stock_id) last_teqc from l)
select case when teqc is not null then 'present' when n_teqc=0 then 'none_any'
            when period_end < first_teqc then 'before_first_value' when period_end > last_teqc then 'after_last_value' else 'inside' end k,
       fiscal_year, count(*) rows, count(distinct stock_id) stocks
from o where teqc is null group by 1,2 order by 1,2;
