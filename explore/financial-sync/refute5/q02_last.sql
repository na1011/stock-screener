-- 잡별: SCHEDULER 성공 최신 · 폴백(<>GAP_FILL) 성공 최신 · 그 직전 몇 건
WITH s AS (
 SELECT job_name, id, triggered_by, status, started_at, target_count, success_count, failure_count, note,
   row_number() OVER (PARTITION BY job_name ORDER BY started_at DESC) rn
 FROM batch_execution
 WHERE status IN ('SUCCESS','PARTIAL_SUCCESS') AND triggered_by <> 'GAP_FILL'
   AND job_name IN ('financial-refill','full-statement-refill','governance-load','governance-derive','market-calendar','daily-update','corporate-action','adjustment-rebuild','indicator-origin-backfill','indicator-derived-rebuild','universe-rebalance','company-profile')
)
SELECT job_name, rn, id, triggered_by, status, started_at AT TIME ZONE 'Asia/Seoul' st, target_count t, success_count s, failure_count f, left(note,70) note
FROM s WHERE rn <= 6 ORDER BY job_name, rn;
SELECT job_name, max(started_at) AT TIME ZONE 'Asia/Seoul' last_sched FROM batch_execution
WHERE status IN ('SUCCESS','PARTIAL_SUCCESS') AND triggered_by='SCHEDULER' GROUP BY job_name ORDER BY 1;
SELECT DISTINCT job_name FROM batch_execution ORDER BY 1;
