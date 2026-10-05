-- 최신일 지표 코드가 60 미만인 현 편입(미국 10-02 · 국내 10-01)과 그 편입 시작
WITH latest AS (SELECT market_code, max(trade_date) d FROM indicator_trade_date GROUP BY 1),
cur AS (SELECT m.stock_id, s.ticker, s.market_code, m.effective_from FROM universe_membership m JOIN universe u ON u.code=m.universe_code AND u.is_active
        JOIN stock s ON s.id=m.stock_id WHERE m.effective_to IS NULL)
SELECT c.ticker, c.market_code, c.effective_from, k.n
FROM cur c JOIN latest l ON l.market_code=c.market_code
JOIN LATERAL (SELECT count(*) n FROM indicator_value iv WHERE iv.stock_id=c.stock_id AND iv.trade_date=l.d) k ON true
WHERE k.n < 60 ORDER BY k.n;
