create temp table e(ticker text, d date);
\copy e from '/private/tmp/claude-501/-Users-na1011-dev-stock-screener/542f2076-f436-4591-9e74-12b004e1f464/scratchpad/b1/succ.tsv'
select e.ticker, e.d,
 (select p.close from price_daily p join stock s on s.id=p.stock_id where s.ticker=e.ticker and s.market_code='US' and p.trade_date < e.d - 3 order by p.trade_date desc limit 1) c_before,
 (select p.close from price_daily p join stock s on s.id=p.stock_id where s.ticker=e.ticker and s.market_code='US' and p.trade_date > e.d + 3 order by p.trade_date limit 1) c_after,
 (select string_agg(ca.type||':'||ca.status||':'||ca.effective_date||':'||round(ca.ratio,4), ',') from corporate_action ca join stock s on s.id=ca.stock_id where s.ticker=e.ticker and s.market_code='US' and ca.effective_date between e.d - 40 and e.d + 40) ledger
from e order by 2;
