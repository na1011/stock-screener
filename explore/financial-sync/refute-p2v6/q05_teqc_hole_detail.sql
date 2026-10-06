with cur as (select distinct m.stock_id from universe_membership m join universe u on u.code=m.universe_code
             where m.effective_to is null and u.market_code in ('KOSPI','KOSDAQ')),
l as (select fs.stock_id, fs.fiscal_year, fs.fiscal_period, fs.period_end, fs.revision_no, fs.published_at,
             fs.total_equity_controlling teqc, fs.net_income_controlling nic, fs.total_equity, fs.created_at
      from financial_statement fs where fs.is_latest and fs.stock_id in (select stock_id from cur) and fs.fiscal_year>=2018),
o as (select l.*, lag(teqc) over w as prev_teqc, lead(teqc) over w as next_teqc,
             count(teqc) over (partition by stock_id) as n_teqc,
             max(period_end) over (partition by stock_id) as max_pe
      from l window w as (partition by stock_id order by period_end, fiscal_period)),
k as (select case when teqc is not null then 'present' when n_teqc=0 then 'none_any'
            when prev_teqc is not null and next_teqc is not null then 'sandwiched'
            when prev_teqc is not null and next_teqc is null and period_end=max_pe then 'latest_label'
            else 'other' end as k, o.* from o)
select k, s.ticker, s.name, fiscal_year, fiscal_period, revision_no, published_at, to_char(k.created_at,'MM-DD') created, total_equity, nic is null as nic_null
from k join stock s on s.id=k.stock_id where k in ('sandwiched','latest_label') order by k, s.ticker, period_end;
