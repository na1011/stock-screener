-- 위 a 창을 공시일 주로 쪼갠다 — 2부 배포가 밀릴 때 11-17 전 관찰 모집단이 얼마나 남는가(2025 Q3 · 국내 현 편입)
with kr as (
  select s.id from stock s
  join universe_membership m on m.stock_id=s.id and m.effective_to is null
  join universe u on u.code=m.universe_code and u.is_active
  where s.market_code in ('KOSPI','KOSDAQ') group by s.id
)
select f.published_at, count(distinct f.stock_id) stocks,
       sum(count(distinct f.stock_id)) over (order by f.published_at desc) cum_from_here_to_1113
from financial_statement f join kr on kr.id=f.stock_id
where f.fiscal_year=2025 and f.fiscal_period='Q3' and f.published_at between '2025-10-20' and '2025-11-13'
group by 1 order by 1;
