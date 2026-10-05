-- 창 안 과거 편입(지금 편입 아님 · US_TOP1000) 중 20-F 판을 가진 외국 발행인 — d0b6ca4f(6-K) 뒤 FR을 안 받은 재편입 후보
WITH win AS (SELECT min(trade_date) lo, max(trade_date) hi FROM indicator_trade_date WHERE market_code='US'),
past AS (
  SELECT DISTINCT m.stock_id FROM universe_membership m JOIN stock s ON s.id=m.stock_id, win
  WHERE m.universe_code='US_TOP1000' AND s.is_active
    AND m.effective_to IS NOT NULL AND m.effective_from <= win.hi AND m.effective_to > win.lo
    AND NOT EXISTS (SELECT 1 FROM universe_membership c WHERE c.stock_id=m.stock_id AND c.universe_code='US_TOP1000' AND c.effective_to IS NULL)
)
SELECT s.ticker,
  bool_or(f.report_code LIKE '20-F%') has_20f, bool_or(f.report_code LIKE '6-K%') has_6k, bool_or(f.report_code LIKE '40-F%') has_40f,
  max(f.created_at)::timestamp(0) last_created
FROM past p JOIN stock s ON s.id=p.stock_id JOIN financial_statement f ON f.stock_id=s.id
GROUP BY s.ticker
HAVING bool_or(f.report_code LIKE '20-F%' OR f.report_code LIKE '40-F%' OR f.report_code LIKE '6-K%')
ORDER BY 1;
