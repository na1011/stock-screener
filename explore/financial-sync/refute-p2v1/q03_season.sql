-- 현 편입의 2025년 같은 철 라벨 첫 공시일(라벨마다 최소 published_at ∈ 2025-09-15~2025-12-15) — 시장별 일자 분포
with cur as (
  select distinct m.stock_id, case when s.market_code='US' then 'US' else 'KR' end mk
  from universe_membership m join universe u on u.code=m.universe_code and u.is_active
  join stock s on s.id=m.stock_id where m.effective_to is null and s.is_active),
lab as (
  select c.mk, f.stock_id, f.fiscal_year, f.fiscal_period, min(f.published_at) p
  from financial_statement f join cur c on c.stock_id=f.stock_id
  group by 1,2,3,4)
select mk, p, to_char(p,'Dy') dy, count(*) labels, count(distinct stock_id) stocks,
       sum(count(*)) over (partition by mk order by p) cum
from lab where p between '2025-09-15' and '2025-12-15' group by mk, p order by mk, p
