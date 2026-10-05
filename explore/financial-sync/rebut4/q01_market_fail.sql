-- 잡별 실행 · 시장별 실패 행 수 (FR·FSR·G · target_count >= 100)
SELECT e.id, e.job_name, e.triggered_by, e.status, to_char(e.started_at,'MM-DD HH24:MI') st,
       e.target_count tc, e.success_count sc, e.failure_count fc,
       (SELECT count(*) FROM batch_execution_failure f WHERE f.execution_id=e.id) frows,
       (SELECT string_agg(m||':'||n, ',' ORDER BY m) FROM (
           SELECT s.market_code m, count(*) n FROM batch_execution_failure f JOIN stock s ON s.id=f.stock_id
           WHERE f.execution_id=e.id GROUP BY s.market_code) x) fail_by_mkt,
       (SELECT string_agg(k||':'||n, ',' ORDER BY k) FROM (
           SELECT f.failure_kind k, count(*) n FROM batch_execution_failure f
           WHERE f.execution_id=e.id GROUP BY f.failure_kind) y) kinds,
       e.retried_from_id rf
FROM batch_execution e
WHERE e.job_name IN ('financial-refill','full-statement-refill','governance-load')
  AND e.target_count >= 100
ORDER BY e.job_name, e.id;
