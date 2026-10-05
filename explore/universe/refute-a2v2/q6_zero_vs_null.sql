SET statement_timeout='60s';
-- refute-a2/q1의 249(재무 있음 · 2년 안 양수 주식수 0)를 「0 행이 있다(관문 '주식수 0' 행의 모양)」 대 「전부 NULL」로 가른다
WITH q AS (
  SELECT s.id, s.ticker, s.sic_code
  FROM stock s JOIN financial_statement f ON f.stock_id=s.id
  WHERE s.market_code='US' AND s.is_active
  GROUP BY s.id, s.ticker, s.sic_code
  HAVING count(*) FILTER (WHERE f.shares_outstanding > 0 AND f.period_end >= DATE '2024-10-05') = 0)
SELECT
  count(*) total,
  count(*) FILTER (WHERE EXISTS (SELECT 1 FROM financial_statement f WHERE f.stock_id=q.id AND f.shares_outstanding = 0)) has_zero_rows,
  count(*) FILTER (WHERE NOT EXISTS (SELECT 1 FROM financial_statement f WHERE f.stock_id=q.id AND f.shares_outstanding IS NOT NULL)) all_null,
  count(*) FILTER (WHERE EXISTS (SELECT 1 FROM financial_statement f WHERE f.stock_id=q.id AND f.shares_outstanding > 0)) has_old_positive,
  count(*) FILTER (WHERE coalesce(sic_code,'') NOT IN ('6770','6798')) non_spac_reit,
  string_agg(CASE WHEN EXISTS (SELECT 1 FROM financial_statement f WHERE f.stock_id=q.id AND f.shares_outstanding = 0) AND coalesce(sic_code,'') NOT IN ('6770','6798') THEN ticker END, ' ') zero_nonspac
FROM q;
SELECT count(DISTINCT f.stock_id) gate_zero_stocks, count(*) gate_zero_rows FROM financial_statement f JOIN stock s ON s.id=f.stock_id WHERE s.market_code='US' AND f.shares_outstanding = 0;
