SET statement_timeout='60s';
-- 미국 stock 전수: 2년 안 주식수(최신 기·개정본) × DB 마지막 종가 · 마지막 봉 날짜 · SIC
WITH sh AS (
  SELECT DISTINCT ON (f.stock_id) f.stock_id, f.shares_outstanding
  FROM financial_statement f JOIN stock s ON s.id=f.stock_id
  WHERE s.market_code='US' AND f.shares_outstanding>0 AND f.period_end >= DATE '2024-10-02' AND f.published_at <= DATE '2026-10-02'
  ORDER BY f.stock_id, f.period_end DESC, f.published_at DESC, f.revision_no DESC),
px AS (
  SELECT DISTINCT ON (p.stock_id) p.stock_id, p.trade_date, p.close AS close_price
  FROM price_daily p JOIN stock s ON s.id=p.stock_id WHERE s.market_code='US'
  ORDER BY p.stock_id, p.trade_date DESC)
SELECT s.ticker, coalesce(s.sic_code,''), coalesce(sh.shares_outstanding::text,''), coalesce(px.trade_date::text,''), coalesce(px.close_price::text,''),
  CASE WHEN sh.shares_outstanding IS NOT NULL AND px.close_price IS NOT NULL THEN round(sh.shares_outstanding*px.close_price/1e6)::text ELSE '' END cap_m,
  EXISTS (SELECT 1 FROM universe_membership m WHERE m.stock_id=s.id AND m.universe_code='US_TOP1000' AND m.effective_to IS NULL) cur_member
FROM stock s LEFT JOIN sh ON sh.stock_id=s.id LEFT JOIN px ON px.stock_id=s.id
WHERE s.market_code='US';
