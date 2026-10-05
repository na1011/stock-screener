\i base.sql
\pset format unaligned
\pset fieldsep '\t'
WITH g AS (SELECT lc.*, sc.shares, (sc.stock_id IS NOT NULL AND NOT sc.cur_mis AND coalesce(sc.sic_code,'') NOT IN ('6798','6770')) pass,
   upper(lc.name) ~ '\m(PREFERRED|PFD|WARRANTS?|RIGHTS?|DEPOSITARY|NOTES?|DEBENTURES?|BONDS?|ETFS?|FUNDS?)\M' nonc FROM lc LEFT JOIN sc ON sc.stock_id=lc.id WHERE lc.cik IS NOT NULL)
SELECT a.cik, string_agg(a.ticker||(CASE WHEN a.pass AND NOT a.nonc THEN '[CAND]' WHEN a.pass AND a.nonc THEN '[NAMEX]' WHEN a.shares IS NULL THEN '[NOSH]' ELSE '[GATED]' END)||' '||left(a.name,60), ' | ' ORDER BY a.ticker)
FROM g a WHERE a.cik IN (
  SELECT cik FROM g GROUP BY cik HAVING bool_or(pass AND NOT nonc) AND bool_or(pass AND nonc AND upper(name) ~ '(AMERICAN DEPOSITARY|ORDINARY|COMMON)'))
GROUP BY a.cik ORDER BY 1;
