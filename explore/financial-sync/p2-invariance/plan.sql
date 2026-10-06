-- 2부 불변성 관찰 규칙 v2(PLAN 「# 2부 계획 v7」) — 표본 20종목 × 최근 보고서 셋. 고정 시드 md5(ticker || '20261006').
with cur as (
  select distinct s.id, s.ticker, s.accounting_month, s.industry_group_key from stock s
  join universe_membership m on m.stock_id = s.id join universe u on u.code = m.universe_code and u.is_active
  where s.market_code in ('KOSPI','KOSDAQ') and s.is_active and m.effective_to is null),
tag as (
  select c.*, md5(c.ticker || '20261006') seed,
    c.accounting_month = 3 as march,
    exists (select 1 from financial_statement f where f.stock_id = c.id and f.operating_cash_flow is not null)
      and not exists (select 1 from financial_statement f where f.stock_id = c.id and f.total_equity_controlling is not null) as ofs,
    c.industry_group_key ~ '^KSIC:6[456]' as fin,
    exists (select 1 from financial_statement f where f.stock_id = c.id and f.revision_no > 0) as revised
  from cur c),
pick as (
  (select id, ticker, 'march' stratum from tag where march order by seed limit 1)
  union all (select id, ticker, 'ofs' from tag where ofs and not march order by seed limit 4)
  union all (select id, ticker, 'finance' from tag where fin and not march and not ofs order by seed limit 4)
  union all (select id, ticker, 'revised' from tag where revised and not fin and not march and not ofs order by seed limit 4)
  union all (select id, ticker, 'general' from tag where not revised and not fin and not march and not ofs and accounting_month = 12 order by seed limit 7)),
labels as (
  select p.ticker, p.stratum, f.fiscal_year, f.report_code, max(f.published_at) pub,
         row_number() over (partition by p.ticker order by max(f.published_at) desc, f.fiscal_year desc, f.report_code) rn
  from pick p join financial_statement f on f.stock_id = p.id
  where f.published_at <= current_date and f.report_code is not null
  group by p.ticker, p.stratum, f.fiscal_year, f.report_code)
select ticker, fiscal_year, report_code, stratum, pub from labels where rn <= 3 order by stratum, ticker, rn;
