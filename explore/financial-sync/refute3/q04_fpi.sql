-- 0콜 · 현 편입 미국 FPI(20-F/40-F 행)와 6-K 행 · 2025+ 분기 행 없는 FPI
with cur as (select distinct m.stock_id from universe_membership m join universe u on u.code=m.universe_code and u.is_active join stock s on s.id=m.stock_id where m.effective_to is null and s.market_code='US')
select count(distinct f.stock_id) fpi, count(distinct f.stock_id) filter (where exists (select 1 from financial_statement g where g.stock_id=f.stock_id and g.report_code like '6-K%')) with_6k
from financial_statement f join cur using(stock_id) where f.report_code ~ '^(20-F|40-F)';
