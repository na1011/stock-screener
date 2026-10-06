-- 현 편입 국내(KOSPI_TOP200 ∪ KOSDAQ_TOP150) · is_latest · period_end >= 2024-01-01 · Q4 제외(유도) 행에서 폴백 갈래
with mem as (
  select distinct m.stock_id from universe_membership m
  where m.universe_code in ('KOSPI_TOP200','KOSDAQ_TOP150') and m.effective_to is null),
r as (
  select f.*, s.ticker, s.name from financial_statement f join mem using (stock_id) join stock s on s.id=f.stock_id
  where f.is_latest and f.period_end >= date '2024-01-01')
select fiscal_period in ('FY') as fy,
  count(*) rows_,
  count(*) filter (where net_income_controlling is null) nic_null,
  count(*) filter (where total_equity_controlling is null) tec_null,
  count(*) filter (where net_income_controlling is null and total_equity_controlling is null) both_null,
  count(*) filter (where net_income_controlling is null and total_equity_controlling is not null and total_equity_controlling < total_equity*0.9) nic_null_nci_real,
  count(*) filter (where total_equity_controlling is null and net_income_controlling is not null) tec_null_only
from r group by 1 order by 1;
