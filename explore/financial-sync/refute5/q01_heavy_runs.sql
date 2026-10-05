SELECT id, job_name, status, triggered_by, started_at AT TIME ZONE 'Asia/Seoul' AS started_kst,
       finished_at AT TIME ZONE 'Asia/Seoul' AS fin_kst, target_count, success_count, failure_count, left(note,90) note
FROM batch_execution
WHERE job_name IN ('financial-refill','full-statement-refill','governance-load','governance-derive','market-calendar','initial-load')
  AND started_at > '2026-09-01'
ORDER BY job_name, started_at;
