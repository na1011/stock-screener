-- (a) 상태(FY 행의 FSR 칸 NULL)에서 normalizedTaxRate가 null이 아니라 4년 중앙값을 내는가 — 0콜 모의
-- 현 편입 국내 · is_latest FY 최근 5행 · effectiveTaxRateOf/clamp를 SQL로 옮김(부호 보정 포함)
with mem as (select distinct stock_id from universe_membership where universe_code in ('KOSPI_TOP200','KOSDAQ_TOP150') and effective_to is null),
fy as (
  select f.stock_id, f.fiscal_year, f.income_tax_expense tax0, f.net_income ni, f.profit_before_tax pbt,
         row_number() over (partition by f.stock_id order by f.period_end desc) rn
  from financial_statement f join mem using(stock_id)
  where f.is_latest and f.fiscal_period='FY'),
r as (
  select *, case when tax0 < 0 and ni is not null and pbt is not null and pbt - ni > 0 then -tax0 else tax0 end tax from fy where rn <= 5),
r2 as (
  select *, coalesce(pbt, ni + tax) pretax from r),
rate as (
  select stock_id, rn, case when tax is null then null when pretax is null or pretax <= 0 then null
          else least(greatest(tax/pretax, 0), 1) end ratio from r2),
agg as (
  select stock_id,
    percentile_cont(0.5) within group (order by ratio) filter (where ratio is not null) med5,
    percentile_cont(0.5) within group (order by ratio) filter (where ratio is not null and rn > 1) med4_latest_null,
    count(*) filter (where rn=1 and ratio is not null) latest_has
  from rate group by stock_id)
select count(*) stocks,
  count(*) filter (where latest_has=1) latest_rate_defined,
  count(*) filter (where latest_has=1 and med4_latest_null is not null) value_not_null_when_latest_nulled,
  count(*) filter (where latest_has=1 and med4_latest_null is not null and abs(med4_latest_null - med5) > 0.0001) differs,
  round(max(abs(med4_latest_null - med5)) filter (where latest_has=1)::numeric * 100, 2) max_diff_pp,
  round((percentile_cont(0.9) within group (order by abs(med4_latest_null - med5)) filter (where latest_has=1))::numeric * 100, 2) p90_diff_pp
from agg;
