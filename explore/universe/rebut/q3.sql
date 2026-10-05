\i base.sql
\pset format unaligned
\pset fieldsep '\t'
-- Decision 2 recipients: listed, has CIK, no own share count (pre- or post-gate), sibling in same CIK has passing share count
WITH g AS (
  SELECT lc.*, sc.shares, sc.cur_mis, sc.sic_code sc_sic,
     (sc.stock_id IS NOT NULL) has_sc,
     (sc.stock_id IS NOT NULL AND NOT sc.cur_mis AND coalesce(sc.sic_code,'') NOT IN ('6798','6770')) pass
  FROM lc LEFT JOIN sc ON sc.stock_id = lc.id WHERE lc.cik IS NOT NULL),
nm AS (SELECT g.*, upper(name) ~ '\m(PREFERRED|PFD|WARRANTS?|RIGHTS?|DEPOSITARY|NOTES?|DEBENTURES?|BONDS?|ETFS?|FUNDS?)\M' nonc FROM g)
SELECT r.cik, r.ticker, r.name, r.sic_code own_sic, r.nonc name_excl,
  (SELECT string_agg(s.ticker||'('||s.sic_code||','||round(s.shares)||')', ' ' ORDER BY s.ticker) FROM nm s WHERE s.cik=r.cik AND s.pass) donors,
  (SELECT count(DISTINCT s.shares) FROM nm s WHERE s.cik=r.cik AND s.pass) n_distinct_shares,
  (SELECT count(*) FROM price_daily p WHERE p.stock_id=r.id) bars,
  (SELECT round(avg(p.close*p.volume)/1e6,2) FROM (SELECT close, volume FROM price_daily x WHERE x.stock_id=r.id ORDER BY trade_date DESC LIMIT 10) p) dv10m
FROM nm r WHERE NOT r.has_sc AND EXISTS (SELECT 1 FROM nm s WHERE s.cik=r.cik AND s.pass)
ORDER BY r.nonc, r.cik, r.ticker;
