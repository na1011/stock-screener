-- 국내 현 편입 중 DB 재무에 지배주주 자본이 한 번도 없는(= OFS 전용으로 보이는) 종목과 최신 라벨 — 1차 수집 4종목(033100·131970·323410·476830) 제외
with cur as (
  select distinct s.id, s.ticker, s.name from stock s
  join universe_membership m on m.stock_id = s.id join universe u on u.code = m.universe_code and u.is_active
  where s.market_code in ('KOSPI','KOSDAQ') and s.is_active and m.effective_to is null),
ofs as (
  select c.* from cur c
  where exists (select 1 from financial_statement f where f.stock_id = c.id and f.operating_cash_flow is not null)
    and not exists (select 1 from financial_statement f where f.stock_id = c.id and f.total_equity_controlling is not null)
    and c.ticker not in ('033100','131970','323410','476830')),
lab as (
  select o.ticker, o.name, f.fiscal_year, f.report_code, max(f.published_at) pub,
         row_number() over (partition by o.ticker order by max(f.published_at) desc, f.fiscal_year desc) rn
  from ofs o join financial_statement f on f.stock_id = o.id where f.report_code is not null
  group by 1,2,3,4)
select ticker, name, fiscal_year, report_code, pub from lab where rn = 1 order by md5(ticker || '20261006');
