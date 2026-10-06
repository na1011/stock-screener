-- p0 술어(SUCCESS/PARTIAL · scope NOT NARROW · GAP_FILL 제외)로 본 무거운 잡 마지막 성공 실행 5개씩 + 실패 행의 시장
WITH r AS (
 SELECT e.*, row_number() OVER (PARTITION BY job_name ORDER BY started_at DESC) rn
 FROM batch_execution e
 WHERE job_name IN ('financial-refill','full-statement-refill','governance-load','company-profile','corporate-action')
   AND status IN ('SUCCESS','PARTIAL_SUCCESS') AND triggered_by <> 'GAP_FILL')
SELECT r.job_name, r.id, r.triggered_by, r.started_at::timestamp(0), r.target_count, r.success_count, r.failure_count,
  (SELECT string_agg(DISTINCT s.market_code, ',') FROM batch_execution_failure f JOIN stock s ON s.id=f.stock_id WHERE f.execution_id=r.id) fail_mkts,
  left(r.note, 120) note
FROM r WHERE rn <= 5 ORDER BY job_name, started_at DESC;
