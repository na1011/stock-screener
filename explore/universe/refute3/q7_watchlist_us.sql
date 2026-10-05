SELECT s.market_code, count(*) FROM watchlist_item w JOIN stock s ON s.id=w.stock_id GROUP BY 1;
-- 저장 화면 표: 이름에 screen/preset이 든 표와 행 수
SELECT relname, n_live_tup FROM pg_stat_user_tables WHERE relname ~ '(screen|preset|saved|backtest)' ORDER BY 1;
