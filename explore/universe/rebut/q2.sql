\i base.sql
\pset format unaligned
\pset fieldsep '\t'
-- 23 tickers with shares but no CIK
SELECT lc.ticker, lc.name, sc.shares, sc.sic_code, (lc.id IN (SELECT stock_id FROM top)) in_top, (lc.id IN (SELECT stock_id FROM topwin)) in_win
FROM sc JOIN lc ON lc.id=sc.stock_id WHERE lc.cik IS NULL ORDER BY 1;
