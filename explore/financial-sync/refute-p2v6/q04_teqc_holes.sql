-- 국내 현 편입 최신 행 중 지배주주 자본이 빈 행을 「그 종목에 다른 라벨의 값이 있나」로 가른다.
-- 앞뒤 라벨이 다 값을 가졌는데 이 라벨만 비면 FSR이 그 라벨을 못 채운 꼴(표지 없이 폴백되는 과거 행 후보).
with cur as (select distinct m.stock_id from universe_membership m join universe u on u.code=m.universe_code
             where m.effective_to is null and u.market_code in ('KOSPI','KOSDAQ')),
l as (select fs.stock_id, fs.fiscal_year, fs.fiscal_period, fs.period_end, fs.revision_no, fs.published_at,
             fs.total_equity_controlling teqc, fs.net_income_controlling nic, fs.total_equity
      from financial_statement fs where fs.is_latest and fs.stock_id in (select stock_id from cur) and fs.fiscal_year>=2018),
o as (select l.*, lag(teqc) over w as prev_teqc, lead(teqc) over w as next_teqc,
             count(teqc) over (partition by stock_id) as n_teqc, count(*) over (partition by stock_id) as n_rows
      from l window w as (partition by stock_id order by period_end, fiscal_period))
select case when teqc is not null then 'teqc_present'
            when n_teqc=0 then 'stock_has_no_teqc_any_label'
            when prev_teqc is not null and next_teqc is not null then 'sandwiched_hole'
            when prev_teqc is not null and next_teqc is null and period_end=(select max(period_end) from l l2 where l2.stock_id=o.stock_id) then 'latest_label_hole'
            else 'other_hole' end as k,
       count(*) rows, count(distinct stock_id) stocks
from o group by 1 order by 1;
