-- 꼬리 FR이 무효화하면 토 20:00~월 13:30 동안 비는 지표 행 — 10-10에 대표로 들 재편입 형제(보존 창에 과거 편입이 있는 것)
SELECT s.ticker, count(*) iv_rows, min(iv.trade_date), max(iv.trade_date), count(DISTINCT iv.trade_date) days
FROM stock s JOIN indicator_value iv ON iv.stock_id=s.id
WHERE s.market_code='US' AND s.ticker IN ('TAP','MOG.A','WSO','BF.B','MKC','PPL','RUSHA','Z','BTSG','NWSA','UHAL.B')
GROUP BY s.ticker ORDER BY 1;
