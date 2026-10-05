-- F6 상태 술어 변형별 집합 (운영 SELECT)
-- 창: indicator_trade_date 시장별 min/max (IN_RETENTION_WINDOW와 같은 조각)
WITH win AS (
  SELECT market_code, min(trade_date) lo, max(trade_date) hi FROM indicator_trade_date GROUP BY 1
),
cur AS (  -- 현 편입 (활성 유니버스 · 활성 종목 · 수집 시장)
  SELECT m.id, m.stock_id, m.universe_code, m.effective_from, m.origin, s.ticker, s.market_code
  FROM universe_membership m JOIN stock s ON s.id=m.stock_id
  JOIN universe u ON u.code=m.universe_code AND u.is_active
  WHERE m.effective_to IS NULL AND s.is_active
),
prior AS ( -- 앞선 편입 행: 같은 종목 · 활성 유니버스 · 창과 겹침 · 현 행보다 앞
  SELECT c.id cur_id, m.origin, m.universe_code, m.effective_from, m.effective_to
  FROM cur c JOIN universe_membership m ON m.stock_id=c.stock_id AND m.id<>c.id
  JOIN universe u ON u.code=m.universe_code AND u.is_active
  JOIN win w ON w.market_code=c.market_code
  WHERE m.effective_from < c.effective_from
    AND (m.effective_to IS NULL OR (m.effective_from <= w.hi AND m.effective_to > w.lo))
)
SELECT c.market_code, c.ticker, c.universe_code, c.effective_from, c.origin,
  (SELECT count(*) FROM prior p WHERE p.cur_id=c.id) n_prior_any,
  (SELECT count(*) FROM prior p WHERE p.cur_id=c.id AND p.origin='OBSERVED') n_prior_obs,
  (SELECT count(*) FROM prior p WHERE p.cur_id=c.id AND p.origin='RESTORED') n_prior_res,
  (SELECT count(*) FROM universe_membership m2 WHERE m2.stock_id=c.stock_id AND m2.id<>c.id) n_all_rows_any_universe,
  (SELECT string_agg(m2.universe_code||':'||m2.origin||'['||m2.effective_from||','||coalesce(m2.effective_to::text,'')||')', ' ' ORDER BY m2.effective_from)
     FROM universe_membership m2 WHERE m2.stock_id=c.stock_id AND m2.id<>c.id AND m2.universe_code<>'US_LISTED') other_rows
FROM cur c
WHERE NOT EXISTS (SELECT 1 FROM prior p WHERE p.cur_id=c.id)
ORDER BY c.market_code, c.effective_from, c.ticker;
