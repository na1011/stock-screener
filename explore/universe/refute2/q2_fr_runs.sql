-- financial-refill 실행: 대상 수가 큰 것(전량에 가까운 것)과 최근 것
SELECT id, target_date, status, triggered_by, started_at, finished_at, target_count, success_count, failure_count,
       left(note, 160) AS note
FROM batch_execution WHERE job_name='financial-refill' AND (target_count >= 100 OR started_at > '2026-09-25')
ORDER BY started_at;
SELECT triggered_by, status, count(*), max(started_at) FROM batch_execution WHERE job_name='financial-refill' GROUP BY 1,2 ORDER BY 1,2;
