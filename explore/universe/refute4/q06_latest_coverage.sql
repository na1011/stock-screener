-- 오늘 화해·분사 배포 뒤 최신일 커버리지: 현 편입 중 최신 지표일에 지표 행이 있는 종목 수 · 코드 수 중앙값
WITH latest AS (SELECT market_code, max(trade_date) d FROM indicator_trade_date GROUP BY 1),
cur AS (
  SELECT DISTINCT m.stock_id, s.market_code FROM universe_membership m JOIN universe u ON u.code=m.universe_code AND u.is_active
  JOIN stock s ON s.id=m.stock_id WHERE m.effective_to IS NULL)
SELECT c.market_code, l.d, count(*) members,
  count(*) FILTER (WHERE k.n > 0) with_rows,
  percentile_cont(0.5) WITHIN GROUP (ORDER BY coalesce(k.n,0)) median_codes,
  count(*) FILTER (WHERE coalesce(k.n,0) < 60) under60
FROM cur c JOIN latest l ON l.market_code=c.market_code
LEFT JOIN LATERAL (SELECT count(*) n FROM indicator_value iv WHERE iv.stock_id=c.stock_id AND iv.trade_date=l.d) k ON true
GROUP BY 1,2;
