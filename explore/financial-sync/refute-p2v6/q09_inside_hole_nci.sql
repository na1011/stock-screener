-- 값이 있는 라벨 사이에 낀 지배주주 자본 빈칸(현 편입 · FY2018~) — 이웃 라벨의 지배/총 비율로 폴백이 값을 얼마나 바꾸나.
with cur as (select distinct m.stock_id from universe_membership m join universe u on u.code=m.universe_code
             where m.effective_to is null and u.market_code in ('KOSPI','KOSDAQ')),
l as (select fs.stock_id, fs.fiscal_year, fs.fiscal_period, fs.period_end, fs.total_equity te, fs.total_equity_controlling teqc,
             fs.cost_of_sales is not null or fs.inventories is not null or fs.cash_and_equivalents is not null as fsr_wrote
      from financial_statement fs where fs.is_latest and fs.stock_id in (select stock_id from cur) and fs.fiscal_year>=2018 and fs.fiscal_period<>'Q4'),
o as (select l.*, min(period_end) filter (where teqc is not null) over (partition by stock_id) f, max(period_end) filter (where teqc is not null) over (partition by stock_id) t from l),
h as (select o.*, (select p.teqc/p.te from l p where p.stock_id=o.stock_id and p.teqc is not null and p.te>0 and p.period_end<o.period_end order by p.period_end desc limit 1) prev_ratio,
                  (select p.teqc/p.te from l p where p.stock_id=o.stock_id and p.teqc is not null and p.te>0 and p.period_end>o.period_end order by p.period_end limit 1) next_ratio
      from o where teqc is null and period_end between f and t)
select s.ticker, s.name, h.fiscal_year||h.fiscal_period lbl, fsr_wrote, round(prev_ratio,3) prev_ratio, round(next_ratio,3) next_ratio
from h join stock s on s.id=h.stock_id order by (least(coalesce(prev_ratio,1),coalesce(next_ratio,1))), s.ticker, h.period_end;
