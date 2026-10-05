\set ON_ERROR_STOP 1
CREATE TEMP TABLE cik(cik bigint, ticker text, name text, exch text);
\copy cik FROM 'cik.csv' CSV
CREATE TEMP TABLE sc AS
WITH latest AS (
  SELECT DISTINCT ON (f.stock_id) f.stock_id, f.shares_outstanding, f.period_end, f.published_at, f.share_basis_multiple, f.share_basis_status
  FROM financial_statement f JOIN stock s ON s.id = f.stock_id
  WHERE s.market_code='US' AND s.is_active AND f.published_at <= DATE '2026-10-02'
    AND f.shares_outstanding IS NOT NULL AND f.shares_outstanding > 0 AND f.period_end >= DATE '2024-10-02'
  ORDER BY f.stock_id, f.period_end DESC, f.published_at DESC, f.revision_no DESC)
SELECT s.id stock_id, s.ticker, latest.shares_outstanding * coalesce(latest.share_basis_multiple,1) *
  coalesce((SELECT exp(sum(ln(ca.ratio))) FROM corporate_action ca WHERE ca.stock_id=latest.stock_id AND ca.status='APPLIED' AND ca.effective_date > latest.published_at AND ca.effective_date <= DATE '2026-10-02'),1) shares,
  s.sic_code, latest.period_end, latest.published_at,
  EXISTS (SELECT 1 FROM financial_statement mm JOIN stock g ON g.id=mm.stock_id JOIN market gm ON gm.code=g.market_code WHERE mm.stock_id=latest.stock_id AND mm.currency_code <> gm.currency_code) cur_mis
FROM latest JOIN stock s ON s.id=latest.stock_id WHERE latest.share_basis_status IS DISTINCT FROM 'UNRESOLVED';
CREATE TEMP TABLE listed AS
SELECT s.id, s.ticker, s.name, s.sic_code FROM stock s WHERE s.market_code='US' AND s.is_active
 AND EXISTS (SELECT 1 FROM universe_membership m WHERE m.stock_id=s.id AND m.universe_code='US_LISTED' AND m.effective_to IS NULL);
CREATE TEMP TABLE lc AS
SELECT l.*, coalesce(c1.cik, c2.cik) cik FROM listed l LEFT JOIN cik c1 ON c1.ticker=l.ticker LEFT JOIN cik c2 ON c1.cik IS NULL AND c2.ticker=replace(l.ticker,'.','-');
CREATE TEMP TABLE top AS
SELECT DISTINCT stock_id FROM universe_membership WHERE universe_code='US_TOP1000' AND effective_to IS NULL;
CREATE TEMP TABLE topwin AS
SELECT DISTINCT m.stock_id FROM universe_membership m WHERE m.universe_code='US_TOP1000'
 AND (m.effective_to IS NULL OR (m.effective_from <= (SELECT max(trade_date) FROM indicator_trade_date WHERE market_code='US') AND m.effective_to > (SELECT min(trade_date) FROM indicator_trade_date WHERE market_code='US')));
