-- FR·initial-load 실행 이력 — 대상 수가 큰 것(전량 후보)과 실패
SELECT id, job_name, triggered_by, status, started_at AT TIME ZONE 'Asia/Seoul' AS kst, target_count, success_count, failure_count,
       left(coalesce(note,''),120) note
FROM batch_execution
WHERE job_name IN ('financial-refill') AND started_at >= '2026-09-01'
ORDER BY started_at;
SELECT code, is_active FROM universe ORDER BY code;
