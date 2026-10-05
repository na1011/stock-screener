\i base.sql
\pset format unaligned
\pset fieldsep '\t'
CREATE TEMP TABLE cand AS
  SELECT lc.*, sc.shares FROM lc JOIN sc ON sc.stock_id=lc.id
  WHERE NOT sc.cur_mis AND coalesce(sc.sic_code,'') NOT IN ('6798','6770')
    AND upper(lc.name) !~ '\m(PREFERRED|PFD|WARRANTS?|RIGHTS?|DEPOSITARY|NOTES?|DEBENTURES?|BONDS?|ETFS?|FUNDS?)\M';
SELECT count(*) cands, count(DISTINCT cik) ciks, count(*) FILTER (WHERE cik IS NULL) nocik FROM cand;
SELECT c.cik, c.ticker, c.name, round(c.shares) shares,
  (SELECT count(DISTINCT x.shares) FROM cand x WHERE x.cik=c.cik) nshares,
  (c.id IN (SELECT stock_id FROM top)) in_top, (c.id IN (SELECT stock_id FROM topwin)) in_win,
  (SELECT p.close FROM price_daily p WHERE p.stock_id=c.id AND p.trade_date<='2026-10-02' ORDER BY trade_date DESC LIMIT 1) close_db,
  (SELECT round(avg(p.close*p.volume)/1e6,3) FROM price_daily p WHERE p.stock_id=c.id AND p.trade_date BETWEEN '2026-09-22' AND '2026-10-02') dv_db
FROM cand c WHERE c.cik IN (SELECT cik FROM cand GROUP BY cik HAVING count(*)>1)
ORDER BY c.cik, c.ticker;
