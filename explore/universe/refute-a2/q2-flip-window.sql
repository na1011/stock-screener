SET statement_timeout='60s';
-- ET·DDS가 10-03 21:14 리밸런싱 스냅숏에 있었다(열린 OBSERVED 행) → Alpaca 비활성 전환은 그 뒤
select id, job_name, target_date, status, started_at from batch_execution where job_name='universe-rebalance' and started_at > '2026-09-28';
select s.ticker, m.effective_from, m.effective_to, m.origin from universe_membership m join stock s on s.id=m.stock_id
 where m.universe_code='US_TOP1000' and s.market_code='US' and s.ticker in ('ET','DDS') order by 1,2;
select s.ticker, p.trade_date, p.created_at from price_daily p join stock s on s.id=p.stock_id
 where s.market_code='US' and s.ticker in ('ET','DDS') and p.trade_date >= '2026-10-01' order by 1,2;
