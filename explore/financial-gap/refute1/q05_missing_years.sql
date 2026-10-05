-- 현 편입 미국: 첫·마지막 회계연도 사이(2018~2025)에 라벨이 하나도 없는 회계연도(SPGI 2024 꼴 — 10-K 하나가 빠지면 그 해 분기까지 통째로 빠진다)
-- 그리고 FY 라벨은 있는데 Q1~Q3 중 빠진 해(10-Q 하나가 빠진 꼴 후보)
with cur as (select stock_id from universe_membership where universe_code='US_TOP1000' and effective_to is null),
y as (select fs.stock_id, fs.fiscal_year, array_agg(distinct fs.fiscal_period::text) ps
      from financial_statement fs join cur using(stock_id) group by 1,2),
b as (select stock_id, min(fiscal_year) lo, max(fiscal_year) hi from y group by 1),
g as (select b.stock_id, gs.fy from b, generate_series(greatest(b.lo,2018), least(b.hi,2025)) gs(fy))
select 'whole_year_missing' kind, count(*) n, count(distinct g.stock_id) stocks, string_agg(s.ticker||':'||g.fy, ' ' order by s.ticker) list
from g left join y on y.stock_id=g.stock_id and y.fiscal_year=g.fy join stock s on s.id=g.stock_id
where y.stock_id is null
union all
select 'quarter_missing_in_year', count(*), count(distinct y.stock_id), string_agg(s.ticker||':'||y.fiscal_year||':'||array_to_string(array(select unnest(array['Q1','Q2','Q3']) except select unnest(y.ps)),','), ' ' order by s.ticker)
from y join b using(stock_id) join stock s on s.id=y.stock_id
where y.fiscal_year between greatest(b.lo+1,2018) and least(b.hi-1,2025) and 'FY'=any(y.ps) and not (array['Q1','Q2','Q3'] <@ y.ps);
