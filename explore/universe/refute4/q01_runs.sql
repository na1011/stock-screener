-- 최근 실행 이력(10-03 이후) — 오늘 배포 뒤 무엇이 돌았나
SELECT id, job_name, triggered_by, status, started_at AT TIME ZONE 'Asia/Seoul' AS started_kst,
       finished_at - started_at AS dur, target_count, success_count, failure_count, left(coalesce(note,''),160) AS note
FROM batch_execution
WHERE started_at >= '2026-10-02'
ORDER BY started_at;
