\i base.sql
\pset format unaligned
\pset fieldsep '\t'
-- tickers dropped from ShareCounts because latest period is UNRESOLVED, or stale (>2y), with a passing same-CIK sibling
WITH latest AS (
  SELECT DISTINCT ON (f.stock_id) f.stock_id, f.share_basis_status, f.period_end
  FROM financial_statement f JOIN stock s ON s.id=f.stock_id
  WHERE s.market_code='US' AND s.is_active AND f.published_at <= DATE '2026-10-02' AND f.shares_outstanding > 0
  ORDER BY f.stock_id, f.period_end DESC, f.published_at DESC, f.revision_no DESC),
g AS (SELECT lc.*, (sc.stock_id IS NOT NULL AND NOT sc.cur_mis AND coalesce(sc.sic_code,'') NOT IN ('6798','6770')) pass, sc.stock_id IS NOT NULL has_sc,
        latest.share_basis_status lstat, latest.period_end lpe FROM lc LEFT JOIN sc ON sc.stock_id=lc.id LEFT JOIN latest ON latest.stock_id=lc.id WHERE lc.cik IS NOT NULL)
SELECT r.cik, r.ticker, left(r.name,70), r.lstat, r.lpe,
  (SELECT string_agg(s.ticker, ' ') FROM g s WHERE s.cik=r.cik AND s.pass) passing_sibling
FROM g r WHERE NOT r.has_sc AND r.lstat IS NOT NULL AND EXISTS (SELECT 1 FROM g s WHERE s.cik=r.cik AND s.pass) ORDER BY 1,2;
SELECT share_basis_status, count(DISTINCT stock_id) FROM financial_statement f JOIN stock s ON s.id=f.stock_id WHERE s.market_code='US' GROUP BY 1;
