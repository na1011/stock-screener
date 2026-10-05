-- 편입 행의 모양: 유니버스 · origin별 행 수 · 지금 편입 수 · 창 경계
SELECT m.universe_code, m.origin, count(*) rows_, count(*) FILTER (WHERE m.effective_to IS NULL) open_rows,
       min(m.effective_from), max(m.effective_from)
FROM universe_membership m GROUP BY 1,2 ORDER BY 1,2;
SELECT market_code, min(trade_date) lo, max(trade_date) hi, count(*) FROM indicator_trade_date GROUP BY 1 ORDER BY 1;
SELECT code, is_active FROM universe ORDER BY 1;
