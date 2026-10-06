-- 평일 13:30 정기 일일 갱신(③)이 실패 종목을 남긴 날 — 결정 3 「재시도 대기」가 15:00·17:00에 ⑧을 다시 돌릴 날의 상한
SELECT job_name, count(*) AS runs,
       count(*) FILTER (WHERE failure_count > 0) AS with_failures,
       count(*) FILTER (WHERE status = 'PARTIAL_SUCCESS') AS partial,
       min(started_at)::date AS first, max(started_at)::date AS last
FROM batch_execution
WHERE triggered_by = 'SCHEDULER' AND job_name = 'daily-update' AND started_at >= '2026-08-31'
GROUP BY 1;
SELECT id, (started_at AT TIME ZONE 'Asia/Seoul')::timestamp(0) AS kst, status, target_count, failure_count, left(coalesce(note,''),120) AS note
FROM batch_execution
WHERE triggered_by = 'SCHEDULER' AND job_name = 'daily-update' AND started_at >= '2026-08-31' AND failure_count > 0
ORDER BY id;
