with kr as (select distinct s.id, s.ticker from stock s join universe_membership m on m.stock_id=s.id and m.effective_to is null
  join universe u on u.code=m.universe_code and u.is_active where s.is_active and s.market_code in ('KOSPI','KOSDAQ')),
f as (select f.* from financial_statement f join kr on kr.id=f.stock_id where f.fiscal_period in ('FY','Q1','Q2','Q3'))
select col, count(*) stocks, string_agg(ticker, ' ' order by ticker) from (
  select kr.ticker, c.col,
    bool_or(c.v is not null) filter (where f.fiscal_year between 2018 and 2024) old_has,
    bool_or(c.v is not null) filter (where f.fiscal_year between 2025 and 2026) new_has,
    bool_or(q4.v is not null) q4_has
  from kr join f on f.stock_id=kr.id
  cross join lateral (values ('revenue', f.revenue), ('operating_income', f.operating_income), ('net_income', f.net_income),
     ('total_assets', f.total_assets), ('total_equity', f.total_equity), ('total_liabilities', f.total_liabilities),
     ('current_assets', f.current_assets), ('current_liabilities', f.current_liabilities), ('retained_earnings', f.retained_earnings)) c(col, v)
  left join lateral (select case c.col when 'revenue' then q.revenue when 'operating_income' then q.operating_income when 'net_income' then q.net_income
       when 'total_assets' then q.total_assets when 'total_equity' then q.total_equity when 'total_liabilities' then q.total_liabilities
       when 'current_assets' then q.current_assets when 'current_liabilities' then q.current_liabilities when 'retained_earnings' then q.retained_earnings end v
     from financial_statement q where q.stock_id=kr.id and q.fiscal_period='Q4' and q.fiscal_year between 2025 and 2026 and q.is_latest limit 1) q4 on true
  group by kr.ticker, c.col) t
where old_has and not coalesce(new_has,false)
group by col order by col
