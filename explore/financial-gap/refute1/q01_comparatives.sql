-- 현 편입 미국 중 6월 분기(기말 2026-05-15~2026-08-15) 10-Q 행이 있는 종목: 그 10-Q 접수번호가 다른 라벨에 만든 행(비교열 판)
with cur as (select stock_id from universe_membership where universe_code='US_TOP1000' and effective_to is null),
q2 as (
  select fs.stock_id, fs.report_code, fs.published_at
  from financial_statement fs join cur using(stock_id)
  where fs.fiscal_period not in ('FY') and fs.period_end between '2026-05-15' and '2026-08-15'
    and fs.report_code like '10-Q:%'
  group by 1,2,3
)
select count(distinct q2.stock_id) as stocks_with_q2,
       count(distinct o.stock_id) as stocks_q2_acc_on_other_labels,
       count(o.*) as other_label_rows,
       count(*) filter (where o.fiscal_period='FY') as fy_rows,
       count(*) filter (where o.fiscal_period<>'FY') as q_rows,
       count(*) filter (where o.is_latest) as latest_rows
from q2 left join financial_statement o
  on o.stock_id=q2.stock_id and o.report_code=q2.report_code and o.period_end < '2026-05-15';
