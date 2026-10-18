create temp table r(ticker text, d date, ratio numeric);
\copy r from '/private/tmp/claude-501/-Users-na1011-dev-stock-screener/542f2076-f436-4591-9e74-12b004e1f464/scratchpad/b1/rest.tsv'
select r.ticker, r.d, r.ratio,
  (select f.shares_outstanding from financial_statement f join stock s on s.id=f.stock_id where s.ticker=r.ticker and s.market_code='US' and f.shares_outstanding>0 and f.period_end < r.d order by f.period_end desc, f.published_at desc limit 1) sh_before,
  (select f.shares_outstanding from financial_statement f join stock s on s.id=f.stock_id where s.ticker=r.ticker and s.market_code='US' and f.shares_outstanding>0 and f.period_end >= r.d order by f.period_end, f.published_at desc limit 1) sh_after
from r order by r.ticker, r.d;
