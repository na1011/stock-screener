-- F6 술어 변형별 크기(시장별). cur = 활성 유니버스 · 열린 행 · 활성 종목
WITH win AS (SELECT market_code, min(trade_date) lo, max(trade_date) hi FROM indicator_trade_date GROUP BY 1),
cur AS (
  SELECT m.id, m.stock_id, m.universe_code, m.effective_from, s.market_code
  FROM universe_membership m JOIN stock s ON s.id=m.stock_id
  JOIN universe u ON u.code=m.universe_code AND u.is_active
  WHERE m.effective_to IS NULL AND s.is_active
),
flags AS (
  SELECT c.*, w.lo,
   EXISTS (SELECT 1 FROM universe_membership m JOIN universe u ON u.code=m.universe_code AND u.is_active
           WHERE m.stock_id=c.stock_id AND m.id<>c.id AND m.effective_from<c.effective_from
             AND (m.effective_to IS NULL OR (m.effective_from<=w.hi AND m.effective_to>w.lo))) v1_active_window_any,
   EXISTS (SELECT 1 FROM universe_membership m JOIN universe u ON u.code=m.universe_code AND u.is_active
           WHERE m.stock_id=c.stock_id AND m.id<>c.id AND m.effective_from<c.effective_from AND m.origin='OBSERVED'
             AND (m.effective_to IS NULL OR (m.effective_from<=w.hi AND m.effective_to>w.lo))) v2_active_window_observed,
   EXISTS (SELECT 1 FROM universe_membership m
           WHERE m.stock_id=c.stock_id AND m.id<>c.id AND m.effective_from<c.effective_from
             AND (m.effective_to IS NULL OR (m.effective_from<=w.hi AND m.effective_to>w.lo))) v3_all_universes_window,
   EXISTS (SELECT 1 FROM universe_membership m JOIN universe u ON u.code=m.universe_code AND u.is_active
           WHERE m.stock_id=c.stock_id AND m.id<>c.id AND m.effective_from<c.effective_from) v4_active_ever,
   EXISTS (SELECT 1 FROM universe_membership m
           WHERE m.stock_id=c.stock_id AND m.id<>c.id AND m.universe_code=c.universe_code AND m.effective_from<c.effective_from
             AND (m.effective_to IS NULL OR (m.effective_from<=w.hi AND m.effective_to>w.lo))) v5_same_universe_window
  FROM cur c JOIN win w ON w.market_code=c.market_code
)
SELECT market_code,
  count(*) current_total,
  count(*) FILTER (WHERE NOT v1_active_window_any) v1_plan,
  count(*) FILTER (WHERE NOT v2_active_window_observed) v2_observed_only,
  count(*) FILTER (WHERE NOT v3_all_universes_window) v3_incl_inactive_universes,
  count(*) FILTER (WHERE NOT v4_active_ever) v4_no_window,
  count(*) FILTER (WHERE NOT v5_same_universe_window) v5_same_universe,
  count(*) FILTER (WHERE NOT v1_active_window_any AND effective_from > lo) v1_and_entry_in_window
FROM flags GROUP BY 1 ORDER BY 1;
