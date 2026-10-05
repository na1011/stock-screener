-- 재편입 형제(지표 행 보유)의 보존 창 지표 행 수 — 꼬리 FR이 정정·교정을 내면 월 13:30/14:20까지 지워지는 크기의 상한
SET statement_timeout = '120s';
SELECT s.ticker, count(*) AS iv_rows, min(iv.trade_date), max(iv.trade_date), count(DISTINCT iv.trade_date) AS days
FROM indicator_value iv JOIN stock s ON s.id = iv.stock_id
WHERE s.market_code='US' AND s.ticker IN ('TAP','MOG.A','WSO','BF.B','MKC','RUSHA','Z','PPL','NWSA','UHAL.B','BTSG','LLYVA','HEI.A','FOX')
GROUP BY s.ticker ORDER BY s.ticker;
