-- #683(09-28 01:14 · 현 편입 1,000) 뒤 들어온 현 편입 = 그 실행이 안 덮은 것. 외국 발행인(20-F/40-F/6-K 판) 여부와 F6(V1) 해당 여부
WITH win AS (SELECT min(trade_date) lo, max(trade_date) hi FROM indicator_trade_date WHERE market_code='US'),
cur AS (SELECT m.id, m.stock_id, m.effective_from FROM universe_membership m
        WHERE m.universe_code='US_TOP1000' AND m.effective_to IS NULL AND m.effective_from > '2026-09-28')
SELECT s.ticker, c.effective_from,
  NOT EXISTS (SELECT 1 FROM universe_membership p, win WHERE p.stock_id=c.stock_id AND p.universe_code='US_TOP1000' AND p.id<>c.id
              AND p.effective_from < c.effective_from AND (p.effective_to IS NULL OR (p.effective_from<=win.hi AND p.effective_to>win.lo))) f6_v1,
  bool_or(f.report_code LIKE '20-F%') f20, bool_or(f.report_code LIKE '40-F%') f40, bool_or(f.report_code LIKE '6-K%') k6,
  max(f.created_at)::timestamp(0) last_created
FROM cur c JOIN stock s ON s.id=c.stock_id
LEFT JOIN financial_statement f ON f.stock_id=c.stock_id
GROUP BY s.ticker, c.effective_from, c.stock_id, c.id ORDER BY 3 DESC, 1;
