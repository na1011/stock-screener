\i base.sql
\pset format unaligned
\pset fieldsep '\t'
CREATE TEMP TABLE c2 AS SELECT lc.*, sc.shares, (sc.stock_id IS NOT NULL AND NOT sc.cur_mis AND coalesce(sc.sic_code,'') NOT IN ('6798','6770')) pass,
   upper(lc.name) ~ '\m(PREFERRED|PFD|WARRANTS?|RIGHTS?|DEPOSITARY|NOTES?|DEBENTURES?|BONDS?|ETFS?|FUNDS?)\M' nonc,
   (lc.id IN (SELECT stock_id FROM top)) in_top, (lc.id IN (SELECT stock_id FROM topwin)) in_win
 FROM lc JOIN sc ON sc.stock_id=lc.id;
SELECT count(*) groups, sum(n) tickers FROM (SELECT shares, count(*) n FROM c2 GROUP BY shares HAVING count(*)>1) x;
-- identical-share groups spanning >1 CIK (or containing a null CIK) among candidates, touching roster window
SELECT shares, string_agg(ticker||'{'||coalesce(cik::text,'null')||(CASE WHEN in_top THEN ',TOP' WHEN in_win THEN ',WIN' ELSE '' END)||(CASE WHEN NOT pass THEN ',gated' WHEN nonc THEN ',namex' ELSE '' END)||'} '||left(name,45), ' | ' ORDER BY ticker)
FROM c2 GROUP BY shares
HAVING count(*)>1 AND count(DISTINCT coalesce(cik,-id))>1 AND count(*) FILTER (WHERE pass AND NOT nonc) > 1
ORDER BY bool_or(in_win) DESC, shares DESC;
