-- 지배주주 순이익이 빈 국내 라벨 중 같은 행에 지배주주 자본이 있고 비지배가 실재(지배 < 총 × 0.99)인 것 — 연결(CFS)을 소비했는데 순이익만 폴백.
with cur as (select distinct m.stock_id from universe_membership m join universe u on u.code=m.universe_code
             where m.effective_to is null and u.market_code in ('KOSPI','KOSDAQ'))
select fs.fiscal_period, count(*) rows, count(distinct fs.stock_id) stocks
from financial_statement fs where fs.is_latest and fs.stock_id in (select stock_id from cur) and fs.fiscal_year>=2018
  and fs.net_income_controlling is null and fs.net_income is not null and fs.total_equity_controlling is not null
  and fs.total_equity_controlling < fs.total_equity*0.99
group by 1 order by 1;
