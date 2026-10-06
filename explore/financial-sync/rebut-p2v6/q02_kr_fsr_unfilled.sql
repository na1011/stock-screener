-- 현 편입 국내 · is_latest · period_end >= 2024-01-01 · Q4 제외
-- FSR 칸(OCF·지배자본·지배순이익) 모두 NULL인 행 = FSR이 그 라벨을 안 채운 행
with mem as (
  select distinct m.stock_id from universe_membership m
  where m.universe_code in ('KOSPI_TOP200','KOSDAQ_TOP150') and m.effective_to is null),
r as (
  select f.*, s.ticker, s.name from financial_statement f join mem using (stock_id) join stock s on s.id=f.stock_id
  where f.is_latest and f.period_end >= date '2024-01-01' and f.fiscal_period <> 'Q4'),
cls as (
  select r.*, 
    (operating_cash_flow is null and total_equity_controlling is null and net_income_controlling is null) as fsr_none,
    exists (select 1 from financial_statement g where g.stock_id=r.stock_id and g.is_latest and g.total_equity_controlling is not null and g.total_equity_controlling < g.total_equity*0.999) as has_nci_elsewhere
  from r)
select fsr_none, has_nci_elsewhere, count(*) rows_, count(distinct stock_id) stocks,
  count(*) filter (where revision_no>0) revised
from cls group by 1,2 order by 1,2;
