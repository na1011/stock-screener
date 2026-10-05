-- 0콜 · 운영 SELECT: 무거운 잡 셋 + BUDGETS 12잡의 현 술어 시계(SCHEDULER 먼저 · 없으면 GAP_FILL 뺀 폴백)
\pset pager off
SELECT job_name,
  max(started_at) FILTER (WHERE triggered_by='SCHEDULER') sched_last,
  max(started_at) FILTER (WHERE triggered_by<>'GAP_FILL') fb_last,
  count(*) FILTER (WHERE triggered_by='SCHEDULER') n_sched,
  count(*) n_succ
FROM batch_execution WHERE status IN ('SUCCESS','PARTIAL_SUCCESS')
GROUP BY 1 ORDER BY 1;
-- 무거운 잡 셋의 최근 성공 행
SELECT id, job_name, triggered_by, status, target_count, failure_count, target_date, started_at, left(coalesce(note,''),160) note
FROM batch_execution WHERE job_name IN ('financial-refill','full-statement-refill','governance-load') AND status IN ('SUCCESS','PARTIAL_SUCCESS')
ORDER BY started_at DESC LIMIT 25;
