-- 재무 갱신 실행 이력(09-10 이후) — 과거 편입(208)을 덮은 실행은 #644·#654(09-21·22 손)뿐
SELECT id, job_name, target_date, triggered_by, status, target_count, started_at::timestamp(0), left(note,120)
FROM batch_execution WHERE job_name IN ('financial-refill','initial-load','full-statement-refill') AND started_at > '2026-09-10' ORDER BY id;
