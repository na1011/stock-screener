\i base.sql
\pset format unaligned
\pset fieldsep '\t'
-- tickers whose own share count exists but is gated, while a same-CIK sibling passes (gate-bypass population if borrowing keys on post-gate map)
WITH g AS (SELECT lc.*, sc.shares, sc.cur_mis, sc.sic_code sc_sic, sc.stock_id IS NOT NULL has_sc,
   (sc.stock_id IS NOT NULL AND NOT sc.cur_mis AND coalesce(sc.sic_code,'') NOT IN ('6798','6770')) pass FROM lc LEFT JOIN sc ON sc.stock_id=lc.id WHERE lc.cik IS NOT NULL)
SELECT r.cik, r.ticker, r.name, r.sc_sic, r.cur_mis,
  (SELECT string_agg(s.ticker||'/'||coalesce(s.sc_sic,'-')||'/'||s.cur_mis, ' ') FROM g s WHERE s.cik=r.cik AND s.pass) passing_sibling
FROM g r WHERE r.has_sc AND NOT r.pass AND EXISTS (SELECT 1 FROM g s WHERE s.cik=r.cik AND s.pass) ORDER BY 1,2;
-- SIC mismatch within CIK among listed
SELECT lc.cik, string_agg(lc.ticker||':'||coalesce(lc.sic_code,'-'), ' ' ORDER BY lc.ticker) FROM lc WHERE lc.cik IS NOT NULL GROUP BY lc.cik HAVING count(DISTINCT coalesce(lc.sic_code,'-'))>1 ORDER BY 1;
