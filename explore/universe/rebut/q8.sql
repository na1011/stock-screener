\i base.sql
\pset format unaligned
\pset fieldsep '\t'
SELECT lc.ticker, lc.name, lc.cik, round(sc.shares) shares, sc.cur_mis, sc.sic_code,
  (SELECT p.close FROM price_daily p WHERE p.stock_id=lc.id ORDER BY trade_date DESC LIMIT 1) close_db,
  (lc.id IN (SELECT stock_id FROM top)) in_top
FROM lc JOIN sc ON sc.stock_id=lc.id
WHERE upper(lc.name) ~ 'DEPOSIT[AO]RY' AND NOT sc.cur_mis AND coalesce(sc.sic_code,'') NOT IN ('6798','6770')
ORDER BY sc.shares DESC;
