\i base.sql
\pset format unaligned
\pset fieldsep '\t'
SELECT count(*) adr_named_pass,
  count(*) FILTER (WHERE upper(lc.name) ~ 'DEPOSITORY') misspelled_pass
FROM lc JOIN sc ON sc.stock_id=lc.id
WHERE upper(lc.name) ~ 'AMERICAN DEPOSIT[AO]RY' AND upper(lc.name) !~ '(PREFERRED|PREFERENCE)' AND NOT sc.cur_mis AND coalesce(sc.sic_code,'') NOT IN ('6798','6770');
SELECT string_agg(lc.ticker, ' ' ORDER BY sc.shares DESC) FROM lc JOIN sc ON sc.stock_id=lc.id
WHERE upper(lc.name) ~ 'AMERICAN DEPOSITARY' AND upper(lc.name) !~ '(PREFERRED|PREFERENCE)' AND NOT sc.cur_mis AND coalesce(sc.sic_code,'') NOT IN ('6798','6770');
SELECT lc.ticker, (lc.id IN (SELECT stock_id FROM top)) in_top FROM lc JOIN sc ON sc.stock_id=lc.id WHERE upper(lc.name) ~ 'DEPOSITORY' AND NOT sc.cur_mis;
