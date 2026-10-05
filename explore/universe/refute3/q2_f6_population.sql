-- 계획 v3 결정 2의 술어: 활성 유니버스 · 지금 편입(열린 행) · 같은 유니버스에 보존 창과 겹치는 앞선 편입 행이 없다
-- (a) 「앞선 행 = effective_from < 지금 행」 · 창과 겹침은 IN_RETENTION_WINDOW와 같은 꼴
WITH w AS (SELECT market_code, min(trade_date) lo, max(trade_date) hi FROM indicator_trade_date GROUP BY 1),
cur AS (
  SELECT m.stock_id, m.universe_code, m.effective_from, s.ticker, s.market_code
  FROM universe_membership m JOIN stock s ON s.id=m.stock_id JOIN universe u ON u.code=m.universe_code AND u.is_active
  WHERE m.effective_to IS NULL AND s.is_active)
SELECT c.universe_code, c.ticker, c.effective_from,
  (SELECT count(*) FROM universe_membership p WHERE p.stock_id=c.stock_id AND p.universe_code=c.universe_code AND p.effective_from < c.effective_from) prior_any,
  (SELECT max(p.effective_to) FROM universe_membership p WHERE p.stock_id=c.stock_id AND p.universe_code=c.universe_code AND p.effective_from < c.effective_from) prior_last_to,
  (SELECT count(*) FROM universe_membership p WHERE p.stock_id=c.stock_id AND p.universe_code<>c.universe_code) other_univ_rows
FROM cur c JOIN w ON w.market_code=c.market_code
WHERE NOT EXISTS (SELECT 1 FROM universe_membership p WHERE p.stock_id=c.stock_id AND p.universe_code=c.universe_code
                    AND p.effective_from < c.effective_from
                    AND p.effective_to > w.lo)
ORDER BY 1,3,2;
