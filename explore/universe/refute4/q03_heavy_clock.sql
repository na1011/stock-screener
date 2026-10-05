-- 무거운 잡 셋의 시계: SCHEDULER 성공 수 · 폴백(GAP_FILL 제외) 마지막 성공
SELECT job_name,
  count(*) FILTER (WHERE triggered_by='SCHEDULER' AND status IN ('SUCCESS','PARTIAL_SUCCESS')) sched_ok,
  max(started_at) FILTER (WHERE triggered_by<>'GAP_FILL' AND status IN ('SUCCESS','PARTIAL_SUCCESS')) AT TIME ZONE 'Asia/Seoul' AS fallback_last,
  (SELECT target_count FROM batch_execution b2 WHERE b2.job_name=b.job_name AND triggered_by<>'GAP_FILL' AND status IN ('SUCCESS','PARTIAL_SUCCESS') ORDER BY started_at DESC LIMIT 1) last_target
FROM batch_execution b
WHERE job_name IN ('financial-refill','full-statement-refill','governance-load')
GROUP BY job_name;
