-- 「FSR 미반영」과 같은 꼴의 기존 행: 국내 최신 행이 개정본(rev>0)인데 FSR 전용 칸(지배주주 자본·지배주주 순이익)이 비고,
-- 같은 라벨의 앞 판에는 그 칸이 있었다 → v6 결정 3a의 표지가 없으니 계산기가 연결 기저로 폴백한다.
with kr as (select id, ticker, name from stock where market_code in ('KOSPI','KOSDAQ')),
cur as (select distinct stock_id from universe_membership where effective_to is null and universe_code in (select code from universe where market_code in ('KOSPI','KOSDAQ'))),
l as (select fs.* from financial_statement fs join kr on kr.id=fs.stock_id where fs.is_latest),
prev as (
  select l.stock_id, l.fiscal_year, l.fiscal_period, l.revision_no, l.published_at,
         l.total_equity_controlling is null as teqc_null, l.net_income_controlling is null as nic_null,
         l.operating_cash_flow is null as ocf_null,
         exists (select 1 from financial_statement p where p.stock_id=l.stock_id and p.fiscal_year=l.fiscal_year and p.fiscal_period=l.fiscal_period and p.revision_no<l.revision_no and p.total_equity_controlling is not null) as teqc_before,
         exists (select 1 from financial_statement p where p.stock_id=l.stock_id and p.fiscal_year=l.fiscal_year and p.fiscal_period=l.fiscal_period and p.revision_no<l.revision_no and p.net_income_controlling is not null) as nic_before,
         l.stock_id in (select stock_id from cur) as is_current
  from l where l.revision_no>0)
select 'latest_rev>0_rows' k, count(*) , count(*) filter (where is_current) from prev
union all select 'teqc_null_but_before', count(*) filter (where teqc_null and teqc_before), count(*) filter (where teqc_null and teqc_before and is_current) from prev
union all select 'nic_null_but_before', count(*) filter (where nic_null and nic_before), count(*) filter (where nic_null and nic_before and is_current) from prev
union all select 'either', count(*) filter (where (teqc_null and teqc_before) or (nic_null and nic_before)), count(*) filter (where ((teqc_null and teqc_before) or (nic_null and nic_before)) and is_current) from prev
union all select 'stocks_either', count(distinct stock_id) filter (where (teqc_null and teqc_before) or (nic_null and nic_before)), count(distinct stock_id) filter (where ((teqc_null and teqc_before) or (nic_null and nic_before)) and is_current) from prev;
