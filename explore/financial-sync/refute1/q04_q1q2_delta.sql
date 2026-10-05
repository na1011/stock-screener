-- 현 편입 미국 중 2026 Q1·Q2 주식수가 둘 다 있는 종목의 |Q2/Q1 − 1| 분포 (최신 판 · 기저 배수 반영)
with cur as (select distinct m.stock_id from universe_membership m where m.universe_code='US_TOP1000' and m.effective_to is null),
q as (select f.stock_id,
  max(f.shares_outstanding*coalesce(f.share_basis_multiple,1)) filter (where f.period_end between '2026-03-01' and '2026-04-30' and f.fiscal_period<>'FY') q1,
  max(f.shares_outstanding*coalesce(f.share_basis_multiple,1)) filter (where f.period_end between '2026-06-01' and '2026-07-31' and f.fiscal_period<>'FY') q2
 from financial_statement f join cur using(stock_id) where f.is_latest and f.shares_outstanding>0 group by 1)
select count(*), 
 round(percentile_cont(0.5) within group (order by abs(q2/q1-1))::numeric,4) p50,
 round(percentile_cont(0.9) within group (order by abs(q2/q1-1))::numeric,4) p90,
 round(percentile_cont(0.99) within group (order by abs(q2/q1-1))::numeric,4) p99,
 count(*) filter (where abs(q2/q1-1)>0.072) gt_exls
from q where q1 is not null and q2 is not null;
