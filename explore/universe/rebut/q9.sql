\i base.sql
\pset format unaligned
\pset fieldsep '\t'
WITH cap AS (
 SELECT s.ticker, sc.shares * (SELECT p.close FROM price_daily p WHERE p.stock_id=s.id AND p.trade_date='2026-10-02') cap
 FROM top JOIN stock s ON s.id=top.stock_id JOIN sc ON sc.stock_id=s.id)
SELECT count(*) n, count(cap) with_cap, round(min(cap)/1e9,3) min_b, round(percentile_disc(0.01) WITHIN GROUP (ORDER BY cap)/1e9,3) p1_b,
 round(percentile_disc(0.02) WITHIN GROUP (ORDER BY cap)/1e9,3) p2_b FROM cap;
WITH cap AS (
 SELECT s.ticker, sc.shares * (SELECT p.close FROM price_daily p WHERE p.stock_id=s.id AND p.trade_date='2026-10-02') cap
 FROM top JOIN stock s ON s.id=top.stock_id JOIN sc ON sc.stock_id=s.id)
SELECT ticker, round(cap/1e9,3) FROM cap ORDER BY cap NULLS FIRST LIMIT 12;
