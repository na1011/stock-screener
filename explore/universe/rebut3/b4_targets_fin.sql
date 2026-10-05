-- F6 현 집합 7종목의 재무 상태와 실패 이력
SELECT s.ticker, s.market_code, count(f.id) fs_rows, count(f.id) FILTER (WHERE f.is_latest) latest_rows,
  max(f.period_end) FILTER (WHERE f.is_latest) max_period_end, max(f.published_at) max_pub,
  min(f.created_at)::date first_created, max(f.created_at)::date last_created,
  (SELECT count(*) FROM batch_execution_failure bf WHERE bf.stock_id=s.id) fail_rows,
  (SELECT string_agg(DISTINCT bf.failed_phase||':'||left(bf.failure_kind||' '||bf.message,70), ' | ') FROM batch_execution_failure bf WHERE bf.stock_id=s.id) fails
FROM stock s LEFT JOIN financial_statement f ON f.stock_id=s.id
WHERE s.ticker IN ('950260','092870','CVI','INSW','GRAL','IOVA','KOD') AND s.is_active
GROUP BY s.id, s.ticker, s.market_code ORDER BY 2,1;
