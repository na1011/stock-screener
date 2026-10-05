-- 관문 재고(오늘 커밋) 문장 대조: GLNG 행 수 · BB 최신 기말
SELECT s.ticker, f.fiscal_period, count(*) FILTER (WHERE f.is_latest) latest_rows, count(*) all_rows, max(f.period_end) max_end, max(f.created_at)::date last_created
FROM financial_statement f JOIN stock s ON s.id=f.stock_id
WHERE s.market_code='US' AND s.ticker IN ('GLNG','BB')
GROUP BY 1,2 ORDER BY 1,2;
