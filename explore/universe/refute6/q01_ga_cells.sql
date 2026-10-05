-- (가): 미국 현 편입 중 최신 기말이 2026-03-01~04-10에 멈춘 80종목(refute5/q04 술어 그대로)의
-- FUNDAMENTAL 지표 칸 — 6월 분기 10-Q 법정 기한(비가속 45일 = 08-14) 뒤 거래일만(하한).
SET statement_timeout='60s';
WITH cur AS (SELECT DISTINCT s.id, s.ticker FROM universe_membership m JOIN stock s ON s.id=m.stock_id
             JOIN universe u ON u.code=m.universe_code AND u.is_active WHERE m.effective_to IS NULL AND s.is_active AND s.market_code='US'),
f AS (SELECT stock_id, max(period_end) pe FROM financial_statement GROUP BY 1),
ga AS (SELECT c.id FROM cur c JOIN f ON f.stock_id=c.id WHERE f.pe BETWEEN date '2026-03-01' AND date '2026-04-10')
SELECT (SELECT count(*) FROM ga) n_stock,
       count(*) cells_after_0817,
       count(DISTINCT iv.trade_date) days,
       count(*) FILTER (WHERE iv.trade_date = date '2026-10-02') cells_latest_day,
       count(DISTINCT iv.indicator_code) codes
FROM indicator_value iv JOIN indicator i ON i.code=iv.indicator_code AND i.category='FUNDAMENTAL'
WHERE iv.stock_id IN (SELECT id FROM ga) AND iv.trade_date >= date '2026-08-17';
