-- 0콜 · SELECT만. 2026-08-14(국내 몰린 날) 지표일의 PBR과 그날 Q2 2026을 낸 종목의 Q2/Q1 지배(없으면 총)자본.
-- 결정 6의 「보류는 보류된 종목만 뺀다」가 보류 안 된 종목의 PBR_PCTL을 지키는지 모의하는 재료.
with mem as (
  select m.stock_id, m.universe_code from universe_membership m
  where m.universe_code in ('KOSPI_TOP200','KOSDAQ_TOP150')
    and m.effective_from <= date '2026-08-14' and (m.effective_to is null or m.effective_to > date '2026-08-14')
), pbr as (
  select stock_id, value from indicator_value where indicator_code='PBR' and trade_date=date '2026-08-14'
), q2 as (
  select distinct on (stock_id) stock_id, published_at, coalesce(total_equity_controlling,total_equity) eq
  from financial_statement where fiscal_year=2026 and fiscal_period='Q2'
  order by stock_id, revision_no asc
), q1 as (
  select distinct on (stock_id) stock_id, coalesce(total_equity_controlling,total_equity) eq
  from financial_statement where fiscal_year=2026 and fiscal_period='Q1' and published_at < date '2026-08-14'
  order by stock_id, published_at desc, revision_no desc
)
select mem.universe_code, mem.stock_id, pbr.value pbr,
       (q2.published_at = date '2026-08-14') filed_0814,
       q2.eq q2_eq, q1.eq q1_eq
from mem left join pbr using(stock_id) left join q2 using(stock_id) left join q1 using(stock_id)
order by 1,2;
