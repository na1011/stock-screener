create temp table b(ticker text, d date);
\copy b from '/private/tmp/claude-501/-Users-na1011-dev-stock-screener/542f2076-f436-4591-9e74-12b004e1f464/scratchpad/b1/bnd.tsv'
select b.ticker, b.d, s.alpaca_anchor_symbol, s.alpaca_anchor_date,
 count(p.trade_date) filter (where p.trade_date < b.d) before_n,
 min(p.trade_date) filter (where p.trade_date >= b.d) first_after,
 (select volume from price_daily x where x.stock_id=s.id and x.trade_date >= b.d order by trade_date limit 1) first_after_vol,
 (select count(*) from corporate_action ca where ca.stock_id=s.id and ca.effective_date < b.d) ledger_before
from b join stock s on s.ticker=b.ticker and s.market_code='US' left join price_daily p on p.stock_id=s.id
group by 1,2,3,4,s.id order by 1;
