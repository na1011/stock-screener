-- B(F6) 술어 근사: 열린 편입(OBSERVED)의 시작 > 그 종목 재무 마지막 쓰기(created_at 최대) — 편입 시작 주별
with m as (select stock_id, universe_code, effective_from from universe_membership
           where effective_to is null and origin='OBSERVED'),
fs as (select stock_id, max(created_at) last_write, count(*) n from financial_statement group by 1)
select m.universe_code, m.effective_from, count(*) joined,
  count(*) filter (where fs.n is not null and fs.last_write::date < m.effective_from) stale_nonzero,
  count(*) filter (where fs.n is null) zero_rows
from m left join fs using(stock_id)
where m.effective_from >= date '2026-09-04'
group by 1,2 order by 1,2;
